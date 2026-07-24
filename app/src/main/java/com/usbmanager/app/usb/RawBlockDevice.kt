package com.usbmanager.app.usb

import me.jahnen.libaums.core.UsbMassStorageDevice
import java.nio.ByteBuffer

/**
 * libaums'un, surumden suruma degisebilecek ic API yuzeyini TEK bir noktada
 * izole eden ince adaptor (adapter pattern).
 *
 * FormatEngine / SpeedTestEngine / IsoWriterEngine bu dosyanin disinda
 * ASLA dogrudan `UsbMassStorageDevice`'in blok-seviyesi metotlarini
 * cagirmaz; hepsi sadece `RawBlockDevice` arayuzunu kullanir. Boylece
 * libaums surum guncellemesi / API degisikligi durumunda TEK bir dosya
 * (bu dosya) guncellenir.
 *
 * =====================================================================
 *  DOGRULAMA GEREKEN NOKTA (ilk CI derlemesinde en olasi hata kaynagi)
 * =====================================================================
 * `device.read(block, buffer)` / `device.write(block, buffer)` cagrilari
 * bu iskelette me.jahnen.libaums:core:0.10.0 uzerinden dogrudan
 * `UsbMassStorageDevice` uzerinde bulunacagi varsayimiyla yazildi.
 * Kutuphanenin bazi surumlerinde bu metotlar `UsbMassStorageDevice`
 * uzerinde degil, alttaki `BlockDeviceDriver` / `ScsiBlockDevice`
 * nesnesinde olabilir. Derleme hatasi alirsaniz:
 *   1) https://github.com/magnusja/libaums -> `core/src/main/java/.../UsbMassStorageDevice.kt`
 *      dosyasini acin,
 *   2) blok okuma/yazma icin genel erisimli (public) metot/alanin GUNCEL
 *      adini bulun,
 *   3) SADECE asagidaki `rawDeviceOf()` fonksiyonunu o isme gore duzeltin.
 * Uygulamanin geri kalani (UI, coroutine akisi, ilerleme yuzdesi vb.)
 * degismeden calismaya devam eder.
 */
interface RawBlockDevice {
    /** Sektor / blok boyutu (genelde 512 bayt). */
    val blockSizeBytes: Int

    /** Aygitin toplam kapasitesi (bayt). */
    val totalBytes: Long

    /** `byteOffset` HER ZAMAN blockSizeBytes'a hizali olmalidir. */
    fun readAt(byteOffset: Long, buffer: ByteBuffer)

    /** `byteOffset` HER ZAMAN blockSizeBytes'a hizali olmalidir. */
    fun writeAt(byteOffset: Long, buffer: ByteBuffer)
}

fun rawDeviceOf(device: UsbMassStorageDevice): RawBlockDevice = object : RawBlockDevice {
    override val blockSizeBytes: Int get() = device.blockSize
    override val totalBytes: Long get() = device.blockSize.toLong() * device.blockCount

    override fun readAt(byteOffset: Long, buffer: ByteBuffer) {
        val block = byteOffset / device.blockSize
        device.read(block, buffer)
    }

    override fun writeAt(byteOffset: Long, buffer: ByteBuffer) {
        val block = byteOffset / device.blockSize
        device.write(block, buffer)
    }
}
