import java.util.zip.ZipFile
import java.io.File
import java.util.UUID
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
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
            // Windows MSI is packaged below so Compose cannot clear its custom WiX template.
            targetFormats(TargetFormat.Dmg)
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

// Preserve the underlying jpackage error in CI diagnostics.
tasks.withType<AbstractJPackageTask>().configureEach {
    freeArgs.add("--verbose")
}

// Compose clears --resource-dir during preparation, so package the completed Windows
// app image directly with jpackage. Keep both standard MSI task names available.
afterEvaluate {
    if (System.getProperty("os.name").startsWith("Windows")) {
        for (classifier in listOf("", "Release")) {
            val imageTask = tasks.named<AbstractJPackageTask>("create${classifier}Distributable")
            imageTask.configure { dependsOn(stageBundledResources) }
            val imageDirectory = imageTask.flatMap { it.destinationDir }.map { it.dir("Image EXIF Reset") }
            val jdkHome = imageTask.flatMap { it.javaHome }
            val buildType = if (classifier.isEmpty()) "main" else "release"
            val outputDirectory = layout.buildDirectory.dir("compose/binaries/$buildType/msi").get().asFile
            val wixResources = layout.buildDirectory.dir("windows-wix-resources/$buildType").get().asFile
            val tempBase = providers.gradleProperty("windowsJpackageTempDir")
                .orElse(providers.environmentVariable("RUNNER_TEMP"))
                .orElse(providers.systemProperty("java.io.tmpdir"))
            val packagingTemp = File(tempBase.get(), "jp-${UUID.randomUUID().toString().take(8)}")
            val version = releaseVersion.get()
            val msi = tasks.register<Exec>("package${classifier}Msi") {
                group = "compose desktop"
                description = "Package the Windows app with external CUDA cabinets"
                dependsOn(imageTask)
                inputs.dir(imageDirectory)
                inputs.property("jdkHome", jdkHome)
                inputs.property("packageVersion", version)
                outputs.dir(outputDirectory)
                doFirst {
                    val template = ZipFile(File(jdkHome.get(), "jmods/jdk.jpackage.jmod")).use { jmod ->
                        val entry = checkNotNull(jmod.getEntry("classes/jdk/jpackage/internal/resources/main.wxs")) {
                            "The selected JDK does not provide the WiX 3 MSI template"
                        }
                        jmod.getInputStream(entry).bufferedReader().use { it.readText() }
                    }
                    val singleCabinet = """<Media Id="1" Cabinet="Data.cab" EmbedCab="yes" />"""
                    check(template.contains(singleCabinet)) { "Unexpected JDK MSI cabinet template" }
                    wixResources.mkdirs()
                    File(wixResources, "main.wxs").writeText(template.replace(
                        singleCabinet,
                        """<MediaTemplate CabinetTemplate="image-exif-data{0}.cab" EmbedCab="no" MaximumUncompressedMediaSize="512" CompressionLevel="medium" />""",
                    ))
                    packagingTemp.deleteRecursively()
                    check(packagingTemp.mkdirs()) { "Cannot create jpackage directory: $packagingTemp" }
                    outputDirectory.deleteRecursively()
                    outputDirectory.mkdirs()
                    commandLine(
                        File(jdkHome.get(), "bin/jpackage.exe").absolutePath,
                        "--verbose", "--type", "msi", "--name", "Image EXIF Reset",
                        "--app-version", version,
                        "--app-image", imageDirectory.get().asFile.absolutePath,
                        "--dest", outputDirectory.absolutePath,
                        "--resource-dir", wixResources.absolutePath,
                        "--temp", packagingTemp.absolutePath,
                        "--win-dir-chooser", "--win-shortcut", "--win-menu",
                        "--win-menu-group", "Image EXIF Reset",
                        "--win-upgrade-uuid", "F30D2ED7-A5EA-4208-A47F-6764EC383D78",
                    )
                }
                doLast { packagingTemp.deleteRecursively() }
            }
            tasks.named("package${classifier}DistributionForCurrentOS") { dependsOn(msi) }
            if (classifier.isEmpty()) tasks.named("package") { dependsOn(msi) }
        }
    }
}
