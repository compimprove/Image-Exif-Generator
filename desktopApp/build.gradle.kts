import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

val releaseVersion = providers.environmentVariable("RELEASE_VERSION").orElse("1.0.0")

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)
}

compose.desktop {
    application {
        mainClass = "org.compi.image_exif_reset.MainKt"

        nativeDistributions {
            appResourcesRootDir.set(project.layout.projectDirectory.dir("src/main/appResources"))
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi)
            packageName = "Image EXIF Reset"
            packageVersion = releaseVersion.get().also { version ->
                require(version.matches(Regex("""\d+\.\d+\.\d+"""))) {
                    "RELEASE_VERSION must contain three numeric components, for example 2.15.2"
                }
            }
            windows {
                dirChooser = true
                perUserInstall = false
                shortcut = true
                menu = true
                menuGroup = "Image EXIF Reset"
                upgradeUuid = "F30D2ED7-A5EA-4208-A47F-6764EC383D78"
            }
            modules("java.desktop")
        }
    }
}
