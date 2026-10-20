#!/usr/bin/env python3
"""Check npm sources and developer-machine paths before sharing this checkout.

This is a portability check, not a replacement for secret/history/media review.
Only file locations and rule names are printed, never matching values.
"""
import json
import pathlib
import re
import subprocess
import sys
from urllib.parse import urlsplit

ROOT = pathlib.Path(__file__).resolve().parents[1]
REGISTRY = "https://registry.npmjs.org/"
HOST_PATH = re.compile(
    r"/(?:Users|home)/[^/\s]+/|[A-Za-z]:\\Users\\"
    r"|/(?:Library)/Java/JavaVirtualMachines/|(?<![\w.])/(?:absolute/path|path/to)/"
)
INTERNAL_NPM = re.compile(r"(?:bnpm|registry\.npm)\.byted\.org", re.IGNORECASE)


def main():
    names = subprocess.check_output(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
        cwd=ROOT,
    ).decode().split("\0")
    errors = []
    checked = 0
    locks = 0
    for name in sorted(set(names) - {""}):
        path = ROOT / name
        if not path.is_file():
            continue
        raw = path.read_bytes()
        if b"\0" in raw:
            continue
        try:
            text = raw.decode("utf-8")
        except UnicodeDecodeError:
            continue
        checked += 1
        for number, line in enumerate(text.splitlines(), 1):
            if HOST_PATH.search(line):
                errors.append(f"{name}:{number}: developer-machine absolute path")
            if INTERNAL_NPM.search(line):
                errors.append(f"{name}:{number}: internal npm host")
        if path.name == ".npmrc":
            settings = [line.strip() for line in text.splitlines() if line.strip() and not line.lstrip().startswith(("#", ";"))]
            if f"registry={REGISTRY}" not in settings or any(
                setting not in (f"registry={REGISTRY}", "save-exact=true") for setting in settings
            ):
                errors.append(f"{name}: npm config must use the public registry and contain no credentials")
        if path.name != "package-lock.json":
            continue
        locks += 1
        try:
            packages = json.loads(text)["packages"]
        except (ValueError, KeyError, TypeError):
            errors.append(f"{name}: invalid npm lockfile")
            continue
        for package, metadata in packages.items():
            resolved = metadata.get("resolved")
            if not resolved:
                continue
            if metadata.get("link"):
                # The pinned browser port is vendored within this repository.
                target = (path.parent / resolved).resolve()
                if urlsplit(resolved).scheme or not target.is_relative_to(ROOT):
                    errors.append(f"{name}: {package}: local link escapes the repository")
            elif not resolved.startswith(REGISTRY):
                errors.append(f"{name}: {package}: non-public npm registry")
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print(f"Public-source portability checks passed: {checked} text files, {locks} npm lockfiles.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
