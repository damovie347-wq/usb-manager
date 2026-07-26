package com.usbmanager.app.ui.filemanager

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.usbmanager.app.usb.UsbMassStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.jahnen.libaums.core.UsbMassStorageDevice
import me.jahnen.libaums.core.fs.FileSystem
import me.jahnen.libaums.core.fs.UsbFile
import me.jahnen.libaums.core.fs.UsbFileInputStream
import me.jahnen.libaums.core.fs.UsbFileOutputStream

data class DiskInfo(
    val capacityBytes: Long,
    val freeBytes: Long,
    val occupiedBytes: Long
)

class FileManagerViewModel(app: Application) : AndroidViewModel(app) {

    private var fileSystem: FileSystem? = null

    // KRITIK DUZELTME: acik USB baglantisini tutuyoruz ki ekran kapaninca
    // (onCleared) kapatabilelim. Kapatilmazsa, baska bir ekran (orn. Hiz
    // Testi) ayni cihaza tekrar baglanmaya calistiginda libaums "mesgul"
    // hatasi verir ve o ekranlarda USB hic algilanmamis gibi gorunur.
    private var openDevice: UsbMassStorageDevice? = null

    private val _currentDir = MutableLiveData<UsbFile?>()
    val currentDir: LiveData<UsbFile?> = _currentDir

    private val _files = MutableLiveData<List<UsbFile>>(emptyList())
    val files: LiveData<List<UsbFile>> = _files

    private val _selected = MutableLiveData<Set<String>>(emptySet())
    val selected: LiveData<Set<String>> = _selected

    private val _statusMessage = MutableLiveData<String?>()
    val statusMessage: LiveData<String?> = _statusMessage

    /** Fragment, mevcut baglanti YOKKEN (ekrana donuldugunde) yeniden denemek icin kullanir. */
    fun isConnected(): Boolean = fileSystem != null

    fun connectToFirstAvailableDevice() {
        val ctx = getApplication<android.app.Application>()
        val device = UsbMassStorageManager.listDevices(ctx).firstOrNull()
        if (device == null) {
            fileSystem = null
            _currentDir.value = null
            _files.value = emptyList()
            // ONCEKI DAVRANIS: burada HICBIR mesaj gosterilmiyordu; kullanici
            // "USB'yi taktim ama uygulama hicbir sey soylemiyor" diye
            // sikayet ediyordu (bkz. proje notlari). Artik en azindan NEDEN
            // baglanamadigimizi (sistem bu USB'yi Mass Storage olarak hic
            // gormuyor) acikca soyluyoruz.
            _statusMessage.postValue("Bağlı USB depolama bulunamadı. USB/OTG bağlantısını kontrol edip tekrar deneyin.")
            return
        }
        UsbMassStorageManager.requestPermissionIfNeeded(ctx, device.usbDevice) { granted ->
            if (!granted) {
                _statusMessage.postValue("USB erişim izni verilmedi")
                return@requestPermissionIfNeeded
            }
            viewModelScope.launch(Dispatchers.IO) {
                // Onceki ekrandan acik kalmis olabilecek baglantiyi once kapat.
                closeCurrentDeviceQuietly()
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
                        // KOK NEDEN: libaums'un genel FileSystem API'si SADECE
                        // FAT12/16/32 okuyabiliyor (exFAT/NTFS degil -- bkz.
                        // RawFileSystemSniffer basindaki not). ONCEKI KOD burada
                        // "Disk bilgisi alınamadı" gibi anlamsiz bir sonuca
                        // duserdu. Simdi GERCEK dosya sistemini ham sektorden
                        // tahmin edip DOGRU/EYLEME GECIRILEBILIR bir mesaj
                        // gosteriyoruz.
                        runCatching { device.close() }
                        val detected = UsbMassStorageManager.sniffUnrecognizedFileSystem(ctx, device.usbDevice)
                        val message = if (detected != null) {
                            "USB bellek algılandı ama dosya sistemi $detected. Bu sürümün Dosya Yöneticisi'si yalnızca FAT32'yi okuyabiliyor; " +
                                "Biçimlendir ekranından FAT32'ye çevirirseniz dosyalarınızı burada görebilirsiniz."
                        } else {
                            "USB bellek algılandı ama üzerinde okunabilir bir bölüm/dosya sistemi bulunamadı."
                        }
                        _statusMessage.postValue(message)
                        _currentDir.postValue(null)
                        _files.postValue(emptyList())
                        return@launch
                    }

                    fileSystem = fs
                    openDevice = device
                    val root = fs.rootDirectory
                    _currentDir.postValue(root)
                    // ESKI KOD BURADA dogrudan refresh() CAGIRIYORDU; refresh()
                    // ise _currentDir.value'yu (henuz postValue'nun ana thread'e
                    // ULASMADIGI icin HALA ESKI/NULL degeri donduren LiveData
                    // alanini) okuyup bos oldugunu saniyor, ustune ustluk
                    // "_files.value = emptyList()" satirini bu ARKA PLAN
                    // THREAD'INDEN cagiriyordu -> "Cannot invoke setValue on a
                    // background thread" IstateException'i ile UYGULAMA COKUYORDU.
                    // Duzeltme: dizini LiveData'dan tekrar OKUMAK yerine,
                    // elimizdeki GERCEK 'root' degerini DOGRUDAN kullaniyoruz ve
                    // her yerde postValue() kullaniyoruz.
                    refreshDir(root)
                } catch (t: Throwable) {
                    _statusMessage.postValue("USB'ye bağlanılamadı: ${t.message}")
                    _files.postValue(emptyList())
                }
            }
        }
    }

    fun openDirectory(dir: UsbFile) {
        _currentDir.value = dir
        clearSelection()
        refresh()
    }

    fun goUp(): Boolean {
        val parent = _currentDir.value?.parent ?: return false
        _currentDir.value = parent
        clearSelection()
        refresh()
        return true
    }

    /** Ana thread'den (UI olaylarindan) cagrilmasi beklenir. */
    fun refresh() {
        refreshDir(_currentDir.value)
    }

    /**
     * KRITIK: Bu fonksiyon HEM ana thread'den HEM arka plan (IO) thread'inden
     * guvenle cagrilabilir; bu yuzden `_files` icin HER ZAMAN postValue()
     * kullanilir, asla dogrudan `.value =` atanmaz.
     */
    private fun refreshDir(dir: UsbFile?) {
        if (dir == null) {
            _files.postValue(emptyList())
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val children = runCatching { dir.listFiles().toList() }.getOrDefault(emptyList())
            _files.postValue(children.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })))
        }
    }

    fun toggleSelection(file: UsbFile) {
        val current = _selected.value.orEmpty().toMutableSet()
        if (!current.add(file.name)) current.remove(file.name)
        _selected.value = current
    }

    fun isSelected(file: UsbFile) = _selected.value.orEmpty().contains(file.name)

    fun clearSelection() {
        _selected.value = emptySet()
    }

    /** Seçili dosyaları kök dizindeki "Kopyalar" klasörüne kopyalar. */
    fun copySelectedToCopiesFolder() {
        val dir = _currentDir.value ?: return
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

    /** Seçili dosyaları verilen hedef klasöre taşır (kopyala + orijinali sil). */
    fun moveSelectedTo(target: UsbFile) {
        val dir = _currentDir.value ?: return
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
        val dir = _currentDir.value ?: return
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

    fun siblingDirectories(): List<UsbFile> =
        _files.value.orEmpty().filter { it.isDirectory }

    suspend fun diskInfo(): DiskInfo? = withContext(Dispatchers.IO) {
        val fs = fileSystem ?: return@withContext null
        runCatching {
            DiskInfo(
                capacityBytes = fs.capacity,
                freeBytes = fs.freeSpace,
                occupiedBytes = fs.occupiedSpace
            )
        }.getOrNull()
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

    private fun closeCurrentDeviceQuietly() {
        runCatching { openDevice?.close() }
        openDevice = null
    }

    override fun onCleared() {
        super.onCleared()
        closeCurrentDeviceQuietly()
    }
}
