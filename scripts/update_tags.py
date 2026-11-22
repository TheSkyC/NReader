"""Collect and validate the next bundled tag snapshot before writing any output."""

import json
import re
import shutil
import tempfile
import time
from collections import Counter
from collections.abc import Callable
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
from pathlib import Path

import requests

if __package__:
    from .tag_catalog import (MAX_BYTES, MAX_INTEGER, MAX_ROWS, MIN_ROWS, TAG_TYPES,
                              TagInfo, atomic_write, decode_json, parse_catalog, read_bounded)
else:
    from tag_catalog import (MAX_BYTES, MAX_INTEGER, MAX_ROWS, MIN_ROWS, TAG_TYPES,
                             TagInfo, atomic_write, decode_json, parse_catalog, read_bounded)

DATA_DIR = Path(__file__).resolve().parents[1] / "data"
API_URL = "https://nhentai.net/api/v2/"
PAGE_SIZE = 100
MAX_PAGES = MAX_ROWS // PAGE_SIZE + 1
MAX_PAGE_BYTES = 1024 * 1024
MAX_ATTEMPTS = 3
MIN_REQUEST_INTERVAL = 2.0
REQUEST_DEADLINE = 30
MAX_RETRY_DELAY = 60
TRANSACTION = ".tag-catalog-transaction.json"
TRANSACTION_FILES = ("tagsPretty.json", "tags.json", "tagsVersion")
COUNT_CHANGE_PERCENT = 10
MIN_RETAINED_PERCENT = 90


class CatalogSession(requests.Session):
    def get_redirect_target(self, response):
        # Requests otherwise reads the redirect body while constructing Response.next.
        return None


class CatalogApi:
    def __init__(self, session: requests.Session):
        self.session = session
        self.next_request = 0.0

    def get(self, endpoint: str) -> dict:
        for attempt in range(MAX_ATTEMPTS):
            delay = self.next_request - time.monotonic()
            if delay > 0:
                time.sleep(delay)
            started = time.monotonic()
            self.next_request = started + MIN_REQUEST_INTERVAL
            with self.session.get(API_URL + endpoint, timeout=(5, 10), stream=True,
                                  allow_redirects=False) as response:
                if response.status_code in (429, 502, 503, 504):
                    if attempt + 1 == MAX_ATTEMPTS:
                        raise ValueError(f"Tag collection exhausted retries (HTTP {response.status_code})")
                    retry_delay = read_retry_delay(response.headers.get("Retry-After"))
                    self.next_request = max(self.next_request, time.monotonic() + max(2 ** attempt, retry_delay))
                    continue
                if response.status_code != 200:
                    raise ValueError(f"Tag collection failed (HTTP {response.status_code})")
                if response.headers.get("Content-Encoding", "identity").lower() != "identity":
                    raise ValueError("Unexpected tag API content encoding")
                data = bytearray()
                for block in response.iter_content(4096):
                    if time.monotonic() - started > REQUEST_DEADLINE or len(data) + len(block) > MAX_PAGE_BYTES:
                        raise ValueError("Tag API page exceeded its time or size limit")
                    data.extend(block)
                if time.monotonic() - started > REQUEST_DEADLINE:
                    raise ValueError("Tag API page exceeded its time limit")
                return decode_json(data)
        raise AssertionError("Unreachable retry state")


def read_retry_delay(value: str | None) -> float:
    if value is None:
        return 0
    if len(value) > 128:
        raise ValueError("Invalid tag API retry delay")
    if re.fullmatch("[0-9]+", value):
        delay = int(value)
    else:
        date = parsedate_to_datetime(value)
        if date.tzinfo is None:
            raise ValueError("Tag API retry date has no timezone")
        delay = max(0, (date - datetime.now(timezone.utc)).total_seconds())
    if delay > MAX_RETRY_DELAY:
        raise ValueError("Tag API requested a delay outside the retry budget")
    return delay


def collect(get_page: Callable[[str], dict], previous: dict[int, TagInfo]) -> dict[int, TagInfo]:
    observed = {}
    known_by_type = Counter(tag.category for tag in previous.values())
    for name, category in TAG_TYPES:
        page_count = None
        matched = 0
        for page in range(1, MAX_PAGES + 1):
            result = get_page(f"tags/{name}?page={page}&per_page={PAGE_SIZE}")
            advertised = result.get("num_pages") if isinstance(result, dict) else None
            if (type(advertised) is not int or not page <= advertised <= MAX_PAGES
                    or (page_count is not None and page_count != advertised)):
                raise ValueError(f"Inconsistent page count for {name}")
            page_count = advertised
            rows = result.get("result")
            if (not isinstance(rows, list) or not 1 <= len(rows) <= PAGE_SIZE
                    or (page < page_count and len(rows) != PAGE_SIZE)):
                raise ValueError(f"Incomplete page for {name}")
            for row in rows:
                if not isinstance(row, dict) or not {"id", "name", "count"} <= row.keys():
                    raise ValueError("Invalid tag API row")
                tag = TagInfo(row["id"], row["name"], row["count"], category)
                if tag.id_ in observed or len(observed) >= MAX_ROWS:
                    raise ValueError("Duplicate tag ID or row limit exceeded during collection")
                prior = previous.get(tag.id_)
                if prior is not None:
                    if prior.category != tag.category:
                        raise ValueError(f"Catalog type changed for tag ID {tag.id_}")
                    matched += 1
                observed[tag.id_] = tag
            if page == page_count:
                break
        if matched * 100 < known_by_type[category] * MIN_RETAINED_PERCENT:
            raise ValueError(f"Too many known {name} IDs missing from collection")
        print(f"Validated {name}: {page_count} pages, {matched} known IDs")
    if len(observed) < MIN_ROWS:
        raise ValueError("The collected catalog does not meet the minimum size")
    return observed


def merge(previous: dict[int, TagInfo], observed: dict[int, TagInfo]) -> dict[int, TagInfo]:
    result = previous.copy()
    for tag_id, tag in observed.items():
        prior = previous.get(tag_id)
        if prior is not None:
            if prior.category != tag.category:
                raise ValueError(f"Catalog type changed for tag ID {tag_id}")
            if abs(prior.amount - tag.amount) * 100 <= prior.amount * COUNT_CHANGE_PERCENT:
                tag = TagInfo(tag_id, tag.title, prior.amount, tag.category)
        result[tag_id] = tag
    return result


def recover_snapshot(directory: Path) -> None:
    journal = directory / TRANSACTION
    if not journal.exists():
        return
    state = decode_json(read_bounded(journal, 4096))
    if not isinstance(state, dict) or set(state) != {"directory", "previous"}:
        raise ValueError("Invalid interrupted catalog transaction")
    stage_name = state["directory"]
    previous = state["previous"]
    if (not isinstance(stage_name, str) or not re.fullmatch(r"\.tag-catalog-[A-Za-z0-9_-]+", stage_name)
            or not isinstance(previous, dict) or set(previous) != set(TRANSACTION_FILES)
            or any(type(existed) is not bool for existed in previous.values())):
        raise ValueError("Invalid interrupted catalog transaction")
    staging = directory / stage_name
    if not staging.is_dir():
        raise ValueError("Catalog transaction recovery files are missing")
    backups = {}
    for name, existed in previous.items():
        if not existed:
            continue
        backup = staging / (name + ".previous")
        if not backup.is_file():
            raise ValueError("Catalog transaction recovery files are incomplete")
        backups[name] = read_bounded(backup, MAX_BYTES * 4)
    for name in TRANSACTION_FILES:
        if name in backups:
            atomic_write(directory / name, backups[name])
        else:
            (directory / name).unlink(missing_ok=True)
    journal.unlink()
    shutil.rmtree(staging)


def write_snapshot(directory: Path, contents: dict[str, bytes]) -> None:
    if set(contents) != set(TRANSACTION_FILES):
        raise ValueError("Invalid catalog transaction file set")
    stage_name = Path(tempfile.mkdtemp(prefix=".tag-catalog-txn-", dir=directory)).name
    staging = directory / stage_name
    previous = {}
    try:
        for name in TRANSACTION_FILES:
            target = directory / name
            previous[name] = target.is_file()
            if previous[name]:
                atomic_write(staging / (name + ".previous"), read_bounded(target, MAX_BYTES * 4))
            atomic_write(staging / (name + ".next"), contents[name])
        atomic_write(directory / TRANSACTION, json.dumps(
            {"directory": stage_name, "previous": previous}, separators=(",", ":")).encode("utf-8"))
        try:
            for name in TRANSACTION_FILES:
                atomic_write(directory / name, read_bounded(staging / (name + ".next"), MAX_BYTES * 4))
        except BaseException:
            recover_snapshot(directory)
            raise
        (directory / TRANSACTION).unlink()
        shutil.rmtree(staging)
    finally:
        # Without a journal no target has changed, so an incomplete preparation is disposable.
        if (directory / TRANSACTION).exists():
            recover_snapshot(directory)
        elif staging.exists():
            shutil.rmtree(staging)


def update_snapshot(directory: Path, get_page: Callable[[str], dict]) -> bool:
    directory.mkdir(parents=True, exist_ok=True)
    recover_snapshot(directory)
    catalog = directory / "tags.json"
    version = directory / "tagsVersion"
    previous = parse_catalog(read_bounded(catalog, MAX_BYTES))
    current_version = read_bounded(version, 32).decode("ascii").strip()
    if not re.fullmatch("[1-9][0-9]*", current_version) or int(current_version) >= MAX_INTEGER:
        raise ValueError("Invalid or exhausted bundled catalog revision")
    observed = collect(get_page, previous)
    updated = merge(previous, observed)
    if updated == previous:
        print("No catalog changes")
        return False

    tags = sorted(updated.values(), key=lambda tag: (tag.category, tag.id_))
    rows = [tag.to_list() for tag in tags]
    compact = json.dumps(rows, separators=(",", ":")).encode("utf-8")
    if len(rows) > MAX_ROWS or len(compact) > MAX_BYTES:
        raise ValueError("Merged catalog exceeds its row or byte limit")
    pretty = json.dumps(rows, indent=4).encode("utf-8")
    new_ids = len(updated.keys() - previous.keys())
    renamed = sum(previous[tag_id].title != updated[tag_id].title for tag_id in previous)
    changed_counts = sum(previous[tag_id].amount != updated[tag_id].amount for tag_id in previous)
    write_snapshot(directory, {
        "tagsPretty.json": pretty,
        "tags.json": compact,
        "tagsVersion": f"{int(current_version) + 1}\n".encode("ascii"),
    })
    print(f"Catalog update: {new_ids} added, {renamed} renamed, {changed_counts} count changes")
    return True


def main() -> None:
    with CatalogSession() as session:
        session.trust_env = False
        session.headers["User-Agent"] = "NReader/tag-update-ci (https://github.com/TheSkyC/NReader)"
        session.headers["Accept-Encoding"] = "identity"
        update_snapshot(DATA_DIR, CatalogApi(session).get)


if __name__ == "__main__":
    main()
