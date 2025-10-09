
# toolchain-macos.cmake
# Generic toolchain file for macOS targets.

set(CMAKE_SYSTEM_NAME Darwin CACHE STRING "Target system name")
# CMAKE_SYSTEM_PROCESSOR 應該由 Gradle 在跨平台編譯時根據目標架構設定
# if(NOT DEFINED CMAKE_SYSTEM_PROCESSOR)
#    set(CMAKE_SYSTEM_PROCESSOR "aarch64" CACHE STRING "Default target processor for macOS if not set by Gradle")
# endif()
message(STATUS "Toolchain: Configuring for macOS (Darwin) target. Processor: ${CMAKE_SYSTEM_PROCESSOR}")

# 允許 Gradle 傳遞編譯器路徑
if(DEFINED EXTERNAL_C_COMPILER AND EXISTS "${EXTERNAL_C_COMPILER}")
    set(CMAKE_C_COMPILER "${EXTERNAL_C_COMPILER}" CACHE FILEPATH "C Compiler")
    message(STATUS "Toolchain: Using C Compiler from EXTERNAL_C_COMPILER: ${CMAKE_C_COMPILER}")
else()
    message(STATUS "Toolchain: EXTERNAL_C_COMPILER not defined or not found. CMake will attempt to find a system C compiler for Darwin.")
    # 不需要顯式設定，CMake 會自動查找
endif()

if(DEFINED EXTERNAL_CXX_COMPILER AND EXISTS "${EXTERNAL_CXX_COMPILER}")
    set(CMAKE_CXX_COMPILER "${EXTERNAL_CXX_COMPILER}" CACHE FILEPATH "C++ Compiler")
    message(STATUS "Toolchain: Using C++ Compiler from EXTERNAL_CXX_COMPILER: ${CMAKE_CXX_COMPILER}")
else()
    message(STATUS "Toolchain: EXTERNAL_CXX_COMPILER not defined or not found. CMake will attempt to find a system C++ compiler for Darwin.")
    # 不需要顯式設定，CMake 會自動查找
endif()

# 允許 Gradle 傳遞 SDK 路徑 (Sysroot)
if(DEFINED EXTERNAL_MACOS_SDK_PATH AND EXISTS "${EXTERNAL_MACOS_SDK_PATH}")
    set(CMAKE_OSX_SYSROOT "${EXTERNAL_MACOS_SDK_PATH}" CACHE PATH "macOS SDK path (sysroot)")
    # 將 SDK 路徑也加入到 CMAKE_FIND_ROOT_PATH，以便 find_* 命令可以搜索 SDK 中的內容
    list(APPEND CMAKE_FIND_ROOT_PATH "${EXTERNAL_MACOS_SDK_PATH}")
    message(STATUS "Toolchain: Using macOS SDK (sysroot) from EXTERNAL_MACOS_SDK_PATH: ${CMAKE_OSX_SYSROOT}")
else()
    message(STATUS "Toolchain: EXTERNAL_MACOS_SDK_PATH not defined or not found. CMake will attempt to use the system's default SDK for Darwin.")
    # 如果系統預設 SDK 正確，通常不需要顯式設定 CMAKE_OSX_SYSROOT
    # 但如果需要強制，可以這樣:
    # find_program(XCRUN_EXECUTABLE xcrun)
    # if(XCRUN_EXECUTABLE)
    #     execute_process(COMMAND ${XCRUN_EXECUTABLE} --sdk macosx --show-sdk-path OUTPUT_VARIABLE MACOSX_SDK_PATH OUTPUT_STRIP_TRAILING_WHITESPACE)
    #     if(MACOSX_SDK_PATH AND EXISTS "${MACOSX_SDK_PATH}")
    #         set(CMAKE_OSX_SYSROOT "${MACOSX_SDK_PATH}" CACHE PATH "macOS SDK path (sysroot) from xcrun")
    #         list(APPEND CMAKE_FIND_ROOT_PATH "${MACOSX_SDK_PATH}")
    #         message(STATUS "Toolchain: Determined macOS SDK (sysroot) via xcrun: ${CMAKE_OSX_SYSROOT}")
    #     endif()
    # endif()
endif()

# 設定 CMake 如何查找程式、函式庫和 include 檔案
# 當使用特定的 SDK (sysroot) 時，這很重要
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)  # 不在 sysroot 中查找程式
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)   # 只在 sysroot (CMAKE_FIND_ROOT_PATH) 中查找函式庫
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)  # 只在 sysroot (CMAKE_FIND_ROOT_PATH) 中查找 include 檔案
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE ONLY)  # 只在 sysroot (CMAKE_FIND_ROOT_PATH) 中查找套件

# 允許 Gradle 傳遞目標架構
if(DEFINED EXTERNAL_OSX_ARCHITECTURES)
    set(CMAKE_OSX_ARCHITECTURES "${EXTERNAL_OSX_ARCHITECTURES}" CACHE STRING "Build architectures for macOS")
    message(STATUS "Toolchain: Using macOS architectures from EXTERNAL_OSX_ARCHITECTURES: ${CMAKE_OSX_ARCHITECTURES}")
else()
    message(STATUS "Toolchain: EXTERNAL_OSX_ARCHITECTURES not defined. CMake will use its default for Darwin (usually the host architecture).")
    # 不需要設定，CMake 會使用預設值
endif()

# 確保 JNI include 路徑被考慮 (儘管主要由 Gradle 傳遞)
# 如果 Gradle 傳遞了 GRADLE_MANAGED_JNI_INCLUDE_DIR 和 GRADLE_MANAGED_JNI_MD_INCLUDE_DIR
# 它們應該在 target_include_directories 中被優先使用。
# 這裡可以作為一個備用，或者確保工具鏈本身知道如何找到 JNI (如果 Gradle 沒有傳遞)
# 但根據你的設定，Gradle 應該總是傳遞這些路徑。
# if(DEFINED JDK_INCLUDE_ROOT_FOR_TOOLCHAIN AND EXISTS "${JDK_INCLUDE_ROOT_FOR_TOOLCHAIN}")
#     include_directories(SYSTEM "${JDK_INCLUDE_ROOT_FOR_TOOLCHAIN}")
#     if(EXISTS "${JDK_INCLUDE_ROOT_FOR_TOOLCHAIN}/darwin/jni_md.h") # 特定於 macOS
#         include_directories(SYSTEM "${JDK_INCLUDE_ROOT_FOR_TOOLCHAIN}/darwin")
#     endif()
#     message(STATUS "Toolchain: Added JDK include paths from JDK_INCLUDE_ROOT_FOR_TOOLCHAIN: ${JDK_INCLUDE_ROOT_FOR_TOOLCHAIN}")
# endif()


message(STATUS "Toolchain: macOS toolchain configuration applied.")
