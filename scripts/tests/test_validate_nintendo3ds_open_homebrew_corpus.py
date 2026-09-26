import importlib.util
import json
import sys
import unittest
from copy import deepcopy
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "validate-nintendo3ds-open-homebrew-corpus.py"
SPEC = importlib.util.spec_from_file_location("validate_n3ds_open_corpus", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)
MANIFEST = json.loads(MODULE.DEFAULT_MANIFEST.read_text(encoding="utf-8"))


class Nintendo3DsOpenHomebrewCorpusValidationTest(unittest.TestCase):
    def test_accepts_six_pinned_distinct_redistributable_entries(self):
        self.assertEqual([], MODULE.validate_manifest(MANIFEST))

    def test_rejects_commercial_content_or_committed_binaries(self):
        changed = deepcopy(MANIFEST)
        changed["commercialContentAllowed"] = True
        changed["binariesCommitted"] = True
        errors = MODULE.validate_manifest(changed)
        self.assertTrue(any("commercial" in error for error in errors))
        self.assertTrue(any("must not be committed" in error for error in errors))

    def test_rejects_unlicensed_or_non_github_asset(self):
        changed = deepcopy(MANIFEST)
        changed["entries"][0]["licenseSpdx"] = "NOASSERTION"
        changed["entries"][0]["assetUrl"] = "https://example.test/game.3dsx"
        errors = MODULE.validate_manifest(changed)
        self.assertTrue(any("approved redistributable license" in error for error in errors))
        self.assertTrue(any("official release" in error for error in errors))

    def test_rejects_duplicate_binary_or_unsafe_archive_path(self):
        changed = deepcopy(MANIFEST)
        changed["entries"][1]["contentSha256"] = changed["entries"][0]["contentSha256"]
        changed["entries"][4]["archiveContentPath"] = "../escape.3dsx"
        errors = MODULE.validate_manifest(changed)
        self.assertTrue(any("binary-distinct" in error for error in errors))
        self.assertTrue(any("unsafe" in error for error in errors))

    def test_repository_contains_no_tracked_nintendo3ds_content_binary(self):
        self.assertEqual([], MODULE.tracked_binary_errors())


if __name__ == "__main__":
    unittest.main()
