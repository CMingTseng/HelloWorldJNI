# For cross-compiling to Windows
set(CMAKE_SYSTEM_NAME Windows)
set(CMAKE_SYSTEM_PROCESSOR x86_64)

# --- Determine if we are in a try_compile context ---
set(IN_TRY_COMPILE FALSE)
if(CMAKE_PROJECT_NAME STREQUAL "CMAKE_TRY_COMPILE")
    set(IN_TRY_COMPILE TRUE)
endif()

message(STATUS "--- Toolchain (toolchain-win-x86_64-by-mingw.cmake) Start --- (In TryCompile: ${IN_TRY_COMPILE}) ---")
message(STATUS "Toolchain: Current CMAKE_PROJECT_NAME is: ${CMAKE_PROJECT_NAME}")
message(STATUS "Toolchain: Current CMAKE_BINARY_DIR is: ${CMAKE_BINARY_DIR}")

# --- MinGW Root Path Logic: MUST use GRADLE_MINGW_PATH if defined and valid ---
set(MINGW_EFFECTIVE_ROOT "") # Initialize
# Default for non-Windows hosts, will be overridden or cause error if host is Windows and GRADLE_MINGW_PATH is not set
set(MINGW_TOOLCHAIN_ROOT_DEFAULT "/opt/homebrew/opt/mingw-w64")

if(DEFINED GRADLE_MINGW_PATH AND NOT GRADLE_MINGW_PATH STREQUAL "")
    message(STATUS "Toolchain: GRADLE_MINGW_PATH is DEFINED as [${GRADLE_MINGW_PATH}]")
    if(IS_DIRECTORY "${GRADLE_MINGW_PATH}")
        set(MINGW_EFFECTIVE_ROOT "${GRADLE_MINGW_PATH}")
        message(STATUS "Toolchain: Using MINGW_EFFECTIVE_ROOT from GRADLE_MINGW_PATH: [${MINGW_EFFECTIVE_ROOT}]")
    else()
        message(WARNING "Toolchain: GRADLE_MINGW_PATH ('${GRADLE_MINGW_PATH}') is not a valid directory.")
        if(NOT IN_TRY_COMPILE)
            message(FATAL_ERROR "Toolchain: CRITICAL - GRADLE_MINGW_PATH was passed by Gradle but is invalid: [${GRADLE_MINGW_PATH}]")
        endif()
    endif()
else()
    message(WARNING "Toolchain: GRADLE_MINGW_PATH was NOT defined or was empty in this context (In TryCompile: ${IN_TRY_COMPILE}).")
    if(NOT IN_TRY_COMPILE)
        # If not in try_compile and GRADLE_MINGW_PATH is missing, this is a critical configuration error from Gradle's side
        message(FATAL_ERROR "Toolchain: CRITICAL - GRADLE_MINGW_PATH was NOT passed by Gradle for main configuration.")
    endif()
endif()

# Fallback if MINGW_EFFECTIVE_ROOT is still empty (e.g., in try_compile where GRADLE_MINGW_PATH wasn't passed,
# or if it was invalid and we are in try_compile).
if(MINGW_EFFECTIVE_ROOT STREQUAL "")
    message(WARNING "Toolchain: MINGW_EFFECTIVE_ROOT is empty. Falling back to MINGW_TOOLCHAIN_ROOT_DEFAULT: [${MINGW_TOOLCHAIN_ROOT_DEFAULT}] (Context: In TryCompile=${IN_TRY_COMPILE})")
    if(IS_DIRECTORY "${MINGW_TOOLCHAIN_ROOT_DEFAULT}")
        set(MINGW_EFFECTIVE_ROOT "${MINGW_TOOLCHAIN_ROOT_DEFAULT}")
    else()
        # Avoid fatal error if this default path is for a different OS during try_compile on Windows host
        if(NOT (CMAKE_HOST_WIN32 AND IN_TRY_COMPILE AND NOT IS_DIRECTORY "${MINGW_TOOLCHAIN_ROOT_DEFAULT}") ) # Corrected variable name
            message(FATAL_ERROR "Toolchain: CRITICAL - Fallback MINGW_TOOLCHAIN_ROOT_DEFAULT ('${MINGW_TOOLCHAIN_ROOT_DEFAULT}') is also not a valid directory. Cannot proceed.")
        else()
            message(WARNING "Toolchain: Fallback MINGW_TOOLCHAIN_ROOT_DEFAULT is not valid on this Windows host during try_compile, but will let CMake try to find compilers itself if possible.")
            # Let CMake try to find compilers if MINGW_EFFECTIVE_ROOT remains empty in this specific case
        endif()
    endif()
endif()

message(STATUS "Toolchain: Final MINGW_EFFECTIVE_ROOT for toolchain setup: [${MINGW_EFFECTIVE_ROOT}] (In TryCompile: ${IN_TRY_COMPILE})")

# --- Set ALL necessary variables based on MINGW_EFFECTIVE_ROOT ---
# Only proceed if MINGW_EFFECTIVE_ROOT is valid and non-empty
if(NOT MINGW_EFFECTIVE_ROOT STREQUAL "" AND IS_DIRECTORY "${MINGW_EFFECTIVE_ROOT}")
    set(TOOL_EXTENSION "")
    # --- MODIFICATION START for .exe extension logic ---
    # The .exe extension applies if the HOST system (where CMake is running) is Windows.
    # MinGW compilers on macOS/Linux are native executables for those hosts, not .exe files.
    if(CMAKE_HOST_WIN32)
        set(TOOL_EXTENSION ".exe")
        message(STATUS "Toolchain: Host is Windows, TOOL_EXTENSION set to '.exe'.")
    else()
        message(STATUS "Toolchain: Host is NOT Windows, TOOL_EXTENSION remains empty.")
    endif()
    # --- MODIFICATION END ---

    set(CMAKE_C_COMPILER   "${MINGW_EFFECTIVE_ROOT}/bin/x86_64-w64-mingw32-gcc${TOOL_EXTENSION}" CACHE FILEPATH "C compiler" FORCE)
    set(CMAKE_CXX_COMPILER "${MINGW_EFFECTIVE_ROOT}/bin/x86_64-w64-mingw32-g++${TOOL_EXTENSION}" CACHE FILEPATH "C++ compiler" FORCE)

    # Handle RC compiler name variations
    set(RC_COMPILER_PRIMARY "${MINGW_EFFECTIVE_ROOT}/bin/x86_64-w64-mingw32-windres${TOOL_EXTENSION}")
    set(RC_COMPILER_FALLBACK "${MINGW_EFFECTIVE_ROOT}/bin/windres${TOOL_EXTENSION}")

    if(EXISTS "${RC_COMPILER_PRIMARY}")
        set(CMAKE_RC_COMPILER  "${RC_COMPILER_PRIMARY}" CACHE FILEPATH "Resource compiler" FORCE)
    elseif(EXISTS "${RC_COMPILER_FALLBACK}")
        set(CMAKE_RC_COMPILER  "${RC_COMPILER_FALLBACK}" CACHE FILEPATH "Resource compiler" FORCE)
        message(STATUS "Toolchain: Using fallback RC compiler name: windres${TOOL_EXTENSION}")
    else()
        message(WARNING "Toolchain: Neither primary ('x86_64-w64-mingw32-windres${TOOL_EXTENSION}') nor fallback ('windres${TOOL_EXTENSION}') RC compiler found in [${MINGW_EFFECTIVE_ROOT}/bin]. Unsetting CMAKE_RC_COMPILER.")
        unset(CMAKE_RC_COMPILER CACHE)
    endif()

    message(STATUS "Toolchain: Set CMAKE_C_COMPILER to [${CMAKE_C_COMPILER}] using MINGW_EFFECTIVE_ROOT.")
    message(STATUS "Toolchain: Set CMAKE_CXX_COMPILER to [${CMAKE_CXX_COMPILER}] using MINGW_EFFECTIVE_ROOT.")
    if(DEFINED CMAKE_RC_COMPILER)
        message(STATUS "Toolchain: Set CMAKE_RC_COMPILER to [${CMAKE_RC_COMPILER}] using MINGW_EFFECTIVE_ROOT.")
    else()
        message(WARNING "Toolchain: CMAKE_RC_COMPILER could not be set.")
    endif()

    # Verify compilers exist
    if(NOT EXISTS "${CMAKE_C_COMPILER}")
        message(FATAL_ERROR "Toolchain: CMAKE_C_COMPILER set by toolchain does not exist: [${CMAKE_C_COMPILER}]")
    endif()
    if(NOT EXISTS "${CMAKE_CXX_COMPILER}")
        message(FATAL_ERROR "Toolchain: CMAKE_CXX_COMPILER set by toolchain does not exist: [${CMAKE_CXX_COMPILER}]")
    endif()
    if(DEFINED CMAKE_RC_COMPILER AND NOT EXISTS "${CMAKE_RC_COMPILER}") # Check after it might have been set
        message(WARNING "Toolchain: CMAKE_RC_COMPILER was set to [${CMAKE_RC_COMPILER}] but the file does not exist.")
    endif()

    # Set CMAKE_SYSROOT
    set(CMAKE_SYSROOT "${MINGW_EFFECTIVE_ROOT}" CACHE PATH "System root for cross-compilation" FORCE)
    message(STATUS "Toolchain: Set CMAKE_SYSROOT to [${CMAKE_SYSROOT}] using MINGW_EFFECTIVE_ROOT.")
    if(NOT IS_DIRECTORY "${CMAKE_SYSROOT}")
        message(FATAL_ERROR "Toolchain: CMAKE_SYSROOT set by toolchain is not a valid directory: [${CMAKE_SYSROOT}]")
    endif()

    # Set CMAKE_FIND_ROOT_PATH and MODES
    set(CMAKE_FIND_ROOT_PATH "${CMAKE_SYSROOT}" CACHE PATH "Root path for find commands" FORCE)
    message(STATUS "Toolchain: Set CMAKE_FIND_ROOT_PATH to [${CMAKE_FIND_ROOT_PATH}] (same as CMAKE_SYSROOT).")

    set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER CACHE STRING "Find program mode" FORCE)
    set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY CACHE STRING "Find library mode" FORCE)
    set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY CACHE STRING "Find include mode" FORCE)
    set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE ONLY CACHE STRING "Find package mode" FORCE)
    message(STATUS "Toolchain: Set CMAKE_FIND_ROOT_PATH_MODE_* variables (PROGRAM=NEVER, others=ONLY).")

else()
    # This branch is taken if MINGW_EFFECTIVE_ROOT could not be determined or is not a directory.
    # This might happen in try_compile on Windows if GRADLE_MINGW_PATH is not passed and the default is non-Windows.
    if(NOT (IN_TRY_COMPILE AND MINGW_EFFECTIVE_ROOT STREQUAL "")) # Avoid fatal error if MINGW_EFFECTIVE_ROOT is empty during try_compile due to default path mismatch
        message(FATAL_ERROR "Toolchain: CRITICAL - MINGW_EFFECTIVE_ROOT ('${MINGW_EFFECTIVE_ROOT}') is invalid or could not be determined. Cannot set up toolchain components.")
    else()
        message(WARNING "Toolchain: MINGW_EFFECTIVE_ROOT is invalid or empty during try_compile. CMake will attempt to find compilers itself if possible (e.g., if already cached or passed via -D by Gradle).")
    endif()
endif()

message(STATUS "--- Toolchain (toolchain-win-x86_64-by-mingw.cmake) End --- (In TryCompile: ${IN_TRY_COMPILE}) ---")



## --- YOU MUST PROVIDE THE CORRECT ABSOLUTE PATH TO YOUR MinGW-w64 TOOLCHAIN ---
#set(MINGW_TOOLCHAIN_ROOT "/opt/homebrew/opt/mingw-w64") # Example for Homebrew on Apple Silicon
#set(CMAKE_C_COMPILER   "${MINGW_TOOLCHAIN_ROOT}/bin/x86_64-w64-mingw32-gcc")
#set(CMAKE_CXX_COMPILER "${MINGW_TOOLCHAIN_ROOT}/bin/x86_64-w64-mingw32-g++")
#set(CMAKE_RC_COMPILER  "${MINGW_TOOLCHAIN_ROOT}/bin/x86_64-w64-mingw32-windres")
## set(CMAKE_AR           "${MINGW_TOOLCHAIN_ROOT}/bin/x86_64-w64-mingw32-ar")
## set(CMAKE_RANLIB       "${MINGW_TOOLCHAIN_ROOT}/bin/x86_64-w64-mingw32-ranlib")
#
## For MinGW, CMAKE_FIND_ROOT_PATH usually points to the root of the MinGW installation
## as it contains the necessary headers and libraries for the target Windows system.
#set(CMAKE_SYSROOT ${MINGW_TOOLCHAIN_ROOT}/x86_64-w64-mingw32 CACHE PATH "Sysroot for MinGW cross-compilation")
## Or sometimes just:
## set(CMAKE_SYSROOT ${MINGW_TOOLCHAIN_ROOT})
## Check your MinGW installation structure.
#
#set(CMAKE_FIND_ROOT_PATH ${CMAKE_SYSROOT})
#
#set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
#set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
#set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
#set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE ONLY)