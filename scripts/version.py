"""Shows or bumps the app version in app/build.gradle.kts.

Usage: python scripts/version.py [show|patch|minor|major]

- versionName is MAJOR.MINOR.PATCH: "patch" is used for each pull request merged into develop,
  "minor" for each release merged into main (the patch number goes back to 0).
- versionCode is increased by 1 on every bump: Android only installs an update with a higher versionCode.

The new version is printed, and written to $GITHUB_OUTPUT as "version" when run in GitHub Actions.
"""
import os
import re
import sys
from pathlib import Path

GRADLE_FILE = Path(__file__).resolve().parent.parent / "app" / "build.gradle.kts"
CODE_PATTERN = re.compile(r"(versionCode\s*=\s*)(\d+)")
NAME_PATTERN = re.compile(r'(versionName\s*=\s*")([^"]+)(")')


def parse(name):
    parts = [int(p) for p in name.split(".")]
    return (parts + [0, 0, 0])[:3]


def main():
    action = sys.argv[1] if len(sys.argv) > 1 else "show"
    text = GRADLE_FILE.read_text(encoding="utf-8")
    code = int(CODE_PATTERN.search(text).group(2))
    major, minor, patch = parse(NAME_PATTERN.search(text).group(2))

    if action == "patch":
        patch += 1
    elif action == "minor":
        minor, patch = minor + 1, 0
    elif action == "major":
        major, minor, patch = major + 1, 0, 0
    elif action != "show":
        sys.exit(__doc__)

    name = f"{major}.{minor}.{patch}"
    if action != "show":
        code += 1
        text = CODE_PATTERN.sub(lambda m: f"{m.group(1)}{code}", text, count=1)
        text = NAME_PATTERN.sub(lambda m: f"{m.group(1)}{name}{m.group(3)}", text, count=1)
        GRADLE_FILE.write_text(text, encoding="utf-8")

    print(f"{name} (versionCode {code})")
    if "GITHUB_OUTPUT" in os.environ:
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as out:
            out.write(f"version={name}\n")


if __name__ == "__main__":
    main()
