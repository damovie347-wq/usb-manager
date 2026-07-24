package com.usbmanager.app

import android.app.Application
import com.usbmanager.app.theme.ThemeManager

/**
 * Uygulama sinifi. Tek gorevi: kullanicinin en son sectigi temayi
 * (Acik / Karanlik / AMOLED / Sistem) ilk Activity olusmadan ONCE
 * uygulamaktir; boylece acilista tema "cakmasi/yanip sonme" olmaz.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemeManager.applyNightMode(ThemeManager.currentMode(this))
    }
}
