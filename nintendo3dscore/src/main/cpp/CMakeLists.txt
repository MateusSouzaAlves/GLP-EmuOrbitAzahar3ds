cmake_minimum_required(VERSION 3.22.1)

project(emuorbit_n3ds_bootstrap LANGUAGES CXX)

# Keep the JNI host subject to the same non-invasive binary hardening baseline
# as the existing native bridge. The 3DS core remains a separately audited GPL
# component; these flags do not remove its required attribution or source offer.
set(EMUORBIT_N3DS_LTO_OPTIONS -flto=thin)
get_filename_component(
        EMUORBIT_N3DS_WORKSPACE_ROOT
        "${CMAKE_CURRENT_SOURCE_DIR}/../../../.."
        ABSOLUTE
)

add_library(
        emuorbit_n3ds_bootstrap
        SHARED
        core_bootstrap.cpp
        core_gameplay_session.cpp
        core_session_registry.cpp
        jni_bootstrap.cpp
        jni_core_gameplay_session.cpp
        jni_hardware_render_host.cpp
        vulkan_render_host.cpp
)

target_compile_features(emuorbit_n3ds_bootstrap PRIVATE cxx_std_17)
set_target_properties(
        emuorbit_n3ds_bootstrap
        PROPERTIES
        CXX_VISIBILITY_PRESET hidden
        VISIBILITY_INLINES_HIDDEN YES
)
target_compile_options(
        emuorbit_n3ds_bootstrap
        PRIVATE
        -Wall
        -Wextra
        -Werror
        -fvisibility=hidden
        -fvisibility-inlines-hidden
        -fno-ident
        -ffunction-sections
        -fdata-sections
        "-ffile-prefix-map=${EMUORBIT_N3DS_WORKSPACE_ROOT}=."
        "-fmacro-prefix-map=${EMUORBIT_N3DS_WORKSPACE_ROOT}=."
        "-fdebug-prefix-map=${EMUORBIT_N3DS_WORKSPACE_ROOT}=."
        ${EMUORBIT_N3DS_LTO_OPTIONS}
)
target_link_options(
        emuorbit_n3ds_bootstrap
        PRIVATE
        -Wl,--build-id
        -Wl,-z,relro
        -Wl,-z,now
        -Wl,-z,noexecstack
        -Wl,-z,max-page-size=16384
        -Wl,-z,common-page-size=16384
        -Wl,--gc-sections
        -Wl,--exclude-libs,ALL
        "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/nintendo3ds_bootstrap.exports.map"
        ${EMUORBIT_N3DS_LTO_OPTIONS}
)

if(NOT CMAKE_OBJCOPY)
    message(FATAL_ERROR "llvm-objcopy is required for the Nintendo 3DS release boundary")
endif()
add_custom_command(
        TARGET emuorbit_n3ds_bootstrap
        POST_BUILD
        COMMAND "${CMAKE_OBJCOPY}" --remove-section=.comment
                "$<TARGET_FILE:emuorbit_n3ds_bootstrap>"
        COMMENT "Removing compiler identity from the Nintendo 3DS JNI host"
        VERBATIM
)

find_library(dl_library dl REQUIRED)
find_library(android_library android REQUIRED)
find_library(log_library log REQUIRED)
target_link_libraries(
        emuorbit_n3ds_bootstrap
        PRIVATE
        ${android_library}
        ${dl_library}
        ${log_library}
)
