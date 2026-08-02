package com.usbmanager.app.ui.filemanager

import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.EditText
import android.widget.ImageView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.usbmanager.app.R
import com.usbmanager.app.core.AppPrefs
import com.usbmanager.app.databinding.FragmentFileManagerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class FileManagerFragment : Fragment() {

    private var _binding: FragmentFileManagerBinding? = null
    private val binding get() = _binding!!
    private val viewModel: FileManagerViewModel by viewModels()

    private lateinit var adapter: FileListAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFileManagerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = FileListAdapter(
            onClick = ::handleClick,
            onLongClick = { entry -> viewModel.toggleSelection(entry); adapter.notifyDataSetChanged(); true },
            isSelected = viewModel::isSelected
        )
        binding.recyclerFiles.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerFiles.adapter = adapter
        // Ayarlar > Animasyonlar kapaliysa liste ogeleri (ekleme/kaldirma/
        // secim degisimi) HICBIR gecis animasyonu OLMADAN aninda guncellenir.
        if (!AppPrefs.animationsEnabled(requireContext())) {
            binding.recyclerFiles.itemAnimator = null
        }

        binding.fabAdd.setOnClickListener { showCreateFolderDialog() }
        binding.actionCopy.setOnClickListener { viewModel.copySelectedToCopiesFolder() }
        binding.actionMove.setOnClickListener { showMoveTargetDialog() }
        binding.actionDelete.setOnClickListener { confirmDelete() }
        binding.actionDiskInfo.setOnClickListener { showDiskInfo() }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!viewModel.goUp()) {
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        observeViewModel()
        // NOT: ilk baglanti denemesi burada DEGIL, asagidaki onResume()
        // icinde yapiliyor -- onResume, Fragment yasam donguesunde
        // onViewCreated'dan HEMEN SONRA (ilk acilista da) zaten calisir; bu
        // yuzden burada AYRICA cagirmak, iki eszamanli USB baglanti denemesinin
        // yarismasina (birbirini kesintiye ugratmasina) yol acabilirdi.
    }

    override fun onResume() {
        super.onResume()
        // ONCEKI DAVRANIS: baglanti SADECE ekran ilk acildiginda bir kez
        // denenirdi; kullanici bu ekrana GELMEDEN ONCE USB'yi takarsa ya da
        // ilk deneme basarisiz olursa, ekrandan cikip tekrar girmeden (yeni
        // bir Fragment orneği olusmadan) hicbir yeniden deneme olmuyordu.
        // Sadece HENUZ BAGLI DEGILKEN yeniden deniyoruz ki dosya gezinme
        // konumu (hangi klasordeyiz) baska bir ekrandan donuste SIFIRLANMASIN.
        if (!viewModel.isConnected()) {
            viewModel.connectToFirstAvailableDevice()
        }
    }

    private fun observeViewModel() {
        viewModel.currentPath.observe(viewLifecycleOwner) { path ->
            binding.textCurrentPath.text = path ?: "/"
            val connected = path != null
            binding.textEmptyState.visibility = if (connected) View.GONE else View.VISIBLE
            binding.recyclerFiles.visibility = if (connected) View.VISIBLE else View.GONE
        }

        viewModel.files.observe(viewLifecycleOwner) { list ->
            adapter.submitList(list)
        }

        // NTFS/exFAT (ham okuyucu) modunda dosya ekleme/kopyalama/tasima/
        // silme YOK (bkz. RawVolumeReader.kt basindaki BILINCLI KAPSAM
        // SINIRI notu) -- bu yuzden ilgili butonlar bu modda GIZLENIR.
        // Disk bilgisi hala calisir, o yuzden gizlenmiyor.
        viewModel.readOnlyMode.observe(viewLifecycleOwner) { readOnly ->
            val writeVisibility = if (readOnly) View.GONE else View.VISIBLE
            binding.fabAdd.visibility = writeVisibility
            binding.actionCopy.visibility = writeVisibility
            binding.actionMove.visibility = writeVisibility
            binding.actionDelete.visibility = writeVisibility
        }

        viewModel.fileSystemLabel.observe(viewLifecycleOwner) { label ->
            binding.textFileSystemBadge.text = label ?: ""
            binding.textFileSystemBadge.visibility = if (label != null) View.VISIBLE else View.GONE
        }

        viewModel.statusMessage.observe(viewLifecycleOwner) { msg ->
            msg ?: return@observe
            Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
            // ONCEKI DAVRANIS: bu mesaj SADECE birkac saniyelik bir Snackbar
            // olarak gorunurdu; kullanici kacirirsa (orn. USB neden
            // algilanmadigini gosteren teshis mesaji), ekranda "Bağlı USB
            // depolama bulunamadı" gibi SABIT/genel bir yazidan baska hicbir
            // ipucu kalmiyordu. Artik ayni mesaj, baglanti kurulana kadar
            // EKRANDA KALICI olarak da gosteriliyor.
            if (viewModel.currentPath.value == null) {
                binding.textEmptyState.text = msg
            }
        }
    }

    private fun handleClick(entry: BrowseEntry) {
        if (viewModel.isSelected(entry)) {
            viewModel.toggleSelection(entry)
            adapter.notifyDataSetChanged()
            return
        }
        if (entry.isDirectory) {
            viewModel.openDirectory(entry)
        } else {
            openFile(entry)
        }
    }

    private fun openFile(entry: BrowseEntry) {
        val ext = entry.name.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"

        when {
            mime.startsWith("image/") -> previewImageInApp(entry)
            mime.startsWith("video/") || mime.startsWith("audio/") -> openWithExternalApp(entry, mime)
            else -> openWithExternalApp(entry, mime)
        }
    }

    /** Fotograflar icin basit, uygulama-ici onizleme (harici uygulamaya gerek yok). */
    private fun previewImageInApp(entry: BrowseEntry) {
        viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    viewModel.openInputStreamFor(entry)?.use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
            }
            if (bitmap == null) {
                Snackbar.make(binding.root, "Görsel açılamadı", Snackbar.LENGTH_SHORT).show()
                return@launch
            }
            val imageView = ImageView(requireContext()).apply { setImageBitmap(bitmap) }
            AlertDialog.Builder(requireContext())
                .setView(imageView)
                .setPositiveButton("Kapat", null)
                .show()
        }
    }

    /** Video/muzik/diger dosyalar icin: onbellege kopyala -> FileProvider -> "Birlikte Aç". */
    private fun openWithExternalApp(entry: BrowseEntry, mime: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val cachedFile = withContext(Dispatchers.IO) {
                runCatching {
                    val input = viewModel.openInputStreamFor(entry) ?: return@runCatching null
                    val sharedDir = File(requireContext().cacheDir, "shared").apply { mkdirs() }
                    val outFile = File(sharedDir, entry.name)
                    input.use { ins -> outFile.outputStream().use { output -> ins.copyTo(output) } }
                    outFile
                }.getOrNull()
            } ?: run {
                Snackbar.make(binding.root, "Dosya hazırlanamadı", Snackbar.LENGTH_SHORT).show()
                return@launch
            }

            val uri = FileProvider.getUriForFile(
                requireContext(), "${requireContext().packageName}.fileprovider", cachedFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching {
                startActivity(Intent.createChooser(intent, getString(R.string.fm_open_with)))
            }.onFailure {
                Snackbar.make(binding.root, "Bu dosya türünü açacak uygulama bulunamadı", Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun showCreateFolderDialog() {
        val input = EditText(requireContext())
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Yeni Klasör")
            .setView(input)
            .setPositiveButton("Oluştur") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    viewModel.createFolder(name)
                }
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun showMoveTargetDialog() {
        val targets = viewModel.siblingDirectoryNames()
        if (targets.isEmpty()) {
            Snackbar.make(binding.root, "Bu klasörde hedef alt klasör yok", Snackbar.LENGTH_SHORT).show()
            return
        }
        val names = targets.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Taşınacak klasörü seçin")
            .setItems(names) { _, which -> viewModel.moveSelectedTo(targets[which]) }
            .show()
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.fm_delete)
            .setMessage("Seçili öğeler kalıcı olarak silinecek. Emin misiniz?")
            .setPositiveButton("Sil") { _, _ -> viewModel.deleteSelected() }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun showDiskInfo() {
        viewLifecycleOwner.lifecycleScope.launch {
            val info = viewModel.diskInfo()
            val message = if (info != null) {
                if (info.freeBytes == 0L && info.occupiedBytes == 0L && info.capacityBytes > 0L) {
                    // NTFS/exFAT ham okuyucuda kullanilan alan hesaplanamadiysa
                    // (bkz. RawVolumeReader.kt) sadece kapasiteyi gosteriyoruz.
                    "Kapasite: ${info.capacityBytes / (1024 * 1024)} MB"
                } else {
                    "Kapasite: ${info.capacityBytes / (1024 * 1024)} MB\n" +
                        "Boş: ${info.freeBytes / (1024 * 1024)} MB\n" +
                        "Kullanılan: ${info.occupiedBytes / (1024 * 1024)} MB"
                }
            } else "Disk bilgisi alınamadı"

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.fm_disk_info)
                .setMessage(message)
                .setPositiveButton("Tamam", null)
                .show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
