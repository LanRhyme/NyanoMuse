"""Conversation sync (0.19): the text of an account's conversations, kept so every device of
the account shows the same chats and a new device can catch up.

What is stored: conversations (an id, a kind, a title, which device started it) and messages
(an id, the role, the text — cut at 16 KB — and the names and sizes of attachments). Never a
file, never a picture, never anything of another account. One change counter per account
gives every accepted change a `seq`; a device keeps the highest seq it has seen (its cursor)
and asks for what came after it.

The main chat is one conversation for the whole account: the first `main` a device pushes
sets its id; a second one is refused with `main_exists` and the id to use instead. The
relay keeps the one it already has — devices' clocks disagree, the arrival order does not,
and every client already handles the refusal.

On by default for a signed-in account; turning it off deletes everything stored. Deleting
a chat tombstones the conversation (so the other devices drop it) and removes its texts at
once; tombstones go after 30 days. At most 20,000 messages per account: beyond that, the
oldest conversations' messages are dropped from the relay (devices keep their own copies).
At most 2,000 live side conversations: a new one past that is refused with
`conversation_limit` (0.23) and stays on the device.

Main first (0.20, contract C9). A device that only wants the account's main conversation
pulls with ``scope=main``; a fresh device asks for the tail — ``since=0&tail=300`` — and
gets the newest messages with their conversations instead of the whole store, so the first
sign-in shows a chat in seconds however big the history is. Presence rides alongside: a
device says ``working`` on a conversation when a turn starts there and when it ends; the
relay tells the account's other sockets and remembers the latest ``working: true`` for ten
minutes, in memory only — it is a hint for the screen, not data worth a table.
"""

from __future__ import annotations

import json
import re
import sqlite3
import time
from typing import Any

from .db import Database
from .service import CloudError

LIMITS = {"messages": 20000, "text_bytes": 16384, "conversations": 2000}
MAX_POST_MESSAGES = 200
DEFAULT_PAGE = 500
MAX_PAGE = 1000
TOMBSTONE_DAYS = 30
MAX_ATTACHMENTS = 20
KINDS = ("main", "side")
ROLES = ("user", "assistant")
SCOPES = ("all", "main")
MAX_TAIL = 500
# how long a device's `working: true` stands without a `working: false` (the device may
# have lost its network mid-turn; the readers' line goes away on its own)
WORKING_TTL_S = 10 * 60
_ID = re.compile(r"^[a-z0-9][a-z0-9._:-]{3,63}$")


def now() -> int:
    return int(time.time())


# -- pure helpers -----------------------------------------------------------------------------


def valid_id(value: object) -> str | None:
    """A conversation or message id: lower-case, 4–64 of letters, digits, . _ : -  (a UUID v4
    fits; so does anything a device mints that is not a secret). None when it is not one."""
    if not isinstance(value, str):
        return None
    s = value.strip().lower()
    return s if _ID.match(s) else None


def cut_text(text: object, limit: int = LIMITS["text_bytes"]) -> tuple[str, bool]:
    """The text as stored: cut at `limit` UTF-8 bytes without splitting a character, and
    whether it was cut. Not a string → empty."""
    if not isinstance(text, str):
        return "", False
    raw = text.encode("utf-8")
    if len(raw) <= limit:
        return text, False
    return raw[:limit].decode("utf-8", "ignore"), True


def clean_attachments(value: object) -> list[dict]:
    """Attachment metadata, nothing else: name, mime, size — at most MAX_ATTACHMENTS."""
    if not isinstance(value, list):
        return []
    out: list[dict] = []
    for a in value[:MAX_ATTACHMENTS]:
        if not isinstance(a, dict):
            continue
        name = str(a.get("name") or "")[:200]
        mime = str(a.get("mime") or "")[:100]
        try:
            size = max(0, int(a.get("size") or 0))
        except (TypeError, ValueError):
            size = 0
        if name or mime or size:
            out.append({"name": name, "mime": mime, "size": size})
    return out


def _ts(value: object, default: int, ceiling: int | None = None) -> int:
    """A Unix time in seconds from what a device sent: milliseconds are divided down, anything
    unreadable, non-positive or more than a week past `ceiling` (the relay's clock; `default`
    when not given) becomes `default`."""
    try:
        t = int(float(value))  # type: ignore[arg-type]
    except (TypeError, ValueError):
        return default
    if t > 10_000_000_000:  # milliseconds
        t //= 1000
    if t <= 0 or t > (ceiling if ceiling is not None else default) + 7 * 86400:
        return default
    return t


def _conversation_row(r: sqlite3.Row, names: dict[str, str]) -> dict:
    return {
        "cid": r["cid"],
        "kind": r["kind"],
        "title": r["title"],
        "device": r["device"],
        "device_name": names.get(r["device"], ""),
        "created_at": int(r["created_at"]),
        "updated_at": int(r["updated_at"]),
        "deleted": bool(r["deleted"]),
        "seq": int(r["seq"]),
    }


def _message_row(r: sqlite3.Row, names: dict[str, str]) -> dict:
    try:
        attachments = json.loads(r["attachments"] or "[]")
    except ValueError:
        attachments = []
    return {
        "mid": r["mid"],
        "cid": r["cid"],
        "seq": int(r["seq"]),
        "device": r["device"],
        "device_name": names.get(r["device"], ""),
        "role": r["role"],
        "text": r["text"],
        "truncated": bool(r["truncated"]),
        "attachments": attachments if isinstance(attachments, list) else [],
        "created_at": int(r["created_at"]),
        "deleted": bool(r["deleted"]),
    }


# -- presence -----------------------------------------------------------------------------------


class Presence:
    """Who is working on which conversation right now (0.20): the latest `working: true` per
    (account, cid), kept in memory for WORKING_TTL_S and dropped by a `working: false` from
    the same device. Never written to disk — a restart forgets it, and that is right."""

    def __init__(self, clock=time.time):
        self.clock = clock
        self._live: dict[tuple[str, str], dict] = {}

    def set(self, account_id: str, cid: str, device: str, device_name: str, working: bool) -> dict:
        """Record the change; returns the frame body the hub sends (without `type`)."""
        at = int(self.clock())
        key = (account_id, cid)
        if working:
            self._live[key] = {"cid": cid, "from": device, "device_name": device_name, "at": at}
        else:
            cur = self._live.get(key)
            # another device's `true` stands: the one that finished is not the one working
            if cur is not None and cur["from"] == device:
                del self._live[key]
        return {"cid": cid, "from": device, "device_name": device_name, "working": working, "at": at}

    def working(self, account_id: str) -> list[dict]:
        """The unexpired entries of one account, oldest first."""
        self._sweep()
        out = [dict(v) for (acc, _), v in self._live.items() if acc == account_id]
        out.sort(key=lambda v: v["at"])
        return out

    def forget(self, account_id: str) -> None:
        for key in [k for k in self._live if k[0] == account_id]:
            del self._live[key]

    def _sweep(self) -> None:
        cutoff = int(self.clock()) - WORKING_TTL_S
        for key in [k for k, v in self._live.items() if v["at"] < cutoff]:
            del self._live[key]


# -- the store ----------------------------------------------------------------------------------


class SyncStore:
    """The account's synced conversations over the relay's database. Every public method is
    one transaction; `push` is the only one with rules worth reading (above)."""

    def __init__(self, db: Database, presence: Presence | None = None):
        self.db = db
        self.presence = presence if presence is not None else Presence()

    # -- state --

    def _cursor(self, c: sqlite3.Connection, account_id: str) -> int:
        row = c.execute("SELECT seq FROM sync_cursors WHERE account_id=?", (account_id,)).fetchone()
        return int(row["seq"]) if row is not None else 0

    def _next_seq(self, c: sqlite3.Connection, account_id: str) -> int:
        c.execute("INSERT OR IGNORE INTO sync_cursors(account_id, seq) VALUES (?, 0)", (account_id,))
        c.execute("UPDATE sync_cursors SET seq=seq+1 WHERE account_id=?", (account_id,))
        return self._cursor(c, account_id)

    def _state(self, c: sqlite3.Connection, account_id: str) -> dict:
        row = c.execute("SELECT sync_enabled FROM accounts WHERE id=?", (account_id,)).fetchone()
        convs = c.execute("SELECT COUNT(*) FROM sync_conversations WHERE account_id=? AND deleted=0", (account_id,)).fetchone()[0]
        msgs = c.execute("SELECT COUNT(*) FROM sync_messages WHERE account_id=? AND deleted=0", (account_id,)).fetchone()[0]
        return {
            "enabled": bool(row["sync_enabled"]) if row is not None else False,
            "cursor": self._cursor(c, account_id),
            "counts": {"conversations": int(convs), "messages": int(msgs)},
            "limits": dict(LIMITS),
            "working": self.presence.working(account_id),
        }

    def state(self, account_id: str) -> dict:
        with self.db.tx() as c:
            return self._state(c, account_id)

    def set_enabled(self, account_id: str, enabled: bool) -> dict:
        """Off deletes everything stored (the person asked for exactly that); on starts
        with an empty store — the counter keeps counting, so no device's cursor goes stale."""
        with self.db.tx() as c:
            c.execute("UPDATE accounts SET sync_enabled=? WHERE id=?", (1 if enabled else 0, account_id))
            if not enabled:
                self._wipe(c, account_id)
            return self._state(c, account_id)

    def _wipe(self, c: sqlite3.Connection, account_id: str) -> None:
        c.execute("DELETE FROM sync_messages WHERE account_id=?", (account_id,))
        c.execute("DELETE FROM sync_conversations WHERE account_id=?", (account_id,))
        self.presence.forget(account_id)

    def wipe(self, account_id: str) -> dict:
        """`DELETE /v1/sync/changes`: the store emptied, the switch left where it is."""
        with self.db.tx() as c:
            self._wipe(c, account_id)
            return self._state(c, account_id)

    # -- reads --

    def _device_names(self, c: sqlite3.Connection, account_id: str) -> dict[str, str]:
        rows = c.execute("SELECT id, name FROM devices WHERE account_id=?", (account_id,)).fetchall()
        return {str(r["id"]): str(r["name"]) for r in rows}

    def changes(self, account_id: str, since: int = 0, limit: int = DEFAULT_PAGE, scope: str = "all", tail: int = 0) -> dict:
        """Everything with a seq after `since`, in seq order, at most `limit` rows; `more`
        says there is another page, and `cursor` is where to continue (the account's counter
        when this was the last page).

        `scope="main"` (0.20) narrows it to the account's main conversation — its row, its
        messages, its tombstone; with no main yet the page is empty and `cursor` the
        account's counter. `tail=K` (with `since=0` only) is the first pull of a fresh
        device: the newest K messages of the scope in seq order plus the rows of the
        conversations they belong to (and the main conversation's row whatever its messages,
        0.25), `cursor` at the account's counter, `more` false and `skipped` saying how many
        older messages stayed on the relay."""
        since = max(0, int(since))
        limit = max(1, min(int(limit), MAX_PAGE))
        if scope not in SCOPES:
            raise CloudError(400, "bad_scope", "scope is all or main")
        tail = max(0, min(int(tail or 0), MAX_TAIL)) if since == 0 else 0
        # the scope as a SQL fragment over sync_conversations (sc) and sync_messages (sm)
        conv_scope = " AND kind='main'" if scope == "main" else ""
        msg_scope = " AND cid IN (SELECT cid FROM sync_conversations WHERE account_id=? AND kind='main')" if scope == "main" else ""
        with self.db.tx() as c:
            if not self._enabled(c, account_id):
                raise CloudError(409, "sync_off", "Conversation sync is off for this account")
            names = self._device_names(c, account_id)
            skipped: int | None = None
            if tail:
                msgs = c.execute(
                    f"SELECT * FROM sync_messages WHERE account_id=?{msg_scope} ORDER BY seq DESC LIMIT ?",
                    (account_id, *((account_id,) if msg_scope else ()), tail),
                ).fetchall()
                msgs.reverse()
                total = c.execute(
                    f"SELECT COUNT(*) FROM sync_messages WHERE account_id=?{msg_scope}",
                    (account_id, *((account_id,) if msg_scope else ())),
                ).fetchone()[0]
                skipped = max(0, int(total) - len(msgs))
                rows: list[tuple[int, str, sqlite3.Row]] = [(int(r["seq"]), "m", r) for r in msgs]
                more = False
                cursor = self._cursor(c, account_id)
            else:
                convs = c.execute(
                    f"SELECT * FROM sync_conversations WHERE account_id=? AND seq>?{conv_scope} ORDER BY seq LIMIT ?",
                    (account_id, since, limit + 1),
                ).fetchall()
                msgs = c.execute(
                    f"SELECT * FROM sync_messages WHERE account_id=? AND seq>?{msg_scope} ORDER BY seq LIMIT ?",
                    (account_id, since, *((account_id,) if msg_scope else ()), limit + 1),
                ).fetchall()
                rows = [(int(r["seq"]), "c", r) for r in convs] + [(int(r["seq"]), "m", r) for r in msgs]
                rows.sort(key=lambda x: x[0])
                more = len(rows) > limit
                rows = rows[:limit]
                cursor = rows[-1][0] if more and rows else self._cursor(c, account_id)
            conversations = [r for _, k, r in rows if k == "c"]
            # A rename gives a conversation a seq above its messages, so a device starting
            # from zero would meet the messages first. Every message's conversation rides
            # along with the page; clients apply the conversations before the messages.
            have = {str(r["cid"]) for r in conversations}
            parents = [str(r["cid"]) for _, k, r in rows if k == "m" and str(r["cid"]) not in have]
            if parents:
                wanted = sorted(set(parents))
                marks = ",".join("?" for _ in wanted)
                extra = c.execute(
                    f"SELECT * FROM sync_conversations WHERE account_id=? AND cid IN ({marks}) ORDER BY seq",
                    (account_id, *wanted),
                ).fetchall()
                conversations.extend(extra)
                have.update(str(r["cid"]) for r in extra)
            if tail:
                # 0.25: the account's main conversation rides with every tail page, even when
                # none of the newest messages are its own (or it has none yet), so a fresh
                # device adopts its id at once instead of minting a second main and learning
                # the first one's id from a `main_exists` refusal
                main = c.execute(
                    "SELECT * FROM sync_conversations WHERE account_id=? AND kind='main' AND deleted=0 ORDER BY seq LIMIT 1", (account_id,)
                ).fetchone()
                if main is not None and str(main["cid"]) not in have:
                    conversations.append(main)
        out = {
            "cursor": cursor,
            "more": more,
            "conversations": [_conversation_row(r, names) for r in conversations],
            "messages": [_message_row(r, names) for _, k, r in rows if k == "m"],
        }
        if skipped is not None:
            out["skipped"] = skipped
        return out

    def set_working(self, account_id: str, cid: str, device: str, working: bool) -> dict:
        """`POST /v1/sync/working`: presence for one conversation. Returns the frame body the
        hub sends to the account's other sockets. 404 for a cid the relay does not hold."""
        cid_ok = valid_id(cid)
        if cid_ok is None:
            raise CloudError(400, "bad_cid", "That is not a conversation id")
        device = str(device or "")[:80]
        with self.db.tx() as c:
            if not self._enabled(c, account_id):
                raise CloudError(409, "sync_off", "Conversation sync is off for this account")
            row = c.execute("SELECT deleted FROM sync_conversations WHERE account_id=? AND cid=?", (account_id, cid_ok)).fetchone()
            if row is None or row["deleted"]:
                raise CloudError(404, "no_conversation", "No synced conversation with that id")
            name = self._device_names(c, account_id).get(device, "")
        return self.presence.set(account_id, cid_ok, device, name, bool(working))

    @staticmethod
    def _live_conversations(c: sqlite3.Connection, account_id: str) -> int:
        return int(
            c.execute("SELECT COUNT(*) FROM sync_conversations WHERE account_id=? AND kind='side' AND deleted=0", (account_id,)).fetchone()[
                0
            ]
        )

    def _enabled(self, c: sqlite3.Connection, account_id: str) -> bool:
        row = c.execute("SELECT sync_enabled FROM accounts WHERE id=?", (account_id,)).fetchone()
        return bool(row["sync_enabled"]) if row is not None else False

    # -- writes --

    def push(self, account_id: str, device: str, conversations: list, messages: list) -> dict:
        """`POST /v1/sync/changes`. Idempotent: a known mid is left alone unless the new one
        is a tombstone; a known cid takes the newer title / updated_at / deleted. Returns
        the new cursor, how many changes took a seq, and what was refused and why."""
        if not isinstance(conversations, list) or not isinstance(messages, list):
            raise CloudError(400, "bad_request", "conversations and messages are lists")
        if len(messages) > MAX_POST_MESSAGES:
            raise CloudError(413, "too_many_messages", f"At most {MAX_POST_MESSAGES} messages per request")
        if len(conversations) > MAX_POST_MESSAGES:
            raise CloudError(413, "too_many_conversations", f"At most {MAX_POST_MESSAGES} conversations per request")
        device = str(device or "")[:80]
        t = now()
        accepted = 0
        rejected: list[dict] = []
        # cids refused as a second main in this request → their messages are refused the same way
        redirected: dict[str, str] = {}
        with self.db.tx() as c:
            if not self._enabled(c, account_id):
                raise CloudError(409, "sync_off", "Conversation sync is off for this account")
            self._sweep(c, t)
            for conv in conversations:
                if not isinstance(conv, dict):
                    continue
                cid = valid_id(conv.get("cid"))
                if cid is None:
                    rejected.append({"cid": str(conv.get("cid") or "")[:80], "reason": "bad_cid"})
                    continue
                kind = str(conv.get("kind") or "side")
                if kind not in KINDS:
                    rejected.append({"cid": cid, "reason": "bad_kind"})
                    continue
                title = str(conv.get("title") or "")[:200]
                if not deleted and (title.startswith("动态 · ") or title.startswith("Feed · ") or title in ("动态", "Feed")):
                    continue
                created_at = _ts(conv.get("created_at"), t)
                # 0.25: the week's grace is measured against the relay's clock, not the
                # conversation's birth; before, a chat older than a week could never be
                # renamed, since its `updated_at` fell back to `created_at` and lost
                updated_at = max(created_at, _ts(conv.get("updated_at"), created_at, ceiling=t))
                deleted = bool(conv.get("deleted"))
                row = c.execute("SELECT * FROM sync_conversations WHERE account_id=? AND cid=?", (account_id, cid)).fetchone()
                if row is None:
                    if kind == "side" and not deleted and self._live_conversations(c, account_id) >= LIMITS["conversations"]:
                        # messages are trimmed past their cap; conversations had none and grew
                        # without end (two hundred empty ones per request). The device keeps
                        # its copy; it learns from the refusal as it does from `main_exists`.
                        rejected.append({"cid": cid, "reason": "conversation_limit"})
                        continue  # its messages are refused below as `unknown_cid`
                    if kind == "main" and not deleted:
                        main = c.execute(
                            "SELECT cid FROM sync_conversations WHERE account_id=? AND kind='main' AND deleted=0 ORDER BY seq LIMIT 1",
                            (account_id,),
                        ).fetchone()
                        if main is not None:
                            redirected[cid] = str(main["cid"])
                            rejected.append({"cid": cid, "reason": "main_exists", "cid_main": str(main["cid"])})
                            continue
                    seq = self._next_seq(c, account_id)
                    c.execute(
                        "INSERT INTO sync_conversations(account_id, cid, kind, title, device, created_at, updated_at, deleted, deleted_at, seq) "
                        "VALUES (?,?,?,?,?,?,?,?,?,?)",
                        (
                            account_id,
                            cid,
                            kind,
                            "" if deleted else title,
                            device,
                            created_at,
                            updated_at,
                            1 if deleted else 0,
                            t if deleted else None,
                            seq,
                        ),
                    )
                    accepted += 1
                    continue
                if row["deleted"]:
                    continue  # a tombstone stays one; the device learns so on its next pull
                if deleted:
                    self._tombstone(c, account_id, cid, t)
                    accepted += 1
                elif updated_at >= int(row["updated_at"]) and (title != row["title"] or updated_at != int(row["updated_at"])):
                    seq = self._next_seq(c, account_id)
                    c.execute(
                        "UPDATE sync_conversations SET title=?, updated_at=?, seq=? WHERE account_id=? AND cid=?",
                        (title, updated_at, seq, account_id, cid),
                    )
                    accepted += 1
            touched_cids: set[str] = set()
            for msg in messages:
                if not isinstance(msg, dict):
                    continue
                mid = valid_id(msg.get("mid"))
                if mid is None:
                    rejected.append({"mid": str(msg.get("mid") or "")[:80], "reason": "bad_mid"})
                    continue
                cid = valid_id(msg.get("cid"))
                if cid is None:
                    rejected.append({"mid": mid, "reason": "bad_cid"})
                    continue
                if cid in redirected:
                    rejected.append({"mid": mid, "reason": "main_exists", "cid_main": redirected[cid]})
                    continue
                deleted = bool(msg.get("deleted"))
                row = c.execute("SELECT deleted FROM sync_messages WHERE account_id=? AND mid=?", (account_id, mid)).fetchone()
                if row is not None:
                    if deleted and not row["deleted"]:
                        seq = self._next_seq(c, account_id)
                        c.execute(
                            "UPDATE sync_messages SET deleted=1, deleted_at=?, text='', truncated=0, attachments='[]', seq=? WHERE account_id=? AND mid=?",
                            (t, seq, account_id, mid),
                        )
                        accepted += 1
                    continue
                conv = c.execute("SELECT deleted FROM sync_conversations WHERE account_id=? AND cid=?", (account_id, cid)).fetchone()
                if conv is None:
                    rejected.append({"mid": mid, "reason": "unknown_cid"})
                    continue
                if conv["deleted"]:
                    rejected.append({"mid": mid, "reason": "conversation_deleted"})
                    continue
                role = str(msg.get("role") or "")
                if role not in ROLES:
                    rejected.append({"mid": mid, "reason": "bad_role"})
                    continue
                text, truncated = cut_text(msg.get("text"))
                seq = self._next_seq(c, account_id)
                c.execute(
                    "INSERT INTO sync_messages(account_id, mid, cid, seq, device, role, text, truncated, attachments, created_at, deleted, deleted_at) "
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    (
                        account_id,
                        mid,
                        cid,
                        seq,
                        device,
                        role,
                        "" if deleted else text,
                        0 if deleted else (1 if truncated else 0),
                        "[]" if deleted else json.dumps(clean_attachments(msg.get("attachments")), ensure_ascii=False),
                        _ts(msg.get("created_at"), t),
                        1 if deleted else 0,
                        t if deleted else None,
                    ),
                )
                touched_cids.add(cid)
                accepted += 1
            if touched_cids:
                self._retain(c, account_id, touched_cids)
            cursor = self._cursor(c, account_id)
        return {"cursor": cursor, "accepted": accepted, "rejected": rejected}

    def _tombstone(self, c: sqlite3.Connection, account_id: str, cid: str, t: int) -> int:
        """The conversation marked deleted (title gone) with a new seq; its texts removed at once."""
        seq = self._next_seq(c, account_id)
        c.execute(
            "UPDATE sync_conversations SET deleted=1, deleted_at=?, title='', updated_at=?, seq=? WHERE account_id=? AND cid=?",
            (t, t, seq, account_id, cid),
        )
        c.execute("DELETE FROM sync_messages WHERE account_id=? AND cid=?", (account_id, cid))
        return seq

    def delete_conversation(self, account_id: str, cid: str) -> dict:
        """`DELETE /v1/sync/conversations/{cid}`: the tombstone every other device will see."""
        cid_ok = valid_id(cid)
        if cid_ok is None:
            raise CloudError(400, "bad_cid", "That is not a conversation id")
        with self.db.tx() as c:
            if not self._enabled(c, account_id):
                raise CloudError(409, "sync_off", "Conversation sync is off for this account")
            row = c.execute("SELECT deleted FROM sync_conversations WHERE account_id=? AND cid=?", (account_id, cid_ok)).fetchone()
            if row is None:
                raise CloudError(404, "no_conversation", "No synced conversation with that id")
            if not row["deleted"]:
                self._tombstone(c, account_id, cid_ok, now())
            return {"cursor": self._cursor(c, account_id), "deleted": True}

    def _retain(self, c: sqlite3.Connection, account_id: str, keep: set[str]) -> None:
        """Over the message limit: the oldest conversations' messages go first (the ones not
        written in this request); when one conversation holds it all, its oldest messages go."""
        limit = LIMITS["messages"]
        count = int(c.execute("SELECT COUNT(*) FROM sync_messages WHERE account_id=? AND deleted=0", (account_id,)).fetchone()[0])
        if count <= limit:
            return
        rows = c.execute(
            """SELECT sc.cid, COUNT(sm.mid) AS n FROM sync_conversations sc
               JOIN sync_messages sm ON sm.account_id=sc.account_id AND sm.cid=sc.cid AND sm.deleted=0
               WHERE sc.account_id=? GROUP BY sc.cid ORDER BY sc.updated_at, sc.seq""",
            (account_id,),
        ).fetchall()
        for r in rows:
            if count <= limit:
                return
            if str(r["cid"]) in keep:
                continue
            c.execute("DELETE FROM sync_messages WHERE account_id=? AND cid=? AND deleted=0", (account_id, r["cid"]))
            count -= int(r["n"])
        if count > limit:
            # only the conversations written just now are left: trim their oldest messages
            c.execute(
                """DELETE FROM sync_messages WHERE account_id=? AND deleted=0 AND rowid IN (
                     SELECT rowid FROM sync_messages WHERE account_id=? AND deleted=0 ORDER BY created_at, seq LIMIT ?)""",
                (account_id, account_id, count - limit),
            )

    @staticmethod
    def _sweep(c: sqlite3.Connection, t: int) -> None:
        """Tombstones older than TOMBSTONE_DAYS go — every device that was going to see them has."""
        cutoff = t - TOMBSTONE_DAYS * 86400
        c.execute("DELETE FROM sync_messages WHERE deleted=1 AND deleted_at IS NOT NULL AND deleted_at<?", (cutoff,))
        c.execute("DELETE FROM sync_conversations WHERE deleted=1 AND deleted_at IS NOT NULL AND deleted_at<?", (cutoff,))

    # -- the operator --

    def admin_totals(self) -> dict[str, Any]:
        """Aggregates only — how many accounts have it on, how much is stored — never a text."""
        with self.db.tx() as c:
            acc = c.execute(
                "SELECT SUM(CASE WHEN sync_enabled=1 THEN 1 ELSE 0 END) AS on_, SUM(CASE WHEN sync_enabled=0 THEN 1 ELSE 0 END) AS off FROM accounts"
            ).fetchone()
            convs = c.execute("SELECT COUNT(*) FROM sync_conversations WHERE deleted=0").fetchone()[0]
            msgs = c.execute("SELECT COUNT(*), COALESCE(SUM(LENGTH(CAST(text AS BLOB))),0) FROM sync_messages WHERE deleted=0").fetchone()
            accounts_with = c.execute("SELECT COUNT(DISTINCT account_id) FROM sync_messages WHERE deleted=0").fetchone()[0]
        return {
            "accounts_enabled": int(acc["on_"] or 0),
            "accounts_disabled": int(acc["off"] or 0),
            "accounts_with_data": int(accounts_with),
            "conversations": int(convs),
            "messages": int(msgs[0]),
            "bytes": int(msgs[1]),
            "limits": dict(LIMITS),
        }
