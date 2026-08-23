cmake_minimum_required(VERSION 3.22.1)

project(byedpi_native)

# CONFIGURE_DEPENDS so the glob re-runs on every build: adding or removing a byedpi source
# must not require a manual CMake re-configure to be picked up.
file(GLOB BYE_DPI_SRC CONFIGURE_DEPENDS byedpi/*.c)
list(REMOVE_ITEM BYE_DPI_SRC ${CMAKE_CURRENT_SOURCE_DIR}/byedpi/win_service.c)

add_library(byedpi SHARED ${BYE_DPI_SRC} native-lib.c)
target_include_directories(byedpi PRIVATE byedpi)

# Hardening for a library that parses untrusted network input: FORTIFY_SOURCE adds runtime
# checks to known-unsafe libc calls, and -fno-strict-aliasing keeps the vendored byedpi code
# (which type-puns socket/protocol structs) well-defined under -O2.
target_compile_options(byedpi PRIVATE -std=c99 -O2 -Wall -Wno-unused -Wextra -Wno-unused-parameter -pedantic
    -D_FORTIFY_SOURCE=2 -fno-strict-aliasing)
target_compile_definitions(byedpi PRIVATE ANDROID_APP)

# relro+now: make the GOT/PLT read-only at load time instead of lazily bound.
target_link_options(byedpi PRIVATE -Wl,-z,relro,-z,now)

target_link_libraries(byedpi PRIVATE android log)
