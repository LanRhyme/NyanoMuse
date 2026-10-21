"""Long-term memory: small facts about the user that persist across sessions.

Stored in SQLite. Retrieval is a lightweight keyword/bigram overlap score weighted
by how rare each word is in the store (words in any alphabetic script, Latin, Cyrillic,
Greek, Arabic and the rest, plus character bigrams for CJK, without external
embeddings); when an embedding endpoint is available (``embeddings.py``) a second
ranking by meaning is fused in. Memories can always be listed and *forgotten* by the
user. Every change a tidy-up makes (see ``consolidate.py``) is logged with the text
it replaced, so it can be undone.
"""

from __future__ import annotations

import hashlib
import json
import math
import re
import sqlite3
import uuid
from array import array
from dataclasses import asdict, dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:
    from nanomuse.memory.embeddings import MemoryIndex

# A word is a run of letters or digits in any script (Cyrillic, Greek, Arabic, accented
# Latin, ...); CJK is left out of the class and handled by character below. The old
# ``[A-Za-z0-9_]+`` made every non-Latin line an empty set, and the tidy-up's guard then
# had nothing to compare and approved anything (#270).
_WORD_RE = re.compile(r"[^\W_\u3400-\u9fff]+")
_CJK_RE = re.compile(r"[\u3400-\u9fff]")


def _now() -> str:
    return datetime.now(UTC).isoformat(timespec="seconds")


def _epoch_ms(iso: str) -> int:
    """ISO time to Unix milliseconds: the wire speaks milliseconds."""
    if not iso:
        return 0
    try:
        return int(datetime.fromisoformat(iso).timestamp() * 1000)
    except ValueError:
        return 0


def _iso_ms(ms: int) -> str:
    """Unix milliseconds to the ISO time this store keeps."""
    if not ms:
        return ""
    try:
        return datetime.fromtimestamp(int(ms) / 1000, UTC).isoformat(timespec="seconds")
    except (OverflowError, OSError, ValueError):
        return ""


@dataclass
class MemoryItem:
    id: str
    content: str
    category: str
    created_at: str
    source: str
    updated_at: str = ""

    def render(self) -> str:
        return f"[{self.id}] ({self.category}) {self.content}"


@dataclass
class MemoryChange:
    """One entry of the tidy-up log: what was there before, what is there now."""

    id: str
    at: str
    action: str  # merge | rewrite | drop
    before: list[MemoryItem] = field(default_factory=list)
    after: MemoryItem | None = None
    reason: str = ""
    restored: bool = False

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "at": self.at,
            "action": self.action,
            "before": [asdict(m) for m in self.before],
            "after": asdict(self.after) if self.after else None,
            "reason": self.reason,
            "restored": self.restored,
        }


def tokenize(text: str) -> set[str]:
    """Words for alphabetic scripts (case folded) + character bigrams for CJK."""
    tokens = {w.casefold() for w in _WORD_RE.findall(text) if len(w) > 1}
    cjk = "".join(_CJK_RE.findall(text))
    tokens.update(cjk[i : i + 2] for i in range(len(cjk) - 1))
    if len(cjk) == 1:
        tokens.add(cjk)
    return tokens


def similarity(a: str, b: str) -> float:
    """Jaccard overlap of the two texts' tokens: 1.0 is the same words, 0.0 none shared."""
    ta, tb = tokenize(a), tokenize(b)
    if not ta or not tb:
        return 1.0 if a.strip().lower() == b.strip().lower() else 0.0
    return len(ta & tb) / len(ta | tb)


def content_hash(text: str) -> str:
    return hashlib.sha1(text.strip().encode("utf-8")).hexdigest()[:16]


class MemoryStore:
    def __init__(self, path: Path | str):
        self.path = str(path)
        # recall by meaning, when an embedding endpoint is set up (see embeddings.py)
        self.index: MemoryIndex | None = None
        self._conn = sqlite3.connect(self.path, check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        self._conn.execute(
            """
            CREATE TABLE IF NOT EXISTS memories (
                id TEXT PRIMARY KEY,
                content TEXT NOT NULL,
                category TEXT NOT NULL DEFAULT 'general',
                created_at TEXT NOT NULL,
                source TEXT NOT NULL DEFAULT 'agent'
            )
            """
        )
        columns = {r["name"] for r in self._conn.execute("PRAGMA table_info(memories)")}
        if "updated_at" not in columns:
            self._conn.execute(
                "ALTER TABLE memories ADD COLUMN updated_at TEXT NOT NULL DEFAULT ''"
            )
        if "seq" not in columns:
            # the relay's sequence number of the change this row carries (C15)
            self._conn.execute("ALTER TABLE memories ADD COLUMN seq INTEGER NOT NULL DEFAULT 0")
        self._conn.execute(
            """
            CREATE TABLE IF NOT EXISTS memory_log (
                id TEXT PRIMARY KEY,
                at TEXT NOT NULL,
                action TEXT NOT NULL,
                before TEXT NOT NULL,
                after TEXT,
                reason TEXT NOT NULL DEFAULT '',
                restored INTEGER NOT NULL DEFAULT 0
            )
            """
        )
        self._conn.execute(
            "CREATE TABLE IF NOT EXISTS memory_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)"
        )
        # one vector per memory and embedding model; the hash says which text it is of
        self._conn.execute(
            """
            CREATE TABLE IF NOT EXISTS memory_vectors (
                memory_id TEXT NOT NULL,
                model TEXT NOT NULL,
                hash TEXT NOT NULL,
                vector BLOB NOT NULL,
                PRIMARY KEY (memory_id, model)
            )
            """
        )
        # every change made here, until the relay gives it a seq (C15)
        self._conn.execute(
            """
            CREATE TABLE IF NOT EXISTS memory_ops (
                op_id TEXT PRIMARY KEY,
                seq INTEGER NOT NULL DEFAULT 0,
                op_type TEXT NOT NULL,
                memory_id TEXT NOT NULL,
                payload TEXT NOT NULL,
                client_time INTEGER NOT NULL,
                created_at INTEGER NOT NULL
            )
            """
        )
        self._conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_memory_ops_pending ON memory_ops(seq, created_at)"
        )
        self._conn.commit()

    def close(self) -> None:
        self._conn.close()

    # ------------------------------------------------------------------ CRUD
    def add(self, content: str, category: str = "general", source: str = "agent") -> MemoryItem:
        content = content.strip()
        if not content:
            raise ValueError("memory content is empty")
        # De-duplicate exact matches.
        row = self._conn.execute("SELECT * FROM memories WHERE content = ?", (content,)).fetchone()
        if row:
            return self._row(row)
        item = MemoryItem(
            id="m_" + uuid.uuid4().hex[:8],
            content=content,
            category=category or "general",
            created_at=_now(),
            source=source,
        )
        self._insert(item)
        self._record_op(
            "upsert_memory", item.id, item.content, item.category, item.source, item.created_at
        )
        self._conn.commit()
        return item

    def update(
        self, memory_id: str, content: str, category: str | None = None
    ) -> MemoryItem | None:
        """Change what a memory says (the fact moved on); keeps its id and age."""
        content = content.strip()
        if not content:
            raise ValueError("memory content is empty")
        item = self.get(memory_id)
        if item is None:
            return None
        before = item.content
        item.content = content
        item.category = category or item.category
        item.updated_at = _now()
        self._conn.execute(
            "UPDATE memories SET content = ?, category = ?, updated_at = ? WHERE id = ?",
            (item.content, item.category, item.updated_at, item.id),
        )
        self._record_op(
            "upsert_memory",
            item.id,
            item.content,
            item.category,
            item.source,
            item.updated_at,
            before_content=before,
        )
        self._conn.commit()
        return item

    def get(self, memory_id: str) -> MemoryItem | None:
        row = self._conn.execute("SELECT * FROM memories WHERE id = ?", (memory_id,)).fetchone()
        return self._row(row) if row else None

    def all(self, limit: int = 1000) -> list[MemoryItem]:
        rows = self._conn.execute(
            "SELECT * FROM memories ORDER BY created_at DESC LIMIT ?", (limit,)
        ).fetchall()
        return [self._row(r) for r in rows]

    def _recent(self, limit: int = 1000) -> list[MemoryItem]:
        rows = self._conn.execute(
            "SELECT * FROM memories ORDER BY COALESCE(NULLIF(updated_at, ''), created_at) DESC, rowid DESC LIMIT ?",
            (limit,),
        ).fetchall()
        return [self._row(r) for r in rows]

    def forget(self, memory_id: str) -> bool:
        item = self.get(memory_id)
        cur = self._conn.execute("DELETE FROM memories WHERE id = ?", (memory_id,))
        if cur.rowcount and item is not None:
            self._record_op(
                "delete_memory",
                item.id,
                "",
                item.category,
                item.source,
                _now(),
                before_content=item.content,
                deleted=True,
            )
        self._conn.commit()
        return cur.rowcount > 0

    def forget_matching(self, query: str) -> int:
        """Forget every memory whose text contains ``query`` (literally, case-insensitive).

        An empty query forgets nothing: ``clear`` is the explicit way to drop everything.
        The deletion is logged as one change, so ``restore`` brings the memories back.
        """
        query = query.strip()
        if not query:
            return 0
        pattern = "%" + re.sub(r"([\\%_])", r"\\\1", query) + "%"
        rows = self._conn.execute(
            "SELECT * FROM memories WHERE content LIKE ? ESCAPE '\\'", (pattern,)
        ).fetchall()
        items = [self._row(r) for r in rows]
        if not items:
            return 0
        self._conn.executemany("DELETE FROM memories WHERE id = ?", [(m.id,) for m in items])
        for m in items:
            self._record_op(
                "delete_memory",
                m.id,
                "",
                m.category,
                m.source,
                _now(),
                before_content=m.content,
                deleted=True,
            )
        self._log("forget", items, None, f"matched {query!r}")
        self._conn.commit()
        return len(items)

    def clear(self) -> int:
        items = self.all()
        cur = self._conn.execute("DELETE FROM memories")
        for m in items:
            self._record_op(
                "delete_memory",
                m.id,
                "",
                m.category,
                m.source,
                _now(),
                before_content=m.content,
                deleted=True,
            )
        self._conn.commit()
        return cur.rowcount

    def count(self) -> int:
        return int(self._conn.execute("SELECT COUNT(*) FROM memories").fetchone()[0])

    # ------------------------------------------------------------------ oplog (C15)
    def _record_op(
        self,
        op_type: str,
        memory_id: str,
        content: str,
        category: str,
        source: str,
        updated_at: str,
        before_content: str = "",
        deleted: bool = False,
    ) -> None:
        """Queue one change for the relay. The op carries an id of its own (C15)."""
        now_ms = _epoch_ms(_now())
        payload = {
            "id": memory_id,
            "content": content,
            "before_content": before_content,
            "category": category or "general",
            "source": source or "agent",
            "is_pinned": False,
            "is_archived": False,
            "importance": 0.5,
            "updated_at": _epoch_ms(updated_at) or now_ms,
            "deleted": bool(deleted),
        }
        self._conn.execute(
            """INSERT INTO memory_ops (op_id, seq, op_type, memory_id, payload, client_time, created_at)
               VALUES (?, 0, ?, ?, ?, ?, ?)""",
            (
                uuid.uuid4().hex,
                op_type,
                memory_id,
                json.dumps(payload, ensure_ascii=False),
                now_ms,
                now_ms,
            ),
        )

    def pending_ops(self, limit: int = 100) -> list[dict[str, Any]]:
        """The changes made here that the relay has not taken yet, oldest first."""
        rows = self._conn.execute(
            "SELECT op_id, op_type, payload, client_time FROM memory_ops "
            "WHERE seq = 0 ORDER BY created_at ASC, rowid ASC LIMIT ?",
            (int(limit),),
        ).fetchall()
        return [
            {
                "op_id": str(r["op_id"]),
                "op_type": str(r["op_type"]),
                "client_time": int(r["client_time"]),
                "payload": json.loads(str(r["payload"])),
            }
            for r in rows
        ]

    def mark_accepted(self, assigned: list[tuple[str, int]]) -> None:
        """The relay took these: the op has a seq, and so does the row it describes."""
        for op_id, seq in assigned:
            self._conn.execute("UPDATE memory_ops SET seq = ? WHERE op_id = ?", (int(seq), op_id))
            row = self._conn.execute(
                "SELECT memory_id FROM memory_ops WHERE op_id = ?", (op_id,)
            ).fetchone()
            if row is None:
                continue
            memory_id = str(row["memory_id"])
            pending = self._conn.execute(
                "SELECT 1 FROM memory_ops WHERE memory_id = ? AND seq = 0 LIMIT 1", (memory_id,)
            ).fetchone()
            if not pending:
                self._conn.execute(
                    "UPDATE memories SET seq = ? WHERE id = ?", (int(seq), memory_id)
                )

    def has_op(self, op_id: str) -> bool:
        if not op_id:
            return False
        row = self._conn.execute("SELECT 1 FROM memory_ops WHERE op_id = ?", (op_id,)).fetchone()
        return row is not None

    def _local_pending(self, memory_id: str) -> bool:
        row = self._conn.execute(
            "SELECT 1 FROM memory_ops WHERE memory_id = ? AND seq = 0 LIMIT 1", (memory_id,)
        ).fetchone()
        return row is not None

    def apply_remote(self, op: dict[str, Any]) -> bool:
        """Apply one change from another device. The relay's seq decides: a row whose seq
        is newer stays, and a change this runtime has not pushed yet outranks anything
        arriving in the meantime."""
        op_id = str(op.get("op_id") or "")
        seq = int(op.get("seq") or 0)
        payload = op.get("payload") or {}
        memory_id = str(payload.get("id") or "")
        if not memory_id or self.has_op(op_id):
            return False

        row = self._conn.execute("SELECT * FROM memories WHERE id = ?", (memory_id,)).fetchone()
        current_seq = int(row["seq"]) if row is not None else 0
        if row is not None and (self._local_pending(memory_id) or current_seq > seq):
            self._keep_op(
                op_id, seq, str(op.get("op_type") or "upsert_memory"), memory_id, payload, op
            )
            return False

        if payload.get("deleted"):
            self._conn.execute("DELETE FROM memories WHERE id = ?", (memory_id,))
        else:
            content = str(payload.get("content") or "").strip()
            if not content:
                self._keep_op(
                    op_id, seq, str(op.get("op_type") or "upsert_memory"), memory_id, payload, op
                )
                return False
            updated_at = _iso_ms(int(payload.get("updated_at") or 0)) or _now()
            self._conn.execute(
                """INSERT INTO memories (id, content, category, created_at, source, updated_at, seq)
                   VALUES (?, ?, ?, ?, ?, ?, ?)
                   ON CONFLICT(id) DO UPDATE SET
                     content = excluded.content, category = excluded.category,
                     source = excluded.source, updated_at = excluded.updated_at, seq = excluded.seq""",
                (
                    memory_id,
                    content,
                    str(payload.get("category") or "general"),
                    updated_at,
                    str(payload.get("source") or "agent"),
                    updated_at,
                    seq,
                ),
            )
        self._keep_op(op_id, seq, str(op.get("op_type") or "upsert_memory"), memory_id, payload, op)
        self._conn.commit()
        return True

    def _keep_op(
        self,
        op_id: str,
        seq: int,
        op_type: str,
        memory_id: str,
        payload: dict[str, Any],
        op: dict[str, Any],
    ) -> None:
        """Remember a change that came in, so its echo is not applied twice."""
        self._conn.execute(
            """INSERT OR IGNORE INTO memory_ops
               (op_id, seq, op_type, memory_id, payload, client_time, created_at)
               VALUES (?, ?, ?, ?, ?, ?, ?)""",
            (
                op_id,
                int(seq),
                op_type,
                memory_id,
                json.dumps(payload, ensure_ascii=False),
                int(op.get("client_time") or 0),
                _epoch_ms(_now()),
            ),
        )

    def cursor(self) -> int:
        """Where this runtime is in the account's memory changes."""
        try:
            return int(self.get_meta("sync_cursor", "0") or 0)
        except ValueError:
            return 0

    def set_cursor(self, seq: int) -> None:
        self.set_meta("sync_cursor", str(max(0, int(seq))))

    # ------------------------------------------------------------------ retrieval
    def search(self, query: str, limit: int = 10) -> list[MemoryItem]:
        """Memories that share words with ``query``, rarest shared words counting most.

        A word that is in half the memories ("likes", "the", "我") says little about
        which one is meant; a word in one memory says everything. Scores are the sum of
        ``log(N / df)`` over the shared tokens, plus a bonus when the whole query appears
        verbatim.
        """
        q_tokens = tokenize(query)
        items = self.all()
        if not q_tokens:
            return items[:limit]
        tokens = {item.id: tokenize(item.content) for item in items}
        df: dict[str, int] = {}
        for toks in tokens.values():
            for tok in toks:
                df[tok] = df.get(tok, 0) + 1
        n = max(1, len(items))
        needle = query.strip().lower()
        scored: list[tuple[float, MemoryItem]] = []
        for item in items:
            shared = q_tokens & tokens[item.id]
            score = sum(math.log((n + 1) / df[tok]) for tok in shared)
            if needle and needle in item.content.lower():
                score += math.log(n + 1) + 1.0
            if score > 0:
                scored.append((score, item))
        scored.sort(key=lambda p: (-p[0], p[1].created_at))
        return [item for _, item in scored[:limit]]

    def relevant(self, context: str, limit: int = 20) -> list[MemoryItem]:
        """Memories to inject into the system prompt: matches first, then most recent."""
        items = self._recent()
        if len(items) <= limit:
            return items
        return self._fill(self.search(context, limit=limit), items, limit)

    @staticmethod
    def _fill(matched: list[MemoryItem], items: list[MemoryItem], limit: int) -> list[MemoryItem]:
        seen = {m.id for m in matched}
        for item in items:
            if len(matched) >= limit:
                break
            if item.id not in seen:
                matched.append(item)
                seen.add(item.id)
        if len(matched) >= limit and limit >= 2 and items and items[0].id not in seen:
            matched = [*matched[: limit - 1], items[0]]
        return matched

    async def search_async(self, query: str, limit: int = 10) -> list[MemoryItem]:
        """``search`` with the meaning-based ranking fused in when an index is set up."""
        if self.index is not None:
            return await self.index.search(query, limit=limit)
        return self.search(query, limit=limit)

    async def relevant_async(self, context: str, limit: int = 20) -> list[MemoryItem]:
        """``relevant`` with the meaning-based ranking fused in when an index is set up."""
        items = self._recent()
        if len(items) <= limit:
            return items
        if self.index is not None:
            return self._fill(await self.index.search(context, limit=limit), items, limit)
        return self._fill(self.search(context, limit=limit), items, limit)

    def similar(
        self, content: str, threshold: float = 0.5, limit: int = 3
    ) -> list[tuple[float, MemoryItem]]:
        """Memories that say roughly the same thing as ``content``, most alike first."""
        pairs = [(similarity(content, item.content), item) for item in self.all()]
        pairs = [(s, item) for s, item in pairs if s >= threshold]
        pairs.sort(key=lambda p: -p[0])
        return pairs[:limit]

    # ------------------------------------------------------------------ tidy-up log
    def replace(
        self,
        ids: list[str],
        content: str,
        category: str = "",
        action: str = "merge",
        reason: str = "",
    ) -> MemoryChange | None:
        """Put one memory in the place of ``ids`` and log the change.

        The new memory is added by the user if any of the old ones was; its age is the
        oldest of them, so a merged fact does not look freshly learned.
        """
        before = [m for m in (self.get(i) for i in ids) if m is not None]
        if not before or not content.strip():
            return None
        after = MemoryItem(
            id="m_" + uuid.uuid4().hex[:8],
            content=content.strip(),
            category=category or before[0].category,
            created_at=min(m.created_at for m in before),
            source="user" if any(m.source == "user" for m in before) else "agent",
            updated_at=_now(),
        )
        for m in before:
            self._conn.execute("DELETE FROM memories WHERE id = ?", (m.id,))
            self._record_op(
                "delete_memory",
                m.id,
                "",
                m.category,
                m.source,
                _now(),
                before_content=m.content,
                deleted=True,
            )
        self._insert(after)
        self._record_op(
            "upsert_memory", after.id, after.content, after.category, after.source, after.updated_at
        )
        change = self._log(action, before, after, reason)
        self._conn.commit()
        return change

    def drop(self, memory_id: str, reason: str = "") -> MemoryChange | None:
        """Forget one memory in a way that can be undone."""
        item = self.get(memory_id)
        if item is None:
            return None
        self._conn.execute("DELETE FROM memories WHERE id = ?", (item.id,))
        self._record_op(
            "delete_memory",
            item.id,
            "",
            item.category,
            item.source,
            _now(),
            before_content=item.content,
            deleted=True,
        )
        change = self._log("drop", [item], None, reason)
        self._conn.commit()
        return change

    def history(self, limit: int = 50) -> list[MemoryChange]:
        rows = self._conn.execute(
            "SELECT * FROM memory_log ORDER BY at DESC, rowid DESC LIMIT ?", (limit,)
        ).fetchall()
        return [self._change(r) for r in rows]

    def restore(self, change_id: str) -> MemoryChange | None:
        """Undo one logged change: the old memories come back, the new one goes."""
        row = self._conn.execute("SELECT * FROM memory_log WHERE id = ?", (change_id,)).fetchone()
        if row is None:
            return None
        change = self._change(row)
        if change.restored:
            return change
        if change.after is not None:
            self._conn.execute("DELETE FROM memories WHERE id = ?", (change.after.id,))
            self._record_op(
                "delete_memory",
                change.after.id,
                "",
                change.after.category,
                change.after.source,
                _now(),
                before_content=change.after.content,
                deleted=True,
            )
        for m in change.before:
            exists = self._conn.execute(
                "SELECT 1 FROM memories WHERE id = ? OR content = ?", (m.id, m.content)
            ).fetchone()
            if exists is None:
                self._insert(m)
                self._record_op(
                    "upsert_memory",
                    m.id,
                    m.content,
                    m.category,
                    m.source,
                    m.updated_at or m.created_at,
                )
        self._conn.execute("UPDATE memory_log SET restored = 1 WHERE id = ?", (change.id,))
        self._conn.commit()
        change.restored = True
        return change

    # ------------------------------------------------------------------ meta
    def get_meta(self, key: str, default: str = "") -> str:
        row = self._conn.execute("SELECT value FROM memory_meta WHERE key = ?", (key,)).fetchone()
        return str(row["value"]) if row else default

    def set_meta(self, key: str, value: str) -> None:
        self._conn.execute(
            "INSERT INTO memory_meta VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            (key, value),
        )
        self._conn.commit()

    # ------------------------------------------------------------------ vectors
    def vectors(self, model: str) -> dict[str, tuple[str, list[float]]]:
        """Stored vectors for ``model``: memory id → (hash of the text, vector)."""
        out: dict[str, tuple[str, list[float]]] = {}
        for row in self._conn.execute(
            "SELECT memory_id, hash, vector FROM memory_vectors WHERE model = ?", (model,)
        ):
            vec = array("f")
            vec.frombytes(row["vector"])
            out[row["memory_id"]] = (row["hash"], vec.tolist())
        return out

    def put_vectors(self, model: str, entries: dict[str, tuple[str, list[float]]]) -> None:
        self._conn.executemany(
            "INSERT INTO memory_vectors (memory_id, model, hash, vector) VALUES (?,?,?,?) "
            "ON CONFLICT(memory_id, model) DO UPDATE SET hash = excluded.hash, vector = excluded.vector",
            [(mid, model, h, array("f", vec).tobytes()) for mid, (h, vec) in entries.items()],
        )
        self._conn.commit()

    def drop_vectors(self, ids: list[str], model: str | None = None) -> None:
        if not ids:
            return
        if model is None:
            self._conn.executemany(
                "DELETE FROM memory_vectors WHERE memory_id = ?", [(i,) for i in ids]
            )
        else:
            self._conn.executemany(
                "DELETE FROM memory_vectors WHERE memory_id = ? AND model = ?",
                [(i, model) for i in ids],
            )
        self._conn.commit()

    # ------------------------------------------------------------------ helpers
    def _insert(self, item: MemoryItem) -> None:
        self._conn.execute(
            "INSERT INTO memories (id, content, category, created_at, source, updated_at) VALUES (?,?,?,?,?,?)",
            (item.id, item.content, item.category, item.created_at, item.source, item.updated_at),
        )

    def _log(
        self, action: str, before: list[MemoryItem], after: MemoryItem | None, reason: str
    ) -> MemoryChange:
        change = MemoryChange(
            id="c_" + uuid.uuid4().hex[:8],
            at=_now(),
            action=action,
            before=before,
            after=after,
            reason=reason,
        )
        self._conn.execute(
            "INSERT INTO memory_log (id, at, action, before, after, reason, restored) VALUES (?,?,?,?,?,?,0)",
            (
                change.id,
                change.at,
                action,
                json.dumps([asdict(m) for m in before], ensure_ascii=False),
                json.dumps(asdict(after), ensure_ascii=False) if after else None,
                reason,
            ),
        )
        return change

    @staticmethod
    def _row(row: sqlite3.Row) -> MemoryItem:
        return MemoryItem(
            id=row["id"],
            content=row["content"],
            category=row["category"],
            created_at=row["created_at"],
            source=row["source"],
            updated_at=row["updated_at"] or "",
        )

    @staticmethod
    def _change(row: sqlite3.Row) -> MemoryChange:
        return MemoryChange(
            id=row["id"],
            at=row["at"],
            action=row["action"],
            before=[MemoryItem(**m) for m in json.loads(row["before"])],
            after=MemoryItem(**json.loads(row["after"])) if row["after"] else None,
            reason=row["reason"],
            restored=bool(row["restored"]),
        )


__all__ = ["MemoryChange", "MemoryItem", "MemoryStore", "content_hash", "similarity", "tokenize"]
