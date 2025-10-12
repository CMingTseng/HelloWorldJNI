import com.android.tools.r8.synthesis.f
import org.gradle.internal.os.OperatingSystem
import de.undercouch.gradle.tasks.download.Download
import java.io.ByteArrayOutputStream
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

var isForceUseZig=true
// --- Internal build script variables ---

val currentHostOs = OperatingSystem.current()
val actualJniSourceDir = settingCmakeFolder?.let { project.file(it) }
    ?: project.file("src/main/jni")
val jdkUrls =settingJDKUrls
val cmakeBuildDirRoot =
    project.layout.buildDirectory.dir(".cxx").get().asFile

val hostOsIdentifier = currentHostOs.run {
    when {
        isWindows -> "windows"
        isMacOsX -> "macos"
        isLinux -> "linux"
        else -> name.lowercase().replace(" ", "_") // Use lowercase() as per deprecation warning
    }
}

//val targetOsForJni: String = hostOsIdentifier
val targetOsForJni: String = "linux"

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
    currentHostOs.isMacOsX -> "darwin"
    currentHostOs.isLinux -> "linux"
    else -> currentHostOs.name.toLowerCase().replace(" ", "_")
}

val targetOsBuildIdentifier = when (targetOsForJni) { // 根據 targetOsForJni 決定 JNI include 子目錄的名稱
    "windows" -> "win32"
    "macos" -> "darwin"
    "linux" -> "linux"
    else -> targetOsForJni.toLowerCase().replace(" ", "_")
}
var hasNinja = false
var isUseAndroidSDK=false

val toolchainFileDir = actualJniSourceDir.resolve("toolchains")
fun findCommandRealPath(command: String, project: Project): String? {
    val logger = project.logger
    val whichStdout = ByteArrayOutputStream()
    val whichResult = project.exec {
        commandLine(if (OperatingSystem.current().isWindows) "where" else "which", command)
        standardOutput = whichStdout
        isIgnoreExitValue = true // We will check the exit code manually
    }

    if (whichResult.exitValue != 0) {
        // This is a normal case when a command is not found, so use a more neutral log level.
        logger.info("[findCommandRealPath] 'which $command' failed (Exit Code: ${whichResult.exitValue}). Command not found in PATH.")
        return null
    }

    val absolutePath = whichStdout.toString().trim()
    if (absolutePath.isBlank() || absolutePath.contains("not found", ignoreCase = true)) {
        // Some 'which' implementations might return 0 but print 'not found' to stdout.
        logger.warn("[findCommandRealPath] 'which $command' succeeded but returned an empty or 'not found' path.")
        return null
    }

    logger.info("[findCommandRealPath] 'which' found '$command' at: $absolutePath")
    return absolutePath
}

fun isCommandAvailable(command: String, project: Project, args: String? = ""): Boolean {
    val logger = project.logger
    logger.info("[isCommandAvailable] Checking availability of '$command'...")

    val absolutePath: String?

    // --- 1. 判斷傳入的 'command' 是路徑還是單純的命令 ---
    // 如果命令包含路徑分隔符，我們將其視為一個明確的路徑。否則，我們認為它是在PATH中搜尋的命令。
    val isExplicitPath = command.contains("/") || command.contains("\\")

    if (isExplicitPath) {
        // 如果是明確的路徑（絕對或相對），直接驗證它
        val commandFile = project.file(command)
        if (commandFile.exists() && commandFile.canExecute()) {
            logger.info("[isCommandAvailable] Provided command '$command' is an explicit path and is executable.")
            // 將路徑標準化為正斜線，以便在 exec 中更好地工作
            absolutePath = commandFile.absolutePath.replace("\\", "/")
        } else {
            logger.error("[isCommandAvailable] Provided command '$command' is an explicit path but does not exist or is not executable.")
            return false
        }
    } else {
        // 如果只是命令名稱（如 "git" 或 "mingw32-make.exe"），則使用 'where'/'which' 在 PATH 中查找
        absolutePath = findCommandRealPath(command, project)
        if (absolutePath == null) {
            // findCommandRealPath 已經記錄了找不到命令的原因
            return false
        }
    }

    // --- 2. 如果 args 為 null，跳過執行檢查 ---
    // 這對 `checkingMinGW` 這樣的場景很有用，我們只想知道命令是否存在。
    if (args == null) {
        logger.lifecycle("[isCommandAvailable] Successfully found '$command' at '$absolutePath'. Skipping execution check as no args were provided.")
        return true
    }

    // --- 3. 執行命令驗證 ---
    val verifyStdout = ByteArrayOutputStream()
    val verifyStderr = ByteArrayOutputStream()
    val verifyResult = project.exec {
        // 確保即使 args 為空，也只傳遞一個空字串，而不是 null
        val commandArgs = if (args.isNotBlank()) args.split(" ") else emptyList()
        commandLine(listOf(absolutePath) + commandArgs)

        standardOutput = verifyStdout
        errorOutput = verifyStderr
        isIgnoreExitValue = true
    }

    val executedCommand = if (args.isNotBlank()) "'$absolutePath $args'" else "'$absolutePath'"
    logger.info("[isCommandAvailable] Verification via $executedCommand -> Exit Code: ${verifyResult.exitValue}")

    if (verifyResult.exitValue == 0) {
        logger.lifecycle("[isCommandAvailable] Successfully verified '$command' at '$absolutePath'.")
        return true
    } else {
        logger.error(
            "[isCommandAvailable] FAILURE: Command found at '$absolutePath' but failed to execute the check. Exit Code: ${verifyResult.exitValue}, STDERR: ${
                verifyStderr.toString().trim()
            }"
        )
        return false
    }
}

// --- CMake Executable Finder ---
fun findCMakeExecutable(project: Project): String {
    val logger = project.logger
    val cmakeExeName = "cmake" + if (currentHostOs.isWindows) ".exe" else ""

    // --- 1. 最高优先级: 检查 ANDROID_HOME ---
    // 这是安卓开发者的主要场景，通常包含 CMake 和 Ninja
    val androidHome = System.getenv("ANDROID_HOME")
    if ( androidHome != null && androidHome.isNotBlank()) {
        val cmakeSdkPath = project.file("$androidHome/cmake")
        if (cmakeSdkPath.exists() && cmakeSdkPath.isDirectory) {
            // 查找最新版本的 CMake 目录
            val latestCmakeVersionDir = cmakeSdkPath.listFiles()
                ?.filter { it.isDirectory && it.name.matches(Regex("\\d+\\.\\d+\\.\\d+")) }
                ?.maxByOrNull { it.name }
            logger.warn("[CMake Finder] Show me latestCmakeVersionDir $latestCmakeVersionDir")
            if (latestCmakeVersionDir != null) {
                val cmakeFromAndroidHome = project.file("${latestCmakeVersionDir.absolutePath}/bin/$cmakeExeName")
                if (cmakeFromAndroidHome.exists() &&cmakeFromAndroidHome.isFile&& cmakeFromAndroidHome.canExecute()) {
                    logger.lifecycle("[CMake Finder] SUCCESS: Found CMake via ANDROID_HOME (latest version): ${cmakeFromAndroidHome.absolutePath}")
                    val ninjaPath = cmakeFromAndroidHome.parentFile.resolve("ninja" + if (currentHostOs.isWindows) ".exe" else "")
                    if(ninjaPath.exists() &&ninjaPath.isFile&& ninjaPath.canExecute()){
                        logger.lifecycle("[CMake Finder] Found Ninja alongside CMake in ANDROID_HOME: ${ninjaPath.absolutePath}")
                        hasNinja = true
                        isUseAndroidSDK=true
                    }
                    return cmakeFromAndroidHome.absolutePath
                }
            }
        }
    }

    // --- 2. 第二优先级: 检查 CMAKE_HOME ---
    // 这是用户明确指定 CMake 安装位置的标准方式
    val cmakeHomeEnv = System.getenv("CMAKE_HOME")
    if (cmakeHomeEnv != null && cmakeHomeEnv.isNotBlank()) {
        val cmakeFromHome = project.file("$cmakeHomeEnv/bin/$cmakeExeName")
        if (cmakeFromHome.exists()&&cmakeFromHome.isFile && cmakeFromHome.canExecute()) {
            logger.lifecycle("[CMake Finder] SUCCESS: Found CMake via CMAKE_HOME: ${cmakeFromHome.absolutePath}")
            return cmakeFromHome.absolutePath
        } else {
            logger.warn("[CMake Finder] CMAKE_HOME was set to '$cmakeHomeEnv', but cmake executable not found or not executable in its bin directory.")
        }
    }

    // --- 3. 第三优先级: 使用 'which'/'where' 在 PATH 中查找 ---
    // 这是最通用和可靠的方法，如果命令在 PATH 中，就不需要绝对路径
    val cmakePathFromWhich = findCommandRealPath(cmakeExeName, project)
    if (cmakePathFromWhich != null ) {
        logger.lifecycle("[CMake Finder] SUCCESS: Found CMake in PATH using 'which/where': $cmakePathFromWhich")
        // 如果在 PATH 中找到了，直接返回命令本身或其绝对路径都可以
        // 返回绝对路径更明确
        return cmakePathFromWhich
    }

    // --- 4. 最低优先级: 检查常见的硬编码路径 (作为最后手段) ---
    logger.warn("[CMake Finder] CMake not found via ANDROID_HOME, CMAKE_HOME, or PATH. Now checking common hardcoded paths...")
    val commonPaths = mutableListOf<String>()
    when {
        currentHostOs.isMacOsX -> {
            commonPaths.add("/opt/homebrew/bin/$cmakeExeName") // Apple Silicon Homebrew
            commonPaths.add("/usr/local/bin/$cmakeExeName")   // Intel Homebrew / manual install
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
            logger.lifecycle("[CMake Finder] SUCCESS: Found CMake in a common system path: ${cmakeFile.absolutePath}")
            return cmakeFile.absolutePath
        }
    }

    // --- 5. 最终回退 ---
    // 如果所有方法都失败了，只能假设 'cmake' 在 PATH 中（尽管 'which' 失败了，这不太可能）
    // 或者让构建在后面失败，并给出清晰的错误信息
    logger.error("[CMake Finder] FATAL: CMake could not be found via ANDROID_HOME, CMAKE_HOME, PATH, or common installation directories.")
    logger.error("[CMake Finder] Please install CMake or configure one of the environment variables (ANDROID_HOME, CMAKE_HOME).")
    // 返回命令名，让后续的 exec 任务失败并报告 "command not found"
    return cmakeExeName
}

private fun checkingMinGW():Boolean{
    val logger = project.logger
    logger.lifecycle("[Compiler Check] Checking for MinGW-w64 availability...")

    val mingwCommandToCheck: String
    val commandArgs: String?

    when (hostOsIdentifier) {
        "windows" -> {
            mingwCommandToCheck = "mingw32-make.exe"
//            commandArgs = null // 只查找，不执行版本检查
            commandArgs = "--version"
        }
        "macos", "linux" -> {
            mingwCommandToCheck = "x86_64-w64-mingw32-gcc"
            commandArgs = "--version"
        }
        else -> {
            logger.error("[Compiler Check] MinGW check not implemented for host OS: $hostOsIdentifier")
            return false
        }
    }

    logger.info("[Compiler Check] Verifying command '$mingwCommandToCheck' on $hostOsIdentifier...")

    if (isCommandAvailable(mingwCommandToCheck, project, commandArgs)) {
        // isCommandAvailable 会打印成功的生命周期日志
        // 在这里可以补充一个更明确的 MinGW 成功日志
        logger.lifecycle("[Compiler Check] SUCCESS: MinGW-w64 toolchain is available on this system.")
        return true
    } else {
        logger.error("[Compiler Check] FAILURE: MinGW-w64 toolchain not found or not functional.")
        when (hostOsIdentifier) {
            "windows" -> logger.error(
                "Please install MSYS2 and MinGW-w64 (e.g., pacman -S mingw-w64-x86_64-toolchain) and ensure its bin directory is in your PATH."
            )
            "macos" -> logger.error("Please install via Homebrew: `brew install mingw-w64`")
            "linux" -> logger.error(
                "Please install using your package manager (e.g., sudo apt-get install mingw-w64)."
            )
        }
        return false
    }
}

private fun checkingZig():Boolean{
    val logger = project.logger
    logger.lifecycle("[Compiler Check] Checking for Zig availability...")
    val zigExeName = "zig" + if (OperatingSystem.current().isWindows) ".exe" else ""
    var effectiveZigCmd: String? = null

    // --- 1. 優先檢查 ZIG_HOME ---
    val zigHomeEnv = System.getenv("ZIG_HOME")
    if (zigHomeEnv != null && zigHomeEnv.isNotBlank()) {
        logger.lifecycle("[Compiler Check] Found ZIG_HOME environment variable: $zigHomeEnv")
        val zigAtRoot = project.file("$zigHomeEnv/$zigExeName")
        val zigInBin = project.file("$zigHomeEnv/bin/$zigExeName")

        if (zigAtRoot.exists() && zigAtRoot.canExecute()) {
            effectiveZigCmd = zigAtRoot.absolutePath
        } else if (zigInBin.exists() && zigInBin.canExecute()) {
            effectiveZigCmd = zigInBin.absolutePath
        } else {
            logger.warn("[Compiler Check] ZIG_HOME is set, but '$zigExeName' was not found in '$zigHomeEnv' or '$zigHomeEnv/bin'. Will try finding in PATH next.")
        }
    }

    // --- 2. 如果 ZIG_HOME 中沒找到，則從系統 PATH 中尋找 ---
    if (effectiveZigCmd == null) {
        logger.info("[Compiler Check] ZIG_HOME did not yield a valid executable. Searching for '$zigExeName' in system PATH.")
        // 注意：這裡直接傳遞命令名稱，讓 isCommandAvailable 內部去查找
        effectiveZigCmd = zigExeName
    }

    logger.lifecycle("[Compiler Check] Checking for Zig effectiveZigCmd : $effectiveZigCmd")

    // --- 3. 使用 isCommandAvailable 驗證找到的命令 ---
    if (isCommandAvailable(effectiveZigCmd, project, "version")) {
        logger.lifecycle("[Compiler Check] SUCCESS: Zig toolchain is available on this system.")
        return true
    } else {
        // --- 4. 如果所有方法都失敗，則顯示您精心撰寫的平台特定警告 ---
        logger.error("For installation guidance, please review the warnings from the initial compiler check.")

        // --- 使用您提供的精确警告信息 ---
        val windowsWarning =
            "\n!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n" +
                    "!! WARNING: ZIG COMPILER NOT FOUND OR NOT USABLE ON WINDOWS                           !!\n" +
                    "!! ---------------------------------------------------------------------------------- !!\n" +
                    "!! Failed to find or verify the Zig compiler ('$zigExeName').                         !!\n" +
                    "!!                                                                                    !!\n" +
                    "!! IF ZIG IS REQUIRED for your current build configuration:                           !!\n" +
                    "!!                                                                                    !!\n" +
                    "!! PLEASE DO THE FOLLOWING ON WINDOWS:                                                !!\n" +
                    "!! 1. Download Zig: Go to https://ziglang.org/download/                               !!\n" +
                    "!! 2. Extract Zig to a stable location (e.g., C:\\zig).                               !!\n" +
                    "!! 3. Configure ZIG_HOME (Recommended): Set the ZIG_HOME environment variable to      !!\n" +
                    "!!    point to your Zig installation directory (e.g., C:\\zig).                         !!\n" +
                    "!! 4. OR Add Zig to PATH: Add the directory containing 'zig.exe' to your System PATH. !!\n" +
                    "!! 5. Verify: Open a NEW Command Prompt and type 'zig version'.                       !!\n" +
                    "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"

        val macosWarning =
            "\n!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n" +
                    "!! WARNING: ZIG COMPILER NOT FOUND OR NOT USABLE ON MACOS                             !!\n" +
                    "!! ---------------------------------------------------------------------------------- !!\n" +
                    "!! Failed to verify Zig. The command '$zigExeName' was not found in your system PATH or is not executable. !!\n" +
                    "!!                                                                                    !!\n" +
                    "!! IF ZIG IS REQUIRED for your current build configuration:                           !!\n" +
                    "!!                                                                                    !!\n" +
                    "!! For general guidance on installing Zig, you can refer to this page:                !!\n" +
                    "!!   https://course.ziglang.cc/environment/install-environment                        !!\n" +
                    "!!                                                                                    !!\n" +
                    "!! PLEASE CHOOSE ONE OF THE FOLLOWING DETAILED INSTALLATION METHODS FOR MACOS:        !!\n" +
                    "!!                                                                                    !!\n" +
                    "!! METHOD 1: Using Homebrew (Recommended for most users)                            !!\n" +
                    "!!   1. Open Terminal and run:                                                        !!\n" +
                    "!!      brew install zig                                                              !!\n" +
                    "!!   2. Ensure Homebrew's bin directory (e.g., /opt/homebrew/bin or /usr/local/bin)   !!\n" +
                    "!!      is correctly configured in your system's PATH.                                !!\n" +
                    "!!                                                                                    !!\n" +
                    "!! METHOD 2: Manual Installation from ziglang.org                                   !!\n" +
                    "!!   1. Download the appropriate Zig bundle for your macOS architecture.              !!\n" +
                    "!!   2. Extract the archive and add the directory containing the 'zig' executable     !!\n" +
                    "!!      to your system's PATH environment variable.                                   !!\n" +
                    "!!                                                                                    !!\n" +
                    "!! METHOD 3: Using MacPorts                                                           !!\n" +
                    "!!   1. If you use MacPorts, open Terminal and run:                                   !!\n" +
                    "!!      sudo port install zig                                                         !!\n" +
                    "!!                                                                                    !!\n" +
                    "!! VERIFICATION:                                                                      !!\n" +
                    "!!   Open a NEW terminal window/tab and type:                                         !!\n" +
                    "!!     zig version                                                                    !!\n" +
                    "!!   You should see the Zig version information. If not, your PATH is not set up correctly. !!\n" +
                    "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"

        val linuxWarning =
            "\n!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n" +
                    "!! WARNING: ZIG COMPILER NOT FOUND OR NOT USABLE ON LINUX                             !!\n" +
                    "!! ---------------------------------------------------------------------------------- !!\n" +
                    "!! Failed to find or verify the Zig compiler ('$zigExeName').                         !!\n" +
                    "!!                                                                                    !!\n" +
                    "!! IF ZIG IS REQUIRED for your current build configuration:                           !!\n" +
                    "!!                                                                                    !!\n" +
                    "!! METHOD 1: Using a Package Manager (e.g., Snap, APT, DNF, Pacman)                   !!\n" +
                    "!!   - Check your distribution's official repository first.                           !!\n" +
                    "!!   - Example (Snap): `snap install zig --classic`                                   !!\n" +
                    "!!   - Example (Arch): `pacman -S zig`                                                !!\n" +
                    "!!                                                                                    !!\n" +
                    "!! METHOD 2: Manual Installation from ziglang.org                                   !!\n" +
                    "!!   1. Download the appropriate Zig bundle for your Linux architecture.              !!\n" +
                    "!!   2. Extract the archive and add the directory containing 'zig' to your PATH.      !!\n" +
                    "!!                                                                                    !!\n" +
                    "!! VERIFICATION:                                                                      !!\n" +
                    "!!   Open a NEW terminal and type: `zig version`                                      !!\n" +
                    "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"

        when (hostOsIdentifier) {
            "windows" -> logger.error(windowsWarning)
            "macos" -> logger.error(macosWarning)
            "linux" -> logger.error(linuxWarning)
            else -> logger.error(macosWarning) // Fallback to a detailed generic message
        }
        return false
    }
}

fun checkingGnuGcc(project: Project): Boolean {
    val logger = project.logger
    val gnuGccCommand = "x86_64-unknown-linux-gnu-gcc"
    logger.lifecycle("[Compiler Check] Checking for GNU cross-compiler ('$gnuGccCommand') on macOS...")

    // Use the new helper function to find the GNU GCC compiler.
    val gnuGccPath = findCommandRealPath(gnuGccCommand, project)

    if (gnuGccPath != null) {
        // Additionally, check for g++ to ensure the full C/C++ toolchain is present.
        val gnuGxxCommand = "x86_64-unknown-linux-gnu-g++"
        val gnuGxxPath = findCommandRealPath(gnuGxxCommand, project)
        if (gnuGxxPath != null) {
            logger.lifecycle("[Compiler Check] SUCCESS: Found GNU cross-compiler toolchain (gcc at '$gnuGccPath', g++ at '$gnuGxxPath').")
            return true
        } else {
            logger.error("[Compiler Check] Found GNU GCC ('$gnuGccCommand') but NOT G++ ('$gnuGxxCommand'). The toolchain is incomplete.")
        }
    }

    // This block is reached if either gcc or g++ is not found.
    logger.error(
        "\n!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n" +
                "!! WARNING: GNU CROSS-COMPILER NOT FOUND ON MACOS                                 !!\n" +
                "!! ---------------------------------------------------------------------------------- !!\n" +
                "!! Failed to find the GNU cross-compiler ('$gnuGccCommand' and/or 'x86_64-unknown-linux-gnu-g++') in your system PATH. !!\n" +
                "!!                                                                                    !!\n" +
                "!! TO INSTALL (Recommended):                                                          !!\n" +
                "!!   - Open Terminal and run: `brew install x86_64-unknown-linux-gnu`                 !!\n" +
                "!!   - This provides the complete C/C++ toolchain for Linux cross-compilation.        !!\n" +
                "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
    )
    return false
}

fun checkingMuslGcc(project: Project): Boolean {
    val logger = project.logger
    val muslToolchainName = "x86_64-linux-musl"
    val muslGccCommand = "${muslToolchainName}-gcc"
    logger.lifecycle("[Compiler Check] Checking for MUSL cross-compiler ('$muslGccCommand') on macOS...")
    // Use findCommandRealPath to check for both gcc and g++ components.
    val muslGccPath = findCommandRealPath(muslGccCommand, project)
    val muslGxxPath = findCommandRealPath("${muslToolchainName}-g++", project)

    if (muslGccPath != null && muslGxxPath != null) {
        logger.lifecycle("[Compiler Check] SUCCESS: Found MUSL cross-compiler toolchain (gcc at '$muslGccPath', g++ at '$muslGxxPath').")
        return true
    } else {
        if (muslGccPath == null) logger.warn("[Compiler Check] MUSL GCC ('$muslGccCommand') not found.")
        if (muslGxxPath == null) logger.warn("[Compiler Check] MUSL G++ ('${muslToolchainName}-g++') not found.")

        logger.error(
            "\n!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n" +
                    "!! WARNING: MUSL CROSS-COMPILER NOT FOUND ON MACOS                                !!\n" +
                    "!! ---------------------------------------------------------------------------------- !!\n" +
                    "!! Failed to find the MUSL cross-compiler toolchain in your system PATH.            !!\n" +
                    "!!                                                                                    !!\n" +
                    "!! TO INSTALL (Recommended):                                                          !!\n" +
                    "!!   - Open Terminal and run the following commands:                                  !!\n" +
                    "!!     `brew tap messense/macos-cross-toolchains`                                     !!\n" +
                    "!!     `brew install ${muslToolchainName}`                                            !!\n" +
                    "!!                                                                                    !!\n" +
                    "!! For more info, see: https://github.com/messense/homebrew-macos-cross-toolchains  !!\n" +
                    "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
        )
        return false
    }
}

fun checkingNinja(project: Project, providedNinjaCmd: String? = null): Boolean {
    val logger = project.logger
    val ninjaCommandNameForPath = if (OperatingSystem.current().isWindows) "ninja.exe" else "ninja"
    var effectiveNinjaCmd: String? = null
    logger.lifecycle("[Compiler Check] Checking for Ninja availability...")

    // 1. 嘗試 NINJA_HOME
    val ninjaHomeEnv = System.getenv("NINJA_HOME")
    if (ninjaHomeEnv != null && ninjaHomeEnv.isNotBlank()) {
        logger.lifecycle("[Compiler Check] Found NINJA_HOME: $ninjaHomeEnv")
        try {
            val ninjaExeInHome = Paths.get(ninjaHomeEnv, ninjaCommandNameForPath)
            if (Files.exists(ninjaExeInHome) && Files.isExecutable(ninjaExeInHome)) {
                effectiveNinjaCmd = ninjaExeInHome.toString().replace("\\", "/")
                logger.info("[Compiler Check] Ninja found via NINJA_HOME at: $effectiveNinjaCmd")
            } else {
                // 嘗試直接在 NINJA_HOME 下找 (如果 NINJA_HOME 指向 bin 目錄)
                val ninjaExeDirect = Paths.get(
                    ninjaHomeEnv,
                    if (ninjaCommandNameForPath.endsWith(".exe")) "" else "../",
                    ninjaCommandNameForPath
                ) // 粗略嘗試
                if (Files.exists(ninjaExeDirect) && Files.isExecutable(ninjaExeDirect)) {
                    effectiveNinjaCmd = ninjaExeDirect.toString().replace("\\", "/")
                    logger.info("[Compiler Check] Ninja found directly in NINJA_HOME (or parent) at: $effectiveNinjaCmd")
                } else {
                    logger.debug("[Compiler Check] NINJA_HOME was set, but '$ninjaCommandNameForPath' not found or not executable in it or common subdirectories.")
                }
            }
        } catch (e: Exception) { // 更通用的異常捕捉
            logger.warn("[Compiler Check] Error processing NINJA_HOME ('$ninjaHomeEnv'): ${e.message}")
        }
    } else {
        logger.debug("[Compiler Check] NINJA_HOME environment variable not set.")
    }

    // 2. 如果 NINJA_HOME 未成功，則考慮 providedNinjaCmd
    if (effectiveNinjaCmd == null && providedNinjaCmd != null && providedNinjaCmd.isNotBlank()) {
        logger.debug("[Compiler Check] NINJA_HOME not conclusive. Using provided Ninja command/path: '$providedNinjaCmd'")
        effectiveNinjaCmd = providedNinjaCmd
    }

    // 3. 如果以上都沒有確定，則默認嘗試在 PATH 中查找
    if (effectiveNinjaCmd == null) {
        logger.debug("[Compiler Check] No NINJA_HOME or specific command. Will check for '$ninjaCommandNameForPath' in system PATH.")
        effectiveNinjaCmd = ninjaCommandNameForPath
    }


    if (isCommandAvailable(effectiveNinjaCmd!!, project, "--version")) {
        logger.lifecycle("[Compiler Check] Ninja command '$effectiveNinjaCmd' found and verified.")
//        // 更新全局狀態 (如果需要，並且這個函數被設計為這樣做)
//        isNinjaAvailableGlobal = true
//        ninjaExecutablePathGlobal = if (project.file(effectiveNinjaCmd).isAbsolute) effectiveNinjaCmd else ninjaCommandNameForPath
        return true
    } else {
        logger.warn(
            "\n!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n" +
                    "!! WARNING: NINJA BUILD TOOL NOT FOUND OR NOT USABLE                                !!\n" +
                    "!! ---------------------------------------------------------------------------------- !!\n" +
                    "!! Failed to verify Ninja using the command: '$effectiveNinjaCmd'.                        !!\n" +
                    "!!                                                                                    !!\n" +
                    "!! IF NINJA IS REQUIRED for your current build configuration (e.g., with Zig):        !!\n" +
                    "!! PLEASE DO THE FOLLOWING:                                                           !!\n" +
                    "!! 1. Download Ninja: From https://github.com/ninja-build/ninja/releases              !!\n" +
                    "!! 2. Install Ninja: Place the 'ninja' (or 'ninja.exe') executable in a directory.    !!\n" +
                    "!! 3. Configure NINJA_HOME (Recommended): Set NINJA_HOME environment variable to      !!\n" +
                    "!!    point to the directory containing the Ninja executable.                         !!\n" +
                    "!! 4. OR Add to PATH: Ensure the directory with 'ninja' is in your system PATH.       !!\n" +
                    "!! 5. Verify: Open a NEW terminal and type 'ninja --version'.                         !!\n" +
                    "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
        )
        return false
    }
}

fun findMinGWRoofDir(project: Project, hostOs: String): String? {
    val logger = project.logger
    logger.lifecycle("[findMinGWRoofDir] Searching for MinGW toolchain root on host: $hostOs")
    when (hostOs) {
        "windows" -> {
            // 1. 优先: 从 PATH 中查找 gcc.exe，并反向推断根目录
            val gccPath = findCommandRealPath("gcc.exe", project)
            if (gccPath != null && gccPath.contains("mingw", ignoreCase = true)) {
                try {
                    val gccFile = project.file(gccPath)
                    val binDir = gccFile.parentFile
                    if (binDir != null && binDir.name.equals("bin", ignoreCase = true)) {
                        val potentialRoot = binDir.parentFile
                        if (potentialRoot != null && potentialRoot.exists() && potentialRoot.isDirectory) {
                            logger.lifecycle("[findMinGWRoofDir] Found MinGW root by locating MinGW-related 'gcc.exe' in PATH: ${potentialRoot.absolutePath}")
                            return potentialRoot.absolutePath.replace("\\", "/")
                        }
                    }
                } catch (e: Exception) {
                    logger.warn("[findMinGWRoofDir] Error while inferring root from GCC path '$gccPath': ${e.message}")
                }
            }

            // 2. 后备: 检查硬编码的常见路径
            logger.info("[findMinGWRoofDir] Could not infer from PATH. Checking hardcoded potential MinGW root paths...")
            val hardcodedPossibleMinGWRoots = listOf(
                "C:/msys64/mingw64", // MSYS2 64-bit MinGW
                "C:/msys64/mingw32", // MSYS2 32-bit MinGW
                "C:/MinGW"          // 旧版 MinGW
            )
            hardcodedPossibleMinGWRoots.forEach { rootPath ->
                val potentialRoot = project.file(rootPath)
                val gccInRoot = potentialRoot.resolve("bin/gcc.exe")
                if (potentialRoot.isDirectory && gccInRoot.isFile) {
                    logger.lifecycle("[findMinGWRoofDir] Found MinGW root via hardcoded path list: '$rootPath'")
                    return potentialRoot.absolutePath.replace("\\", "/")
                }
            }

            logger.warn("[findMinGWRoofDir] Could not determine MinGW root directory on Windows.")
            return null
        }

        "macos", "linux" -> {
            // 在非 Windows 宿主机上，我们依赖 PATH，不需要为 MinGW 设置 sysroot。
            // 只需确认交叉编译器存在即可。
            val mingwGccCommand = "x86_64-w64-mingw32-gcc"
            logger.info("[findMinGWRoofDir] On $hostOs, checking for '$mingwGccCommand' in PATH...")

            if (findCommandRealPath(mingwGccCommand, project) != null) {
                logger.lifecycle("[findMinGWRoofDir] Found '$mingwGccCommand'. Returning null to indicate PATH usage.")
                return null // **正确返回 null**，表示让 CMake 在 PATH 中自行查找
            }

            logger.warn("[findMinGWRoofDir] Could not find '$mingwGccCommand' in PATH on $hostOs.")
            return null
        }

        else -> {
            logger.warn("[findMinGWRoofDir] MinGW root detection not implemented for host OS: $hostOs")
            return null
        }
    }
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
    val isNativeBuildOnHost = (targetOsForJni == hostOsIdentifier)
    val cmakeBuildDirForTarget = if (isNativeBuildOnHost) {
        cmakeBuildDirRoot
    } else {
        cmakeBuildDirRoot.resolve(targetOsForJni)
    }

    var cmakeGenerator: String

    // Configure CMake arguments and Make command based on OS
    val cmakeArgs = mutableListOf(
        cmakeExecutable,
        "-S", "${actualJniSourceDir.absolutePath}",
        "-B", "${cmakeBuildDirForTarget.absolutePath}",
        "-D", "CMAKE_BUILD_TYPE=$cmakeBuildType"
    )

//    val cmakeArgs = mutableListOf(
//        cmakeExecutable
//    )
//
//    cmakeArgs.add("-S")
//    cmakeArgs.add("${actualJniSourceDir.absolutePath}")
//    cmakeArgs.add("-B")
//    cmakeArgs.add("${cmakeBuildDirForTarget.absolutePath}")


    cmakeArgs.add("-D")
    cmakeArgs.add("CMAKE_BUILD_TYPE=$cmakeBuildType")
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
    logger.lifecycle("[Build Config] Adding to cmakeArgs: -DGRADLE_MANAGED_JNI_INCLUDE_DIR='${gradleManagedJniIncludeDir.absolutePath.replace("\\", "/")}'")
    cmakeArgs.add("-DGRADLE_MANAGED_JNI_MD_INCLUDE_DIR=${gradleManagedJniMdIncludeDir.absolutePath.replace("\\", "/")}")
    logger.lifecycle("[Build Config] Adding to cmakeArgs: -DGRADLE_MANAGED_JNI_MD_INCLUDE_DIR='${gradleManagedJniMdIncludeDir.absolutePath.replace("\\", "/")}'")
    cmakeGenerator = "MinGW Makefiles"
    when(hostOsIdentifier){
        "windows"->{
            val isZIgExist= checkingZig()
            if (targetOsForJni == "windows"){
                cmakeArgs.add("-DCMAKE_SYSTEM_NAME=Windows")
                cmakeArgs.add("-DCMAKE_SYSTEM_PROCESSOR=x86_64")
                val isMinGW64 = checkingMinGW()
               if (isMinGW64){
                    cmakeGenerator = "MinGW Makefiles"
                } else if(isZIgExist){
                    cmakeGenerator="Ninja"
                    val isUseToolchainFile = true
                    if (isUseToolchainFile){
                        logger.lifecycle("[Build Config] Using ZIG via external toolchain file.")
                        val toolchainFileName = "toolchain-windows-x86_64_by_zig.cmake"
                        val toolchainFile = toolchainFileDir.resolve(toolchainFileName)
                        if (!toolchainFile.exists()) {
                            throw GradleException(
                                "Toolchain file strategy is enabled (isUseToolchainFile=true), " +
                                        "but the file was not found at the expected location: ${toolchainFile.absolutePath}"
                            )
                        }
                        cmakeArgs.add("-DCMAKE_TOOLCHAIN_FILE=${toolchainFile.absolutePath.replace("\\", "/")}")

                        logger.lifecycle("[Build Config] Toolchain file set to: ${toolchainFile.absolutePath}")

                    }else{
                        val targetTriple = "x86_64-windows-gnu"
                        cmakeArgs.add("-DCMAKE_C_COMPILER=zig;cc")
                        cmakeArgs.add("-DCMAKE_CXX_COMPILER=zig;c++")
                        cmakeArgs.add("-DCMAKE_C_COMPILER_TARGET=${targetTriple}")
                        cmakeArgs.add("-DCMAKE_CXX_COMPILER_TARGET=${targetTriple}")
                        logger.lifecycle("[Build Config] Configured to use ZIG as compiler for Windows.")
                    }
                }else{
                    val errorContext = "Windows native compilation"
                    logger.error(
                        "\n!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n" +
                                "!! BUILD ERROR: No Suitable Native Compiler Found for $errorContext               !!\n" +
                                "!! ---------------------------------------------------------------------------------- !!\n" +
                                "!! Failed to configure a usable build environment (e.g., Zig/Ninja or MinGW).       !!\n" +
                                "!! PLEASE REVIEW THE PREVIOUS '[Compiler Check]' LOGS for detailed warnings and     !!\n" +
                                "!! installation guidance for missing tools (like MinGW's mingw32-make or Zig).    !!\n" +
                                "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
                    )
                    throw GradleException("No suitable native compiler found for $errorContext. Check build logs for details and installation instructions.")
                }
            }else if(targetOsForJni == "linux"){
                cmakeArgs.add("-DCMAKE_SYSTEM_NAME=Linux")
                cmakeArgs.add("-DCMAKE_SYSTEM_PROCESSOR=x86_64")
                cmakeGenerator="Ninja"
                val isCygwin = false
                if (isCygwin) {
                    //TODO Cygwin to big !!!
                    //Ref : https://metamod-p.sourceforge.net/cross-compiling.on.windows.for.linux.html
                }
//                else if ( hasNinja && isUseAndroidSDK) {//FIXME need Linux sysroot !!
//                    //TODO cmake + clang + ninja
//                }
                else if (isZIgExist && hasNinja) {
                    val isUseToolchainFile = true
                    if (isUseToolchainFile) {
                        logger.lifecycle("[Build Config] Using ZIG via external toolchain file.")
                        val toolchainFileName = "toolchain-linux-x86_64_by_zig.cmake"
                        val toolchainFile = toolchainFileDir.resolve(toolchainFileName)
                        if (!toolchainFile.exists()) {
                            throw GradleException(
                                "Toolchain file strategy is enabled (isUseToolchainFile=true), " +
                                        "but the file was not found at the expected location: ${toolchainFile.absolutePath}"
                            )
                        }
                        cmakeArgs.add("-DCMAKE_TOOLCHAIN_FILE=${toolchainFile.absolutePath.replace("\\", "/")}")

                        logger.lifecycle("[Build Config] Toolchain file set to: ${toolchainFile.absolutePath}")

                    } else {
                        //FIXME like cli
//                       cmakeArgs.add("-G"); cmakeArgs.add("Ninja")
                        logger.lifecycle("[Build Config After -G Ninja] cmakeArgs now contains '-G Ninja'. Size: ${cmakeArgs.size}")
                        cmakeArgs.add("-DCMAKE_C_COMPILER=zig;cc")
                        cmakeArgs.add("-DCMAKE_CXX_COMPILER=zig;c++")
                        val targetTriple = "x86_64-linux-gnu"
                        cmakeArgs.add("-DCMAKE_C_COMPILER_TARGET=${targetTriple}")
                        cmakeArgs.add("-DCMAKE_CXX_COMPILER_TARGET=${targetTriple}")
                    }
                } else {
                    //TODO
                    // ***** 修改錯誤拋出，提示用戶查看日誌 *****
                    val errorContext = "Windows native compilation"
                    logger.error(
                        "\n!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n" +
                                "!! BUILD ERROR: No Suitable Native Compiler Found for $errorContext               !!\n" +
                                "!! ---------------------------------------------------------------------------------- !!\n" +
                                "!! Failed to configure a usable build environment (e.g., Zig/Ninja or MinGW).       !!\n" +
                                "!! PLEASE REVIEW THE PREVIOUS '[Compiler Check]' LOGS for detailed warnings and     !!\n" +
                                "!! installation guidance for missing tools (like MinGW's mingw32-make or Zig).    !!\n" +
                                "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
                    )
                    throw GradleException("No suitable native compiler found for $errorContext. Check build logs for details and installation instructions.")
                }
            }else if(targetOsForJni == "macos"){
                if (isZIgExist && hasNinja) {

                } else {
                    val errorContext = "Windows native compilation"
                    logger.error(
                        "\n!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n" +
                                "!! BUILD ERROR: No Suitable Native Compiler Found for $errorContext               !!\n" +
                                "!! ---------------------------------------------------------------------------------- !!\n" +
                                "!! Failed to configure a usable build environment (e.g., Zig/Ninja or MinGW).       !!\n" +
                                "!! PLEASE REVIEW THE PREVIOUS '[Compiler Check]' LOGS for detailed warnings and     !!\n" +
                                "!! installation guidance for missing tools (like MinGW's mingw32-make or Zig).    !!\n" +
                                "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
                    )
                    throw GradleException("No suitable native compiler found for $errorContext. Check build logs for details and installation instructions.")
                }
            }
        }
        "linux"->{
            cmakeGenerator = "Unix Makefiles"
            if (targetOsForJni == "linux") {

            }else if (targetOsForJni == "windows") {

            } else if (targetOsForJni == "macos") {

            } else  {

            }
        }
        "macos"->{
            val isZIgExist = checkingZig()
            logger.lifecycle("[buildNativeLib] Host is macOS, Show isZIgExist $isZIgExist isNinja $hasNinja")
            cmakeGenerator = "Unix Makefiles"
            if (targetOsForJni == "windows") {
                cmakeArgs.add("-DCMAKE_SYSTEM_NAME=Windows")
                cmakeArgs.add("-DCMAKE_SYSTEM_PROCESSOR=x86_64")
                logger.lifecycle("[buildNativeLib] Host is macOS, target is Windows.")
//               val isMinGW =checkingMinGW()
                val isMinGW = false
                logger.lifecycle("[buildNativeLib] Host is macOS, Show isMinGW $isMinGW")
                if (isMinGW){
                    val mingwRootOnMac = findMinGWRoofDir(project, hostOsIdentifier)
                    val isUseToolchainFile = false
                    if (isUseToolchainFile) {//TODO use toolchain file
                        val mingwRootOnMacPath = mingwRootOnMac!!.replace("\\", "/")
                        logger.lifecycle("[buildNativeLib] Using generator: Unix Makefiles for MinGW cross-compile") // 您的原始日志
                        val toolchainFilePath =
                            actualJniSourceDir.resolve("toolchains/toolchain-win-x86_64-by-mingw.cmake").absolutePath.replace(
                                "\\",
                                "/"
                            )
                        cmakeArgs.add("-DCMAKE_TOOLCHAIN_FILE=$toolchainFilePath")
//                       cmakeArgs.add("-DGRADLE_MINGW_PATH=$mingwRootOnMacPath")
//                       logger.lifecycle("[buildNativeLib] Passing GRADLE_MINGW_PATH to CMake: $mingwRootOnMacPath")
                    } else {//TODO use gradle trigger cli
//                       val cCompilerPath = "${mingwRootOnMac}/bin/x86_64-w64-mingw32-gcc"
//                       val cxxCompilerPath = "${mingwRootOnMac}/bin/x86_64-w64-mingw32-g++"
                        val cCompilerPath = "x86_64-w64-mingw32-gcc"
                        val cxxCompilerPath = "x86_64-w64-mingw32-g++"
                        if (!project.file(cCompilerPath)
                                .exists()
                        ) throw GradleException("C compiler not found at: $cCompilerPath")
                        if (!project.file(cxxCompilerPath)
                                .exists()
                        ) throw GradleException("CXX compiler not found at: $cxxCompilerPath")
                        cmakeArgs.add("-DCMAKE_SYSROOT=${mingwRootOnMac}")//TODO if use like cli  do not CMAKE_SYSROOT
                        logger.lifecycle("[buildNativeLib] Setting CMAKE_SYSROOT: $mingwRootOnMac")
                        cmakeArgs.add("-DCMAKE_C_COMPILER=${cCompilerPath}")
                        cmakeArgs.add("-DCMAKE_CXX_COMPILER=${cxxCompilerPath}")
                        logger.lifecycle("[buildNativeLib] Using C Compiler: $cCompilerPath")
                        logger.lifecycle("[buildNativeLib] Using CXX Compiler: $cxxCompilerPath")
                    }
                    logger.lifecycle("[buildNativeLib] Using generator: Unix Makefiles for MinGW cross-compile")
                }else if (hasNinja && isZIgExist) {
                    val isUseToolchain = false
                    cmakeGenerator = "Ninja"
                    if (isUseToolchain) {
                        val toolchainFileName = "toolchains/toolchain-windows-x86_64-zig.cmake"
                        val toolchainFilePath = actualJniSourceDir.resolve(toolchainFileName).absolutePath.replace("\\", "/")
                        if (!project.file(toolchainFilePath).exists()) {
                            throw GradleException("Zig Toolchain file '$toolchainFileName' not found at: $toolchainFilePath")
                        }
                        cmakeArgs.add("-DCMAKE_TOOLCHAIN_FILE=$toolchainFilePath")
                        logger.lifecycle("[buildNativeLib] Applying Zig toolchain for Windows cross-compilation: $toolchainFilePath")
                        val zigCommand = "zig"
                        cmakeArgs.add("-DGRADLE_ZIG_EXECUTABLE=$zigCommand")
                        logger.lifecycle("[buildNativeLib] Passing GRADLE_ZIG_EXECUTABLE='$zigCommand' to toolchain (relying on PATH).")

                    } else {
                        cmakeArgs.add("-DCMAKE_C_COMPILER=zig;cc")
                        cmakeArgs.add("-DCMAKE_CXX_COMPILER=zig;c++")
                        val targetTriple = "x86_64-windows-gnu"
                        cmakeArgs.add("-DCMAKE_C_COMPILER_TARGET=${targetTriple}")
                        cmakeArgs.add("-DCMAKE_CXX_COMPILER_TARGET=${targetTriple}")
                    }
                } else {
                    val errorContext = "macOS to Windows cross-compilation"
                    logger.error(
                        "\n!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n" +
                                "!! BUILD ERROR: No Suitable Cross-Compiler Found for $errorContext !!\n" +
                                "!! ---------------------------------------------------------------------------------- !!\n" +
                                "!! This build requires a toolchain on macOS to compile for Windows, but none were found.!!\n" +
                                "!! The script checked for the following required toolchains:                          !!\n" +
                                "!!                                                                                    !!\n" +
                                "!!   METHOD 1: MinGW-w64 Toolchain                                                    !!\n" +
                                "!!     - Install via Homebrew: `brew install mingw-w64`                               !!\n" +
                                "!!                                                                                    !!\n" +
                                "!!   METHOD 2: Zig Compiler (used with Ninja)                                         !!\n" +
                                "!!     - Install via Homebrew: `brew install zig`                                     !!\n" +
                                "!!                                                                                    !!\n" +
                                "!! PLEASE REVIEW THE PREVIOUS '[Compiler Check]' LOGS for detailed warnings and       !!\n" +
                                "!! install ONE of the toolchains listed above.                                        !!\n" +
                                "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
                    )
                    throw GradleException("No suitable cross-compiler found for $errorContext. Check build logs for MinGW or Zig installation guidance.")
                }
            }else if (targetOsForJni == "linux") {
                cmakeArgs.add("-DCMAKE_SYSTEM_NAME=Linux")
                cmakeArgs.add("-DCMAKE_SYSTEM_PROCESSOR=x86_64")
                cmakeGenerator = "Unix Makefiles"
                val isGnuGcc = checkingGnuGcc(project)// check install x86_64-unknown-linux-gnu-gcc
                val isMuslGcc=checkingMuslGcc(project)// x86_64-linux-musl-gcc
                if (isGnuGcc){
                    val isUseToolchain = false
                    if (isUseToolchain) {
                        val makeExecutableGnu = "make"
                        if (isCommandAvailable(makeExecutableGnu, project, null)) {
                            cmakeArgs.add("-DCMAKE_MAKE_PROGRAM=$makeExecutableGnu")
                            logger.lifecycle("[buildNativeLib] Using generator: Unix Makefiles with CMAKE_MAKE_PROGRAM: $makeExecutableGnu")
                        } else {
                            logger.warn("[buildNativeLib] 'make' command not found for GNU setup. CMake will attempt to find it.")
                        }

                        // Set CMAKE_TOOLCHAIN_FILE
                        val toolchainFilePath =
                            actualJniSourceDir.resolve("toolchains/toolchain-linux.cmake").absolutePath.replace(
                                "\\",
                                "/"
                            )
                        if (!project.file(toolchainFilePath).exists()) {
                            throw GradleException("Linux Toolchain file 'toolchain-linux.cmake' not found at: $toolchainFilePath")
                        }
                        cmakeArgs.add("-DCMAKE_TOOLCHAIN_FILE=$toolchainFilePath")
                        logger.lifecycle("[buildNativeLib] Applying toolchain file: $toolchainFilePath")
                        val gnuToolchainName = "x86_64-unknown-linux-gnu"
                        val cCompilerGnu = "${gnuToolchainName}-gcc"
                        val cxxCompilerGnu = "${gnuToolchainName}-g++"
                        project.logger.lifecycle("[buildNativeLib] Passing GRADLE_C_COMPILER (GNU): $cCompilerGnu (name only, expected in PATH)")
                        project.logger.lifecycle("[buildNativeLib] Passing GRADLE_CXX_COMPILER (GNU): $cxxCompilerGnu (name only, expected in PATH)")
                        cmakeArgs.add("-DGRADLE_C_COMPILER=$cCompilerGnu")
                        cmakeArgs.add("-DGRADLE_CXX_COMPILER=$cxxCompilerGnu")
                    } else {
                        val gnuToolchainName = "x86_64-unknown-linux-gnu"
                        cmakeArgs.add("-DCMAKE_C_COMPILER=${gnuToolchainName}-gcc")
                        cmakeArgs.add("-DCMAKE_CXX_COMPILER=${gnuToolchainName}-g++")
                    }
                }else if(isMuslGcc){
                    val isUseToolchain = true
                    if (isUseToolchain) {
                        val makeExecutableMusl = "make"
                        if (isCommandAvailable(makeExecutableMusl, project, null)) {
                            cmakeArgs.add("-DCMAKE_MAKE_PROGRAM=$makeExecutableMusl")
                            logger.lifecycle("[buildNativeLib] Using generator: Unix Makefiles with CMAKE_MAKE_PROGRAM: $makeExecutableMusl")
                        } else {
                            logger.warn("[buildNativeLib] 'make' command not found for MUSL setup. CMake will attempt to find it.")
                        }

                        // Set CMAKE_TOOLCHAIN_FILE
                        val toolchainFilePath =
                            actualJniSourceDir.resolve("toolchains/toolchain-linux.cmake").absolutePath.replace(
                                "\\",
                                "/"
                            )
                        if (!project.file(toolchainFilePath).exists()) {
                            throw GradleException("Linux Toolchain file 'toolchain-linux.cmake' not found at: $toolchainFilePath")
                        }
                        cmakeArgs.add("-DCMAKE_TOOLCHAIN_FILE=$toolchainFilePath")
                        logger.lifecycle("[buildNativeLib] Applying toolchain file: $toolchainFilePath")
                        val muslToolchainName = "x86_64-linux-musl"
                        val cCompilerMusl = "${muslToolchainName}-gcc"
                        val cxxCompilerMusl = "${muslToolchainName}-g++"
                        cmakeArgs.add("-DGRADLE_C_COMPILER=$cCompilerMusl")
                        cmakeArgs.add("-DGRADLE_CXX_COMPILER=$cxxCompilerMusl")
                        logger.lifecycle("[buildNativeLib] Passing GRADLE_C_COMPILER (MUSL): $cCompilerMusl to toolchain.")
                        logger.lifecycle("[buildNativeLib] Passing GRADLE_CXX_COMPILER (MUSL): $cxxCompilerMusl to toolchain.")
                        logger.lifecycle("[buildNativeLib] For MUSL, GRADLE_LINUX_CROSS_TOOLCHAIN_ROOT will not be set. Toolchain relies on compiler's built-in sysroot.")
                    } else {
                        val gnuToolchainName = "x86_64-linux-musl"
                        cmakeArgs.add("-DCMAKE_C_COMPILER=${gnuToolchainName}-gcc")
                        cmakeArgs.add("-DCMAKE_CXX_COMPILER=${gnuToolchainName}-g++")
                    }
                }else if (hasNinja && isZIgExist) {
                    cmakeGenerator = "Ninja"
                    val isUseToolchain = false
                    if (isUseToolchain) {
                        val toolchainFileName = "toolchains/toolchain-linux-x86_64-by-zig.cmake"
                        val toolchainFilePath =
                            actualJniSourceDir.resolve(toolchainFileName).absolutePath.replace(
                                "\\",
                                "/"
                            )
                        if (!project.file(toolchainFilePath).exists()) {
                            throw GradleException("Zig Toolchain file '$toolchainFileName' not found at: $toolchainFilePath")
                        }
                        cmakeArgs.add("-DCMAKE_TOOLCHAIN_FILE=$toolchainFilePath")
                        logger.lifecycle("[buildNativeLib] Applying Zig toolchain for Linux cross-compilation: $toolchainFilePath")
                        // 2. Pass the command name "zig" to the toolchain file.
                        // Since isZIgExist is already true, we know 'zig' is available in the PATH.
                        // The toolchain file is designed to handle both absolute paths and command names.
                        val zigCommand = "zig"
                        cmakeArgs.add("-DGRADLE_ZIG_EXECUTABLE=$zigCommand")
                        logger.lifecycle("[buildNativeLib] Passing GRADLE_ZIG_EXECUTABLE='$zigCommand' to toolchain (relying on PATH).")
                    } else {
                        cmakeArgs.add("-DCMAKE_C_COMPILER=zig;cc")
                        cmakeArgs.add("-DCMAKE_CXX_COMPILER=zig;c++")
                        val targetTriple = "x86_64-linux-gnu"
                        cmakeArgs.add("-DCMAKE_C_COMPILER_TARGET=${targetTriple}")
                        cmakeArgs.add("-DCMAKE_CXX_COMPILER_TARGET=${targetTriple}")
                    }
                } else {
                    val errorContext = "macOS to Linux cross-compilation"
                    logger.error(
                        "\n!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n" +
                                "!! BUILD ERROR: No Suitable Cross-Compiler Found for $errorContext !!\n" +
                                "!! ---------------------------------------------------------------------------------- !!\n" +
                                "!! This build requires a toolchain on macOS to compile for Linux, but none were found.!!\n" +
                                "!! The script checked for the following required toolchains:                          !!\n" +
                                "!!   1. GNU Toolchain (e.g., 'x86_64-unknown-linux-gnu-gcc' from 'brew install x86_64-unknown-linux-gnu') !!\n" +
                                "!!   2. MUSL Toolchain (e.g., 'x86_64-linux-musl-gcc' from 'brew install messense/macos-cross-toolchains/x86_64-linux-musl') !!\n" +
                                "!!   3. Zig Compiler (used with Ninja, e.g., 'brew install zig')                      !!\n" +
                                "!!                                                                                    !!\n" +
                                "!! PLEASE REVIEW THE PREVIOUS '[Compiler Check]' LOGS for detailed warnings and       !!\n" +
                                "!! install ONE of the toolchains listed above.                                        !!\n" +
                                "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
                    )
                    throw GradleException("No suitable cross-compiler found for $errorContext. Check build logs for details and installation instructions for GNU, MUSL, or Zig toolchains.")
                }
            }else if (targetOsForJni == "macos") {
                logger.lifecycle("[buildNativeLib] Host is macOS, target is macOS.")
                cmakeGenerator = "Unix Makefiles"
                val cCompilerForMac = "clang"
                val cxxCompilerForMac = "clang++"
                cmakeArgs.add("-DCMAKE_C_COMPILER=${cCompilerForMac}")
                cmakeArgs.add("-DCMAKE_CXX_COMPILER=${cxxCompilerForMac}")
                logger.lifecycle("[buildNativeLib] Using C Compiler: $cCompilerForMac")
                logger.lifecycle("[buildNativeLib] Using CXX Compiler: $cxxCompilerForMac")
            }
        }
    }

    cmakeArgs.add("-G")
    cmakeArgs.add(cmakeGenerator)
    // Configure Task
    workingDir = project.projectDir
    doFirst {
        if (!actualJniSourceDir.exists() || !actualJniSourceDir.isDirectory) {
            throw GradleException("JNI source directory does not exist or is not a directory: ${actualJniSourceDir.absolutePath}")
        }
        cmakeBuildDirForTarget.mkdirs()
        cmakeArgs.forEach { logger.lifecycle("  $it") }
        logger.lifecycle("[EXEC] Running command : ==============\n ${cmakeArgs.joinToString(" ") { arg ->
            if (arg.contains(" ")) "\"$arg\"" else arg
        }}")
        logger.lifecycle("==============")
        commandLine(cmakeArgs.first())
        args(cmakeArgs.drop(1))
        standardOutput = System.out
        errorOutput = System.err
    }

    doLast {
        exec {
            workingDir = cmakeBuildDirForTarget
            logger.lifecycle("Show me at doLast cmakeBuildDirForTarget ${cmakeBuildDirForTarget} \n workingDir $workingDir")
            commandLine(findCMakeExecutable(project))
            args(
                "--build", workingDir,
                "--config", cmakeBuildType
            )
            standardOutput = System.out
            errorOutput = System.err
            logger.lifecycle("[buildNativeLib.doLast] Executing build command : \n '${commandLine.joinToString(" ")}' in project root (build dir is an argument)")
        }
        logger.lifecycle("[buildNativeLib.doLast] Native library build step completed successfully using 'cmake --build'.")
        logger.lifecycle("[buildNativeLib.doLast] Starting post-build file operations.")

        println("== File Operations in doLast (Using Configurable Paths) ==")
        println("Source Directory (CMake build output): ${cmakeBuildDirForTarget.absolutePath}")
        println("Renaming lib*.dll files in source directory (${cmakeBuildDirForTarget.absolutePath}):")
        cmakeBuildDirForTarget.walkTopDown().forEach { fileToRename ->
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
                    from(project.fileTree(cmakeBuildDirForTarget) {
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
