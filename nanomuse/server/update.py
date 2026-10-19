"""Is there a newer nanoMuse? The download mirror's index first, GitHub Releases second,
kept for a day, a failed check for an hour (contract C2: every client shows the installed
and the latest version).

Nothing is downloaded and nothing about the install is sent: the requests are the ones a
browser makes when it opens the download page. ``https://nanomuse.cn/dl/index.json``
(``{"repo", "releases": [{"tag", "name", "published_at", "assets"}]}``, newest first) is
asked first with a short timeout, then ``api.github.com/.../releases/latest``. The check
is off when ``server.update_check`` is false, when ``NANOMUSE_NO_UPDATE_CHECK`` is set,
and in a hosted web session (the operator updates those, not the person using them —
those runtimes are the ones handed a ``NANOMUSE_CLOUD_KEY``). The desktop app and the
Android app have checks of their own; this one is for ``pipx`` / Docker installs, which
show it under *Settings → About* with a "Check now" when the answer is stale or failed.
"""

from __future__ import annotations

import os
import re
import time
from datetime import UTC, datetime
from typing import Any

import httpx

from nanomuse import __version__
from nanomuse.logger import logger

INDEX_URL = ""
DOWNLOAD_PAGE = "https://github.com/LanRhyme/NyanoMuse/releases"
RELEASES_API = "https://api.github.com/repos/LanRhyme/NyanoMuse/releases/latest"
RELEASES_PAGE = "https://github.com/LanRhyme/NyanoMuse/releases/latest"
RELEASE_TAG_PAGE = "https://github.com/LanRhyme/NyanoMuse/releases/tag/{tag}"
CACHE_S = 24 * 3600
# a check that failed (the runtime came up before the network did) is tried again sooner
RETRY_S = 3600
INDEX_TIMEOUT_S = 5.0
TIMEOUT_S = 6.0


def parse_version(text: str) -> tuple[int, ...]:
    """``v0.1.21`` → ``(0, 1, 21)``; anything after the numbers (``-rc1``) is ignored,
    a string without numbers is ``()`` and therefore older than everything."""
    m = re.match(r"v?(\d+(?:\.\d+)*)", text.strip())
    return tuple(int(p) for p in m.group(1).split(".")) if m else ()


def newer_than(candidate: str, current: str) -> bool:
    a, b = parse_version(candidate), parse_version(current)
    return bool(a) and a > b


def enabled(setting: bool) -> bool:
    if not setting or os.environ.get("NANOMUSE_NO_UPDATE_CHECK", "").strip().lower() in (
        "1",
        "true",
        "yes",
        "on",
    ):
        return False
    return not os.environ.get("NANOMUSE_CLOUD_KEY")


def latest_from_index(data: Any) -> str:
    """The newest tag in the mirror's index (``releases`` newest first; the highest one is
    taken anyway in case the order slipped); ``""`` when the shape is not the index."""
    if not isinstance(data, dict) or not isinstance(data.get("releases"), list):
        return ""
    best = ""
    for rel in data["releases"]:
        tag = str(rel.get("tag") or "") if isinstance(rel, dict) else ""
        if parse_version(tag) and (not best or newer_than(tag, best)):
            best = tag
    return best


class UpdateCheck:
    """The cached answer; :meth:`view` is what ``GET /api/update`` returns."""

    def __init__(self, setting: bool = True, *, current: str = __version__) -> None:
        self.setting = setting
        self.current = current
        self.latest: str | None = None
        self.url: str = RELEASES_PAGE
        self.download_url: str = DOWNLOAD_PAGE
        # monotonic, for the cache; the wall clock, for "checked at" on the About page
        self.checked_at: float | None = None
        self.checked_wall: str | None = None
        self.source: str | None = None
        self.error: str | None = None

    async def view(self, force: bool = False) -> dict[str, Any]:
        if not enabled(self.setting):
            return {
                "current": self.current,
                "enabled": False,
                "latest": None,
                "newer": False,
                "url": self.url,
                "download_url": self.download_url,
                "checked_at": None,
                "source": None,
                "error": None,
            }
        if force or self.checked_at is None or self._stale():
            await self._check()
        return {
            "current": self.current,
            "enabled": True,
            "latest": self.latest,
            "newer": bool(self.latest and newer_than(self.latest, self.current)),
            "url": self.url,
            "download_url": self.download_url,
            "checked_at": self.checked_wall,
            "source": self.source,
            "error": self.error,
        }

    def _stale(self) -> bool:
        """A good answer is kept for a day; a failed one for an hour, so a runtime that
        started before its network was up does not say "could not check" all day."""
        if self.checked_at is None:
            return True
        age = time.monotonic() - self.checked_at
        return age > (RETRY_S if self.error else CACHE_S)

    async def _check(self) -> None:
        self.checked_at = time.monotonic()
        self.checked_wall = datetime.now(UTC).isoformat(timespec="seconds")
        headers = {"User-Agent": f"nanoMuse/{self.current}"}
        errors: list[str] = []
        async with httpx.AsyncClient(follow_redirects=True) as client:
            # 1. the download mirror's index (short timeout: it is the quick way, not the only one)
            if INDEX_URL:
                try:
                    r = await client.get(INDEX_URL, headers=headers, timeout=INDEX_TIMEOUT_S)
                    r.raise_for_status()
                    tag = latest_from_index(r.json())
                    if not tag:
                        raise ValueError("no release in the index")
                    self._found(tag, RELEASE_TAG_PAGE.format(tag=tag), "releases")
                    return
                except Exception as exc:  # noqa: BLE001 — the mirror is one of two ways
                    errors.append(exc.__class__.__name__)
                    logger.debug("update check (index) failed: {}", exc)
            # 2. GitHub Releases
            try:
                r = await client.get(
                    RELEASES_API,
                    headers={**headers, "Accept": "application/vnd.github+json"},
                    timeout=TIMEOUT_S,
                )
                r.raise_for_status()
                data = r.json()
                tag = str(data.get("tag_name") or "")
                if not parse_version(tag):
                    raise ValueError(f"unexpected tag {tag!r}")
                self._found(tag, str(data.get("html_url") or RELEASES_PAGE), "github")
                return
            except Exception as exc:  # noqa: BLE001 — the answer is "could not check", never a failure of the app
                errors.append(exc.__class__.__name__)
                logger.debug("update check (github) failed: {}", exc)
        self.error = " / ".join(dict.fromkeys(errors)) or "failed"

    def _found(self, tag: str, url: str, source: str) -> None:
        self.latest = tag.lstrip("v")
        self.url = url
        self.source = source
        self.error = None
