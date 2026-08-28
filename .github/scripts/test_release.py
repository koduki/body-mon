import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import release


SHA = "a" * 40
CERT = "ab" * 32
ENV = {
    "GITHUB_RUN_NUMBER": "42", "GITHUB_SHA": SHA,
    "GITHUB_REPOSITORY": "koduki/body-mon", "GITHUB_REF": "refs/heads/main",
}


class ReleaseValidationTest(unittest.TestCase):
    def test_versions_are_increasing_and_reruns_are_stable(self):
        self.assertEqual(release.version_for_run("42"), (1042, "0.1.1042"))
        self.assertGreater(release.version_for_run("43")[0], release.version_for_run("42")[0])

    def test_invalid_or_overflowing_versions_are_rejected(self):
        for value in ["", "0", "-1", "1\nversion_code=5", "1.5", "01", "2099999001"]:
            with self.subTest(value=value), self.assertRaises(ValueError):
                release.version_for_run(value)
        self.assertEqual(release.version_for_run("2099999000")[0], 2_100_000_000)

    def test_apk_identity_version_and_debuggability_are_checked(self):
        good = "package: name='com.master.healthcoach' versionCode='1042' versionName='0.1.1042'\n"
        release.verify_badging(good, 1042, "0.1.1042")
        for bad in [good.replace("healthcoach", "different"), good.replace("1042", "1041"),
                    good + "application-debuggable\n", ""]:
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                release.verify_badging(bad, 1042, "0.1.1042")

    def test_only_the_pinned_single_signer_is_accepted(self):
        report = f"Signer #1 certificate SHA-256 digest: {CERT}\n"
        release.verify_certificate(report, ":".join(["AB"] * 32))
        for bad in ["", report.replace("ab", "cd"), report + report.replace("#1", "#2")]:
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                release.verify_certificate(bad, CERT)

    def test_api_failures_are_not_treated_as_a_missing_release(self):
        with patch("release.subprocess.run", return_value=subprocess.CompletedProcess([], 1, '{"status":"403"}', "")):
            with self.assertRaises(RuntimeError):
                release.api_optional("repos/koduki/body-mon/releases/tags/v0.1.1042")
        with patch("release.subprocess.run", return_value=subprocess.CompletedProcess([], 1, '{"status":"404"}', "")):
            self.assertIsNone(release.api_optional("repos/koduki/body-mon/releases/tags/v0.1.1042"))

    def test_missing_signing_configuration_fails_before_any_command(self):
        with patch.dict(os.environ, {}, clear=True), patch("release.command") as cmd:
            with self.assertRaises(ValueError):
                release.sign("unused.apk", "unused-output")
            cmd.assert_not_called()

    def test_private_key_is_removed_when_signing_fails(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            apk = root / "unsigned.apk"
            apk.write_bytes(b"unsigned fixture")
            env = dict(ENV, ANDROID_HOME=str(root / "sdk"), RUNNER_TEMP=str(root),
                       ANDROID_SIGNING_CERT_SHA256=CERT, ANDROID_KEY_ALIAS="test",
                       ANDROID_KEYSTORE_BASE64=base64.b64encode(b"test key fixture").decode(),
                       ANDROID_KEYSTORE_PASSWORD="test", ANDROID_KEY_PASSWORD="test")
            badging = "package: name='com.master.healthcoach' versionCode='1042' versionName='0.1.1042'\n"
            with patch.dict(os.environ, env, clear=True), patch("release.command", side_effect=[badging, "", RuntimeError("signing failed")]):
                with self.assertRaises(RuntimeError):
                    release.sign(apk, root / "output")
            self.assertEqual(list(root.glob("body-mon-signing-*")), [])
            self.assertFalse((root / "output" / "release-metadata.json").exists())


class PublishTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.output = Path(self.temp.name)
        apk = b"signed APK fixture; publishing never runs Android tools"
        digest = hashlib.sha256(apk).hexdigest()
        (self.output / "body-mon.apk").write_bytes(apk)
        (self.output / "SHA256SUMS").write_text(f"{digest}  body-mon.apk\n")
        (self.output / "release-metadata.json").write_text(json.dumps({
            "commit": SHA, "versionCode": 1042, "versionName": "0.1.1042", "sha256": digest,
        }))
        env = patch.dict(os.environ, ENV, clear=True)
        env.start()
        self.addCleanup(env.stop)
        summaries = patch("release.summary")
        summaries.start()
        self.addCleanup(summaries.stop)
        self.head = {"object": {"sha": SHA, "type": "commit"}}
        self.assets = [{"name": asset, "size": (self.output / asset).stat().st_size} for asset in release.ASSETS]

    def test_old_commit_is_never_published(self):
        with patch("release.api_optional", return_value={"object": {"sha": "b" * 40}}), patch("release.command") as cmd:
            release.publish(self.output)
            cmd.assert_not_called()

    def test_published_release_is_never_overwritten_on_rerun(self):
        with patch("release.api_optional", side_effect=[self.head, self.head, {"draft": False}]), patch("release.command") as cmd:
            release.publish(self.output)
            cmd.assert_not_called()

    def test_tag_collision_is_rejected(self):
        bad_ref = {"object": {"sha": "b" * 40, "type": "commit"}}
        with patch("release.api_optional", side_effect=[self.head, bad_ref]), patch("release.command") as cmd:
            with self.assertRaises(ValueError):
                release.publish(self.output)
            cmd.assert_not_called()

    def test_new_release_is_published_only_after_complete_draft_upload(self):
        responses = [self.head, None, None, {"assets": self.assets}, {"draft": False}]
        with patch("release.api_optional", side_effect=responses), patch("release.command") as cmd:
            release.publish(self.output)
            calls = [call.args[0] for call in cmd.call_args_list]
            self.assertEqual([call[:3] for call in calls[1:]], [
                ["gh", "release", "create"], ["gh", "release", "upload"], ["gh", "release", "edit"],
            ])
            self.assertIn("--draft", calls[1])
            self.assertIn("--draft=false", calls[-1])

    def test_interrupted_draft_can_be_retried(self):
        responses = [self.head, self.head, {"draft": True, "target_commitish": SHA},
                     {"assets": self.assets}, {"draft": False}]
        with patch("release.api_optional", side_effect=responses), patch("release.command") as cmd:
            release.publish(self.output)
            calls = [call.args[0] for call in cmd.call_args_list]
            self.assertEqual(calls[0][:3], ["gh", "release", "upload"])
            self.assertIn("--clobber", calls[0])

    def test_partial_upload_stays_a_draft(self):
        responses = [self.head, None, None, {"assets": self.assets[:1]}]
        with patch("release.api_optional", side_effect=responses), patch("release.command") as cmd:
            with self.assertRaises(RuntimeError):
                release.publish(self.output)
            self.assertFalse(any(call.args[0][:3] == ["gh", "release", "edit"] for call in cmd.call_args_list))

    def test_bad_artifacts_are_rejected_before_any_remote_write(self):
        for asset in release.ASSETS:
            with self.subTest(asset=asset):
                path = self.output / asset
                original = path.read_bytes()
                path.write_bytes(b"corrupted")
                with patch("release.command") as cmd, self.assertRaises((ValueError, json.JSONDecodeError)):
                    release.publish(self.output)
                cmd.assert_not_called()
                path.write_bytes(original)

    def test_forks_and_non_main_refs_cannot_publish(self):
        for override in [{"GITHUB_REPOSITORY": "someone/body-mon"}, {"GITHUB_REF": "refs/pull/7/merge"}]:
            with self.subTest(override=override), patch.dict(os.environ, override), patch("release.command") as cmd:
                with self.assertRaises(ValueError):
                    release.publish(self.output)
                cmd.assert_not_called()


@unittest.skipUnless(os.environ.get("RELEASE_TEST_APK"), "Requires the built release APK and Android SDK (CI)")
class SigningSmokeTest(unittest.TestCase):
    def test_real_release_apk_can_be_signed_and_verified_without_persisting_the_key(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            key = root / "disposable.keystore"
            # This key is generated for this test only and is never a release asset.
            password = "disposable-ci-test-only"
            release.command(["keytool", "-genkeypair", "-keystore", str(key), "-storepass", password,
                             "-keypass", password, "-alias", "smoke", "-keyalg", "RSA", "-keysize", "2048",
                             "-validity", "2", "-dname", "CN=Disposable CI Signing Test", "-noprompt"])
            certificate = subprocess.run(["keytool", "-exportcert", "-keystore", str(key),
                                          "-storepass", password, "-alias", "smoke"],
                                         check=True, capture_output=True).stdout
            cert = hashlib.sha256(certificate).hexdigest()
            env = {
                "ANDROID_KEYSTORE_BASE64": base64.b64encode(key.read_bytes()).decode("ascii"),
                "ANDROID_KEYSTORE_PASSWORD": password, "ANDROID_KEY_PASSWORD": password,
                "ANDROID_KEY_ALIAS": "smoke", "ANDROID_SIGNING_CERT_SHA256": cert,
                "RUNNER_TEMP": str(root),
            }
            with patch.dict(os.environ, env):
                release.sign(os.environ["RELEASE_TEST_APK"], root / "output")
            metadata = json.loads((root / "output" / "release-metadata.json").read_text())
            self.assertEqual(metadata["signingCertificateSha256"], cert)
            self.assertEqual(metadata["sha256"], hashlib.sha256((root / "output" / "body-mon.apk").read_bytes()).hexdigest())
            self.assertEqual(list(root.glob("body-mon-signing-*")), [])


if __name__ == "__main__":
    unittest.main()
