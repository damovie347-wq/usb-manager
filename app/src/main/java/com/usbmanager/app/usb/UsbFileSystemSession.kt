package com.usbmanager.app.usb

import android.hardware.usb.UsbDevice
import me.jahnen.libaums.core.UsbMassStorageDevice
import me.jahnen.libaums.core.fs.FileSystem

/**
 * KOK NEDEN ("her ekran degisiminde 'USB bellek takıldı/çıkartıldı'"):
 * Dosya Yoneticisi ve Hiz Testi ekranlarinin HER BIRI, KENDI ViewModel'inde
 * BAGIMSIZ bir libaums baglantisi tutuyordu ve o ekrandan ayrilinca
 * (`onCleared`) bu baglantiyi KAPATIYORDU. Bu USB arabirimini (interface)
 * "force claim" ile aliyor (cekirdegin kendi usb-storage surucusunu devre
 * disi birakiyor); baglanti kapatildiginda arabirim serbest kalinca
 * cekirdek SIK SIK kendi surucusunu YENIDEN baglar -- bu da Android'in
 * "USB depolama bağlandı/kaldırıldı" sistem bildirimini tetikler. Iki ekran
 * arasinda ILERI-GERI gecildikce (Dosya Yoneticisi -> Hiz Testi -> Dosya
 * Yoneticisi...) bu claim/release dongusu HER GECISTE tekrarlaniyordu.
 *
 * Duzeltme: bu iki ekran artik BAGIMSIZ baglanti tutmuyor, bu PAYLASILAN
 * oturumu kullaniyor. Aygit FIZIKSEL olarak degismedigi surece baglanti
 * ekranlar arasi yeniden kullanilir; sadece GERCEKTEN gerektiginde (aygit
 * degisti/koptu ya da Biçimlendir/Bootable ekrani arabirimi HAM erisim
 * icin istedigi zaman -- bkz. `releaseForExclusiveAccess()`) kapatilip
 * yeniden acilir.
 *
 * NTFS/exFAT icin de (libaums bunlari ANLAMADIGI icin -- bkz.
 * RawFileSystemSniffer) Dosya Yoneticisi artik `ExFatReader`/`NtfsReader`
 * ile HAM bir okuyucu acabiliyor; bu baglanti da AYNI arabirimi claim
 * ettiginden, AYNI paylasim/serbest-birakma disiplinine (bu nesne
 * uzerinden) tabidir -- yoksa Bootable/Biçimlendir ekranlarina gecerken
 * "Bootable donuyor" hatasiyla AYNI turden bir arabirim celismesi
 * (bkz. IsoWriterViewModel.kt) burada da olusurdu.
 */
object UsbFileSystemSession {

    private var device: UsbMassStorageDevice? = null
    private var fileSystem: FileSystem? = null
    private var rawReader: RawVolumeReader? = null
    private var deviceId: Int? = null

    /** Zaten AYNI fiziksel aygita acik bir libaums oturumu varsa (ac/kapa YAPMADAN) dondurur. */
    @Synchronized
    fun existingFor(usbDevice: UsbDevice): FileSystem? =
        if (fileSystem != null && deviceId == usbDevice.deviceId) fileSystem else null

    @Synchronized
    fun adopt(usbDevice: UsbDevice, massStorageDevice: UsbMassStorageDevice, fs: FileSystem) {
        // Farkli bir aygit/baglanti zaten aciksa, once onu kapat.
        if (device !== massStorageDevice) runCatching { device?.close() }
        runCatching { rawReader?.close() }
        rawReader = null
        device = massStorageDevice
        fileSystem = fs
        deviceId = usbDevice.deviceId
    }

    /** Zaten AYNI fiziksel aygita acik bir NTFS/exFAT ham okuyucu oturumu varsa dondurur. */
    @Synchronized
    fun existingRawReaderFor(usbDeviceId: Int): RawVolumeReader? =
        if (rawReader != null && deviceId == usbDeviceId) rawReader else null

    @Synchronized
    fun adoptRawReader(usbDeviceId: Int, reader: RawVolumeReader) {
        runCatching { device?.close() }
        device = null
        fileSystem = null
        if (rawReader !== reader) runCatching { rawReader?.close() }
        rawReader = reader
        deviceId = usbDeviceId
    }

    /**
     * Biçimlendir/Bootable ekrani gibi HAM (raw) SCSI erisimi gereken bir
     * islemden ONCE cagrilmalidir -- ayni USB arabirimine iki AYRI baglanti
     * turu (libaums'un kendi baglantisi veya bu oturumun NTFS/exFAT
     * okuyucusu + ham ScsiRawBlockDevice) AYNI ANDA acik OLAMAZ.
     */
    @Synchronized
    fun releaseForExclusiveAccess() {
        runCatching { device?.close() }
        runCatching { rawReader?.close() }
        device = null
        fileSystem = null
        rawReader = null
        deviceId = null
    }

    @Synchronized
    fun isOpenFor(usbDeviceId: Int): Boolean = (fileSystem != null || rawReader != null) && deviceId == usbDeviceId
}
