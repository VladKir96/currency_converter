#!/usr/bin/env python3
"""Prepare, verify, and publish Pocket FX APKs from an existing GitHub checkout."""
from __future__ import annotations

import argparse
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
import fcntl
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener
from zipfile import ZipFile

from generate_update_manifest import apk_metadata, sha256

ROOT = Path(__file__).resolve().parent.parent
SUPPORT = Path.home() / "Library/Application Support/Pocket FX"
PACKAGE = "com.pocketfx"
MANIFEST = "update-manifest.json"
VERSION = re.compile(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)")
CERT = re.compile(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})$", re.MULTILINE)
SHA256 = re.compile(r"[0-9a-f]{64}")
ASSET_HOSTS = {"github.com", "api.github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"}


class ReleaseError(Exception):
    pass


def call(*command, env=None, given=None, subject=None, stream=False):
    result = subprocess.run(
        [str(word) for word in command], cwd=ROOT, env=env, input=given,
        text=True, capture_output=not stream,
    )
    if result.returncode != 0:
        raise ReleaseError(f"{subject or Path(command[0]).name} failed (exit {result.returncode}).")
    return result.stdout.strip() if result.stdout is not None else ""


@dataclass(frozen=True)
class Repo:
    slug: str

    @classmethod
    def detect(cls):
        remote = call("git", "remote", "get-url", "origin", subject="Reading Git origin")
        match = re.fullmatch(r"(?:https://github\.com/|git@github\.com:|ssh://git@github\.com/)"
                             r"([\w.-]+/[\w.-]+?)(?:\.git)?/?", remote)
        if not match:
            raise ReleaseError("Set origin to the Pocket FX repository on github.com.")
        return cls(match[1])

    @property
    def api(self):
        return f"repos/{self.slug}"

    @property
    def manifest(self):
        return f"https://github.com/{self.slug}/releases/latest/download/{MANIFEST}"

    def asset(self, tag, name):
        return f"https://github.com/{self.slug}/releases/download/{quote(tag, safe='')}/{quote(name, safe='')}"

    def release_url(self, tag):
        return f"https://github.com/{self.slug}/releases/tag/{quote(tag, safe='')}"


class GitHubRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        validate_https(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def validate_https(url):
    parsed = urlsplit(url)
    if parsed.scheme != "https" or parsed.hostname not in ASSET_HOSTS or parsed.username or parsed.password:
        raise ReleaseError("GitHub redirected to an unexpected download origin.")


def open_public(url):
    validate_https(url)
    return build_opener(GitHubRedirect()).open(Request(url, headers={"User-Agent": "Pocket-FX-release",
                                                                  "Cache-Control": "no-cache"}), timeout=30)


def public_json(url):
    limit = 128 * 1024 if "/download/" in url else 8 * 1024 * 1024
    with open_public(url) as response:
        payload = response.read(limit + 1)
    if len(payload) > limit:
        raise ReleaseError("The GitHub JSON response exceeded the expected size.")
    return json.loads(payload)


def public_file(url, destination):
    with open_public(url) as response, destination.open("wb") as output:
        total = 0
        while block := response.read(64 * 1024):
            total += len(block)
            if total > 512 * 1024 * 1024:
                raise ReleaseError("The published APK exceeded the download size limit.")
            output.write(block)


def github_cli():
    explicit = os.environ.get("POCKET_FX_GH")
    gh = explicit or shutil.which("gh") or SUPPORT / "tools/gh"
    return str(gh) if Path(gh).is_file() and os.access(gh, os.X_OK) else None


def github_commands(repo):
    """Return CLI and child-only environment if GitHub grants write access."""
    gh = github_cli()
    if gh is None:
        return None
    environment = dict(os.environ, GH_PROMPT_DISABLED="1")

    def can_publish(env):
        check = subprocess.run(
            [gh, "api", "--hostname", "github.com", repo.api, "--jq", ".permissions.push // false"],
            cwd=ROOT, env=env, capture_output=True, text=True,
        )
        return check.returncode == 0 and check.stdout.strip() == "true"

    if can_publish(environment):
        return gh, environment
    helper_env = dict(environment, GIT_TERMINAL_PROMPT="0", GIT_ASKPASS=shutil.which("false") or "/usr/bin/false")
    filled = subprocess.run(
        ["git", "credential", "fill"], cwd=ROOT, env=helper_env,
        input=f"protocol=https\nhost=github.com\npath={repo.slug}.git\n\n",
        text=True, capture_output=True, timeout=15,
    )
    if filled.returncode == 0:
        fields = dict(line.split("=", 1) for line in filled.stdout.splitlines() if "=" in line)
        if fields.get("host") == "github.com" and fields.get("password"):
            environment["GH_TOKEN"] = fields["password"]
            if can_publish(environment):
                return gh, environment
    return None


def authenticated(session, *args, subject=None):
    gh, env = session
    return call(gh, *args, env=env, subject=subject or "GitHub CLI")


def release_list(repo, session):
    if session:
        pages = json.loads(authenticated(session, "api", "--hostname", "github.com", "--paginate",
                                        "--slurp", f"{repo.api}/releases?per_page=100", subject="Listing GitHub releases"))
        return [release for page in pages for release in page]
    result = []
    for index in range(1, 101):
        items = public_json(f"https://api.github.com/{repo.api}/releases?per_page=100&page={index}")
        result.extend(items)
        if len(items) < 100:
            return result
    raise ReleaseError("The GitHub release inventory exceeds the pagination limit.")


@dataclass(frozen=True)
class AndroidTools:
    sdk: Path
    java: Path
    build: Path

    @property
    def aapt(self):
        return self.build / "aapt"

    @property
    def adb(self):
        candidate = self.sdk / "platform-tools/adb"
        return str(candidate) if candidate.is_file() else shutil.which("adb")

    @property
    def env(self):
        return dict(os.environ, JAVA_HOME=str(self.java), ANDROID_HOME=str(self.sdk),
                    ANDROID_SDK_ROOT=str(self.sdk))

    @classmethod
    def detect(cls):
        local = {}
        if (ROOT / "local.properties").is_file():
            local = read_properties(ROOT / "local.properties")
        sdk_candidates = [
            os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"), local.get("sdk.dir"),
            Path.home() / "Library/Android/sdk", Path.home() / "Android/Sdk", SUPPORT / "tools/android-sdk",
        ]
        sdk = None
        build = None
        for value in sdk_candidates:
            if not value:
                continue
            candidate = Path(value).expanduser()
            if not any((candidate / "platforms" / name / "android.jar").is_file() for name in ("android-37.0", "android-37")):
                continue
            programs = [path for path in (candidate / "build-tools").glob("*")
                        if all((path / name).is_file() for name in ("aapt", "apksigner"))]
            if programs:
                sdk = candidate
                build = max(programs, key=lambda p: tuple(int(n) for n in re.findall(r"\d+", p.name)))
                break
        if sdk is None:
            raise ReleaseError("Install Android SDK Platform 37 and Build Tools with aapt and apksigner, or set ANDROID_HOME.")
        java_candidates = [os.environ.get("JAVA_HOME"), SUPPORT / "tools/jdk-17",
                           "/Applications/Android Studio.app/Contents/jbr/Contents/Home"]
        if Path("/usr/libexec/java_home").is_file():
            found = subprocess.run(["/usr/libexec/java_home", "-v", "17+"], capture_output=True, text=True)
            if found.returncode == 0:
                java_candidates.insert(1, found.stdout.strip())
        java = next((Path(value).expanduser() for value in java_candidates
                     if value and (Path(value).expanduser() / "bin/javac").is_file()), None)
        if java is None:
            raise ReleaseError("Install JDK 17 or later and set JAVA_HOME.")
        return cls(sdk, java, build)

    def fingerprint(self, apk, require_release=True):
        args = [self.build / "apksigner", "verify", "--print-certs"]
        if require_release:
            args.append("-Werr")
        output = call(*args, apk, env=self.env, subject="APK signature verification")
        signatures = tuple(sorted(CERT.findall(output)))
        if not signatures:
            raise ReleaseError("The APK has no verified signing certificate.")
        return signatures


def read_properties(path):
    result = {}
    for line in path.read_text().splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            result[key.strip()] = value.strip().replace(r"\ ", " ")
    return result


@dataclass(frozen=True)
class Device:
    serial: str
    code: int
    version: str
    signers: tuple[str, ...]


def device_call(tools, serial, *args, subject=None):
    return call(tools.adb, "-s", serial, *args, subject=subject or "ADB")


def inspect_device(tools, temp, serial=None):
    if tools.adb is None:
        print("ADB unavailable. Device inspection was skipped.")
        return None
    output = call(tools.adb, "devices", subject="Listing Android devices")
    entries = [row.split()[:2] for row in output.splitlines()[1:] if len(row.split()) >= 2]
    selected = serial or os.environ.get("ANDROID_SERIAL")
    if selected:
        entries = [item for item in entries if item[0] == selected]
    if not entries:
        print("No connected Android device was detected.")
        return None
    if len(entries) > 1:
        raise ReleaseError("Multiple Android devices were detected. Select one with --serial.")
    serial, status = entries[0]
    if status != "device":
        raise ReleaseError("The Android device is not authorized. Approve USB debugging on the device.")
    paths = subprocess.run([tools.adb, "-s", serial, "shell", "pm", "path", PACKAGE],
                           capture_output=True, text=True)
    device_apk = next((row[8:] for row in paths.stdout.splitlines() if row.startswith("package:") and row.endswith("/base.apk")), None)
    if not device_apk:
        if paths.stdout.strip() or paths.stderr.strip():
            raise ReleaseError("Could not identify the installed Pocket FX base APK.")
        print("Pocket FX is not installed on the selected device.")
        return None
    pulled = temp / "installed.apk"
    device_call(tools, serial, "pull", device_apk, pulled, subject="Copying the installed base APK for inspection")
    _, code, version = apk_metadata(pulled, tools.aapt)
    signers = tools.fingerprint(pulled, require_release=False)
    print(f"Installed Pocket FX {version} (versionCode {code}).")
    print(f"Installed signing certificate SHA-256 {' / '.join(signers)}")
    return Device(serial, code, version, signers)


def verify_backup(path):
    total = 0
    with tarfile.open(path, "r:") as archive:
        for member in archive:
            name = PurePosixPath(member.name)
            parts = name.parts
            if name.is_absolute() or ".." in parts or not (
                parts[:2] == ("files", "datastore") or parts[:1] == ("databases",)
            ) or not (member.isfile() or member.isdir()):
                raise ReleaseError("The backup archive contains an unexpected entry.")
            if member.isfile():
                with archive.extractfile(member) as contents:
                    length = sum(len(chunk) for chunk in iter(lambda: contents.read(64 * 1024), b""))
                if length != member.size:
                    raise ReleaseError("The backup archive contains an incomplete file.")
                total += length
    if total == 0:
        raise ReleaseError("The backup archive contains no application data.")
    return total


def backup_device(tools, device):
    b = Path(os.environ.get("POCKET_FX_BACKUP_DIR", str(SUPPORT / "backups"))).expanduser().resolve()
    if b == ROOT or ROOT in b.parents:
        raise ReleaseError("Choose a backup directory outside the Git worktree.")
    check = subprocess.run([tools.adb, "-s", device.serial, "shell", "run-as", PACKAGE, "id"],
                           capture_output=True, text=True)
    if check.returncode:
        raise ReleaseError("ADB run-as cannot read this installation’s private data.")
    device_call(tools, device.serial, "shell", "am", "force-stop", PACKAGE, subject="Stopping Pocket FX for a consistent backup")
    locations = []
    for entry in ("files/datastore", "databases"):
        exists = subprocess.run([tools.adb, "-s", device.serial, "shell", "run-as", PACKAGE, "ls", "-d", entry],
                                capture_output=True)
        if exists.returncode == 0:
            locations.append(entry)
    if not locations:
        raise ReleaseError("The installation exposes no DataStore or Room directories to back up.")
    b.mkdir(mode=0o700, parents=True, exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    path = None
    try:
        with tempfile.NamedTemporaryFile(dir=b, prefix=f"pocketfx-{stamp}-", suffix=".tar", delete=False) as target:
            path = Path(target.name)
            result = subprocess.run([tools.adb, "-s", device.serial, "exec-out", "run-as", PACKAGE, "tar", "-cf", "-", *locations],
                                    stdout=target, stderr=subprocess.PIPE, timeout=60)
        if result.returncode != 0:
            raise ReleaseError("ADB could not archive the app data.")
        count = verify_backup(path)
    except (OSError, tarfile.TarError, ReleaseError, subprocess.SubprocessError):
        if path is not None:
            path.unlink(missing_ok=True)
        raise
    print(f"Verified readable backup ({count:,} data bytes) at {path}")
    print("Backup restoration has not been tested. A non-debuggable APK generally blocks ADB run-as.")
    return path


@dataclass(frozen=True)
class PublishedApk:
    code: int
    version: str
    url: str
    digest: str | None = None
    signers: tuple[str, ...] | None = None


def published_apks(repo, releases, tools, temp):
    result = []
    for release in releases:
        if release["draft"]:
            continue
        tag = release["tag_name"]
        assets = {asset["name"]: asset for asset in release["assets"]}
        apk_names = [name for name in assets if name.lower().endswith(".apk")]
        if MANIFEST in assets:
            value = public_json(repo.asset(tag, MANIFEST))
            if not isinstance(value, dict):
                raise ReleaseError(f"Published manifest for {tag} is not a JSON object.")
            matching = [name for name in apk_names if value.get("apkUrl") == repo.asset(tag, name)]
            code = value.get("versionCode")
            name = value.get("versionName")
            digest = value.get("sha256")
            if (value.get("packageName") != PACKAGE or type(code) is not int or code <= 0
                    or not isinstance(name, str) or not name or not isinstance(digest, str)
                    or not SHA256.fullmatch(digest.lower()) or len(matching) != 1):
                raise ReleaseError(f"Published manifest for {tag} does not describe a valid Pocket FX release asset.")
            result.append(PublishedApk(code, name, value["apkUrl"], digest.lower()))
            apk_names = [asset for asset in apk_names if asset not in matching]
        for asset in apk_names:
            url = repo.asset(tag, asset)
            archived = temp / "published.apk"
            public_file(url, archived)
            _, code, name = apk_metadata(archived, tools.aapt)
            result.append(PublishedApk(code, name, url, sha256(archived), tools.fingerprint(archived, require_release=False)))
    return result


def version_code(base, installed, published):
    floor = max([base, installed or 0, *[apk.code for apk in published]])
    if floor >= 2_147_483_647:
        raise ReleaseError("The release version code has reached its supported limit.")
    return floor + 1


def compare_published_signing(published, signing, tools, temp):
    if not published:
        return
    newest = max(published, key=lambda apk: apk.code)
    signers = newest.signers
    if signers is None:
        previous = temp / "previous-release.apk"
        public_file(newest.url, previous)
        if sha256(previous) != newest.digest or apk_metadata(previous, tools.aapt) != (PACKAGE, newest.code, newest.version):
            raise ReleaseError("The highest-version published APK does not match its manifest.")
        signers = tools.fingerprint(previous, require_release=False)
    if signers != signing:
        raise ReleaseError("The selected keystore does not match the previous public release certificate.")


def choose_signing(tools, published, use_debug, device):
    env = tools.env
    keys = ("POCKET_FX_KEYSTORE", "POCKET_FX_KEYSTORE_PASSWORD", "POCKET_FX_KEY_ALIAS", "POCKET_FX_KEY_PASSWORD")
    if any(env.get(key) for key in keys):
        if use_debug or any(not env.get(key) for key in keys):
            raise ReleaseError("Set all four Pocket FX signing variables and omit --use-debug-key.")
        keyfile = Path(env[keys[0]]).expanduser().resolve()
        if not keyfile.is_file() or ROOT in keyfile.parents:
            raise ReleaseError("POCKET_FX_KEYSTORE must refer to an existing file outside the repository.")
        env[keys[0]] = str(keyfile)
        return env, []
    if not use_debug and not published and device is None:
        raise ReleaseError("For the first release, connect the existing installation or explicitly choose --use-debug-key.")
    keyfile = Path(env.get("ANDROID_USER_HOME", str(Path.home() / ".android"))).expanduser().resolve() / "debug.keystore"
    if not keyfile.is_file() or ROOT in keyfile.resolve().parents:
        raise ReleaseError("The existing Android debug keystore is unavailable. Supply the compatible release keystore.")
    print("Using the existing Android debug signing key for personal distribution.")
    return env, ["-PpocketFxReuseDebugSigning=true"]


def verify_embedded_feed(apk, repo, tools):
    with ZipFile(apk) as archive:
        dex = [name for name in archive.namelist() if re.fullmatch(r"classes\d*\.dex", name)]
        if not any(repo.manifest.encode() in archive.read(name) for name in dex):
            raise ReleaseError("The stable update manifest URL is missing from the APK.")
    permissions = call(tools.aapt, "dump", "permissions", apk, subject="Inspecting installer permission")
    if "android.permission.REQUEST_INSTALL_PACKAGES" not in permissions:
        raise ReleaseError("The configured APK lacks the Android installer permission.")
    badging = call(tools.aapt, "dump", "badging", apk, subject="Inspecting release APK")
    if re.search(r"^application-debuggable\b", badging, re.MULTILINE):
        raise ReleaseError("The distributable APK must be a non-debuggable release build.")


def build_release(repo, tools, directory, args, code, version, signing):
    env, options = signing
    call(ROOT / "gradlew", "--no-daemon", ":app:assembleRelease",
         f"-PpocketFxUpdateManifestUrl={repo.manifest}", "-PpocketFxUpdateApkOrigin=",
         f"-PpocketFxVersionCode={code}", f"-PpocketFxVersionName={version}", *options,
         env=env, subject="Gradle release build", stream=True)
    apk = directory / f"pocketfx-{version}.apk"
    raw = ROOT / "app/build/outputs/apk/release/app-release.apk"
    if not raw.is_file():
        raise ReleaseError("Gradle did not produce the signed release APK.")
    shutil.copyfile(raw, apk)
    if apk_metadata(apk, tools.aapt) != (PACKAGE, code, version):
        raise ReleaseError("The built APK does not have the requested package and version.")
    signers = tools.fingerprint(apk)
    verify_embedded_feed(apk, repo, tools)
    print(f"Release signing certificate SHA-256 {' / '.join(signers)}")
    tag = f"v{version}"
    notes = args.notes
    optional = ["--notes", notes.resolve()] if notes else []
    generator = ROOT / "scripts/generate_update_manifest.py"
    value = json.loads(call(sys.executable, generator, apk, repo.asset(tag, apk.name),
                            "--aapt", tools.aapt, *optional, subject="Manifest generation"))
    value["releaseNotes"] = value["releaseNotes"] or {
        "en": "Pocket FX application update.",
        "ru": "Обновление приложения Pocket FX.",
        "sr-Latn": "Ažuriranje aplikacije Pocket FX.",
    }
    (directory / MANIFEST).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")
    return apk, signers, value


def verify_assets(directory, apk_name, expected, tools):
    candidate = directory / apk_name
    actual = json.loads((directory / MANIFEST).read_text())
    if actual != expected or sha256(candidate) != expected["sha256"]:
        raise ReleaseError("The uploaded manifest and APK bytes failed verification.")
    if apk_metadata(candidate, tools.aapt) != (PACKAGE, expected["versionCode"], expected["versionName"]):
        raise ReleaseError("The uploaded APK has different package metadata.")
    tools.fingerprint(candidate)


def clean_head():
    if call("git", "status", "--porcelain"):
        raise ReleaseError("Publish from a clean working tree. Commit and push the intended source first.")
    return call("git", "rev-parse", "HEAD")


def ensure_pushed(repo, session, head):
    remote = authenticated(session, "api", "--hostname", "github.com",
                           f"{repo.api}/commits/{head}", "--jq", ".sha", subject="Verifying the source commit on GitHub")
    if remote != head:
        raise ReleaseError("Push the intended commit to GitHub before publishing.")


def publish(repo, session, directory, apk, expected, tools, head, temp):
    tag = f"v{expected['versionName']}"
    target = repo.release_url(tag)
    name = f"Pocket FX {expected['versionName']}"
    notes_file = temp / "release-notes.md"
    notes_file.write_text("\n\n".join(f"**{language}**\n\n{value}" for language, value in expected["releaseNotes"].items()),
                          encoding="utf-8")
    if clean_head() != head:
        raise ReleaseError("The source commit changed during the release build.")
    if any(release["tag_name"] == tag for release in release_list(repo, session)):
        raise ReleaseError(f"A draft or published release already exists for {tag}.")
    authenticated(session, "release", "create", tag, "--repo", repo.slug, "--target", head,
                  "--draft", "--title", name, "--notes-file", notes_file, subject="Creating a draft release")
    print(f"Draft created at {target}")
    try:
        authenticated(session, "release", "upload", tag, apk, directory / MANIFEST, "--repo", repo.slug,
                      subject="Uploading the APK and manifest to the draft")
        draft = temp / "draft"
        draft.mkdir()
        authenticated(session, "release", "download", tag, "--repo", repo.slug, "--dir", draft,
                      "--pattern", apk.name, "--pattern", MANIFEST, subject="Reading back uploaded draft assets")
        verify_assets(draft, apk.name, expected, tools)
        current = published_apks(repo, release_list(repo, session), tools, temp)
        if any(item.code >= expected["versionCode"] for item in current):
            raise ReleaseError("Another release reached this version code during the build.")
        if clean_head() != head:
            raise ReleaseError("The source changed before publishing the draft.")
        try:
            reference = public_json(f"https://api.github.com/{repo.api}/git/ref/tags/{tag}")
        except HTTPError as error:
            if error.code != 404:
                raise
        else:
            if reference["object"]["sha"] != head:
                raise ReleaseError(f"The tag {tag} points to a different source commit.")
        authenticated(session, "release", "edit", tag, "--repo", repo.slug, "--draft=false",
                      "--latest", subject="Publishing the verified draft as latest")
    except (ReleaseError, OSError, ValueError, subprocess.SubprocessError) as error:
        raise ReleaseError(f"{error} Check the release state at {target} before retrying.") from error
    public = temp / "public"
    public.mkdir()
    for pause in (0, 2, 4, 8):
        time.sleep(pause)
        try:
            if public_json(repo.asset(tag, MANIFEST)) != expected:
                raise ReleaseError("The public versioned manifest does not match the verified draft.")
            with open_public(repo.manifest) as response:
                data = response.read(128 * 1024 + 1)
            (public / MANIFEST).write_bytes(data)
            public_file(expected["apkUrl"], public / apk.name)
            verify_assets(public, apk.name, expected, tools)
            break
        except (OSError, ValueError, ReleaseError, subprocess.SubprocessError) as error:
            last_error = error
    else:
        raise ReleaseError(f"Published {target}, but public asset verification failed: {last_error}")
    print(f"Published and verified {target}")
    print(f"APK {expected['apkUrl']}")
    print(f"Manifest {repo.manifest}")


@contextmanager
def release_lock():
    directory = ROOT / "build/pocketfx-releases"
    directory.mkdir(parents=True, exist_ok=True)
    with (directory / ".lock").open("w") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise ReleaseError("Another Pocket FX preparation or release is running in this checkout.")
        yield directory


def run(args):
    repo = Repo.detect()
    print(f"Repository {repo.slug}")
    print(f"Stable update manifest {repo.manifest}")
    metadata = public_json(f"https://api.github.com/{repo.api}")
    if metadata["visibility"] != "public":
        raise ReleaseError("The updater requires a public GitHub repository.")
    tools = AndroidTools.detect()
    print(f"Android SDK {tools.sdk}")
    session = github_commands(repo)
    print("GitHub publishing access verified." if session else "GitHub publishing access requires gh auth login.")
    publishing = args.mode == "release" and not args.prepare_only
    if publishing and session is None:
        raise ReleaseError("Run gh auth login --hostname github.com --web with an account that can publish releases.")
    head = clean_head() if publishing else None
    if publishing:
        ensure_pushed(repo, session, head)

    with release_lock() as output, tempfile.TemporaryDirectory(prefix="pocketfx-release-") as directory:
        temp = Path(directory)
        device = inspect_device(tools, temp, args.serial)
        backup = None
        if args.mode == "setup" and device:
            try:
                backup = backup_device(tools, device)
            except (ReleaseError, OSError, tarfile.TarError, subprocess.SubprocessError) as error:
                print(f"Backup unavailable: {error}")
        elif args.mode == "setup":
            print("No device backup was created.")
        releases = release_list(repo, session)
        published = published_apks(repo, releases, tools, temp)
        props = read_properties(ROOT / "gradle.properties")
        version = args.version
        if version is None:
            last = max(published, key=lambda apk: apk.code).version if published else props["pocketFxBaseVersionName"]
            parsed = VERSION.fullmatch(last)
            if not parsed:
                raise ReleaseError("Specify the intended release version with --version X.Y.Z.")
            version = f"{parsed[1]}.{int(parsed[2])+1}.0"
        if not VERSION.fullmatch(version):
            raise ReleaseError("Use a stable version name in X.Y.Z format.")
        tag = f"v{version}"
        if any(release["tag_name"] == tag for release in releases):
            raise ReleaseError(f"A draft or published release already exists for {tag}.")
        try:
            public_json(f"https://api.github.com/{repo.api}/git/ref/tags/{tag}")
        except HTTPError as error:
            if error.code != 404:
                raise
        else:
            raise ReleaseError(f"GitHub already has a tag named {tag}.")
        floor = int(props["pocketFxBaseVersionCode"])
        local_debug = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
        if local_debug.exists():
            floor = max(floor, apk_metadata(local_debug, tools.aapt)[1])
        code = version_code(floor, device.code if device else None, published)
        signing = choose_signing(tools, published, args.use_debug_key, device)
        stage = temp / "stage"
        stage.mkdir()
        apk, signers, manifest = build_release(repo, tools, stage, args, code, version, signing)
        compare_published_signing(published, signers, tools, temp)
        compatible = device is not None and signers == device.signers
        if device:
            print("Compatible with the installed signing certificate." if compatible else
                  "Signing mismatch. Installing this APK over the connected device’s Pocket FX would be refused.")
        elif local_debug.exists():
            matched = signers == tools.fingerprint(local_debug, require_release=False)
            print("Matches the local Debug APK’s certificate." if matched else "The local Debug APK has a different certificate.")
            print("The signing certificate on a phone could not be checked.")
        target = output / tag
        target.mkdir(exist_ok=True)
        for asset in (apk, stage / MANIFEST):
            shutil.copyfile(asset, target / asset.name)
        print(f"Prepared Pocket FX {version} (versionCode {code}) at {target}")
        print(f"APK SHA-256 {manifest['sha256']}")
        if args.mode == "setup" and args.install:
            if not compatible or not backup:
                raise ReleaseError("An in-place installation requires a matching connected app and a verified local backup.")
            current = inspect_device(tools, temp, device.serial)
            if current != device or code <= current.code:
                raise ReleaseError("The installed package changed after the backup. Repeat setup before installing.")
            device_call(tools, device.serial, "install", "-r", apk, subject="In-place Android installation")
            installed = inspect_device(tools, temp, device.serial)
            if installed is None or installed.code != code or installed.signers != signers:
                raise ReleaseError("The device did not confirm the expected update.")
            print(f"Device confirmed Pocket FX {version}. The package was upgraded in place.")
        if publishing:
            if device and not compatible and not published:
                raise ReleaseError("The first release was kept local because the connected installation has a different signing key.")
            publish(repo, session, stage, apk, manifest, tools, head, temp)
        elif args.mode == "setup":
            if device and not compatible:
                print("Kept this build for local verification. Recover the original signing key or plan a backup-based migration before the first release.")
            else:
                option = " --use-debug-key" if not published and not os.environ.get("POCKET_FX_KEYSTORE") else ""
                print(f"Publish with ./scripts/release.sh {version}{option} after committing and pushing the intended source.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="mode", required=True)
    setup = commands.add_parser("setup", help="Inspect signing and device data, and prepare a local release")
    setup.add_argument("--version", help="Release name in X.Y.Z format (defaults to the next minor release)")
    setup.add_argument("--install", action="store_true", help="Install in place after matching the signer and backing up data")
    release = commands.add_parser("release", help="Build, verify, and publish a GitHub release")
    release.add_argument("version")
    release.add_argument("--prepare-only", action="store_true", help="Produce locally verified assets without publishing")
    for command in (setup, release):
        command.add_argument("--notes", type=Path, help="JSON object with en, ru, or sr-Latn release notes")
        command.add_argument("--serial", help="ADB serial if more than one device is connected")
        command.add_argument("--use-debug-key", action="store_true", help="Use the existing debug key for personal distribution")
    os.umask(0o077)
    try:
        run(parser.parse_args())
    except (ReleaseError, URLError, OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        print(f"Pocket FX release: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
