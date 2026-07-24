package com.usbmanager.app.core

import com.usbmanager.app.usb.RawBlockDevice
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * Standart Microsoft FAT32 spesifikasyonuna (fatgen103) gore CALISAN bir
 * biçimlendirici: MBR + FAT32 Boot Sector + FSInfo + iki adet FAT tablosu +
 * bos kok dizini yazar.
 *
 * Bu, iskeletin "gercekten calisan" tek dosya sistemi yazicisidir; digerleri
 * (exFAT, NTFS, Ext2/3/4, Btrfs, F2FS, XFS, HFS+, APFS) icin bkz. FormatEngine
 * ve README "Yol Haritasi" bolumu.
 *
 * NOT: Bu, egitim/referans kalitesinde bir implementasyondur. Uretime
 * almadan once gercek USB bellekler uzerinde test edilmeli ve mumkunse
 * `mkfs.vfat -F 32` ciktisiyla (hexdump ile) karsilastirilmalidir.
 */
object Fat32Formatter {

    private const val SECTOR_SIZE = 512

    suspend fun format(
        raw: RawBlockDevice,
        volumeLabel: String = "USB MANAGER",
        onProgress: (percent: Int) -> Unit = {}
    ) {
        require(raw.blockSizeBytes == SECTOR_SIZE) {
            "Bu formatter simdilik yalnizca 512 baytlik sektorleri destekler."
        }

        val totalSectors = (raw.totalBytes / SECTOR_SIZE)
        val sectorsPerCluster = chooseSectorsPerCluster(raw.totalBytes)
        val reservedSectors = 32
        val numFats = 2

        val fatSize = computeFatSize(
            totalSectors = totalSectors,
            reservedSectors = reservedSectors,
            sectorsPerCluster = sectorsPerCluster,
            numFats = numFats
        )

        val firstFatSector = reservedSectors
        val secondFatSector = firstFatSector + fatSize
        val rootDirCluster = 2L
        val dataStartSector = reservedSectors + (numFats * fatSize)

        onProgress(5)

        // 1) MBR (sektor 0) - tek bir birincil bolum, tipi 0x0C (FAT32 LBA)
        writeMbr(raw, totalSectors)
        onProgress(15)

        // 2) Boot Sector (sektor 0 GORECELI bolume, burada bolum offseti 0
        //    kabul ediyoruz - "superfloppy" stili, cogu USB bellek + Android
        //    icin en uyumlu yaklasim budur)
        writeBootSector(
            raw, totalSectors, sectorsPerCluster, reservedSectors,
            numFats, fatSize, volumeLabel
        )
        onProgress(25)

        // 3) Yedek Boot Sector (BkBootSec = 6)
        copySector(raw, from = 0, to = 6)
        onProgress(30)

        // 4) FSInfo sektoru (sektor 1) + yedegi (sektor 7)
        writeFsInfo(raw, sector = 1)
        writeFsInfo(raw, sector = 7)
        onProgress(35)

        // 5) Iki FAT tablosu: ilk 3 giris ozel (media/EOC/root-dir-EOC),
        //    gerisi 0x00 (bos).
        writeFatTable(raw, firstFatSector, fatSize, onProgress, progressStart = 35, progressEnd = 65)
        writeFatTable(raw, secondFatSector, fatSize, onProgress, progressStart = 65, progressEnd = 90)

        // 6) Bos kok dizini (root dir), tek cluster, tamami sifir
        writeEmptyCluster(raw, dataStartSector.toLong())
        onProgress(100)
    }

    private fun chooseSectorsPerCluster(totalBytes: Long): Int {
        val gb = totalBytes / (1024.0 * 1024 * 1024)
        return when {
            gb <= 8 -> 8      // 4 KB cluster
            gb <= 16 -> 16    // 8 KB cluster
            gb <= 32 -> 32    // 16 KB cluster
            else -> 64        // 32 KB cluster
        }
    }

    /** Microsoft fatgen103 yaklasik FAT boyutu formulu (FAT32 icin). */
    private fun computeFatSize(
        totalSectors: Long, reservedSectors: Int, sectorsPerCluster: Int, numFats: Int
    ): Long {
        val tmp1 = totalSectors - reservedSectors
        var tmp2 = (256L * sectorsPerCluster) + numFats
        tmp2 /= 2
        return (tmp1 + (tmp2 - 1)) / tmp2
    }

    private fun writeMbr(raw: RawBlockDevice, totalSectors: Long) {
        val buf = ByteBuffer.allocate(SECTOR_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        // Boot kodu alani (0x000-0x1BD): bos birakildi (0x00)
        buf.position(0x1BE) // ilk bolum tablosu girisi

        buf.put(0x00)                     // durum: aktif degil
        buf.put(byteArrayOf(0, 2, 0))      // CHS baslangic (kullanilmiyor, LBA moddayiz)
        buf.put(0x0C.toByte())             // tur: FAT32, LBA
        buf.put(byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0xFF.toByte())) // CHS bitis (dummy)
        buf.putInt(0)                      // bolum baslangic LBA (0 = superfloppy stili)
        buf.putInt(totalSectors.toInt())   // bolum boyutu (sektor)

        buf.position(0x1FE)
        buf.put(0x55); buf.put(0xAA.toByte())

        buf.rewind()
        raw.writeAt(0, buf)
    }

    private fun writeBootSector(
        raw: RawBlockDevice, totalSectors: Long, secPerClus: Int, reservedSectors: Int,
        numFats: Int, fatSize: Long, label: String
    ) {
        val buf = ByteBuffer.allocate(SECTOR_SIZE).order(ByteOrder.LITTLE_ENDIAN)

        buf.put(byteArrayOf(0xEB.toByte(), 0x58, 0x90.toByte())) // jmpBoot
        buf.put("MSWIN4.1".toByteArray(Charsets.US_ASCII))       // OEMName (8)
        buf.putShort(SECTOR_SIZE.toShort())                      // BytsPerSec
        buf.put(secPerClus.toByte())                             // SecPerClus
        buf.putShort(reservedSectors.toShort())                  // RsvdSecCnt
        buf.put(numFats.toByte())                                // NumFATs
        buf.putShort(0)                                          // RootEntCnt (FAT32=0)
        buf.putShort(0)                                          // TotSec16
        buf.put(0xF8.toByte())                                   // Media
        buf.putShort(0)                                          // FATSz16
        buf.putShort(32)                                         // SecPerTrk (dummy)
        buf.putShort(64)                                         // NumHeads (dummy)
        buf.putInt(0)                                            // HiddSec
        buf.putInt(totalSectors.toInt())                         // TotSec32
        buf.putInt(fatSize.toInt())                              // FATSz32
        buf.putShort(0)                                          // ExtFlags
        buf.putShort(0)                                          // FSVer
        buf.putInt(2)                                            // RootClus
        buf.putShort(1)                                          // FSInfo sektoru
        buf.putShort(6)                                          // BkBootSec
        buf.put(ByteArray(12))                                   // Reserved
        buf.put(0x80.toByte())                                   // DrvNum
        buf.put(0)                                                // Reserved1
        buf.put(0x29)                                             // BootSig
        buf.putInt(Random.nextInt())                              // VolID

        val labelPadded = label.uppercase().padEnd(11).take(11)
        buf.put(labelPadded.toByteArray(Charsets.US_ASCII))       // VolLab (11)
        buf.put("FAT32   ".toByteArray(Charsets.US_ASCII))        // FilSysType (8)

        buf.position(0x1FE)
        buf.put(0x55); buf.put(0xAA.toByte())

        buf.rewind()
        raw.writeAt(0, buf)
    }

    private fun writeFsInfo(raw: RawBlockDevice, sector: Int) {
        val buf = ByteBuffer.allocate(SECTOR_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0x41615252)                       // LeadSig
        buf.position(0x1E4)
        buf.putInt(0x61417272)                       // StrucSig
        buf.putInt(-1)                                // Free_Count (bilinmiyor)
        buf.putInt(3)                                 // Nxt_Free (ilk bos cluster tahmini)
        buf.position(0x1FC)
        buf.putShort(0x0000)
        buf.put(0x55); buf.put(0xAA.toByte())
        buf.rewind()
        raw.writeAt(sector.toLong() * SECTOR_SIZE, buf)
    }

    private fun copySector(raw: RawBlockDevice, from: Long, to: Long) {
        val buf = ByteBuffer.allocate(SECTOR_SIZE)
        raw.readAt(from * SECTOR_SIZE, buf)
        buf.rewind()
        raw.writeAt(to * SECTOR_SIZE, buf)
    }

    private fun writeFatTable(
        raw: RawBlockDevice, startSector: Long, fatSizeSectors: Long,
        onProgress: (Int) -> Unit, progressStart: Int, progressEnd: Int
    ) {
        // Ilk sektor: FAT[0]=medya+EOC, FAT[1]=EOC (temiz birim bayraklari),
        // FAT[2]=EOC (kok dizin tek cluster, zincir yok)
        val first = ByteBuffer.allocate(SECTOR_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        first.putInt(0x0FFFFFF8.toInt())
        first.putInt(0x0FFFFFFF.toInt())
        first.putInt(0x0FFFFFFF.toInt())
        while (first.remaining() > 0) first.put(0)
        first.rewind()
        raw.writeAt(startSector * SECTOR_SIZE, first)

        // Kalan sektorler: tamami sifir (bos FAT girisleri)
        val zero = ByteBuffer.allocate(SECTOR_SIZE)
        var s = startSector + 1
        val end = startSector + fatSizeSectors
        val totalToWrite = (end - s).coerceAtLeast(0)
        var written = 0L
        while (s < end) {
            zero.rewind()
            raw.writeAt(s * SECTOR_SIZE, zero)
            s++
            written++
            if (totalToWrite > 0 && written % 512 == 0L) {
                val pct = progressStart + ((written * (progressEnd - progressStart)) / totalToWrite).toInt()
                onProgress(pct.coerceIn(progressStart, progressEnd))
            }
        }
        onProgress(progressEnd)
    }

    private fun writeEmptyCluster(raw: RawBlockDevice, startSector: Long) {
        val zero = ByteBuffer.allocate(SECTOR_SIZE)
        raw.writeAt(startSector * SECTOR_SIZE, zero)
    }
}
