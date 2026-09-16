plugins {
    id("com.android.application") version "9.4.0" apply false
    // AGP 9 compila Kotlin da sé: il plugin qui fissa solo la versione del compilatore.
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
