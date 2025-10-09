 cmake_minimum_required(VERSION 3.10)

project(JniDllExample CXX)

add_library(
        HelloWorld # This is the name used in System.loadLibrary("HelloWorld")
        SHARED
        HelloWorld.cpp
)

message(STATUS "Project Name: ${PROJECT_NAME}")

message(STATUS "Library Name (target): HelloWorld")

message(STATUS "--- JNI Include Path Configuration (CMakeLists.txt) ---")
message(STATUS "Received GRADLE_MANAGED_JNI_INCLUDE_DIR: [${GRADLE_MANAGED_JNI_INCLUDE_DIR}]")
message(STATUS "Received GRADLE_MANAGED_JNI_MD_INCLUDE_DIR: [${GRADLE_MANAGED_JNI_MD_INCLUDE_DIR}]")

# 主要 JNI include 目錄
if(NOT DEFINED GRADLE_MANAGED_JNI_INCLUDE_DIR OR GRADLE_MANAGED_JNI_INCLUDE_DIR STREQUAL "")
    message(FATAL_ERROR "GRADLE_MANAGED_JNI_INCLUDE_DIR is not defined or is empty from Gradle.")
elseif(NOT EXISTS "${GRADLE_MANAGED_JNI_INCLUDE_DIR}/jni.h")
    message(FATAL_ERROR "jni.h not found in GRADLE_MANAGED_JNI_INCLUDE_DIR: ${GRADLE_MANAGED_JNI_INCLUDE_DIR}/jni.h. Check Gradle parameters.")
else()
    target_include_directories(HelloWorld PRIVATE "${GRADLE_MANAGED_JNI_INCLUDE_DIR}")
    message(STATUS "SUCCESS: Added main JNI include directory: ${GRADLE_MANAGED_JNI_INCLUDE_DIR}")
endif()

# 平台特定的 jni_md.h 目錄
set(JNI_MD_DIR_MESSAGE "INFO: GRADLE_MANAGED_JNI_MD_INCLUDE_DIR evaluation: ")
if(DEFINED GRADLE_MANAGED_JNI_MD_INCLUDE_DIR AND NOT GRADLE_MANAGED_JNI_MD_INCLUDE_DIR STREQUAL "")
    string(APPEND JNI_MD_DIR_MESSAGE "Was defined and NOT EMPTY: [${GRADLE_MANAGED_JNI_MD_INCLUDE_DIR}]. ")
    # *** 關鍵檢查點在 CMakeLists.txt ***
    if(EXISTS "${GRADLE_MANAGED_JNI_MD_INCLUDE_DIR}/jni_md.h")
        target_include_directories(HelloWorld PRIVATE "${GRADLE_MANAGED_JNI_MD_INCLUDE_DIR}")
        string(APPEND JNI_MD_DIR_MESSAGE "SUCCESS: jni_md.h found. Added JNI machine-dependent include directory: ${GRADLE_MANAGED_JNI_MD_INCLUDE_DIR}")
    else()
        string(APPEND JNI_MD_DIR_MESSAGE "WARNING: jni_md.h NOT found in specified GRADLE_MANAGED_JNI_MD_INCLUDE_DIR: ${GRADLE_MANAGED_JNI_MD_INCLUDE_DIR}. Compilation might fail if jni.h references it directly and it's not in another include path.")
    endif()
else()
    string(APPEND JNI_MD_DIR_MESSAGE "Was NOT DEFINED or was EMPTY by Gradle. ")
    # 如果 Gradle 沒有提供 GRADLE_MANAGED_JNI_MD_INCLUDE_DIR，
    # 那麼 jni.h 必須能夠在其自身的相對路徑中找到 jni_md.h (例如，在 GRADLE_MANAGED_JNI_INCLUDE_DIR 下的 darwin/jni_md.h)
    # 這種情況下，不需要額外添加 include 目錄，因為 jni.h 內部會處理相對路徑。
    # 但是，如果 jni.h 找不到，編譯仍然會失敗。
    # 這裡的 CMake 腳本主要負責將 Gradle 提供的路徑加入。
    if(EXISTS "${GRADLE_MANAGED_JNI_INCLUDE_DIR}/${CMAKE_SYSTEM_NAME_LOWER}/jni_md.h") # 嘗試猜測，CMAKE_SYSTEM_NAME_LOWER 可能需要調整
        message(STATUS "INFO: Found jni_md.h in a subdirectory of main include: ${GRADLE_MANAGED_JNI_INCLUDE_DIR}/${CMAKE_SYSTEM_NAME_LOWER}/jni_md.h. This might be picked up automatically by jni.h.")
    elseif(EXISTS "${GRADLE_MANAGED_JNI_INCLUDE_DIR}/jni_md.h")
        message(STATUS "INFO: Found jni_md.h directly in main include: ${GRADLE_MANAGED_JNI_INCLUDE_DIR}/jni_md.h.")
    else()
        message(WARNING "WARNING: GRADLE_MANAGED_JNI_MD_INCLUDE_DIR was not provided, and jni_md.h was not found in common relative locations to the main JNI include directory. Compilation might fail if jni.h requires it.")
    endif()
endif()
message(STATUS "${JNI_MD_DIR_MESSAGE}")
message(STATUS "--- End JNI Include Path Configuration ---")


# 函式庫命名邏輯 (保持不變)
if (CMAKE_SYSTEM_NAME STREQUAL "Windows" OR (NOT CMAKE_SYSTEM_NAME AND WIN32))
    set(LIB_EXTENSION ".dll")
    set_target_properties(HelloWorld PROPERTIES PREFIX "")
    set_target_properties(HelloWorld PROPERTIES OUTPUT_NAME "HelloWorld")
    set_target_properties(HelloWorld PROPERTIES SUFFIX ${LIB_EXTENSION})
    message(STATUS "Targeting Windows. Library name: HelloWorld${LIB_EXTENSION}")
elseif (CMAKE_SYSTEM_NAME STREQUAL "Linux" OR (NOT CMAKE_SYSTEM_NAME AND UNIX AND NOT APPLE))
    set(LIB_EXTENSION ".so")
    set_target_properties(HelloWorld PROPERTIES PREFIX "lib")
    set_target_properties(HelloWorld PROPERTIES OUTPUT_NAME "HelloWorld")
    set_target_properties(HelloWorld PROPERTIES SUFFIX ${LIB_EXTENSION})
    message(STATUS "Targeting Linux. Library name: libHelloWorld${LIB_EXTENSION}")
elseif (CMAKE_SYSTEM_NAME STREQUAL "Darwin" OR (NOT CMAKE_SYSTEM_NAME AND APPLE)) # macOS
    set(LIB_EXTENSION ".dylib")
    set_target_properties(HelloWorld PROPERTIES PREFIX "lib")
    set_target_properties(HelloWorld PROPERTIES OUTPUT_NAME "HelloWorld")
    set_target_properties(HelloWorld PROPERTIES SUFFIX ${LIB_EXTENSION})
    message(STATUS "Targeting macOS. Library name: libHelloWorld${LIB_EXTENSION}")
else()
    message(WARNING "Could not determine target system for library naming conventions (CMAKE_SYSTEM_NAME: '${CMAKE_SYSTEM_NAME}'). Using default naming.")
    if(WIN32)
        set_target_properties(HelloWorld PROPERTIES PREFIX "")
        set_target_properties(HelloWorld PROPERTIES OUTPUT_NAME "HelloWorld")
        set_target_properties(HelloWorld PROPERTIES SUFFIX ".dll")
    elseif(UNIX)
        set_target_properties(HelloWorld PROPERTIES PREFIX "lib")
        set_target_properties(HelloWorld PROPERTIES OUTPUT_NAME "HelloWorld")
        if(APPLE)
            set_target_properties(HelloWorld PROPERTIES SUFFIX ".dylib")
        else()
            set_target_properties(HelloWorld PROPERTIES SUFFIX ".so")
        endif()
    endif()
endif()

message(STATUS "CMake configuration for ${PROJECT_NAME} JNI library complete.")
message(STATUS "Output library will be: ${CMAKE_BINARY_DIR}/${CMAKE_CFG_INTDIR}/<prefix>HelloWorld<suffix>")
message(STATUS "CMAKE_C_COMPILER: ${CMAKE_C_COMPILER}")
message(STATUS "CMAKE_CXX_COMPILER: ${CMAKE_CXX_COMPILER}")