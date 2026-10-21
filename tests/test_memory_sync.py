"""Memory changes with the account (C15), the runtime's half.

Every write queues one operation with an id of its own; the relay gives it a seq, the other
devices apply the changes in that order, and a change this runtime has not pushed yet is not
overwritten by one that arrives in the meantime.
"""

from __future__ import annotations

from typing import Any

from nanomuse.memory.store import MemoryStore
from nanomuse.sync.memory_sync import MemorySync


class FakeRelay:
    """The relay of C15 in memory: one seq counter, one op per id."""

    def __init__(self) -> None:
        self.ops: list[dict[str, Any]] = []
        self.seq = 0

    async def push_memory(self, device: str, ops: list[dict[str, Any]]) -> dict[str, Any]:
        assigned: list[dict[str, Any]] = []
        accepted = 0
        for op in ops:
            known = next((o for o in self.ops if o["op_id"] == op["op_id"]), None)
            if known is not None:
                assigned.append({"op_id": op["op_id"], "seq": known["seq"]})
                continue
            self.seq += 1
            self.ops.append({**op, "seq": self.seq})
            assigned.append({"op_id": op["op_id"], "seq": self.seq})
            accepted += 1
        return {"cursor": self.seq, "accepted": accepted, "assigned": assigned}

    async def pull_memory(self, since: int = 0, limit: int = 200) -> dict[str, Any]:
        page = [o for o in self.ops if o["seq"] > since]
        more = len(page) > limit
        rows = page[:limit] if more else page
        cursor = rows[-1]["seq"] if more and rows else self.seq
        return {"cursor": cursor, "more": more, "ops": rows}


def _remote(
    op_id: str, seq: int, memory_id: str, content: str, deleted: bool = False
) -> dict[str, Any]:
    return {
        "op_id": op_id,
        "seq": seq,
        "op_type": "delete_memory" if deleted else "upsert_memory",
        "client_time": 1,
        "payload": {
            "id": memory_id,
            "content": "" if deleted else content,
            "before_content": content,
            "category": "general",
            "source": "agent",
            "is_pinned": False,
            "is_archived": False,
            "importance": 0.5,
            "updated_at": 1_700_000_000_000,
            "deleted": deleted,
        },
    }


def test_a_write_queues_one_op_and_the_same_text_again_queues_nothing(tmp_path) -> None:
    store = MemoryStore(tmp_path / "memory.db")
    store.add("临时脚本放 ~/tmp")
    assert len(store.pending_ops()) == 1

    store.add("临时脚本放 ~/tmp")
    assert len(store.pending_ops()) == 1
    store.close()


def test_an_accepted_op_gives_the_row_the_relay_seq(tmp_path) -> None:
    store = MemoryStore(tmp_path / "memory.db")
    item = store.add("电脑是 Arch Linux")
    op = store.pending_ops()[0]

    store.mark_accepted([(op["op_id"], 7)])
    assert store.pending_ops() == []
    row = store._conn.execute("SELECT seq FROM memories WHERE id = ?", (item.id,)).fetchone()
    assert int(row["seq"]) == 7
    store.close()


def test_a_remote_change_applies_once_and_an_older_one_cannot_take_the_row_back(tmp_path) -> None:
    store = MemoryStore(tmp_path / "memory.db")
    item = store.add("住在上海")
    store.mark_accepted([(store.pending_ops()[0]["op_id"], 1)])

    remote = _remote("remote-1", 2, item.id, "住在北京")
    assert store.apply_remote(remote) is True
    assert store.get(item.id).content == "住在北京"
    assert store.apply_remote(remote) is False

    assert store.apply_remote(_remote("remote-0", 0, item.id, "住在广州")) is False
    assert store.get(item.id).content == "住在北京"
    store.close()


def test_an_edit_not_yet_pushed_is_not_overwritten_by_a_remote_change(tmp_path) -> None:
    store = MemoryStore(tmp_path / "memory.db")
    item = store.add("住在上海")
    store.mark_accepted([(store.pending_ops()[0]["op_id"], 1)])
    store.update(item.id, "住在深圳")

    assert store.apply_remote(_remote("remote-2", 3, item.id, "住在杭州")) is False
    assert store.get(item.id).content == "住在深圳"
    store.close()


def test_a_remote_delete_takes_the_memory_away(tmp_path) -> None:
    store = MemoryStore(tmp_path / "memory.db")
    item = store.add("临时脚本放 ~/tmp")
    store.mark_accepted([(store.pending_ops()[0]["op_id"], 1)])

    assert store.apply_remote(_remote("remote-del", 2, item.id, "临时脚本放 ~/tmp", deleted=True))
    assert store.get(item.id) is None
    store.close()


def test_clearing_the_store_queues_a_delete_for_every_memory(tmp_path) -> None:
    store = MemoryStore(tmp_path / "memory.db")
    store.add("第一条")
    store.add("第二条")
    store.mark_accepted([(op["op_id"], 1) for op in store.pending_ops()])
    assert store.clear() == 2

    ops = store.pending_ops()
    assert len(ops) == 2
    assert all(op["op_type"] == "delete_memory" for op in ops)
    assert all(op["payload"]["deleted"] for op in ops)
    store.close()


async def test_the_sync_pushes_what_is_queued_and_takes_what_the_account_has(tmp_path) -> None:
    store = MemoryStore(tmp_path / "memory.db")
    relay = FakeRelay()
    sync = MemorySync(store, relay, "desk")

    local = store.add("主平板是 OnePlus Pad 2 Pro")
    pushed, applied = await sync.run()
    assert (pushed, applied) == (1, 0)
    assert store.pending_ops() == []
    assert len(relay.ops) == 1

    await relay.push_memory(
        "phone",
        [
            {
                "op_id": "phone-1",
                "op_type": "upsert_memory",
                "client_time": 1,
                "payload": {
                    "id": "mem_phone",
                    "content": "喜欢莫兰迪配色",
                    "before_content": "",
                    "category": "general",
                    "source": "agent",
                    "is_pinned": False,
                    "is_archived": False,
                    "importance": 0.5,
                    "updated_at": 1_700_000_000_000,
                    "deleted": False,
                },
            }
        ],
    )
    pushed, applied = await sync.run()
    assert (pushed, applied) == (0, 1)
    assert store.get("mem_phone") is not None
    assert store.get(local.id) is not None
    assert store.cursor() == relay.seq

    # the echo of our own op is not applied a second time
    pushed, applied = await sync.run()
    assert (pushed, applied) == (0, 0)
    store.close()
