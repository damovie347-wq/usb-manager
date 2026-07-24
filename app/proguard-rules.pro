# USB Manager - ProGuard / R8 kurallari

# libaums: SCSI/FAT32 driver siniflarini reflection ile kullanabilir,
# bu yuzden kutuphanenin kendi paketini korumaya aliyoruz.
-keep class me.jahnen.libaums.** { *; }
-dontwarn me.jahnen.libaums.**

# ViewBinding olusturulan siniflarin isimlerini koru (debug icin faydali)
-keepclassmembers class * implements androidx.viewbinding.ViewBinding {
    public static *** inflate(...);
    public static *** bind(...);
}
