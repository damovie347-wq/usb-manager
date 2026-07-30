package com.usbmanager.app.core

import com.usbmanager.app.usb.RawBlockDevice
import com.usbmanager.app.usb.RawIoUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * Microsoft'un NTFS spesifikasyonuna gore CALISAN, MINIMAL bir NTFS
 * "hizli format" bicimlendiricisi: Onyukleme Sektoru + Yedek Onyukleme
 * Sektoru + gecerli/bagli(mountable) bir $MFT (12 ayrilmis sistem dosyasi +
 * 4 bos yer tutucu kayit) + bos kok dizin.
 *
 * Fat32Formatter/ExFatFormatter'daki AYNI "referans kalitesinde, bilincli
 * kapsam sinirlari acikca belirtilmis" yaklasim burada da GECERLIDIR.
 * NTFS, FAT32/exFAT'tan cok daha karmasik bir dosya sistemi oldugu icin
 * (Master File Table + oznitelik/attribute kayitlari + fixup/Update
 * Sequence Array mekanizmasi), asagidaki BILINCLI KAPSAM SINIRLARI vardir:
 *
 *  - $LogFile SIFIRLA doldurulur (gercek bir islem/transaction log
 *    "restart area" yapisi YAZILMAZ); bunun yerine $Volume'un
 *    VolumeFlags alaninda "dirty" biti KASITLI OLARAK isaretlenir, boylece
 *    Windows/chkdsk ilk baglamada $LogFile'i kendisi guvenle ilklendirir.
 *    Bu, gercek dunyada minimal NTFS bicimlendiricilerin kullandigi bilinen
 *    ve guvenli bir yontemdir; tasinabilir bir USB bellekte Windows bunun
 *    icin erisimi engellemez.
 *  - $Secure sistem dosyasi, $SDS akisini BOS birakir; $SDH/$SII arama
 *    indeksleri YAZILMAZ. Butun kayitlarimiz security_id=0 (guvenlik
 *    tanimlayicisi yok) kullandigi icin bu, normal calisirken hic
 *    referans edilmez.
 *  - $BadClus, gercek NTFS'teki tum-birim-boyutunda "sparse" akis yerine
 *    BOS (0 bayt) bir akisla temsil edilir.
 *  - $AttrDef, gercek oznitelik tanim tablosu yerine BOS (0 bayt) birakilir
 *    (modern NTFS surucusu standart oznitelikleri zaten kendi icinde bilir).
 *  - $Extend dizini, alt ozel dosyalar ($Quota/$ObjId/$UsnJrnl/$Reparse)
 *    OLMADAN bos bir dizin olarak birakilir (bunlar Windows tarafindan
 *    ilgili ozellik ilk kullanildiginda zaten sonradan olusturulur).
 *  - Kok dizinin $INDEX_ROOT'u BOS birakilir (sistem dosyalari icin dizin
 *    girisi eklenmez); bu dosyalar MFT kayit numaralariyla dogrudan
 *    erisilir, yol/isim araramasiyla degil, bu yuzden normal "dir" gorunumu
 *    icin bir eksiklik yaratmaz.
 *
 * NOT (Fat32Formatter/ExFatFormatter'daki UYARI burada da AYNEN GECERLIDIR
 * -- hatta NTFS'in getirdigi ek karmasiklik nedeniyle DAHA da onemlidir):
 * bu, egitim/referans kalitesinde bir implementasyondur. Onemli veriler
 * icin kullanmadan once GERCEK donanimda test edilmeli, bicimlendirmeden
 * sonra Windows'ta `chkdsk /f` calistirilarak dogrulanmali ve mumkunse
 * `mkntfs`/`format /fs:ntfs` ciktisiyla karsilastirilmalidir.
 */
object NtfsFormatter {

    private const val SECTOR_SIZE = 512
    private const val CLUSTER_SIZE = 4096 // Windows'un NTFS icin tipik varsayilani
    private const val SECTORS_PER_CLUSTER = CLUSTER_SIZE / SECTOR_SIZE // 8
    private const val MFT_RECORD_SIZE = 1024
    private const val INDEX_RECORD_SIZE = 4096
    private const val UPCASE_ENTRY_COUNT = 65536
    private const val UPCASE_BYTES = UPCASE_ENTRY_COUNT * 2 // 131072 (128 KB)
    private const val RESERVED_RECORD_COUNT = 16 // 0..15 (fatgen'deki gibi NTFS'in de sabit ayirdigi ilk 16 MFT kaydi)

    // Sabit MFT kayit numaralari (NTFS spesifikasyonu)
    private const val REC_MFT = 0
    private const val REC_MFTMIRR = 1
    private const val REC_LOGFILE = 2
    private const val REC_VOLUME = 3
    private const val REC_ATTRDEF = 4
    private const val REC_ROOT = 5
    private const val REC_BITMAP = 6
    private const val REC_BOOT = 7
    private const val REC_BADCLUS = 8
    private const val REC_SECURE = 9
    private const val REC_UPCASE = 10
    private const val REC_EXTEND = 11

    // Attribute type kodlari
    private const val AT_STANDARD_INFORMATION = 0x10
    private const val AT_FILE_NAME = 0x30
    private const val AT_DATA = 0x80
    private const val AT_INDEX_ROOT = 0x90
    private const val AT_BITMAP = 0xB0
    private const val AT_VOLUME_NAME = 0x60
    private const val AT_VOLUME_INFORMATION = 0x70

    suspend fun format(
        raw: RawBlockDevice,
        volumeLabel: String = "",
        onProgress: (percent: Int) -> Unit = {}
    ) {
        require(raw.blockSizeBytes == SECTOR_SIZE) {
            "Bu formatter simdilik yalnizca 512 baytlik sektorleri destekler."
        }

        // Kismi/eksik son kumeyi (varsa) YOK sayiyoruz; TotalSectors alanini
        // KUME SINIRINA HIZALI olarak bildiriyoruz -- boylece "yedek onyukleme
        // sektoru" ile "bitmap'teki son kume" HER ZAMAN tutarli kalir.
        val totalClusters = raw.totalBytes / (SECTOR_SIZE.toLong() * SECTORS_PER_CLUSTER)
        val declaredTotalSectors = totalClusters * SECTORS_PER_CLUSTER
        val lastCluster = totalClusters - 1

        // --- 1) Sistem alanlarinin fiziksel kume yerlesimi ---
        var cursor = 1L // kume 0 = Onyukleme Sektoru bolgesi ($Boot)
        val mftStart = cursor
        val mftClusters = clustersFor(RESERVED_RECORD_COUNT.toLong() * MFT_RECORD_SIZE)
        cursor += mftClusters

        val mftMirrStart = cursor
        val mftMirrClusters = clustersFor(4L * MFT_RECORD_SIZE)
        cursor += mftMirrClusters

        val logFileBytes = chooseLogFileSize(raw.totalBytes)
        val logFileStart = cursor
        val logFileClusters = clustersFor(logFileBytes)
        cursor += logFileClusters

        val bitmapBytes = (totalClusters + 7) / 8
        val bitmapStart = cursor
        val bitmapClusters = clustersFor(bitmapBytes)
        cursor += bitmapClusters

        val upcaseStart = cursor
        val upcaseClusters = clustersFor(UPCASE_BYTES.toLong())
        cursor += upcaseClusters

        require(lastCluster - cursor > 1000) {
            "USB bellek NTFS ile bicimlendirilemeyecek kadar kucuk."
        }

        val volumeSerial = Random.nextLong()
        val now = filetimeNow()
        onProgress(3)

        // --- 2) Onyukleme Sektoru (sektor 0) + Yedek (son sektor) ---
        writeBootSector(raw, declaredTotalSectors, mftStart, mftMirrStart, volumeSerial)
        copySector(raw, fromSector = 0, toSector = declaredTotalSectors - 1)
        onProgress(8)

        // --- 3) $MFT kayitlarini bellekte olustur (henuz diske yazilmadi) ---
        val records = arrayOfNulls<ByteArray>(RESERVED_RECORD_COUNT)

        records[REC_MFT] = buildSystemFileRecord(
            recordNumber = REC_MFT, name = "\$MFT", isDirectory = false, now = now,
            extraAttributes = listOf(
                buildNonResidentAttribute(AT_DATA, "", mftStart, mftClusters, mftClusters * CLUSTER_SIZE),
                buildResidentAttribute(AT_BITMAP, "", mftBitmapContent())
            )
        )
        records[REC_MFTMIRR] = buildSystemFileRecord(
            recordNumber = REC_MFTMIRR, name = "\$MFTMirr", isDirectory = false, now = now,
            extraAttributes = listOf(
                buildNonResidentAttribute(AT_DATA, "", mftMirrStart, mftMirrClusters, 4L * MFT_RECORD_SIZE)
            )
        )
        records[REC_LOGFILE] = buildSystemFileRecord(
            recordNumber = REC_LOGFILE, name = "\$LogFile", isDirectory = false, now = now,
            extraAttributes = listOf(
                buildNonResidentAttribute(AT_DATA, "", logFileStart, logFileClusters, logFileClusters * CLUSTER_SIZE)
            )
        )
        records[REC_VOLUME] = buildSystemFileRecord(
            recordNumber = REC_VOLUME, name = "\$Volume", isDirectory = false, now = now,
            extraAttributes = listOf(
                buildResidentAttribute(AT_VOLUME_NAME, "", volumeLabel.toByteArray(Charsets.UTF_16LE)),
                buildResidentAttribute(AT_VOLUME_INFORMATION, "", buildVolumeInformation())
            )
        )
        records[REC_ATTRDEF] = buildSystemFileRecord(
            recordNumber = REC_ATTRDEF, name = "\$AttrDef", isDirectory = false, now = now,
            extraAttributes = listOf(
                buildResidentAttribute(AT_DATA, "", ByteArray(0))
            )
        )
        records[REC_ROOT] = buildSystemFileRecord(
            recordNumber = REC_ROOT, name = ".", isDirectory = true, now = now,
            parentOverride = mftRef(REC_ROOT, 1), // kok dizin KENDI kendisinin ebeveynidir (NTFS ozel kurali)
            extraAttributes = listOf(
                buildResidentAttribute(AT_INDEX_ROOT, "\$I30", buildEmptyIndexRoot(), indexed = true)
            )
        )
        records[REC_BITMAP] = buildSystemFileRecord(
            recordNumber = REC_BITMAP, name = "\$Bitmap", isDirectory = false, now = now,
            extraAttributes = listOf(
                buildNonResidentAttribute(AT_DATA, "", bitmapStart, bitmapClusters, bitmapBytes)
            )
        )
        records[REC_BOOT] = buildSystemFileRecord(
            recordNumber = REC_BOOT, name = "\$Boot", isDirectory = false, now = now,
            extraAttributes = listOf(
                buildNonResidentAttribute(AT_DATA, "", 0L, 1L, CLUSTER_SIZE.toLong())
            )
        )
        records[REC_BADCLUS] = buildSystemFileRecord(
            recordNumber = REC_BADCLUS, name = "\$BadClus", isDirectory = false, now = now,
            extraAttributes = listOf(
                buildResidentAttribute(AT_DATA, "", ByteArray(0)),
                buildResidentAttribute(AT_DATA, "\$Bad", ByteArray(0))
            )
        )
        records[REC_SECURE] = buildSystemFileRecord(
            recordNumber = REC_SECURE, name = "\$Secure", isDirectory = false, now = now,
            extraAttributes = listOf(
                buildResidentAttribute(AT_DATA, "\$SDS", ByteArray(0))
            )
        )
        records[REC_UPCASE] = buildSystemFileRecord(
            recordNumber = REC_UPCASE, name = "\$UpCase", isDirectory = false, now = now,
            extraAttributes = listOf(
                buildNonResidentAttribute(AT_DATA, "", upcaseStart, upcaseClusters, UPCASE_BYTES.toLong())
            )
        )
        records[REC_EXTEND] = buildSystemFileRecord(
            recordNumber = REC_EXTEND, name = "\$Extend", isDirectory = true, now = now,
            extraAttributes = listOf(
                buildResidentAttribute(AT_INDEX_ROOT, "\$I30", buildEmptyIndexRoot(), indexed = true)
            )
        )
        for (r in 12 until RESERVED_RECORD_COUNT) {
            records[r] = buildMftRecord(recordNumber = r, isDirectory = false, inUse = false, attributes = emptyList())
        }
        onProgress(45)

        // --- 4) $MFT bolgesini (16 kayit) ve $MFTMirr bolgesini (ilk 4 kayit) diske yaz ---
        val mftRegion = ByteArray(RESERVED_RECORD_COUNT * MFT_RECORD_SIZE)
        for (i in 0 until RESERVED_RECORD_COUNT) {
            System.arraycopy(records[i]!!, 0, mftRegion, i * MFT_RECORD_SIZE, MFT_RECORD_SIZE)
        }
        writeClusterData(raw, mftStart, mftClusters, mftRegion)
        onProgress(58)

        val mftMirrRegion = ByteArray(4 * MFT_RECORD_SIZE)
        for (i in 0 until 4) {
            System.arraycopy(records[i]!!, 0, mftMirrRegion, i * MFT_RECORD_SIZE, MFT_RECORD_SIZE)
        }
        writeClusterData(raw, mftMirrStart, mftMirrClusters, mftMirrRegion)
        onProgress(65)

        // --- 5) $LogFile: SIFIRLA doldurulur (bkz. yukaridaki kapsam notu) ---
        zeroFillClusters(raw, logFileStart, logFileClusters) { pct ->
            onProgress(65 + (pct * 15 / 100))
        }

        // --- 6) $Bitmap: birim genelindeki kume ayirma haritasi ---
        val bitmapContent = buildVolumeBitmap(
            bitmapSizeBytes = bitmapBytes,
            usedRanges = listOf(0L until cursor, lastCluster..lastCluster)
        )
        writeClusterData(raw, bitmapStart, bitmapClusters, bitmapContent)
        onProgress(90)

        // --- 7) $UpCase tablosu ---
        writeClusterData(raw, upcaseStart, upcaseClusters, buildUpcaseTable())
        onProgress(100)
    }

    // ---------------------------------------------------------------
    // MFT kayit insasi
    // ---------------------------------------------------------------

    private fun buildSystemFileRecord(
        recordNumber: Int,
        name: String,
        isDirectory: Boolean,
        now: Long,
        extraAttributes: List<ByteArray>,
        parentOverride: Long? = null
    ): ByteArray {
        val parentRef = parentOverride ?: mftRef(REC_ROOT, 1)
        val attrs = mutableListOf<ByteArray>()
        attrs += buildResidentAttribute(AT_STANDARD_INFORMATION, "", buildStandardInformation(now))
        attrs += buildResidentAttribute(AT_FILE_NAME, "", buildFileName(parentRef, name, isDirectory, now))
        attrs += extraAttributes
        // Oznitelikler tur koduna gore ARTAN sirada olmali (NTFS gereksinimi);
        // yukaridaki cagri sirasi zaten bunu saglayacak sekilde tasarlandi,
        // ama garanti olsun diye burada da siraliyoruz.
        attrs.sortBy { readAttrType(it) }
        return buildMftRecord(recordNumber, isDirectory, inUse = true, attributes = attrs)
    }

    private fun readAttrType(attr: ByteArray): Int =
        (attr[0].toInt() and 0xFF) or ((attr[1].toInt() and 0xFF) shl 8) or
            ((attr[2].toInt() and 0xFF) shl 16) or ((attr[3].toInt() and 0xFF) shl 24)

    /**
     * Tek bir MFT kaydini (header + fixup/Update Sequence Array uygulanmis)
     * olusturur. `inUse=false` ise (12-15 numarali ayrilmis/bos kayitlar)
     * gecerli bir "FILE" imzali ama BOS bir kayit yazilir.
     */
    private fun buildMftRecord(
        recordNumber: Int, isDirectory: Boolean, inUse: Boolean, attributes: List<ByteArray>
    ): ByteArray {
        val recordSize = MFT_RECORD_SIZE
        val sectorsInRecord = recordSize / SECTOR_SIZE
        val usaCount = sectorsInRecord + 1
        val usaOffset = 0x30
        val firstAttrOffset = align8(usaOffset + usaCount * 2)

        val buf = ByteArray(recordSize)

        // Oznitelik id'lerini 0..N-1 olarak sirayla atiyoruz (her oznitelik
        // basliginin ortak 0x0E ofsetindeki 2 baytlik alani).
        attributes.forEachIndexed { idx, a ->
            a[0x0E] = (idx and 0xFF).toByte()
            a[0x0F] = ((idx shr 8) and 0xFF).toByte()
        }

        var used = firstAttrOffset
        if (inUse) {
            var pos = firstAttrOffset
            for (a in attributes) {
                System.arraycopy(a, 0, buf, pos, a.size)
                pos += a.size
            }
            // Bitis isaretcisi: 0xFFFFFFFF
            buf[pos] = 0xFF.toByte(); buf[pos + 1] = 0xFF.toByte()
            buf[pos + 2] = 0xFF.toByte(); buf[pos + 3] = 0xFF.toByte()
            pos += 4
            used = align8(pos)
        }

        val header = ByteBuffer.wrap(buf, 0, usaOffset).order(ByteOrder.LITTLE_ENDIAN)
        header.put("FILE".toByteArray(Charsets.US_ASCII))       // 0x00 imza
        header.putShort(usaOffset.toShort())                      // 0x04 USA ofseti
        header.putShort(usaCount.toShort())                        // 0x06 USA eleman sayisi
        header.putLong(0L)                                           // 0x08 $LogFile sira no
        header.putShort(1)                                            // 0x10 sequence number
        header.putShort(if (inUse) 1 else 0)                            // 0x12 hard link count
        header.putShort(firstAttrOffset.toShort())                       // 0x14 ilk oznitelik ofseti
        val flags = (if (inUse) 0x0001 else 0x0000) or (if (inUse && isDirectory) 0x0002 else 0x0000)
        header.putShort(flags.toShort())                                  // 0x16 bayraklar
        header.putInt(used)                                                // 0x18 kullanilan boyut
        header.putInt(recordSize)                                            // 0x1C ayrilan boyut
        header.putLong(0L)                                                    // 0x20 taban kayit referansi
        header.putShort(attributes.size.toShort())                            // 0x28 sonraki oznitelik id
        header.putShort(0)                                                      // 0x2A hizalama/reserved
        header.putInt(recordNumber)                                              // 0x2C bu kaydin numarasi

        // --- Fixup (Update Sequence Array) uygula ---
        val usn = 1
        val usa = ShortArray(usaCount)
        usa[0] = usn.toShort()
        for (i in 0 until sectorsInRecord) {
            val tail = (i + 1) * SECTOR_SIZE - 2
            usa[i + 1] = ((buf[tail].toInt() and 0xFF) or ((buf[tail + 1].toInt() and 0xFF) shl 8)).toShort()
            buf[tail] = (usn and 0xFF).toByte()
            buf[tail + 1] = ((usn shr 8) and 0xFF).toByte()
        }
        val usaBuf = ByteBuffer.wrap(buf, usaOffset, usaCount * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in usa) usaBuf.putShort(s)

        return buf
    }

    // ---------------------------------------------------------------
    // Oznitelik icerikleri
    // ---------------------------------------------------------------

    private fun buildStandardInformation(filetime: Long): ByteArray {
        val buf = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN)
        buf.putLong(filetime) // olusturma
        buf.putLong(filetime) // degistirme
        buf.putLong(filetime) // MFT kaydi degistirme
        buf.putLong(filetime) // erisim
        buf.putInt(0)          // dosya oznitelikleri (basit/varsayilan)
        buf.putInt(0)          // maksimum surum sayisi
        buf.putInt(0)          // surum numarasi
        buf.putInt(0)          // sinif id
        return buf.array()
    }

    private fun buildFileName(parentRef: Long, name: String, isDirectory: Boolean, filetime: Long): ByteArray {
        val nameBytes = name.toByteArray(Charsets.UTF_16LE)
        val buf = ByteBuffer.allocate(0x42 + nameBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putLong(parentRef) // 0x00 ebeveyn dizin referansi
        buf.putLong(filetime)   // 0x08 olusturma
        buf.putLong(filetime)    // 0x10 degistirme
        buf.putLong(filetime)     // 0x18 MFT kaydi degistirme
        buf.putLong(filetime)      // 0x20 erisim
        buf.putLong(0L)             // 0x28 ayrilan boyut
        buf.putLong(0L)              // 0x30 gercek boyut
        buf.putInt(if (isDirectory) 0x10000000 else 0) // 0x38 bayraklar (NTFS-ozel DIZIN biti)
        buf.putInt(0)                                    // 0x3C reparse/EA boyutu
        buf.put(name.length.toByte())                     // 0x40 isim uzunlugu (karakter)
        buf.put(3)                                          // 0x41 ad alani: WIN32_AND_DOS
        buf.put(nameBytes)                                   // 0x42 isim (UTF-16LE)
        return buf.array()
    }

    private fun buildVolumeInformation(): ByteArray {
        val buf = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        buf.putLong(0L)     // ayrilmis
        buf.put(3)           // ana surum (NTFS 3.1)
        buf.put(1)            // alt surum
        // KASITLI: "dirty" biti (0x0001) isaretlendi -- bkz. dosya basindaki
        // $LogFile kapsam notu. Bu, Windows/chkdsk'in $LogFile'i ilk
        // baglantida guvenle kendi ilklendirmesini saglar.
        buf.putShort(0x0001)
        return buf.array()
    }

    /** Bos bir dizin icin $INDEX_ROOT degeri: ic gorunmez giris (0 cocuk). */
    private fun buildEmptyIndexRoot(): ByteArray {
        val buf = ByteBuffer.allocate(16 + 16 + 16).order(ByteOrder.LITTLE_ENDIAN)
        // --- INDEX_ROOT basligi (16 bayt) ---
        buf.putInt(AT_FILE_NAME)       // indekslenen oznitelik turu
        buf.putInt(1)                    // collation rule: COLLATION_FILE_NAME
        buf.putInt(INDEX_RECORD_SIZE)      // indeks kaydi boyutu (bayt)
        buf.put(1)                          // kume basina indeks kaydi (kume>=4096 oldugu icin pozitif=1)
        buf.put(ByteArray(3))                 // dolgu

        // --- INDEX_HEADER (16 bayt) ---
        val entriesStart = 16 // bu INDEX_HEADER'in basindan itibaren
        buf.putInt(entriesStart)                 // ilk girise ofset
        buf.putInt(entriesStart + 16)               // kullanilan toplam boyut (sadece bitis girisi)
        buf.putInt(entriesStart + 16)                 // ayrilan boyut
        buf.put(0)                                      // bayraklar: 0 = kucuk/yaprak dugum ($INDEX_ALLOCATION yok)
        buf.put(ByteArray(3))                             // dolgu

        // --- Tek "bitis" girisi (16 bayt): gercek anahtar/veri YOK ---
        buf.putLong(0L)       // MFT referansi (kullanilmiyor)
        buf.putShort(16)        // bu girisin uzunlugu
        buf.putShort(0)          // anahtar (key) uzunlugu
        buf.putShort(0x0002)      // bayraklar: LAST_ENTRY
        buf.putShort(0)            // dolgu

        return buf.array()
    }

    /** $MFT'nin kendi $BITMAP'i: ilk 12 kayit (0-11) kullanimda, 12-15 bos. */
    private fun mftBitmapContent(): ByteArray = byteArrayOf(0xFF.toByte(), 0x0F)

    private fun buildVolumeBitmap(bitmapSizeBytes: Long, usedRanges: List<LongRange>): ByteArray {
        val bytes = ByteArray(bitmapSizeBytes.toInt())
        for (range in usedRanges) {
            for (c in range) {
                val byteIdx = (c / 8).toInt()
                val bitIdx = (c % 8).toInt()
                bytes[byteIdx] = (bytes[byteIdx].toInt() or (1 shl bitIdx)).toByte()
            }
        }
        return bytes
    }

    /**
     * exFAT'teki ZORUNLU-minimum yaklasimin NTFS'e uyarlanmis hali: tum
     * 65536 UTF-16 kod noktasi icin kimlik (identity) eslemesi, sadece
     * a-z -> A-Z gercek buyultme. Turkce 'ı/İ' gibi karakterlerde Windows'un
     * kendi tablosundan FARKLI davranabilir (bkz. dosya basindaki not).
     */
    private fun buildUpcaseTable(): ByteArray {
        val buf = ByteBuffer.allocate(UPCASE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (code in 0 until UPCASE_ENTRY_COUNT) {
            val upper = if (code in 0x61..0x7A) code - 0x20 else code
            buf.putShort(upper.toShort())
        }
        return buf.array()
    }

    // ---------------------------------------------------------------
    // Oznitelik basligi olusturucular (resident / non-resident)
    // ---------------------------------------------------------------

    private fun buildResidentAttribute(type: Int, name: String, content: ByteArray, indexed: Boolean = false): ByteArray {
        val nameBytes = if (name.isEmpty()) ByteArray(0) else name.toByteArray(Charsets.UTF_16LE)
        val headerSize = 24
        val nameOffset = if (nameBytes.isEmpty()) 0 else headerSize
        val contentOffset = align8(headerSize + nameBytes.size)
        val total = align8(contentOffset + content.size)

        val buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(type)                       // 0x00 tur
        buf.putInt(total)                        // 0x04 toplam uzunluk
        buf.put(0)                                 // 0x08 non-resident = 0
        buf.put(name.length.toByte())                // 0x09 isim uzunlugu (karakter)
        buf.putShort(nameOffset.toShort())             // 0x0A isim ofseti
        buf.putShort(0)                                  // 0x0C bayraklar
        buf.putShort(0)                                   // 0x0E oznitelik id (sonradan atanir)
        buf.putInt(content.size)                            // 0x10 deger uzunlugu
        buf.putShort(contentOffset.toShort())                 // 0x14 deger ofseti
        buf.put(if (indexed) 1 else 0)                          // 0x16 indexed bayragi
        buf.put(0)                                                // 0x17 dolgu

        val arr = buf.array()
        if (nameBytes.isNotEmpty()) System.arraycopy(nameBytes, 0, arr, nameOffset, nameBytes.size)
        System.arraycopy(content, 0, arr, contentOffset, content.size)
        return arr
    }

    private fun buildNonResidentAttribute(
        type: Int, name: String, startCluster: Long, clusterCount: Long, realSize: Long
    ): ByteArray {
        val nameBytes = if (name.isEmpty()) ByteArray(0) else name.toByteArray(Charsets.UTF_16LE)
        val headerSize = 64
        val nameOffset = if (nameBytes.isEmpty()) 0 else headerSize
        val runlistOffset = align8(headerSize + nameBytes.size)
        val runlist = encodeRunlistSingleRun(clusterCount, startCluster)
        val total = align8(runlistOffset + runlist.size)
        val allocatedSize = clusterCount * CLUSTER_SIZE

        val buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(type)                          // 0x00 tur
        buf.putInt(total)                           // 0x04 toplam uzunluk
        buf.put(1)                                    // 0x08 non-resident = 1
        buf.put(name.length.toByte())                   // 0x09 isim uzunlugu (karakter)
        buf.putShort(nameOffset.toShort())                // 0x0A isim ofseti
        buf.putShort(0)                                     // 0x0C bayraklar
        buf.putShort(0)                                      // 0x0E oznitelik id (sonradan atanir)
        buf.putLong(0L)                                        // 0x10 baslangic VCN
        buf.putLong(clusterCount - 1)                            // 0x18 bitis VCN
        buf.putShort(runlistOffset.toShort())                      // 0x20 runlist ofseti
        buf.putShort(0)                                              // 0x22 sikistirma birimi
        buf.putInt(0)                                                 // 0x24 ayrilmis
        buf.putLong(allocatedSize)                                      // 0x28 ayrilan boyut
        buf.putLong(realSize)                                             // 0x30 gercek boyut
        buf.putLong(realSize)                                               // 0x38 ilklendirilmis boyut

        val arr = buf.array()
        if (nameBytes.isNotEmpty()) System.arraycopy(nameBytes, 0, arr, nameOffset, nameBytes.size)
        System.arraycopy(runlist, 0, arr, runlistOffset, runlist.size)
        return arr
    }

    /**
     * Tek bir bitisik (contiguous) calisma/run icin NTFS runlist kodlamasi:
     * [1 bayt: (offsetByteCount<<4)|lengthByteCount] [length, kucuk-endian,
     * isaretsiz] [offset, kucuk-endian, iaretli/two's-complement] [0x00 sonlandirici].
     */
    private fun encodeRunlistSingleRun(lengthClusters: Long, startLcn: Long): ByteArray {
        val lenBytes = minBytesUnsigned(lengthClusters).coerceAtLeast(1)
        val offBytes = minBytesSigned(startLcn).coerceAtLeast(1)
        val out = ByteArray(1 + lenBytes + offBytes + 1) // sonda +1 = 0x00 sonlandirici
        out[0] = ((offBytes shl 4) or lenBytes).toByte()
        var v = lengthClusters
        for (i in 0 until lenBytes) { out[1 + i] = (v and 0xFF).toByte(); v = v ushr 8 }
        var o = startLcn
        for (i in 0 until offBytes) { out[1 + lenBytes + i] = (o and 0xFF).toByte(); o = o shr 8 }
        return out
    }

    private fun minBytesUnsigned(value: Long): Int {
        if (value == 0L) return 1
        var v = value; var n = 0
        while (v != 0L) { n++; v = v ushr 8 }
        return n
    }

    private fun minBytesSigned(value: Long): Int {
        var n = 1
        while (true) {
            val bits = n * 8
            val min = -(1L shl (bits - 1))
            val max = (1L shl (bits - 1)) - 1
            if (value in min..max) return n
            n++
        }
    }

    // ---------------------------------------------------------------
    // Onyukleme Sektoru
    // ---------------------------------------------------------------

    private fun writeBootSector(
        raw: RawBlockDevice, totalSectors: Long, mftStartCluster: Long, mftMirrStartCluster: Long, volumeSerial: Long
    ) {
        val buf = ByteBuffer.allocate(SECTOR_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(byteArrayOf(0xEB.toByte(), 0x52, 0x90.toByte())) // 0x00 jmp
        buf.put("NTFS    ".toByteArray(Charsets.US_ASCII))        // 0x03 OEM ID
        buf.putShort(SECTOR_SIZE.toShort())                          // 0x0B bayt/sektor
        buf.put(SECTORS_PER_CLUSTER.toByte())                          // 0x0D sektor/kume
        buf.putShort(0)                                                  // 0x0E ayrilmis sektor
        buf.put(ByteArray(3))                                              // 0x10 kullanilmiyor
        buf.putShort(0)                                                      // 0x13 kullanilmiyor
        buf.put(0xF8.toByte())                                                 // 0x15 medya tanimlayici
        buf.putShort(0)                                                          // 0x16 kullanilmiyor
        buf.putShort(0x3F)                                                         // 0x18 sektor/track (dummy)
        buf.putShort(0xFF.toShort())                                                 // 0x1A kafa sayisi (dummy)
        buf.putInt(0)                                                                  // 0x1C gizli sektor
        buf.putInt(0)                                                                    // 0x20 kullanilmiyor
        buf.put(0x80.toByte())                                                             // 0x24 fiziksel surucu no
        buf.put(0)                                                                           // 0x25 mevcut kafa/ayrilmis
        buf.put(0x80.toByte())                                                                 // 0x26 genisletilmis imza
        buf.put(0)                                                                               // 0x27 ayrilmis
        buf.putLong(totalSectors)                                                                  // 0x28 toplam sektor
        buf.putLong(mftStartCluster)                                                                  // 0x30 $MFT kume no
        buf.putLong(mftMirrStartCluster)                                                                // 0x38 $MFTMirr kume no
        buf.put(0xF6.toByte())                                                                            // 0x40 kume/MFT-kaydi = -10 -> 1024 bayt
        buf.put(ByteArray(3))                                                                               // 0x41 kullanilmiyor
        buf.put(1)                                                                                            // 0x44 kume/indeks-arabellegi = 1 -> 4096 bayt
        buf.put(ByteArray(3))                                                                                   // 0x45 kullanilmiyor
        buf.putLong(volumeSerial)                                                                                 // 0x48 birim seri no
        buf.putInt(0)                                                                                               // 0x50 sağlama (kullanilmiyor)
        // 0x54 - 0x1FD: onyukleme kodu alani -- bilerek BOS (veri diski,
        // onyuklenebilir olmasi gerekmiyor).
        buf.position(0x1FE)
        buf.put(0x55); buf.put(0xAA.toByte())
        buf.rewind()
        raw.writeAt(0, buf)
    }

    private fun copySector(raw: RawBlockDevice, fromSector: Long, toSector: Long) {
        val buf = ByteBuffer.allocate(SECTOR_SIZE)
        raw.readAt(fromSector * SECTOR_SIZE, buf)
        buf.rewind()
        raw.writeAt(toSector * SECTOR_SIZE, buf)
    }

    // ---------------------------------------------------------------
    // Yardimcilar
    // ---------------------------------------------------------------

    private fun mftRef(recordNumber: Int, sequenceNumber: Int): Long =
        (sequenceNumber.toLong() shl 48) or recordNumber.toLong()

    /** 1601-01-01'den beri 100ns birimler (Windows FILETIME). */
    private fun filetimeNow(): Long = (System.currentTimeMillis() + 11_644_473_600_000L) * 10_000L

    private fun clustersFor(bytes: Long): Long = (bytes + CLUSTER_SIZE - 1) / CLUSTER_SIZE

    private fun chooseLogFileSize(totalBytes: Long): Long {
        val mb = totalBytes / (1024 * 1024)
        return when {
            mb <= 200 -> 2L * 1024 * 1024
            mb <= 2_000 -> 4L * 1024 * 1024
            mb <= 20_000 -> 16L * 1024 * 1024
            else -> 32L * 1024 * 1024
        }
    }

    private fun align8(v: Int): Int = (v + 7) and 7.inv()

    private fun zeroFillClusters(
        raw: RawBlockDevice, startCluster: Long, clusterCount: Long, onProgress: (Int) -> Unit
    ) {
        RawIoUtils.zeroFill(raw, startCluster * CLUSTER_SIZE, clusterCount * CLUSTER_SIZE, onProgress = onProgress)
    }

    /**
     * KRITIK PERFORMANS DUZELTMESI: bu fonksiyon ONCEDEN icerigi 512 bayt/cagri
     * (`writeBytesAt`) yaziyordu -- buyuk bir $Bitmap icin (ozellikle genis
     * hacimlerde) binlerce ayri USB komut dongusu, yani DAKIKALARCA surme
     * demekti. Artik RawIoUtils ile ~1 MB'lik BUYUK parcalar halinde (bkz.
     * RawIoUtils.kt basindaki not) -- aynisi artik saniyeler suruyor.
     */
    private fun writeClusterData(raw: RawBlockDevice, startCluster: Long, clusterCount: Long, data: ByteArray) {
        val fullSize = (clusterCount * CLUSTER_SIZE).toInt()
        val padded = if (data.size == fullSize) data else data.copyOf(fullSize)
        RawIoUtils.writeBulk(raw, startCluster * CLUSTER_SIZE, padded)
    }
}
