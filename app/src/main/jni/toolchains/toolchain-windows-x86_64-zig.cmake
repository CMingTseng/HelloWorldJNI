set(CMAKE_SYSTEM_NAME Windows)
set(CMAKE_SYSTEM_PROCESSOR x86_64)

# --- MODIFICATION START: Make the toolchain generic and robust ---

# --- Variables expected from Gradle ---
# GRADLE_ZIG_EXECUTABLE   (Required: Name or absolute path to the 'zig' executable)

message(STATUS "--- Toolchain (toolchain-windows-x86_64-zig.cmake) Start ---")

# --- 1. Get Zig executable path from Gradle ---
set(EFFECTIVE_ZIG_EXECUTABLE "")
if(DEFINED GRADLE_ZIG_EXECUTABLE AND NOT GRADLE_ZIG_EXECUTABLE STREQUAL "")
    if(IS_ABSOLUTE "${GRADLE_ZIG_EXECUTABLE}")
        set(EFFECTIVE_ZIG_EXECUTABLE "${GRADLE_ZIG_EXECUTABLE}")
        message(STATUS "[toolchain] Using Zig executable from Gradle (absolute path): [${EFFECTIVE_ZIG_EXECUTABLE}]")
    else()
        # If not absolute, assume it's a command name in PATH.
        set(EFFECTIVE_ZIG_EXECUTABLE "${GRADLE_ZIG_EXECUTABLE}")
        message(STATUS "[toolchain] Using Zig command name from Gradle (relying on PATH): [${EFFECTIVE_ZIG_EXECUTABLE}]")
    endif()
    # --- Backwards compatibility for old variable names (GRADLE_ZIG_C_COMPILER_LIST) ---
elseif(DEFINED GRADLE_ZIG_C_COMPILER_LIST AND NOT GRADLE_ZIG_C_COMPILER_LIST STREQUAL "")
    # Extract the executable part from the old list format "path/to/zig;cc"
    string(REPLACE ";cc" "" LEGACY_ZIG_EXECUTABLE "${GRADLE_ZIG_C_COMPILER_LIST}")
    set(EFFECTIVE_ZIG_EXECUTABLE "${LEGACY_ZIG_EXECUTABLE}")
    message(WARNING "[toolchain] Using legacy GRADLE_ZIG_C_COMPILER_LIST. Please switch to GRADLE_ZIG_EXECUTABLE. Inferred executable: [${EFFECTIVE_ZIG_EXECUTABLE}]")
else()
    # Fallback if no variable is provided
    set(EFFECTIVE_ZIG_EXECUTABLE "zig")
    message(WARNING "[toolchain] GRADLE_ZIG_EXECUTABLE not defined. Falling back to 'zig' in PATH.")
endif()

# --- 2. Set the compilers as a CMake list (e.g., "/path/to/zig;cc") ---
set(CMAKE_C_COMPILER   "${EFFECTIVE_ZIG_EXECUTABLE};cc"   CACHE STRING "C compiler (Zig)"   FORCE)
set(CMAKE_CXX_COMPILER "${EFFECTIVE_ZIG_EXECUTABLE};c++" CACHE STRING "C++ compiler (Zig)" FORCE)

message(STATUS "[toolchain] Final CMAKE_C_COMPILER list: ${CMAKE_C_COMPILER}")
message(STATUS "[toolchain] Final CMAKE_CXX_COMPILER list: ${CMAKE_CXX_COMPILER}")

# --- 3. Set the cross-compilation target ---
set(target_triple "x86_64-windows-gnu") # Or x86_64-windows-msvc if you prefer
set(CMAKE_C_COMPILER_TARGET   "${target_triple}" CACHE STRING "C cross-compilation target"   FORCE)
set(CMAKE_CXX_COMPILER_TARGET "${target_triple}" CACHE STRING "C++ cross-compilation target" FORCE)
message(STATUS "[toolchain] Set compiler target to: ${target_triple}")

# --- 4. Standard toolchain settings ---
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_TRY_COMPILE_TARGET_TYPE STATIC_LIBRARY)


message(STATUS "[toolchain] Toolchain for Windows x86_64 (using Zig) configured successfully.")
message(STATUS "--- Toolchain (toolchain-windows-x86_64-zig.cmake) End ---")

# --- MODIFICATION END ---
