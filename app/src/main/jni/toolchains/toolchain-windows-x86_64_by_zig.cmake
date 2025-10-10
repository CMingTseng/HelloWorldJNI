# 設置目標系統名稱和處理器，這對於 CMake 的內部判斷很重要
set(CMAKE_SYSTEM_NAME "Windows")
set(CMAKE_SYSTEM_PROCESSOR "x86_64")

## 1. 尋找 'zig' 可執行檔
##    因為 Gradle 的 checkingZig() 已經確認過它存在於 PATH 中，
##    所以這裡的 find_program 應該總能成功。
#find_program(ZIG_EXE zig REQUIRED)
#message(STATUS "Toolchain: Found Zig executable at: ${ZIG_EXE}")

## 2. 定義目標三元組 (Target Triple)
##    對於 Windows 64-bit，使用 gnu ABI (通常與 MinGW 更相容)
#set(TARGET_TRIPLE "x86_64-windows-gnu")
#message(STATUS "Toolchain: Setting target triple to: ${TARGET_TRIPLE}")
#
## 3. 設置 C 和 C++ 編譯器
##    我們將 'zig cc' 和 'zig c++' 作為編譯器命令，
##    並透過 `-target` 參數指定交叉編譯目標。
##    CMake 會自動處理命令中的空格和引號。
#set(CMAKE_C_COMPILER "${ZIG_EXE}" "cc" "-target" "${TARGET_TRIPLE}")
#set(CMAKE_CXX_COMPILER "${ZIG_EXE}" "c++" "-target" "${TARGET_TRIPLE}")
#
## 4. (可選但推薦) 設置 Sysroot
##    讓 Zig 告訴我們它為這個目標所使用的內部 sysroot 在哪裡，
##    這能幫助 CMake 的 find_library/find_path 正確工作。
#execute_process(
#        COMMAND "${ZIG_EXE}" "cc" "-target" "${TARGET_TRIPLE}" "-print-sysroot"
#        OUTPUT_VARIABLE ZIG_SYSROOT
#        OUTPUT_STRIP_TRAILING_WHITESPACE
#)
#if(ZIG_SYSROOT)
#    set(CMAKE_SYSROOT "${ZIG_SYSROOT}")
#    message(STATUS "Toolchain: Setting CMAKE_SYSROOT to: ${CMAKE_SYSROOT}")
#else()
#    message(WARNING "Toolchain: Could not determine sysroot from Zig for target ${TARGET_TRIPLE}.")
#endif()
#
#message(STATUS "Toolchain: C Compiler configured to: ${CMAKE_C_COMPILER}")
#message(STATUS "Toolchain: CXX Compiler configured to: ${CMAKE_CXX_COMPILER}")

# 1. 硬編碼 Zig 可執行檔名稱
#    我們相信 Gradle 已經確認了 'zig' 在 PATH 中。
set(ZIG_EXE "zig")
message(STATUS "Toolchain: Using command '${ZIG_EXE}' (assumed to be in PATH).")

# 2. 定義目標三元組
set(TARGET_TRIPLE "x86_64-windows-gnu")
message(STATUS "Toolchain: Setting target triple to: ${TARGET_TRIPLE}")

# 3. 設置 C 和 C++ 編譯器
set(CMAKE_C_COMPILER "${ZIG_EXE}" "cc" "-target" "${TARGET_TRIPLE}")
set(CMAKE_CXX_COMPILER "${ZIG_EXE}" "c++" "-target" "${TARGET_TRIPLE}")

# 4. (已修正) 設置 Sysroot
#    不再使用不穩定的 '-print-sysroot' 參數。
#    對於簡單的 JNI 編譯，不設置 CMAKE_SYSROOT 通常是安全的，
#    因為 Zig 會隱性處理。如果未來需要更複雜的函式庫查找，
#    可以研究 `zig targets` 命令的輸出來解析 sysroot 路徑。
#    目前，為了保持日誌乾淨，我們直接移除這一段。
message(STATUS "Toolchain: CMAKE_SYSROOT is not explicitly set. Letting Zig manage it implicitly.")

# --- 以下為可選的補充說明，如果您想讓 CMake 知道這個編譯器是 Clang ---
# 這可以幫助啟用一些 Clang 特有的功能或警告。
set(CMAKE_CXX_COMPILER_ID "Clang")
set(CMAKE_C_COMPILER_ID "Clang")

message(STATUS "Toolchain: C Compiler configured to: ${CMAKE_C_COMPILER}")
message(STATUS "Toolchain: CXX Compiler configured to: ${CMAKE_CXX_COMPILER}")
