"""Exercise release continuity independently from Android and GitHub."""
import contextlib
import io
import json
from pathlib import Path
import sys
from tempfile import TemporaryDirectory
from types import SimpleNamespace
from unittest.mock import patch
from urllib.error import HTTPError

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import update_release

SIGNER = "1" * 64
LATEST = {
    "tag_name": "android-v0.6.0",
    "assets": [{"name": update_release.MANIFEST}, {"name": update_release.SIGNER}],
}


def check(output, previous, expected_error=None):
    with patch.dict("os.environ", {"GH_TOKEN": "test-only"}), \
            patch.object(update_release, "read_response", side_effect=previous), \
            contextlib.redirect_stdout(io.StringIO()):
        try:
            update_release.check_channel(SimpleNamespace(output=output))
        except ValueError as error:
            if not expected_error or expected_error not in str(error):
                raise
        else:
            if expected_error:
                raise AssertionError("Expected release to be rejected: " + expected_error)


with TemporaryDirectory(prefix="motonav-release-test-") as folder:
    output = Path(folder)
    (output / update_release.MANIFEST).write_text(json.dumps({"versionCode": 7}), encoding="utf-8")
    (output / update_release.SIGNER).write_text(SIGNER + "\n", encoding="ascii")
    latest = json.dumps(LATEST).encode()
    old_manifest = json.dumps({"applicationId": update_release.APPLICATION_ID, "versionCode": 6}).encode()
    check(output, [latest, old_manifest, SIGNER.encode()])
    check(output, [latest, old_manifest, ("2" * 64).encode()], "certificate differs")
    same_version = json.dumps({"applicationId": update_release.APPLICATION_ID, "versionCode": 7}).encode()
    check(output, [latest, same_version, SIGNER.encode()], "must exceed")
    missing_signer = json.dumps({**LATEST, "assets": LATEST["assets"][:1]}).encode()
    check(output, [missing_signer], "Cannot verify")
    invalid_tag = json.dumps({**LATEST, "tag_name": "../wrong"}).encode()
    check(output, [invalid_tag], "Cannot verify")
    check(output, [HTTPError("https://api.github.com", 404, "Not found", {}, None)])

print("PASS: initial release, version progression, signer continuity and unsafe release metadata")
