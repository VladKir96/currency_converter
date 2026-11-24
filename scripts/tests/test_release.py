from contextlib import redirect_stdout
import io
import json
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import Mock, patch
from urllib.error import HTTPError

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import pocketfx_release as release


class ReleaseTest(unittest.TestCase):
    def setUp(self):
        self.repo = release.Repo("VladKir96/currency_converter")
        self.tools = Mock()

    def test_derives_repository_and_stable_feed_from_supported_remote_forms(self):
        for remote in (
            "https://github.com/VladKir96/currency_converter.git",
            "git@github.com:VladKir96/currency_converter.git",
            "ssh://git@github.com/VladKir96/currency_converter.git",
            "https://github.com/VladKir96/currency_converter",
        ):
            with patch.object(release, "call", return_value=remote):
                self.assertEqual(self.repo, release.Repo.detect())
        self.assertEqual(
            "https://github.com/VladKir96/currency_converter/releases/latest/download/update-manifest.json",
            self.repo.manifest,
        )

    def test_code_is_greater_than_project_installed_and_every_published_version(self):
        published = [release.PublishedApk(50, "1.6.0", "first"), release.PublishedApk(19, "8.0.0", "second")]
        self.assertEqual(51, release.version_code(4, 22, published))
        self.assertEqual(72, release.version_code(4, 71, published))
        self.assertEqual(5, release.version_code(4, None, []))
        with self.assertRaises(release.ReleaseError):
            release.version_code(2_147_483_647, None, [])

    def test_required_published_metadata_uses_its_own_asset(self):
        with tempfile.TemporaryDirectory() as temp:
            entries = [{"draft": False, "tag_name": "v1.4.0",
                        "assets": [{"name": release.MANIFEST}, {"name": "pocketfx-1.4.0.apk"}]}]
            metadata = self.manifest()
            with patch.object(release, "public_json", return_value=metadata):
                self.assertEqual([release.PublishedApk(5, "1.4.0", metadata["apkUrl"], "a" * 64)],
                                 release.published_apks(self.repo, entries, self.tools, Path(temp)))
            metadata["apkUrl"] = self.repo.asset("v1.3.0", "pocketfx-1.3.0.apk")
            with patch.object(release, "public_json", return_value=metadata), self.assertRaises(release.ReleaseError):
                release.published_apks(self.repo, entries, self.tools, Path(temp))

    def test_disallows_insecure_or_unrecognized_redirects(self):
        release.validate_https("https://release-assets.githubusercontent.com/assets/123")
        for url in ("http://github.com/example", "https://github.com.evil.example/file", "https://name@github.com/file"):
            with self.assertRaises(release.ReleaseError):
                release.validate_https(url)

    def test_backup_requires_readable_nonempty_data_under_app_directories(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "preferences.tar"
            self.write_tar(path, "files/datastore/pocket_fx_preferences.preferences_pb", b"stored data")
            self.assertEqual(11, release.verify_backup(path))
            self.write_tar(path, "files/datastore/prefs", b"")
            with self.assertRaises(release.ReleaseError):
                release.verify_backup(path)
            self.write_tar(path, "../other", b"invalid")
            with self.assertRaises(release.ReleaseError):
                release.verify_backup(path)

    def test_dirty_worktree_prevents_publication(self):
        with patch.object(release, "call", return_value=" M README.md"), self.assertRaises(release.ReleaseError):
            release.clean_head()

    def test_verifies_both_assets_before_making_draft_public(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            apk = folder / "pocketfx-1.4.0.apk"
            apk.write_bytes(b"verified fixture")
            expected = self.manifest()
            expected["sha256"] = release.sha256(apk)

            def gh(*args, **kwargs):
                if args[1:3] == ("release", "download"):
                    draft = folder / "draft"
                    (draft / apk.name).write_bytes(b"corrupt download")
                    (draft / release.MANIFEST).write_text(json.dumps(expected))
                return ""

            with patch.object(release, "authenticated", side_effect=gh) as requests, \
                    patch.object(release, "clean_head", return_value="source-sha"), \
                    patch.object(release, "release_list", return_value=[]), redirect_stdout(io.StringIO()):
                with self.assertRaisesRegex(release.ReleaseError, "failed verification"):
                    release.publish(self.repo, object(), folder, apk, expected, self.tools, "source-sha", folder)
                self.assertFalse(any(invocation.args[1:3] == ("release", "edit") for invocation in requests.call_args_list))

    def test_publication_checks_latest_manifest_and_versioned_assets(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            apk = folder / "pocketfx-1.4.0.apk"
            artifact = b"verified fixture"
            apk.write_bytes(artifact)
            expected = self.manifest()
            expected["sha256"] = release.sha256(apk)
            checked_urls = []

            def gh(*args, **kwargs):
                if args[1:3] == ("release", "download"):
                    draft = folder / "draft"
                    (draft / apk.name).write_bytes(artifact)
                    (draft / release.MANIFEST).write_text(json.dumps(expected))
                return ""

            def public_json(url):
                if "/git/ref/tags/" in url:
                    raise HTTPError(url, 404, "No tag yet", None, None)
                checked_urls.append(url)
                return expected

            def stable_manifest(url):
                checked_urls.append(url)
                return io.BytesIO(json.dumps(expected).encode())

            def download(url, destination):
                checked_urls.append(url)
                destination.write_bytes(artifact)

            with patch.object(release, "authenticated", side_effect=gh) as requests, \
                    patch.object(release, "clean_head", return_value="source-sha"), \
                    patch.object(release, "release_list", return_value=[]), \
                    patch.object(release, "public_json", side_effect=public_json), \
                    patch.object(release, "open_public", side_effect=stable_manifest), \
                    patch.object(release, "public_file", side_effect=download), \
                    patch.object(release, "apk_metadata", return_value=(release.PACKAGE, 5, "1.4.0")), \
                    redirect_stdout(io.StringIO()):
                release.publish(self.repo, object(), folder, apk, expected, self.tools, "source-sha", folder)
            self.assertEqual(["create", "upload", "download", "edit"], [item.args[2] for item in requests.call_args_list])
            self.assertIn("--draft", requests.call_args_list[0].args)
            self.assertIn("--latest", requests.call_args_list[-1].args)
            self.assertEqual(
                [self.repo.asset("v1.4.0", release.MANIFEST), self.repo.manifest, expected["apkUrl"]],
                checked_urls,
            )

    def manifest(self):
        return {
            "versionCode": 5,
            "versionName": "1.4.0",
            "packageName": release.PACKAGE,
            "apkUrl": self.repo.asset("v1.4.0", "pocketfx-1.4.0.apk"),
            "sha256": "a" * 64,
            "releaseNotes": {"en": "Pocket FX application update."},
        }

    @staticmethod
    def write_tar(path, name, content):
        with tarfile.open(path, "w") as archive:
            entry = tarfile.TarInfo(name)
            entry.size = len(content)
            archive.addfile(entry, io.BytesIO(content))


if __name__ == "__main__":
    unittest.main()
