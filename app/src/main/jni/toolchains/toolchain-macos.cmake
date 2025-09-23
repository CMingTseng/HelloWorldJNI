# For cross-compiling to macOS using osxcross
set(CMAKE_SYSTEM_NAME Darwin)

# 設定 osxcross 工具的路徑
set(OSXCROSS_TOOLCHAIN_PATH $ENV{HOME}/osxcross/target/bin) # 修改為您的 osxcross 安裝路徑

set(CMAKE_C_COMPILER ${OSXCROSS_TOOLCHAIN_PATH}/o64-clang)
set(CMAKE_CXX_COMPILER ${OSXCROSS_TOOLCHAIN_PATH}/o64-clang++)

set(CMAKE_FIND_ROOT_PATH $ENV{HOME}/osxcross/target/macports/pkgs/opt/local) # 修改為您的 SDK 路徑
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)