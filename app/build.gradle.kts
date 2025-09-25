import org.gradle.internal.os.OperatingSystem
import java.nio.file.Files
import java.nio.file.Paths

plugins {
    application
    alias(libs.plugins.kotlin.jvm)
}

application {
    mainClass.set("org.kmp.jni.HelloWorld")
}

// User-configurable properties (will be plugin options later)
var settingCmakeFolder: String? = "src/main/jni" // Path to CMakeLists.txt and source
var cmakeBuildType: String = "Release"          // Or "Debug"
var jniOutputFolder: String? = "libs/native"      // Output for final libraries, can be null
var toolchainFileName: String? = null             // e.g., "my-custom-toolchain.cmake" (optional)

// --- Internal build script variables ---
val currentOs = OperatingSystem.current()
val actualJniSourceDir = settingCmakeFolder?.let { project.file(it) }
    ?: project.file("src/main/jni") // Default if null
val cmakeBuildDirRoot = project.layout.buildDirectory.dir(".cxx").get().asFile // Intermediate build folder

// OS specific identifier for build subdirectories
val osBuildIdentifier = when {
    currentOs.isWindows -> "windows"
    currentOs.isMacOsX -> "macos"
    currentOs.isLinux -> "linux"
    else -> currentOs.name.toLowerCase().replace(" ", "_")
}
val cmakeBuildDirForCurrentOS = cmakeBuildDirRoot.resolve(osBuildIdentifier) // e.g. build/.cxx/windows


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
                    val path = project.file("${cmakeSdkPath.absolutePath}/$version/bin/cmake" + if (currentOs.isWindows) ".exe" else "")
                    if (path.exists() && path.canExecute()) path else null
                }
                val cmakeFromAndroidHome = specificVersionPath ?: project.file("${latestCmakeVersionDir.absolutePath}/bin/cmake" + if (currentOs.isWindows) ".exe" else "")

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

    // Priority 2: CMAKE_HOME
    val cmakeHomeEnv = System.getenv("CMAKE_HOME")
    if (cmakeHomeEnv != null && cmakeHomeEnv.isNotBlank()) {
        val cmakeFromHome = project.file("$cmakeHomeEnv/bin/cmake" + if (currentOs.isWindows) ".exe" else "")
        if (cmakeFromHome.exists() && cmakeFromHome.canExecute()) {
            logger.lifecycle("Found CMake via CMAKE_HOME: ${cmakeFromHome.absolutePath}")
            return cmakeFromHome.absolutePath
        } else {
            logger.warn("CMAKE_HOME was set to '$cmakeHomeEnv', but cmake executable not found or not executable in its bin directory.")
        }
    }

    // Priority 3: Common system paths
    val commonPaths = mutableListOf<String>()
    val cmakeExeName = "cmake" + if (currentOs.isWindows) ".exe" else ""
    when {
        currentOs.isMacOsX -> {
            commonPaths.add("/usr/local/bin/$cmakeExeName")
            commonPaths.add("/opt/homebrew/bin/$cmakeExeName")
        }
        currentOs.isLinux -> {
            commonPaths.add("/usr/bin/$cmakeExeName")
            commonPaths.add("/usr/local/bin/$cmakeExeName")
        }
        currentOs.isWindows -> {
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

// --- Task to build the native library ---
tasks.register<Exec>("buildNativeLib") {
    description = "Builds the native library using CMake and the appropriate make tool."
    group = "build"

    val logger = project.logger
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

    if (toolchainFileName != null && toolchainFileName!!.isNotBlank()) {
        val actualToolchainFile = actualJniSourceDir.resolve("toolchains/$toolchainFileName")
        if (actualToolchainFile.exists()) {
            logger.lifecycle("Using toolchain file: ${actualToolchainFile.absolutePath}")
            cmakeArgs.add("-D CMAKE_TOOLCHAIN_FILE=${actualToolchainFile.absolutePath.replace("\\", "/")}")
        } else {
            logger.warn("Specified toolchain file '$toolchainFileName' not found at ${actualToolchainFile.absolutePath}. Build might not use it.")
        }
    }

    val javaHome = System.getenv("JAVA_HOME")
    if (javaHome != null && javaHome.isNotBlank()) {
        val jniHeader = Paths.get(javaHome, "include", "jni.h")
        if (!Files.exists(jniHeader)) {
            logger.warn("JAVA_HOME is set to '$javaHome', but jni.h not found in its include directory. CMake FindJNI might fail if not configured otherwise.")
        }
        cmakeArgs.add("-DJAVA_HOME_FOR_CMAKE=${javaHome.replace("\\", "/")}")
    } else {
        logger.warn("JAVA_HOME environment variable is not set. CMake FindJNI might fail.")
    }

    when {
        currentOs.isWindows -> {
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

            if (toolchainFileName == null) {
                mingwGcc?.let { cmakeArgs.add("-D CMAKE_C_COMPILER=${it}") }
                mingwGpp?.let { cmakeArgs.add("-D CMAKE_CXX_COMPILER=${it}") }
            }
        }
        currentOs.isMacOsX || currentOs.isLinux -> {
            cmakeGenerator = "Unix Makefiles"
            cmakeArgs.add("-G"); cmakeArgs.add(cmakeGenerator)
            makeCommand = "make"
        }
        else -> throw GradleException("Unsupported operating system for native build: ${currentOs.name}")
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
    }

    doLast {
        logger.lifecycle("CMake configuration finished. Running make command: '$makeCommand' in '${cmakeBuildDirForCurrentOS.absolutePath}'")

        // Execute Make
        project.exec {
            workingDir = cmakeBuildDirForCurrentOS
            commandLine(makeCommand.split(" "))
            standardOutput = System.out
            errorOutput = System.err
        }.assertNormalExitValue()
        logger.lifecycle("Make command finished successfully in '${cmakeBuildDirForCurrentOS.absolutePath}'.")

        // Step 1: Rename Windows DLLs (remove 'lib' prefix) IN the CMake build directory
        logger.lifecycle("Scanning for Windows DLLs to rename in build directory: ${cmakeBuildDirForCurrentOS.absolutePath}")

        cmakeBuildDirForCurrentOS.walkTopDown().forEach { fileToRename ->
            if (fileToRename.isFile && fileToRename.name.startsWith("lib") && fileToRename.name.endsWith(".dll")) {
                val newNameInSource = fileToRename.name.substring(3)
                val newFileInSource = File(fileToRename.parentFile, newNameInSource)
                if (fileToRename.renameTo(newFileInSource)) {
                } else {
                }
            }
        }

        jniOutputFolder?.let {
            project.copy {
                from(cmakeBuildDirForCurrentOS) {
                    include("*.dll", "*.so", "*.dylib")
//                    exclude()
                }

                into( project.file(it))
            }
        }
    }
}

// Task Dependencies
tasks.jar {
    dependsOn("buildNativeLib")
    jniOutputFolder?.let { outputDir ->
        val nativeLibsDir = project.file(outputDir)
        if (nativeLibsDir.exists() && nativeLibsDir.isDirectory) {
            from(nativeLibsDir) {
                // into("native") // Optional: place in a subfolder within the JAR
                logger.lifecycle("Adding native libraries from $nativeLibsDir to JAR.")
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
    dependsOn("buildNativeLib")
    val finalLibsPath = jniOutputFolder?.let { project.file(it).absolutePath }
        ?: cmakeBuildDirForCurrentOS.absolutePath
    systemProperty("java.library.path", finalLibsPath)
    doFirst {
        logger.lifecycle("Running tests with java.library.path=$finalLibsPath")
        if (!project.file(finalLibsPath).exists()) {
            logger.warn("Native library path for tests does not exist: $finalLibsPath")
        }
    }
}

tasks.withType<JavaExec>().configureEach {
    if (name == "run") {
        dependsOn("buildNativeLib")
        val finalLibsPath = jniOutputFolder?.let { project.file(it).absolutePath }
            ?: cmakeBuildDirForCurrentOS.absolutePath
        jvmArgs("-Djava.library.path=${finalLibsPath}")
        doFirst {
            logger.lifecycle("Configuring 'run' task with java.library.path=$finalLibsPath")
        }
    }
}

dependencies {
    testImplementation(kotlin("test"))
}
