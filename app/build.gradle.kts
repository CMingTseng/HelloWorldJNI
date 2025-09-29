import org.gradle.internal.os.OperatingSystem

plugins {
    application
    alias(libs.plugins.kotlin.jvm)
}

application {
    mainClass.set("org.kmp.jni.HelloWorld") // Set your main class [5]
}

var settingCmakeFolder = "src/main/jni" // 可修改為 "src/main/cpp" 等
var targetJdkVersion: String = JavaVersion.VERSION_17.toString()

val cmakeBuildDirRoot =
    project.layout.buildDirectory.dir(".cxx").get().asFile

var jniOutputFolder: String? = "native" // 可修改為 "libs" 等

val currentHostOs = OperatingSystem.current()
val hostOsIdentifier = currentHostOs.run {
    when {
        isWindows -> "windows"
        isMacOsX -> "macos"
        isLinux -> "linux"
        else -> name.lowercase().replace(" ", "_") // Use lowercase() as per deprecation warning
    }
}

val osBuildIdentifier = when {
    currentHostOs.isWindows -> "windows"
    currentHostOs.isMacOsX -> "macos"
    currentHostOs.isLinux -> "linux"
    else -> currentHostOs.name.toLowerCase().replace(" ", "_")
}

// Custom task to build the native library using CMake
tasks.register<Exec>("buildNativeLib") {
    description = "Builds the native library using CMake and MinGW."
    group = "build"
    val logger = project.logger
    println("Show me targetJdkVersion $targetJdkVersion")
    // 使用配置的變數
    val actualJniSourceDir = project.file(settingCmakeFolder)

    workingDir = actualJniSourceDir // CMake 命令的 workingDir

    commandLine(
        "cmake",
        "-S", ".", // Source directory (相對於 workingDir)
        "-B",cmakeBuildDirRoot.relativeTo(actualJniSourceDir).path, // Build directory (相對於 workingDir)
        "-G", "MinGW Makefiles",
        // 確保這些 MinGW 路徑是正確的，或者也將它們提取為變數
//        "-D", "CMAKE_C_COMPILER=D:/MinGW/bin/x86_64-w64-mingw32-gcc.exe",
//        "-D", "CMAKE_CXX_COMPILER=D:/MinGW/bin/x86_64-w64-mingw32-g++.exe",
//        "-D", "CMAKE_MAKE_PROGRAM=D:/MinGW/bin/mingw32-make.exe"
    )

    doLast {
        exec {
            workingDir = cmakeBuildDirRoot // make 命令的 workingDir
            commandLine("mingw32-make")
        }

        println("== File Operations in doLast (Using Configurable Paths) ==")
        println("Source Directory (CMake build output): ${cmakeBuildDirRoot.absolutePath}")
        // 需求 2: 在來源位置 (actualCmakeBuildDir) 將 lib*.dll 更名
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