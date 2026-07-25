package com.usbmanager.app.usb

import java.nio.ByteBuffer

/**
 * Ham (raw) blok seviyesinde okuma/yazma arayuzu.
 *
 * ONEMLI MIMARI NOT (gercek CI derlemesiyle dogrulandi): libaums'un GENEL
 * (public) API'si -- FileSystem / UsbFile -- bu tur ham blok erisimi
 * SUNMAZ; kutuphane sadece taninan bir dosya sistemi (bugunku surumde
 * FAT32) uzerinden dosya/klasor islemlerine izin verir. Bu yuzden bu
 * arayuzun TEK implementasyonu artik `ScsiRawBlockDevice`'tir (bkz.
 * ScsiRawBlockDevice.kt) -- USB Mass Storage Bulk-Only Transport + SCSI
 * READ(10)/WRITE(10) komut setini dogrudan Android USB Host API uzerinde
 * uygulayan, hicbir ucuncu taraf kutuphaneye bagli olmayan bir sinif.
 *
 * FormatEngine / Fat32Formatter / IsoWriterEngine SADECE bu arayuzu
 * kullanir; somut implementasyonun (ScsiRawBlockDevice) nasil calistigini
 * bilmeleri gerekmez.
 */
interface RawBlockDevice {
    /** Sektor / blok boyutu (genelde 512 bayt, bazi cihazlarda 4096). */
    val blockSizeBytes: Int

    /** Aygitin toplam kapasitesi (bayt). */
    val totalBytes: Long

    /** `byteOffset` HER ZAMAN blockSizeBytes'a hizali olmalidir. */
    fun readAt(byteOffset: Long, buffer: ByteBuffer)

    /** `byteOffset` HER ZAMAN blockSizeBytes'a hizali olmalidir. */
    fun writeAt(byteOffset: Long, buffer: ByteBuffer)
}
