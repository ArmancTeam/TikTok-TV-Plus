package com.armanc.team

import android.annotation.SuppressLint
import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.webkit.JavascriptInterface
import org.json.JSONObject
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.SafeBrowsingResponse
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.armanc.team.databinding.ActivityMainBinding
import kotlin.math.abs
import kotlin.math.pow

/**
 * Основной экран TikTok TV.
 *
 * Управление с пульта: D-pad перемещает фокус или виртуальный курсор;
 * короткое OK выполняет действие, удержание OK включает/выключает курсор,
 * красная кнопка коротко переключает курсор и при удержании открывает настройки.
 * Зелёная кнопка переключает чистый экран.
 *
 * Миграция сессии: старая и новая система используют один и тот же
 * CookieManager (applicationId не менялся), поэтому рабочая старая сессия
 * автоматически становится новой — ничего не удаляется. Если TikTok
 * редиректит на страницу входа (сессия недействительна), открывается
 * Login WebView. Старые данные не уничтожаются ни на одном этапе.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "Tiktok TV"

        private const val TIKTOK_URL = "https://www.tiktok.com/"

        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Safari/537.36"

        private const val WINDOWS_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Safari/537.36"

        // WebView initial scale hint (percent).
        private const val DESKTOP_SCALE = 90

        // Absolute target page zoom. 0.90 = 90%
        private const val TV_SCALE = 0.90f

        // Audio
        private const val BASS_STRENGTH = 1000

        // Connection retry
        private const val MAX_RETRIES = 5
        private const val BASE_RETRY_DELAY_MS = 2000L

        // Double-back-to-exit window
        private const val EXIT_CONFIRM_WINDOW_MS = 2000L
    }

    private lateinit var binding: ActivityMainBinding

    private var splashHidden = false
    private var splashSoundPlayed = false

    private var tvAudio: TvAudioController? = null
    private var bassEnabled = true

    private val mainHandler = Handler(Looper.getMainLooper())

    private var retryCount = 0
    private var pendingRetry = false

    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    private var lastBackPressTime = 0L
    private var mouseEnabled = false
    private var cleanScreenEnabled = false
    private var feedMuted = false
    // İşaretçinin (hotspot) kök görünüm içindeki konumu, piksel.
    private var pointerX = 0f
    private var pointerY = 0f
    private var lastScrollTime = 0L
    private var redLongPressHandled = false
    private var okLongPressHandled = false
    /** Son uygulanan rehber modu; değişince akış bir kez yeniden yüklenir. */
    private var appliedGuideMode = -1

    /** Login screen launcher. RESULT_OK — сессия изменилась, нужен reload. */
    private val loginLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                // Сессия появилась/изменилась — перезагружаем ленту.
                retryCount = 0
                pendingRetry = false
                hideErrorOverlay()
                binding.webView.loadUrl(TIKTOK_URL)
            }
        }


    /** Ayarlardan dönüş: oturum değiştiyse (giriş/çıkış) akış yeniden yüklenir. */
    private val settingsLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) reloadFeed()
        }

    private fun openSettings() {
        settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
    }

    private fun reloadFeed() {
        retryCount = 0
        pendingRetry = false
        hideErrorOverlay()
        binding.webView.loadUrl(TIKTOK_URL)
    }

    private var voiceRecognizer: SpeechRecognizer? = null

    private val voicePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startNativeVoiceRecognition()
            else Toast.makeText(
                this, getString(R.string.voice_permission_denied), Toast.LENGTH_LONG
            ).show()
        }

    private inner class VoiceSearchBridge {
        @JavascriptInterface
        fun start() {
            runOnUiThread { requestVoiceSearch() }
        }
    }

    private fun requestVoiceSearch() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(
                this, getString(R.string.voice_unavailable), Toast.LENGTH_LONG
            ).show()
            return
        }
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            voicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startNativeVoiceRecognition()
    }

    private fun startNativeVoiceRecognition() {
        try {
            voiceRecognizer?.destroy()
            voiceRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            voiceRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Toast.makeText(
                        this@MainActivity, getString(R.string.voice_listening),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onError(error: Int) {
                    voiceRecognizer?.destroy()
                    voiceRecognizer = null
                    if (error != SpeechRecognizer.ERROR_CLIENT &&
                        error != SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                    ) {
                        Toast.makeText(
                            this@MainActivity, getString(R.string.voice_no_match),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                override fun onResults(results: Bundle?) {
                    val query = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim().orEmpty()

                    voiceRecognizer?.destroy()
                    voiceRecognizer = null

                    if (query.isNotBlank()) {
                        binding.webView.evaluateJavascript(
                            "window.__fixtokVoiceSubmit && " +
                                "window.__fixtokVoiceSubmit(${JSONObject.quote(query)});",
                            null
                        )
                    } else {
                        Toast.makeText(
                            this@MainActivity, getString(R.string.voice_no_match),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                val lang = java.util.Locale.getDefault().toLanguageTag()
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_prompt))
            }
            voiceRecognizer?.startListening(intent)
        } catch (e: Exception) {
            voiceRecognizer?.destroy()
            voiceRecognizer = null
            Log.e(TAG, "Sesli arama başlatılamadı", e)
            Toast.makeText(
                this, getString(R.string.voice_unavailable), Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun injectVoiceSearch(webView: WebView) {
        val js = """
            (() => {
              if (window.__fixtokVoiceSearchInstalled) return;
              window.__fixtokVoiceSearchInstalled = true;

              const selectors = [
                'input[type="search"]',
                'input[placeholder*="Search" i]',
                'input[placeholder*="Ara" i]',
                'input[data-e2e*="search" i]',
                '[data-e2e*="search"] input',
                'form[role="search"] input'
              ];

              function findSearchInput() {
                for (const selector of selectors) {
                  for (const input of document.querySelectorAll(selector)) {
                    const r = input.getBoundingClientRect();
                    if (r.width > 0 && r.height > 0 &&
                        getComputedStyle(input).visibility !== 'hidden') return input;
                  }
                }
                return null;
              }

              function positionButton() {
                const button = document.getElementById('__fixtok_voice_button');
                const input = findSearchInput();
                if (!button) return;
                if (!input) {
                  button.style.display = 'none';
                  return;
                }
                const r = input.getBoundingClientRect();
                button.style.display = 'flex';
                button.style.left = Math.max(0, r.right - 40) + 'px';
                button.style.top = Math.max(0, r.top + (r.height - 36) / 2) + 'px';
              }

              function installButton() {
                if (!document.body) return;
                let button = document.getElementById('__fixtok_voice_button');
                if (!button) {
                  button = document.createElement('button');
                  button.id = '__fixtok_voice_button';
                  button.type = 'button';
                  button.textContent = '🎤';
                  button.setAttribute('aria-label', 'Türkçe sesli ara');
                  button.style.cssText =
                    'position:fixed;z-index:2147483647;width:36px;height:36px;' +
                    'border:0;border-radius:9px;background:#fe2c55;color:white;' +
                    'font-size:20px;display:none;align-items:center;justify-content:center;' +
                    'cursor:pointer;padding:0;box-shadow:0 2px 8px #0006;';
                  button.addEventListener('click', function(event) {
                    event.preventDefault();
                    event.stopPropagation();
                    if (window.FixtokVoice) window.FixtokVoice.start();
                  });
                  document.body.appendChild(button);
                }
                positionButton();
              }

              window.__fixtokVoiceSubmit = function(query) {
                const input = findSearchInput();
                if (!input || !query) return;
                input.focus();
                const setter = Object.getOwnPropertyDescriptor(
                  HTMLInputElement.prototype, 'value'
                );
                if (setter && setter.set) setter.set.call(input, query);
                else input.value = query;
                input.dispatchEvent(new Event('input', {bubbles:true}));
                input.dispatchEvent(new Event('change', {bubbles:true}));

                setTimeout(function() {
                  const form = input.closest('form');
                  const candidates = [
                    form && form.querySelector('button[type="submit"]'),
                    form && form.querySelector('[aria-label*="Search" i]'),
                    document.querySelector('[data-e2e="search-button"]'),
                    document.querySelector('button[aria-label*="Search" i]'),
                    document.querySelector('button[aria-label*="Ara" i]')
                  ].filter(Boolean);
                  if (candidates.length) candidates[0].click();
                  else {
                    input.dispatchEvent(new KeyboardEvent('keydown', {
                      key:'Enter', code:'Enter', keyCode:13, which:13, bubbles:true
                    }));
                    input.dispatchEvent(new KeyboardEvent('keyup', {
                      key:'Enter', code:'Enter', keyCode:13, which:13, bubbles:true
                    }));
                  }
                }, 150);
              };

              installButton();
              new MutationObserver(installButton).observe(
                document.documentElement, {childList:true, subtree:true}
              );
              window.addEventListener('resize', positionButton);
              window.addEventListener('scroll', positionButton, true);
              setInterval(positionButton, 1500);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        loadPreferences()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        enterFullscreen()

        // Android TV audio effects (best effort — see TvAudioController).
        if (bassEnabled) {
            tvAudio = TvAudioController()
            tvAudio?.enable()
            tvAudio?.setBass(BASS_STRENGTH)
        }

        setupWebView()
        animateSplashIn()
        setupTvChrome()
        setupRemote()
        setupVirtualMouse()

        if (savedInstanceState == null) {
            binding.webView.loadUrl(TIKTOK_URL)
        } else {
            binding.webView.restoreState(savedInstanceState)
        }
    }

    /** Wires the TV chrome: settings button, retry button, account status, auto-hiding bottom bar. */
    private fun setupTvChrome() {
        binding.settingsButton.setOnClickListener { openSettings() }
        binding.remoteButton.setOnClickListener { toggleRemotePanel() }
        binding.remoteLauncher.setOnClickListener { showRemote() }
        binding.errorRetryButton.setOnClickListener { retryNow() }
        updateAccountStatus()
    }

    private fun isRemoteOpen(): Boolean =
        binding.remoteInclude.root.visibility == View.VISIBLE

    private fun showRemote() {
        binding.remoteInclude.root.visibility = View.VISIBLE
        applyCleanScreen()
        binding.remoteInclude.remoteOk.post {
            binding.remoteInclude.remoteOk.requestFocus()
        }
    }

    private fun hideRemote() {
        binding.remoteInclude.root.visibility = View.GONE
        applyCleanScreen()
        binding.webView.requestFocus()
    }

    private fun toggleRemotePanel() {
        if (isRemoteOpen()) hideRemote() else showRemote()
    }

    private fun setupRemote() {
        val remote = binding.remoteInclude
        remote.remoteClose.setOnClickListener { hideRemote() }
        remote.remoteUp.setOnClickListener { tapRemoteKey(KeyEvent.KEYCODE_DPAD_UP) }
        remote.remoteDown.setOnClickListener { tapRemoteKey(KeyEvent.KEYCODE_DPAD_DOWN) }
        remote.remoteLeft.setOnClickListener { tapRemoteKey(KeyEvent.KEYCODE_DPAD_LEFT) }
        remote.remoteRight.setOnClickListener { tapRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
        remote.remoteOk.setOnClickListener { tapRemoteOk() }
        remote.remoteBack.setOnClickListener { remoteBack() }
        remote.remoteRed.setOnClickListener { toggleMainMouse() }
        remote.remoteGreen.setOnClickListener { toggleRemotePanel() }
        remote.remoteSearch.setOnClickListener { openTikTokPath("/search") }
        remote.remoteVoice.setOnClickListener {
            hideRemote()
            requestVoiceSearch()
        }
        remote.remoteForYou.setOnClickListener { openTikTokPath("/foryou") }
        remote.remoteFollowing.setOnClickListener { openTikTokPath("/following") }
        remote.remoteExplore.setOnClickListener { openTikTokPath("/explore") }
        remote.remoteLive.setOnClickListener { openTikTokPath("/live") }
        remote.remoteMouse.setOnClickListener { toggleMainMouse() }
        remote.remoteMute.setOnClickListener { toggleMute() }
        remote.remoteQr.setOnClickListener {
            hideRemote()
            loginLauncher.launch(Intent(this, LoginActivity::class.java))
        }
        remote.remoteClean.setOnClickListener { toggleCleanScreen() }
        remote.remoteSettings.setOnClickListener {
            hideRemote()
            openSettings()
        }
        remote.remoteHelp.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.help_title)
                .setMessage(R.string.help_body)
                .setPositiveButton(R.string.common_close, null)
                .show()
        }
    }

    private fun openTikTokPath(path: String) {
        hideRemote()
        retryCount = 0
        pendingRetry = false
        hideErrorOverlay()
        binding.webView.loadUrl("https://www.tiktok.com$path")
    }

    private fun tapRemoteKey(keyCode: Int) {
        if (mouseEnabled && keyCode in listOf(
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT
            )
        ) {
            mouseDpad(keyCode, 0)
            return
        }
        val now = SystemClock.uptimeMillis()
        binding.webView.dispatchKeyEvent(
            KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
        )
        binding.webView.dispatchKeyEvent(
            KeyEvent(now, now + 1, KeyEvent.ACTION_UP, keyCode, 0)
        )
    }

    private fun tapRemoteOk() {
        if (mouseEnabled) clickMainMouse()
        else tapRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    private fun remoteBack() {
        if (fullscreenView != null) exitFullscreenVideo()
        else if (binding.webView.canGoBack()) binding.webView.goBack()
        else hideRemote()
    }

    private fun toggleMute() {
        feedMuted = !feedMuted
        applyFeedMute()
        Toast.makeText(
            this,
            if (feedMuted) R.string.osd_muted else R.string.osd_unmuted,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun applyFeedMute() {
        val flag = if (feedMuted) "true" else "false"
        binding.webView.evaluateJavascript(
            "(function(){document.querySelectorAll('video,audio').forEach(function(el){el.muted=$flag;});})();",
            null
        )
    }

    private fun updateAccountStatus() {
        val loggedIn = LoginActivity.hasSessionCookie(CookieManager.getInstance())
        binding.topBarAccountStatus.setText(
            if (loggedIn) R.string.account_status_logged_in
            else R.string.account_status_logged_out
        )
    }


    private fun loadPreferences() {
        val prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
        bassEnabled = prefs.getBoolean(SettingsActivity.KEY_BASS, true) &&
            prefs.getBoolean(SettingsActivity.KEY_ADVANCED_AUDIO, true)
    }

    private fun prefs() = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)

    /** 0 Android CSS, 1 Wide Windows, 2 None. */
    private fun guideMode(): Int =
        prefs().getInt(SettingsActivity.KEY_GUIDE_MODE, 0).coerceIn(0, 2)

    private fun userAgentForGuide(): String =
        if (guideMode() == 1) WINDOWS_USER_AGENT else DESKTOP_USER_AGENT

    private fun targetPageScale(): Float = if (guideMode() == 1) 1f else TV_SCALE

    private fun initialScalePercent(): Int = if (guideMode() == 1) 100 else DESKTOP_SCALE

    /**
     * Rehber modu ayarını WebView'a uygular. Mod değiştiyse sayfa yeniden yüklenir;
     * aksi halde her onResume'da gereksiz reload olmaz.
     */
    private fun syncGuideMode() {
        val mode = guideMode()
        val ua = userAgentForGuide()
        if (binding.webView.settings.userAgentString != ua) {
            binding.webView.settings.userAgentString = ua
        }
        val changed = appliedGuideMode != -1 && appliedGuideMode != mode
        appliedGuideMode = mode
        if (changed) reloadFeed()
    }

    private fun setupVirtualMouse() {
        val prefs = prefs()
        mouseEnabled = prefs.getBoolean(SettingsActivity.KEY_MOUSE_ENABLED, false)
        cleanScreenEnabled = prefs.getBoolean(SettingsActivity.KEY_CLEAN_SCREEN, false)
        val cursor = binding.mainMouseCursor
        cursor.visibility = if (mouseEnabled) View.VISIBLE else View.GONE
        applyCursorAppearance()
        cursor.post { centerPointerIfNeeded() }
        applyCleanScreen()
    }

    private fun centerPointerIfNeeded() {
        if (pointerX == 0f && pointerY == 0f) {
            pointerX = binding.root.width / 2f
            pointerY = binding.root.height / 2f
        }
        updateMainMousePosition()
    }

    /** Tıklama/hover/kaydırma hedefi: HTML5 tam ekran açıksa onun kapsayıcısı, değilse WebView. */
    private fun pointerTarget(): View =
        if (fullscreenView != null) binding.fullscreenContainer else binding.webView

    private fun moveMainMouse(dx: Float, dy: Float, repeat: Int) {
        val speed = prefs().getInt(SettingsActivity.KEY_MOUSE_SPEED, 5)
        val precision = prefs().getBoolean(SettingsActivity.KEY_CURSOR_PRECISION, true)
        val precisionFactor = if (precision) 0.65f else 1.0f
        val step = VirtualPointer.stepPx(speed, resources.displayMetrics.density) *
            VirtualPointer.accel(repeat) * precisionFactor
        pointerX = (pointerX + dx * step).coerceIn(0f, binding.root.width.toFloat())
        pointerY = (pointerY + dy * step).coerceIn(0f, binding.root.height.toFloat())
        updateMainMousePosition()
        VirtualPointer.hover(pointerTarget(), pointerX, pointerY)
    }

    /**
     * Fare açıkken D-pad. İşaretçi ekranın üst/alt kenarındayken yukarı/aşağı tuşu
     * sayfayı (sonraki/önceki video) kaydırır; aksi halde imleci hareket ettirir.
     */
    private fun mouseDpad(keyCode: Int, repeat: Int) {
        val h = binding.root.height.toFloat()
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP ->
                if (pointerY <= 1f) scrollFeed(1f) else moveMainMouse(0f, -1f, repeat)
            KeyEvent.KEYCODE_DPAD_DOWN ->
                if (pointerY >= h - 1f) scrollFeed(-1f) else moveMainMouse(0f, 1f, repeat)
            KeyEvent.KEYCODE_DPAD_LEFT -> moveMainMouse(-1f, 0f, repeat)
            KeyEvent.KEYCODE_DPAD_RIGHT -> moveMainMouse(1f, 0f, repeat)
        }
    }

    /** vscroll > 0 yukarı, < 0 aşağı. Art arda kaydırmayı sınırlar (tek seferde bir video). */
    private fun scrollFeed(vscroll: Float) {
        val now = SystemClock.uptimeMillis()
        if (now - lastScrollTime < 450L) return
        lastScrollTime = now
        VirtualPointer.scroll(pointerTarget(), pointerX, pointerY, vscroll)
    }

    private fun updateMainMousePosition() {
        val cursor = binding.mainMouseCursor
        cursor.translationX = pointerX - cursor.hotspotX()
        cursor.translationY = pointerY - cursor.hotspotY()
    }

    private fun toggleMainMouse() {
        mouseEnabled = !mouseEnabled
        prefs().edit().putBoolean(SettingsActivity.KEY_MOUSE_ENABLED, mouseEnabled).apply()
        binding.mainMouseCursor.visibility = if (mouseEnabled) View.VISIBLE else View.GONE
        if (mouseEnabled) binding.mainMouseCursor.post { centerPointerIfNeeded() }
        Toast.makeText(this, if (mouseEnabled) R.string.osd_mouse_on else R.string.osd_mouse_off, Toast.LENGTH_SHORT).show()
    }

    private fun clickMainMouse() {
        binding.mainMouseCursor.showClickFeedback()
        VirtualPointer.click(pointerTarget(), pointerX, pointerY)
    }

    private fun applyCursorAppearance() {
        val cursor = binding.mainMouseCursor
        cursor.applyPrefs(prefs())
        // Boyut/şekil değişince hotspot değişir: yerleşimden sonra konumu yenile.
        cursor.post { updateMainMousePosition() }
    }

    private fun applyAmoledTheme() {
        val prefs = getSharedPreferences(
            SettingsActivity.PREFS_NAME, MODE_PRIVATE
        )
        val amoled = prefs.getBoolean(SettingsActivity.KEY_AMOLED, false)
        val background = if (amoled) {
            Color.BLACK
        } else {
            ContextCompat.getColor(this, R.color.fixtok_background)
        }

        binding.root.setBackgroundColor(background)
        binding.webView.setBackgroundColor(background)
    }

    private fun applyCleanScreen() {
        binding.topBar.visibility = if (cleanScreenEnabled) View.GONE else View.VISIBLE
        val remoteOpen = isRemoteOpen()
        binding.remoteLauncher.visibility = View.GONE
    }

    private fun toggleCleanScreen() {
        cleanScreenEnabled = !cleanScreenEnabled
        getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(SettingsActivity.KEY_CLEAN_SCREEN, cleanScreenEnabled).apply()
        applyCleanScreen()
        Toast.makeText(this, if (cleanScreenEnabled) R.string.osd_clean_on else R.string.osd_clean_off, Toast.LENGTH_SHORT).show()
    }


    private fun enterFullscreen() {
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.webView
        webView.addJavascriptInterface(VoiceSearchBridge(), "FixtokVoice")

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        webView.isFocusable = true
        webView.isFocusableInTouchMode = true

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            // Вход через Google/Facebook/Apple обрабатывается отдельным
            // LoginActivity; в основном WebView popup-окна не нужны.
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)

            mediaPlaybackRequiresUserGesture = false

            allowFileAccess = false
            allowContentAccess = false

            cacheMode = WebSettings.LOAD_DEFAULT

            useWideViewPort = true
            // Native WebView page zoom only. Do NOT use CSS zoom or
            // View.scaleX/scaleY: TikTok relies heavily on fixed/absolute
            // layout and those can break it.
            loadWithOverviewMode = false

            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false

            textZoom = 100
            defaultFontSize = 16
            defaultFixedFontSize = 16

            userAgentString = userAgentForGuide()

            // Safe Browsing никогда не отключаем ради совместимости.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
        }

        webView.setInitialScale(initialScalePercent())

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                return handleNavigation(request.url)
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(
                view: WebView,
                url: String?
            ): Boolean {
                val uri = url?.let {
                    runCatching { android.net.Uri.parse(it) }.getOrNull()
                } ?: return true
                return handleNavigation(uri)
            }

            /**
             * Навигация в основном WebView. Разрешены только tiktok.com и
             * его поддомены; редирект на /login означает недействительную
             * сессию — открываем LoginActivity (сценарий C ТЗ). Внешние
             * ссылки открываем системным браузером, если он есть.
             */
            private fun handleNavigation(uri: android.net.Uri): Boolean {
                val scheme = uri.scheme?.lowercase()
                val host = uri.host?.lowercase()

                if (scheme != "https" && scheme != "http") return true

                val isTikTok =
                    host == "tiktok.com" || host?.endsWith(".tiktok.com") == true
                if (!isTikTok) {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                    }
                    return true
                }

                if (isLoginUrl(uri)) {
                    // Oturum düşmüş — sorgu parametreli /login adresleri de dahil.
                    loginLauncher.launch(
                        Intent(this@MainActivity, LoginActivity::class.java)
                    )
                    return true
                }

                return false
            }

            /** TikTok giriş yolu: /login ve /login/... (sorgu dizesi fark etmez). */
            private fun isLoginUrl(uri: android.net.Uri): Boolean {
                val path = uri.path?.trimEnd('/') ?: return false
                return path == "/login" || path.startsWith("/login/")
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)

                retryCount = 0
                pendingRetry = false
                hideErrorOverlay()

                injectSafeWebAudioBass(view)
                if (guideMode() == 0) injectChromeHide(view)
                injectVoiceSearch(view)
                if (feedMuted) applyFeedMute()

                // setInitialScale — только стартовая подсказка. Реальный
                // зум применяем один раз относительно текущего масштаба,
                // чтобы 0.90 был абсолютным, а не мультипликативным.
                view.postDelayed({ applyTvZoom(view) }, 350)

                if (!splashHidden) {
                    hideSplash()
                }
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: android.webkit.WebResourceResponse
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                Log.w(
                    TAG,
                    "HTTP ${errorResponse.statusCode} ${errorResponse.reasonPhrase}; " +
                        "mainFrame=${request.isForMainFrame}; url=${request.url}"
                )
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    scheduleRetry(view)
                }
            }

            // Тот же обработчик для API 21–22.
            @Deprecated("Deprecated in Java")
            override fun onReceivedError(
                view: WebView,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                scheduleRetry(view)
            }

            override fun onSafeBrowsingHit(
                view: WebView,
                request: WebResourceRequest,
                threatType: Int,
                callback: SafeBrowsingResponse
            ) {
                Log.w(TAG, "Safe Browsing threat: $threatType ${request.url}")
                super.onSafeBrowsingHit(view, request, threatType, callback)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {

            override fun onShowCustomView(
                view: View,
                callback: CustomViewCallback
            ) {
                fullscreenView = view
                fullscreenCallback = callback

                binding.fullscreenContainer.addView(
                    view,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
                binding.fullscreenContainer.visibility = View.VISIBLE
            }

            override fun onHideCustomView() {
                exitFullscreenVideo()
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                // Камера/микрофон приложению не нужны — запросы отклоняются,
                // никакие ресурсы не грантятся автоматически.
                runOnUiThread {
                    runCatching { request.deny() }
                }
            }
        }

        webView.requestFocus()
        appliedGuideMode = guideMode()
    }

    /** Exit HTML5 fullscreen video, if it is currently shown. */
    private fun exitFullscreenVideo() {
        fullscreenView?.let {
            binding.fullscreenContainer.removeView(it)
        }
        fullscreenView = null
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        binding.fullscreenContainer.visibility = View.GONE
    }

    private fun scheduleRetry(view: WebView) {
        if (pendingRetry) return

        if (retryCount >= MAX_RETRIES) {
            showErrorOverlay(getString(R.string.error_no_connection_final))
            return
        }

        showErrorOverlay(getString(R.string.error_no_connection))

        pendingRetry = true

        val delay = BASE_RETRY_DELAY_MS * 2.0.pow(retryCount).toLong()
        retryCount++

        mainHandler.postDelayed({
            pendingRetry = false
            view.loadUrl(TIKTOK_URL)
        }, delay)
    }

    private fun showErrorOverlay(message: String) {
        // Never leave the launch overlay covering the retry/error state.
        hideSplash()
        binding.errorText.text = message
        binding.errorContainer.visibility = View.VISIBLE
    }

    private fun hideErrorOverlay() {
        binding.errorContainer.visibility = View.GONE
    }

    /** Ручной повтор, когда показан финальный оверлей «Press OK to retry». */
    private fun retryNow() {
        retryCount = 0
        pendingRetry = false
        hideErrorOverlay()
        binding.webView.loadUrl(TIKTOK_URL)
    }

    private fun applyTvZoom(webView: WebView) {
        val target = targetPageScale()

        try {
            webView.settings.setSupportZoom(true)
            webView.settings.builtInZoomControls = true
            webView.settings.displayZoomControls = false
            webView.settings.loadWithOverviewMode = false
            webView.settings.useWideViewPort = true

            webView.setInitialScale(initialScalePercent())

            webView.postDelayed({
                val afterInitial = webView.scale
                if (afterInitial > 0.01f && abs(afterInitial - target) > 0.02f) {
                    val factor = target / afterInitial
                    if (factor in 0.5f..2.0f && abs(factor - 1f) > 0.01f) {
                        webView.zoomBy(factor)
                        Log.d(TAG, "Fallback page zoom applied, factor=$factor")
                    }
                }
            }, 250)
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to apply WebView page zoom", t)
        }
    }

    /**
     * Bass boost via Web Audio.
     *
     * Perf note: TikTok's feed preloads several adjacent <video> elements
     * at once. Earlier this attached a Web Audio graph (one real-time
     * AudioContext each) to *every* video with a src as soon as it was
     * found, which meant multiple concurrent AudioContexts running for
     * off-screen/paused videos — a plausible source of the reported
     * stutter/frame drops on weaker TV boxes. Now a video is only wired
     * up once it actually starts playing, and its context is suspended
     * (not just left running) the moment it pauses, so at most one
     * AudioContext is normally alive at a time.
     */
    private fun injectSafeWebAudioBass(webView: WebView) {
        val enabled = if (bassEnabled) "true" else "false"
        val js = """
            (() => {
              window.__fixtokBassEnabled = $enabled;
              const BASS_DB = 8;
              const contexts = window.__fixtokBassContexts || new Map();
              window.__fixtokBassContexts = contexts;

              window.__fixtokBassSet = function(on) {
                window.__fixtokBassEnabled = !!on;
                contexts.forEach((ctx) => {
                  if (ctx.__bass) ctx.__bass.gain.value = on ? BASS_DB : 0;
                });
              };

              if (window.__fixtokBassInstalled) {
                window.__fixtokBassSet($enabled);
                return;
              }
              window.__fixtokBassInstalled = true;

              const wired = new WeakSet();
              let scanScheduled = false;

              function sameOrigin(src) {
                try { return new URL(src, location.href).origin === location.origin; }
                catch (_) { return false; }
              }

              function connect(el) {
                if (contexts.has(el)) return;

                // Do NOT connect media that has no CORS permission.
                // MediaElementSource would otherwise silence cross-origin audio.
                const corsOK = el.crossOrigin === 'anonymous' ||
                               el.crossOrigin === 'use-credentials' ||
                               sameOrigin(el.currentSrc || '');
                if (!corsOK) return;

                try {
                  const Ctx = window.AudioContext || window.webkitAudioContext;
                  if (!Ctx) return;
                  const ctx = new Ctx();
                  const source = ctx.createMediaElementSource(el);
                  const bass = ctx.createBiquadFilter();
                  bass.type = 'lowshelf';
                  bass.frequency.value = 140;
                  bass.gain.value = window.__fixtokBassEnabled ? BASS_DB : 0;
                  source.connect(bass);
                  bass.connect(ctx.destination);
                  ctx.__bass = bass;
                  contexts.set(el, ctx);
                } catch (_) {
                  // Never interfere with normal playback if Web Audio is rejected.
                }
              }

              function detach(el) {
                const ctx = contexts.get(el);
                if (!ctx) return;
                try { ctx.close(); } catch (_) {}
                contexts.delete(el);
              }

              function suspend(el) {
                const ctx = contexts.get(el);
                if (ctx) { try { ctx.suspend(); } catch (_) {} }
              }

              function resume(el) {
                // Bas kapalıyken grafiği hiç kurma; aksi halde video sesi Web Audio'ya
                // kalıcı olarak devredilir. Daha önce kurulduysa kazancı sıfırla.
                if (window.__fixtokBassEnabled) connect(el);
                const ctx = contexts.get(el);
                if (!ctx) return;
                if (ctx.__bass) ctx.__bass.gain.value = window.__fixtokBassEnabled ? BASS_DB : 0;
                try { ctx.resume(); } catch (_) {}
              }

              function wire(el) {
                if (!el || wired.has(el)) return;
                wired.add(el);
                // Only build the audio graph once playback actually starts,
                // and only for the element that is actually playing —
                // never for preloaded/off-screen siblings.
                el.addEventListener('playing', () => resume(el), {passive:true});
                el.addEventListener('pause', () => suspend(el), {passive:true});
                el.addEventListener('emptied', () => detach(el), {passive:true});
                if (!el.paused && el.currentSrc) resume(el);
              }

              function scan() {
                document.querySelectorAll('video,audio').forEach(wire);
                contexts.forEach((_, el) => { if (!el.isConnected) detach(el); });
              }

              function scheduleScan() {
                if (scanScheduled) return;
                scanScheduled = true;
                setTimeout(() => { scanScheduled = false; scan(); }, 1000);
              }

              scan();
              new MutationObserver(scheduleScan)
                  .observe(document.documentElement, {childList:true, subtree:true});
              setInterval(scheduleScan, 15000);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    /**
     * Best-effort visual cleanup of TikTok's own chrome (site header,
     * sidebar navigation, footer, cookie/app-install banners) so the
     * WebView reads as "the video, inside FixTok" rather than "the
     * tiktok.com website". This only hides elements with CSS — it does
     * not touch TikTok's markup, scripts, or any private/internal API,
     * and it never hides the player itself.
     *
     * TikTok's own class names are build-generated and can change at any
     * time, so this targets semantic/ARIA landmarks (<header>, <footer>,
     * role="navigation", role="banner") plus a couple of well-known,
     * publicly-documented cookie-banner ids. Coverage is therefore
     * best-effort, not guaranteed — see the final report.
     */
    private fun injectChromeHide(webView: WebView) {
        // Single-quoted attribute selectors only: this string gets embedded
        // inside a double-quoted JS string literal below, so it must not
        // contain any unescaped double quotes.
        val css = """
            header, footer,
            [role='navigation'], [role='banner'],
            #header-more-menu,
            [data-e2e='nav-more-container'], [data-e2e='recommend-account'],
            [data-e2e='video-side-action-bar-download'] {
              display: none !important;
            }
        """.trimIndent().replace("\n", " ")

        val js = """
            (() => {
              if (window.__fixtokChromeHideInstalled) return;
              window.__fixtokChromeHideInstalled = true;

              function ensureStyle() {
                if (document.getElementById('__fixtok_hide_chrome')) return;
                const style = document.createElement('style');
                style.id = '__fixtok_hide_chrome';
                style.textContent = "$css";
                document.head.appendChild(style);
              }

              ensureStyle();
              // TikTok is a single-page app; re-check occasionally in case
              // the <head> is ever rebuilt. Cheap: only touches the DOM
              // when the style tag is actually missing.
              new MutationObserver(ensureStyle)
                  .observe(document.documentElement, {childList: true, subtree: false});
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private var redPressStartedAt = 0L
    private var okPressStartedAt = 0L
    private var okLongAction = 0
    

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val key = event.keyCode

        if (isRemoteOpen()) {
            if (event.action == KeyEvent.ACTION_DOWN &&
                (key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_MENU)
            ) {
                hideRemote()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        if (event.action == KeyEvent.ACTION_DOWN &&
            (key == KeyEvent.KEYCODE_MENU || key == KeyEvent.KEYCODE_INFO)
        ) {
            showRemote()
            return true
        }

        if (event.action == KeyEvent.ACTION_DOWN) {
            when (key) {
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (mouseEnabled) {
                        mouseDpad(key, event.repeatCount)
                        return true
                    }
                }

                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                    if (mouseEnabled) {
                        if (event.repeatCount == 0) scrollFeed(1f)
                        return true
                    }
                }

                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                    if (mouseEnabled) {
                        if (event.repeatCount == 0) scrollFeed(-1f)
                        return true
                    }
                }

                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (event.repeatCount == 0) {
                        okPressStartedAt = SystemClock.elapsedRealtime()
                        okLongAction = 0
                        if (mouseEnabled) {
                            binding.mainMouseCursor.setClickFeedback(true)
                        }
                    } else {
                        val held = SystemClock.elapsedRealtime() - okPressStartedAt

                        if (held >= 1500 && okLongAction == 0) {
                            okLongAction = 1
                            toggleMainMouse()
                        }
                    }
                    return true
                }

                KeyEvent.KEYCODE_PROG_RED, KeyEvent.KEYCODE_BUTTON_X -> {
                    if (event.repeatCount == 0) {
                        redPressStartedAt = SystemClock.elapsedRealtime()
                    }
                    return true
                }

                KeyEvent.KEYCODE_PROG_GREEN, KeyEvent.KEYCODE_BUTTON_Y -> {
                    if (event.repeatCount == 0) toggleRemotePanel()
                    return true
                }

                KeyEvent.KEYCODE_BACK -> {
                    if (fullscreenView != null) {
                        exitFullscreenVideo()
                    } else if (binding.webView.canGoBack()) {
                        binding.webView.goBack()
                    } else {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastBackPressTime <= EXIT_CONFIRM_WINDOW_MS) {
                            finish()
                        } else {
                            lastBackPressTime = now
                            Toast.makeText(
                                this,
                                R.string.press_back_again_to_exit,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    return true
                }
            }
        } else if (event.action == KeyEvent.ACTION_UP) {
            when (key) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    binding.mainMouseCursor.setClickFeedback(false)
                    val held = SystemClock.elapsedRealtime() - okPressStartedAt

                    if (okLongAction == 0 && held < 1500) {
                        if (retryCount >= MAX_RETRIES &&
                            binding.errorContainer.visibility == View.VISIBLE
                        ) {
                            retryNow()
                        } else if (mouseEnabled) {
                            clickMainMouse()
                        } else {
                            val now = SystemClock.uptimeMillis()
                            binding.webView.dispatchKeyEvent(
                                KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0)
                            )
                            binding.webView.dispatchKeyEvent(
                                KeyEvent(now, now + 1, KeyEvent.ACTION_UP, key, 0)
                            )
                        }
                    }
                    okLongAction = 0
                    okPressStartedAt = 0L
                    return true
                }

                KeyEvent.KEYCODE_PROG_RED, KeyEvent.KEYCODE_BUTTON_X -> {
                    val held = SystemClock.elapsedRealtime() - redPressStartedAt
                    if (redPressStartedAt > 0L && held < 600L) {
                        openSettings()
                    }
                    redPressStartedAt = 0L
                    redLongPressHandled = false
                    return true
                }
            }
        }

        return super.dispatchKeyEvent(event)
    }

    private var splashMediaPlayer: MediaPlayer? = null
    private var splashStartTime = 0L
    private val splashHandler = Handler(Looper.getMainLooper())
    private var splashHidePending = false

    private fun animateSplashIn() {
        splashStartTime = android.os.SystemClock.elapsedRealtime()
        splashHidePending = false
        splashHandler.removeCallbacksAndMessages(null)
        binding.splashContainer.animate().cancel()
        binding.splashContainer.alpha = 1f
        binding.splashContainer.visibility = View.VISIBLE

        binding.splashLogo.animate().cancel()
        binding.splashLogo.alpha = 1f
        binding.splashLogo.scaleX = 1f
        binding.splashLogo.scaleY = 1f
        binding.splashLogo.startLogoAnimation()

        binding.splashTitle.visibility = View.GONE
        binding.splashByManas.visibility = View.GONE

        if (!splashSoundPlayed) {
            splashSoundPlayed = true
            try {
                splashMediaPlayer?.release()
                splashMediaPlayer = MediaPlayer.create(
                    this,
                    R.raw.splash_sound
                )?.apply {
                    setOnCompletionListener { player ->
                        player.release()
                        if (splashMediaPlayer === player) {
                            splashMediaPlayer = null
                        }
                    }
                    start()
                }
            } catch (e: Exception) {
                android.util.Log.w(
                    "Fixtok7",
                    "Splash sesi çalınamadı",
                    e
                )
            }
        }
    }


    private fun hideSplash() {
        if (splashHidden || splashHidePending) return
        val elapsed = android.os.SystemClock.elapsedRealtime() - splashStartTime
        val remaining = (5000L - elapsed).coerceAtLeast(0L)
        if (remaining > 0L) {
            splashHidePending = true
            splashHandler.postDelayed({
                splashHidePending = false
                hideSplash()
            }, remaining)
            return
        }
        splashHidden = true
        binding.splashContainer.animate().cancel()
        binding.splashContainer.animate()
            .alpha(0f)
            .setDuration(500L)
            .withEndAction {
                binding.splashContainer.visibility = View.GONE
            }
            .start()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        binding.webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()

        loadPreferences()
        updateAccountStatus()
        val prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
        mouseEnabled = prefs.getBoolean(SettingsActivity.KEY_MOUSE_ENABLED, mouseEnabled)
        cleanScreenEnabled = prefs.getBoolean(SettingsActivity.KEY_CLEAN_SCREEN, cleanScreenEnabled)
        binding.mainMouseCursor.visibility = if (mouseEnabled) View.VISIBLE else View.GONE
        applyAmoledTheme()
        applyCursorAppearance()
        applyCleanScreen()

        binding.webView.onResume()
        binding.webView.resumeTimers()
        syncGuideMode()
        binding.webView.evaluateJavascript(
            "window.__fixtokBassSet && window.__fixtokBassSet($bassEnabled);",
            null
        )

        if (bassEnabled && tvAudio == null) {
            tvAudio = TvAudioController()
        }
        if (bassEnabled) {
            tvAudio?.enable()
            tvAudio?.setBass(BASS_STRENGTH)
        } else {
            tvAudio?.disable()
        }
    }

    override fun onPause() {
        binding.webView.onPause()
        binding.webView.pauseTimers()
        tvAudio?.disable()
        super.onPause()
    }

    override fun onDestroy() {
        runCatching { voiceRecognizer?.cancel() }
        runCatching { voiceRecognizer?.destroy() }
        voiceRecognizer = null
        mainHandler.removeCallbacksAndMessages(null)
        exitFullscreenVideo()

        splashMediaPlayer?.runCatching {
            if (isPlaying) stop()
            release()
        }
        splashMediaPlayer = null

        tvAudio?.release()
        tvAudio = null

        val webView = binding.webView
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.destroy()

        super.onDestroy()
    }
}
