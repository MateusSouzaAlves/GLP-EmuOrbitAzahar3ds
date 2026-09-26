import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile


SCRIPT = Path(__file__).parents[1] / "audit-nintendo3ds-packaged-artifacts.py"
SPEC = importlib.util.spec_from_file_location("audit_n3ds_packaged", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class Nintendo3DsPackagedArtifactsAuditTest(unittest.TestCase):
    seed = "n3ds-packaged-audit-test"
    context = MODULE.DEFAULT_CORE_CONTEXT

    def _artifacts(
        self,
        root: Path,
        *,
        extra_aab: str | None = None,
        extra_apk: str | None = None,
        apk_notice: bytes | None = None,
    ):
        bundle = root / "candidate.aab"
        apk = root / "universal.apk"
        core = b"\x7fELF" + b"pinned-core"
        bootstrap = b"bootstrap"
        notice = (
            f"{MODULE.PUBLIC_SOURCE_REPOSITORY}\n"
            f"{MODULE.PUBLIC_SOURCE_COMMIT}\nGPL-3.0-or-later\n"
        ).encode()
        source = root / "core.so"
        packed = root / "core.bin"
        source.write_bytes(core)
        MODULE._load_container_module().pack(
            source, packed, self.seed, self.context
        )
        container_bytes = packed.read_bytes()
        asset_name = MODULE.protected_asset_name(self.seed, self.context)
        with ZipFile(bundle, "w") as archive:
            archive.writestr(
                f"nintendo3dscore/assets/{asset_name}", container_bytes
            )
            archive.writestr(MODULE.AAB_BOOTSTRAP, bootstrap)
            archive.writestr(MODULE.AAB_NOTICE, notice)
            if extra_aab:
                archive.writestr(extra_aab, b"forbidden")
        with ZipFile(apk, "w") as archive:
            archive.writestr(f"assets/{asset_name}", container_bytes)
            archive.writestr(MODULE.APK_BOOTSTRAP, bootstrap)
            archive.writestr(MODULE.APK_NOTICE, apk_notice or notice)
            if extra_apk:
                archive.writestr(extra_apk, b"forbidden")
        return bundle, apk, core

    @staticmethod
    def _native(kind, payload, ndk_root, forbidden_paths):
        return [], {"kind": kind, "bytes": len(payload)}

    def _audit(self, root: Path, *, extra_aab: str | None = None):
        bundle, apk, core = self._artifacts(root, extra_aab=extra_aab)
        original_bytes = MODULE.EXPECTED_CORE_BYTES
        original_hash = MODULE.EXPECTED_CORE_SHA256
        original_container_bytes = MODULE.EXPECTED_CONTAINER_BYTES
        MODULE.EXPECTED_CORE_BYTES = len(core)
        MODULE.EXPECTED_CORE_SHA256 = MODULE._sha256(core)
        MODULE.EXPECTED_CONTAINER_BYTES = len(core) + 68
        try:
            return MODULE.audit_packaged_artifacts(
                bundle,
                apk,
                root,
                seed=self.seed,
                context=self.context,
                native_auditor=self._native,
            )
        finally:
            MODULE.EXPECTED_CORE_BYTES = original_bytes
            MODULE.EXPECTED_CORE_SHA256 = original_hash
            MODULE.EXPECTED_CONTAINER_BYTES = original_container_bytes

    def test_accepts_matching_minimal_artifacts(self):
        with tempfile.TemporaryDirectory() as directory:
            errors, report = self._audit(Path(directory))
            self.assertEqual([], errors)
            self.assertEqual(0, report["sourceOrSbomEntries"])

    def test_rejects_rom_or_homebrew_entry(self):
        with tempfile.TemporaryDirectory() as directory:
            errors, _ = self._audit(Path(directory), extra_aab="assets/test.3dsx")
            self.assertTrue(any("ROM or homebrew" in error for error in errors))

    def test_rejects_source_or_sbom_entry(self):
        with tempfile.TemporaryDirectory() as directory:
            errors, _ = self._audit(
                Path(directory), extra_aab="assets/nintendo3ds/source-archive.zip"
            )
            self.assertTrue(any("source/SBOM" in error for error in errors))

    def test_rejects_notice_without_public_commit(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bundle, apk, core = self._artifacts(
                root, apk_notice=b"GPL-3.0-or-later"
            )
            original_bytes = MODULE.EXPECTED_CORE_BYTES
            original_hash = MODULE.EXPECTED_CORE_SHA256
            original_container_bytes = MODULE.EXPECTED_CONTAINER_BYTES
            MODULE.EXPECTED_CORE_BYTES = len(core)
            MODULE.EXPECTED_CORE_SHA256 = MODULE._sha256(core)
            MODULE.EXPECTED_CONTAINER_BYTES = len(core) + 68
            try:
                errors, _ = MODULE.audit_packaged_artifacts(
                    bundle,
                    apk,
                    root,
                    seed=self.seed,
                    context=self.context,
                    native_auditor=self._native,
                )
            finally:
                MODULE.EXPECTED_CORE_BYTES = original_bytes
                MODULE.EXPECTED_CORE_SHA256 = original_hash
                MODULE.EXPECTED_CONTAINER_BYTES = original_container_bytes
            self.assertTrue(any("different Nintendo 3DS notices" in error for error in errors))
            self.assertTrue(any("public source commit" in error for error in errors))

    def test_rejects_native_audit_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bundle, apk, core = self._artifacts(root)
            original_bytes = MODULE.EXPECTED_CORE_BYTES
            original_hash = MODULE.EXPECTED_CORE_SHA256
            original_container_bytes = MODULE.EXPECTED_CONTAINER_BYTES
            MODULE.EXPECTED_CORE_BYTES = len(core)
            MODULE.EXPECTED_CORE_SHA256 = MODULE._sha256(core)
            MODULE.EXPECTED_CONTAINER_BYTES = len(core) + 68
            try:
                errors, _ = MODULE.audit_packaged_artifacts(
                    bundle,
                    apk,
                    root,
                    seed=self.seed,
                    context=self.context,
                    native_auditor=lambda *args: (["GNU_RELRO is missing"], {}),
                )
            finally:
                MODULE.EXPECTED_CORE_BYTES = original_bytes
                MODULE.EXPECTED_CORE_SHA256 = original_hash
                MODULE.EXPECTED_CONTAINER_BYTES = original_container_bytes
            self.assertTrue(any("GNU_RELRO is missing" in error for error in errors))

    def test_rejects_plaintext_core_entry(self):
        with tempfile.TemporaryDirectory() as directory:
            errors, _ = self._audit(
                Path(directory), extra_aab=MODULE.AAB_RAW_CORE
            )
            self.assertTrue(any("directly extractable" in error for error in errors))

    def test_rejects_unexpected_opaque_asset_in_universal_apk(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bundle, apk, core = self._artifacts(
                root, extra_apk="assets/q00000000000000000000000"
            )
            original_bytes = MODULE.EXPECTED_CORE_BYTES
            original_hash = MODULE.EXPECTED_CORE_SHA256
            original_container_bytes = MODULE.EXPECTED_CONTAINER_BYTES
            MODULE.EXPECTED_CORE_BYTES = len(core)
            MODULE.EXPECTED_CORE_SHA256 = MODULE._sha256(core)
            MODULE.EXPECTED_CONTAINER_BYTES = len(core) + 68
            try:
                errors, _ = MODULE.audit_packaged_artifacts(
                    bundle,
                    apk,
                    root,
                    seed=self.seed,
                    context=self.context,
                    native_auditor=self._native,
                )
            finally:
                MODULE.EXPECTED_CORE_BYTES = original_bytes
                MODULE.EXPECTED_CORE_SHA256 = original_hash
                MODULE.EXPECTED_CONTAINER_BYTES = original_container_bytes
            self.assertTrue(any("APK protected asset inventory" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
