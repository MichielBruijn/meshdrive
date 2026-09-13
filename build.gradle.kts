plugins {
    id("com.android.application") version "8.6.1" apply false
    // Bumped from 2.0.20: com.github.mik3y:usb-serial-for-android:3.11.0 transitively
    // pulls kotlin-stdlib:2.2.10, which a 2.0.20 Kotlin plugin can't read the metadata
    // of ("Module was compiled with an incompatible version of Kotlin").
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10" apply false
}
