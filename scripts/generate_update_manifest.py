#!/usr/bin/env python3
"""Generate Pocket FX update metadata from the final signed APK."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
from urllib.parse import urlsplit


def apk_metadata(apk, aapt):
    badging = subprocess.run(
        [aapt, "dump", "badging", str(apk)], check=True, capture_output=True, text=True
    ).stdout
    metadata = re.search(
        r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.MULTILINE
    )
    if not metadata or metadata[1] != "com.pocketfx" or int(metadata[2]) < 1:
        raise ValueError("expected a com.pocketfx APK with positive versionCode and versionName")
    return metadata[1], int(metadata[2]), metadata[3]


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as contents:
        for chunk in iter(lambda: contents.read(64 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path, help="Final signed Pocket FX APK")
    parser.add_argument("apk_url", help="Public HTTPS URL of the uploaded APK")
    parser.add_argument("--aapt", default="aapt", help="Path to the Android SDK aapt executable")
    parser.add_argument("--notes", type=Path, help="Optional JSON object with en, ru, and sr-Latn release notes")
    args = parser.parse_args()

    url = urlsplit(args.apk_url)
    if url.scheme != "https" or not url.hostname or url.username is not None or url.fragment:
        parser.error("apk_url must be a public HTTPS URL without credentials or a fragment")

    try:
        package, code, name = apk_metadata(args.apk, args.aapt)
    except ValueError as error:
        parser.error(str(error))

    notes = json.loads(args.notes.read_text(encoding="utf-8")) if args.notes else {}
    if not isinstance(notes, dict) or any(
        key not in {"en", "ru", "sr-Latn"} or not isinstance(value, str) or len(value) > 8_000
        for key, value in notes.items()
    ):
        parser.error("release notes must be strings of up to 8,000 characters keyed by en, ru, or sr-Latn")

    print(json.dumps({
        "versionCode": code,
        "versionName": name,
        "packageName": package,
        "apkUrl": args.apk_url,
        "sha256": sha256(args.apk),
        "releaseNotes": notes,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
