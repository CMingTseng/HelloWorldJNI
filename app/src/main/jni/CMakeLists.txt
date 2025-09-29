 cmake_minimum_required(VERSION 3.10)

 # Project name (can be anything, doesn't directly affect output library name for add_library)
 project(JniDllExample CXX)
#TODO add_library must before target_include_directories
 # Add the C++ source file for the native library.
 # The library will be named "libHelloWorld.so" (or .dylib, .dll depending on platform if not Android)
 # The first argument to add_library ("HelloWorld") is what System.loadLibrary("HelloWorld") expects.
 add_library(
         HelloWorld # This is the name used in System.loadLibrary("HelloWorld")
         SHARED  # Creates a shared library (DLL on Windows,/ .so file for Android)
         HelloWorld.cpp
         # If you had other .cpp or .c files, add them here
 )

 find_package(JNI)

 # 檢查 find_package(JNI) 是否完全成功並且定義了有效的 JNI_INCLUDE_DIRS
 # JNI_FOUND 可能為 TRUE，但 JNI_INCLUDE_DIRS 可能包含 NOTFOUND 路徑
 set(JNI_CONFIG_SUCCESSFUL FALSE)
 if (JNI_FOUND AND JNI_INCLUDE_DIRS)
     # 進一步檢查 JNI_INCLUDE_DIRS 是否真的包含了有效的路徑，而不是 -NOTFOUND
     # 這一步比較複雜，因為 JNI_INCLUDE_DIRS 是個列表
     # 一個簡單的檢查是，如果 JNI_INCLUDE_DIRS 中有任何一個元素包含 "NOTFOUND"
     # 我們就認為它不完全成功。
     set(JNI_PATHS_ARE_VALID TRUE)
     foreach(DIR ${JNI_INCLUDE_DIRS})
         if(DIR MATCHES "-NOTFOUND$") # 檢查是否以 -NOTFOUND 結尾
             set(JNI_PATHS_ARE_VALID FALSE)
             break()
         endif()
     endforeach()

     if(JNI_PATHS_ARE_VALID)
         message(STATUS "Found JNI automatically by find_package(JNI). Using JNI_INCLUDE_DIRS: ${JNI_INCLUDE_DIRS}")
         # 如果 find_package(JNI) 成功，它通常會為目標自動處理 include 目錄。
         # 如果沒有，可以手動添加： target_include_directories(HelloWorld PRIVATE ${JNI_INCLUDE_DIRS})
         set(JNI_CONFIG_SUCCESSFUL TRUE)
     else()
         message(WARNING "find_package(JNI) found JNI but JNI_INCLUDE_DIRS ('${JNI_INCLUDE_DIRS}') seem invalid. Will use Gradle paths.")
     endif()
 endif()


 if (NOT JNI_CONFIG_SUCCESSFUL)
     message(WARNING "JNI include paths not successfully configured by find_package(JNI). Attempting to use paths from Gradle.")

     # 清空可能由 find_package(JNI) 部分設置的 JNI_INCLUDE_DIRS，以避免衝突
     # 或者確保我們不依賴它
     unset(JNI_INCLUDE_DIRS CACHE) # 嘗試清除緩存中的 JNI_INCLUDE_DIRS
     set(JNI_INCLUDE_DIRS "")     # 將其設置為空列表

     if(DEFINED GRADLE_MANAGED_JNI_INCLUDE_DIR AND EXISTS "${GRADLE_MANAGED_JNI_INCLUDE_DIR}/jni.h")
         message(STATUS "Using GRADLE_MANAGED_JNI_INCLUDE_DIR: ${GRADLE_MANAGED_JNI_INCLUDE_DIR}")
         target_include_directories(HelloWorld PRIVATE "${GRADLE_MANAGED_JNI_INCLUDE_DIR}")
         list(APPEND JNI_INCLUDE_DIRS "${GRADLE_MANAGED_JNI_INCLUDE_DIR}") # 手動填充 JNI_INCLUDE_DIRS
     else()
         message(WARNING "GRADLE_MANAGED_JNI_INCLUDE_DIR ('${GRADLE_MANAGED_JNI_INCLUDE_DIR}') is not defined or jni.h not found in it.")
     endif()

     if(DEFINED GRADLE_MANAGED_JNI_MD_INCLUDE_DIR AND EXISTS "${GRADLE_MANAGED_JNI_MD_INCLUDE_DIR}/jni_md.h")
         message(STATUS "Using GRADLE_MANAGED_JNI_MD_INCLUDE_DIR: ${GRADLE_MANAGED_JNI_MD_INCLUDE_DIR}")
         target_include_directories(HelloWorld PRIVATE "${GRADLE_MANAGED_JNI_MD_INCLUDE_DIR}")
         list(APPEND JNI_INCLUDE_DIRS "${GRADLE_MANAGED_JNI_MD_INCLUDE_DIR}") # 手動填充 JNI_INCLUDE_DIRS
     else()
         message(WARNING "GRADLE_MANAGED_JNI_MD_INCLUDE_DIR ('${GRADLE_MANAGED_JNI_MD_INCLUDE_DIR}') is not defined or jni_md.h (or equivalent) not found in it.")
     endif()

     if(NOT (DEFINED GRADLE_MANAGED_JNI_INCLUDE_DIR AND EXISTS "${GRADLE_MANAGED_JNI_INCLUDE_DIR}/jni.h"))
         message(FATAL_ERROR "Failed to configure JNI include directories via Gradle paths. Check Gradle parameters and JDK download/extraction.")
     endif()
     set(JNI_CONFIG_SUCCESSFUL TRUE) # 標記我們已經通過 Gradle 路徑成功配置了
 endif()

 # --- 平台相關設定 ---
 # 這是交叉編譯的關鍵！我們檢查由 toolchain file 設定的 CMAKE_SYSTEM_NAME
 if (CMAKE_SYSTEM_NAME STREQUAL "Windows")
     set(LIB_EXTENSION "dll")
     set(JVM_SUB_DIR "win32")
 elseif (CMAKE_SYSTEM_NAME STREQUAL "Linux")
     set(LIB_EXTENSION "so")
     set(JVM_SUB_DIR "linux")
 elseif (CMAKE_SYSTEM_NAME STREQUAL "Darwin") # Darwin 是 macOS 的核心名稱
     set(LIB_EXTENSION "dylib")
     set(JVM_SUB_DIR "darwin")
 else ()
     # 如果沒有 toolchain file，就偵測當前主機系統
     if (WIN32)
         set(LIB_EXTENSION "dll")
         set(JVM_SUB_DIR "win32")
     elseif (UNIX AND NOT APPLE)
         set(LIB_EXTENSION "so")
         set(JVM_SUB_DIR "linux")
     elseif (APPLE)
         set(LIB_EXTENSION "dylib")
         set(JVM_SUB_DIR "darwin")
     endif ()
 endif ()

 message(STATUS "Target System: ${CMAKE_SYSTEM_NAME}")
 message(STATUS "Output Library: ${TARGET_NAME}.${LIB_EXTENSION}")
 message(STATUS "Java Headers: ${JAVA_HOME}/include/${JVM_SUB_DIR}")

 # Link JNI libraries
 if (JNI_FOUND AND JNI_LIBRARIES)
     message(STATUS "Linking JNI libraries: ${JNI_LIBRARIES}")
     target_link_libraries(HelloWorld PRIVATE ${JNI_LIBRARIES})
 else ()
     # 如果 JNI 函式庫也找不到，你可能需要手動指定或讓連結器自己想辦法（不建議）
     message(WARNING "JNI_LIBRARIES not found or JNI package not found. Linking might require manual setup or might fail.")
     # 例如: target_link_libraries(HelloWorld PRIVATE "path/to/jvm.lib")
 endif ()

 # You typically don't need to manually find JNI for Android NDK builds,
 # as the NDK toolchain configures JNI headers automatically.
 # find_package(JNI REQUIRED)
 # include_directories(${JNI_INCLUDE_DIRS})
 # target_link_libraries(HelloWorld ${JNI_LIBRARIES})
 # The above 3 lines are usually not needed for Android NDK.

 # Optional: Add include directories if your .h files are in subdirectories
 # For this simple example, HelloWorld.h and HelloWorld.cpp are in the same directory (jni/)
 # target_include_directories(HelloWorld PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})