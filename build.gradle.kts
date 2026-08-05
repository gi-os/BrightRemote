plugins {
    id("com.android.application") version "8.7.3" apply false
    // 2.1.0 to match light-common: a library compiled by 2.1.0 carries metadata a 2.0.x
    // compiler refuses to read, so the app has to be at least level with it.
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
}
