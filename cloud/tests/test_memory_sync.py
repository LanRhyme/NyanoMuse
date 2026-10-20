import pytest
import sqlite3
import time
from nanomuse_cloud.db import Database
from nanomuse_cloud.sync import SyncStore

@pytest.fixture
def sync_store(tmp_path):
    db_file = tmp_path / "test.db"
    db = Database(str(db_file))
    store = SyncStore(db)
    # Enable sync for test account
    with db.tx() as c:
        c.execute("INSERT INTO accounts (id, id_hash, channel, hint, created_at, sync_enabled) VALUES ('acc_test', 'hash_test', 'phone', '138****0000', 1000, 1)")
    return store

def test_memory_sync_push_and_pull(sync_store):
    ops = [
        {
            "op_id": "op_1",
            "op_type": "upsert_memory",
            "payload": {
                "id": "mem_1",
                "content": "偏好莫兰迪低饱和度色系",
                "category": "preference",
                "is_pinned": True,
            },
            "client_time": int(time.time()),
        },
        {
            "op_id": "op_2",
            "op_type": "upsert_memory",
            "payload": {
                "id": "mem_2",
                "content": "临时文件统一放 ~/tmp",
                "category": "habit",
                "is_pinned": False,
            },
            "client_time": int(time.time()),
        },
    ]

    res = sync_store.push_memory_ops("acc_test", "desktop", ops)
    assert res["accepted"] == 2
    assert len(res["assigned"]) == 2
    assert res["cursor"] == 2

    # Idempotency: re-pushing op_1 should not re-insert or advance sequence
    res2 = sync_store.push_memory_ops("acc_test", "desktop", [ops[0]])
    assert res2["accepted"] == 0
    assert res2["assigned"][0]["seq"] == 1
    assert res2["cursor"] == 2

    # Pull since 0
    pulled = sync_store.pull_memory_ops("acc_test", since=0)
    assert len(pulled["ops"]) == 2
    assert pulled["ops"][0]["payload"]["content"] == "偏好莫兰迪低饱和度色系"
    assert pulled["ops"][0]["payload"]["is_pinned"] is True
    assert pulled["ops"][1]["payload"]["content"] == "临时文件统一放 ~/tmp"

    # Pull since 1 (incremental)
    pulled_inc = sync_store.pull_memory_ops("acc_test", since=1)
    assert len(pulled_inc["ops"]) == 1
    assert pulled_inc["ops"][0]["payload"]["id"] == "mem_2"

    # Wipe
    sync_store.wipe("acc_test")
    pulled_after_wipe = sync_store.pull_memory_ops("acc_test", since=0)
    assert len(pulled_after_wipe["ops"]) == 0
