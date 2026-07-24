package com.usbmanager.app.theme

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import com.usbmanager.app.R

/**
 * Uygulamanin 4 modlu Tema Motoru.
 *
 * - LIGHT     -> AppCompat gece modu KAPALI, standart Theme.UsbManager
 * - DARK      -> AppCompat gece modu ACIK,  standart Theme.UsbManager (values-night ile override)
 * - AMOLED    -> AppCompat gece modu ACIK,  fakat activity'ye ayrica Theme.UsbManager.Amoled
 *                uygulanir (setTheme + recreate) -> tum surface'ler tam #000000 olur.
 * - SYSTEM    -> AppCompat gece modu FOLLOW_SYSTEM
 *
 * Secim SharedPreferences'ta saklanir, boylece uygulama yeniden acildiginda
 * kullanicinin son sectigi tema hatirlanir. Tamamen yerelde (offline) calisir.
 */
object ThemeManager {

    private const val PREFS = "usb_manager_prefs"
    private const val KEY_THEME_MODE = "theme_mode"

    enum class Mode { LIGHT, DARK, AMOLED, SYSTEM }

    fun currentMode(context: Context): Mode {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_THEME_MODE, Mode.SYSTEM.name) ?: Mode.SYSTEM.name
        return runCatching { Mode.valueOf(name) }.getOrDefault(Mode.SYSTEM)
    }

    fun setMode(context: Context, mode: Mode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_THEME_MODE, mode.name)
        }
        applyNightMode(mode)
    }

    /** Uygulama baslarken (Application.onCreate) cagrilmalidir. */
    fun applyNightMode(mode: Mode) {
        val nightMode = when (mode) {
            Mode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            Mode.DARK, Mode.AMOLED -> AppCompatDelegate.MODE_NIGHT_YES
            Mode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    /**
     * AMOLED modu icin Activity.onCreate() -> setContentView() ONCESINDE cagrilmalidir.
     * Diger modlarda manifest'teki varsayilan Theme.UsbManager (+ values-night override)
     * zaten yeterlidir, bu yuzden sadece AMOLED durumunda ekstra setTheme yapariz.
     */
    fun applyActivityThemeOverride(activity: Activity) {
        if (currentMode(activity) == Mode.AMOLED) {
            activity.setTheme(R.style.Theme_UsbManager_Amoled)
        }
    }
}
