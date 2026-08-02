package com.usbmanager.app.usb

import java.io.InputStream

/**
 * ExFatReader/NtfsReader gibi HAM (raw) okuyucularin urettigi, libaums'un
 * `UsbFile`'ina PARALEL calisan HAFIF bir dosya/klasor girisi. Dosya
 * Yoneticisi ekrani, kaynagi (libaums mi yoksa bu ham okuyuculardan biri mi)
 * FARK ETMEKSIZIN ayni turu gosterebilsin diye vardir (bkz. FileManagerViewModel
 * icindeki `BrowseEntry`).
 */
data class RawDirEntry(
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modifiedEpochMillis: Long,
    /** Okuyucuya OZEL, alt ogeleri listelemek/dosyayi okumak icin opak tanitici. */
    val token: Any
)

/** Bir birimin kapasite / kullanilan alan ozeti ("Disk Bilgisi" diyalogu icin). */
data class RawVolumeSpace(val capacityBytes: Long, val usedBytes: Long?)

/**
 * libaums'un FileSystem/UsbFile API'siyla ayni TEMEL islevi (gezinme + dosya
 * okuma) saglayan, ancak dogrudan `ScsiRawBlockDevice` uzerinde calisan,
 * SADECE-OKUMA bir dosya sistemi okuyucusu arabirimi. NTFS ve exFAT icin
 * kullanilir -- cunku libaums bu iki formati ANLAMIYOR (bkz.
 * RawFileSystemSniffer basindaki KOK NEDEN notu).
 *
 * BILINCLI KAPSAM SINIRI: bu okuyucular yazma / silme / tasima / yeniden
 * adlandirma YAPMAZ -- sadece gezinme + dosya okuma (onizleme, paylas,
 * kopyala-DISARI). NTFS/exFAT bir USB'de dosya DEGISTIRMEK isteyen kullanici
 * hala Bicimlendir ekranindan FAT32'ye cevirmelidir. Bu, gercek-dunya
 * USB-OTG uygulamalarinin da (yazma icin genelde ayri/ticari bir surucu
 * gerektiren) yaygin olarak benimsedigi guvenli bir tasarim tercihidir --
 * sifirdan yazilmis bir NTFS/exFAT YAZICISI/DUZENLEYICISI, kullanicinin
 * GERCEK verisini sessizce bozma riski tasir ve bu projede BILINCLI olarak
 * yapilmamistir.
 */
interface RawVolumeReader {
    /** Kisa etiket: "NTFS" veya "exFAT" (ekranda gosterilir). */
    val fileSystemLabel: String

    /** Kok dizin girisi (list() ile birlikte kullanilir). */
    val root: RawDirEntry

    /** Bir dizinin (kok dahil) alt ogelerini dondurur. `dir.token` bu okuyucuya ait olmalidir. */
    fun list(dir: RawDirEntry): List<RawDirEntry>

    /** Bir dosyanin icerigini AKIS (streaming) olarak acar -- tum dosyayi belleğe yuklemez. */
    fun openStream(file: RawDirEntry): InputStream

    /** Kapasite / kullanilan alan bilgisi (hesaplanamiyorsa null). */
    fun spaceInfo(): RawVolumeSpace?

    /** Alttaki ham USB baglantisini kapatir. Birden fazla cagrilmasi guvenlidir. */
    fun close()
}
