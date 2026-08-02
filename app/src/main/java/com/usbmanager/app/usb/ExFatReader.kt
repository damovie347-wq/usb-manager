package com.usbmanager.app.usb

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * exFAT icin SIFIRDAN yazilmis, SADECE-OKUMA bir dosya sistemi okuyucusu
 * (bkz. RawVolumeReader.kt basindaki BILINCLI KAPSAM SINIRI notu).
 *
 * Neden gerekli: libaums'un genel API'si exFAT'i ANLAMIYOR (bkz.
 * RawFileSystemSniffer). Bu sinif, ExFatFormatter'in yazdigi ILE AYNI
 * (Microsoft exFAT spesifikasyonuna uygun) alan duzenini okur:
 *  - Onyukleme sektoru: FatOffset/FatLength/ClusterHeapOffset/ClusterCount/
 *    FirstClusterOfRootDirectory (bkz. ExFatFormatter.writeBootRegion).
 *  - Dizin girisleri: 0x85 (Dosya) + 0xC0 (Akis Uzantisi) + 0xC1.. (Ad)
 *    uclusu/seti (bkz. Microsoft exFAT spec, bolum 7).
 *  - Kume zinciri: FAT tablosu UZERINDEN (kok dizin HER ZAMAN boyle) ya da,
 *    dosya/alt-dizin NoFatChain bayragini set etmisse, ARDISIK kumeler
 *    (bu sadece bir OKUMA HIZI optimizasyonudur, yazicilar/FAT girmeden de
 *    calisir -- bkz. Stream Extension girisindeki GeneralSecondaryFlags).
 */
class ExFatReader private constructor(
    private val raw: RawBlockDevice,
    private val partitionStartSector: Long,
    private val bytesPerSector: Int,
    private val sectorsPerCluster: Int,
    private val fatOffsetSector: Long,
    private val clusterHeapOffsetSector: Long,
    private val clusterCountTotal: Long,
    private val rootDirCluster: Long
) : RawVolumeReader {

    private val clusterBytes: Int = bytesPerSector * sectorsPerCluster
    private var closed = false

    override val fileSystemLabel: String = "exFAT"

    override val root: RawDirEntry = RawDirEntry(
        name = "", isDirectory = true, sizeBytes = 0L, modifiedEpochMillis = 0L,
        token = ChainRef(rootDirCluster, noFatChain = false, lengthBytes = -1L)
    )

    /** Bir dosya/dizinin ham kume zincirine ait bilgiler (RawDirEntry.token icinde tasinir). */
    private data class ChainRef(val firstCluster: Long, val noFatChain: Boolean, val lengthBytes: Long)

    // ------------------------------------------------------------------
    // Genel API
    // ------------------------------------------------------------------

    override fun list(dir: RawDirEntry): List<RawDirEntry> {
        val ref = dir.token as? ChainRef ?: return emptyList()
        val bytes = if (ref.lengthBytes < 0) {
            readChainUntilFatEof(ref.firstCluster)
        } else {
            readChainForLength(ref.firstCluster, ref.noFatChain, ref.lengthBytes)
        }
        return parseDirectoryEntries(bytes)
    }

    override fun openStream(file: RawDirEntry): InputStream {
        val ref = file.token as ChainRef
        return ChainInputStream(ref.firstCluster, ref.noFatChain, ref.lengthBytes.coerceAtLeast(0L))
    }

    override fun spaceInfo(): RawVolumeSpace? = runCatching {
        val capacity = clusterCountTotal * clusterBytes.toLong()
        val bitmap = findAllocationBitmap() ?: return RawVolumeSpace(capacity, null)
        // NOT: Allocation Bitmap girisinin (0x85'in aksine) bir "NoFatChain"
        // bayragi YOKTUR -- BitmapFlags alaninin tek anlamli biti ikinci
        // (TexFAT) bitmap icindir. Bu yuzden bitmap HER ZAMAN normal FAT
        // zinciri ile okunur (bkz. ExFatFormatter.writeFatTable -> writeChain).
        val bitmapBytes = readChainForLength(bitmap.first, noFatChain = false, length = bitmap.second)
        var usedClusters = 0L
        for (b in bitmapBytes) {
            usedClusters += Integer.bitCount(b.toInt() and 0xFF)
        }
        RawVolumeSpace(capacity, usedClusters * clusterBytes.toLong())
    }.getOrNull()

    override fun close() {
        if (closed) return
        closed = true
        runCatching { raw.close() }
    }

    // ------------------------------------------------------------------
    // Dizin girisi (0x85 + 0xC0 + 0xC1..) ayristirma
    // ------------------------------------------------------------------

    private fun parseDirectoryEntries(buf: ByteArray): List<RawDirEntry> {
        val out = ArrayList<RawDirEntry>()
        var pos = 0
        while (pos + 32 <= buf.size) {
            val type = buf[pos].toInt() and 0xFF
            if (type != 0x85) {
                pos += 32
                continue
            }
            val parsed = runCatching { parseOneFileEntrySet(buf, pos) }.getOrNull()
            if (parsed == null) {
                pos += 32
                continue
            }
            out.add(parsed.first)
            pos = parsed.second
        }
        return out
    }

    /**
     * `pos` konumundaki 0x85 (Dosya) girisinden baslayan TAM bir 0x85+0xC0+0xC1..
     * setini ayristirir. Herhangi bir tutarsizlikta exception firlatir (cagiran
     * `runCatching` ile yakalayip o SLOTU atlar -- boylece TEK bozuk bir giris
     * TUM listeyi bozmaz, bkz. RawVolumeReader.kt).
     */
    private fun parseOneFileEntrySet(buf: ByteArray, pos: Int): Pair<RawDirEntry, Int> {
        val secondaryCount = buf[pos + 1].toInt() and 0xFF
        require(secondaryCount >= 1) { "secondaryCount < 1" }
        val setEndExclusive = pos + 32 * (1 + secondaryCount)
        require(setEndExclusive <= buf.size) { "giris seti sinirlarin disina tasiyor" }

        val attrs = readU16(buf, pos + 4)
        val isDir = (attrs and 0x10) != 0
        val modified = exFatTimestampToMillis(readU32(buf, pos + 12))

        val streamPos = pos + 32
        require((buf[streamPos].toInt() and 0xFF) == 0xC0) { "0xC0 Akis Uzantisi bekleniyordu" }
        val flags = buf[streamPos + 1].toInt() and 0xFF
        val noFatChain = (flags and 0x02) != 0
        val nameLenChars = buf[streamPos + 3].toInt() and 0xFF
        val firstCluster = readU32(buf, streamPos + 20)
        val dataLength = readU64(buf, streamPos + 24)

        val nameEntryCount = secondaryCount - 1
        val nameBuf = ByteArrayOutputStream()
        for (i in 0 until nameEntryCount) {
            val npos = streamPos + 32 * (i + 1)
            require((buf[npos].toInt() and 0xFF) == 0xC1) { "0xC1 Ad girisi bekleniyordu" }
            nameBuf.write(buf, npos + 2, 30)
        }
        val fullName = String(nameBuf.toByteArray(), Charsets.UTF_16LE)
        val name = if (nameLenChars in 0..fullName.length) fullName.substring(0, nameLenChars) else fullName.trimEnd('\u0000')
        require(name.isNotEmpty()) { "bos dosya adi" }

        val entry = RawDirEntry(
            name = name,
            isDirectory = isDir,
            sizeBytes = if (isDir) 0L else dataLength,
            modifiedEpochMillis = modified,
            token = ChainRef(firstCluster, noFatChain, dataLength)
        )
        return entry to setEndExclusive
    }

    /** Kok dizindeki 0x81 (Ayirma Haritasi) girisini bulur: (ilkKume, DataLength). */
    private fun findAllocationBitmap(): Pair<Long, Long>? {
        val rootBytes = readChainUntilFatEof(rootDirCluster)
        var pos = 0
        while (pos + 32 <= rootBytes.size) {
            val type = rootBytes[pos].toInt() and 0xFF
            if (type == 0x81) {
                val firstCluster = readU32(rootBytes, pos + 20)
                val dataLength = readU64(rootBytes, pos + 24)
                return firstCluster to dataLength
            }
            pos += 32
        }
        return null
    }

    // ------------------------------------------------------------------
    // Kume zinciri okuma
    // ------------------------------------------------------------------

    private fun readCluster(cluster: Long): ByteArray {
        val startSector = partitionStartSector + clusterHeapOffsetSector + (cluster - 2) * sectorsPerCluster
        return RawIoUtils.readAligned(raw, startSector * bytesPerSector, clusterBytes)
    }

    private fun nextClusterInFat(cluster: Long): Long {
        val byteOffset = (partitionStartSector + fatOffsetSector) * bytesPerSector + cluster * 4
        val bytes = RawIoUtils.readAligned(raw, byteOffset, 4)
        return readU32(bytes, 0)
    }

    /** Kok dizin icin: HER ZAMAN FAT-zincirli, dogal EOF'a (>= 0xFFFFFFF8) kadar oku. */
    private fun readChainUntilFatEof(firstCluster: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var cluster = firstCluster
        val visited = HashSet<Long>()
        var guard = 0
        while (cluster >= 2 && cluster < clusterCountTotal + 2 && visited.add(cluster) && guard < 4_000_000) {
            out.write(readCluster(cluster))
            cluster = nextClusterInFat(cluster)
            guard++
        }
        return out.toByteArray()
    }

    /** Dosyalar/alt dizinler icin: BILINEN bayt uzunlugu kadar oku. */
    private fun readChainForLength(firstCluster: Long, noFatChain: Boolean, length: Long): ByteArray {
        if (length <= 0L) return ByteArray(0)
        val out = ByteArrayOutputStream(minOf(length, 4L * 1024 * 1024).toInt().coerceAtLeast(64))
        var remaining = length
        var cluster = firstCluster
        val visited = HashSet<Long>()
        while (remaining > 0) {
            if (cluster < 2 || cluster >= clusterCountTotal + 2) break
            if (!noFatChain && !visited.add(cluster)) break
            val chunk = readCluster(cluster)
            val take = minOf(chunk.size.toLong(), remaining).toInt()
            out.write(chunk, 0, take)
            remaining -= take
            cluster = if (noFatChain) cluster + 1 else nextClusterInFat(cluster)
        }
        return out.toByteArray()
    }

    /** Dosya icerigini kume-kume, TALEP UZERINE (belegi sismetetmeden) akitan InputStream. */
    private inner class ChainInputStream(
        firstCluster: Long,
        private val noFatChain: Boolean,
        private val totalLength: Long
    ) : InputStream() {
        private var cluster = firstCluster
        private var remaining = totalLength
        private var buffer = ByteArray(0)
        private var bufferPos = 0
        private val visited = HashSet<Long>()

        private fun ensureData(): Boolean {
            if (bufferPos < buffer.size) return true
            if (remaining <= 0) return false
            if (cluster < 2 || cluster >= clusterCountTotal + 2) return false
            if (!noFatChain && !visited.add(cluster)) return false
            val chunk = readCluster(cluster)
            val take = minOf(chunk.size.toLong(), remaining).toInt()
            buffer = if (take == chunk.size) chunk else chunk.copyOf(take)
            bufferPos = 0
            remaining -= take
            cluster = if (noFatChain) cluster + 1 else nextClusterInFat(cluster)
            return buffer.isNotEmpty()
        }

        override fun read(): Int {
            if (!ensureData()) return -1
            return buffer[bufferPos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!ensureData()) return -1
            val avail = buffer.size - bufferPos
            val take = minOf(avail, len)
            System.arraycopy(buffer, bufferPos, b, off, take)
            bufferPos += take
            return take
        }
    }

    companion object {
        /** exFAT olarak taninirsa bir okuyucu dondurur; degilse (veya herhangi bir tutarsizlikta) null. */
        fun tryOpen(raw: RawBlockDevice): ExFatReader? = runCatching {
            val vbr = RawFileSystemSniffer.locateVbr(raw) ?: return null
            if (vbr.label != "exFAT") return null
            val s = vbr.sectorBytes

            val bytesPerSectorShift = s[0x6C].toInt() and 0xFF
            val sectorsPerClusterShift = s[0x6D].toInt() and 0xFF
            val bytesPerSector = 1 shl bytesPerSectorShift
            val sectorsPerCluster = 1 shl sectorsPerClusterShift
            val fatOffset = readU32(s, 0x50)
            val clusterHeapOffset = readU32(s, 0x58)
            val clusterCount = readU32(s, 0x5C)
            val rootCluster = readU32(s, 0x60)

            if (bytesPerSector <= 0 || sectorsPerCluster <= 0) return null
            if (bytesPerSector != raw.blockSizeBytes) return null
            if (rootCluster < 2 || clusterCount <= 0) return null

            ExFatReader(
                raw = raw,
                partitionStartSector = vbr.vbrSectorLba,
                bytesPerSector = bytesPerSector,
                sectorsPerCluster = sectorsPerCluster,
                fatOffsetSector = fatOffset,
                clusterHeapOffsetSector = clusterHeapOffset,
                clusterCountTotal = clusterCount,
                rootDirCluster = rootCluster
            )
        }.getOrNull()
    }
}

// ------------------------------------------------------------------
// Kucuk ikili-okuma yardimcilari (bu dosya ve NtfsReader tarafindan kullanilir)
// ------------------------------------------------------------------

internal fun readU16(b: ByteArray, off: Int): Int =
    (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

internal fun readU32(b: ByteArray, off: Int): Long =
    ((b[off].toInt() and 0xFF).toLong()) or
        ((b[off + 1].toInt() and 0xFF).toLong() shl 8) or
        ((b[off + 2].toInt() and 0xFF).toLong() shl 16) or
        ((b[off + 3].toInt() and 0xFF).toLong() shl 24)

internal fun readS32(b: ByteArray, off: Int): Int =
    (b[off].toInt() and 0xFF) or
        ((b[off + 1].toInt() and 0xFF) shl 8) or
        ((b[off + 2].toInt() and 0xFF) shl 16) or
        ((b[off + 3].toInt() and 0xFF) shl 24)

internal fun readU64(b: ByteArray, off: Int): Long {
    var v = 0L
    for (i in 0 until 8) v = v or ((b[off + i].toLong() and 0xFF) shl (8 * i))
    return v
}

/** exFAT'in 32-bitlik paketlenmis zaman damgasini (bkz. spec 7.4.8) epoch-ms'ye cevirir. */
internal fun exFatTimestampToMillis(packed: Long): Long {
    if (packed == 0L) return 0L
    val p = packed.toInt()
    val year = 1980 + ((p ushr 25) and 0x7F)
    val month = ((p ushr 21) and 0x0F).coerceIn(1, 12)
    val day = ((p ushr 16) and 0x1F).coerceIn(1, 31)
    val hour = ((p ushr 11) and 0x1F).coerceIn(0, 23)
    val minute = ((p ushr 5) and 0x3F).coerceIn(0, 59)
    val second = ((p and 0x1F) * 2).coerceIn(0, 59)
    return runCatching {
        java.time.LocalDateTime.of(year, month, day, hour, minute, second)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }.getOrDefault(0L)
}
