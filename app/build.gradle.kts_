import org.gradle.internal.os.OperatingSystem
import de.undercouch.gradle.tasks.download.Download
import java.nio.file.Files
import java.nio.file.Paths

plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    id("de.undercouch.download") version "5.6.0"
}

application {
    mainClass.set("org.kmp.jni.HelloWorld") // Set your main class [5]
}

var settingCmakeFolder : String? = "src/main/jni" // 可修改為 "src/main/cpp" 等
var targetJdkVersion: String = JavaVersion.VERSION_17.toString()
var settingJDKUrls = mapOf(
    "windows" to "https://github.com/adoptium/temurin${targetJdkVersion}-binaries/releases/download/jdk-${targetJdkVersion}.0.11%2B9/OpenJDK${targetJdkVersion}U-jdk_x64_windows_hotspot_${targetJdkVersion}.0.11_9.zip",
    "linux" to "https://github.com/adoptium/temurin${targetJdkVersion}-binaries/releases/download/jdk-${targetJdkVersion}.0.11%2B9/OpenJDK${targetJdkVersion}U-jdk_x64_linux_hotspot_${targetJdkVersion}.0.11_9.tar.gz",
    "macos" to "https://github.com/adoptium/temurin${targetJdkVersion}-binaries/releases/download/jdk-${targetJdkVersion}.0.11%2B9/OpenJDK${targetJdkVersion}U-jdk_aarch64_mac_hotspot_${targetJdkVersion}.0.11_9.tar.gz"
)
var jniOutputFolder: String? = "native" // 可修改為 "libs" 等
var cmakeBuildType: String = "Release"

// --- Internal build script variables ---

val currentHostOs = OperatingSystem.current()
val actualJniSourceDir = settingCmakeFolder?.let { project.file(it) }
    ?: project.file("src/main/jni")
val jdkUrls =settingJDKUrls
val cmakeBuildDirRoot =
    project.layout.buildDirectory.dir(".cxx").get().asFile

val cmakeBuildDirForCurrentOS = cmakeBuildDirRoot//

val hostOsIdentifier = currentHostOs.run {
    when {
        isWindows -> "windows"
        isMacOsX -> "macos"
        isLinux -> "linux"
        else -> name.lowercase().replace(" ", "_") // Use lowercase() as per deprecation warning
    }
}

val targetOsForJni: String = hostOsIdentifier

val currentJdkDownloadUrl: String? = jdkUrls[targetOsForJni]
val jdkDownloadRootBaseDir =
    project.layout.buildDirectory.dir("JDK${targetJdkVersion}").get().asFile
val targetJdkZipDestinationDir = jdkDownloadRootBaseDir.resolve("download")
val archiveExtension =
    currentJdkDownloadUrl?.substringAfterLast('.') ?: "archive"
val targetJdkArchiveFile =
    targetJdkZipDestinationDir.resolve("${targetOsForJni}.${archiveExtension}")
val targetJdkExtractBaseDir = jdkDownloadRootBaseDir.resolve("define/${targetOsForJni}")

val osBuildIdentifier = when {
    currentHostOs.isWindows -> "windows"
    currentHostOs.isMacOsX -> "macos"
    currentHostOs.isLinux -> "linux"
    else -> currentHostOs.name.toLowerCase().replace(" ", "_")
}

// --- CMake Executable Finder ---
fun findCMakeExecutable(project: Project): String {
    val logger = project.logger
    // Priority 1: ANDROID_HOME
    val androidHome = System.getenv("ANDROID_HOME")
    if (androidHome != null && androidHome.isNotBlank()) {
        val cmakeSdkPath = project.file("$androidHome/cmake")
        if (cmakeSdkPath.exists() && cmakeSdkPath.isDirectory) {
            val latestCmakeVersionDir = cmakeSdkPath.listFiles()
                ?.filter { it.isDirectory && it.name.matches(Regex("\\d+\\.\\d+\\.\\d+")) }
                ?.maxByOrNull { it.name }
            if (latestCmakeVersionDir != null) {
                val standardCMakeVersions = listOf("3.22.1", "3.18.1", "3.10.2", "3.6.0")
                val specificVersionPath = standardCMakeVersions.firstNotNullOfOrNull { version ->
                    val path = project.file("${cmakeSdkPath.absolutePath}/$version/bin/cmake" + if (currentHostOs.isWindows) ".exe" else "")
                    if (path.exists() && path.canExecute()) path else null
                }
                val cmakeFromAndroidHome = specificVersionPath ?: project.file("${latestCmakeVersionDir.absolutePath}/bin/cmake" + if (currentHostOs.isWindows) ".exe" else "")

                if (cmakeFromAndroidHome.exists() && cmakeFromAndroidHome.canExecute()) {
                    logger.lifecycle("Found CMake via ANDROID_HOME: ${cmakeFromAndroidHome.absolutePath}")
                    return cmakeFromAndroidHome.absolutePath
                } else {
                    logger.warn("ANDROID_HOME was set, CMake directory found, but cmake executable not found or not executable (tried common versions and ${latestCmakeVersionDir.name}).")
                }
            } else {
                logger.warn("ANDROID_HOME was set, CMake directory found ($cmakeSdkPath), but no versioned CMake subdirectory found.")
            }
        }
    }

    val cmakeHomeEnv = System.getenv("CMAKE_HOME")
    if (cmakeHomeEnv != null && cmakeHomeEnv.isNotBlank()) {
        val cmakeFromHome = project.file("$cmakeHomeEnv/bin/cmake" + if (currentHostOs.isWindows) ".exe" else "")
        if (cmakeFromHome.exists() && cmakeFromHome.canExecute()) {
            logger.lifecycle("Found CMake via CMAKE_HOME: ${cmakeFromHome.absolutePath}")
            return cmakeFromHome.absolutePath
        } else {
            logger.warn("CMAKE_HOME was set to '$cmakeHomeEnv', but cmake executable not found or not executable in its bin directory.")
        }
    }

    val commonPaths = mutableListOf<String>()
    val cmakeExeName = "cmake" + if (currentHostOs.isWindows) ".exe" else ""
    when {
        currentHostOs.isMacOsX -> {
            commonPaths.add("/usr/local/bin/$cmakeExeName")
            commonPaths.add("/opt/homebrew/bin/$cmakeExeName")
        }
        currentHostOs.isLinux -> {
            commonPaths.add("/usr/bin/$cmakeExeName")
            commonPaths.add("/usr/local/bin/$cmakeExeName")
        }
        currentHostOs.isWindows -> {
            System.getenv("ProgramFiles")?.let { commonPaths.add("$it/CMake/bin/$cmakeExeName") }
            System.getenv("ProgramFiles(x86)")?.let { commonPaths.add("$it/CMake/bin/$cmakeExeName") }
        }
    }
    for (path in commonPaths) {
        val cmakeFile = project.file(path)
        if (cmakeFile.exists() && cmakeFile.canExecute()) {
            logger.lifecycle("Found CMake in common system path: ${cmakeFile.absolutePath}")
            return cmakeFile.absolutePath
        }
    }

    logger.lifecycle("CMake not found via ANDROID_HOME, CMAKE_HOME, or common OS paths. Attempting to use '$cmakeExeName' from PATH.")
    return cmakeExeName // Fallback to PATH
}

tasks.register<Download>("downloadTargetOsJdkArchive") { // Renamed task
    description =
        "Downloads and extracts the JDK archive for $targetOsForJni (version $targetJdkVersion) if needed for cross-compilation."
    group = "build setup"

    // Condition to run:
    // 1. Host OS is different from Target OS (classic cross-compile scenario)
    //    OR always run if hardcoding for test (remove `hostOsIdentifier != targetOsForJni &&` for unconditional test run)
    // 2. A valid URL exists for the target OS
    // (Future: Could also check if a specific toolchain file for this cross-compile pair exists)
    onlyIf {
        // For now, let's assume we always want to try if URL is present,
        // and host is different, or if we are specifically testing a target.
        // For actual cross-compilation, you'd refine this.
        // During hardcoded testing of targetOsForJni, we might want it to always run if URL exists.
        val shouldRun = currentJdkDownloadUrl != null
        shouldRun
    }

    src(provider {
        currentJdkDownloadUrl
            ?: throw GradleException("No JDK download URL defined for target OS: $targetOsForJni")
    })
    dest(targetJdkArchiveFile)
    overwrite(false)

    doFirst {
        project.logger.lifecycle("[downloadTargetOsJdkArchive.doFirst] Preparing to download JDK for $targetOsForJni (v$targetJdkVersion).")
        project.logger.lifecycle("Download URL: $currentJdkDownloadUrl")
        project.logger.lifecycle("Destination Archive: ${targetJdkArchiveFile.absolutePath}")
        targetJdkZipDestinationDir.mkdirs()
    }

    doLast {
        if (!outputs.files.singleFile.exists()) {
            project.logger.warn("[downloadTargetOsJdkArchive.doLast] JDK for $targetOsForJni download seems to have failed or was skipped. File not found. Cannot extract.")
            return@doLast
        }
        project.logger.lifecycle("[downloadTargetOsJdkArchive.doLast] Download JDK for $targetOsForJni (v$targetJdkVersion) to ${targetJdkArchiveFile.absolutePath} finish.")

        if (targetJdkExtractBaseDir.exists()) {
            targetJdkExtractBaseDir.deleteRecursively()
        }
        val targetIncludeDir = targetJdkExtractBaseDir.resolve("include")
        targetIncludeDir.mkdirs()

        project.logger.lifecycle("[downloadTargetOsJdkArchive.doLast] Extracting 'include' folder from ${targetJdkArchiveFile.absolutePath} to ${targetIncludeDir.absolutePath}")

        project.copy {
            val archiveFile = targetJdkArchiveFile // Closure capture
            from(
                if (archiveFile.name.endsWith(".zip")) {
                    zipTree(archiveFile)
                } else if (archiveFile.name.endsWith(".tar.gz")) {
                    tarTree(resources.gzip(archiveFile)) // tarTree for .tar.gz
                } else if (archiveFile.name.endsWith(".gz")) {
                    tarTree(resources.gzip(archiveFile)) // tarTree for .tar.gz
                } else {
                    throw GradleException("Unsupported archive format for JDK: ${archiveFile.name}")
                }
            )
            include("**/include/**") // Common pattern for JDKs
            exclude("**/include/demo/**")

            eachFile(object : org.gradle.api.Action<org.gradle.api.file.FileCopyDetails> {
                override fun execute(details: org.gradle.api.file.FileCopyDetails) {
                    val pathInArchive = details.relativePath.segments.joinToString("/")
                    val includeKeyword = "include/"
                    var relativePathToSet = pathInArchive
                    val indexOfInclude =
                        pathInArchive.lastIndexOf(includeKeyword) // Use lastIndexOf in case 'include' appears in top-level folder name

                    if (indexOfInclude != -1) {
                        relativePathToSet =
                            pathInArchive.substring(indexOfInclude + includeKeyword.length)
                    } else {
                        details.exclude()
                        return
                    }
                    if (relativePathToSet.startsWith("/")) {
                        relativePathToSet = relativePathToSet.substring(1)
                    }

                    if (relativePathToSet.isNotEmpty()) {
                        details.relativePath =
                            RelativePath(true, *relativePathToSet.split("/").toTypedArray())
                    } else {
                        details.exclude()
                    }
                }
            })
            into(targetIncludeDir)
            includeEmptyDirs = false
        }
//        project.copy {
//            val archiveFile = targetJdkArchiveFile; from( if (archiveFile.name.endsWith(".zip")) zipTree(archiveFile) else if (archiveFile.name.endsWith(".tar.gz")) tarTree(resources.gzip(archiveFile)) else throw GradleException("Unsupported archive format for JDK: ${archiveFile.name}") ); include("**/include/**"); exclude("**/include/demo/**");
//            eachFile(object : org.gradle.api.Action<org.gradle.api.file.FileCopyDetails> {
//                override fun execute(details: org.gradle.api.file.FileCopyDetails) {
//                    val pathInArchive = details.relativePath.segments.joinToString("/"); val includeKeyword = "include/"; val indexOfInclude = pathInArchive.lastIndexOf(includeKeyword);
//                    if (indexOfInclude != -1) { var relativePathToSet = pathInArchive.substring(indexOfInclude + includeKeyword.length); if (relativePathToSet.startsWith("/")) { relativePathToSet = relativePathToSet.substring(1) }; if (relativePathToSet.isNotEmpty()) { details.relativePath = org.gradle.api.file.RelativePath(true, *relativePathToSet.split("/").toTypedArray()) } else { details.exclude() }
//                    } else { details.exclude(); return }
//                }
//            }); into(targetIncludeDir); includeEmptyDirs = false
//        }

        project.logger.lifecycle("[downloadTargetOsJdkArchive.doLast] Extraction to ${targetIncludeDir.absolutePath} complete.")
        if (targetIncludeDir.resolve("jni.h").exists()) {
            project.logger.lifecycle("[downloadTargetOsJdkArchive.doLast] Extraction verified for $targetOsForJni. Found jni.h.")
        } else {
            project.logger.warn("[downloadTargetOsJdkArchive.doLast] Post-extraction for $targetOsForJni verification failed to find jni.h in ${targetIncludeDir.absolutePath}.")
        }
    }
}

// Custom task to build the native library using CMake
tasks.register<Exec>("buildNativeLib") {
    description = "Builds the native library using CMake and MinGW."
    group = "build"
    val logger = project.logger
    dependsOn("downloadTargetOsJdkArchive")
    val cmakeExecutable = findCMakeExecutable(project)
    var makeCommand: String
    val cmakeGenerator: String
    var mingwGcc: String? = null
    var mingwGpp: String? = null

    // Configure CMake arguments and Make command based on OS
    val cmakeArgs = mutableListOf(
        cmakeExecutable,
        "-S", actualJniSourceDir.absolutePath,
        "-B", cmakeBuildDirForCurrentOS.absolutePath,
        "-D", "CMAKE_BUILD_TYPE=$cmakeBuildType"
    )

    val gradleManagedJniIncludeDir = targetJdkExtractBaseDir.resolve("include")
    val platformSpecificIncludeSubDir = when (targetOsForJni) {
        "linux" -> "linux"
        "windows" -> "win32"
        "macos" -> "darwin"
        else -> {
            logger.warn("Unknown target OS '$targetOsForJni' for determining jni_md.h subdirectory. Using target name directly.")
            targetOsForJni
        }
    }
    val gradleManagedJniMdIncludeDir = gradleManagedJniIncludeDir.resolve(platformSpecificIncludeSubDir)
    cmakeArgs.add("-DGRADLE_MANAGED_JNI_INCLUDE_DIR=${gradleManagedJniIncludeDir.absolutePath.replace("\\", "/")}")
    cmakeArgs.add("-DGRADLE_MANAGED_JNI_MD_INCLUDE_DIR=${gradleManagedJniMdIncludeDir.absolutePath.replace("\\", "/")}")
    logger.lifecycle("[Build Config] Adding to cmakeArgs: -DGRADLE_MANAGED_JNI_INCLUDE_DIR='${gradleManagedJniIncludeDir.absolutePath.replace("\\", "/")}'")
    logger.lifecycle("[Build Config] Adding to cmakeArgs: -DGRADLE_MANAGED_JNI_MD_INCLUDE_DIR='${gradleManagedJniMdIncludeDir.absolutePath.replace("\\", "/")}'")


    when {
        currentHostOs.isWindows -> {
            cmakeGenerator = "MinGW Makefiles"
            cmakeArgs.add("-G"); cmakeArgs.add(cmakeGenerator)
            makeCommand = "mingw32-make.exe"

            val mingwHome = System.getenv("MinGW_HOME")
            if (mingwHome != null && mingwHome.isNotBlank()) {
                logger.lifecycle("Found MinGW_HOME: $mingwHome")
                val mingwBin = Paths.get(mingwHome, "bin")
                val mingwMakePath = mingwBin.resolve("mingw32-make.exe")
                if (Files.exists(mingwMakePath) && Files.isExecutable(mingwMakePath)) {
                    makeCommand = mingwMakePath.toString().replace("\\","/")
                } else {
                    logger.warn("mingw32-make.exe not found or not executable in MinGW_HOME/bin. Using default '$makeCommand'.")
                }
                val gccPath = mingwBin.resolve("gcc.exe")
                val gppPath = mingwBin.resolve("g++.exe")
                if (Files.exists(gccPath)) mingwGcc = gccPath.toString().replace("\\","/")
                if (Files.exists(gppPath)) mingwGpp = gppPath.toString().replace("\\","/")
            } else {
                logger.warn("MinGW_HOME not set. Trying to use '$makeCommand' from PATH.")
            }
        }
        currentHostOs.isMacOsX ||  currentHostOs.isLinux -> {
            cmakeGenerator = "Unix Makefiles"
            cmakeArgs.add("-G"); cmakeArgs.add(cmakeGenerator)
            makeCommand = "make"
        }
        else -> throw GradleException("Unsupported operating system for native build: ${ currentHostOs.name}")
    }

    // Configure Task
    workingDir = project.projectDir
    commandLine(cmakeArgs)

    doFirst {
        if (!actualJniSourceDir.exists() || !actualJniSourceDir.isDirectory) {
            throw GradleException("JNI source directory does not exist or is not a directory: ${actualJniSourceDir.absolutePath}")
        }
        cmakeBuildDirForCurrentOS.mkdirs()
        logger.lifecycle("Configuring native library with CMake. Executable: $cmakeExecutable")
        logger.lifecycle("CMake arguments: ${commandLine.joinToString(" ")}")
        cmakeArgs.forEach { logger.lifecycle("  $it") } // 打印最終的 cmakeArgs
        commandLine(cmakeArgs.first()) // cmakeExecutable
        args(cmakeArgs.drop(1))       // cmakeExecutable 之後的參數
        standardOutput = System.out
        errorOutput = System.err
    }

    doLast {
        exec {
            workingDir = cmakeBuildDirRoot // make 命令的 workingDir
            commandLine("mingw32-make")
        }

        println("== File Operations in doLast (Using Configurable Paths) ==")
        println("Source Directory (CMake build output): ${cmakeBuildDirRoot.absolutePath}")
        println("Renaming lib*.dll files in source directory (${cmakeBuildDirRoot.absolutePath}):")
        cmakeBuildDirRoot.walkTopDown().forEach { fileToRename ->
            if (fileToRename.isFile && fileToRename.name.startsWith("lib") && fileToRename.name.endsWith(".dll")) {
                val newNameInSource = fileToRename.name.substring(3)
                val newFileInSource = File(fileToRename.parentFile, newNameInSource)
                print("  Attempting to rename in source: ${fileToRename.name} to $newNameInSource ... ")
                if (fileToRename.renameTo(newFileInSource)) {
                    println("SUCCESS")
                } else {
                    println("FAILED (File may be locked or path issue)")
                }
            }
        }

        jniOutputFolder?.let{outputDirString ->
            val targetCopyDir = project.file(outputDirString)
            println("Target Directory (Final native libs): ${targetCopyDir.absolutePath}")
            targetCopyDir.mkdirs()
            logger.lifecycle("[buildNativeLib.doLast.copy] Attempting to collect ALL native libraries from subdirectories of ${cmakeBuildDirRoot.absolutePath} into ${targetCopyDir.absolutePath}")

            var anyFilesCopiedOverall = false

            try {
                project.copy {
                    from(project.fileTree(cmakeBuildDirRoot) {
                        include(
                            "**/*.dll",
                            "**/*.so",
                            "**/*.dylib"
                        )
                        exclude(
                            "**/CMakeFiles/**", "**/CMakeCache.txt", "**/cmake_install.cmake",
                            "**/Makefile", "**/*.cmake", "**/*.o", "**/*.obj", "**/JDK*/**"
                        )
                    })
                    into(targetCopyDir)

                    eachFile(object : org.gradle.api.Action<org.gradle.api.file.FileCopyDetails> {
                        override fun execute(details: org.gradle.api.file.FileCopyDetails) {
                            val originalSourcePath = details.sourcePath // For logging
                            val originalRelativePath = details.relativePath // For logging
                            val fileNameOnly = details.relativePath.lastName // Get just the file name as a String

                            logger.info("[buildNativeLib.doLast.copy.eachFile] Original: source='${originalSourcePath}', relativeInTree='${originalRelativePath}'")

                            // Create a new RelativePath object that only contains the file name.
                            // The 'true' argument indicates that 'fileNameOnly' is a file, not a directory.
                            details.relativePath = org.gradle.api.file.RelativePath(true, fileNameOnly)

                            logger.info("[buildNativeLib.doLast.copy.eachFile] Copying '${details.name}' to '$outputDirString' as flattened path: '${details.relativePath.pathString}'")
                            anyFilesCopiedOverall = true
                        }
                    })
                }

                if (anyFilesCopiedOverall) {
                    logger.lifecycle("[buildNativeLib.doLast.copy] Native library collection to '$outputDirString' complete.")
                    val finalCopiedFiles = targetCopyDir.listFiles { file ->
                        file.isFile && (file.name.endsWith(".dll") || file.name.endsWith(".so") || file.name.endsWith(".dylib"))
                    }
                    logger.lifecycle("Files found in '$outputDirString': ${finalCopiedFiles?.joinToString { it.name } ?: "None"}")
                } else {
                    logger.warn("No native libraries matching patterns were found under '${cmakeBuildDirRoot.absolutePath}' to copy to '$outputDirString'.")
                }

            } catch (e: Exception) {
                logger.error("[buildNativeLib.doLast.copy] FAILED during collection of native libraries. Error: ${e.message}", e)
            }
        }

        println("===================================")
    }
}

// Make the 'jar' task depend on 'buildNativeLib'
tasks.jar {
    dependsOn("buildNativeLib")
    // Optionally, configure the JAR manifest to include java.library.path
    // or instruct users to set it when running.
    // For simplicity, we'll assume the DLL is in a known location relative to the JAR
    // or the user sets java.library.path.
}

tasks.test {
    useJUnitPlatform()
    // Ensure the native library is available when running tests
    dependsOn("buildNativeLib")
    systemProperty("java.library.path", cmakeBuildDirRoot.absolutePath)
}

// Ensure native library is built before running the application
//當你寫 tasks.run { ... } 時，如果沒有足夠的上下文提示，Kotlin DSL 的類型推斷系統可能無法確定 run 是一個 JavaExec 類型的任務。因此，它不會識別 JavaExec 特有的 dependsOn 方法（儘管 dependsOn 是一個非常通用的任務方法，但有時類型推斷的缺失會導致解析問題，特別是在更複雜的閉包結構中）和 systemProperty 方法。通過使用 tasks.named<JavaExec>("run")，你明確地告訴 Gradle：「我要配置一個名為 'run' 的任務，並且我知道它的類型是 JavaExec。」這樣，IDE 和 Gradle 就能正確解析該類型擁有的所有屬性和方法。
//tasks.run {//FAIL
tasks.named<JavaExec>("run") {
    dependsOn("buildNativeLib")
    systemProperty("java.library.path", cmakeBuildDirRoot.absolutePath)
}

dependencies {
    testImplementation(kotlin("test"))
}