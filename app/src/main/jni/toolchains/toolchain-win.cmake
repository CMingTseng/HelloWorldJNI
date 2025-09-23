# For cross-compiling to Windows
set(CMAKE_SYSTEM_NAME Windows)
set(CMAKE_SYSTEM_PROCESSOR x86_64)

# --- YOU MUST PROVIDE THE CORRECT ABSOLUTE PATH TO YOUR MinGW-w64 TOOLCHAIN ---
set(MINGW_TOOLCHAIN_ROOT "/opt/homebrew/opt/mingw-w64") # Example for Homebrew on Apple Silicon

set(CMAKE_C_COMPILER   "${MINGW_TOOLCHAIN_ROOT}/bin/x86_64-w64-mingw32-gcc")
set(CMAKE_CXX_COMPILER "${MINGW_TOOLCHAIN_ROOT}/bin/x86_64-w64-mingw32-g++")
set(CMAKE_RC_COMPILER  "${MINGW_TOOLCHAIN_ROOT}/bin/x86_64-w64-mingw32-windres")
# set(CMAKE_AR           "${MINGW_TOOLCHAIN_ROOT}/bin/x86_64-w64-mingw32-ar")
# set(CMAKE_RANLIB       "${MINGW_TOOLCHAIN_ROOT}/bin/x86_64-w64-mingw32-ranlib")

# For MinGW, CMAKE_FIND_ROOT_PATH usually points to the root of the MinGW installation
# as it contains the necessary headers and libraries for the target Windows system.
set(CMAKE_SYSROOT ${MINGW_TOOLCHAIN_ROOT}/x86_64-w64-mingw32 CACHE PATH "Sysroot for MinGW cross-compilation")
# Or sometimes just:
# set(CMAKE_SYSROOT ${MINGW_TOOLCHAIN_ROOT})
# Check your MinGW installation structure.

set(CMAKE_FIND_ROOT_PATH ${CMAKE_SYSROOT})

set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE ONLY)