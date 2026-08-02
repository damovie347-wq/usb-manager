package com.usbmanager.app.usb

import java.nio.ByteBuffer

/**
 * KOK NEDEN (bicimlendirmenin dakikalar surmesi): Fat32Formatter/ExFatFormatter/
 * NtfsFormatter, FAT tablosu / Allocation Bitmap / $MFT gibi BUYUK (genelde
 * birkac MB - birkac onlarca MB) bolgeleri, `raw.writeAt()`'i HER SEKTOR
 * (512 bayt) icin AYRI AYRI cagirarak yaziyordu. `ScsiRawBlockDevice.writeAt()`
 * her cagrida TAM bir USB Bulk-Only Transport dongusu (CBW gonder + veri fazi +
 * CSW oku = en az 3 ayri USB bulkTransfer) calistirir; bunun binlerce kez
 * tekrarlanmasi (16 MB'lik bir FAT tablosu icin 32.000+ cagri gibi) START
 * gecikmesi yuzunden dakikalarca surer -- oysa GERCEKTE `writeAt()` ZATEN
 * KENDI ICINDE 32 KB'lik SCSI WRITE(10) komutlarina bolup gonderiyor, yani
 * TEK bir `writeAt()` cagrisina 1 MB'lik bir arabellek vermek TAMAMEN GUVENLI
 * ve neredeyse ayni sayida gercek SCSI komutu uretir -- sadece 2000 kat DAHA AZ
 * fonksiyon cagrisi/USB-round-trip-baslatma maliyetiyle.
 *
 * Bu yardimci, TUM formatter'larin ayni "buyuk parca" stratejisini kullanmasini
 * saglar (FormatEngine.zeroFillEntireDevice'taki ~1 MB'lik parca boyutuyla
 * TUTARLI).
 */
object RawIoUtils {

    private const val DEFAULT_CHUNK_BYTES = 1024 * 1024 // ~1 MB

    /**
     * `RawBlockDevice.readAt()` HER ZAMAN sektore hizali bir ofset ister (bkz.
     * RawBlockDevice.kt). NTFS/exFAT okuyucularinin (ExFatReader/NtfsReader)
     * FAT girisi / MFT kaydi gibi KUCUK ve GENELDE HIZALANMAMIS alanlari
     * okuyabilmesi icin: istenen [byteOffset, byteOffset+length) araligini
     * kapsayan TAM sektorleri okuyup, sonra istenen alt-araligi keser.
     */
    fun readAligned(raw: RawBlockDevice, byteOffset: Long, length: Int): ByteArray {
        val sectorSize = raw.blockSizeBytes.toLong()
        val startSector = byteOffset / sectorSize
        val endExclusive = byteOffset + length
        val sectorCount = ((endExclusive - startSector * sectorSize) + sectorSize - 1) / sectorSize
        val buf = ByteBuffer.allocate((sectorCount * sectorSize).toInt())
        raw.readAt(startSector * sectorSize, buf)
        buf.rewind()
        val full = ByteArray(buf.remaining())
        buf.get(full)
        val startInBuf = (byteOffset - startSector * sectorSize).toInt()
        return full.copyOfRange(startInBuf, startInBuf + length)
    }

    /** `data`'yi (gerekirse pedallanarak) BUYUK PARCALAR halinde yazar. */
    fun writeBulk(raw: RawBlockDevice, startByteOffset: Long, data: ByteArray, chunkBytes: Int = DEFAULT_CHUNK_BYTES) {
        var offset = 0
        while (offset < data.size) {
            val size = minOf(chunkBytes, data.size - offset)
            val buf = ByteBuffer.allocate(size)
            buf.put(data, offset, size)
            buf.rewind()
            raw.writeAt(startByteOffset + offset, buf)
            offset += size
        }
    }

    /** Belirtilen bayt araligini SIFIRLA doldurur, BUYUK PARCALAR halinde. */
    fun zeroFill(
        raw: RawBlockDevice, startByteOffset: Long, totalBytes: Long,
        chunkBytes: Int = DEFAULT_CHUNK_BYTES, onProgress: ((Int) -> Unit)? = null
    ) {
        if (totalBytes <= 0) return
        val zero = ByteBuffer.allocate(minOf(chunkBytes.toLong(), totalBytes).toInt())
        var done = 0L
        while (done < totalBytes) {
            val size = minOf(chunkBytes.toLong(), totalBytes - done).toInt()
            zero.rewind(); zero.limit(size)
            raw.writeAt(startByteOffset + done, zero)
            zero.limit(zero.capacity())
            done += size
            onProgress?.invoke(((done * 100) / totalBytes).toInt().coerceIn(0, 100))
        }
    }
}
