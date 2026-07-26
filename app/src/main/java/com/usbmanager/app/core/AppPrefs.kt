package com.usbmanager.app.core

import android.content.Context

/**
 * Merkezi SharedPreferences okuma yardimcisi.
 *
 * ONCEKI DURUM (HATA): Ayarlar ekranindaki "Animasyonlar" anahtari sadece
 * SharedPreferences'a "animations_enabled" degerini YAZIYORDU; uygulamanin
 * BASKA HICBIR YERINDE bu deger OKUNMUYORDU. Sonuc: anahtari kapatmak hicbir
 * gorsel etki yaratmiyordu (Fragment gecisleri, hiz testi ibresi, dosya
 * listesi animasyonlari hep ayni sekilde calismaya devam ediyordu).
 *
 * DUZELTME: Bu nesne, "usb_manager_prefs" SharedPreferences dosyasini
 * (SettingsFragment ile AYNI dosya + AYNI anahtar isimleri) okuyarak,
 * animasyon/haptik tercihinin uygulamanin GERCEKTEN ilgili yerlerinde
 * (bkz. MainActivity.showFragment, SpeedometerView, FileManagerFragment)
 * kullanilmasini saglar.
 */
object AppPrefs {
    private const val PREFS_NAME = "usb_manager_prefs"
    private const val KEY_ANIMATIONS = "animations_enabled"
    private const val KEY_HAPTICS = "haptics_enabled"

    fun animationsEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ANIMATIONS, true)

    fun hapticsEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_HAPTICS, true)
}
