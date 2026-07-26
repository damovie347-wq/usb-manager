package com.usbmanager.app.core

import com.usbmanager.app.usb.RawBlockDevice
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * Microsoft'un resmi "exFAT File System Specification"ina gore CALISAN,
 * MINIMAL bir exFAT bicimlendirici (bos birim: MBR yok/superfloppy, Ana +
 * Yedek Boot Bolgesi, tek FAT, Allocation Bitmap, Up-case Table, bos Kok
 * Dizin).
 *
 * BILINCLI KAPSAM SINIRLARI (Fat32Formatter'daki ayni "referans kalitesinde"
 * yaklasimla ACIKCA belirtiliyor):
 *  - Tek FAT (NumberOfFats = 1); TexFAT (ikinci/yedek canli FAT) YOK.
 *  - Up-case Table, Windows'un yazdigi TAM/kanonik tabloyla BIREBIR AYNI
 *    DEGIL. Bunun yerine, spesifikasyonun ACIKCA "en az bunlar yeterlidir"
 *    dedigi ZORUNLU 128 karakterlik (0x0000-0x007F) esleme tablosu yazilir
 *    (a-z -> A-Z gercek esleme, digerleri birebir). Bu, dosya sisteminin
 *    standarda gore GECERLI ve bagimsiz isletim sistemlerince bağlanabilir
 *    (mountable) olmasini saglar; ancak 128 karakter disindaki (orn. Turkce
 *    'ı/İ' gibi) karakterlerde Windows'un varsayilanindan FARKLI (daha harfe
 *    duyarli) bir dosya adi karsilastirmasi olabilir.
 *  - Birim etiketi (volume label) yazilmaz (bos/"etiketsiz" birakilir).
 *
 * NOT: Fat32Formatter'daki uyari burada da GECERLIDIR: uretime almadan once
 * gercek USB bellekler uzerinde test edilmeli, mumkunse `mkfs.exfat`
 * ciktisiyla (hexdump ile) karsilastirilmalidir.
 */
object ExFatFormatter {

    private const val SECTOR_SIZE = 512
    private const val BOOT_REGION_SECTORS = 12L          // sektor 0..11 (Ana Boot Bolgesi)
    private const val FAT_OFFSET_SECTORS = BOOT_REGION_SECTORS * 2 // Ana(12)+Yedek(12) = 24
    private const val UPCASE_TABLE_ENTRY_COUNT = 128       // spesifikasyonun zorunlu kildigi minimum
    private const val UPCASE_TABLE_BYTES = UPCASE_TABLE_ENTRY_COUNT * 2 // 256 bayt
    private const val ROOT_DIR_BYTES = 32 * 3              // Bitmap + Up-case + (etiketsiz) Birim Etiketi girisi

    suspend fun format(
        raw: RawBlockDevice,
        onProgress: (percent: Int) -> Unit = {}
    ) {
        require(raw.blockSizeBytes == SECTOR_SIZE) {
            "Bu formatter simdilik yalnizca 512 baytlik sektorleri destekler."
        }

        val totalSectors = raw.totalBytes / SECTOR_SIZE
        val sectorsPerClusterShift = chooseSectorsPerClusterShift(raw.totalBytes)
        val sectorsPerCluster = 1L shl sectorsPerClusterShift
        val clusterBytes = sectorsPerCluster * SECTOR_SIZE

        // --- 1) FAT boyutu / Cluster Heap konumu ---
        // (2 gecisli hesap: FAT'in kendi kapladigi alan ClusterCount'u ufak bir
        // miktar dusurebilir; ikinci gecis bunu guvenli sekilde yeniden hesaplar.)
        val fatOffset = FAT_OFFSET_SECTORS
        val fatLengthEstimate = fatLengthForClusterCount(
            ((totalSectors - fatOffset) / sectorsPerCluster).coerceAtLeast(1)
        )
        val clusterHeapOffset = fatOffset + fatLengthEstimate
        val clusterCount = ((totalSectors - clusterHeapOffset) / sectorsPerCluster).coerceAtLeast(1)
        val fatLengthSectors = fatLengthEstimate // clusterCount kucul(mus)dugu icin bu deger HER ZAMAN yeterli/guvenli kalir

        // --- 2) Sistem kumeleri: Allocation Bitmap / Up-case Table / Kok Dizin ---
        val bitmapSizeBytes = (clusterCount + 7) / 8
        val bitmapClusterCount = ((bitmapSizeBytes + clusterBytes - 1) / clusterBytes).coerceAtLeast(1)
        val upcaseClusterCount = ((UPCASE_TABLE_BYTES + clusterBytes - 1) / clusterBytes).coerceAtLeast(1)
        val rootDirClusterCount = ((ROOT_DIR_BYTES + clusterBytes - 1) / clusterBytes).coerceAtLeast(1)

        val bitmapFirstCluster = 2L
        val upcaseFirstCluster = bitmapFirstCluster + bitmapClusterCount
        val rootDirFirstCluster = upcaseFirstCluster + upcaseClusterCount
        val systemClusterCount = (rootDirFirstCluster + rootDirClusterCount) - bitmapFirstCluster

        require(systemClusterCount <= 256) {
            "Bu USB bellek exFAT icin desteklenmeyen bir boyuta/yapiya sahip."
        }
        require(clusterCount > systemClusterCount) {
            "USB bellek exFAT ile bicimlendirilemeyecek kadar kucuk."
        }

        val volumeSerial = Random.nextInt()

        onProgress(2)

        // --- 3) Ana Boot Bolgesi (sektor 0..11) ---
        writeBootRegion(
            raw = raw,
            regionStartSector = 0,
            totalSectors = totalSectors,
            fatOffset = fatOffset,
            fatLengthSectors = fatLengthSectors,
            clusterHeapOffset = clusterHeapOffset,
            clusterCount = clusterCount,
            rootDirCluster = rootDirFirstCluster,
            sectorsPerClusterShift = sectorsPerClusterShift,
            volumeSerial = volumeSerial
        )
        onProgress(10)

        // --- 4) Yedek Boot Bolgesi (sektor 12..23) -- Ana bolgenin BIREBIR kopyasi ---
        copyRegion(raw, fromSector = 0, toSector = BOOT_REGION_SECTORS, sectorCount = BOOT_REGION_SECTORS)
        onProgress(18)

        // --- 5) FAT tablosu ---
        writeFatTable(
            raw, fatOffset, fatLengthSectors,
            bitmapFirstCluster, bitmapClusterCount,
            upcaseFirstCluster, upcaseClusterCount,
            rootDirFirstCluster, rootDirClusterCount
        ) { pct -> onProgress(18 + (pct * 42 / 100)) }

        // --- 6) Cluster Heap: Allocation Bitmap ---
        writeAllocationBitmap(
            raw, clusterHeapOffset, sectorsPerCluster,
            bitmapClusterCount, bitmapSizeBytes, usedClusterCount = systemClusterCount
        )
        onProgress(75)

        // --- 7) Cluster Heap: Up-case Table ---
        val upcaseTableBytes = buildMandatoryUpcaseTable()
        val upcaseSector = clusterHeapOffset + (upcaseFirstCluster - 2) * sectorsPerCluster
        writeClusterData(raw, upcaseSector, upcaseClusterCount * sectorsPerCluster, upcaseTableBytes)
        val upcaseChecksum = computeChecksum(upcaseTableBytes)
        onProgress(88)

        // --- 8) Cluster Heap: Kok Dizin (Bitmap + Up-case + [etiketsiz] Birim Etiketi girisi) ---
        val rootDirSector = clusterHeapOffset + (rootDirFirstCluster - 2) * sectorsPerCluster
        val rootDirContent = buildRootDirectory(
            bitmapFirstCluster, bitmapSizeBytes,
            upcaseFirstCluster, UPCASE_TABLE_BYTES.toLong(), upcaseChecksum
        )
        writeClusterData(raw, rootDirSector, rootDirClusterCount * sectorsPerCluster, rootDirContent)
        onProgress(100)
    }

    /** exFAT icin Microsoft'un onerdigi buyuklukte kume secimi (FAT32'den daha buyuk kumeler). */
    private fun chooseSectorsPerClusterShift(totalBytes: Long): Int {
        val mb = totalBytes / (1024.0 * 1024)
        return when {
            mb <= 256 -> 3        // 4 KB   (2^3 sektor * 512)
            mb <= 32_000 -> 6     // 32 KB  (~32 GB'a kadar)
            mb <= 256_000 -> 7    // 64 KB  (~256 GB'a kadar)
            else -> 8              // 128 KB
        }
    }

    /** (ClusterCount + 2) giris * 4 bayt / 512 -> yukari yuvarlanir. */
    private fun fatLengthForClusterCount(clusterCount: Long): Long {
        val entries = clusterCount + 2
        val bytes = entries * 4
        return (bytes + SECTOR_SIZE - 1) / SECTOR_SIZE
    }

    private fun writeBootRegion(
        raw: RawBlockDevice,
        regionStartSector: Long,
        totalSectors: Long,
        fatOffset: Long,
        fatLengthSectors: Long,
        clusterHeapOffset: Long,
        clusterCount: Long,
        rootDirCluster: Long,
        sectorsPerClusterShift: Int,
        volumeSerial: Int
    ) {
        // --- Sektor 0: Ana Boot Sektoru (Volume Boot Record) ---
        val boot = ByteBuffer.allocate(SECTOR_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        boot.put(byteArrayOf(0xEB.toByte(), 0x76, 0x90.toByte()))   // JumpBoot
        boot.put("EXFAT   ".toByteArray(Charsets.US_ASCII))        // FileSystemName (8)
        boot.position(boot.position() + 53)                         // MustBeZero (53)
        boot.putLong(0L)                                            // PartitionOffset (superfloppy, MBR yok)
        boot.putLong(totalSectors)                                  // VolumeLength
        boot.putInt(fatOffset.toInt())                              // FatOffset
        boot.putInt(fatLengthSectors.toInt())                       // FatLength
        boot.putInt(clusterHeapOffset.toInt())                      // ClusterHeapOffset
        boot.putInt(clusterCount.toInt())                           // ClusterCount
        boot.putInt(rootDirCluster.toInt())                         // FirstClusterOfRootDirectory
        boot.putInt(volumeSerial)                                   // VolumeSerialNumber
        boot.putShort(0x0100)                                       // FileSystemRevision 1.00
        boot.putShort(0)                                            // VolumeFlags (temiz)
        boot.put(9.toByte())                                        // BytesPerSectorShift (2^9=512)
        boot.put(sectorsPerClusterShift.toByte())                   // SectorsPerClusterShift
        boot.put(1.toByte())                                        // NumberOfFats (tek FAT)
        boot.put(0x80.toByte())                                     // DriveSelect
        boot.put(0.toByte())                                        // PercentInUse (0 = henuz kullanilmiyor)
        boot.position(boot.position() + 7)                          // Reserved (7)
        boot.position(boot.position() + 390)                        // BootCode (bos)
        boot.position(0x1FE)
        boot.put(0x55); boot.put(0xAA.toByte())                     // BootSignature
        boot.rewind()
        raw.writeAt(regionStartSector * SECTOR_SIZE, boot)

        // --- Sektor 1..8: Genisletilmis Boot Sektorleri (bos + uzatilmis imza) ---
        for (i in 1..8) {
            val ext = ByteBuffer.allocate(SECTOR_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            ext.position(0x1FC)
            ext.putInt(0xAA550000.toInt())
            ext.rewind()
            raw.writeAt((regionStartSector + i) * SECTOR_SIZE, ext)
        }

        // --- Sektor 9: OEM Parametreleri (kullanilmiyor -> tamami sifir) ---
        raw.writeAt((regionStartSector + 9) * SECTOR_SIZE, ByteBuffer.allocate(SECTOR_SIZE))

        // --- Sektor 10: Ayrilmis (Reserved) -> tamami sifir ---
        raw.writeAt((regionStartSector + 10) * SECTOR_SIZE, ByteBuffer.allocate(SECTOR_SIZE))

        // --- Sektor 11: Boot Checksum -> sektor 0..10'un checksum'i, sektor sonuna kadar tekrarlanir ---
        // Az once yazdigimiz sektorleri checksum icin GERI OKUYORUZ (VolumeFlags
        // ve PercentInUse alanlari spesifikasyon geregi HESABA KATILMAZ).
        val elevenSectors = ByteArray(11 * SECTOR_SIZE)
        val readBuf = ByteBuffer.allocate(SECTOR_SIZE)
        for (s in 0 until 11) {
            readBuf.clear()
            raw.readAt((regionStartSector + s) * SECTOR_SIZE, readBuf)
            readBuf.rewind()
            readBuf.get(elevenSectors, s * SECTOR_SIZE, SECTOR_SIZE)
        }
        val checksum = computeChecksum(elevenSectors, skipOffsets = intArrayOf(106, 107, 112))
        val checksumSector = ByteBuffer.allocate(SECTOR_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        while (checksumSector.remaining() > 0) checksumSector.putInt(checksum)
        checksumSector.rewind()
        raw.writeAt((regionStartSector + 11) * SECTOR_SIZE, checksumSector)
    }

    private fun copyRegion(raw: RawBlockDevice, fromSector: Long, toSector: Long, sectorCount: Long) {
        val buf = ByteBuffer.allocate(SECTOR_SIZE)
        for (i in 0 until sectorCount) {
            buf.clear()
            raw.readAt((fromSector + i) * SECTOR_SIZE, buf)
            buf.rewind()
            raw.writeAt((toSector + i) * SECTOR_SIZE, buf)
        }
    }

    /**
     * FAT[0]/FAT[1] ozel degerleri + Bitmap/Up-case/Kok Dizin icin zincir
     * (chain) girisleri HER ZAMAN ilk 256 giris (= ilk 2 sektor) icinde kalir
     * (format() basindaki `require(systemClusterCount <= 256)` bunu garanti
     * eder); bu yuzden sadece ilk 2 sektoru bellekte olusturup yaziyoruz,
     * gerisini sifirla dolduruyoruz.
     */
    private suspend fun writeFatTable(
        raw: RawBlockDevice,
        fatOffset: Long,
        fatLengthSectors: Long,
        bitmapFirstCluster: Long, bitmapClusterCount: Long,
        upcaseFirstCluster: Long, upcaseClusterCount: Long,
        rootDirFirstCluster: Long, rootDirClusterCount: Long,
        onProgress: (Int) -> Unit
    ) {
        val head = ByteBuffer.allocate(2 * SECTOR_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        head.putInt(0, 0xFFFFFFF8.toInt()) // FAT[0]
        head.putInt(4, 0xFFFFFFFF.toInt()) // FAT[1]

        fun writeChain(first: Long, count: Long) {
            for (i in 0 until count) {
                val cluster = first + i
                val value = if (i == count - 1) 0xFFFFFFFF.toInt() else (cluster + 1).toInt()
                head.putInt((cluster * 4).toInt(), value)
            }
        }
        writeChain(bitmapFirstCluster, bitmapClusterCount)
        writeChain(upcaseFirstCluster, upcaseClusterCount)
        writeChain(rootDirFirstCluster, rootDirClusterCount)

        head.rewind()
        raw.writeAt(fatOffset * SECTOR_SIZE, head)

        // Geri kalan FAT sektorleri: tamami sifir (bos kumeler)
        val zero = ByteBuffer.allocate(SECTOR_SIZE)
        var s = fatOffset + 2
        val end = fatOffset + fatLengthSectors
        val total = (end - s).coerceAtLeast(0)
        var written = 0L
        while (s < end) {
            zero.rewind()
            raw.writeAt(s * SECTOR_SIZE, zero)
            s++
            written++
            if (total > 0 && written % 2048 == 0L) {
                onProgress(((written * 100) / total).toInt().coerceIn(0, 100))
            }
        }
        onProgress(100)
    }

    private fun writeAllocationBitmap(
        raw: RawBlockDevice,
        clusterHeapOffset: Long,
        sectorsPerCluster: Long,
        bitmapClusterCount: Long,
        bitmapSizeBytes: Long,
        usedClusterCount: Long
    ) {
        // Bitmap'in TAMAMI (bitmapClusterCount kadar kume) sifirla baslar
        // (=bos); sadece ilk 'usedClusterCount' bitini (Bitmap+Up-case+
        // KokDizin'in KENDI kumeleri) 1 yapariz -- LSB = kume 2.
        val bitmap = ByteArray(bitmapSizeBytes.toInt())
        for (i in 0 until usedClusterCount) {
            val byteIndex = (i / 8).toInt()
            val bitIndex = (i % 8).toInt()
            bitmap[byteIndex] = (bitmap[byteIndex].toInt() or (1 shl bitIndex)).toByte()
        }
        writeClusterData(raw, clusterHeapOffset, bitmapClusterCount * sectorsPerCluster, bitmap)
    }

    /**
     * exFAT'in ZORUNLU kildigi minimum 128 karakterlik (0x0000-0x007F)
     * Up-case Table: a-z -> A-Z gercek esleme, digerleri birebir (identity).
     * (bkz. dosya basindaki mimari not / kapsam aciklamasi)
     */
    private fun buildMandatoryUpcaseTable(): ByteArray {
        val buf = ByteBuffer.allocate(UPCASE_TABLE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (code in 0 until UPCASE_TABLE_ENTRY_COUNT) {
            val upper = if (code in 0x61..0x7A) code - 0x20 else code
            buf.putShort(upper.toShort())
        }
        return buf.array()
    }

    private fun buildRootDirectory(
        bitmapFirstCluster: Long, bitmapSizeBytes: Long,
        upcaseFirstCluster: Long, upcaseSizeBytes: Long, upcaseChecksum: Int
    ): ByteArray {
        val buf = ByteBuffer.allocate(ROOT_DIR_BYTES).order(ByteOrder.LITTLE_ENDIAN)

        // --- Giris 1: Allocation Bitmap Directory Entry (EntryType 0x81) ---
        buf.put(0x81.toByte())                    // EntryType
        buf.put(0.toByte())                       // BitmapFlags (ilk/tek bitmap)
        buf.position(buf.position() + 18)         // Reserved
        buf.putInt(bitmapFirstCluster.toInt())    // FirstCluster
        buf.putLong(bitmapSizeBytes)              // DataLength

        // --- Giris 2: Up-case Table Directory Entry (EntryType 0x82) ---
        buf.put(0x82.toByte())                    // EntryType
        buf.position(buf.position() + 3)          // Reserved1
        buf.putInt(upcaseChecksum)                // TableChecksum
        buf.position(buf.position() + 12)         // Reserved2
        buf.putInt(upcaseFirstCluster.toInt())    // FirstCluster
        buf.putLong(upcaseSizeBytes)               // DataLength

        // --- Giris 3: Birim Etiketi Directory Entry -- ETIKETSIZ (0x03) ---
        buf.put(0x03.toByte())                    // EntryType (etiketsiz = "kullanilmiyor")
        buf.put(0.toByte())                       // CharacterCount = 0
        buf.position(buf.position() + 30)         // VolumeLabel(22) + Reserved(8)

        return buf.array()
    }

    /** exFAT Boot Checksum / Up-case Table Checksum icin ORTAK algoritma (spesifikasyon). */
    private fun computeChecksum(data: ByteArray, skipOffsets: IntArray = IntArray(0)): Int {
        var sum = 0
        for (i in data.indices) {
            if (i in skipOffsets) continue
            val bit0 = sum and 1
            sum = (sum ushr 1) or (bit0 shl 31)
            sum += (data[i].toInt() and 0xFF)
        }
        return sum
    }

    /** `data`, kume(ler)in tam boyutuna kadar SIFIRLA doldurulup yazilir. */
    private fun writeClusterData(raw: RawBlockDevice, startSector: Long, sectorCount: Long, data: ByteArray) {
        val fullSize = (sectorCount * SECTOR_SIZE).toInt()
        val padded = data.copyOf(fullSize) // fazla kisim otomatik 0 ile doldurulur
        writeBytesAtSector(raw, startSector, padded)
    }

    private fun writeBytesAtSector(raw: RawBlockDevice, startSector: Long, data: ByteArray) {
        var offset = 0
        var sector = startSector
        while (offset < data.size) {
            val chunkSize = minOf(SECTOR_SIZE, data.size - offset)
            val buf = ByteBuffer.allocate(SECTOR_SIZE)
            buf.put(data, offset, chunkSize)
            buf.rewind()
            raw.writeAt(sector * SECTOR_SIZE, buf)
            offset += chunkSize
            sector++
        }
    }
}
