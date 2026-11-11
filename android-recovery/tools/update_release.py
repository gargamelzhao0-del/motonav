"""Build the v1 release manifest from a signed APK and check channel continuity."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
from urllib.error import HTTPError
from urllib.request import Request, urlopen

REPOSITORY = "gargamelzhao0-del/motonav"
APPLICATION_ID = "com.motonav.recovery"
MANIFEST = "motonav-update.json"
SIGNER = "motonav-signer.sha256"
MAX_APK_BYTES = 150 * 1024 * 1024
VERSION = r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)"
FINGERPRINT = r"[0-9a-f]{64}"


def run_tool(executable, *arguments):
    return subprocess.run([str(executable), *map(str, arguments)], check=True,
                          stdout=subprocess.PIPE, encoding="utf-8").stdout


def prepare(args):
    apk = args.apk
    size = apk.stat().st_size
    if not 0 < size <= MAX_APK_BYTES:
        raise ValueError("APK must fit the app's 150 MiB download limit")

    badging = run_tool(args.build_tools / "aapt", "dump", "badging", apk)
    package = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'",
                        badging, re.M)
    sdk = re.search(r"^sdkVersion:'([0-9]+)'$", badging, re.M)
    if not package or not sdk or package.group(1) != APPLICATION_ID:
        raise ValueError("Expected the MotoNav application ID and an APK with version metadata")
    version_code, version_name, min_sdk = int(package.group(2)), package.group(3), int(sdk.group(1))
    if not 0 < version_code <= 2147483647 or not 26 <= min_sdk <= 2147483647:
        raise ValueError("APK versionCode or minSdk is outside the supported range")
    if not re.fullmatch(VERSION, version_name) or len(version_name) > 80:
        raise ValueError("Use a numeric major.minor.patch versionName")
    tag = "android-v" + version_name
    if args.tag and args.tag != tag:
        raise ValueError(f"Release tag must be {tag} for this APK")

    certificate = run_tool(args.build_tools / "apksigner", "verify", "--verbose", "--print-certs", apk)
    signers = re.findall(r"^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$",
                         certificate, re.M)
    if len(signers) != 1:
        raise ValueError("Expected a verified APK with one signer")

    asset = f"MotoNav-{version_name}.apk"
    with apk.open("rb") as source:
        sha256 = hashlib.file_digest(source, "sha256").hexdigest()
    release = {
        "schemaVersion": 1,
        "applicationId": APPLICATION_ID,
        "versionCode": version_code,
        "versionName": version_name,
        "minSdk": min_sdk,
        "apkUrl": f"https://github.com/{REPOSITORY}/releases/download/{tag}/{asset}",
        "sha256": sha256,
        "sizeBytes": size,
    }
    args.output.mkdir(parents=True)
    shutil.copyfile(apk, args.output / asset)
    (args.output / MANIFEST).write_text(json.dumps(release, indent=2) + "\n", encoding="utf-8")
    (args.output / SIGNER).write_text(signers[0].lower() + "\n", encoding="ascii")
    (args.output / "certificate.txt").write_text(certificate, encoding="utf-8")
    (args.output / "SHA256SUMS.txt").write_text(f"{sha256}  {asset}\n", encoding="ascii")
    print(f"Verified {APPLICATION_ID} {version_name} ({version_code}), {size} bytes")


def read_response(url, limit, headers=None):
    with urlopen(Request(url, headers=headers or {}), timeout=20) as response:
        value = response.read(limit + 1)
    if len(value) > limit:
        raise ValueError("Release metadata exceeds its size limit")
    return value


def check_channel(args):
    current = json.loads((args.output / MANIFEST).read_text(encoding="utf-8"))
    signer = (args.output / SIGNER).read_text(encoding="ascii").strip()
    if not re.fullmatch(FINGERPRINT, signer):
        raise ValueError("Invalid signer fingerprint")
    try:
        latest = json.loads(read_response(
            f"https://api.github.com/repos/{REPOSITORY}/releases/latest", 1024 * 1024,
            {"Authorization": "Bearer " + os.environ["GH_TOKEN"], "Accept": "application/vnd.github+json"}))
    except HTTPError as error:
        if error.code == 404:
            print("No current published release")
            return
        raise

    names = {asset["name"] for asset in latest["assets"]}
    if MANIFEST not in names:
        print("The current latest release has no Android update manifest")
        return
    tag = latest["tag_name"]
    if SIGNER not in names or not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9._-]{0,159}", tag) or ".." in tag:
        raise ValueError("Cannot verify the previous release signer")
    base = f"https://github.com/{REPOSITORY}/releases/download/{tag}/"
    previous = json.loads(read_response(base + MANIFEST, 32 * 1024))
    previous_signer = read_response(base + SIGNER, 256).decode("ascii").strip()
    if previous.get("applicationId") != APPLICATION_ID or type(previous.get("versionCode")) is not int:
        raise ValueError("Invalid previous release metadata")
    if previous["versionCode"] >= current["versionCode"]:
        raise ValueError("The new versionCode must exceed the published versionCode")
    if previous_signer != signer:
        raise ValueError("The signing certificate differs from the published update")
    print("Verified version progression and signing continuity")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    package = commands.add_parser("prepare")
    package.add_argument("--apk", type=Path, required=True)
    package.add_argument("--build-tools", type=Path, required=True)
    package.add_argument("--output", type=Path, required=True)
    package.add_argument("--tag", default="")
    package.set_defaults(handler=prepare)
    channel = commands.add_parser("check-channel")
    channel.add_argument("--output", type=Path, required=True)
    channel.set_defaults(handler=check_channel)
    args = parser.parse_args()
    args.handler(args)


if __name__ == "__main__":
    main()
