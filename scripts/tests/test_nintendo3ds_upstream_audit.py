import importlib.util
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch


PROJECT_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = PROJECT_ROOT / "scripts" / "audit-nintendo3ds-upstream.py"
SPEC = importlib.util.spec_from_file_location("nintendo3ds_upstream_audit", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class Nintendo3dsUpstreamAuditTest(unittest.TestCase):
    def test_command_output_replaces_non_utf8_bytes_instead_of_truncating_audit(self):
        completed = type("Completed", (), {"stdout": "C:/src/�rea/externals/used"})()
        with patch.object(MODULE.subprocess, "run", return_value=completed) as run:
            self.assertIn("externals/used", MODULE.run("ninja", "-t", "commands"))
        self.assertEqual("replace", run.call_args.kwargs["errors"])

    def test_parses_only_clean_pinned_submodules(self):
        output = " 0123456789abcdef0123456789abcdef01234567 externals/example (v1)\n"
        self.assertEqual(
            {"externals/example": "0123456789abcdef0123456789abcdef01234567"},
            MODULE.parse_submodule_status(output),
        )
        with self.assertRaisesRegex(ValueError, "not initialized"):
            MODULE.parse_submodule_status(output.replace(" ", "+", 1))

    def test_submodule_digest_is_order_independent(self):
        first = {"z": "1" * 40, "a": "2" * 40}
        second = dict(reversed(list(first.items())))
        self.assertEqual(
            MODULE.submodule_digest(first), MODULE.submodule_digest(second)
        )

    def test_build_reference_discovery_uses_normalized_paths(self):
        submodules = {
            "externals/used": "1" * 40,
            "externals/unused": "2" * 40,
        }
        commands = r"clang -IC:\src\externals\used source.cpp"
        self.assertEqual(
            {"externals/used"},
            MODULE.discover_build_references(commands, submodules),
        )

    def test_license_evidence_must_exist_inside_dependency(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            dependency = root / "externals" / "example"
            dependency.mkdir(parents=True)
            (dependency / "LICENSE").write_text("MIT", encoding="utf-8")
            valid = [
                {
                    "path": "externals/example",
                    "licenseExpression": "MIT",
                    "licenseEvidence": ["LICENSE"],
                }
            ]
            self.assertEqual([], MODULE.validate_license_evidence(root, valid))
            invalid = [dict(valid[0], licenseEvidence=["../outside"])]
            self.assertTrue(MODULE.validate_license_evidence(root, invalid))


if __name__ == "__main__":
    unittest.main()
