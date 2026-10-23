package com.armanc.team

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.armanc.team.databinding.ActivityLoginBinding

/**
 * Отдельный экран входа: свой WebView, свой lifecycle, свой allowlist доменов.
 *
 * В отличие от основного экрана, здесь разрешена виртуальная мышь — страницы
 * входа TikTok/Google/Facebook/Apple рассчитаны на указатель, и без курсора
 * по ним невозможно нормально пройти. После закрытия этого экрана мышь
 * полностью исчезает: в основном интерфейсе Fixtok её нет.
 *
 * Домены проверяются строго по URI.host (точное совпадение или поддомен).
 * Проверки вида url.contains("tiktok.com") запрещены — они пропускают
 * evil-tiktok.com и tiktok.com.evil.com.
 */
class LoginActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "FixTokLogin"

        private const val LOGIN_URL = "https://www.tiktok.com/login"

        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Safari/537.36"

        // Giriş / OAuth sırasında gerçekten gezilen hostlar.
        // Eşleşme tam host veya alt alan adıdır (evil-tiktok.com geçmez).
        private val ALLOWED_HOSTS = listOf(
            "tiktok.com",
            "google.com",
            "googleapis.com",
            "gstatic.com",
            "googleusercontent.com",
            "recaptcha.net",
            "facebook.com",
            "fb.com",
            "facebook.net",
            "fbcdn.net",
            "apple.com",
            "cdn-apple.com"
        )

        fun isAllowedHost(host: String?): Boolean {
            val h = host?.lowercase() ?: return false
            return ALLOWED_HOSTS.any { h == it || h.endsWith(".$it") }
        }

        private val SESSION_COOKIES = listOf(
            "sessionid", "sessionid_ss", "sid_tt", "sid_guard",
            "sid_ucp_v1", "ssid_ucp_v1", "uid_tt", "uid_tt_ss"
        )

        /** Geçerli TikTok oturum çerezinin değeri (yoksa null). */
        fun sessionValue(cookieManager: CookieManager): String? {
            val cookies = cookieManager.getCookie("https://www.tiktok.com") ?: return null
            return cookies.split(";")
                .map { it.trim() }
                .firstOrNull { it.startsWith("sessionid=") }
                ?.substringAfter("=", "")
                ?.takeIf { it.isNotBlank() }
        }

        /** Есть ли действующая сессия TikTok в CookieManager. */
        fun hasSessionCookie(cookieManager: CookieManager): Boolean =
            sessionValue(cookieManager) != null

        /**
         * Gerçek çıkış: oturum çerezlerini süresini geçirerek siler.
         * (CookieManager.setCookie ile "a=b; c=d" yazmak çerez silmez.)
         */
        fun clearSession(cookieManager: CookieManager) {
            val expire = "Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/"
            for (name in SESSION_COOKIES) {
                cookieManager.setCookie("https://www.tiktok.com", "$name=; $expire; Domain=.tiktok.com; Secure")
                cookieManager.setCookie("https://www.tiktok.com", "$name=; $expire; Secure")
            }
            cookieManager.flush()
        }
    }

    private lateinit var binding: ActivityLoginBinding

    /** Ekran açılırken var olan oturum; sadece YENİ bir oturum girişi tamamlanmış sayılır. */
    private var initialSession: String? = null

    private var popupWebView: WebView? = null

    /** Login completed: finish() called to avoid double finish. */
    private var finished = false

    /** İşaretçinin kök görünüm içindeki hotspot konumu. */
    private var pointerX = 0f
    private var pointerY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initialSession = sessionValue(CookieManager.getInstance())

        enterFullscreen()
        setupWebView()
        setupLoginMouse()

        if (savedInstanceState == null) {
            binding.loginWebView.loadUrl(LOGIN_URL)
        } else {
            binding.loginWebView.restoreState(savedInstanceState)
        }

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
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.loginWebView

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = false
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            textZoom = 100
            userAgentString = DESKTOP_USER_AGENT
        }

        // Тот же CookieManager, что у основного WebView: сессия общая.
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                return !allowNavigation(request.url)
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(
                view: WebView,
                url: String?
            ): Boolean {
                val uri = url?.let {
                    runCatching { android.net.Uri.parse(it) }.getOrNull()
                }
                return if (uri == null) true else !allowNavigation(uri)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                checkLoginComplete()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {

            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message
            ): Boolean {
                // Google/Facebook/Apple OAuth открываются через window.open().
                closePopup()

                val popup = WebView(this@LoginActivity).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = DESKTOP_USER_AGENT
                    settings.setSupportMultipleWindows(false)

                    webViewClient = object : WebViewClient() {

                        override fun shouldOverrideUrlLoading(
                            v: WebView,
                            req: WebResourceRequest
                        ): Boolean {
                            return !allowNavigation(req.url)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun shouldOverrideUrlLoading(
                            v: WebView,
                            url: String?
                        ): Boolean {
                            val uri = url?.let {
                                runCatching { android.net.Uri.parse(it) }.getOrNull()
                            }
                            return if (uri == null) true else !allowNavigation(uri)
                        }

                        override fun onPageFinished(v: WebView, url: String) {
                            super.onPageFinished(v, url)
                            // Фlow вернулся на TikTok — вход завершён.
                            val host = runCatching {
                                android.net.Uri.parse(url).host?.lowercase()
                            }.getOrNull()
                            if (host == "tiktok.com" ||
                                host?.endsWith(".tiktok.com") == true
                            ) {
                                closePopup()
                                checkLoginComplete()
                            }
                        }
                    }
                }

                popup.webChromeClient = object : WebChromeClient() {
                    override fun onCloseWindow(window: WebView) {
                        // window.close() popup'ın kendi chrome client'ına gelir.
                        binding.root.post {
                            closePopup()
                            checkLoginComplete()
                        }
                    }

                    override fun onPermissionRequest(request: PermissionRequest) {
                        runOnUiThread { runCatching { request.deny() } }
                    }
                }
                CookieManager.getInstance().setAcceptThirdPartyCookies(popup, true)

                popupWebView = popup

                binding.loginPopupContainer.removeAllViews()
                binding.loginPopupContainer.addView(
                    popup,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
                binding.loginPopupContainer.visibility = View.VISIBLE
                popup.requestFocus()

                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = popup
                resultMsg.sendToTarget()

                return true
            }

            override fun onCloseWindow(window: WebView) {
                closePopup()
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                // Камера/микрофон приложению не нужны — запросы отклоняются.
                runOnUiThread {
                    runCatching { request.deny() }
                }
            }
        }

        webView.requestFocus()
    }

    /**
     * Разрешена ли навигация по URI. Строгая проверка host из allowlist
     * плюс схема https. Всё остальное блокируется.
     */
    private fun allowNavigation(uri: android.net.Uri): Boolean {
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()

        if (scheme != "https") {
            Log.w(TAG, "Blocked non-https navigation: $uri")
            return false
        }
        if (!isAllowedHost(host)) {
            Log.w(TAG, "Blocked navigation outside allowlist: $uri")
            return false
        }
        return true
    }

    /** Сессия появилась — вход выполнен, возвращаемся в основной экран. */
    private fun checkLoginComplete() {
        if (finished) return
        val current = sessionValue(CookieManager.getInstance())
        if (current != null && current != initialSession) {
            finished = true
            setResult(RESULT_OK)
            finish()
        }
    }

    private fun closePopup() {
        popupWebView?.let { popup ->
            binding.loginPopupContainer.removeView(popup)
            popup.stopLoading()
            popup.destroy()
        }
        popupWebView = null
        binding.loginPopupContainer.visibility = View.GONE
    }

    // ── Sanal fare (Login WebView; TV'de giriş aksi halde tıklanamaz) ─

    private fun prefs() = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)

    private fun pointerTarget(): View = popupWebView ?: binding.loginWebView

    private fun setupLoginMouse() {
        binding.loginMouseCursor.applyPrefs(prefs())
        binding.loginMouseCursor.post { centerPointerIfNeeded() }
    }

    private fun centerPointerIfNeeded() {
        if (pointerX == 0f && pointerY == 0f) {
            pointerX = binding.root.width / 2f
            pointerY = binding.root.height / 2f
        }
        updateLoginMousePosition()
    }

    private fun moveLoginMouse(dx: Float, dy: Float, repeat: Int) {
        val speed = prefs().getInt(SettingsActivity.KEY_MOUSE_SPEED, 5)
        val precision = prefs().getBoolean(SettingsActivity.KEY_CURSOR_PRECISION, true)
        val precisionFactor = if (precision) 0.65f else 1f
        val step = VirtualPointer.stepPx(speed, resources.displayMetrics.density) *
            VirtualPointer.accel(repeat) * precisionFactor
        val maxX = binding.root.width.toFloat().coerceAtLeast(0f)
        val maxY = binding.root.height.toFloat().coerceAtLeast(0f)
        pointerX = (pointerX + dx * step).coerceIn(0f, maxX)
        pointerY = (pointerY + dy * step).coerceIn(0f, maxY)
        updateLoginMousePosition()
        VirtualPointer.hover(pointerTarget(), pointerX, pointerY)
    }

    private fun updateLoginMousePosition() {
        val cursor = binding.loginMouseCursor
        cursor.translationX = pointerX - cursor.hotspotX()
        cursor.translationY = pointerY - cursor.hotspotY()
    }

    private fun clickLoginMouse() {
        binding.loginMouseCursor.showClickFeedback()
        VirtualPointer.click(pointerTarget(), pointerX, pointerY)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    // Back внутри popup закрывает popup, иначе — закрывает
                    // Login WebView и возвращает фокус основному экрану.
                    if (popupWebView != null) {
                        closePopup()
                    } else {
                        setResult(RESULT_CANCELED)
                        finished = true
                        finish()
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    moveLoginMouse(0f, -1f, event.repeatCount)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    moveLoginMouse(0f, 1f, event.repeatCount)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    moveLoginMouse(-1f, 0f, event.repeatCount)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    moveLoginMouse(1f, 0f, event.repeatCount)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (event.repeatCount == 0) {
                        binding.loginMouseCursor.setClickFeedback(true)
                    }
                    return true
                }
            }
        } else if (event.action == KeyEvent.ACTION_UP) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    binding.loginMouseCursor.setClickFeedback(false)
                    clickLoginMouse()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        binding.loginWebView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        binding.loginWebView.onResume()
        binding.loginWebView.resumeTimers()
        setupLoginMouse()
    }

    override fun onPause() {
        binding.loginWebView.onPause()
        binding.loginWebView.pauseTimers()
        super.onPause()
    }

    override fun onDestroy() {
        closePopup()
        val webView = binding.loginWebView
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }
}
