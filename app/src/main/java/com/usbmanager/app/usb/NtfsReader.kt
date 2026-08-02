package com.usbmanager.app.usb

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * NTFS icin SIFIRDAN yazilmis, SADECE-OKUMA bir dosya sistemi okuyucusu
 * (bkz. RawVolumeReader.kt basindaki BILINCLI KAPSAM SINIRI notu).
 *
 * Neden gerekli: libaums'un genel API'si NTFS'i ANLAMIYOR (bkz.
 * RawFileSystemSniffer). Bu sinif, NtfsFormatter'in yazdigi ILE AYNI
 * (standart NTFS) alan duzenini okur -- onyukleme sektoru alanlari, MFT
 * kaydi basligi, oznitelik (attribute) basligi, $FILE_NAME icerigi ve
 * $INDEX_ROOT/$INDEX_HEADER duzeni icin bkz. NtfsFormatter.kt icindeki
 * `writeBootSector` / `buildMftRecord` / `buildFileName` / `buildEmptyIndexRoot`.
 *
 * NASIL CALISIR (ozet):
 *  1) Onyukleme sektorunden $MFT'nin baslangic kumesi + kayit/kume
 *     boyutlari okunur.
 *  2) $MFT'nin KENDI kaydi (kayit 0), o baslangic kumesinin TAM basindan
 *     BOOTSTRAP olarak dogrudan okunur -- bu, HERHANGI bir MFT kaydini
 *     okuyabilmek icin gereken $MFT'nin kendi veri parcalarini (data run)
 *     verir.
 *  3) Bundan sonra herhangi bir MFT kayit numarasi, o parcalar UZERINDEN
 *     mantiksal->fiziksel kume cevirimi yapilarak okunabilir.
 *  4) Bir dizinin icerigi: $INDEX_ROOT'taki (kucuk dizinler icin yeterli)
 *     girisler + (varsa) $INDEX_ALLOCATION akisindaki TUM indeks
 *     arabelleklerinin DUZ TARANMASI (gercek bir B+ agaci GEZINMESI
 *     YAPILMAZ -- bkz. asagidaki BILINCLI KAPSAM SINIRI) ile elde edilir.
 *
 * BILINCLI KAPSAM SINIRI (ayrica bkz. RawVolumeReader.kt):
 *  - $INDEX_ALLOCATION icin GERCEK bir B+ agaci gezinmesi YAPILMAZ; bunun
 *    yerine $BITMAP ile "kullanimda" isaretli TUM indeks arabellekleri
 *    TEK TEK taranip icindeki gercek dosya/dizin girisleri toplanir. Bir
 *    B+ agacindaki TUM anahtarlar zaten agacin bir yerinde bir yaprak/ic
 *    dugumde bulunur; bu yuzden DUZ TARAMA, siralama/hizli-arama saglamaz
 *    ama TAM ve DOGRU bir listeleme sonucu verir (bir dosya yoneticisi
 *    icin ihtiyac duyulan budur).
 *  - Sikistirilmis/sifrelenmis (EFS) dosyalar, "reparse point"ler ve
 *    $ATTRIBUTE_LIST'e (asiri parcalanmis dosyalar/coklu adli akislar)
 *    yayilan oznitelikler ozel olarak ELE ALINMAZ -- boyle bir dosya
 *    TESPIT edilirse (attribute list bulunur veya veri okunamazsa) o TEK
 *    dosya/girisi atlanir, geri kalan listeleme BOZULMAZ.
 *  - Yazma / silme / tasima / yeniden adlandirma YOKTUR.
 */
class NtfsReader private constructor(
    private val raw: RawBlockDevice,
    private val partitionStartSector: Long,
    private val bytesPerSector: Int,
    private val clusterBytes: Int,
    private val mftRecordSize: Int,
    private val indexRecordSize: Int,
    private val totalClusters: Long,
    private val mftDataRuns: List<DataRun>
) : RawVolumeReader {

    private var closed = false

    override val fileSystemLabel: String = "NTFS"

    override val root: RawDirEntry = RawDirEntry(
        name = "", isDirectory = true, sizeBytes = 0L, modifiedEpochMillis = 0L,
        token = MftRef(REC_ROOT)
    )

    private data class MftRef(val recordNumber: Long)

    /**
     * KRITIK: bir LCN'in (mantiksal kume numarasi) MUTLAK bayt ofsetini hesaplar.
     * `partitionStartSector` DAHIL EDILMEZSE, bu okuyucu SADECE bolum
     * tablosu olmayan ("superfloppy") duzendeki birimlerde calisir --
     * ancak GERCEK Windows USB'lerinin BUYUK COGUNLUGU bir MBR bolum
     * tablosuyla bicimlendirilir (NTFS bolumu sektor 0'dan BASLAMAZ).
     * Bu yuzden HER kume adresi hesaplamasi MUTLAKA bu fonksiyondan
     * gecmelidir -- dogrudan `lcn * clusterBytes` YAZILMAMALIDIR.
     */
    private fun clusterByteOffset(lcn: Long): Long =
        partitionStartSector * bytesPerSector + lcn * clusterBytes

    // ------------------------------------------------------------------
    // Genel API
    // ------------------------------------------------------------------

    override fun list(dir: RawDirEntry): List<RawDirEntry> {
        val ref = dir.token as? MftRef ?: return emptyList()
        return runCatching { listDirectory(ref.recordNumber) }.getOrDefault(emptyList())
    }

    override fun openStream(file: RawDirEntry): InputStream {
        val ref = file.token as MftRef
        val record = readMftRecordRaw(ref.recordNumber) ?: return ByteArrayInputStream(ByteArray(0))
        val attrs = parseAttributes(record)
        val dataAttr = attrs.firstOrNull { it.type == AT_DATA && it.name.isEmpty() }
            ?: return ByteArrayInputStream(ByteArray(0))
        return if (dataAttr.resident) {
            ByteArrayInputStream(dataAttr.content ?: ByteArray(0))
        } else {
            RunsInputStream(dataAttr.runs ?: emptyList(), dataAttr.realSize)
        }
    }

    override fun spaceInfo(): RawVolumeSpace? = runCatching {
        val capacity = totalClusters * clusterBytes.toLong()
        val bitmapRecord = readMftRecordRaw(REC_BITMAP) ?: return RawVolumeSpace(capacity, null)
        val dataAttr = parseAttributes(bitmapRecord).firstOrNull { it.type == AT_DATA && it.name.isEmpty() }
            ?: return RawVolumeSpace(capacity, null)
        val bitmapBytes = readAttrContent(dataAttr)
        val limit = minOf(bitmapBytes.size.toLong(), (totalClusters + 7) / 8).toInt()
        var usedClusters = 0L
        for (i in 0 until limit) {
            usedClusters += Integer.bitCount(bitmapBytes[i].toInt() and 0xFF)
        }
        RawVolumeSpace(capacity, usedClusters * clusterBytes.toLong())
    }.getOrNull()

    override fun close() {
        if (closed) return
        closed = true
        runCatching { raw.close() }
    }

    // ------------------------------------------------------------------
    // Dizin listeleme
    // ------------------------------------------------------------------

    private fun listDirectory(recordNumber: Long): List<RawDirEntry> {
        val record = readMftRecordRaw(recordNumber) ?: return emptyList()
        val attrs = parseAttributes(record)
        val indexRoot = attrs.firstOrNull { it.type == AT_INDEX_ROOT && it.name == "\$I30" } ?: return emptyList()
        val rootContent = readAttrContent(indexRoot)
        if (rootContent.size < 32) return emptyList()

        // En iyi (tercih edilen ad alani) girisi MFT kayit numarasina gore
        // TEKILLESTIRIR -- ayni dosya hem WIN32 hem DOS(8.3) adiyla İKİ
        // ayri indeks girisine sahip olabilir (bkz. dosya basi notu).
        val best = LinkedHashMap<Long, FileNameInfo>()
        fun consider(mftRef: Long, fnContent: ByteArray?) {
            if (fnContent == null) return
            val info = runCatching { parseFileNameAttr(fnContent) }.getOrNull() ?: return
            val recNum = mftRef and 0xFFFFFFFFFFFFL
            if (recNum == 0L) return
            val existing = best[recNum]
            if (existing == null || namespaceRank(info.namespace) < namespaceRank(existing.namespace)) {
                best[recNum] = info.copy(mftRecordNumber = recNum)
            }
        }

        val ihStart = 16
        val entriesOffRel = readS32(rootContent, ihStart)
        val entriesUsedRel = readS32(rootContent, ihStart + 4)
        val ihFlags = rootContent[ihStart + 12].toInt() and 0xFF
        val absStart = ihStart + entriesOffRel
        val absEnd = ihStart + entriesUsedRel
        if (absStart in 0..rootContent.size && absEnd in absStart..rootContent.size) {
            for (e in parseIndexEntriesRange(rootContent, absStart, absEnd)) {
                consider(e.mftRef, e.fileNameContent)
            }
        }

        val isLargeIndex = (ihFlags and 0x01) != 0
        if (isLargeIndex) {
            val indexAlloc = attrs.firstOrNull { it.type == AT_INDEX_ALLOCATION && it.name == "\$I30" }
            val allocRuns = indexAlloc?.runs
            if (indexAlloc != null && !allocRuns.isNullOrEmpty()) {
                val bitmapAttr = attrs.firstOrNull { it.type == AT_BITMAP && it.name == "\$I30" }
                val bitmapBytes = bitmapAttr?.let { readAttrContent(it) }
                val totalVcns = if (indexRecordSize > 0) indexAlloc.realSize / indexRecordSize else 0L
                var vcn = 0L
                var guard = 0
                while (vcn < totalVcns && guard < 2_000_000) {
                    guard++
                    val inUse = bitmapBytes == null || isBitSet(bitmapBytes, vcn)
                    if (inUse) {
                        val lcn = resolveVcnToLcn(allocRuns, vcn)
                        if (lcn != null) {
                            val bufOrNull = runCatching {
                                RawIoUtils.readAligned(raw, clusterByteOffset(lcn), indexRecordSize)
                            }.getOrNull()
                            if (bufOrNull != null && applyFixupGeneric(bufOrNull, "INDX")) {
                                val bIhStart = 0x18
                                if (bufOrNull.size >= bIhStart + 16) {
                                    val bOffRel = readS32(bufOrNull, bIhStart)
                                    val bUsedRel = readS32(bufOrNull, bIhStart + 4)
                                    val bAbsStart = bIhStart + bOffRel
                                    val bAbsEnd = bIhStart + bUsedRel
                                    if (bAbsStart in 0..bufOrNull.size && bAbsEnd in bAbsStart..bufOrNull.size) {
                                        for (e in parseIndexEntriesRange(bufOrNull, bAbsStart, bAbsEnd)) {
                                            consider(e.mftRef, e.fileNameContent)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    vcn++
                }
            }
        }

        return best.values.filter { it.name.isNotEmpty() }.map { info ->
            RawDirEntry(
                name = info.name,
                isDirectory = info.isDirectory,
                sizeBytes = info.realSize,
                modifiedEpochMillis = info.modifiedMillis,
                token = MftRef(info.mftRecordNumber)
            )
        }
    }

    private fun isBitSet(bitmap: ByteArray, index: Long): Boolean {
        val byteIdx = (index / 8)
        if (byteIdx < 0 || byteIdx >= bitmap.size) return false
        val bitIdx = (index % 8).toInt()
        return (bitmap[byteIdx.toInt()].toInt() and (1 shl bitIdx)) != 0
    }

    // ------------------------------------------------------------------
    // MFT kaydi okuma (fixup uygulanmis) + oznitelik icerigi okuma
    // ------------------------------------------------------------------

    private fun readMftRecordRaw(recordNumber: Long): ByteArray? {
        if (recordNumber < 0 || clusterBytes <= 0 || mftRecordSize <= 0) return null
        val recordsPerCluster = clusterBytes / mftRecordSize
        if (recordsPerCluster <= 0) return null
        val vcn = recordNumber / recordsPerCluster
        val withinIndex = recordNumber % recordsPerCluster
        val lcn = resolveVcnToLcn(mftDataRuns, vcn) ?: return null
        val byteOffset = clusterByteOffset(lcn) + withinIndex * mftRecordSize
        val buf = runCatching { RawIoUtils.readAligned(raw, byteOffset, mftRecordSize) }.getOrNull() ?: return null
        return if (applyFixupGeneric(buf, "FILE")) buf else null
    }

    private fun readAttrContent(attr: NtfsAttr): ByteArray = if (attr.resident) {
        attr.content ?: ByteArray(0)
    } else {
        readRunsForLength(attr.runs ?: emptyList(), attr.realSize)
    }

    /** Verilen data-run listesinden TAM `length` bayt kadar veriyi bellekte toplar (dizin/bitmap gibi KUCUK akislar icin). */
    private fun readRunsForLength(runs: List<DataRun>, length: Long): ByteArray {
        if (length <= 0L || clusterBytes <= 0) return ByteArray(0)
        val out = ByteArrayOutputStream(minOf(length, 4L * 1024 * 1024).toInt().coerceAtLeast(64))
        var remaining = length
        var vcn = 0L
        var guard = 0
        while (remaining > 0 && guard < 2_000_000) {
            guard++
            val take = minOf(clusterBytes.toLong(), remaining).toInt()
            val lcn = resolveVcnToLcn(runs, vcn)
            if (lcn == null) {
                out.write(ByteArray(take)) // seyrek (sparse) kume: sifirla doldur
            } else {
                val chunk = runCatching { RawIoUtils.readAligned(raw, clusterByteOffset(lcn), clusterBytes) }.getOrNull()
                if (chunk == null) {
                    out.write(ByteArray(take))
                } else {
                    out.write(chunk, 0, take)
                }
            }
            remaining -= take
            vcn++
        }
        return out.toByteArray()
    }

    /** Dosya icerigini kume-kume, TALEP UZERINE akitan InputStream (buyuk dosyalarda tamamini bellege yuklemez). */
    private inner class RunsInputStream(
        private val runs: List<DataRun>,
        private val totalLength: Long
    ) : InputStream() {
        private var posBytes = 0L
        private var buffer = ByteArray(0)
        private var bufferPos = 0

        private fun ensureData(): Boolean {
            if (bufferPos < buffer.size) return true
            if (posBytes >= totalLength || clusterBytes <= 0) return false
            val vcn = posBytes / clusterBytes
            val lcn = resolveVcnToLcn(runs, vcn)
            val offsetInCluster = (posBytes % clusterBytes).toInt()
            val remainingInCluster = clusterBytes - offsetInCluster
            val remainingTotal = minOf((totalLength - posBytes), remainingInCluster.toLong()).toInt()
            val chunk = if (lcn == null) {
                ByteArray(clusterBytes)
            } else {
                runCatching { RawIoUtils.readAligned(raw, clusterByteOffset(lcn), clusterBytes) }.getOrNull()
                    ?: return false
            }
            buffer = chunk.copyOfRange(offsetInCluster, offsetInCluster + remainingTotal)
            bufferPos = 0
            posBytes += remainingTotal
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
        private const val SECTOR_SIZE = 512
        private const val REC_ROOT = 5L
        private const val REC_BITMAP = 6L
        private const val AT_DATA = 0x80
        private const val AT_INDEX_ROOT = 0x90
        private const val AT_INDEX_ALLOCATION = 0xA0
        private const val AT_BITMAP = 0xB0

        /** NTFS olarak taninirsa bir okuyucu dondurur; degilse (veya herhangi bir tutarsizlikta) null. */
        fun tryOpen(raw: RawBlockDevice): NtfsReader? = runCatching {
            val vbr = RawFileSystemSniffer.locateVbr(raw) ?: return null
            if (vbr.label != "NTFS") return null
            val s = vbr.sectorBytes

            val bytesPerSector = readU16(s, 0x0B)
            val sectorsPerCluster = s[0x0D].toInt() and 0xFF
            if (bytesPerSector <= 0 || sectorsPerCluster <= 0) return null
            if (bytesPerSector != raw.blockSizeBytes) return null
            val clusterBytesLocal = bytesPerSector * sectorsPerCluster

            val totalSectors = readU64(s, 0x28)
            val mftStartCluster = readU64(s, 0x30)
            val clustersPerMftRecordRaw = s[0x40].toInt() // imzali bayt
            val clustersPerIndexBufferRaw = s[0x44].toInt() // imzali bayt

            val mftRecordSizeLocal = sizeFromSignedField(clustersPerMftRecordRaw, clusterBytesLocal)
            val indexRecordSizeLocal = sizeFromSignedField(clustersPerIndexBufferRaw, clusterBytesLocal)
            if (mftRecordSizeLocal <= 0 || indexRecordSizeLocal <= 0) return null
            if (mftRecordSizeLocal % SECTOR_SIZE != 0 || indexRecordSizeLocal % SECTOR_SIZE != 0) return null

            val totalClustersLocal = totalSectors / sectorsPerCluster
            if (mftStartCluster < 2 || totalClustersLocal <= 0) return null

            // --- BOOTSTRAP: $MFT'nin KENDI kaydi (kayit 0) dogrudan, mftStartCluster'in
            // TAM basindan okunur (bkz. sinif basi mimari notu). ---
            val record0Offset = vbr.vbrSectorLba * bytesPerSector + mftStartCluster * clusterBytesLocal
            val record0 = RawIoUtils.readAligned(raw, record0Offset, mftRecordSizeLocal)
            if (!applyFixupGeneric(record0, "FILE")) return null

            val mftDataAttr = parseAttributes(record0).firstOrNull { it.type == AT_DATA && it.name.isEmpty() }
                ?: return null
            val mftRuns = if (!mftDataAttr.resident) mftDataAttr.runs ?: emptyList() else return null
            if (mftRuns.isEmpty()) return null

            NtfsReader(
                raw = raw,
                partitionStartSector = vbr.vbrSectorLba,
                bytesPerSector = bytesPerSector,
                clusterBytes = clusterBytesLocal,
                mftRecordSize = mftRecordSizeLocal,
                indexRecordSize = indexRecordSizeLocal,
                totalClusters = totalClustersLocal,
                mftDataRuns = mftRuns
            )
        }.getOrNull()
    }
}

// ------------------------------------------------------------------
// Salt-veri (pure) yardimcilar -- ornek durumundan (instance) bagimsiz,
// bu yuzden NtfsReader olusturulmadan ONCE de (bootstrap sirasinda)
// cagirilabilirler.
// ------------------------------------------------------------------

private data class DataRun(val lengthClusters: Long, val lcn: Long?)

private data class NtfsAttr(
    val type: Int,
    val name: String,
    val resident: Boolean,
    val content: ByteArray?,
    val runs: List<DataRun>?,
    val realSize: Long
)

private data class RawIndexEntry(val mftRef: Long, val fileNameContent: ByteArray?, val isLast: Boolean)

private data class FileNameInfo(
    val name: String,
    val namespace: Int,
    val isDirectory: Boolean,
    val realSize: Long,
    val modifiedMillis: Long,
    val mftRecordNumber: Long = 0L
)

/** clustersPerMftRecord / clustersPerIndexBuffer alanlarini (imzali bayt) bayt boyutuna cevirir (bkz. NTFS BPB spesifikasyonu). */
private fun sizeFromSignedField(signedByteValue: Int, clusterSize: Int): Int {
    val v = signedByteValue // zaten Kotlin'de Byte.toInt() isaret uzatarak (sign-extend) cevirir
    return if (v >= 0) v * clusterSize else 1 shl (-v)
}

/**
 * "FILE" (MFT kaydi) veya "INDX" (indeks arabellegi) imzali bir arabellege
 * fixup/Update Sequence Array'i UYGULAR (yerinde degistirir) VE dogrular.
 * Herhangi bir sektorun "korumali" son 2 bayti beklenen USN ile
 * ESLESMIYORSA (bozuk/tutarsiz veri) `false` doner -- cagiran bu arabellege
 * GUVENMEMELIDIR.
 */
private fun applyFixupGeneric(buf: ByteArray, expectedMagic: String): Boolean {
    if (buf.size < 8 || buf.size % 512 != 0) return false
    for (i in expectedMagic.indices) {
        if (buf[i] != expectedMagic[i].code.toByte()) return false
    }
    val usaOffset = readU16(buf, 0x04)
    val usaCount = readU16(buf, 0x06)
    val sectorsExpected = buf.size / 512
    if (usaCount != sectorsExpected + 1) return false
    if (usaOffset < 8 || usaOffset + usaCount * 2 > buf.size) return false
    val usn0 = readU16(buf, usaOffset)
    for (i in 0 until sectorsExpected) {
        val tail = (i + 1) * 512 - 2
        val stored = readU16(buf, tail)
        if (stored != usn0) return false
        val origOff = usaOffset + (i + 1) * 2
        val orig = readU16(buf, origOff)
        buf[tail] = (orig and 0xFF).toByte()
        buf[tail + 1] = ((orig ushr 8) and 0xFF).toByte()
    }
    return true
}

/** NTFS runlist kodlamasini (bkz. NtfsFormatter.encodeRunlistSingleRun) cozer; delta'lar birikimli LCN'e cevrilir. */
private fun decodeDataRuns(data: ByteArray, start: Int, end: Int): List<DataRun> {
    val runs = ArrayList<DataRun>()
    var pos = start
    var currentLcn = 0L
    val safeEnd = end.coerceAtMost(data.size)
    var guard = 0
    while (pos < safeEnd && guard < 100_000) {
        guard++
        val header = data[pos].toInt() and 0xFF
        if (header == 0) break
        val lenBytes = header and 0x0F
        val offBytes = (header ushr 4) and 0x0F
        if (lenBytes == 0 || lenBytes > 8 || offBytes > 8) break
        pos += 1
        if (pos + lenBytes + offBytes > safeEnd) break

        var length = 0L
        for (i in 0 until lenBytes) length = length or ((data[pos + i].toLong() and 0xFF) shl (8 * i))
        pos += lenBytes

        if (offBytes == 0) {
            runs.add(DataRun(length, null)) // seyrek (sparse) calisma -- fiziksel yer ayrilmamis
            continue
        }
        var rawOffset = 0L
        for (i in 0 until offBytes) rawOffset = rawOffset or ((data[pos + i].toLong() and 0xFF) shl (8 * i))
        pos += offBytes
        val bits = offBytes * 8
        if (bits < 64 && (rawOffset and (1L shl (bits - 1))) != 0L) {
            rawOffset -= (1L shl bits)
        }
        currentLcn += rawOffset
        runs.add(DataRun(length, currentLcn))
    }
    return runs
}

private fun resolveVcnToLcn(runs: List<DataRun>, vcn: Long): Long? {
    var cursor = 0L
    for (run in runs) {
        if (vcn < cursor + run.lengthClusters) {
            val within = vcn - cursor
            return run.lcn?.let { it + within }
        }
        cursor += run.lengthClusters
    }
    return null
}

/** Bir MFT kaydinin (fixup uygulanmis) oznitelik listesini ayristirir. */
private fun parseAttributes(record: ByteArray): List<NtfsAttr> {
    val out = ArrayList<NtfsAttr>()
    if (record.size < 0x18) return out
    val firstAttrOffset = readU16(record, 0x14)
    var pos = firstAttrOffset
    var guard = 0
    while (pos + 4 <= record.size && guard < 4096) {
        guard++
        val type = readS32(record, pos)
        if (type == -1) break // 0xFFFFFFFF bitis isaretcisi
        if (pos + 8 > record.size) break
        val length = readS32(record, pos + 4)
        if (length < 24 || pos + length > record.size) break

        val nonResident = record[pos + 8].toInt() != 0
        val nameLenChars = record[pos + 9].toInt() and 0xFF
        val nameOffset = readU16(record, pos + 0x0A)
        val name = if (nameLenChars > 0 && pos + nameOffset + nameLenChars * 2 <= record.size) {
            String(record, pos + nameOffset, nameLenChars * 2, Charsets.UTF_16LE)
        } else ""

        if (!nonResident) {
            val valueLength = readS32(record, pos + 0x10)
            val valueOffset = readU16(record, pos + 0x14)
            val content = if (valueLength in 0..(record.size - pos - valueOffset)) {
                record.copyOfRange(pos + valueOffset, pos + valueOffset + valueLength)
            } else ByteArray(0)
            out.add(NtfsAttr(type, name, false, content, null, content.size.toLong()))
        } else {
            val runlistOffset = readU16(record, pos + 0x20)
            val realSize = readU64(record, pos + 0x30)
            val runs = if (pos + runlistOffset <= record.size) {
                runCatching { decodeDataRuns(record, pos + runlistOffset, pos + length) }.getOrDefault(emptyList())
            } else emptyList()
            out.add(NtfsAttr(type, name, true, null, runs, realSize))
        }
        pos += length
    }
    return out
}

/** [start,end) araligindaki $INDEX_ENTRY dizisini ayristirir (bkz. Microsoft NTFS $I30 index spesifikasyonu). */
private fun parseIndexEntriesRange(buf: ByteArray, start: Int, end: Int): List<RawIndexEntry> {
    val out = ArrayList<RawIndexEntry>()
    var pos = start
    val safeEnd = end.coerceAtMost(buf.size)
    var guard = 0
    while (pos + 16 <= safeEnd && guard < 100_000) {
        guard++
        val mftRef = readU64(buf, pos)
        val entryLen = readU16(buf, pos + 8)
        val keyLen = readU16(buf, pos + 10)
        val flags = readU16(buf, pos + 12)
        val isLast = (flags and 0x0002) != 0
        if (entryLen < 16 || pos + entryLen > safeEnd) break
        val fnContent = if (!isLast && keyLen > 0 && pos + 16 + keyLen <= safeEnd) {
            buf.copyOfRange(pos + 16, pos + 16 + keyLen)
        } else null
        out.add(RawIndexEntry(mftRef, fnContent, isLast))
        if (isLast) break
        pos += entryLen
    }
    return out
}

/** $FILE_NAME oznitelik icerigini ayristirir (bkz. NtfsFormatter.buildFileName). */
private fun parseFileNameAttr(content: ByteArray): FileNameInfo {
    require(content.size >= 0x42) { "FILE_NAME cok kisa" }
    val flags = readS32(content, 0x38)
    val isDir = (flags and 0x10000000) != 0
    val realSize = readU64(content, 0x30)
    val modified = ntfsFiletimeToMillis(readU64(content, 0x10)) // "son yazilma" zamani
    val nameLenChars = content[0x40].toInt() and 0xFF
    val namespace = content[0x41].toInt() and 0xFF
    val nameBytesLen = nameLenChars * 2
    require(0x42 + nameBytesLen <= content.size) { "isim alani sinirlarin disina tasiyor" }
    val name = if (nameBytesLen > 0) String(content, 0x42, nameBytesLen, Charsets.UTF_16LE) else ""
    return FileNameInfo(name, namespace, isDir, realSize, modified)
}

/** Ayni dosyanin WIN32 (uzun) adini DOS (8.3 kisa) adina TERCIH etmek icin siralama anahtari (kucuk = daha iyi). */
private fun namespaceRank(namespace: Int): Int = when (namespace) {
    1, 3 -> 0 // WIN32, WIN32_AND_DOS
    0 -> 1    // POSIX
    2 -> 2    // DOS (kisa 8.3 ad) -- sadece baska secenek yoksa
    else -> 3
}

/** NTFS'in 64-bitlik FILETIME (1601-01-01 UTC'den beri 100ns birimleri) degerini epoch-ms'ye cevirir. */
private fun ntfsFiletimeToMillis(filetime: Long): Long {
    if (filetime <= 0L) return 0L
    val windowsToUnixEpochMs = 11_644_473_600_000L
    return (filetime / 10_000L) - windowsToUnixEpochMs
}
