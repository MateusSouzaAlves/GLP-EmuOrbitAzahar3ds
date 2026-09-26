import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


PROJECT_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = PROJECT_ROOT / "scripts" / "generate-nintendo3ds-compliance.py"
SPEC = importlib.util.spec_from_file_location("nintendo3ds_compliance", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class Nintendo3dsComplianceTest(unittest.TestCase):
    def setUp(self):
        self.manifest = json.loads(
            (PROJECT_ROOT / "config" / "nintendo3ds-source-scope.json").read_text(
                encoding="utf-8"
            )
        )

    def test_source_acquisition_is_pinned_source_only_and_non_destructive(self):
        source = (
            PROJECT_ROOT / "scripts" / "acquire-nintendo3ds-source.ps1"
        ).read_text(encoding="utf-8")
        self.assertIn("$manifest.distributedUpstreams", source)
        self.assertIn("clone --filter=blob:none --no-checkout", source)
        self.assertIn("checkout --detach $expectedCommit", source)
        self.assertIn("submodule update --init --recursive", source)
        self.assertIn("audit-nintendo3ds-upstream.py", source)
        self.assertNotIn("reset --hard", source)
        self.assertNotIn("azahar_libretro.so", source)

    def test_sbom_is_deterministic_and_covers_every_linked_dependency(self):
        first = MODULE.render_json(MODULE.generate_sbom(self.manifest))
        second = MODULE.render_json(MODULE.generate_sbom(self.manifest))
        self.assertEqual(first, second)
        sbom = json.loads(first)
        self.assertEqual(
            "http://cyclonedx.org/schema/bom-1.6.schema.json", sbom["$schema"]
        )
        self.assertEqual("CycloneDX", sbom["bomFormat"])
        self.assertEqual("1.6", sbom["specVersion"])
        self.assertEqual(
            self.manifest["upstreamAudit"]["linkedDependencyCount"],
            len(sbom["components"]),
        )
        self.assertEqual(
            self.manifest["upstreamAudit"]["aggregateLicense"],
            sbom["metadata"]["component"]["licenses"][0]["expression"],
        )
        self.assertFalse(self.manifest["n3ds01Decision"]["downloadedCoreBinary"])

    def test_notice_index_records_license_evidence_and_exclusion(self):
        notices = MODULE.generate_notices(self.manifest)
        for dependency in self.manifest["upstreamAudit"]["linkedDependencies"]:
            self.assertIn(dependency["path"], notices)
            self.assertIn(dependency["commit"], notices)
            for evidence in dependency["licenseEvidence"]:
                self.assertIn(evidence, notices)
        self.assertIn(
            self.manifest["upstreamAudit"]["excludedLegacyTls"]["path"], notices
        )
        for reference in self.manifest["referenceImplementations"]:
            for source in reference.get("incorporatedSourcePaths", []):
                self.assertIn(source["source"], notices)
                self.assertIn(source["license"], notices)
                for destination in source["destinations"]:
                    self.assertIn(destination, notices)
        self.assertIn("does not authorize distribution", notices)

    def test_check_mode_rejects_stale_outputs(self):
        with tempfile.TemporaryDirectory() as directory:
            output_root = Path(directory)
            sbom = output_root / "sbom.json"
            notices = output_root / "notices.md"
            self.assertEqual(
                0,
                MODULE.main(["--sbom", str(sbom), "--notices", str(notices)]),
            )
            self.assertEqual(
                0,
                MODULE.main(
                    ["--sbom", str(sbom), "--notices", str(notices), "--check"]
                ),
            )
            notices.write_text("stale", encoding="utf-8")
            with self.assertRaises(SystemExit):
                MODULE.main(
                    ["--sbom", str(sbom), "--notices", str(notices), "--check"]
                )


if __name__ == "__main__":
    unittest.main()
