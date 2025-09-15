plugins {
    application
    alias(libs.plugins.kotlin.jvm)
}

application {
    mainClass.set("org.kmp.jni.HelloWorld")
}

val jniSourceDirName = "src/main/jni"
val cmakeOutputSubDirName = "jni"
val finalNativeLibsDirName = "native"
val runtimeNativeLibsDir = project.layout.buildDirectory.dir(cmakeOutputSubDirName).get().asFile

tasks.register<Exec>("buildNativeLib") {
    description = "Builds the native library using CMake and MinGW."
    group = "build"
    val actualJniSourceDir = project.file(jniSourceDirName)
    val actualCmakeBuildDir = project.layout.buildDirectory.dir(cmakeOutputSubDirName).get().asFile
    val actualFinalNativeLibsDir = project.projectDir.resolve(finalNativeLibsDirName)
    workingDir = actualJniSourceDir
    commandLine(
        "cmake",
        "-S", ".", // Source directory (相對於 workingDir)
        "-B", actualCmakeBuildDir.relativeTo(actualJniSourceDir).path,
        "-G", "MinGW Makefiles",
//        "-D", "CMAKE_C_COMPILER=D:/MinGW/bin/x86_64-w64-mingw32-gcc.exe",
//        "-D", "CMAKE_CXX_COMPILER=D:/MinGW/bin/x86_64-w64-mingw32-g++.exe",
//        "-D", "CMAKE_MAKE_PROGRAM=D:/MinGW/bin/mingw32-make.exe"
    )

    doLast {
        exec {
            workingDir = actualCmakeBuildDir
            commandLine("mingw32-make")
        }
        actualFinalNativeLibsDir.mkdirs()
        actualCmakeBuildDir.walkTopDown().forEach { fileToRename ->
            if (fileToRename.isFile && fileToRename.name.startsWith("lib") && fileToRename.name.endsWith(".dll")) {
                val newNameInSource = fileToRename.name.substring(3)
                val newFileInSource = File(fileToRename.parentFile, newNameInSource)
                if (fileToRename.renameTo(newFileInSource)) {
                } else {
                }
            }
        }
        project.copy {
            from(actualCmakeBuildDir) {
                include("*.dll", "*.so", "*.dylib")
            }
            into(actualFinalNativeLibsDir)
            rename { originalFileName ->
                val finalTargetName = if (originalFileName.endsWith(".dll") && originalFileName.startsWith("lib")) {
                    originalFileName.substring(3)
                } else {
                    originalFileName
                }
                finalTargetName
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
    systemProperty("java.library.path", runtimeNativeLibsDir.absolutePath)
}

// Ensure native library is built before running the application
//當你寫 tasks.run { ... } 時，如果沒有足夠的上下文提示，Kotlin DSL 的類型推斷系統可能無法確定 run 是一個 JavaExec 類型的任務。因此，它不會識別 JavaExec 特有的 dependsOn 方法（儘管 dependsOn 是一個非常通用的任務方法，但有時類型推斷的缺失會導致解析問題，特別是在更複雜的閉包結構中）和 systemProperty 方法。通過使用 tasks.named<JavaExec>("run")，你明確地告訴 Gradle：「我要配置一個名為 'run' 的任務，並且我知道它的類型是 JavaExec。」這樣，IDE 和 Gradle 就能正確解析該類型擁有的所有屬性和方法。
//tasks.run {//FAIL
tasks.named<JavaExec>("run") {
    dependsOn("buildNativeLib")
    systemProperty("java.library.path", runtimeNativeLibsDir.absolutePath)
}

dependencies {
    testImplementation(kotlin("test"))
}