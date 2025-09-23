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

var settingCmakeFolder  :String? = "src/main/jni" //Manually like Android externalNativeBuild cmake path
var camke_version = "3.22.1"//Manually like Android externalNativeBuild cmake version
var mingwMakePath :String? =  "D:/MinGW/bin/mingw32-make.exe"
var jniOutputFolder :String? =  "libs" //Manually
val currentOs = OperatingSystem.current()
val osIdentifier = when {
    currentOs.isWindows -> "win"
    currentOs.isMacOsX -> "osx"
    currentOs.isLinux -> "linux"
    // Add other OS families if needed, or a default
    else -> currentOs.name.toLowerCase().replace(" ", "_")
}
tasks.register<Exec>("buildNativeLib") {
    description = "Builds the native library using CMake"
    group = "build"
    val javaHome = System.getenv("JAVA_HOME")
    if (javaHome.isNullOrBlank()) {
        throw GradleException("JAVA_HOME environment variable is not set. Please set it to your JDK installation directory.")
    }
    println("Using JAVA_HOME: $javaHome")
    println("Check JNI header existence like CMakeLists \"find_package(JNI)\"")
    val jniHeaderPath = Paths.get(javaHome, "include", "jni.h")
    if (!Files.exists(jniHeaderPath)) {
        val platformJniHeaderPath = Paths.get(
            javaHome,
            "include",
            when {
                currentOs.isWindows -> "win32"
                currentOs.isMacOsX -> "darwin"
                currentOs.isLinux -> "linux"
                else -> ""
            },
            "jni_md.h"
        )
        if (!Files.exists(jniHeaderPath) || (!platformJniHeaderPath.fileName.toString().isBlank() && !Files.exists(platformJniHeaderPath))) {
            logger.warn(
                """
                Warning: Basic JNI header check failed for JAVA_HOME '$javaHome'.
                Expected 'include/jni.h' and platform-specific 'include/.../jni_md.h' to exist.
                Compilation might fail if CMake cannot find JNI headers.
                Ensure JAVA_HOME points to a full JDK installation.
                """.trimIndent()
            )
        }
    }
    val androidHome = System.getenv("ANDROID_HOME")
    val cmakeHome = System.getenv("CMAKE_HOME")
    var cmakeExecutablePath =   androidHome?.let { Paths.get(it, "cmake", camke_version, "bin", "cmake").toString() }
            .takeIf { it != null && Files.exists(Paths.get(it)) }
            ?: cmakeHome?.let { Paths.get(it, "bin", "cmake").toString() }
                .takeIf { it != null && Files.exists(Paths.get(it)) }
            ?: "cmake" // Fallback to system path

    if (!Files.exists(Paths.get(cmakeExecutablePath)) && cmakeExecutablePath == "cmake") {
        println("Warning: CMake executable not found via ANDROID_HOME or CMAKE_HOME. Falling back to system 'cmake'. Ensure it's installed and in PATH.")
    } else {
        println("Using CMake from: $cmakeExecutablePath")
    }
    val cmakeGenerator: String
    var makeCommand: String
    val actualJniSourceDir=settingCmakeFolder?.let { project.file(it)  }?:project.file("src/main/jni")
    if (!actualJniSourceDir.exists() || !actualJniSourceDir.isDirectory) {
        throw GradleException("JNI source directory does not exist or is not a directory: ${actualJniSourceDir.absolutePath}")
    }
    println("Using JNI source directory: ${actualJniSourceDir.absolutePath}")
    val cmakeBuildFolder = project.file("${project.buildDir}/.cxx/$osIdentifier")
    println("Using CMake build directory (absolute): ${cmakeBuildFolder.absolutePath}")

    val cmakeArgs = mutableListOf<String>()
    cmakeArgs.add("-S")
    cmakeArgs.add(actualJniSourceDir.absolutePath)
    cmakeArgs.add("-B")
    cmakeArgs.add(cmakeBuildFolder.absolutePath)
    if (!javaHome.isNullOrBlank()) {
        cmakeArgs.add("-DJAVA_HOME_FOR_CMAKE=${javaHome.replace("\\", "/")}")
    }
    if (currentOs.isWindows) {
        var mingwgcc: String?=null
        var mingwgccplusplus: String?=null
        val mingwHomeEnv = System.getenv("MinGW_HOME")
        if (!mingwHomeEnv.isNullOrBlank()) {
            println("Using MinGW_HOME: $mingwHomeEnv")
            val mingwBinPath = Paths.get(mingwHomeEnv, "bin")
            mingwMakePath = Paths.get(mingwBinPath.toString(), "mingw32-make.exe").toString()
            mingwgcc = Paths.get(mingwBinPath.toString(), "x86_64-w64-mingw32-gcc.exe").toString() // Or your specific gcc like x86_64-w64-mingw32-gcc.exe
            mingwgccplusplus = Paths.get(mingwBinPath.toString(), "x86_64-w64-mingw32-g++.exe").toString() // Or your specific g++
        }
        if (mingwHomeEnv.isNullOrBlank() && mingwMakePath.isNullOrBlank()) {
            throw GradleException("MinGW make command path not found or invalid. Windows must install MinGW and then set environment variable MinGW_HOME . You can go to MSYS2 ( https://www.msys2.org/ ) or use Standalone MinGW-w64 ( https://winlibs.com/ )")
        }
        makeCommand = mingwMakePath!!
        val cmakeGenerator = "MinGW Makefiles"
        cmakeArgs.add("-G")
        cmakeArgs.add(cmakeGenerator)

        if (mingwgcc != null && Files.exists(Paths.get(mingwgcc))) {
             cmakeArgs.add("-DCMAKE_C_COMPILER=${mingwgcc.replace("\\", "/")}")
        } else {
            println("Warning: MinGW C compiler (mingwgcc variable: '$mingwgcc') not found or not specified correctly.")
        }

        if (mingwgccplusplus != null && Files.exists(Paths.get(mingwgccplusplus))) {
             cmakeArgs.add("-DCMAKE_CXX_COMPILER=${mingwgccplusplus.replace("\\", "/")}")
        } else {
            println("Warning: MinGW C++ compiler (mingwgccplusplus variable: '$mingwgccplusplus') not found or not specified correctly.")
        }
    } else if (currentOs.isMacOsX) {
        cmakeGenerator = "Unix Makefiles"
        cmakeArgs.add("-G")
        cmakeArgs.add(cmakeGenerator)
        makeCommand = "make"
    } else if (currentOs.isLinux) {
        cmakeGenerator = "Unix Makefiles"
        cmakeArgs.add("-G")
        cmakeArgs.add(cmakeGenerator)
        makeCommand = "make"
    } else {
        throw GradleException("Unsupported operating system for native build: ${currentOs.name}")
    }
    workingDir = actualJniSourceDir
    commandLine(listOf(cmakeExecutablePath) + cmakeArgs)
    doLast {
        if (!cmakeBuildFolder.exists()) {
            cmakeBuildFolder.mkdirs() // Should typically be created by CMake's -B argument if it doesn't exist
            println("Warning: CMake build folder did not exist and was created by Gradle: ${cmakeBuildFolder.absolutePath}. This might indicate an issue if CMake itself didn't create it.")
        }
        logger.lifecycle("Running make command: '$makeCommand' in '${cmakeBuildFolder.absolutePath}'")

        exec {
            workingDir = cmakeBuildFolder
            commandLine(makeCommand.split(" "))
        }
        jniOutputFolder?.let {
            project.file(it).mkdirs()
        }
        cmakeBuildFolder.walkTopDown().forEach { fileToRename ->
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
                from(cmakeBuildFolder) {
                    include("*.dll", "*.so", "*.dylib")
//                    exclude()
                }

                into( project.file(it))
            }
        }
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
    val cmakeBuildFolder = project.file("${project.buildDir}/.cxx/$osIdentifier")
    jniOutputFolder?.let {   systemProperty("java.library.path", project.file(it).absolutePath)}?:run{systemProperty("java.library.path",cmakeBuildFolder.absolutePath)}

}

// Ensure native library is built before running the application
//當你寫 tasks.run { ... } 時，如果沒有足夠的上下文提示，Kotlin DSL 的類型推斷系統可能無法確定 run 是一個 JavaExec 類型的任務。因此，它不會識別 JavaExec 特有的 dependsOn 方法（儘管 dependsOn 是一個非常通用的任務方法，但有時類型推斷的缺失會導致解析問題，特別是在更複雜的閉包結構中）和 systemProperty 方法。通過使用 tasks.named<JavaExec>("run")，你明確地告訴 Gradle：「我要配置一個名為 'run' 的任務，並且我知道它的類型是 JavaExec。」這樣，IDE 和 Gradle 就能正確解析該類型擁有的所有屬性和方法。
//tasks.run {//FAIL
tasks.named<JavaExec>("run") {
    dependsOn("buildNativeLib")
    val cmakeBuildFolder = project.file("${project.buildDir}/.cxx/$osIdentifier")
    jniOutputFolder?.let {   systemProperty("java.library.path", project.file(it).absolutePath)}?:run{systemProperty("java.library.path",cmakeBuildFolder.absolutePath)}
}

dependencies {
    testImplementation(kotlin("test"))
}