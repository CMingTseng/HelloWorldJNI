# For cross-compiling to Linux
set(CMAKE_SYSTEM_NAME Linux)
set(CMAKE_SYSTEM_PROCESSOR x86_64) # Explicitly set architecture

# --- YOU MUST PROVIDE THE CORRECT ABSOLUTE PATH TO YOUR LINUX CROSS-COMPILER TOOLCHAIN ---
# Example: If installed via Homebrew on Apple Silicon, it might be something like:
set(LINUX_CROSS_TOOLCHAIN_ROOT "/opt/homebrew/opt/x86_64-unknown-linux-gnu")
# Or for Intel Macs via Homebrew:
# set(LINUX_CROSS_TOOLCHAIN_ROOT "/usr/local/opt/x86_64-unknown-linux-gnu")
# Or wherever your specific toolchain is installed.

set(CMAKE_C_COMPILER   "${LINUX_CROSS_TOOLCHAIN_ROOT}/bin/x86_64-unknown-linux-gnu-gcc")
set(CMAKE_CXX_COMPILER "${LINUX_CROSS_TOOLCHAIN_ROOT}/bin/x86_64-unknown-linux-gnu-g++")
# Optionally set other tools if needed by your build
# set(CMAKE_AR           "${LINUX_CROSS_TOOLCHAIN_ROOT}/bin/x86_64-unknown-linux-gnu-ar")
# set(CMAKE_RANLIB       "${LINUX_CROSS_TOOLCHAIN_ROOT}/bin/x86_64-unknown-linux-gnu-ranlib")
# set(CMAKE_LINKER       "${LINUX_CROSS_TOOLCHAIN_ROOT}/bin/x86_64-unknown-linux-gnu-ld")

# The CMAKE_FIND_ROOT_PATH should point to the sysroot for the target Linux system.
# This contains headers and libraries needed for cross-compilation.
# Often, the toolchain includes its own sysroot.
set(CMAKE_SYSROOT "${LINUX_CROSS_TOOLCHAIN_ROOT}/x86_64-unknown-linux-gnu/sysroot" CACHE PATH "Sysroot for Linux cross-compilation")
# If the sysroot is directly under LINUX_CROSS_TOOLCHAIN_ROOT, adjust accordingly:
# set(CMAKE_SYSROOT "${LINUX_CROSS_TOOLCHAIN_ROOT}/sysroot")
# Or if your toolchain names the sysroot directory differently.
# Ensure this path exists and contains usr/include, usr/lib etc. for the target.

set(CMAKE_FIND_ROOT_PATH ${CMAKE_SYSROOT})

set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE ONLY)