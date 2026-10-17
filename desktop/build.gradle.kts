import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    // Воспроизведение (MP3/FLAC/Opus/OGG/ALAC) через libVLC
    implementation("uk.co.caprica:vlcj:4.8.3")

    // Теги и обложки локальных файлов
    implementation("net.jthink:jaudiotagger:3.0.1")

    // Разбор ответов Subsonic API
    implementation("org.json:json:20240303")

    // Корутины для фоновых загрузок
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
}

compose.desktop {
    application {
        mainClass = "dev.local.player.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            // Встроенный libVLC и прочие ресурсы (заполняется при сборке в CI)
            appResourcesRootDir.set(project.layout.projectDirectory.dir("appResources"))
            packageName = "AkkMusic"
            packageVersion = (System.getenv("APP_VERSION") ?: "1.0.0")
            vendor = "akkraw"

            windows {
                menuGroup = "AkkMusic"
                // Стабильный UUID, чтобы обновления ставились поверх прошлой версии
                upgradeUuid = "7E9B2C14-5A3D-4F8E-9C1B-2D6A8F0E4B37"
                shortcut = true
            }
        }
    }
}
