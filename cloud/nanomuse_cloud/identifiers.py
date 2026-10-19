"""Phone numbers and e-mail addresses: normalise, mask, hash.

The relay never stores the identifier itself. `id_hash` (HMAC-SHA256 with the
deployment secret) is the account key; `hint` is what the account page shows.
"""

from __future__ import annotations

import hashlib
import hmac
import re
from dataclasses import dataclass

_EMAIL = re.compile(r"^[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}$")
_CN_MOBILE = re.compile(r"^1[3-9]\d{9}$")
_E164 = re.compile(r"^\+[1-9]\d{7,14}$")
_GMAIL_DOMAINS = frozenset({"gmail.com", "googlemail.com"})


class BadIdentifier(ValueError):
    pass


def _canonical_email(address: str) -> str:
    """Canonical form of an e-mail address for hashing and account identity.

    Every address has any ``+tag`` subaddress stripped from its local part so
    ``user+1@example.com`` and ``user@example.com`` are one account; Gmail and
    Googlemail addresses also have ``googlemail.com`` folded to ``gmail.com``
    and dots removed from the local part, matching how Gmail routes mail.
    """
    local, _, domain = address.partition("@")
    if "+" in local:
        local = local.split("+", 1)[0]
    if domain in _GMAIL_DOMAINS:
        domain = "gmail.com"
        local = local.replace(".", "")
    if not local:
        raise BadIdentifier("email")
    canonical = f"{local}@{domain}"
    if not _EMAIL.match(canonical) or len(canonical) > 254:
        raise BadIdentifier("email")
    return canonical


@dataclass(frozen=True)
class Identifier:
    channel: str  # phone | email
    value: str  # +8613800138000 / user@example.com (canonical)
    raw_value: str = ""  # as typed (lower-cased for email), when different from `value`

    @property
    def hint(self) -> str:
        if self.channel == "user":
            return self.value
        if self.channel == "phone":
            digits = self.value
            if digits.startswith("+86") and len(digits) == 14:
                n = digits[3:]
                return f"{n[:3]}****{n[-4:]}"
            return f"{digits[:4]}****{digits[-3:]}"
        local, _, domain = self.value.partition("@")
        shown = local[0] if len(local) <= 2 else local[:2]
        return f"{shown}***@{domain}"

    def hash(self, key: bytes) -> str:
        return hmac.new(key, f"{self.channel}:{self.value}".encode(), hashlib.sha256).hexdigest()

    def legacy_hash(self, key: bytes) -> str | None:
        """The pre-canonical hash when the typed address had dots, a ``+tag`` or
        ``googlemail.com``, so an existing account from before canonicalisation
        is still found when its holder signs in."""
        if not self.raw_value or self.raw_value == self.value:
            return None
        return hmac.new(key, f"{self.channel}:{self.raw_value}".encode(), hashlib.sha256).hexdigest()

    def hashes(self, key: bytes) -> tuple[str, ...]:
        """The canonical hash first, followed by the legacy hash when distinct."""
        primary = self.hash(key)
        legacy = self.legacy_hash(key)
        return (primary, legacy) if legacy and legacy != primary else (primary,)


def parse(raw: str) -> Identifier:
    s = (raw or "").strip()
    if not s:
        raise BadIdentifier("empty")
    if "@" in s:
        s = s.lower()
        if not _EMAIL.match(s) or len(s) > 254:
            raise BadIdentifier("email")
        canonical = _canonical_email(s)
        return Identifier("email", canonical, raw_value=s if canonical != s else "")
    digits = re.sub(r"[\s\-()]", "", s)
    if digits.startswith("0086"):
        digits = "+" + digits[2:]
    elif digits.startswith("86") and len(digits) == 13:
        digits = "+" + digits
    if _CN_MOBILE.match(digits):
        return Identifier("phone", "+86" + digits)
    if _E164.match(digits):
        return Identifier("phone", digits)
    if re.match(r"^[A-Za-z0-9_.\-@#]{1,64}$", s):
        return Identifier("user", s.lower())
    raise BadIdentifier("username")
