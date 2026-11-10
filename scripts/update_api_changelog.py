import hashlib
import json
import os
import tempfile
from datetime import date
from html.parser import HTMLParser
from pathlib import Path

import requests


API_URL = "https://nhentai.net/api/v2/changelog"
OUTPUT = Path(__file__).resolve().parents[1] / "data" / "apiChangelog.json"


class ChangelogParser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.heading = None
        self.text = []
        self.sections = []
        self.current = None
        self.list_open = False
        self.saw_title = False
        self.closed_html = False

    def handle_starttag(self, tag, attrs):
        if tag in ("h1", "h2") or (tag == "li" and self.list_open):
            if self.heading is not None:
                raise ValueError("Unexpected nested changelog element")
            self.heading = tag
            self.text = []
        elif tag == "ul" and self.current is not None:
            if self.list_open:
                raise ValueError("Unexpected nested changelog list")
            self.list_open = True

    def handle_data(self, data):
        if self.heading is not None:
            self.text.append(data)

    def handle_endtag(self, tag):
        if tag == "html":
            self.closed_html = True
        if tag == "ul" and self.list_open:
            self.list_open = False
        if tag != self.heading:
            return
        value = " ".join("".join(self.text).split())
        self.heading = None
        if tag == "h1":
            self.saw_title = value == "nhentai API Changelog"
        elif tag == "h2":
            if self.current is not None and not self.current["entries"]:
                raise ValueError("Empty changelog section")
            try:
                if date.fromisoformat(value).isoformat() != value:
                    raise ValueError
            except ValueError as error:
                raise ValueError("Invalid changelog date") from error
            self.current = {"date": value, "entries": []}
            self.sections.append(self.current)
        elif not value:
            raise ValueError("Empty changelog entry")
        else:
            self.current["entries"].append(hashlib.sha256(value.encode("utf-8")).hexdigest())

    def result(self):
        if (not self.saw_title or not self.closed_html or not self.sections or self.heading is not None
                or self.list_open or not self.sections[-1]["entries"]):
            raise ValueError("Invalid or incomplete API changelog page")
        return self.sections


def fetch_changelog():
    response = requests.get(
        API_URL,
        headers={"User-Agent": "NReader/api-changelog-ci"},
        timeout=(10, 30),
    )
    response.raise_for_status()
    parser = ChangelogParser()
    parser.feed(response.text)
    parser.close()
    return parser.result()


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
