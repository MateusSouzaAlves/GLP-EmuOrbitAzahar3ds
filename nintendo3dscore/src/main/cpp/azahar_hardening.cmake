# Applied to the pinned Azahar source through CMAKE_PROJECT_INCLUDE. Keep this
# separate from upstream so the complete GPL modification remains reproducible.
get_property(
        _emuorbit_n3ds_core_hardening_scheduled
        GLOBAL
        PROPERTY EMUORBIT_N3DS_CORE_HARDENING_SCHEDULED
        SET
)
if(NOT _emuorbit_n3ds_core_hardening_scheduled)
    set_property(GLOBAL PROPERTY EMUORBIT_N3DS_CORE_HARDENING_SCHEDULED TRUE)

    get_filename_component(
            _emuorbit_n3ds_core_source_root
            "${CMAKE_SOURCE_DIR}"
            ABSOLUTE
    )
    # These options are inherited by targets created below this top-level
    # project. They prevent source paths from entering diagnostics/debug data
    # and make unused code/data eligible for linker collection.
    add_compile_options(
            "-ffile-prefix-map=${_emuorbit_n3ds_core_source_root}=."
            "-fmacro-prefix-map=${_emuorbit_n3ds_core_source_root}=."
            "-fdebug-prefix-map=${_emuorbit_n3ds_core_source_root}=."
            -ffunction-sections
            -fdata-sections
            -fno-ident
    )

    function(emuorbit_harden_azahar_libretro)
        # Azahar names the compiled shared-library target `citra_libretro` and
        # exposes `azahar_libretro` as a convenience custom target. Link
        # properties must therefore be attached to the former.
        if(NOT TARGET citra_libretro)
            message(FATAL_ERROR "Pinned Azahar libretro shared-library target was not created")
        endif()
        if(NOT DEFINED EMUORBIT_N3DS_CORE_EXPORT_MAP
                OR NOT EXISTS "${EMUORBIT_N3DS_CORE_EXPORT_MAP}")
            message(FATAL_ERROR "Nintendo 3DS libretro export map is required")
        endif()
        target_link_options(
                citra_libretro
                PRIVATE
                "-Wl,--version-script=${EMUORBIT_N3DS_CORE_EXPORT_MAP}"
                -Wl,--exclude-libs,ALL
                -Wl,--gc-sections
                -Wl,-z,relro
                -Wl,-z,now
                -Wl,-z,noexecstack
                -Wl,-z,max-page-size=16384
                -Wl,-z,common-page-size=16384
        )
        set_property(
                TARGET citra_libretro
                APPEND
                PROPERTY LINK_DEPENDS "${EMUORBIT_N3DS_CORE_EXPORT_MAP}"
        )
    endfunction()

    cmake_language(DEFER CALL emuorbit_harden_azahar_libretro)
endif()
