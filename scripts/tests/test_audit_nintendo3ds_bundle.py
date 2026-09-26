import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile


SCRIPT = Path(__file__).parents[1] / "audit-nintendo3ds-bundle.py"
SPEC = importlib.util.spec_from_file_location("audit_n3ds_bundle", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class Nintendo3DsBundleAuditTest(unittest.TestCase):
    seed = "n3ds-bundle-audit-test"
    context = MODULE.DEFAULT_CORE_CONTEXT

    def _bundle(self, root: Path, *, leak_to_base: bool = False) -> Path:
        bundle = root / "candidate.aab"
        container = b"protected-container"
        asset_name = MODULE.protected_asset_name(self.seed, self.context)
        with ZipFile(bundle, "w") as archive:
            archive.writestr("base/dex/classes.dex", b"base")
            archive.writestr(
                "nintendo3dscore/dex/classes.dex",
                MODULE.FEATURE_DESCRIPTOR + b"Nintendo3DsProductActivity;",
            )
            archive.writestr(
                f"nintendo3dscore/assets/{asset_name}", container
            )
            archive.writestr(MODULE.BOOTSTRAP_ENTRY, b"bootstrap")
            archive.writestr(MODULE.NOTICE_ENTRY, b"notices")
            if leak_to_base:
                archive.writestr("base/lib/arm64-v8a/libn3ds.so", b"leak")
        return bundle

    def test_accepts_separated_feature_boundary(self):
        with tempfile.TemporaryDirectory() as directory:
            bundle = self._bundle(Path(directory))
            original_bytes = MODULE.EXPECTED_CORE_BYTES
            original_hash = MODULE.EXPECTED_CORE_SHA256
            original_container_bytes = MODULE.EXPECTED_CONTAINER_BYTES
            MODULE.EXPECTED_CONTAINER_BYTES = len(b"protected-container")
            try:
                errors, report = MODULE.audit_bundle(
                    bundle, seed=self.seed, context=self.context
                )
            finally:
                MODULE.EXPECTED_CORE_BYTES = original_bytes
                MODULE.EXPECTED_CORE_SHA256 = original_hash
                MODULE.EXPECTED_CONTAINER_BYTES = original_container_bytes
            self.assertEqual([], errors)
            self.assertEqual(
                len(b"protected-container"), report["containerBytes"]
            )

    def test_rejects_native_payload_in_base(self):
        with tempfile.TemporaryDirectory() as directory:
            bundle = self._bundle(Path(directory), leak_to_base=True)
            errors, _ = MODULE.audit_bundle(
                bundle, seed=self.seed, context=self.context
            )
            self.assertTrue(any("leaked into base" in error for error in errors))

    def test_rejects_external_source_sbom_in_bundle(self):
        with tempfile.TemporaryDirectory() as directory:
            bundle = self._bundle(Path(directory))
            with ZipFile(bundle, "a") as archive:
                archive.writestr(MODULE.SBOM_ENTRY, b"{}")
            errors, _ = MODULE.audit_bundle(
                bundle, seed=self.seed, context=self.context
            )
            self.assertTrue(any("SBOM leaked" in error for error in errors))

    def test_rejects_unapproved_feature_native_payload(self):
        with tempfile.TemporaryDirectory() as directory:
            bundle = self._bundle(Path(directory))
            with ZipFile(bundle, "a") as archive:
                archive.writestr(
                    "nintendo3dscore/lib/arm64-v8a/libunexpected.so", b"unexpected"
                )
            errors, _ = MODULE.audit_bundle(
                bundle, seed=self.seed, context=self.context
            )
            self.assertTrue(any("differs from the approved" in error for error in errors))

    def test_rejects_plaintext_core_entry(self):
        with tempfile.TemporaryDirectory() as directory:
            bundle = self._bundle(Path(directory))
            with ZipFile(bundle, "a") as archive:
                archive.writestr(MODULE.RAW_CORE_ENTRY, b"plaintext")
            errors, _ = MODULE.audit_bundle(
                bundle, seed=self.seed, context=self.context
            )
            self.assertTrue(any("directly extractable" in error for error in errors))

    def test_rejects_exported_or_on_demand_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            manifest = Path(directory) / "AndroidManifest.xml"
            manifest.write_text(
                """<manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    xmlns:dist="http://schemas.android.com/apk/distribution"
                    featureSplit="nintendo3dscore">
                  <dist:module dist:instant="false"><dist:delivery><dist:on-demand />
                  </dist:delivery><dist:fusing dist:include="true" /></dist:module>
                  <application><activity android:exported="true" /></application>
                </manifest>""",
                encoding="utf-8",
            )
            errors = MODULE.audit_manifest(manifest)
            self.assertIn("feature is not declared for install-time delivery", errors)
            self.assertIn("feature must not use on-demand delivery", errors)
            self.assertIn("feature contains an exported Android component", errors)

    def test_rejects_install_time_manifest_that_would_fuse_into_base(self):
        with tempfile.TemporaryDirectory() as directory:
            manifest = Path(directory) / "AndroidManifest.xml"
            manifest.write_text(
                """<manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    xmlns:dist="http://schemas.android.com/apk/distribution"
                    featureSplit="nintendo3dscore">
                  <dist:module dist:instant="false"><dist:delivery><dist:install-time />
                  </dist:delivery><dist:fusing dist:include="true" /></dist:module>
                  <application><activity android:exported="false" /></application>
                </manifest>""",
                encoding="utf-8",
            )
            errors = MODULE.audit_manifest(manifest)
            self.assertIn(
                "install-time feature is not kept as a removable split", errors
            )

    def test_allows_only_dump_protected_probe_in_isolated_test_package(self):
        with tempfile.TemporaryDirectory() as directory:
            manifest = Path(directory) / "AndroidManifest.xml"
            manifest.write_text(
                f"""<manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    xmlns:dist="http://schemas.android.com/apk/distribution"
                    featureSplit="nintendo3dscore"
                    package="{MODULE.DELIVERY_TEST_PACKAGE}">
                  <dist:module dist:instant="false"><dist:delivery><dist:install-time>
                    <dist:removable dist:value="true" /></dist:install-time>
                  </dist:delivery><dist:fusing dist:include="true" /></dist:module>
                  <application android:debuggable="true"><activity
                    android:name="{MODULE.DELIVERY_TEST_PROBE}"
                    android:enabled="true" android:exported="true"
                    android:permission="android.permission.DUMP"
                    android:noHistory="true" android:excludeFromRecents="true" />
                  </application>
                </manifest>""",
                encoding="utf-8",
            )
            errors = MODULE.audit_manifest(
                manifest, allow_isolated_delivery_test_probe=True
            )
            self.assertEqual([], errors)
            strict_errors = MODULE.audit_manifest(manifest)
            self.assertIn("feature contains an exported Android component", strict_errors)

            source = manifest.read_text(encoding="utf-8").replace(
                "android.permission.DUMP", "android.permission.INTERNET"
            )
            manifest.write_text(source, encoding="utf-8")
            errors = MODULE.audit_manifest(
                manifest, allow_isolated_delivery_test_probe=True
            )
            self.assertIn("feature contains an exported Android component", errors)

    def test_accepts_bundletool_manifest_with_disabled_protected_probe(self):
        with tempfile.TemporaryDirectory() as directory:
            manifest = Path(directory) / "AndroidManifest.xml"
            manifest.write_text(
                f"""<manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    xmlns:dist="http://schemas.android.com/apk/distribution"
                    android:isFeatureSplit="true" split="nintendo3dscore"
                    package="com.mateussouza.emuorbit.advance">
                  <dist:module dist:instant="false"><dist:delivery><dist:install-time>
                    <dist:removable dist:value="true" /></dist:install-time>
                  </dist:delivery><dist:fusing dist:include="true" /></dist:module>
                  <application android:debuggable="true"><activity
                    android:name="{MODULE.DELIVERY_TEST_PROBE}"
                    android:enabled="false" android:exported="true"
                    android:permission="android.permission.DUMP"
                    android:noHistory="true" android:excludeFromRecents="true" />
                  </application>
                </manifest>""",
                encoding="utf-8",
            )
            self.assertEqual([], MODULE.audit_manifest(manifest))


if __name__ == "__main__":
    unittest.main()
