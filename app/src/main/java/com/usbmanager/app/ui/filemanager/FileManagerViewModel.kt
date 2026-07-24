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

    private val _currentDir = MutableLiveData<UsbFile?>()
    val currentDir: LiveData<UsbFile?> = _currentDir

    private val _files = MutableLiveData<List<UsbFile>>(emptyList())
    val files: LiveData<List<UsbFile>> = _files

    private val _selected = MutableLiveData<Set<String>>(emptySet())
    val selected: LiveData<Set<String>> = _selected

    private val _statusMessage = MutableLiveData<String?>()
    val statusMessage: LiveData<String?> = _statusMessage

    fun connectToFirstAvailableDevice() {
        val ctx = getApplication<android.app.Application>()
        val device = UsbMassStorageManager.listDevices(ctx).firstOrNull()
        if (device == null) {
            fileSystem = null
            _currentDir.value = null
            _files.value = emptyList()
            return
        }
        UsbMassStorageManager.requestPermissionIfNeeded(ctx, device.usbDevice) { granted ->
            if (!granted) {
                _statusMessage.postValue("USB erişim izni verilmedi")
                return@requestPermissionIfNeeded
            }
            viewModelScope.launch(Dispatchers.IO) {
                runCatching {
                    val partition = UsbMassStorageManager.openFirstPartition(device)
                    val fs = partition?.let { UsbMassStorageManager.fileSystemOf(it) }
                    fileSystem = fs
                    _currentDir.postValue(fs?.rootDirectory)
                }.onFailure {
                    _statusMessage.postValue("USB'ye bağlanılamadı: ${it.message}")
                }
                refresh()
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

    fun refresh() {
        val dir = _currentDir.value ?: run { _files.value = emptyList(); return }
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
}
