package com.usbmanager.app.usb

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * KOK NEDEN TESHISI: libaums'un GENEL (public) FileSystem/UsbFile API'si
 * SADECE FAT12/16/32'yi anlar (bkz. libaums GitHub #212 -- exFAT, #302 ve
 * #8 -- NTFS). Bir USB bellek exFAT ya da NTFS ile bicimlendirilmisse,
 * `UsbMassStorageManager.fileSystemOf(partition)` sessizce `null` doner ve
 * kullanici ekranda SADECE "Disk bilgisi alınamadı" gibi anlamsiz/genel bir
 * mesaj gorur -- ASIL nedeni (desteklenmeyen dosya sistemi) hicbir yerde
 * gosterilmez.
 *
 * Bu sinif, libaums basarisiz oldugunda devreye girip diskin sektor 0'ini
 * (gerekirse MBR bolum tablosu uzerinden asil VBR'a atlayarak) HAM olarak
 * okur ve OEM ID / dosya sistemi imzasina bakarak GERCEK dosya sistemi
 * turunu tahmin eder. Boylece kullaniciya "algilanamadi" yerine "algilandi
 * ama su an okunamiyor, cunku X" gibi DOGRU VE EYLEME GECIRILEBILIR bir
 * mesaj gosterilebilir.
 *
 * NOT: Bu bir TAM dosya sistemi ayristirici DEGILDIR -- sadece onyukleme
 * sektorundeki iyi bilinen imza alanlarina bakan hafif bir "sniff"tir.
 */
object RawFileSystemSniffer {

    private const val SECTOR_SIZE = 512

    /** Onaylanmis (boot-imzali + taninan) bir VBR'in konumu, ham sektor baytlari ve etiketi. */
    data class VbrLocation(val vbrSectorLba: Long, val sectorBytes: ByteArray, val label: String)

    /** Taninirsa kisa bir etiket ("NTFS", "exFAT", "FAT32" vb.) dondurur, taninmazsa null. */
    fun sniffLabel(raw: RawBlockDevice): String? {
        locateVbr(raw)?.let { return it.label }

        // locateVbr() KESIN bir VBR dogrulayamadi (imza gecersiz/taninmiyor).
        // Bu, SADECE bilgilendirme mesaji icin: en azindan MBR bolum TURU
        // baytindan kaba bir tahmin dene. ExFatReader/NtfsReader BU ZAYIF
        // tahmine GUVENMEZ -- onlar SADECE locateVbr()'in dondurdugu, gercekten
        // dogrulanmis konumu kullanir.
        if (raw.blockSizeBytes != SECTOR_SIZE) return null
        val sector0 = readSector(raw, 0) ?: return null
        if (!hasBootSignature(sector0)) return null
        val partType = sector0[0x1BE + 4].toInt() and 0xFF
        val partStartLba = readLeUInt32(sector0, 0x1BE + 8)
        return if (partType != 0 && partStartLba in 1..0x0FFFFFFFL) partitionTypeLabel(partType) else null
    }

    /**
     * ExFatReader/NtfsReader'in dosya sistemini ayristirmaya baslamadan once
     * cagirdigi PAYLASILAN konum-bulma mantigi: sektor 0 dogrudan gecerli
     * (boot-imzali + taninan) bir VBR mi (superfloppy duzeni, MBR yok)?
     * Degilse sektor 0 bir MBR olabilir -- bu durumda ilk bolum girisinin
     * isaret ettigi sektordeki VBR dogrulanir. Gercekten dogrulanmis/taninan
     * bir VBR bulunamadiginda (ornegin bolum turu baytindan yapilan ZAYIF
     * tahminlerde) `null` doner -- boylece okuyucular hicbir zaman
     * dogrulanmamis bir konumdan ayristirmaya CALISMAZ.
     */
    fun locateVbr(raw: RawBlockDevice): VbrLocation? {
        if (raw.blockSizeBytes != SECTOR_SIZE) return null
        val sector0 = readSector(raw, 0) ?: return null
        if (!hasBootSignature(sector0)) return null

        identifyVbr(sector0)?.let { return VbrLocation(0, sector0, it) }

        // Sektor 0 taninan bir VBR degildi (ama boot-imzali): MBR olabilir.
        val partType = sector0[0x1BE + 4].toInt() and 0xFF
        val partStartLba = readLeUInt32(sector0, 0x1BE + 8)
        if (partType == 0 || partStartLba !in 1..0x0FFFFFFFL) return null

        val vbr = readSector(raw, partStartLba) ?: return null
        if (!hasBootSignature(vbr)) return null
        val label = identifyVbr(vbr) ?: return null
        return VbrLocation(partStartLba, vbr, label)
    }

    private fun identifyVbr(sector: ByteArray): String? {
        val oem = String(sector, 3, 8, Charsets.US_ASCII)
        return when {
            oem.startsWith("NTFS") -> "NTFS"
            oem.startsWith("EXFAT") -> "exFAT"
            sector[0x42].toInt() and 0xFF == 0x29 &&
                String(sector, 0x52, 8, Charsets.US_ASCII).startsWith("FAT32") -> "FAT32"
            sector[0x26].toInt() and 0xFF == 0x29 &&
                String(sector, 0x36, 8, Charsets.US_ASCII).let { it.startsWith("FAT16") || it.startsWith("FAT12") } ->
                String(sector, 0x36, 5, Charsets.US_ASCII).trim()
            else -> null
        }
    }

    private fun partitionTypeLabel(type: Int): String? = when (type) {
        0x07 -> "exFAT/NTFS (bölüm türünden tahmin)"
        0x0B, 0x0C -> "FAT32"
        0x04, 0x06, 0x0E -> "FAT16"
        0x83 -> "Linux (ext2/3/4)"
        else -> null
    }

    private fun hasBootSignature(sector: ByteArray): Boolean =
        (sector[510].toInt() and 0xFF) == 0x55 && (sector[511].toInt() and 0xFF) == 0xAA

    private fun readSector(raw: RawBlockDevice, lba: Long): ByteArray? = try {
        val buf = ByteBuffer.allocate(SECTOR_SIZE)
        raw.readAt(lba * SECTOR_SIZE, buf)
        buf.rewind()
        val arr = ByteArray(SECTOR_SIZE)
        buf.get(arr)
        arr
    } catch (t: Throwable) {
        null
    }

    private fun readLeUInt32(data: ByteArray, offset: Int): Long {
        val bb = ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN)
        return bb.int.toLong() and 0xFFFFFFFFL
    }
}
