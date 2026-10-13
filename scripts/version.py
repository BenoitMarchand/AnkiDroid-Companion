"""Shows or bumps the app version in app/build.gradle.kts.

Usage: python scripts/version.py [show|patch|rc|final|minor|major]

versionName is MAJOR.MINOR.PATCH, with a "-rcN" suffix on release candidates:

- "patch": each pull request merged into develop. On a release candidate, a ".N" is added after the
  candidate instead (0.4.0-rc1 -> 0.4.0-rc1.1 -> 0.4.0-rc1.2), so the next candidate or release keeps its number.
- "rc": a release candidate. From a released version, starts the next minor version (0.3.3 -> 0.4.0-rc1);
  from a release candidate, the next candidate of the same version (0.4.0-rc1.2 -> 0.4.0-rc2).
- "final": the release of a validated candidate (0.4.0-rc2 -> 0.4.0). Only from a release candidate.
- "minor" / "major": the next minor / major version, without a candidate (the patch number goes back to 0).

versionCode is increased by 1 on every bump: Android only installs an update with a higher versionCode.

The new version is printed, and written to $GITHUB_OUTPUT as "version" (and "prerelease" = true/false)
when run in GitHub Actions.
"""
import os
import re
import sys
from pathlib import Path

GRADLE_FILE = Path(__file__).resolve().parent.parent / "app" / "build.gradle.kts"
CODE_PATTERN = re.compile(r"(versionCode\s*=\s*)(\d+)")
NAME_PATTERN = re.compile(r'(versionName\s*=\s*")([^"]+)(")')
VERSION_PATTERN = re.compile(r"^(\d+)\.(\d+)\.(\d+)(?:-rc(\d+)(?:\.(\d+))?)?$")


def parse(name):
    """[major, minor, patch, rc, rc_patch]; rc is None for a released version."""
    match = VERSION_PATTERN.match(name)
    if not match:
        sys.exit(f"Unexpected versionName: {name}")
    major, minor, patch, rc, rc_patch = match.groups()
    return [int(major), int(minor), int(patch), int(rc) if rc else None, int(rc_patch or 0)]


def format_name(major, minor, patch, rc, rc_patch):
    name = f"{major}.{minor}.{patch}"
    if rc is not None:
        name += f"-rc{rc}"
        if rc_patch:
            name += f".{rc_patch}"
    return name


def main():
    action = sys.argv[1] if len(sys.argv) > 1 else "show"
    text = GRADLE_FILE.read_text(encoding="utf-8")
    code = int(CODE_PATTERN.search(text).group(2))
    major, minor, patch, rc, rc_patch = parse(NAME_PATTERN.search(text).group(2))

    if action == "patch":
        if rc is None:
            patch += 1
        else:
            rc_patch += 1
    elif action == "rc":
        if rc is None:
            minor, patch, rc = minor + 1, 0, 1
        else:
            rc += 1
        rc_patch = 0
    elif action == "final":
        if rc is None:
            sys.exit("No release candidate to release: run the 'rc' step first.")
        rc, rc_patch = None, 0
    elif action == "minor":
        minor, patch, rc, rc_patch = minor + 1, 0, None, 0
    elif action == "major":
        major, minor, patch, rc, rc_patch = major + 1, 0, 0, None, 0
    elif action != "show":
        sys.exit(__doc__)

    name = format_name(major, minor, patch, rc, rc_patch)
    if action != "show":
        code += 1
        text = CODE_PATTERN.sub(lambda m: f"{m.group(1)}{code}", text, count=1)
        text = NAME_PATTERN.sub(lambda m: f"{m.group(1)}{name}{m.group(3)}", text, count=1)
        GRADLE_FILE.write_text(text, encoding="utf-8")

    print(f"{name} (versionCode {code})")
    if "GITHUB_OUTPUT" in os.environ:
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as out:
            out.write(f"version={name}\n")
            out.write(f"prerelease={'true' if rc is not None else 'false'}\n")


if __name__ == "__main__":
    main()
