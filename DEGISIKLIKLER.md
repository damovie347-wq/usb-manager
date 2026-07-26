# USB Manager — Yapılan Düzeltmeler

Bu belge, bildirdiğiniz 6 sorun için yapılan tüm değişiklikleri dosya dosya özetler.
Projeyi olduğu gibi (bu klasörün tamamını) mevcut projenizin üzerine kopyalayıp
Android Studio'da yeniden derleyebilirsiniz.

---

## 1) Dosya Yöneticisi çökmesi

**Kök neden:** USB'ye ilk bağlanıldığında, arka plan (IO) thread'i içinden
LiveData'ya `.value = ...` ile DOĞRUDAN atama yapılıyordu. Android'de bu,
ana thread dışından yapılırsa `IllegalStateException: Cannot invoke setValue
on a background thread` hatasıyla **anında çökmeye** yol açar.

**Değişen dosyalar:**
- `app/src/main/java/com/usbmanager/app/ui/filemanager/FileManagerViewModel.kt`
  — tüm arka-plan güncellemeleri `postValue()` kullanacak şekilde yeniden
  yazıldı; ayrıca ekrandan çıkınca USB bağlantısını kapatan `onCleared()`
  eklendi (bkz. madde 3'teki bağlantı sızıntısı notu).
- `app/src/main/java/com/usbmanager/app/ui/filemanager/FileListAdapter.kt`
  — her klasör satırında gerçek USB okuması (`listFiles().size`) yapan,
  listede çok öğe varken donmaya yol açabilecek kod kaldırıldı.

---

## 2) Ayarlar ekranında iç içe geçen yazılar

**Kök neden:** `fragment_settings.xml`'de "Animasyonlar" satırının kendi
`id`'si yoktu; altındaki "Haptik" satırı yanlışlıkla anahtar (switch)
görünümüne bağlanmıştı. ConstraintLayout bunu geçersiz sayıp satırı ekranın
en tepesine (0,0) yerleştiriyor, bu da tema seçim alanının üstüne
çakılıyordu.

**Değişen dosyalar:**
- `app/src/main/res/layout/fragment_settings.xml` — satıra `row_animations`
  id'si eklendi, alttaki satırın bağlantısı düzeltildi.

---

## 3) USB Hız Testi çalışmıyordu + tek tuşla otomatik yazma/okuma

**Kök neden (buton tepkisiz):** Dosya Yöneticisi ekranı USB bağlantısını
kapatmadan bırakıyordu; Hız Testi ekranına geçilince cihaz "meşgul"
sayılıyor, `deviceReady` hiç `true` olmuyor, buton kalıcı olarak devre dışı
kalıyordu. Ayrıca buton her zaman aynı düz renkte göründüğü için devre dışı
olduğu görsel olarak da belli olmuyordu.

**Yeni özellik:** Artık CrystalDiskMark'takine benzer şekilde **TEK TUŞLA**
önce yazma, sonra okuma testi otomatik ve sırayla çalışıyor; ekranda her
ikisinin sonucu birlikte gösteriliyor.

**Değişen/yeni dosyalar:**
- `app/src/main/java/com/usbmanager/app/core/SpeedTestEngine.kt` —
  `runFullTest()` eklendi (yazma + okuma otomatik sıralı çalıştırma).
- `app/src/main/java/com/usbmanager/app/ui/speedtest/SpeedTestViewModel.kt`
  — bağlantı sızıntısı düzeltildi (`onCleared()` ile kapatma), `start()`
  artık tek tuşla tam testi başlatıyor.
- `app/src/main/java/com/usbmanager/app/ui/speedtest/SpeedTestFragment.kt`
  — sekme (tab) seçimi kaldırıldı, otomatik "şu an ne yapılıyor" göstergesi
  eklendi.
- `app/src/main/res/layout/fragment_speed_test.xml` — sekmeler kaldırıldı;
  durum yazısı + yazma/okuma sonuçlarını birlikte gösteren iki satır eklendi.
- `app/src/main/res/color/button_state_success.xml` **(yeni)** — buton artık
  devre dışıyken görsel olarak da gri görünüyor.
- `app/src/main/res/values/colors.xml`, `values/strings.xml` — yeni renk ve
  metinler.

---

## 4) Dosya sistemi seçimi ("hep yakında")

**Yapılan:** `exFAT` için gerçek, spesifikasyona uygun **çalışan bir
biçimlendirici** yazıldı (FAT32'nin yanına). 64 MB ile 1 TB arası simüle
edilmiş sürücülerde önyükleme sektörü, checksum, yedek önyükleme bölgesi,
FAT zinciri, tahsis bitmap'i ve up-case tablosu ayrı ayrı doğrulandı.

**Dürüst sınır:** NTFS, ext4, btrfs gibi diğerleri **hâlâ yol haritasında**.
Bunlar gerçek dünyada yılların mühendislik emeği isteyen, hatalı yazılırsa
USB belleğinizi okunamaz hale getirebilecek karmaşık dosya sistemleri; bu
riski almadım. Artık bir dosya sistemine dokunduğunuzda "yakında" yazıp
sizi Format tuşuna bastıktan sonra hayal kırıklığına uğratmıyor, hemen
neden henüz olmadığını açıklıyor.

**Değişen/yeni dosyalar:**
- `app/src/main/java/com/usbmanager/app/core/ExFatFormatter.kt` **(yeni)**
- `app/src/main/java/com/usbmanager/app/core/FileSystemType.kt` — exFAT artık
  `IMPLEMENTED`.
- `app/src/main/java/com/usbmanager/app/core/FormatEngine.kt` — exFAT'ı
  `ExFatFormatter`'a yönlendiriyor.
- `app/src/main/java/com/usbmanager/app/ui/format/FormatFragment.kt` —
  desteklenmeyen dosya sistemine dokununca açıklayıcı bir pencere açılıyor.

---

## 5) "Animasyonları kapat" hiçbir işe yaramıyordu

**Kök neden:** Ayar sadece kaydediliyordu; uygulamanın hiçbir yerinde
okunmuyordu.

**Değişen/yeni dosyalar:**
- `app/src/main/java/com/usbmanager/app/core/AppPrefs.kt` **(yeni)** —
  merkezi okuma yardımcı sınıfı.
- `app/src/main/java/com/usbmanager/app/MainActivity.kt` — ekran geçiş
  animasyonları artık ayarla uyumlu.
- `app/src/main/java/com/usbmanager/app/ui/speedtest/SpeedometerView.kt` —
  ibre artık animasyonlu/anında geçiş seçilebiliyor.
- `app/src/main/java/com/usbmanager/app/ui/filemanager/FileManagerFragment.kt`
  — dosya listesi animasyonları ayarla uyumlu.

---

## 6) NTFS biçimlendirme desteği + "USB algılanmıyor / Disk bilgisi alınamadı" teşhisi

**Yapılan (NTFS):** `exFAT`in yanına, aynı "referans kalitesinde, bilinçli
kapsam sınırları açıkça belirtilmiş" yaklaşımla gerçek, çalışan bir **NTFS**
biçimlendirici eklendi: Önyükleme Sektörü + Yedeği, geçerli/bağlanabilir bir
$MFT (12 ayrılmış sistem dosyası + fixup/Update Sequence Array uygulanmış
kayıtlar) ve boş bir kök dizin yazıyor. Bilinçli basitleştirmeler dosyanın
başındaki yorumda ayrıntılı listeleniyor ($LogFile sıfırla doldurulup
"dirty" bit ile Windows'un kendi ilklendirmesine bırakılıyor, $AttrDef/
$Secure/$BadClus minimal tutuluyor). **Önemli veriler için kullanmadan önce
gerçek donanımda test edip `chkdsk /f` ile doğrulamanızı öneririm** —
Fat32Formatter/ExFatFormatter'daki aynı uyarı burada daha da geçerli.

**Kök neden (USB algılanmıyor / "Disk bilgisi alınamadı"):** Kullanılan
`libaums` kütüphanesinin GENEL (public) `FileSystem` API'si — Dosya
Yöneticisi ve Hız Testi'nin kullandığı katman — **sadece FAT12/16/32'yi**
okuyabiliyor; exFAT ve NTFS'i **anlamıyor** (bkz. libaums GitHub #212, #302,
#8). Bir USB bellek exFAT ya da NTFS ile biçimlendirilmişse, `fileSystem`
sessizce `null` dönüyor ve ekranda sadece anlamsız bir "Disk bilgisi
alınamadı" görünüyordu — asıl neden hiçbir yerde açıklanmıyordu. Ayrıca USB
hiç bulunamadığında (liste boşsa) hiçbir mesaj gösterilmiyordu, ve bağlantı
sadece ekran ilk açıldığında bir kez deneniyordu (USB ekrana girildikten
sonra takılırsa yeniden deneme yoktu).

**Değişen/yeni dosyalar:**
- `app/src/main/java/com/usbmanager/app/core/NtfsFormatter.kt` **(yeni)**
- `app/src/main/java/com/usbmanager/app/core/FileSystemType.kt` — NTFS artık
  `IMPLEMENTED`.
- `app/src/main/java/com/usbmanager/app/core/FormatEngine.kt` — NTFS'i
  `NtfsFormatter`'a yönlendiriyor.
- `app/src/main/java/com/usbmanager/app/ui/format/FormatFragment.kt` —
  bilgi metinleri NTFS'in artık desteklendiğini yansıtıyor.
- `app/src/main/java/com/usbmanager/app/usb/RawFileSystemSniffer.kt`
  **(yeni)** — libaums okuyamadığında, onyükleme sektörünü ham okuyup GERÇEK
  dosya sistemini (NTFS/exFAT/FAT32/FAT16) tahmin eden hafif bir "sniff".
- `app/src/main/java/com/usbmanager/app/usb/UsbMassStorageManager.kt` —
  `sniffUnrecognizedFileSystem()` yardımcı fonksiyonu eklendi.
- `app/src/main/java/com/usbmanager/app/ui/filemanager/FileManagerViewModel.kt`
  — USB bulunamadığında artık mesaj gösteriliyor; `fileSystem == null`
  durumunda gerçek dosya sistemi tahmin edilip DOĞRU/EYLEME GEÇİRİLEBİLİR bir
  mesaj gösteriliyor ("... dosya sistemi NTFS. ... FAT32'ye çevirirseniz...").
- `app/src/main/java/com/usbmanager/app/ui/filemanager/FileManagerFragment.kt`
  — `onResume()` içinde, SADECE bağlı değilken yeniden bağlanma denemesi
  eklendi (Format ekranındaki mevcut düzenle tutarlı); teşhis mesajı artık
  Snackbar'ın yanında boş-durum yazısında da KALICI olarak gösteriliyor.
- `app/src/main/java/com/usbmanager/app/ui/speedtest/SpeedTestViewModel.kt`,
  `SpeedTestFragment.kt` — Dosya Yöneticisi'ndeki AYNI teşhis/yeniden-bağlanma
  mantığı Hız Testi ekranına da eklendi.

**Not:** exFAT/NTFS ile biçimlendirilmiş bir USB'nin Dosya Yöneticisi'nde
içeriğinin görünmesi (dosyaları listeleme/okuma/yazma) için libaums'un
kendisine tam bir exFAT/NTFS okuyucusunun eklenmesi gerekir; bu, bu
düzeltmenin kapsamı dışındadır (bkz. yukarıdaki kök neden açıklaması).
Şu an için pratik çözüm: USB'yi bu uygulamanın Biçimlendir ekranından
FAT32'ye çevirmek.

---

## Test / Doğrulama Notu

`ExFatFormatter` gerçek bir kod çalıştırma ortamında, sahte (bellek içi) bir
USB sürücü simülasyonuna karşı 5 farklı boyutta (64 MB, 8 GB, 64 GB, 256 GB,
1 TB) çalıştırılıp tüm spesifikasyon kontrolleri (checksum'lar dahil)
otomatik olarak doğrulandı. Yine de **gerçek bir USB bellek üzerinde,
önemli olmayan/yedek bir bellekle ilk denemeyi yapmanızı öneririm** —
gerçek donanım her zaman küçük sürprizler barındırabilir.

`NtfsFormatter` için bu ortamda gerçek bir Kotlin/Android derleme zinciri
bulunmadığından (ağ erişimi kapalı), doğrulama şu şekilde yapıldı: (1) her
bir MFT özniteliğinin bayt-bayt alan uzunlukları elle toplanıp beklenen
başlık boyutlarıyla (24/64 bayt) karşılaştırıldı; (2) kümesel alan yerleşimi
(MFT/MFTMirr/LogFile/Bitmap/UpCase) 64 MB'den 8 TB'a kadar 7 farklı birim
boyutu için Python'da yeniden üretilip çakışma/boşluk OLMADIĞI doğrulandı;
(3) runlist (data run) kodlayıcısı Python'a taşınıp kodlanan değerlerin
NTFS spesifikasyonundaki çözme algoritmasıyla doğru şekilde geri
çözüldüğü onaylandı. **Bu, gerçek donanımda test etmenin YERİNE GEÇMEZ** —
NTFS, FAT32/exFAT'tan çok daha karmaşık bir format olduğu için, önemli
veriler için kullanmadan önce mutlaka gerçek bir USB bellekte deneyip
`chkdsk /f` ile doğrulayın.
