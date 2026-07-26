package com.usbmanager.app.core

/**
 * Desteklenmesi ISTENEN dosya sistemleri.
 *
 * uygulamaDurumu alani, bu iskeletin hangi FS icin GERCEKTEN calisan bir
 * yazici (formatter) icerdigini, hangileri icin sadece mimari/genisletme
 * noktasi hazirlandigini acikca isaretler. Bu, "her seyi tam yaptik"
 * yaniltmasindan kacinmak icin bilincli bir tasarim tercihidir.
 */
enum class FileSystemType(
    val displayName: String,
    val uygulamaDurumu: SupportLevel
) {
    FAT16("FAT16", SupportLevel.ROADMAP),
    FAT32("FAT32", SupportLevel.IMPLEMENTED),
    EXFAT("exFAT", SupportLevel.IMPLEMENTED),
    NTFS("NTFS", SupportLevel.ROADMAP),
    EXT2("Ext2", SupportLevel.ROADMAP),
    EXT3("Ext3", SupportLevel.ROADMAP),
    EXT4("Ext4", SupportLevel.ROADMAP),
    BTRFS("Btrfs", SupportLevel.ROADMAP),
    F2FS("F2FS", SupportLevel.ROADMAP),
    XFS("XFS", SupportLevel.ROADMAP),
    HFS_PLUS("HFS+", SupportLevel.ROADMAP),
    APFS("APFS", SupportLevel.ROADMAP);

    enum class SupportLevel {
        /** Bu surumde gercekten calisan, ham blok seviyesinde yazan bir formatter var. */
        IMPLEMENTED,

        /** Mimari (FormatEngine icindeki dispatch) hazir; gercek yazici native
         *  kutuphane entegrasyonu (bkz. README "Yol Haritasi") gerektirir. */
        ROADMAP
    }
}
