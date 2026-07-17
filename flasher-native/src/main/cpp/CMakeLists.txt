cmake_minimum_required(VERSION 3.22)
project(flasher_native C)

set(PORT "USER_DEFINED" CACHE STRING "" FORCE)

set(REPO_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../..)
add_subdirectory(${REPO_ROOT}/third_party/esp-serial-flasher
                  ${CMAKE_CURRENT_BINARY_DIR}/esp-serial-flasher)

add_library(flasher_native SHARED
    android_port.c
    jni_bridge.c
)

target_link_libraries(flasher_native PRIVATE flasher log)

target_include_directories(flasher_native PRIVATE
    ${REPO_ROOT}/third_party/esp-serial-flasher/include
)

target_compile_options(flasher_native PRIVATE -Wall -Wextra)
