package com.usbmanager.app.core

import com.usbmanager.app.usb.RawBlockDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

enum class FormatMode { QUICK, LOW_LEVEL_ZERO_FILL }

sealed class FormatStage(val label: String) {
    object ZeroFill : FormatStage("Sektörler sıfırlanıyor (Zero-Fill)")
    object WritingFileSystem : FormatStage("Dosya sistemi yazılıyor")
    object Done : FormatStage("Tamamlandı")
}

data class FormatProgress(val stage: FormatStage, val percent: Int)

sealed class FormatResult {
    object Success : FormatResult()
    data class Unsupported(val fs: FileSystemType) : FormatResult()
    data class Failed(val error: Throwable) : FormatResult()
}

/**
 * Bicimlendirme / Low-Level Format orkestratoru.
 *
 * Tum agir islemler Dispatchers.IO uzerinde calisir; UI thread'e SADECE
 * `onProgress` callback'i uzerinden ilerleme yuzdesi bildirilir (ViewModel
 * bunu LiveData/StateFlow ile ana thread'e tasir). Bu, "UI Thread
 * Izolasyonu" gereksiniminin kod tarafindaki karsiligidir.
 */
object FormatEngine {

    private const val ZERO_CHUNK_SECTORS = 2048 // ~1 MB'lik parcalar halinde yaz

    suspend fun run(
        raw: RawBlockDevice,
        targetFs: FileSystemType,
        mode: FormatMode,
        onProgress: (FormatProgress) -> Unit
    ): FormatResult = withContext(Dispatchers.IO) {
        try {
            if (mode == FormatMode.LOW_LEVEL_ZERO_FILL) {
                zeroFillEntireDevice(raw) { pct ->
                    onProgress(FormatProgress(FormatStage.ZeroFill, pct))
                }
            }

            when (targetFs.uygulamaDurumu) {
                FileSystemType.SupportLevel.IMPLEMENTED -> {
                    // Su an icin tek gercek yazici: FAT32
                    Fat32Formatter.format(raw) { pct ->
                        onProgress(FormatProgress(FormatStage.WritingFileSystem, pct))
                    }
                    onProgress(FormatProgress(FormatStage.Done, 100))
                    FormatResult.Success
                }
                FileSystemType.SupportLevel.ROADMAP -> {
                    FormatResult.Unsupported(targetFs)
                }
            }
        } catch (t: Throwable) {
            FormatResult.Failed(t)
        }
    }

    /**
     * Diskin TAMAMINI (kullanilan/kullanilmayan tum sektorler) '0' ile
     * doldurur -> veritabani seviyesinde kurtarilamaz temizlik.
     * RawBlockDevice sayesinde bu islem dogrudan SCSI WRITE(10) komutlariyla
     * yapilir; Android sayfa onbellegi devre disidir.
     */
    private suspend fun zeroFillEntireDevice(
        raw: RawBlockDevice,
        onProgress: (Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        val chunkBytes = raw.blockSizeBytes * ZERO_CHUNK_SECTORS
        val zeroChunk = ByteBuffer.allocate(chunkBytes)
        var offset = 0L
        val total = raw.totalBytes

        while (offset < total) {
            val remaining = total - offset
            val thisChunkSize = if (remaining < chunkBytes) remaining.toInt() else chunkBytes

            zeroChunk.rewind()
            zeroChunk.limit(thisChunkSize)
            raw.writeAt(offset, zeroChunk)
            zeroChunk.limit(zeroChunk.capacity())

            offset += thisChunkSize
            onProgress(((offset * 100) / total).toInt().coerceIn(0, 100))
        }
    }
}
