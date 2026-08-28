"""Build/version/sign/publish helpers for the single Obtainium release channel."""

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile


APP_ID = "com.master.healthcoach"
ASSETS = ("body-mon.apk", "SHA256SUMS", "release-metadata.json")


def version_for_run(run_number):
    if not re.fullmatch(r"[1-9][0-9]*", run_number):
        raise ValueError("GITHUB_RUN_NUMBER must be a positive integer.")
    code = 1000 + int(run_number)
    if code > 2_100_000_000:
        raise ValueError("Android versionCode limit exceeded.")
    return code, f"0.1.{code}"


def required_env(name):
    value = os.environ.get(name, "")
    if not value:
        raise ValueError(f"Set {name} before enabling APK releases.")
    return value


def commit_sha():
    value = required_env("GITHUB_SHA")
    if not re.fullmatch(r"[0-9a-f]{40}", value):
        raise ValueError("GITHUB_SHA must be a full commit SHA.")
    return value


def fingerprint(value):
    value = re.sub(r"[:\s]", "", value).lower()
    if not re.fullmatch(r"[0-9a-f]{64}", value):
        raise ValueError("ANDROID_SIGNING_CERT_SHA256 must contain a SHA-256 fingerprint.")
    return value


def command(args):
    result = subprocess.run(args, text=True, capture_output=True)
    if result.returncode:
        # Do not echo command arguments, environment variables, or signing errors
        # that could expose credentials. The failing tool's name is sufficient.
        raise RuntimeError(f"{Path(args[0]).name} failed (exit {result.returncode}).")
    return result.stdout


def verify_badging(badging, code, name):
    package_line = next((line for line in badging.splitlines() if line.startswith("package: ")), "")
    attrs = dict(re.findall(r"(\w+)='([^']*)'", package_line))
    if (attrs.get("name"), attrs.get("versionCode"), attrs.get("versionName")) != (
        APP_ID, str(code), name
    ):
        raise ValueError("APK package/version does not match this workflow run.")
    if "application-debuggable" in badging:
        raise ValueError("Refusing to distribute a debuggable APK.")


def verify_certificate(report, expected):
    certificates = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)$", report, re.M)
    if len(certificates) != 1 or fingerprint(certificates[0]) != fingerprint(expected):
        raise ValueError("APK signing certificate does not match ANDROID_SIGNING_CERT_SHA256.")


def sign(apk, output):
    code, name = version_for_run(required_env("GITHUB_RUN_NUMBER"))
    sha = commit_sha()
    expected = fingerprint(required_env("ANDROID_SIGNING_CERT_SHA256"))
    encoded = required_env("ANDROID_KEYSTORE_BASE64")
    alias = required_env("ANDROID_KEY_ALIAS")
    required_env("ANDROID_KEYSTORE_PASSWORD")
    required_env("ANDROID_KEY_PASSWORD")
    sdk = Path(required_env("ANDROID_HOME")) / "build-tools" / "36.0.0"
    apk = Path(apk).resolve(strict=True)
    verify_badging(command([str(sdk / "aapt2"), "dump", "badging", str(apk)]), code, name)
    command([str(sdk / "zipalign"), "-c", "4", str(apk)])
    key_bytes = base64.b64decode("".join(encoded.split()), validate=True)
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    signed_apk = output / "body-mon.apk"
    with tempfile.TemporaryDirectory(prefix="body-mon-signing-", dir=os.environ.get("RUNNER_TEMP")) as temporary:
        key = Path(temporary) / "signing.keystore"
        key.write_bytes(key_bytes)
        key.chmod(0o600)
        command([
            str(sdk / "apksigner"), "sign", "--ks", str(key), "--ks-key-alias", alias,
            "--ks-pass", "env:ANDROID_KEYSTORE_PASSWORD", "--key-pass", "env:ANDROID_KEY_PASSWORD",
            "--out", str(signed_apk), str(apk),
        ])
    # The private key has been removed, including on a failed sign operation.
    report = command([str(sdk / "apksigner"), "verify", "--verbose", "--print-certs", str(signed_apk)])
    verify_certificate(report, expected)
    digest = hashlib.sha256(signed_apk.read_bytes()).hexdigest()
    (output / "SHA256SUMS").write_text(f"{digest}  body-mon.apk\n", encoding="utf-8")
    metadata = {
        "applicationId": APP_ID, "versionCode": code, "versionName": name,
        "commit": sha, "sha256": digest, "signingCertificateSha256": expected,
    }
    (output / "release-metadata.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")


def api_optional(path):
    result = subprocess.run(["gh", "api", path], text=True, capture_output=True)
    try:
        data = json.loads(result.stdout)
    except json.JSONDecodeError as error:
        raise RuntimeError("GitHub API did not return valid JSON.") from error
    if result.returncode:
        if data.get("status") == "404":
            return None
        raise RuntimeError("GitHub API request failed; release was not published.")
    return data


def summary(message):
    print(message)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as handle:
            handle.write(message + "\n")


def publish(output):
    code, name = version_for_run(required_env("GITHUB_RUN_NUMBER"))
    sha = commit_sha()
    repo = required_env("GITHUB_REPOSITORY")
    if repo != "koduki/body-mon" or required_env("GITHUB_REF") != "refs/heads/main":
        raise ValueError("Releases are only allowed from koduki/body-mon main.")
    output = Path(output)
    for asset in ASSETS:
        if not (output / asset).is_file() or (output / asset).stat().st_size == 0:
            raise ValueError(f"Missing release asset: {asset}")
    metadata = json.loads((output / "release-metadata.json").read_text(encoding="utf-8"))
    if (metadata.get("commit"), metadata.get("versionCode"), metadata.get("versionName")) != (sha, code, name):
        raise ValueError("Release metadata does not match this workflow run.")
    digest = hashlib.sha256((output / "body-mon.apk").read_bytes()).hexdigest()
    if metadata.get("sha256") != digest or (output / "SHA256SUMS").read_text() != f"{digest}  body-mon.apk\n":
        raise ValueError("Release APK checksum mismatch.")

    # A rerun of an older workflow must never replace the latest app version.
    head = api_optional(f"repos/{repo}/git/ref/heads/main")
    if not head or head["object"]["sha"] != sha:
        summary("Skipped release: this commit is no longer the main branch HEAD.")
        return
    tag = f"v{name}"
    ref = api_optional(f"repos/{repo}/git/ref/tags/{tag}")
    if ref:
        if ref["object"]["type"] != "commit" or ref["object"]["sha"] != sha:
            raise ValueError("Release tag already points to a different commit.")
    else:
        command(["gh", "api", f"repos/{repo}/git/refs", "--method", "POST", "-f", f"ref=refs/tags/{tag}", "-f", f"sha={sha}"])
    existing = api_optional(f"repos/{repo}/releases/tags/{tag}")
    if existing and not existing["draft"]:
        summary(f"Release {tag} is already published; its assets were left unchanged.")
        return
    if existing and existing["target_commitish"] != sha:
        raise ValueError("Draft release targets a different commit.")

    notes = output / "release-notes.md"
    notes.write_text(
        f"## Health Coach {name}\n\n"
        f"Commit: {sha}\n\n"
        "Obtainiumに https://github.com/koduki/body-mon を登録すると更新を受け取れます。\n\n"
        "APKは body-mon.apk です。署名のSHA-256とソースコミットは release-metadata.json を確認してください。\n\n"
        "既存アプリはアンインストールしないでください。署名が一致する場合に上書き更新します。\n",
        encoding="utf-8",
    )
    if not existing:
        command(["gh", "release", "create", tag, "--repo", repo, "--verify-tag", "--target", sha,
                 "--draft", "--title", f"Health Coach {name}", "--notes-file", str(notes)])
    # Only drafts may be overwritten when recovering from an interrupted upload.
    command(["gh", "release", "upload", tag, "--repo", repo, "--clobber", *[str(output / asset) for asset in ASSETS]])
    uploaded = api_optional(f"repos/{repo}/releases/tags/{tag}")
    sizes = {asset["name"]: asset["size"] for asset in uploaded["assets"]} if uploaded else {}
    if any(sizes.get(asset) != (output / asset).stat().st_size for asset in ASSETS):
        raise RuntimeError("Draft release assets are incomplete; it was not published.")
    command(["gh", "release", "edit", tag, "--repo", repo, "--draft=false", "--latest"])
    published = api_optional(f"repos/{repo}/releases/tags/{tag}")
    if not published or published["draft"]:
        raise RuntimeError("Could not verify that the release was published.")
    summary(f"Published [{tag}](https://github.com/{repo}/releases/tag/{tag}).")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    sub.add_parser("version")
    signing = sub.add_parser("sign")
    signing.add_argument("apk", type=Path)
    signing.add_argument("output", type=Path)
    publishing = sub.add_parser("publish")
    publishing.add_argument("output", type=Path)
    args = parser.parse_args()
    try:
        if args.action == "version":
            code, name = version_for_run(required_env("GITHUB_RUN_NUMBER"))
            print(f"version_code={code}\nversion_name={name}")
        elif args.action == "sign":
            sign(args.apk, args.output)
        else:
            publish(args.output)
    except (ValueError, RuntimeError, OSError) as error:
        parser.exit(1, f"Release failed: {error}\n")


if __name__ == "__main__":
    main()
