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

## Test / Doğrulama Notu

`ExFatFormatter` gerçek bir kod çalıştırma ortamında, sahte (bellek içi) bir
USB sürücü simülasyonuna karşı 5 farklı boyutta (64 MB, 8 GB, 64 GB, 256 GB,
1 TB) çalıştırılıp tüm spesifikasyon kontrolleri (checksum'lar dahil)
otomatik olarak doğrulandı. Yine de **gerçek bir USB bellek üzerinde,
önemli olmayan/yedek bir bellekle ilk denemeyi yapmanızı öneririm** —
gerçek donanım her zaman küçük sürprizler barındırabilir.
