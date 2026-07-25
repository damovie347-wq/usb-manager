// Kök proje seviyesi build dosyası.
// Alt modüller (app) kendi build.gradle.kts dosyalarında bu pluginleri uygular.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
