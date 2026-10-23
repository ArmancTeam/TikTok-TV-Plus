package com.armanc.team

import android.os.Bundle
import android.app.AlertDialog
import android.os.Build
import android.webkit.WebView
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.webkit.CookieManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.armanc.team.databinding.ActivitySettingsBinding

/**
 * TV-friendly настройки. Всё управляется D-pad: элементы focusable,
 * видимое focused-состояние через @drawable/focusable_bg.
 *
 * RESULT_OK возвращается в MainActivity, если сессия изменилась
 * (вход выполнен или logout) — тогда лента перезагружается.
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "fixtok_settings"
        const val KEY_BASS = "bass_enabled"
        const val KEY_MOUSE_SPEED = "mouse_speed"
        const val KEY_CURSOR_COLOR = "cursor_color"
        const val KEY_CURSOR_SHAPE = "cursor_shape"
        const val KEY_MOUSE_ENABLED = "mouse_enabled"
        const val KEY_CLEAN_SCREEN = "clean_screen"
        const val KEY_AMOLED = "amoled_enabled"
        const val KEY_CURSOR_SIZE = "cursor_size"
        const val KEY_GUIDE_MODE = "guide_mode"
        const val KEY_ADVANCED_AUDIO = "advanced_audio"
        const val KEY_CURSOR_PRECISION = "cursor_precision"
        const val KEY_MATERIAL_YOU = "material_you_theme"
        const val KEY_ACCENT_COLOR = "accent_color"
        const val KEY_VIDEO_QUALITY = "video_quality_preference"
        const val KEY_VIDEO_DOWNLOAD = "video_download_preference"
    }

    private lateinit var binding: ActivitySettingsBinding

    /** Giriş tamamlanırsa (yeni oturum) sonucu MainActivity'ye ilet: akış yenilensin. */
    private val loginLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) setResult(RESULT_OK)
            updateAccountStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Material You theme and configurable accent color.
        binding.settingsMaterialYou.isChecked = prefs.getBoolean(KEY_MATERIAL_YOU, false)
        val accentLabels = resources.getStringArray(R.array.settings_accent_color_labels).toList()
        binding.settingsAccentColor.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, accentLabels
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        val savedAccent = prefs.getInt(KEY_ACCENT_COLOR, 0).coerceIn(0, accentLabels.lastIndex)
        binding.settingsAccentColor.setSelection(savedAccent)
        fun applyAccent(index: Int, materialYou: Boolean) {
            val systemAccent = if (Build.VERSION.SDK_INT >= 31) {
                runCatching { ContextCompat.getColor(this, android.R.color.system_accent1_500) }.getOrNull()
            } else {
                null
            }
            val color = when {
                materialYou && systemAccent != null -> systemAccent
                index == 1 -> android.graphics.Color.parseColor("#FFFE2C55")
                index == 2 -> android.graphics.Color.parseColor("#FF4285F4")
                index == 3 -> android.graphics.Color.parseColor("#FF9C27B0")
                index == 4 -> android.graphics.Color.parseColor("#FF4CAF50")
                index == 5 -> android.graphics.Color.parseColor("#FFFF9800")
                index == 6 && systemAccent != null -> systemAccent
                else -> ContextCompat.getColor(this, R.color.fixtok_accent)
            }
            binding.settingsMaterialYou.buttonTintList = android.content.res.ColorStateList.valueOf(color)
            binding.settingsAccentColor.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
            binding.settingsDone.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
            binding.settingsCursorPreview.invalidate()
        }
        applyAccent(savedAccent, binding.settingsMaterialYou.isChecked)
        binding.settingsMaterialYou.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_MATERIAL_YOU, checked).apply()
            applyAccent(binding.settingsAccentColor.selectedItemPosition, checked)
        }
        binding.settingsAccentColor.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                prefs.edit().putInt(KEY_ACCENT_COLOR, position).apply()
                applyAccent(position, binding.settingsMaterialYou.isChecked)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK

        // ── Guide mode ────────────────────────────────────────────────
        val guideModes = resources.getStringArray(R.array.settings_guide_mode_labels).toList()
        binding.settingsGuideMode.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, guideModes
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.settingsGuideMode.setSelection(
            prefs.getInt(KEY_GUIDE_MODE, 0).coerceIn(0, guideModes.lastIndex)
        )
        binding.settingsGuideMode.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                prefs.edit().putInt(KEY_GUIDE_MODE, position).apply()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        binding.settingsAdvancedAudio.isChecked = prefs.getBoolean(KEY_ADVANCED_AUDIO, true)
        binding.settingsAdvancedAudio.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_ADVANCED_AUDIO, checked).apply()
        }

        binding.settingsCursorPrecision.isChecked = prefs.getBoolean(KEY_CURSOR_PRECISION, true)
        binding.settingsCursorPrecision.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_CURSOR_PRECISION, checked).apply()
        }

        // These are saved preferences. TikTok's website may ignore requested quality
        // or download behavior when it does not expose a corresponding control.
        val qualityLabels = resources.getStringArray(R.array.settings_video_quality_labels).toList()
        binding.settingsVideoQuality.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, qualityLabels
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.settingsVideoQuality.setSelection(
            prefs.getInt(KEY_VIDEO_QUALITY, 0).coerceIn(0, qualityLabels.lastIndex)
        )
        binding.settingsVideoQuality.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                prefs.edit().putInt(KEY_VIDEO_QUALITY, position).apply()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        val downloadLabels = resources.getStringArray(R.array.settings_video_download_labels).toList()
        binding.settingsVideoDownload.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, downloadLabels
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.settingsVideoDownload.setSelection(
            prefs.getInt(KEY_VIDEO_DOWNLOAD, 0).coerceIn(0, downloadLabels.lastIndex)
        )
        binding.settingsVideoDownload.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                prefs.edit().putInt(KEY_VIDEO_DOWNLOAD, position).apply()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        binding.settingsEmptyCache.setOnClickListener {
            try {
                // Yalnızca HTTP önbelleği. WebStorage.deleteAllData() localStorage'ı da
                // siler ve TikTok oturumunu / site durumunu bozar.
                WebView(this).apply {
                    clearCache(true)
                    destroy()
                }
                android.widget.Toast.makeText(this, R.string.settings_cache_cleared, android.widget.Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                android.widget.Toast.makeText(this, R.string.settings_cache_clear_failed, android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        // ── Bass boost ────────────────────────────────────────────────

        binding.settingsBass.isChecked =
            prefs.getBoolean(KEY_BASS, true)

        binding.settingsBass.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_BASS, isChecked).apply()
        }

        // ── AMOLED mode ──────────────────────────────────────────────
        binding.settingsAmoled.isChecked = prefs.getBoolean(KEY_AMOLED, false)
        binding.root.setBackgroundColor(
            if (prefs.getBoolean(KEY_AMOLED, false)) {
                android.graphics.Color.BLACK
            } else {
                ContextCompat.getColor(this, R.color.fixtok_background)
            }
        )
        binding.settingsAmoled.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_AMOLED, checked).apply()
            binding.root.setBackgroundColor(
                if (checked) android.graphics.Color.BLACK
                else ContextCompat.getColor(this, R.color.fixtok_background)
            )
        }

        // ── D-pad virtual mouse ───────────────────────────────────────
        binding.settingsMouseSpeed.progress = (prefs.getInt(KEY_MOUSE_SPEED, 5) - 1).coerceIn(0, 9)
        updateMouseSpeedLabel(binding.settingsMouseSpeed.progress + 1)
        binding.settingsMouseSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val speed = progress + 1
                prefs.edit().putInt(KEY_MOUSE_SPEED, speed).apply()
                updateMouseSpeedLabel(speed)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        binding.settingsCursorSize.progress = (prefs.getInt(KEY_CURSOR_SIZE, 32) - 16).coerceIn(0, 48)
        binding.settingsCursorSizeLabel.text = getString(R.string.settings_cursor_size_label, binding.settingsCursorSize.progress + 16)
        binding.settingsCursorSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val size = progress + 16
                prefs.edit().putInt(KEY_CURSOR_SIZE, size).apply()
                binding.settingsCursorSizeLabel.text = getString(R.string.settings_cursor_size_label, size)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        // Saklanan anahtarlar sabit (İngilizce); ekranda gösterilen etiketler yerelleştirilmiş.
        val colorKeys = VirtualCursorView.COLOR_KEYS
        val colorLabels = resources.getStringArray(R.array.cursor_color_labels).toList()
        binding.settingsCursorColor.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, colorLabels
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.settingsCursorColor.setSelection(
            colorKeys.indexOf(prefs.getString(KEY_CURSOR_COLOR, VirtualCursorView.DEFAULT_COLOR)).coerceAtLeast(0)
        )
        binding.settingsCursorColor.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                prefs.edit().putString(KEY_CURSOR_COLOR, colorKeys[position]).apply()
                binding.settingsCursorPreview.applyPrefs(prefs, resize = false)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        val shapeKeys = VirtualCursorView.SHAPE_KEYS
        val shapeLabels = resources.getStringArray(R.array.cursor_shape_labels).toList()
        binding.settingsCursorShape.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, shapeLabels
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.settingsCursorShape.setSelection(
            shapeKeys.indexOf(prefs.getString(KEY_CURSOR_SHAPE, VirtualCursorView.DEFAULT_SHAPE)).coerceAtLeast(0)
        )
        binding.settingsCursorShape.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                prefs.edit().putString(KEY_CURSOR_SHAPE, shapeKeys[position]).apply()
                binding.settingsCursorPreview.applyPrefs(prefs, resize = false)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        // Canlı önizleme: kaydedilmiş ayarlarla başla.
        binding.settingsCursorPreview.applyPrefs(prefs, resize = false)

        binding.settingsMouseEnabled.isChecked = prefs.getBoolean(KEY_MOUSE_ENABLED, false)
        binding.settingsMouseEnabled.setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(KEY_MOUSE_ENABLED, checked).apply() }
        binding.settingsCleanScreen.isChecked = prefs.getBoolean(KEY_CLEAN_SCREEN, false)
        binding.settingsCleanScreen.setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(KEY_CLEAN_SCREEN, checked).apply() }

        // ── Аккаунт ───────────────────────────────────────────────────

        updateAccountStatus()

        binding.settingsLogin.setOnClickListener {
            loginLauncher.launch(android.content.Intent(this, LoginActivity::class.java))
        }

        binding.settingsLogout.setOnClickListener {
            logout()
        }

        binding.settingsDone.setOnClickListener {
            finish()
        }

        binding.settingsDiagnostics.setOnClickListener { showDiagnostics() }
        binding.settingsCheckUpdates.setOnClickListener { checkForUpdates() }
    }

    private fun checkForUpdates() {
        if (!GitHubUpdateChecker.isConfigured()) {
            showUpdateDialog(getString(R.string.settings_update_not_configured))
            return
        }
        GitHubUpdateChecker.check(this) { result, error ->
            if (isFinishing || isDestroyed) return@check
            val message = when {
                error == GitHubUpdateChecker.NO_RELEASE -> getString(R.string.settings_update_none)
                error != null -> getString(R.string.settings_update_error, error)
                result == null -> getString(R.string.settings_update_error, "?")
                result.isNewer -> getString(R.string.settings_update_available, result.latestTag, result.releaseUrl)
                else -> getString(R.string.settings_update_latest, result.latestTag)
            }
            val openUrl = if (result != null && result.isNewer) result.releaseUrl else null
            showUpdateDialog(message, openUrl)
        }
    }

    private fun showUpdateDialog(message: String, openUrl: String? = null) {
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.settings_update_title)
            .setMessage(message)
        if (openUrl != null) {
            builder.setPositiveButton(R.string.settings_update_open) { _, _ ->
                val intent = android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(openUrl)
                )
                runCatching { startActivity(intent) }
                    .onFailure {
                        android.widget.Toast.makeText(this, openUrl, android.widget.Toast.LENGTH_LONG).show()
                    }
            }
            builder.setNegativeButton(android.R.string.cancel, null)
        } else {
            builder.setPositiveButton(android.R.string.ok, null)
        }
        builder.show()
    }

    private fun showDiagnostics() {
        val version = try {
            val info = packageManager.getPackageInfo(packageName, 0)
            val versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            "${info.versionName ?: "?"} ($versionCode)"
        } catch (_: Exception) { "?" }
        val webViewVersion = if (Build.VERSION.SDK_INT >= 26) {
            WebView.getCurrentWebViewPackage()?.versionName ?: "?"
        } else "Android System WebView"
        val details = getString(
            R.string.settings_diagnostics_body,
            version, packageName, Build.VERSION.RELEASE, Build.VERSION.SDK_INT,
            "${Build.MANUFACTURER} ${Build.MODEL}", webViewVersion
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_diagnostics_title)
            .setMessage(details)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun updateMouseSpeedLabel(speed: Int) {
        binding.settingsMouseSpeedLabel.text = getString(R.string.settings_mouse_speed_label, speed)
    }

    private fun updateAccountStatus() {
        val loggedIn =
            LoginActivity.hasSessionCookie(CookieManager.getInstance())
        binding.settingsAccountStatus.text = getString(
            if (loggedIn) R.string.account_status_logged_in
            else R.string.account_status_logged_out
        )
    }

    /**
     * Logout: TikTok oturum çerezleri gerçekten silinir (süresi geçirilir ve flush edilir),
     * arayüz güncellenir, sonuç MainActivity'ye döner ve akış oturumsuz yenilenir.
     */
    private fun logout() {
        LoginActivity.clearSession(CookieManager.getInstance())
        setResult(RESULT_OK)
        updateAccountStatus()
    }

    override fun onResume() {
        super.onResume()
        // Вернулся из LoginActivity — обновить статус.
        updateAccountStatus()
    }
}
