# Generic Linux Toolchain file for cross-compilation (handles GNU and MUSL via Gradle params)
set(CMAKE_SYSTEM_NAME Linux)
set(CMAKE_SYSTEM_PROCESSOR x86_64) # Explicitly set architecture

# --- Determine if we are in a try_compile context ---
set(IN_TRY_COMPILE FALSE)
if(CMAKE_PROJECT_NAME STREQUAL "CMAKE_TRY_COMPILE")
    set(IN_TRY_COMPILE TRUE)
endif()

message(STATUS "--- Toolchain (toolchain-linux.cmake) Start --- (In TryCompile: ${IN_TRY_COMPILE}) ---")
message(STATUS "Toolchain: CMAKE_PROJECT_NAME: ${CMAKE_PROJECT_NAME}, CMAKE_BINARY_DIR: ${CMAKE_BINARY_DIR}")

# --- Variables expected from Gradle (non-cache, toolchain will set actual CMake CACHE vars) ---
# GRADLE_C_COMPILER         (Required: Name or absolute path of C compiler)
# GRADLE_CXX_COMPILER       (Required: Name or absolute path of CXX compiler)
# GRADLE_LINUX_CROSS_TOOLCHAIN_ROOT (Optional: Absolute path to the toolchain installation root, mainly for GNU to find sysroot)
# GRADLE_AR                 (Optional: Name or absolute path of ar)
# GRADLE_STRIP              (Optional: Name or absolute path of strip)

# --- Initialize effective variables ---
set(EFFECTIVE_C_COMPILER "")
set(EFFECTIVE_CXX_COMPILER "")
set(EFFECTIVE_AR "")
set(EFFECTIVE_STRIP "")
set(EFFECTIVE_TOOLCHAIN_ROOT "")


# --- 1. Determine Compilers (from Gradle first) ---
if(DEFINED GRADLE_C_COMPILER AND NOT GRADLE_C_COMPILER STREQUAL "")
    set(EFFECTIVE_C_COMPILER "${GRADLE_C_COMPILER}")
    message(STATUS "Toolchain: Using C_COMPILER hint from GRADLE_C_COMPILER: [${EFFECTIVE_C_COMPILER}]")
else()
    if(NOT IN_TRY_COMPILE)
        message(FATAL_ERROR "Toolchain: GRADLE_C_COMPILER was not defined by Gradle. This is required for main configuration.")
    else()
        message(WARNING "Toolchain (try_compile): GRADLE_C_COMPILER not defined. Relying on CMake to find/use cached C compiler.")
        # In try_compile, if not defined, we let CMake try to use cached CMAKE_C_COMPILER if set by a previous -D or main config.
    endif()
endif()

if(DEFINED GRADLE_CXX_COMPILER AND NOT GRADLE_CXX_COMPILER STREQUAL "")
    set(EFFECTIVE_CXX_COMPILER "${GRADLE_CXX_COMPILER}")
    message(STATUS "Toolchain: Using CXX_COMPILER hint from GRADLE_CXX_COMPILER: [${EFFECTIVE_CXX_COMPILER}]")
else()
    if(NOT IN_TRY_COMPILE)
        message(FATAL_ERROR "Toolchain: GRADLE_CXX_COMPILER was not defined by Gradle. This is required for main configuration.")
    else()
        message(WARNING "Toolchain (try_compile): GRADLE_CXX_COMPILER not defined. Relying on CMake to find/use cached CXX compiler.")
    endif()
endif()

# Set actual CMake compiler variables (use CACHE with FORCE)
# CMake will search in PATH if non-absolute names are given.
if(NOT EFFECTIVE_C_COMPILER STREQUAL "")
    set(CMAKE_C_COMPILER "${EFFECTIVE_C_COMPILER}" CACHE FILEPATH "C compiler for Linux" FORCE)
    message(STATUS "Toolchain: Set CMAKE_C_COMPILER to [${CMAKE_C_COMPILER}]")
    if(IS_ABSOLUTE "${CMAKE_C_COMPILER}" AND NOT EXISTS "${CMAKE_C_COMPILER}")
        message(WARNING "Toolchain: Absolute CMAKE_C_COMPILER [${CMAKE_C_COMPILER}] does not exist!") # Warning instead of FATAL, CMake will fail later if truly missing
    endif()
else()
    # This branch is mainly for IN_TRY_COMPILE if Gradle didn't pass compiler hints
    if(NOT CMAKE_C_COMPILER AND IN_TRY_COMPILE)
        message(WARNING "Toolchain (try_compile): CMAKE_C_COMPILER not set by Gradle hint and not already cached. Compiler detection might fail.")
    elseif(CMAKE_C_COMPILER)
        message(STATUS "Toolchain (try_compile): Using already set/cached CMAKE_C_COMPILER: [${CMAKE_C_COMPILER}]")
    endif()
endif()

if(NOT EFFECTIVE_CXX_COMPILER STREQUAL "")
    set(CMAKE_CXX_COMPILER "${EFFECTIVE_CXX_COMPILER}" CACHE FILEPATH "C++ compiler for Linux" FORCE)
    message(STATUS "Toolchain: Set CMAKE_CXX_COMPILER to [${CMAKE_CXX_COMPILER}]")
    if(IS_ABSOLUTE "${CMAKE_CXX_COMPILER}" AND NOT EXISTS "${CMAKE_CXX_COMPILER}")
        message(WARNING "Toolchain: Absolute CMAKE_CXX_COMPILER [${CMAKE_CXX_COMPILER}] does not exist!")
    endif()
else()
    if(NOT CMAKE_CXX_COMPILER AND IN_TRY_COMPILE)
        message(WARNING "Toolchain (try_compile): CMAKE_CXX_COMPILER not set by Gradle hint and not already cached. Compiler detection might fail.")
    elseif(CMAKE_CXX_COMPILER)
        message(STATUS "Toolchain (try_compile): Using already set/cached CMAKE_CXX_COMPILER: [${CMAKE_CXX_COMPILER}]")
    endif()
endif()


# --- 2. Determine Toolchain Root (from Gradle, if provided) ---
if(DEFINED GRADLE_LINUX_CROSS_TOOLCHAIN_ROOT AND NOT GRADLE_LINUX_CROSS_TOOLCHAIN_ROOT STREQUAL "")
    if(IS_DIRECTORY "${GRADLE_LINUX_CROSS_TOOLCHAIN_ROOT}")
        set(EFFECTIVE_TOOLCHAIN_ROOT "${GRADLE_LINUX_CROSS_TOOLCHAIN_ROOT}")
        message(STATUS "Toolchain: Using EFFECTIVE_TOOLCHAIN_ROOT from GRADLE_LINUX_CROSS_TOOLCHAIN_ROOT: [${EFFECTIVE_TOOLCHAIN_ROOT}]")
    else()
        message(WARNING "Toolchain: GRADLE_LINUX_CROSS_TOOLCHAIN_ROOT ('${GRADLE_LINUX_CROSS_TOOLCHAIN_ROOT}') is not a valid directory. Sysroot setup might be affected.")
        if(NOT IN_TRY_COMPILE)
            # If path from Gradle is invalid in main config, it's a significant issue.
            # Depending on strictness, could be FATAL_ERROR.
        endif()
    endif()
else()
    message(STATUS "Toolchain: GRADLE_LINUX_CROSS_TOOLCHAIN_ROOT was not defined by Gradle (In TryCompile: ${IN_TRY_COMPILE}). This is expected for MUSL-from-PATH or if compilers are self-contained.")
endif()


# --- 3. Set Sysroot and Find Root Path (Crucial for cross-compilation) ---
# This logic needs to be robust for both GNU (where root is known) and MUSL (where root might not be, relying on compiler)

if(NOT EFFECTIVE_TOOLCHAIN_ROOT STREQUAL "")
    # Case 1: GNU-like toolchain where root is provided and a sysroot subdirectory is expected.
    # (Example: Homebrew's x86_64-unknown-linux-gnu often has .../x86_64-unknown-linux-gnu/sysroot)
    string(FIND "${EFFECTIVE_C_COMPILER}" "unknown-linux-gnu" IS_GNU_GCC_PATH_CHECK) # Heuristic for GNU

    set(POTENTIAL_SYSROOT "")
    if(IS_GNU_GCC_PATH_CHECK GREATER -1 OR DEFINED GRADLE_ASSUME_GNU_SYSROOT_STRUCTURE) # Check if it looks like GNU, or Gradle gives a hint
        # Try specific GNU sysroot structure
        get_filename_component(TOOLCHAIN_BIN_DIR "${CMAKE_C_COMPILER}" DIRECTORY)
        get_filename_component(TOOLCHAIN_PREFIX_GUESS "${TOOLCHAIN_BIN_DIR}/.." ABSOLUTE) # Guess prefix from compiler path

        # Heuristic for Homebrew GNU: <prefix>/<target_triple>/sysroot
        if(EXISTS "${EFFECTIVE_TOOLCHAIN_ROOT}/${CMAKE_SYSTEM_PROCESSOR}-unknown-linux-gnu/sysroot")
            set(POTENTIAL_SYSROOT "${EFFECTIVE_TOOLCHAIN_ROOT}/${CMAKE_SYSTEM_PROCESSOR}-unknown-linux-gnu/sysroot")
        elseif(EXISTS "${TOOLCHAIN_PREFIX_GUESS}/${CMAKE_SYSTEM_PROCESSOR}-unknown-linux-gnu/sysroot") # If EFFECTIVE_TOOLCHAIN_ROOT was not the actual prefix
            set(POTENTIAL_SYSROOT "${TOOLCHAIN_PREFIX_GUESS}/${CMAKE_SYSTEM_PROCESSOR}-unknown-linux-gnu/sysroot")
        elseif(EXISTS "${EFFECTIVE_TOOLCHAIN_ROOT}/sysroot") # Generic sysroot subdir
            set(POTENTIAL_SYSROOT "${EFFECTIVE_TOOLCHAIN_ROOT}/sysroot")
        elseif(IS_DIRECTORY "${EFFECTIVE_TOOLCHAIN_ROOT}/usr/include" AND IS_DIRECTORY "${EFFECTIVE_TOOLCHAIN_ROOT}/usr/lib") # If root itself looks like a sysroot
            set(POTENTIAL_SYSROOT "${EFFECTIVE_TOOLCHAIN_ROOT}")
        else()
            message(WARNING "Toolchain: Could not identify a clear sysroot structure within EFFECTIVE_TOOLCHAIN_ROOT [${EFFECTIVE_TOOLCHAIN_ROOT}] for a presumed GNU-like toolchain.")
        endif()
    else()
        # Non-GNU or unknown structure, maybe try generic sysroot subdir or the root itself
        if(EXISTS "${EFFECTIVE_TOOLCHAIN_ROOT}/sysroot")
            set(POTENTIAL_SYSROOT "${EFFECTIVE_TOOLCHAIN_ROOT}/sysroot")
        elseif(IS_DIRECTORY "${EFFECTIVE_TOOLCHAIN_ROOT}/usr/include" AND IS_DIRECTORY "${EFFECTIVE_TOOLCHAIN_ROOT}/usr/lib")
            set(POTENTIAL_SYSROOT "${EFFECTIVE_TOOLCHAIN_ROOT}")
        endif()
    endif()

    if(NOT POTENTIAL_SYSROOT STREQUAL "" AND IS_DIRECTORY "${POTENTIAL_SYSROOT}")
        set(CMAKE_SYSROOT "${POTENTIAL_SYSROOT}" CACHE PATH "System root for cross-compilation" FORCE)
        message(STATUS "Toolchain: Set CMAKE_SYSROOT to [${CMAKE_SYSROOT}]")
    else()
        message(WARNING "Toolchain: CMAKE_SYSROOT could not be determined from EFFECTIVE_TOOLCHAIN_ROOT [${EFFECTIVE_TOOLCHAIN_ROOT}]. Relying on compiler's default sysroot search.")
    endif()
else()
    # Case 2: No EFFECTIVE_TOOLCHAIN_ROOT provided (e.g., MUSL compilers found directly in PATH).
    # In this scenario, we typically rely on the compiler's built-in knowledge of its sysroot.
    # So, we do NOT explicitly set CMAKE_SYSROOT from the toolchain file.
    # CMake might still pick up a cached CMAKE_SYSROOT if set by a previous -D argument from Gradle,
    # but this toolchain file won't force one if no root is given.
    message(STATUS "Toolchain: EFFECTIVE_TOOLCHAIN_ROOT is not set. CMAKE_SYSROOT will not be set by this toolchain file. This is expected for self-contained compilers (e.g., MUSL from PATH).")
    if(CMAKE_SYSROOT) # Check if it was cached or passed by Gradle directly
        message(STATUS "Toolchain: Note - CMAKE_SYSROOT is already defined/cached as [${CMAKE_SYSROOT}]. This toolchain file will not override it without a toolchain root path.")
    endif()
endif()

# Set CMAKE_FIND_ROOT_PATH based on CMAKE_SYSROOT if it's defined
if(DEFINED CMAKE_SYSROOT AND IS_DIRECTORY "${CMAKE_SYSROOT}")
    set(CMAKE_FIND_ROOT_PATH "${CMAKE_SYSROOT}" CACHE PATH "Root for find commands" FORCE)
    message(STATUS "Toolchain: Set CMAKE_FIND_ROOT_PATH to [${CMAKE_FIND_ROOT_PATH}]")
else()
    # If CMAKE_SYSROOT is not set (e.g. for MUSL from PATH), CMAKE_FIND_ROOT_PATH should also not be forced here.
    # CMake's default behavior will apply. For cross-compiling, this might mean it searches host paths,
    # which is generally undesirable unless the compiler is truly self-contained.
    message(WARNING "Toolchain: CMAKE_SYSROOT is not defined or invalid. CMAKE_FIND_ROOT_PATH will not be forced by this toolchain. CMake default find behavior applies.")
    if(CMAKE_FIND_ROOT_PATH) # If it was cached/passed by Gradle
        message(STATUS "Toolchain: Note - CMAKE_FIND_ROOT_PATH is already defined/cached as [${CMAKE_FIND_ROOT_PATH}]. This toolchain file will not override it without CMAKE_SYSROOT.")
    endif()
endif()

# These modes are generally good for cross-compilation, regardless of CMAKE_FIND_ROOT_PATH being explicitly set or not.
# If CMAKE_FIND_ROOT_PATH is empty, these modes might have less effect or apply to CMake's default search roots.
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER CACHE STRING "Program find mode" FORCE)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY CACHE STRING "Library find mode" FORCE)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY CACHE STRING "Include find mode" FORCE)
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE ONLY CACHE STRING "Package find mode" FORCE)


# --- 4. Set Optional Tools (AR, STRIP) ---
if(DEFINED GRADLE_AR AND NOT GRADLE_AR STREQUAL "")
    set(EFFECTIVE_AR "${GRADLE_AR}")
    set(CMAKE_AR "${EFFECTIVE_AR}" CACHE FILEPATH "Archiver" FORCE)
    message(STATUS "Toolchain: Set CMAKE_AR to [${CMAKE_AR}] from GRADLE_AR.")
elseif(NOT CMAKE_AR AND IN_TRY_COMPILE) # 如果在 try_compile 中，GRADLE_AR 未定义，且 CMAKE_AR 也尚未缓存
    message(WARNING "Toolchain (try_compile): GRADLE_AR not defined and CMAKE_AR not cached. CMake will try to find 'ar'.")
elseif(CMAKE_AR AND IN_TRY_COMPILE) # 如果在 try_compile 中，CMAKE_AR 已被缓存
    message(STATUS "Toolchain (try_compile): Using already set/cached CMAKE_AR: [${CMAKE_AR}]")
elseif(NOT CMAKE_AR AND NOT IN_TRY_COMPILE) # 如果在主配置中，GRADLE_AR 未定义，CMAKE_AR 也未缓存 (这种情况一般不期望，因为 AR 通常是编译器套件一部分)
    message(WARNING "Toolchain (main config): GRADLE_AR not defined and CMAKE_AR not set. CMake will try to find 'ar'.")
    # else: CMAKE_AR is set (not IN_TRY_COMPILE and CMAKE_AR already set, or from GRADLE_AR) - no message needed, or could add one
endif()

if(DEFINED GRADLE_STRIP AND NOT GRADLE_STRIP STREQUAL "")
    set(EFFECTIVE_STRIP "${GRADLE_STRIP}")
    set(CMAKE_STRIP "${EFFECTIVE_STRIP}" CACHE FILEPATH "Symbol Strip Utility" FORCE)
    message(STATUS "Toolchain: Set CMAKE_STRIP to [${CMAKE_STRIP}] from GRADLE_STRIP.")
elseif(NOT CMAKE_STRIP AND IN_TRY_COMPILE)
    message(WARNING "Toolchain (try_compile): GRADLE_STRIP not defined and CMAKE_STRIP not cached. CMake will try to find 'strip'.")
elseif(CMAKE_STRIP AND IN_TRY_COMPILE)
    message(STATUS "Toolchain (try_compile): Using already set/cached CMAKE_STRIP: [${CMAKE_STRIP}]")
elseif(NOT CMAKE_STRIP AND NOT IN_TRY_COMPILE)
    message(WARNING "Toolchain (main config): GRADLE_STRIP not defined and CMAKE_STRIP not set. CMake will try to find 'strip'.")
endif()

message(STATUS "--- Toolchain (toolchain-linux.cmake) Configuration Finished --- (In TryCompile: ${IN_TRY_COMPILE}) ---")

## --- YOU MUST PROVIDE THE CORRECT ABSOLUTE PATH TO YOUR LINUX CROSS-COMPILER TOOLCHAIN ---
## Example: If installed via Homebrew on Apple Silicon, it might be something like:
#set(LINUX_CROSS_TOOLCHAIN_ROOT "/opt/homebrew/opt/x86_64-unknown-linux-gnu")
## Or for Intel Macs via Homebrew:
## set(LINUX_CROSS_TOOLCHAIN_ROOT "/usr/local/opt/x86_64-unknown-linux-gnu")
## Or wherever your specific toolchain is installed.
#
#set(CMAKE_C_COMPILER   "${LINUX_CROSS_TOOLCHAIN_ROOT}/bin/x86_64-unknown-linux-gnu-gcc")
#set(CMAKE_CXX_COMPILER "${LINUX_CROSS_TOOLCHAIN_ROOT}/bin/x86_64-unknown-linux-gnu-g++")
## Optionally set other tools if needed by your build
## set(CMAKE_AR           "${LINUX_CROSS_TOOLCHAIN_ROOT}/bin/x86_64-unknown-linux-gnu-ar")
## set(CMAKE_RANLIB       "${LINUX_CROSS_TOOLCHAIN_ROOT}/bin/x86_64-unknown-linux-gnu-ranlib")
## set(CMAKE_LINKER       "${LINUX_CROSS_TOOLCHAIN_ROOT}/bin/x86_64-unknown-linux-gnu-ld")
#
## The CMAKE_FIND_ROOT_PATH should point to the sysroot for the target Linux system.
## This contains headers and libraries needed for cross-compilation.
## Often, the toolchain includes its own sysroot.
#set(CMAKE_SYSROOT "${LINUX_CROSS_TOOLCHAIN_ROOT}/x86_64-unknown-linux-gnu/sysroot" CACHE PATH "Sysroot for Linux cross-compilation")
## If the sysroot is directly under LINUX_CROSS_TOOLCHAIN_ROOT, adjust accordingly:
## set(CMAKE_SYSROOT "${LINUX_CROSS_TOOLCHAIN_ROOT}/sysroot")
## Or if your toolchain names the sysroot directory differently.
## Ensure this path exists and contains usr/include, usr/lib etc. for the target.
#
#set(CMAKE_FIND_ROOT_PATH ${CMAKE_SYSROOT})
#
#set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
#set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
#set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
#set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE ONLY)