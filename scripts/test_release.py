import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("publish", Path(__file__).with_name("publish-release.py"))
publish = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publish)


class ReleaseHistoryTest(unittest.TestCase):
    def test_next_version(self):
        publish.validate_history([{"tag_name": "v0.10.1"}], "0.11.0", 19, [18])

    def test_reused_or_lower_version_rejected(self):
        for version in ("0.10.1", "0.9.9"):
            with self.assertRaises(ValueError):
                publish.validate_history([{"tag_name": "v0.10.1"}], version, 19, [18])

    def test_codes_independent_of_display_versions(self):
        with self.assertRaises(ValueError):
            publish.validate_history([], "1.0.0", 19, [18, 20])

    def test_draft_also_reserves_version(self):
        with self.assertRaises(ValueError):
            publish.validate_history([{"tag_name": "v0.11.0", "draft": True}], "0.11.0", 19, [18])
