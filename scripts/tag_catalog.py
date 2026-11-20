"""Validation and file helpers for the public tag catalog."""

import json
import os
import tempfile
from dataclasses import dataclass
from pathlib import Path

MAX_INTEGER = 2_147_483_647
MIN_ROWS = 50_000
MAX_ROWS = 250_000
MAX_BYTES = 16 * 1024 * 1024
TAG_TYPES = (
    ("parody", 1),
    ("character", 2),
    ("tag", 3),
    ("artist", 4),
    ("group", 5),
    ("language", 6),
    ("category", 7),
)
TYPE_IDS = {type_id for _, type_id in TAG_TYPES}


@dataclass(frozen=True)
class TagInfo:
    id_: int
    title: str
    amount: int
    category: int

    def __post_init__(self):
        if type(self.id_) is not int or not 1 <= self.id_ <= MAX_INTEGER:
            raise ValueError("Invalid catalog tag ID")
        if type(self.amount) is not int or not 0 <= self.amount <= MAX_INTEGER:
            raise ValueError("Invalid catalog count")
        if type(self.category) is not int or self.category not in TYPE_IDS:
            raise ValueError("Invalid catalog type")
        if (not isinstance(self.title, str) or not self.title.strip(" ")
                or any(ord(char) < 32 or 127 <= ord(char) <= 159
                       or 0xD800 <= ord(char) <= 0xDFFF or char in '"\\' for char in self.title)
                or len(self.title.encode("utf-16-le")) > 512):
            raise ValueError("Invalid canonical tag name")

    def to_list(self):
        return [self.id_, self.title, self.amount, self.category]


def read_bounded(path: Path, limit: int) -> bytes:
    with path.open("rb") as stream:
        data = stream.read(limit + 1)
    if len(data) > limit:
        raise ValueError("Input exceeds size limit")
    return data


def decode_json(data: bytes):
    def unique_fields(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("Duplicate JSON field")
            result[key] = value
        return result

    def reject_constant(_value):
        raise ValueError("Invalid JSON constant")

    try:
        return json.loads(data.decode("utf-8"), object_pairs_hook=unique_fields,
                          parse_constant=reject_constant)
    except RecursionError as error:
        raise ValueError("JSON nesting exceeds limit") from error


def parse_catalog(data: bytes, minimum_rows: int = MIN_ROWS) -> dict[int, TagInfo]:
    rows = decode_json(data)
    if not isinstance(rows, list) or not minimum_rows <= len(rows) <= MAX_ROWS:
        raise ValueError("Catalog row count outside limits")
    tags = {}
    for row in rows:
        if not isinstance(row, list) or len(row) != 4:
            raise ValueError("Invalid catalog row")
        tag = TagInfo(*row)
        if tag.id_ in tags:
            raise ValueError("Duplicate catalog tag ID")
        tags[tag.id_] = tag
    if {tag.category for tag in tags.values()} != TYPE_IDS:
        raise ValueError("Catalog does not cover all tag types")
    return tags


def atomic_write(path: Path, data: bytes) -> None:
    with tempfile.NamedTemporaryFile(dir=path.parent, prefix=path.name + ".",
                                     suffix=".part", delete=False) as stream:
        staging = Path(stream.name)
        try:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
            stream.close()
            staging.replace(path)
        finally:
            stream.close()
            staging.unlink(missing_ok=True)
