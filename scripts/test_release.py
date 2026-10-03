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


package_spec = importlib.util.spec_from_file_location("package_release", Path(__file__).with_name("package-release.py"))
package = importlib.util.module_from_spec(package_spec)
package_spec.loader.exec_module(package)


class SigningOutputTest(unittest.TestCase):
    def test_numbered_signer(self):
        digest = "a" * 64
        self.assertEqual(digest, package.certificate_digest(f"Signer #1 certificate SHA-256 digest: {digest}\n"))

    def test_sdk_range_signer(self):
        digest = "b" * 64
        self.assertEqual(digest, package.certificate_digest(
            f"Signer (minSdkVersion=28, maxSdkVersion=2147483647) certificate SHA-256 digest: {digest}\n"))

    def test_missing_or_multiple_identities_rejected(self):
        for report in ("", "Source Stamp Signer certificate SHA-256 digest: " + "a" * 64,
                       "Signer #1 certificate SHA-256 digest: " + "a" * 64 + "\nSigner #2 certificate SHA-256 digest: " + "b" * 64):
            with self.assertRaises(ValueError):
                package.certificate_digest(report)


class ReleaseChannelTest(unittest.TestCase):
    def test_default_stays_prerelease_during_draft_and_publication(self):
        from unittest.mock import patch
        with patch.object(publish, "run") as run:
            publish.publish_artifacts("0.12.0", "commit", [], "Release notes")
        create, edit = [call.args for call in run.call_args_list]
        self.assertIn("--draft", create)
        self.assertIn("--prerelease=true", create)
        self.assertIn("--prerelease=true", edit)
        self.assertIn("--latest=false", edit)

    def test_stable_requires_explicit_request(self):
        from unittest.mock import patch
        with patch.object(publish, "run") as run:
            publish.publish_artifacts("0.12.0", "commit", [], "Release notes", stable=True)
        create, edit = [call.args for call in run.call_args_list]
        self.assertIn("--prerelease=false", create)
        self.assertIn("--prerelease=false", edit)
        self.assertIn("--latest=true", edit)


class MonorepoVersionTest(unittest.TestCase):
    def test_ipad_metadata_matches_android_version_authority(self):
        import re
        root = Path(__file__).resolve().parent.parent
        gradle = (root / "android/app/build.gradle.kts").read_text()
        project = (root / "ios/Dotnote.xcodeproj/project.pbxproj").read_text()
        version = re.search(r'versionName\s*=\s*"([^"]+)"', gradle)[1]
        code = re.search(r'versionCode\s*=\s*(\d+)', gradle)[1]
        self.assertEqual([version, version], re.findall(r'MARKETING_VERSION = "([^"]+)"', project))
        self.assertEqual([code, code], re.findall(r'CURRENT_PROJECT_VERSION = "([^"]+)"', project))
