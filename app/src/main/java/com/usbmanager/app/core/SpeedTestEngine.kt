package com.usbmanager.app.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import me.jahnen.libaums.core.fs.FileSystem
import me.jahnen.libaums.core.fs.UsbFile
import me.jahnen.libaums.core.fs.UsbFileInputStream
import me.jahnen.libaums.core.fs.UsbFileOutputStream

private const val TEST_FILE_NAME = "~usbmanager_speedtest.tmp"
private const val CHUNK_SIZE = 4 * 1024 * 1024 // 4 MB'lik parcalar

sealed class SpeedTestUpdate {
    data class Progress(
        val bytesDone: Long,
        val totalBytes: Long,
        val instantMBps: Double,
        val averageMBps: Double
    ) : SpeedTestUpdate()

    data class Finished(val averageMBps: Double, val elapsedMs: Long) : SpeedTestUpdate()
    data class CancelledOrFailed(val error: Throwable?) : SpeedTestUpdate()
}

/**
 * Gercekci USB Hiz Testi.
 *
 * libaums, dosyayi Android'in kendi VFS'i / sayfa onbellegi (page cache)
 * ustunden DEGIL, dogrudan USB bulk-transfer + SCSI komutlariyla yazip
 * okudugu icin, burada olculen hiz "onbellek yanilmasi" olmadan gercek
 * donanim hizidir (gorevin istedigi Direct I/O / Cache Bypass davranisi).
 *
 * Guvenlik: test dosyasi ismi "~" ile baslar (gizli/gecici oldugu belli
 * olsun diye), varsayilan boyutu 512 MB'dir ve test bitince/iptal edilince
 * ANINDA silinir; mevcut kullanici verilerine dokunulmaz.
 */
object SpeedTestEngine {

    suspend fun runWriteTest(
        fs: FileSystem,
        testSizeBytes: Long = 512L * 1024 * 1024,
        onUpdate: (SpeedTestUpdate) -> Unit
    ) = withContext(Dispatchers.IO) {
        val root = fs.rootDirectory
        deleteIfExists(root)

        val testFile: UsbFile = root.createFile(TEST_FILE_NAME)
        val buffer = ByteArray(CHUNK_SIZE) { 0xA5.toByte() } // dummy veri

        var written = 0L
        val start = System.nanoTime()
        try {
            UsbFileOutputStream(testFile).use { out ->
                while (written < testSizeBytes && currentCoroutineContext().isActive) {
                    val remaining = testSizeBytes - written
                    val thisChunk = if (remaining < CHUNK_SIZE) remaining.toInt() else CHUNK_SIZE
                    val chunkStart = System.nanoTime()

                    out.write(buffer, 0, thisChunk)
                    written += thisChunk

                    val chunkSeconds = (System.nanoTime() - chunkStart) / 1_000_000_000.0
                    val instantMBps = if (chunkSeconds > 0) (thisChunk / (1024.0 * 1024.0)) / chunkSeconds else 0.0
                    val elapsedSeconds = (System.nanoTime() - start) / 1_000_000_000.0
                    val avgMBps = if (elapsedSeconds > 0) (written / (1024.0 * 1024.0)) / elapsedSeconds else 0.0

                    onUpdate(SpeedTestUpdate.Progress(written, testSizeBytes, instantMBps, avgMBps))
                }
            }
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            val avgMBps = if (elapsedMs > 0) (written / (1024.0 * 1024.0)) / (elapsedMs / 1000.0) else 0.0
            onUpdate(SpeedTestUpdate.Finished(avgMBps, elapsedMs))
        } catch (t: Throwable) {
            onUpdate(SpeedTestUpdate.CancelledOrFailed(t))
        } finally {
            // Test dosyasi ANINDA silinir (basarili / iptal / hata farketmez).
            deleteIfExists(root)
        }
    }

    suspend fun runReadTest(
        fs: FileSystem,
        testSizeBytes: Long = 512L * 1024 * 1024,
        onUpdate: (SpeedTestUpdate) -> Unit
    ) = withContext(Dispatchers.IO) {
        val root = fs.rootDirectory
        deleteIfExists(root)

        // Okuma testi icin once ayni boyutta bir dosya yazilir (disk uzerinde
        // gercek veri olmadan "okuma hizi" olculemez), sonra o dosya okunur.
        val testFile: UsbFile = root.createFile(TEST_FILE_NAME)
        val writeBuffer = ByteArray(CHUNK_SIZE) { 0x5A }
        UsbFileOutputStream(testFile).use { out ->
            var w = 0L
            while (w < testSizeBytes) {
                val remaining = testSizeBytes - w
                val thisChunk = if (remaining < CHUNK_SIZE) remaining.toInt() else CHUNK_SIZE
                out.write(writeBuffer, 0, thisChunk)
                w += thisChunk
            }
        }

        val readBuffer = ByteArray(CHUNK_SIZE)
        var readTotal = 0L
        val start = System.nanoTime()
        try {
            UsbFileInputStream(testFile).use { input ->
                while (readTotal < testSizeBytes && currentCoroutineContext().isActive) {
                    val chunkStart = System.nanoTime()
                    val n = input.read(readBuffer)
                    if (n <= 0) break
                    readTotal += n

                    val chunkSeconds = (System.nanoTime() - chunkStart) / 1_000_000_000.0
                    val instantMBps = if (chunkSeconds > 0) (n / (1024.0 * 1024.0)) / chunkSeconds else 0.0
                    val elapsedSeconds = (System.nanoTime() - start) / 1_000_000_000.0
                    val avgMBps = if (elapsedSeconds > 0) (readTotal / (1024.0 * 1024.0)) / elapsedSeconds else 0.0

                    onUpdate(SpeedTestUpdate.Progress(readTotal, testSizeBytes, instantMBps, avgMBps))
                }
            }
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            val avgMBps = if (elapsedMs > 0) (readTotal / (1024.0 * 1024.0)) / (elapsedMs / 1000.0) else 0.0
            onUpdate(SpeedTestUpdate.Finished(avgMBps, elapsedMs))
        } catch (t: Throwable) {
            onUpdate(SpeedTestUpdate.CancelledOrFailed(t))
        } finally {
            deleteIfExists(root)
        }
    }

    fun mbpsToMbits(mbps: Double): Double = mbps * 8.0

    private fun deleteIfExists(root: UsbFile) {
        runCatching {
            root.search(TEST_FILE_NAME)?.delete()
        }
    }
}
