 cmake_minimum_required(VERSION 3.10)

 # Project name (can be anything, doesn't directly affect output library name for add_library)
 project(JniDllExample CXX)



# # Add include directories for JNI
 include_directories(${JNI_INCLUDE_DIRS})



 # Add the C++ source file for the native library.
 # The library will be named "libHelloWorld.so" (or .dylib, .dll depending on platform if not Android)
 # The first argument to add_library ("HelloWorld") is what System.loadLibrary("HelloWorld") expects.
add_library(
         HelloWorld # This is the name used in System.loadLibrary("HelloWorld")
         SHARED  # Creates a shared library (DLL on Windows,/ .so file for Android)
         HelloWorld.cpp
         # If you had other .cpp or .c files, add them here
)

 # Find JNI package - this should define JNI_INCLUDE_DIRS and JNI_LIBRARIES
 # Find and link against the JNI headers and libraries (Android provided by the NDK JNI by Java)
 # For Android, log library is often useful.
 find_package(JNI)

 # Explicitly add JNI include directories to the target
 # 這是關鍵的補充/修正
 # Explicitly add JNI include directories to the target
 if(JNI_FOUND AND JNI_INCLUDE_DIRS) # 檢查 JNI 是否找到並且 include 路徑已設定
  message(STATUS "Found JNI automatically. Using JNI_INCLUDE_DIRS: ${JNI_INCLUDE_DIRS}")
  target_include_directories(HelloWorld PRIVATE ${JNI_INCLUDE_DIRS})
 else()
  message(WARNING "JNI_INCLUDE_DIRS not found by find_package(JNI) or JNI package itself not found. Attempting to use manually configured paths.")
  # 手動設定 JDK 的 include 路徑
  # 根據你的 JDK 安裝位置修改這些路徑
  set(JDK_INCLUDE_DIR "E:/JDK/jbr-17.0.14/include") # 指向 jni.h
  set(JDK_PLATFORM_INCLUDE_DIR "E:/JDK/jbr-17.0.14/include/win32") # 指向 jni_md.h (Windows 範例)

  # 檢查手動設定的路徑是否存在
  if(NOT EXISTS "${JDK_INCLUDE_DIR}/jni.h")
   message(FATAL_ERROR "Manual JDK_INCLUDE_DIR does not contain jni.h: ${JDK_INCLUDE_DIR}")
  endif()
  if(NOT EXISTS "${JDK_PLATFORM_INCLUDE_DIR}/jni_md.h") # 根據平台調整檢查的檔案
   message(FATAL_ERROR "Manual JDK_PLATFORM_INCLUDE_DIR does not contain jni_md.h (or equivalent): ${JDK_PLATFORM_INCLUDE_DIR}")
  endif()

  target_include_directories(HelloWorld PRIVATE
          "${JDK_INCLUDE_DIR}"
          "${JDK_PLATFORM_INCLUDE_DIR}"
  )
 endif()

 # Link JNI libraries
 if(JNI_FOUND AND JNI_LIBRARIES)
  target_link_libraries(HelloWorld PRIVATE ${JNI_LIBRARIES})
 else()
  # 如果 JNI 函式庫也找不到，你可能需要手動指定或讓連結器自己想辦法（不建議）
  message(WARNING "JNI_LIBRARIES not found or JNI package not found. Linking might require manual setup or might fail.")
  # 例如: target_link_libraries(HelloWorld PRIVATE "path/to/jvm.lib")
 endif()



 # 可選: 如果你的 .h 檔案在子目錄，可以新增
 # target_include_directories(HelloWorld PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
 # In Android case, the log library found with find_library.
 # if(log-lib)
 #  target_link_libraries(
 #          HelloWorld # The name of your library defined with add_library
 #          ${log-lib} # Link the log library
 #  )
 # else()
 #  message(WARNING "Android log library not found.")
 # endif()


 # You typically don't need to manually find JNI for Android NDK builds,
 # as the NDK toolchain configures JNI headers automatically.
 # find_package(JNI REQUIRED)
 # include_directories(${JNI_INCLUDE_DIRS})
 # target_link_libraries(HelloWorld ${JNI_LIBRARIES})
 # The above 3 lines are usually not needed for Android NDK.

 # Optional: Add include directories if your .h files are in subdirectories
 # For this simple example, HelloWorld.h and HelloWorld.cpp are in the same directory (jni/)
 # target_include_directories(HelloWorld PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})