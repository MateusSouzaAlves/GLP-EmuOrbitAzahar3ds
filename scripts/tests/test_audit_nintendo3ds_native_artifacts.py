import importlib.util
import sys
import unittest
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "audit-nintendo3ds-native-artifacts.py"
SPEC = importlib.util.spec_from_file_location("audit_n3ds_native", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


def valid_output():
    return MODULE.ToolOutput(
        header="Class: ELF64\nType: DYN (Shared object file)\nMachine: AArch64\n",
        dynamic=(
            "(NEEDED) Shared library: [libandroid.so]\n"
            "(NEEDED) Shared library: [libc.so]\n"
            "(NEEDED) Shared library: [libdl.so]\n"
            "(NEEDED) Shared library: [liblog.so]\n"
            "(NEEDED) Shared library: [libm.so]\n"
            "(SONAME) Library soname: [libemuorbit_n3ds_bootstrap.so]\n"
            "(FLAGS) BIND_NOW\n(FLAGS_1) NOW\n"
        ),
        program_headers=(
            "LOAD 0x0 0x0 0x0 0x20 0x20 R E 0x4000\n"
            "LOAD 0x20 0x20 0x20 0x20 0x20 RW 0x4000\n"
            "GNU_RELRO 0x20 0x20 0x20 0x20 0x20 R 0x1\n"
            "GNU_STACK 0x0 0x0 0x0 0x0 0x0 RW 0x0\n"
        ),
        sections="[ 1] .text PROGBITS 00 00\n",
        notes="Build ID: 0123456789abcdef\n",
        dynamic_symbols="JNI_OnLoad@@EMUORBIT_N3DS_BOOTSTRAP_1 T 100 20\n",
    )


class Nintendo3DsNativeArtifactAuditTest(unittest.TestCase):
    def test_accepts_hardened_bootstrap_contract(self):
        errors = MODULE.audit_outputs(
            kind="bootstrap", artifact_bytes=b"\x7fELF-safe", output=valid_output()
        )
        self.assertEqual([], errors)

    def test_rejects_extra_export_and_packaged_metadata(self):
        output = valid_output()
        output = MODULE.ToolOutput(
            **{
                **output.__dict__,
                "sections": "[ 7] .comment PROGBITS 00 00\n[ 8] .debug_info PROGBITS 00 00\n",
                "dynamic_symbols": "JNI_OnLoad T 100 20\ninternal_helper T 120 20\n",
            }
        )
        errors = MODULE.audit_outputs(
            kind="bootstrap", artifact_bytes=b"\x7fELF-safe", output=output
        )
        self.assertIn("bootstrap must export only JNI_OnLoad", errors)
        self.assertTrue(any("forbidden packaged sections" in error for error in errors))

    def test_rejects_weak_hardening_and_checkout_path(self):
        output = valid_output()
        output = MODULE.ToolOutput(
            **{
                **output.__dict__,
                "dynamic": output.dynamic.replace("(FLAGS) BIND_NOW\n", ""),
                "program_headers": output.program_headers.replace("0x4000", "0x1000", 1),
            }
        )
        errors = MODULE.audit_outputs(
            kind="bootstrap",
            artifact_bytes=b"\x7fELF C:/Users/example/workspace/source.cpp",
            output=output,
            forbidden_paths=["C:/Users/example/workspace"],
        )
        self.assertIn("immediate relocation binding is missing", errors)
        self.assertIn("a LOAD segment is not aligned to 16 KiB", errors)
        self.assertIn("artifact contains a forbidden build path", errors)

    def test_core_rejects_every_export_outside_complete_libretro_abi(self):
        output = valid_output()
        core_dynamic = (
            output.dynamic.replace("libandroid.so", "libc.so", 1)
            .replace("(NEEDED) Shared library: [libc.so]\n", "", 1)
            .replace("libemuorbit_n3ds_bootstrap.so", "azahar_libretro.so")
        )
        core_symbols = "\n".join(
            f"{symbol} T 100 20" for symbol in sorted(MODULE.CORE_EXPORTS)
        )
        core_output = MODULE.ToolOutput(
            **{
                **output.__dict__,
                "dynamic": core_dynamic,
                "dynamic_symbols": core_symbols + "\ninternal_helper T 120 20\n",
            }
        )
        errors = MODULE.audit_outputs(
            kind="core", artifact_bytes=b"\x7fELF-safe", output=core_output
        )
        self.assertTrue(any("outside the libretro ABI" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
