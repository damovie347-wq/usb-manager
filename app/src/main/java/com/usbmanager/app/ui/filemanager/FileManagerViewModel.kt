package com.usbmanager.app.ui.filemanager

import android.app.Application
import android.hardware.usb.UsbDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.usbmanager.app.usb.ExFatReader
import com.usbmanager.app.usb.NtfsReader
import com.usbmanager.app.usb.RawDirEntry
import com.usbmanager.app.usb.RawVolumeReader
import com.usbmanager.app.usb.ScsiRawBlockDevice
import com.usbmanager.app.usb.UsbFileSystemSession
import com.usbmanager.app.usb.UsbMassStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.jahnen.libaums.core.fs.FileSystem
import me.jahnen.libaums.core.fs.UsbFile
import me.jahnen.libaums.core.fs.UsbFileInputStream
import me.jahnen.libaums.core.fs.UsbFileOutputStream
import java.io.InputStream

data class DiskInfo(
    val capacityBytes: Long,
    val freeBytes: Long,
    val occupiedBytes: Long
)

/**
 * Dosya Yoneticisi'nin listede gosterdigi HER OGE icin ORTAK, kaynak-bagimsiz
 * gorunum. libaums (FAT32) icin [Lib], sifirdan yazilmis HAM NTFS/exFAT
 * okuyuculari icin [Raw] kullanilir -- boylece Fragment/Adapter, altta HANGI
 * motorun calistigini bilmeden ayni sekilde listeyi gosterebilir.
 *
 * Bu, "NTFS/exFAT sadece FAT32 destekleniyor hatasi veriyor" sorununun GERCEK
 * duzeltmesidir: eskiden libaums'un FileSystem'i null donunce sadece bir
 * teshis mesaji gosterilip birakiliyordu (bkz. ExFatReader.kt / NtfsReader.kt
 * basindaki mimari notlar).
 */
sealed class BrowseEntry {
    abstract val name: String
    abstract val isDirectory: Boolean
    abstract val sizeBytes: Long

    data class Lib(val usbFile: UsbFile) : BrowseEntry() {
        override val name: String get() = usbFile.name
        override val isDirectory: Boolean get() = usbFile.isDirectory
        override val sizeBytes: Long get() = usbFile.length
    }

    data class Raw(val entry: RawDirEntry) : BrowseEntry() {
        override val name: String get() = entry.name
        override val isDirectory: Boolean get() = entry.isDirectory
        override val sizeBytes: Long get() = entry.sizeBytes
    }
}

class FileManagerViewModel(app: Application) : AndroidViewModel(app) {

    // ARTIK bu ViewModel kendi USB baglantisini AYRICA ACIP KAPAMIYOR;
    // paylasilan `UsbFileSystemSession`i kullaniyor (bkz. o dosyadaki KOK
    // NEDEN aciklamasi -- "her ekran degisiminde USB takildi/cikartildi"
    // bildirimlerinin gercek nedeni buydu).
    private var fileSystem: FileSystem? = null
    private var currentLibDir: UsbFile? = null

    // NTFS/exFAT icin: libaums yerine dogrudan calisan, SADECE-OKUMA ham
    // okuyucu (bkz. RawVolumeReader.kt). `fileSystem` ve `rawReader` ASLA
    // AYNI ANDA ikisi de dolu OLMAZ.
    private var rawReader: RawVolumeReader? = null
    private var rawCurrentDir: RawDirEntry? = null
    private val rawDirStack = ArrayDeque<RawDirEntry>()

    private val _currentPath = MutableLiveData<String?>()
    /** null = baglanti yok; aksi halde "/" ile baslayan gezinme yolu. */
    val currentPath: LiveData<String?> = _currentPath

    private val _files = MutableLiveData<List<BrowseEntry>>(emptyList())
    val files: LiveData<List<BrowseEntry>> = _files

    private val _selected = MutableLiveData<Set<String>>(emptySet())
    val selected: LiveData<Set<String>> = _selected

    private val _statusMessage = MutableLiveData<String?>()
    val statusMessage: LiveData<String?> = _statusMessage

    /**
     * true iken (NTFS/exFAT ham okuyucu modu) Fragment; klasor olusturma,
     * kopyalama, tasima, silme gibi YAZMA islemlerini GIZLEMELIDIR -- bkz.
     * RawVolumeReader.kt basindaki BILINCLI KAPSAM SINIRI notu. Bu ViewModel
     * ayrica kendi ic fonksiyonlarinda da bu modda yazma islemlerini
     * SESSIZCE reddeder (savunma amacli ikinci bir katman).
     */
    private val _readOnlyMode = MutableLiveData(false)
    val readOnlyMode: LiveData<Boolean> = _readOnlyMode

    /** Baglanildiginda tespit edilen dosya sistemi etiketi ("NTFS", "exFAT") -- FAT32/libaums'ta null. */
    private val _fileSystemLabel = MutableLiveData<String?>()
    val fileSystemLabel: LiveData<String?> = _fileSystemLabel

    /** Fragment, mevcut baglanti YOKKEN (ekrana donuldugunde) yeniden denemek icin kullanir. */
    fun isConnected(): Boolean = fileSystem != null || rawReader != null

    fun connectToFirstAvailableDevice() {
        val ctx = getApplication<Application>()
        val device = UsbMassStorageManager.listDevices(ctx).firstOrNull()
        if (device == null) {
            resetState()
            // ONCEKI DAVRANIS: burada HICBIR mesaj gosterilmiyordu; kullanici
            // "USB'yi taktim ama uygulama hicbir sey soylemiyor" diye
            // sikayet ediyordu (bkz. proje notlari). Artik en azindan NEDEN
            // baglanamadigimizi (sistem bu USB'yi Mass Storage olarak hic
            // gormuyor) acikca soyluyoruz.
            _statusMessage.postValue("Bağlı USB depolama bulunamadı. USB/OTG bağlantısını kontrol edip tekrar deneyin.")
            return
        }

        // PAYLASILAN oturumda AYNI aygita zaten acik bir libaums baglantisi
        // varsa, arabirimi tekrar claim/release ETMEDEN dogrudan onu kullan
        // (bkz. UsbFileSystemSession.kt basindaki KOK NEDEN aciklamasi).
        UsbFileSystemSession.existingFor(device.usbDevice)?.let { fs ->
            enterLibMode(fs)
            return
        }

        // ...veya AYNI aygita zaten acik bir NTFS/exFAT ham okuyucu varsa.
        UsbFileSystemSession.existingRawReaderFor(device.usbDevice.deviceId)?.let { reader ->
            enterRawMode(reader)
            return
        }

        UsbMassStorageManager.requestPermissionIfNeeded(ctx, device.usbDevice) { granted ->
            if (!granted) {
                _statusMessage.postValue("USB erişim izni verilmedi")
                return@requestPermissionIfNeeded
            }
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    // NOT: libaums, tanimadigi bir dosya sistemiyle (exFAT/NTFS)
                    // karsilastiginda kimi surumlerde `null` partition/fileSystem
                    // dondurur, kimi surumlerde ise device.init() icinde
                    // ISTISNA FIRLATIR. Asagida HER IKI durumu da AYNI teshis
                    // mantigina yonlendiriyoruz ki kullanici HANGI durumda olursa
                    // olsun dogru mesaji gorsun.
                    val fs = runCatching {
                        UsbMassStorageManager.openFirstPartition(device)
                            ?.let { p -> UsbMassStorageManager.fileSystemOf(p) }
                    }.getOrNull()

                    if (fs == null) {
                        runCatching { device.close() }
                        connectViaRawReader(ctx, device.usbDevice)
                        return@launch
                    }

                    UsbFileSystemSession.adopt(device.usbDevice, device, fs)
                    withContext(Dispatchers.Main) { enterLibMode(fs) }
                } catch (t: Throwable) {
                    _statusMessage.postValue("USB'ye bağlanılamadı: ${t.message}")
                    _files.postValue(emptyList())
                }
            }
        }
    }

    /**
     * libaums bu USB'yi ANLAYAMADIGINDA (exFAT/NTFS oldugu icin -- bkz.
     * RawFileSystemSniffer basindaki KOK NEDEN notu) cagrilir.
     *
     * ONCEKI DAVRANIS: burada sadece "bu sürüm yalnızca FAT32 okuyabiliyor"
     * diyen bir TESHIS mesaji gosterilip birakiliyordu -- kullanicinin asil
     * sikayeti buydu. Artik GERCEKTEN NTFS/exFAT ise (ExFatReader/NtfsReader
     * ile) dogrudan ACILIP icerigi GOSTERILIYOR (salt okunur -- bkz.
     * RawVolumeReader.kt); sadece HICBIR okuyucu (ne FAT32 ne NTFS ne exFAT)
     * bu USB'yi taniyamazsa eski teshis mesajina dusuluyor.
     */
    private suspend fun connectViaRawReader(ctx: Application, usbDevice: UsbDevice) {
        val opened = runCatching {
            // Baska bir ekranin (Bicimlendir/Bootable) HAM erisimden sonra
            // birakip gitmis olabilecegi ya da HENUZ acik olan paylasilan
            // libaums baglantisini once serbest birak (ayni arabirime iki
            // baglanti turu AYNI ANDA olamaz -- bkz. IsoWriterViewModel.kt).
            UsbFileSystemSession.releaseForExclusiveAccess()
            val raw = ScsiRawBlockDevice.open(ctx, usbDevice)
            val reader: RawVolumeReader? = ExFatReader.tryOpen(raw) ?: NtfsReader.tryOpen(raw)
            if (reader == null) runCatching { raw.close() }
            reader
        }.getOrNull()

        if (opened != null) {
            UsbFileSystemSession.adoptRawReader(usbDevice.deviceId, opened)
            withContext(Dispatchers.Main) {
                enterRawMode(opened)
                _statusMessage.value = "${opened.fileSystemLabel} biçimli USB açıldı. Bu sürümde ${opened.fileSystemLabel} için " +
                    "yalnızca görüntüleme/dosya açma desteklenir; ekleme, silme ve taşıma için Biçimlendir ekranından FAT32 kullanın."
            }
            return
        }

        val detected = UsbMassStorageManager.sniffUnrecognizedFileSystem(ctx, usbDevice)
        val message = if (detected != null) {
            "USB bellek algılandı ama dosya sistemi $detected. Bu sürümün Dosya Yöneticisi'si bu birimi açamadı; " +
                "Biçimlendir ekranından FAT32'ye çevirirseniz dosyalarınızı burada görebilirsiniz."
        } else {
            "USB bellek algılandı ama üzerinde okunabilir bir bölüm/dosya sistemi bulunamadı."
        }
        _statusMessage.postValue(message)
        _currentPath.postValue(null)
        _files.postValue(emptyList())
    }

    private fun enterLibMode(fs: FileSystem) {
        fileSystem = fs
        rawReader = null
        rawDirStack.clear()
        rawCurrentDir = null
        _readOnlyMode.value = false
        _fileSystemLabel.value = null
        val root = fs.rootDirectory
        currentLibDir = root
        _currentPath.value = buildLibPath(root)
        refreshLibDir(root)
    }

    private fun enterRawMode(reader: RawVolumeReader) {
        fileSystem = null
        currentLibDir = null
        rawReader = reader
        rawDirStack.clear()
        rawCurrentDir = reader.root
        _readOnlyMode.value = true
        _fileSystemLabel.value = reader.fileSystemLabel
        _currentPath.value = buildRawPath()
        refreshRawDir(reader.root)
    }

    private fun resetState() {
        fileSystem = null
        currentLibDir = null
        rawReader = null
        rawCurrentDir = null
        rawDirStack.clear()
        _readOnlyMode.value = false
        _fileSystemLabel.value = null
        _currentPath.value = null
        _files.value = emptyList()
    }

    fun openDirectory(entry: BrowseEntry) {
        when (entry) {
            is BrowseEntry.Lib -> {
                currentLibDir = entry.usbFile
                _currentPath.value = buildLibPath(entry.usbFile)
                clearSelection()
                refreshLibDir(entry.usbFile)
            }
            is BrowseEntry.Raw -> {
                if (rawReader == null) return
                rawCurrentDir?.let { rawDirStack.addLast(it) }
                rawCurrentDir = entry.entry
                _currentPath.value = buildRawPath()
                clearSelection()
                refreshRawDir(entry.entry)
            }
        }
    }

    fun goUp(): Boolean {
        if (rawReader != null) {
            val previous = rawDirStack.removeLastOrNull() ?: return false
            rawCurrentDir = previous
            _currentPath.value = buildRawPath()
            clearSelection()
            refreshRawDir(previous)
            return true
        }
        val parent = currentLibDir?.parent ?: return false
        currentLibDir = parent
        _currentPath.value = buildLibPath(parent)
        clearSelection()
        refreshLibDir(parent)
        return true
    }

    /** Ana thread'den (UI olaylarindan) cagrilmasi beklenir. */
    fun refresh() {
        if (rawReader != null) {
            rawCurrentDir?.let { refreshRawDir(it) }
        } else {
            currentLibDir?.let { refreshLibDir(it) }
        }
    }

    /**
     * KRITIK: Bu fonksiyon HEM ana thread'den HEM arka plan (IO) thread'inden
     * guvenle cagrilabilir; bu yuzden `_files` icin HER ZAMAN postValue()
     * kullanilir, asla dogrudan `.value =` atanmaz.
     */
    private fun refreshLibDir(dir: UsbFile) {
        viewModelScope.launch(Dispatchers.IO) {
            val children = runCatching { dir.listFiles().toList() }.getOrDefault(emptyList())
            val sorted = children.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            _files.postValue(sorted.map { BrowseEntry.Lib(it) })
        }
    }

    private fun refreshRawDir(dir: RawDirEntry) {
        val reader = rawReader ?: run { _files.postValue(emptyList()); return }
        viewModelScope.launch(Dispatchers.IO) {
            val children = runCatching { reader.list(dir) }.getOrDefault(emptyList())
            val sorted = children.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            _files.postValue(sorted.map { BrowseEntry.Raw(it) })
        }
    }

    private fun buildLibPath(file: UsbFile): String {
        val parts = mutableListOf<String>()
        var current: UsbFile? = file
        while (current != null && current.parent != null) {
            parts.add(0, current.name)
            current = current.parent
        }
        return "/" + parts.joinToString("/")
    }

    private fun buildRawPath(): String {
        val names = rawDirStack.mapNotNull { it.name.ifEmpty { null } } +
            listOfNotNull(rawCurrentDir?.name?.ifEmpty { null })
        return "/" + names.joinToString("/")
    }

    fun toggleSelection(entry: BrowseEntry) {
        val current = _selected.value.orEmpty().toMutableSet()
        if (!current.add(entry.name)) current.remove(entry.name)
        _selected.value = current
    }

    fun isSelected(entry: BrowseEntry) = _selected.value.orEmpty().contains(entry.name)

    fun clearSelection() {
        _selected.value = emptySet()
    }

    /** Gecerli klasorde yeni bir alt klasor olusturur. SADECE FAT32 (libaums) modunda calisir. */
    fun createFolder(name: String) {
        if (rawReader != null) return // bkz. RawVolumeReader.kt -- salt okunur
        val dir = currentLibDir ?: return
        if (name.isBlank()) return
        runCatching { dir.createDirectory(name) }
            .onFailure { _statusMessage.postValue("Klasör oluşturulamadı: ${it.message}") }
        refresh()
    }

    /** Seçili dosyaları kök dizindeki "Kopyalar" klasörüne kopyalar. SADECE FAT32 (libaums) modunda. */
    fun copySelectedToCopiesFolder() {
        if (rawReader != null) return
        val dir = currentLibDir ?: return
        val root = fileSystem?.rootDirectory ?: return
        val names = _selected.value.orEmpty()
        if (names.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val target = root.search("Kopyalar") ?: root.createDirectory("Kopyalar")
                names.forEach { name ->
                    val source = dir.search(name) ?: return@forEach
                    if (!source.isDirectory) copyFile(source, target, source.name)
                }
                _statusMessage.postValue("Kopyalandı -> /Kopyalar")
            }.onFailure {
                _statusMessage.postValue("Kopyalama hatası: ${it.message}")
            }
            clearSelection()
            refresh()
        }
    }

    /** Seçili dosyalari, adi verilen KARDES alt klasore tasir (kopyala + orijinali sil). SADECE FAT32 modunda. */
    fun moveSelectedTo(targetName: String) {
        if (rawReader != null) return
        val dir = currentLibDir ?: return
        val target = runCatching { dir.search(targetName) }.getOrNull() ?: return
        val names = _selected.value.orEmpty()
        if (names.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                names.forEach { name ->
                    val source = dir.search(name) ?: return@forEach
                    if (!source.isDirectory) {
                        copyFile(source, target, source.name)
                        source.delete()
                    }
                }
                _statusMessage.postValue("Taşındı -> ${target.name}")
            }.onFailure {
                _statusMessage.postValue("Taşıma hatası: ${it.message}")
            }
            clearSelection()
            refresh()
        }
    }

    fun deleteSelected() {
        if (rawReader != null) return
        val dir = currentLibDir ?: return
        val names = _selected.value.orEmpty()
        if (names.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                names.forEach { name -> dir.search(name)?.delete() }
                _statusMessage.postValue("Silindi")
            }.onFailure {
                _statusMessage.postValue("Silme hatası: ${it.message}")
            }
            clearSelection()
            refresh()
        }
    }

    /** Gecerli klasordeki alt klasor ADLARI -- "Taşı" hedef klasor secici icin. SADECE FAT32 modunda anlamlidir. */
    fun siblingDirectoryNames(): List<String> =
        _files.value.orEmpty().filter { it.isDirectory }.map { it.name }

    suspend fun diskInfo(): DiskInfo? = withContext(Dispatchers.IO) {
        val reader = rawReader
        if (reader != null) {
            val info = runCatching { reader.spaceInfo() }.getOrNull() ?: return@withContext null
            val used = info.usedBytes
            return@withContext DiskInfo(
                capacityBytes = info.capacityBytes,
                freeBytes = if (used != null) (info.capacityBytes - used).coerceAtLeast(0) else 0L,
                occupiedBytes = used ?: 0L
            )
        }
        val fs = fileSystem ?: return@withContext null
        runCatching {
            DiskInfo(
                capacityBytes = fs.capacity,
                freeBytes = fs.freeSpace,
                occupiedBytes = fs.occupiedSpace
            )
        }.getOrNull()
    }

    /** Dosya onizleme/harici uygulamayla acma icin akis acar (FAT32 VE NTFS/exFAT icin de calisir). */
    fun openInputStreamFor(entry: BrowseEntry): InputStream? = when (entry) {
        is BrowseEntry.Lib -> runCatching { UsbFileInputStream(entry.usbFile) }.getOrNull()
        is BrowseEntry.Raw -> rawReader?.let { reader -> runCatching { reader.openStream(entry.entry) }.getOrNull() }
    }

    private fun copyFile(source: UsbFile, targetDir: UsbFile, name: String) {
        val dest = targetDir.createFile(name)
        UsbFileInputStream(source).use { input ->
            UsbFileOutputStream(dest).use { output ->
                val buffer = ByteArray(1 * 1024 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    output.write(buffer, 0, n)
                }
            }
        }
    }

    // NOT: burada artik `onCleared()` icinde baglantiyi KAPATMIYORUZ --
    // baglanti PAYLASILAN oturuma (UsbFileSystemSession) ait; bu ekrandan
    // ayrilmak, baska bir ekranin (orn. Hiz Testi) HALA kullaniyor olabilecegi
    // baglantiyi koparmamali. Baglanti sadece aygit degistiginde/koptugunda
    // ya da Biçimlendir/Bootable ekrani ham erisim icin arabirimi istedigi
    // zaman kapanir.
}
