set(CMAKE_SYSTEM_NAME Linux)
set(CMAKE_SYSTEM_PROCESSOR x86_64)

# --- MODIFICATION START: Make the toolchain generic ---

# --- Variables expected from Gradle ---
# GRADLE_ZIG_EXECUTABLE   (Required: Absolute path to the 'zig' executable, found by 'which')

message(STATUS "--- Toolchain (toolchain-linux-x86_64-by-zig.cmake) Start ---")

# --- 1. Get Zig executable path from Gradle ---
set(EFFECTIVE_ZIG_EXECUTABLE "")
if(DEFINED GRADLE_ZIG_EXECUTABLE AND NOT GRADLE_ZIG_EXECUTABLE STREQUAL "")
    if(IS_ABSOLUTE "${GRADLE_ZIG_EXECUTABLE}")
        set(EFFECTIVE_ZIG_EXECUTABLE "${GRADLE_ZIG_EXECUTABLE}")
        message(STATUS "[toolchain] Using Zig executable from Gradle: [${EFFECTIVE_ZIG_EXECUTABLE}]")
    else()
        # If not absolute, assume it's in PATH. 'zig' is sufficient.
        set(EFFECTIVE_ZIG_EXECUTABLE "${GRADLE_ZIG_EXECUTABLE}")
        message(STATUS "[toolchain] Using Zig command name from Gradle: [${EFFECTIVE_ZIG_EXECUTABLE}]")
    endif()
else()
    # Fallback to just "zig" if Gradle doesn't provide it, relying on PATH.
    set(EFFECTIVE_ZIG_EXECUTABLE "zig")
    message(WARNING "[toolchain] GRADLE_ZIG_EXECUTABLE not defined. Falling back to 'zig' in PATH.")
endif()


# --- 2. Set the compilers as a CMake list (e.g., "/path/to/zig;cc") ---
# CMake correctly interprets the semicolon as a list separator for the command.
set(CMAKE_C_COMPILER   "${EFFECTIVE_ZIG_EXECUTABLE};cc"   CACHE STRING "C compiler (Zig)"   FORCE)
set(CMAKE_CXX_COMPILER "${EFFECTIVE_ZIG_EXECUTABLE};c++" CACHE STRING "C++ compiler (Zig)" FORCE)

message(STATUS "[toolchain] Final CMAKE_C_COMPILER list: ${CMAKE_C_COMPILER}")
message(STATUS "[toolchain] Final CMAKE_CXX_COMPILER list: ${CMAKE_CXX_COMPILER}")


# --- 3. Set the cross-compilation target ---
# This is passed as an argument to 'zig cc' and 'zig c++'.
set(target_triple "x86_64-linux-gnu") # Or x86_64-linux-musl if you prefer
set(CMAKE_C_COMPILER_TARGET   "${target_triple}" CACHE STRING "C cross-compilation target"   FORCE)
set(CMAKE_CXX_COMPILER_TARGET "${target_triple}" CACHE STRING "C++ cross-compilation target" FORCE)
message(STATUS "[toolchain] Set compiler target to: ${target_triple}")


# --- 4. Standard toolchain settings ---
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_TRY_COMPILE_TARGET_TYPE STATIC_LIBRARY)
set(CMAKE_CROSSCOMPILING TRUE)

message(STATUS "[toolchain] Toolchain for Linux x86_64 (using Zig) configured successfully.")
message(STATUS "--- Toolchain (toolchain-linux-x86_64-by-zig.cmake) End ---")

# --- MODIFICATION END ---
