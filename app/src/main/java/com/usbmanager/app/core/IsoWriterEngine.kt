package com.usbmanager.app.core

import android.content.Context
import android.net.Uri
import com.usbmanager.app.usb.RawBlockDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.security.MessageDigest

sealed class IsoWriteUpdate {
    data class Progress(val bytesWritten: Long, val totalBytes: Long, val instantMBps: Double) : IsoWriteUpdate()
    object Finished : IsoWriteUpdate()
    data class Failed(val error: Throwable) : IsoWriteUpdate()
}

/**
 * Bootable ISO/IMG Yazici ("Mobil Rufus") - RAW/DD modu.
 *
 * Secilen .iso/.img dosyasi, hedef USB'nin BASINDAN itibaren (LBA 0) HAM
 * BLOK seviyesinde, sektor sektor kopyalanir. Bu, `dd if=image.iso
 * of=/dev/sdX bs=1M` komutunun yaptigi ISLEMIN AYNISIDIR: mevcut partition
 * tablosu ve dosya sistemi TAMAMEN uzerine yazilir, boylece Windows/Linux
 * kurulum ortami dogrudan boot edilebilir hale gelir.
 *
 * Windows ve Linux ISO'lari GENELLIKLE ISOHYBRID formatinda uretildigi
 * icin (Windows'un guncel ISO'lari ve cogu Linux dagitimi bunu destekler)
 * dogrudan RAW/DD kopyalama ile bootable USB elde edilir; ekstra bir
 * "El Torito -> hibrit MBR" donusumu bu iskelet kapsaminda YAPILMAZ (bu,
 * Rufus'un ISOHybrid disini kendisi olusturdugu senaryolar icin gerekli
 * olan, ayri ve daha ileri seviye bir ozelliktir).
 */
object IsoWriterEngine {

    private const val CHUNK_SIZE = 4 * 1024 * 1024 // 4 MB

    suspend fun writeRawImage(
        context: Context,
        imageUri: Uri,
        imageSizeBytes: Long,
        target: RawBlockDevice,
        onUpdate: (IsoWriteUpdate) -> Unit
    ) = withContext(Dispatchers.IO) {
        require(imageSizeBytes <= target.totalBytes) {
            "Secilen imaj (${imageSizeBytes} bayt), hedef USB'den (${target.totalBytes} bayt) buyuk."
        }

        try {
            context.contentResolver.openInputStream(imageUri)?.use { input ->
                val buffer = ByteArray(CHUNK_SIZE)
                var written = 0L
                val start = System.nanoTime()

                while (written < imageSizeBytes && currentCoroutineContext().isActive) {
                    val chunkStart = System.nanoTime()
                    val n = input.read(buffer)
                    if (n <= 0) break

                    // Blok hizalamasi: RawBlockDevice her zaman blockSizeBytes'a
                    // hizali offsetler bekler; son (kismi) parcayi da tam
                    // sektor sinirina yuvarlayip sifirla dolduruyoruz.
                    val alignedSize = alignUp(n, target.blockSizeBytes)
                    val bb = ByteBuffer.allocate(alignedSize)
                    bb.put(buffer, 0, n)
                    while (bb.hasRemaining()) bb.put(0)
                    bb.rewind()

                    target.writeAt(written, bb)
                    written += n

                    val chunkSeconds = (System.nanoTime() - chunkStart) / 1_000_000_000.0
                    val instantMBps = if (chunkSeconds > 0) (n / (1024.0 * 1024.0)) / chunkSeconds else 0.0
                    onUpdate(IsoWriteUpdate.Progress(written, imageSizeBytes, instantMBps))
                }
            } ?: throw IllegalStateException("Imaj dosyasi acilamadi (Uri gecersiz).")

            onUpdate(IsoWriteUpdate.Finished)
        } catch (t: Throwable) {
            onUpdate(IsoWriteUpdate.Failed(t))
        }
    }

    private fun alignUp(value: Int, alignment: Int): Int {
        val rem = value % alignment
        return if (rem == 0) value else value + (alignment - rem)
    }

    /** Secilen imajin SHA-256 ozetini hesaplar (dogrulama karti icin). */
    suspend fun computeSha256(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(CHUNK_SIZE)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Dosya adindan Windows/Linux imaj turunu tahmin eder (yalnizca UI etiketi icin). */
    fun detectImageKind(fileName: String): String {
        val n = fileName.lowercase()
        return when {
            "win" in n -> "Windows Kurulum İmajı"
            listOf("ubuntu", "debian", "fedora", "mint", "arch", "kali", "manjaro", "linux")
                .any { it in n } -> "Linux Dağıtım İmajı"
            else -> "Genel Disk İmajı"
        }
    }
}
