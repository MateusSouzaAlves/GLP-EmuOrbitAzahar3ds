import importlib.util
import json
import sys
import unittest
from copy import deepcopy
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "validate-nintendo3ds-regression-matrix.py"
SPEC = importlib.util.spec_from_file_location("validate_n3ds_regression", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)
CONTRACT = json.loads(MODULE.DEFAULT_CONTRACT.read_text(encoding="utf-8"))


def partial_report():
    return {
        "schemaVersion": 1,
        "planItem": "N3DS-13",
        "status": "PARTIAL_EXTERNAL_GATES",
        "deviceClass": "GALAXY_XCLIPSE_REFERENCE",
        "cases": [
            {
                "slotId": "PRIVATE_OWNED_DUMP_1",
                "status": "PASSED",
                "frames": 600,
                "audioFrames": 1000,
                "inputPolls": 600,
                "touchEvents": 1,
                "saveRoundTrips": 1,
                "lifecycleCycles": 2,
                "elapsedMillis": 10000,
                "fatalSignals": 0,
            }
        ],
        "existingSystems": [],
        "matrixEvidence": [],
        "summary": {
            "distinctNintendo3DsContents": 1,
            "passedNintendo3DsContents": 1,
            "passedExistingSystems": 0,
            "fatalSignals": 0,
            "externalGatesRemaining": 2,
        },
    }


class Nintendo3DsRegressionMatrixValidationTest(unittest.TestCase):
    def test_accepts_frozen_privacy_safe_contract(self):
        self.assertEqual([], MODULE.validate_contract(CONTRACT))

    def test_accepts_sanitized_partial_report(self):
        self.assertEqual([], MODULE.validate_report(partial_report(), CONTRACT))

    def test_rejects_private_path_or_rom_filename(self):
        report = partial_report()
        report["deviceClass"] = r"C:\private\game.3ds"
        errors = MODULE.validate_report(report, CONTRACT)
        self.assertTrue(any("private path" in error for error in errors))

    def test_rejects_identity_field_even_with_benign_value(self):
        report = partial_report()
        report["cases"][0]["title"] = "redacted"
        errors = MODULE.validate_report(report, CONTRACT)
        self.assertTrue(any("forbidden private field" in error for error in errors))

    def test_rejects_duplicate_slots(self):
        report = partial_report()
        report["cases"].append(deepcopy(report["cases"][0]))
        report["summary"]["distinctNintendo3DsContents"] = 1
        report["summary"]["passedNintendo3DsContents"] = 2
        errors = MODULE.validate_report(report, CONTRACT)
        self.assertTrue(any("duplicate content slots" in error for error in errors))

    def test_rejects_full_pass_below_ten_contents(self):
        report = partial_report()
        report["status"] = "PASSED"
        errors = MODULE.validate_report(report, CONTRACT)
        self.assertTrue(any("at least ten" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
