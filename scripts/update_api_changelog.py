import json
import os
import tempfile
from pathlib import Path

import requests


API_URL = "https://nhentai.net/api/v2/changelog"
OUTPUT = Path(__file__).resolve().parents[1] / "data" / "apiChangelog.json"


def fetch_changelog():
    response = requests.get(
        API_URL,
        headers={"User-Agent": "NReader/api-changelog-ci"},
        timeout=(10, 30),
    )
    response.raise_for_status()
    payload = response.json()
    if not isinstance(payload, (dict, list)):
        raise RuntimeError("API changelog response must be a JSON object or array")
    return payload


def write_if_changed(payload):
    content = json.dumps(payload, ensure_ascii=False, indent=2) + "\n"
    previous = OUTPUT.read_text(encoding="utf-8") if OUTPUT.exists() else None
    if previous == content:
        print("No API changelog changes")
        return False
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary_name = tempfile.mkstemp(prefix="apiChangelog.", dir=OUTPUT.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as writer:
            writer.write(content)
            writer.flush()
            os.fsync(writer.fileno())
        os.replace(temporary_name, OUTPUT)
    finally:
        if os.path.exists(temporary_name):
            os.unlink(temporary_name)
    print("API changelog updated")
    return True


if __name__ == "__main__":
    write_if_changed(fetch_changelog())
