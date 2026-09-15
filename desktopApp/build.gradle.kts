import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

val releaseVersion = providers.environmentVariable("RELEASE_VERSION").orElse("1.0.0")

// Merge generated Python binaries with existing platform resources for packaging.
val stageBundledResources by tasks.registering(Sync::class) {
    dirPermissions { unix("755") }
    filePermissions { unix("755") }
    from("src/main/appResources")
    from(layout.buildDirectory.dir("synthid")) {
        exclude("worker.py", "models.json", "THIRD_PARTY.md")
        into("common/synthid")
    }
    from(rootProject.layout.projectDirectory.dir("synthid")) {
        include("worker.py", "models.json", "THIRD_PARTY.md")
        into("common/synthid")
    }
    into(layout.buildDirectory.dir("packagedResources"))
}

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
            appResourcesRootDir.set(layout.buildDirectory.dir("packagedResources"))
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

tasks.matching { it.name == "prepareAppResources" }.configureEach {
    dependsOn(stageBundledResources)
}
tasks.matching { it.name in listOf("run", "createDistributable", "packageDmg", "packageMsi") }.configureEach {
    dependsOn(stageBundledResources)
}
tasks.matching { it.name in listOf("createDistributable", "packageDmg", "packageMsi") }.configureEach {
    inputs.dir(layout.buildDirectory.dir("packagedResources"))
}

// jpackage copies application resources without retaining executable bits.
val packagedPythonBin = layout.buildDirectory.dir(
    "compose/binaries/main/app/Image EXIF Reset.app/Contents/app/resources/synthid/python/bin",
)
tasks.matching { it.name == "createDistributable" }.configureEach {
    val pythonBin = packagedPythonBin.get().asFile
    doLast {
        pythonBin.listFiles()
            ?.filter { it.name.startsWith("python") }
            ?.forEach { check(it.setExecutable(true, false)) { "Cannot make bundled Python executable: $it" } }
    }
}
