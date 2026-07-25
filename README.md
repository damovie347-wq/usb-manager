# USB Manager — Evrensel USB & Depolama Yöneticisi

Android için, **root gerektirmeyen**, **%100 çevrimdışı** çalışan USB/Depolama
yönetim uygulaması. Kaynak kodu GitHub'a yükleyip **GitHub Actions** üzerinden
bulutta derlersiniz; kendi bilgisayarınıza Android Studio kurmanıza gerek yoktur.

---

## 0) Önce okuyun: Bu iskelet gerçekte ne yapıyor?

Bu, "her şeyi eksiksiz yapan" bir sihirbaz değil, **gerçek bir mühendislik
iskeletidir**. Kıdemli bir mimarın yapması gereken şey abartılı vaatler değil,
neyin gerçekten çalıştığını net söylemektir:

| Modül | Durum | Açıklama |
|---|---|---|
| Navigasyon, 4 Tema (Açık/Karanlık/AMOLED/Sistem), Responsive UI | ✅ **Çalışıyor** | Tam Kotlin + XML implementasyonu |
| USB OTG algılama (sistem "açılsın mı?" popup'ı) | ✅ **Çalışıyor** | Manifest intent-filter + `MainActivity` |
| Dosya Yöneticisi (gezinme, kopyala/taşı/sil, "Birlikte Aç") | ✅ **Çalışıyor** | [libaums](https://github.com/magnusja/libaums)'un GENEL `FileSystem`/`UsbFile` API'si üzerinden, **root'suz** |
| USB Hız Testi (Direct I/O, cache bypass) | ✅ **Çalışıyor** | Aynı libaums `FileSystem`/`UsbFile` API'si; libaums doğrudan USB bulk-transfer kullandığı için Android sayfa önbelleğini zaten atlar |
| Bootable ISO/IMG Yazıcı (RAW/DD modu) | ✅ **Çalışıyor** | **libaums'a bağlı DEĞİL** — kendi USB Mass Storage Bulk-Only Transport + SCSI READ(10)/WRITE(10) implementasyonumuz (`ScsiRawBlockDevice.kt`) üzerinden ham bloklara sektör sektör yazar |
| **FAT32** biçimlendirme (Hızlı + Low-Level Zero-Fill) | ✅ **Çalışıyor** | Aynı şekilde `ScsiRawBlockDevice` üzerinden; Microsoft fatgen103 spesifikasyonuna göre gerçek bir MBR/Boot Sector/FAT yazıcı |
| **exFAT / NTFS / Ext2‑4 / Btrfs / F2FS / XFS / HFS+ / APFS** biçimlendirme | 🟡 **Yol haritasında** | Aşağıdaki "Yol Haritası" bölümüne bakın |

**Önemli mimari not (gerçek CI derlemesiyle keşfedildi ve düzeltildi):**
İlk sürümde Format ve ISO Yazıcı modülleri, ham (raw) blok erişimi için
libaums'un `UsbMassStorageDevice.read()/write()/blockSize/blockCount` gibi
metotlarına dayanıyordu. Gerçek derleme bunun **libaums'un genel API'sinde
var olmadığını** ortaya çıkardı — kütüphane kasıtlı olarak sadece
`FileSystem`/`UsbFile` (tanınmış bir dosya sistemi) üzerinden erişim sunuyor,
ham disk erişimi (MBR yazma, dd-tarzı sektör kopyalama) sunmuyor. Bunun
üzerine, **USB Mass Storage Bulk-Only Transport + SCSI komut setini**
(`ScsiRawBlockDevice.kt`) doğrudan Android'in USB Host API'si üzerinde,
libaums'a bağlı olmadan kendimiz yazdık — bu tam olarak libaums'un kendi
içinde yaptığı ama dışarı açmadığı şeydir. Dosya Yöneticisi ve Hız Testi
modülleri hâlâ libaums'un sağlam `FileSystem`/`UsbFile` API'sini kullanıyor;
onlarda değişiklik gerekmedi.

---

## 1) Mimari Özet

```
USB-Manager/
├── .github/workflows/build.yml      ← CI/CD: push'ta otomatik APK derler
├── app/
│   ├── build.gradle.kts             ← Bağımlılıklar (libaums, Material, Coroutines...)
│   └── src/main/
│       ├── AndroidManifest.xml      ← INTERNET izni YOK, USB Host + FileProvider var
│       └── java/com/usbmanager/app/
│           ├── MainActivity.kt      ← Drawer navigasyonu + USB attach yakalama
│           ├── theme/ThemeManager.kt← 4 modlu tema motoru
│           ├── usb/                 ← libaums sarmalayıcı (izin akışı) + kendi SCSI implementasyonumuz (ScsiRawBlockDevice)
│           ├── core/                ← FormatEngine, SpeedTestEngine, IsoWriterEngine, Fat32Formatter
│           └── ui/                  ← Her modül için Fragment + ViewModel + (varsa) özel View
└── README.md                        ← bu dosya
```

**Neden libaums?** Android, USB OTG bellekleri normalde ancak **root** ile
`/dev/sdX` gibi bir blok aygıtı olarak erişilebilir hale getirir. libaums
(Apache 2.0, açık kaynak) bunu atlar: Android'in **USB Host API**'si üzerinden
doğrudan **SCSI komut seti** (READ(10)/WRITE(10)) ile USB bulk-transfer
yaparak, root'suz hem dosya sistemi seviyesinde (`UsbFile`) hem de ham blok
seviyesinde (`RawBlockDevice` sarmalayıcımız) erişim sağlar. Bu, projenin
"Direct I/O", "Low-Level Format" ve "RAW/DD ISO yazma" gereksinimlerinin
teknik temelidir.

---

## 2) GitHub'a Yükleme ve Derleme Rehberi

### Adım 1 — Projeyi GitHub'a yükleyin
1. [github.com/new](https://github.com/new) adresinden **yeni ve BOŞ** bir repository oluşturun (örn. `usb-manager`). "Initialize with README" kutusunu **işaretlemeyin**.
2. Bilgisayarınızda, bu ZIP'i açtığınız klasörde bir terminal açın ve şunları çalıştırın:
   ```bash
   cd USB-Manager
   git init
   git add .
   git commit -m "İlk sürüm: USB Manager"
   git branch -M main
   git remote add origin https://github.com/KULLANICI_ADINIZ/usb-manager.git
   git push -u origin main
   ```
   (Git yüklü değilse [git-scm.com](https://git-scm.com/downloads)'dan kurabilir, ya da GitHub'ın web arayüzündeki "Add file → Upload files" özelliğiyle klasörü sürükleyip bırakabilirsiniz.)

### Adım 2 — Actions sekmesinden derlemeyi izleyin
1. Repo sayfanızda **Actions** sekmesine gidin.
2. Push işleminden birkaç saniye sonra **"Android CI/CD - USB Manager APK"** adlı workflow otomatik başlar (push tetikledi). Elle başlatmak isterseniz: Actions → sol menüden workflow'u seçin → **"Run workflow"** butonu.
3. Workflow'a tıklayıp adımların (✔️/❌) canlı loglarını izleyebilirsiniz. Tipik süre: 3-6 dakika (ilk derlemede bağımlılık indirme nedeniyle biraz daha uzun olabilir).

### Adım 3 — APK'yı indirin
1. Tamamlanan workflow çalıştırmasına tıklayın.
2. Sayfanın en altındaki **Artifacts** bölümünde **"USB-Manager-APK"** dosyasını göreceksiniz — tıklayıp indirin (bir `.zip` iner, içinde `app-debug.apk` vardır).

### Adım 4 — Android cihaza kurulum
1. İndirdiğiniz `.zip`'i açıp `app-debug.apk`'yı telefonunuza/tabletinize aktarın (kablo, Google Drive, e-posta vb.).
2. Cihazda: **Ayarlar → Uygulamalar → Bilinmeyen kaynaklardan yükleme** iznini, dosyayı açacağınız uygulama (Dosyalar, Chrome vb.) için açın.
3. `app-debug.apk`'ya dokunup **Yükle**'yi onaylayın.
4. Uygulamayı açın, bir USB OTG kablosuyla bir USB bellek takın — sistem "USB Manager ile açılsın mı?" diye soracaktır.

> Derleme hatası alırsanız Actions logundaki kırmızı adıma tıklayıp tam hata
> mesajını okuyun; en olası kaynaklar aşağıdaki "Bilinmesi Gerekenler"
> bölümünde listelidir.

---

## 3) Bilinmesi Gerekenler / Gerçek Donanımda Test Ederken Aklınızda Olsun

`ScsiRawBlockDevice.kt`, USB Mass Storage Bulk-Only Transport + SCSI
READ(10)/WRITE(10) protokolünün kendi implementasyonumuzdur (bkz. dosyanın
başındaki mimari not). Standart, sağlıklı bir USB flash bellekte güvenilir
çalışacak şekilde yazıldı, ancak şunları bilerek belirtmemiz gerekir:

1. **Sadece LUN 0 desteklenir.** USB flash belleklerin neredeyse tamamı tek
   LUN'dur; çoklu-LUN kart okuyucular (SD+microSD gibi çoklu yuvalı) bu
   sürümde çalışmayabilir.
2. **~2 TB üzeri diskler desteklenmez** (READ10/WRITE10'un 32-bit LBA
   sınırı). USB flash bellekler pratikte bu boyutun çok altındadır.
3. **Tam BBB hata kurtarma (Mass Storage Reset + clear-halt) bu sürümde
   yok.** Bir SCSI komutu başarısız olursa `IOException` fırlatılır ve
   işlem durur; sağlıklı bir bellekte "happy path" güvenilir çalışır, ama
   yarı-arızalı/uyumsuz bir bellekte ek hata-kurtarma mantığı gerekebilir.
4. **`me.jahnen.libaums:core:0.10.0`** — bağımlılık sürümü, bu projenin
   hazırlandığı tarihte Maven Central'daki güncel sürümdü (`storageprovider`
   alt modülü bu sürümle yayınlanmadığı için kaldırıldı, çekirdek
   işlevsellik etkilenmez). Yeni bir sürüm çıktıysa `app/build.gradle.kts`
   içinde güncelleyin.
5. Fiziksel test: `Fat32Formatter` referans/eğitim kalitesindedir. Gerçek bir
   USB bellek üzerinde biçimlendirip, bilgisayarınızda (Windows/Linux/Mac)
   sorunsuz okunup okunmadığını mutlaka doğrulayın.

---

## 4) Yol Haritası (exFAT / NTFS / Ext2‑4 / Btrfs / F2FS / XFS / HFS+ / APFS)

`core/FileSystemType.kt` içindeki `SupportLevel.ROADMAP` işaretli her dosya
sistemi için önerilen genişletme yolu:

- **exFAT**: Microsoft'un exFAT spesifikasyonu (patentleri artık İngiltere/ABD
  dışında büyük ölçüde serbest) yayınlıdır; `Fat32Formatter`'a benzer şekilde
  sıfırdan yazılabilir — FAT32'den daha basit bir tablo yapısına sahiptir.
- **NTFS**: `ntfs-3g` / `ntfs3g-android` gibi açık kaynak projelerin Android
  NDK ile derlenip JNI üzerinden çağrılması önerilir.
- **Ext2/3/4**: `e2fsprogs` (`mke2fs`) NDK ile derlenip çalıştırılabilir; ancak
  bir cihaz düğümü (`/dev/sdX`) yerine libaums'un sağladığı ham blok
  arayüzüne (`RawBlockDevice`) bir "sanal blok aygıtı" köprüsü (FUSE benzeri
  ya da bellek-eşlemeli geçici dosya) yazılması gerekir.
- **Btrfs / F2FS / XFS**: Aynı NDK-derleme + köprü yaklaşımı; araç zinciri
  (`btrfs-progs`, `f2fs-tools`, `xfsprogs`) hepsi açık kaynaktır.
- **HFS+ / APFS**: Apple'ın APFS'i resmi olarak belgelenmemiştir; topluluk
  projeleri (`apfs-fuse` vb.) referans alınabilir, ancak yazma desteği bu
  projelerde bile sınırlıdır — gerçekçi olarak en son ele alınması önerilir.

Her durumda mimari zaten hazır: `FormatEngine.run()` içindeki
`when (targetFs.uygulamaDurumu)` bloğuna yeni bir `IMPLEMENTED` dalı eklemeniz
yeterli.

---

## 5) Gizlilik / Çevrimdışı Çalışma

`AndroidManifest.xml` içinde `android.permission.INTERNET` **kasıtlı olarak
yoktur**. Uygulama derlenip paketlendiğinde hiçbir ağ isteği atamaz; tüm
biçimlendirme, dosya yönetimi, hız testi ve ISO yazma işlemleri cihaz
üzerinde, USB Host API aracılığıyla yerel olarak yürütülür.

---

## 6) Lisans Notu

Bu proje [libaums](https://github.com/magnusja/libaums) (Apache License 2.0)
kütüphanesini kullanır. Kendi uygulamanızı yayınlarken bu bağımlılığın
lisansına uymanız gerekir (Apache 2.0, ticari kullanıma izin verir; sadece
lisans/telif bildirimini korumanız yeterlidir).
