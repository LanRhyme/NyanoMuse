"""The hub through the ASGI app: two devices of one account find each other and
pass calls; a second account sees nothing of it."""

from __future__ import annotations

import asyncio
import hashlib
import json
import time

import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from nanomuse_cloud.api import create_app
from nanomuse_cloud.config import Settings
from nanomuse_cloud.db import Database
from nanomuse_cloud.hub import SEND_QUEUE_FRAMES, STAND_DOWN, Connection
from nanomuse_cloud.senders import LogSender
from nanomuse_cloud.service import Cloud


@pytest.fixture
def client():
    settings = Settings(database=":memory:", secret="test-secret", public_base="http://cloud.test", hub_frame_limit=4096)
    sender = LogSender()
    cloud = Cloud(settings, Database(":memory:"), sender)
    app = create_app(settings, cloud)
    with TestClient(app) as c:
        c.sender = sender
        yield c


def sign_up(client: TestClient, identifier: str) -> str:
    assert client.post("/v1/auth/code", json={"identifier": identifier}).status_code == 204
    _, code = client.sender.sent[-1]
    r = client.post("/v1/auth/verify", json={"identifier": identifier, "code": code, "device": "test"})
    assert r.status_code == 200, r.text
    return r.json()["api_key"]


def hello(kind: str, device_id: str, name: str, key: str | None = None, actions=("info", "shell")) -> dict:
    frame = {
        "type": "hello",
        "device": {"id": device_id, "name": name, "kind": kind, "os": "TestOS", "version": "0.1", "actions": list(actions)},
    }
    if key:
        frame["key"] = key
    return frame


def connect(client: TestClient, key: str | None, **kw):
    headers = {"Authorization": f"Bearer {key}"} if key else {}
    return client.websocket_connect("/v1/hub", headers=headers, **kw)


def test_two_devices_route_calls_and_results(client):
    key = sign_up(client, "13800138000")
    with connect(client, key) as phone, connect(client, key) as pc:
        phone.send_json(hello("phone", "phone-1", "Pixel"))
        w1 = phone.receive_json()
        assert w1["type"] == "welcome" and w1["device_id"] == "phone-1"
        assert [d["id"] for d in w1["devices"]] == ["phone-1"]
        phone.receive_json()  # the devices broadcast triggered by its own arrival

        pc.send_json(hello("computer", "pc-1", "desk", actions=("info", "shell", "screen")))
        w2 = pc.receive_json()
        assert w2["type"] == "welcome"
        assert {d["id"]: d["online"] for d in w2["devices"]} == {"phone-1": True, "pc-1": True}
        pc.receive_json()  # broadcast
        upd = phone.receive_json()
        assert upd["type"] == "devices" and {d["id"] for d in upd["devices"]} == {"phone-1", "pc-1"}
        pc_row = next(d for d in upd["devices"] if d["id"] == "pc-1")
        assert pc_row["actions"] == ["info", "shell", "screen"] and pc_row["controllable"] is True

        # phone → pc: a call, progress, then the result; each lands on the right side.
        phone.send_json({"type": "call", "id": "c1", "to": "pc-1", "action": "shell", "args": {"command": "uname"}})
        call = pc.receive_json()
        assert call == {
            "type": "call",
            "id": "c1",
            "from": {"id": "phone-1", "name": "Pixel", "kind": "phone"},
            "action": "shell",
            "args": {"command": "uname"},
        }
        pc.send_json({"type": "event", "id": "c1", "body": {"stage": "running"}})
        ev = phone.receive_json()
        assert ev["type"] == "event" and ev["body"] == {"stage": "running"} and ev["from"]["id"] == "pc-1"
        pc.send_json({"type": "result", "id": "c1", "ok": True, "body": {"stdout": "Linux\n", "exit_code": 0}})
        res = phone.receive_json()
        assert res["type"] == "result" and res["ok"] is True and res["body"]["stdout"] == "Linux\n"

        # A result for a call that is not pending is refused, once, to its sender.
        pc.send_json({"type": "result", "id": "c1", "ok": True, "body": {}})
        assert pc.receive_json()["code"] == "unknown_call"

        # pc → phone works the same way.
        pc.send_json({"type": "call", "id": "c2", "to": "phone-1", "action": "notify", "args": {"text": "hi"}})
        assert phone.receive_json()["action"] == "notify"
        phone.send_json({"type": "result", "id": "c2", "ok": False, "error": "denied", "message": "no"})
        r2 = pc.receive_json()
        assert r2["ok"] is False and r2["error"] == "denied"

        # Calling yourself, or a device that is not there.
        pc.send_json({"type": "call", "id": "c3", "to": "pc-1", "action": "info", "args": {}})
        assert pc.receive_json()["code"] == "self_call"
        pc.send_json({"type": "call", "id": "c4", "to": "nope", "action": "info", "args": {}})
        assert pc.receive_json()["code"] == "device_offline"

        pc.send_json({"type": "ping"})
        assert pc.receive_json()["type"] == "pong"

    # Both gone: remembered, offline, listable over HTTP.
    r = client.get("/v1/devices", headers={"Authorization": f"Bearer {key}"})
    assert r.status_code == 200
    rows = {d["id"]: d for d in r.json()["devices"]}
    assert set(rows) == {"phone-1", "pc-1"} and not rows["pc-1"]["online"]
    assert client.delete("/v1/devices/pc-1", headers={"Authorization": f"Bearer {key}"}).status_code == 204
    assert [d["id"] for d in client.get("/v1/devices", headers={"Authorization": f"Bearer {key}"}).json()["devices"]] == ["phone-1"]


def test_target_leaving_fails_the_pending_call(client):
    key = sign_up(client, "someone@example.com")
    with connect(client, key) as phone, connect(client, key) as pc:
        phone.send_json(hello("phone", "phone-1", "Pixel"))
        phone.receive_json()
        phone.receive_json()
        pc.send_json(hello("computer", "pc-1", "desk"))
        pc.receive_json()
        pc.receive_json()
        phone.receive_json()  # devices: pc joined
        phone.send_json({"type": "call", "id": "c1", "to": "pc-1", "action": "shell", "args": {"command": "sleep 100"}})
        assert pc.receive_json()["action"] == "shell"
        # pc drops without answering (an explicit close: the test session's own
        # exit cancels the server task before it can tell anyone).
        pc.close(1000)
        seen = [phone.receive_json(), phone.receive_json()]
        kinds = {f["type"]: f for f in seen}
        assert kinds["error"]["code"] == "device_offline" and kinds["error"]["id"] == "c1"
        assert {d["id"]: d["online"] for d in kinds["devices"]["devices"]} == {"phone-1": True, "pc-1": False}


def test_a_lone_pending_call_times_out_on_the_callers_ping(client):
    """The sweep ran only when another `call` arrived, so a caller with one call outstanding
    and nothing more to ask never heard `timeout`; every client pings, so it sweeps too."""
    from nanomuse_cloud import hub as hub_mod

    key = sign_up(client, "someone@example.com")
    with connect(client, key) as phone, connect(client, key) as pc:
        phone.send_json(hello("phone", "phone-1", "Pixel"))
        phone.receive_json()
        phone.receive_json()
        pc.send_json(hello("computer", "pc-1", "desk"))
        pc.receive_json()
        pc.receive_json()
        phone.receive_json()  # devices: pc joined
        phone.send_json({"type": "call", "id": "c1", "to": "pc-1", "action": "shell", "args": {"command": "sleep"}})
        assert pc.receive_json()["action"] == "shell"
        hub = client.app.state.hub
        assert ("hub", "c1") not in hub.pending and len(hub.pending) == 1
        # a ping while the call is young: a pong and nothing else
        phone.send_json({"type": "ping"})
        assert phone.receive_json()["type"] == "pong"
        # the call ages past the limit; the next ping delivers the timeout
        for p in hub.pending.values():
            p.started -= hub_mod.CALL_TTL_S + 1
        phone.send_json({"type": "ping"})
        assert phone.receive_json()["type"] == "pong"
        err = phone.receive_json()
        assert err == {"type": "error", "id": "c1", "code": "timeout", "message": "the device did not answer in time"}
        assert not hub.pending
        # a late result from the target is an unknown call now
        pc.send_json({"type": "result", "id": "c1", "ok": True, "body": {}})
        assert pc.receive_json()["code"] == "unknown_call"


def test_web_tab_authenticates_in_hello_and_cannot_be_called(client):
    key = sign_up(client, "13800138000")
    with connect(client, None) as web, connect(client, key) as pc:
        web.send_json(hello("web", "", "Chrome", key=key))
        w = web.receive_json()
        assert w["type"] == "welcome" and w["device_id"].startswith("web-")
        web.receive_json()
        pc.send_json(hello("computer", "pc-1", "desk"))
        pc.receive_json()
        pc.receive_json()
        upd = web.receive_json()
        tab = next(d for d in upd["devices"] if d["kind"] == "web")
        assert tab["controllable"] is False

        pc.send_json({"type": "call", "id": "x", "to": w["device_id"], "action": "info", "args": {}})
        assert pc.receive_json()["code"] == "not_controllable"

        web.send_json({"type": "call", "id": "t1", "to": "pc-1", "action": "task", "args": {"text": "open the calendar"}})
        assert pc.receive_json()["args"] == {"text": "open the calendar"}
        pc.send_json({"type": "result", "id": "t1", "ok": True, "body": {"text": "done"}})
        assert web.receive_json()["body"] == {"text": "done"}

    # Browser tabs are not remembered.
    assert [d["kind"] for d in client.get("/v1/devices", headers={"Authorization": f"Bearer {key}"}).json()["devices"]] == ["computer"]


def test_bad_key_and_bad_hello(client):
    with connect(client, None) as ws:
        ws.send_json(hello("phone", "p", "x", key="nm_nothing"))
        assert ws.receive_json()["code"] == "bad_key"
    key = sign_up(client, "13800138000")
    with connect(client, key) as ws:
        ws.send_json(hello("toaster", "t-1", "x"))
        assert ws.receive_json()["code"] == "bad_device"
    with connect(client, key) as ws:
        ws.send_json({"type": "call"})
        with pytest.raises(WebSocketDisconnect):
            ws.receive_json()  # closed: hello expected


def test_a_bad_bearer_header_is_refused_with_4001_after_the_handshake(client):
    """The apps send the key in the header. A refusal must reach them as a close with
    4001 and the code (docs/hub.md), not as a failed handshake: a failed handshake looks
    like the network and the phone would retry it forever."""
    with connect(client, "nm_nothing") as ws:
        err = ws.receive_json()
        assert err["type"] == "error" and err["code"] == "bad_key"
        with pytest.raises(WebSocketDisconnect) as closed:
            ws.receive_json()
        assert closed.value.code == 4001 and closed.value.reason == "bad_key"


def test_binary_and_unknown_frames_are_answered_not_fatal(client):
    key = sign_up(client, "13800138000")
    # before hello a binary frame is as wrong as any other non-hello: closed, 4000
    with connect(client, key) as ws:
        ws.send_bytes(b"\x00\x01")
        with pytest.raises(WebSocketDisconnect) as closed:
            ws.receive_json()
        assert closed.value.code == 4000
    # after hello the socket stays open and says what was wrong
    with connect(client, key) as ws:
        ws.send_json(hello("phone", "phone-1", "Pixel"))
        assert ws.receive_json()["type"] == "welcome"
        ws.receive_json()  # devices broadcast
        ws.send_bytes(b"\x00\x01")
        err = ws.receive_json()
        assert err["code"] == "bad_frame" and "binary" in err["message"]
        ws.send_json({"type": "teleport"})
        err = ws.receive_json()
        assert err["code"] == "bad_frame" and "teleport" in err["message"]
        ws.send_text("[1, 2]")
        assert ws.receive_json()["code"] == "bad_frame"
        ws.send_json({"type": "ping"})
        assert ws.receive_json()["type"] == "pong"  # still alive


class _StalledSocket:
    """A peer that reads only when told to: send_text waits on the gate."""

    def __init__(self):
        self.sent: list[str] = []
        self.closed: tuple[int, str] | None = None
        self.gate = asyncio.Event()

    async def send_text(self, text: str) -> None:
        await self.gate.wait()
        self.sent.append(text)

    async def close(self, code: int, reason: str = "") -> None:
        self.closed = (code, reason)


def _conn(ws) -> Connection:
    return Connection(ws=ws, account_id="a", key_hash="k", device_id="pc-1", name="desk", kind="computer")


async def test_a_send_never_waits_on_the_peer_and_order_is_kept():
    """A slow phone used to hold the computer calling it: send awaited the phone's socket
    inside the caller's receive loop. Now a send queues and returns; the writer drains in
    order once the peer reads."""
    ws = _StalledSocket()
    conn = _conn(ws)
    conn.start()
    try:
        t0 = time.monotonic()
        for i in range(5):
            await conn.send({"type": "event", "i": i})
        assert time.monotonic() - t0 < 0.5 and ws.sent == [] and not conn.closed
        ws.gate.set()
        for _ in range(100):
            if len(ws.sent) == 5:
                break
            await asyncio.sleep(0.01)
        assert [json.loads(t)["i"] for t in ws.sent] == [0, 1, 2, 3, 4]
        assert conn.queued_bytes == 0 and not conn.queue
    finally:
        conn.stop()


async def test_a_second_socket_of_one_device_takes_over_gently(monkeypatch):
    """A phone that moves from Wi-Fi to cellular opens a second socket under the same id.
    The old one is not kicked: it hears `standby` and is closed with 4004 once that has
    gone out, while the new one is the account's device from hello on. A caller in
    between hears `device_busy`, not `device_offline`."""
    import time

    import nanomuse_cloud.hub as hub_mod

    monkeypatch.setattr(hub_mod, "STANDBY_S", 0.2)
    hub = _hub()
    old = _link("phone-1", "Pixel")
    await hub._attach(old)
    await old.send({"type": "welcome"})
    await _settled(old)

    new = _link("phone-1", "Pixel")
    await hub._attach(new)
    # the notice jumps the queue, and the old socket is still up to receive it
    assert not old.closed and old.standby
    assert json.loads(old.queue[0])["type"] == "standby"
    await _settled(old)

    # the id is the new socket's from hello on, and the new socket is not told to stand down
    assert hub.online["a"]["phone-1"] is new and not new.standby
    # a caller in the same second hears `device_busy`, not `device_offline`
    caller = _link("pc-1", "desk", kind="computer")
    hub.online["a"]["pc-1"] = caller
    new.standby_since = time.monotonic()  # the instant of the handover
    await hub._call(caller, {"type": "call", "id": "c0", "to": "phone-1", "action": "info"})
    await _settled(caller)
    err = json.loads(caller.ws.sent[-1])
    assert err["code"] == "device_busy" and err["id"] == "c0"
    new.standby_since = None

    # after STANDBY_S the old socket is closed with 4004 and it left nothing behind
    deadline = time.monotonic() + 2
    while not old.ws.closed and time.monotonic() < deadline:
        await asyncio.sleep(0.02)
    assert old.ws.closed == (STAND_DOWN, "standby")
    assert hub.online["a"]["phone-1"] is new
    # and a call reaches the new socket now
    await hub._call(caller, {"type": "call", "id": "c1", "to": "phone-1", "action": "info"})
    await _settled(new)
    assert json.loads(new.ws.sent[-1])["id"] == "c1"


class _OpenSocket(_StalledSocket):
    """A socket that reads at once."""

    def __init__(self):
        super().__init__()
        self.gate.set()


async def _settled(conn):
    """Wait until the writer has handed everything queued to the socket."""
    for _ in range(200):
        await asyncio.sleep(0.01)
        if not conn.queue and not conn.writing:
            return


def _hub():
    from nanomuse_cloud.hub import Hub as _H

    hub = _H.__new__(_H)
    hub.cloud = None

    class _Devices:
        def upsert_device(self, *a, **k):
            pass

        def touch_device(self, *a, **k):
            pass

    hub.db = _Devices()
    hub.frame_limit = 4096
    hub.online = {}
    hub.pending = {}
    hub.stats = lambda: {}
    hub.dropped_total = 0
    hub.flood_closes = 0
    hub.slow_closes = 0

    async def _noop(*a, **k):
        return None

    hub.broadcast_devices = _noop
    hub._fail_pending_for = _noop
    hub.devices = lambda account_id: []
    return hub


def _link(device_id, name, kind="phone"):
    conn = Connection(ws=_OpenSocket(), account_id="a", key_hash="k", device_id=device_id, name=name, kind=kind)
    conn.start()
    return conn


async def test_a_peer_that_stops_reading_is_closed_with_4009():
    """The writer takes the first frame and blocks in send_text; the queue fills behind it."""
    ws = _StalledSocket()
    conn = _conn(ws)
    conn.start()
    for i in range(SEND_QUEUE_FRAMES + 2):
        await conn.send({"type": "event", "i": i})
    assert conn.closed and conn.overflowed
    for _ in range(100):
        if ws.closed:
            break
        await asyncio.sleep(0.01)
    assert ws.closed == (4009, "slow_consumer")
    assert conn.writer is not None and (conn.writer.cancelled() or conn.writer.done())
    # bytes count too: two frames of 12 MiB fit, the third does not
    ws2 = _StalledSocket()
    conn2 = _conn(ws2)
    conn2.start()
    big = "x" * (12 * 1024 * 1024)
    for _ in range(3):
        await conn2.send({"type": "event", "body": big})
    assert conn2.overflowed and len(conn2.queue) <= 2
    conn2.stop()


def test_accounts_are_separate_and_frames_are_capped(client):
    a = sign_up(client, "13800138000")
    b = sign_up(client, "13900139000")
    with connect(client, a) as pa, connect(client, b) as pb:
        pa.send_json(hello("phone", "phone-1", "A's"))
        pa.receive_json()
        pa.receive_json()
        pb.send_json(hello("computer", "pc-1", "B's"))
        w = pb.receive_json()
        assert [d["id"] for d in w["devices"]] == ["pc-1"]
        pb.receive_json()
        pa.send_json({"type": "call", "id": "c1", "to": "pc-1", "action": "info", "args": {}})
        assert pa.receive_json()["code"] == "device_offline"
        pa.send_text(json.dumps({"type": "ping", "pad": "x" * 5000}))
        assert pa.receive_json()["code"] == "too_large"


def test_reconnect_replaces_the_older_socket(client):
    key = sign_up(client, "13800138000")
    with connect(client, key) as first:
        first.send_json(hello("computer", "pc-1", "desk"))
        first.receive_json()
        first.receive_json()
        with connect(client, key) as second:
            second.send_json(hello("computer", "pc-1", "desk"))
            w = second.receive_json()
            assert [d["id"] for d in w["devices"]] == ["pc-1"]
            second.send_json({"type": "devices"})
            frames = [second.receive_json(), second.receive_json()]
            assert all(f["type"] == "devices" for f in frames)
            assert all(len(f["devices"]) == 1 for f in frames)


def test_a_sign_out_closes_the_hub_socket_of_the_key_it_revoked(client):
    """Sign out everywhere is what to do after losing a phone. The lost phone's key stops
    opening at once; its hub socket, open before the sign-out, must go too (4001 bad_key,
    docs/hub.md), or the phone keeps a live line to the other devices until it next
    reconnects. The device that asked keeps its socket; a session revoked by prefix from
    the account page and a device's own sign-out close the same way."""
    phone_key = sign_up(client, "13800138000")
    pc_key = sign_up(client, "13800138000")
    with connect(client, phone_key) as phone, connect(client, pc_key) as pc:
        phone.send_json(hello("phone", "phone-1", "Pixel"))
        phone.receive_json()
        phone.receive_json()
        pc.send_json(hello("computer", "pc-1", "desk"))
        pc.receive_json()
        pc.receive_json()
        phone.receive_json()  # the pc arrived
        # every other device: the phone's key is revoked, its socket closed; the pc stays
        r = client.post("/v1/auth/sign-out-all", headers={"Authorization": f"Bearer {pc_key}"}, json={})
        assert r.status_code == 200 and r.json()["signed_out"] == 1
        with pytest.raises(WebSocketDisconnect) as closed:
            phone.receive_json()
        assert closed.value.code == 4001 and closed.value.reason == "bad_key"
        pc.send_json({"type": "devices"})
        assert pc.receive_json()["type"] == "devices"  # the pc's line is still open
        r = client.get("/v1/devices", headers={"Authorization": f"Bearer {pc_key}"})
        assert {d["id"]: d["online"] for d in r.json()["devices"]} == {"phone-1": False, "pc-1": True}
    # a session revoked by prefix from the account page
    key_a = sign_up(client, "dev-a@example.com")
    key_b = sign_up(client, "dev-a@example.com")
    with connect(client, key_b) as other:
        other.send_json(hello("computer", "pc-2", "laptop"))
        other.receive_json()
        other.receive_json()
        sessions = client.get("/v1/me/sessions", headers={"Authorization": f"Bearer {key_a}"}).json()["sessions"]
        prefix = next(s["prefix"] for s in sessions if not s["current"])
        assert client.delete(f"/v1/me/sessions/{prefix}", headers={"Authorization": f"Bearer {key_a}"}).status_code == 204
        with pytest.raises(WebSocketDisconnect) as closed:
            other.receive_json()
        assert closed.value.code == 4001
    # a device's own sign-out
    with connect(client, key_a) as own:
        own.send_json(hello("computer", "pc-3", "desk"))
        own.receive_json()
        own.receive_json()
        assert client.post("/v1/auth/sign-out", headers={"Authorization": f"Bearer {key_a}"}).status_code == 204
        with pytest.raises(WebSocketDisconnect) as closed:
            own.receive_json()
        assert closed.value.code == 4001


def test_the_profile_is_shared_and_announced(client):
    """The agent's name and look live on the relay so every device of the account wears
    the same one: a PUT from one device is a ``profile`` frame on the other's socket, and
    the pictures are fetched only when asked for."""
    import base64

    key = sign_up(client, "13800138000")
    auth = {"Authorization": f"Bearer {key}"}
    assert client.get("/v1/me/profile", headers=auth).json()["rev"] == 0

    webp = base64.b64encode(b"RIFF\x00\x00\x00\x00WEBPVP8 " + b"\x00" * 40).decode()
    with connect(client, key) as phone, connect(client, key) as pc:
        phone.send_json(hello("phone", "phone-1", "Pixel"))
        phone.receive_json(), phone.receive_json()
        pc.send_json(hello("computer", "pc-1", "desk"))
        pc.receive_json(), pc.receive_json()
        phone.receive_json()  # the devices broadcast for the pc's arrival

        r = client.put(
            "/v1/me/profile",
            headers=auth,
            json={
                "device": "pc-1",
                "name": "小火",
                "avatar": "face",
                "style": "pixel",
                "description": "a robot owl",
                "face": {"idle": webp, "happy": webp},
            },
        )
        assert r.status_code == 200 and r.json()["rev"] == 1
        assert phone.receive_json() == {"type": "profile", "rev": 1, "device": "pc-1"}
        assert pc.receive_json() == {"type": "profile", "rev": 1, "device": "pc-1"}

    light = client.get("/v1/me/profile", params={"face": "false"}, headers=auth).json()
    assert light["rev"] == 1 and light["name"] == "小火" and light["has_face"] is True and "face" not in light
    full = client.get("/v1/me/profile", headers=auth).json()
    assert set(full["face"]) == {"idle", "happy"} and full["device"] == "pc-1"
    # the idle still's hash, so a device that already wears these pictures keeps its copy
    assert light["face_id"] == hashlib.sha1(base64.b64decode(webp)).hexdigest()[:12] == full["face_id"]

    # a rename keeps the pictures; switching to an emoji drops them
    r = client.put("/v1/me/profile", headers=auth, json={"device": "phone-1", "name": "小火龙", "avatar": "face"})
    assert r.status_code == 200 and r.json()["rev"] == 2
    assert client.get("/v1/me/profile", headers=auth).json()["face"]["idle"] == webp
    # 0.25: a write names only what changed. The name alone keeps the look (a phone that
    # renamed before it ever pulled the face no longer sends "dragon" and takes the face off
    # every device); a blank name keeps the stored one; a face without a name keeps the name.
    r = client.put("/v1/me/profile", headers=auth, json={"device": "phone-1", "name": "阿火"})
    after = client.get("/v1/me/profile", headers=auth).json()
    assert r.status_code == 200 and r.json()["rev"] == 3
    assert after["name"] == "阿火" and after["avatar"] == "face" and after["style"] == "pixel" and after["face"]["idle"] == webp
    r = client.put("/v1/me/profile", headers=auth, json={"device": "phone-1", "name": "", "description": "a robot owl, blue"})
    after = client.get("/v1/me/profile", headers=auth).json()
    assert r.status_code == 200 and after["name"] == "阿火" and after["description"] == "a robot owl, blue" and after["has_face"]
    r = client.put("/v1/me/profile", headers=auth, json={"device": "phone-1", "avatar": "face", "face": {"idle": webp}, "style": "ink"})
    after = client.get("/v1/me/profile", headers=auth).json()
    assert r.status_code == 200 and after["name"] == "阿火" and after["style"] == "ink" and set(after["face"]) == {"idle"}
    # back to the dragon without `face: null` (Android 1.0.3): the pictures go with the look,
    # so no device reads `has_face` as a face still worn while another shows the dragon
    r = client.put("/v1/me/profile", headers=auth, json={"device": "phone-1", "name": "阿火", "avatar": "dragon"})
    after = client.get("/v1/me/profile", headers=auth).json()
    assert r.status_code == 200 and after["avatar"] == "dragon" and after["has_face"] is False and after["face"] is None
    r = client.put(
        "/v1/me/profile",
        headers=auth,
        json={
            "device": "phone-1",
            "name": "阿火",
            "avatar": "face",
            "style": "pixel",
            "description": "a robot owl",
            "face": {"idle": webp},
        },
    )
    assert r.status_code == 200
    r = client.put(
        "/v1/me/profile", headers=auth, json={"name": "小火龙", "avatar": "emoji", "emoji": "🦉", "color": "#0064d4", "face": None}
    )
    after = client.get("/v1/me/profile", headers=auth).json()
    assert r.status_code == 200 and after["face"] is None and after["face_id"] == ""

    # what is refused: a face without pictures, a stray mood, a picture that is not a picture, a name too long
    bad = [
        {"name": "x", "avatar": "face"},
        {"name": "x", "avatar": "face", "face": {"idle": webp, "angry": webp}},
        {"name": "x", "avatar": "face", "face": {"idle": base64.b64encode(b"hello").decode()}},
        {"name": "x" * 61, "avatar": "dragon"},
        {"name": "x", "avatar": "emoji", "color": "blue"},
    ]
    for body in bad:
        assert client.put("/v1/me/profile", headers=auth, json=body).status_code == 400, body
    assert client.delete("/v1/me/profile", headers=auth).status_code == 204
    assert client.get("/v1/me/profile", headers=auth).json()["rev"] == 0
    # the first write of an account may be the name alone: the look is the dragon until a device says otherwise
    r = client.put("/v1/me/profile", headers=auth, json={"device": "phone-1", "name": "阿火"})
    after = client.get("/v1/me/profile", headers=auth).json()
    assert (
        r.status_code == 200
        and after["rev"] == 1
        and after["name"] == "阿火"
        and after["avatar"] == "dragon"
        and after["has_face"] is False
    )


def test_a_flooding_socket_is_slowed_then_closed(client):
    """One socket may send so many frames and bytes a second (hub.FRAMES_PER_S, BYTES_PER_S,
    twice that in a burst); over it frames are dropped with one error a second, and a socket
    that keeps flooding is closed. The bucket itself, with a clock of our own."""
    from nanomuse_cloud import hub as hub_module
    from nanomuse_cloud.hub import FLOOD_CLOSE_AFTER, Bucket

    clock = [0.0]
    bucket = Bucket(10.0, 20.0, clock=lambda: clock[0])
    assert all(bucket.take() for _ in range(20)) and not bucket.take()  # the burst, then no more
    clock[0] = 0.5
    assert all(bucket.take() for _ in range(5)) and not bucket.take()  # half a second: five back
    clock[0] = 100
    assert sum(1 for _ in range(50) if bucket.take()) == 20  # never more than the burst

    a = sign_up(client, "13800138000")
    with connect(client, a) as pa:
        pa.send_json(hello("phone", "phone-1", "A's"))
        pa.receive_json()
        pa.receive_json()
        # a flood: twice the burst of pings in no time — the burst answered (a few more, for
        # the tokens that trickle in meanwhile), then one rate_limited error, not one per
        # dropped frame — and the hub counts the drops
        burst = int(2 * hub_module.FRAMES_PER_S)
        for _ in range(burst + 50):
            pa.send_json({"type": "ping"})
        got = []
        while True:
            r = pa.receive_json()
            got.append(r)
            if r["type"] == "error":
                break
        pongs = sum(1 for r in got if r["type"] == "pong")
        assert burst <= pongs <= burst + 10 and got[-1]["code"] == "rate_limited"
        hub = client.app.state.hub
        assert hub.dropped_total >= 1 and hub.stats()["dropped_frames"] == hub.dropped_total
        # kept up, the socket goes
        with pytest.raises(WebSocketDisconnect) as closed:
            for _ in range(FLOOD_CLOSE_AFTER + 200):
                pa.send_json({"type": "ping"})
            while True:
                pa.receive_json()
        assert closed.value.code == 4008
    assert hub.flood_closes == 1


def test_connectors_are_merged_by_device_and_never_hold_a_secret(client):
    """0.17 (contract C3): each device publishes which services it connected — a label and
    the sign-in kind, never the credential. The relay keeps one list per account, replaces
    only the writing device's entries, caps it at 64, refuses anything named like a secret,
    and tells the other devices on the hub as it does for the look."""
    key = sign_up(client, "13800138000")
    auth = {"Authorization": f"Bearer {key}"}
    assert client.get("/v1/me/profile", headers=auth).json()["connectors"] == []

    # the desk connects two services before the account has a look at all
    desk = [
        {"id": "github", "label": "GitHub", "url": "https://api.github.com", "auth": "oauth", "at": "2026-10-01T08:00:00Z"},
        {"id": "weather", "label": "Weather", "auth": "open", "enabled": False},
    ]
    with connect(client, key) as phone:
        phone.send_json(hello("phone", "phone-1", "Pixel"))
        phone.receive_json(), phone.receive_json()
        r = client.put("/v1/me/profile", headers=auth, json={"device": "desk-1", "connectors": desk})
        assert r.status_code == 200 and r.json() == {"rev": 1, "device": "desk-1"}
        assert phone.receive_json() == {"type": "profile", "rev": 1, "device": "desk-1"}
    got = client.get("/v1/me/profile", headers=auth).json()
    assert got["rev"] == 1 and got["name"] == "" and got["avatar"] == ""  # the look is still unset
    assert got["connectors"] == [
        {
            "id": "github",
            "label": "GitHub",
            "url": "https://api.github.com",
            "device": "desk-1",
            "device_id": "desk-1",
            "at": "2026-10-01T08:00:00Z",
            "auth": "oauth",
            "enabled": True,
        },
        {
            "id": "weather",
            "label": "Weather",
            "url": "",
            "device": "desk-1",
            "device_id": "desk-1",
            "at": got["connectors"][1]["at"],
            "auth": "open",
            "enabled": False,
        },
    ]
    assert got["connectors"][1]["at"].endswith("Z")  # stamped by the relay when the device did not say
    # ``?face=false`` carries them too — a device that reads the light profile sees the connectors
    light = client.get("/v1/me/profile", params={"face": "false"}, headers=auth).json()
    assert [c["id"] for c in light["connectors"]] == ["github", "weather"] and "face" not in light

    # the phone adds its own; the desk's stay. Then the desk drops one: only its own list changes.
    phone_conn = [{"id": "github", "label": "GitHub (phone)", "auth": "oauth", "device": "My Pixel"}]
    r = client.put("/v1/me/profile", headers=auth, json={"device": "phone-1", "connectors": phone_conn})
    assert r.status_code == 200 and r.json()["rev"] == 2
    ids = [(c["device_id"], c["id"]) for c in client.get("/v1/me/profile", headers=auth).json()["connectors"]]
    assert ids == [("desk-1", "github"), ("desk-1", "weather"), ("phone-1", "github")]
    r = client.put("/v1/me/profile", headers=auth, json={"device": "desk-1", "connectors": [desk[0]]})
    assert r.status_code == 200 and r.json()["rev"] == 3
    after = client.get("/v1/me/profile", headers=auth).json()["connectors"]
    assert [(c["device_id"], c["id"], c["device"]) for c in after] == [("phone-1", "github", "My Pixel"), ("desk-1", "github", "desk-1")]
    # an empty list from the phone clears the phone's; the desk's stand
    r = client.put("/v1/me/profile", headers=auth, json={"device": "phone-1", "connectors": []})
    assert r.status_code == 200
    assert [c["device_id"] for c in client.get("/v1/me/profile", headers=auth).json()["connectors"]] == ["desk-1"]

    # the look and the connectors in one write; a look-only write leaves the connectors alone
    r = client.put(
        "/v1/me/profile",
        headers=auth,
        json={"device": "desk-1", "name": "小火", "avatar": "dragon", "connectors": [desk[1]]},
    )
    assert r.status_code == 200
    got = client.get("/v1/me/profile", headers=auth).json()
    assert got["name"] == "小火" and [c["id"] for c in got["connectors"]] == ["weather"]
    r = client.put("/v1/me/profile", headers=auth, json={"device": "phone-1", "name": "小火龙", "avatar": "dragon"})
    assert r.status_code == 200
    got = client.get("/v1/me/profile", headers=auth).json()
    assert got["name"] == "小火龙" and [c["id"] for c in got["connectors"]] == ["weather"]

    # never a credential: a key named like one, at any depth, is a 400 and nothing is stored
    for leak in (
        [{"id": "x", "auth": "key", "api_key": "sk-abc"}],
        [{"id": "x", "auth": "oauth", "token": "t"}],
        [{"id": "x", "auth": "oauth", "Authorization": "Bearer t"}],
        [{"id": "x", "auth": "open", "extra": {"password": "p"}}],
        [{"id": "x", "auth": "open", "extra": [{"client_secret": "s"}]}],
    ):
        r = client.put("/v1/me/profile", headers=auth, json={"device": "desk-1", "connectors": leak})
        assert r.status_code == 400 and r.json()["error"]["code"] == "no_secrets_in_profile", leak
        assert "sk-abc" not in r.text and "Bearer t" not in r.text
    assert [c["id"] for c in client.get("/v1/me/profile", headers=auth).json()["connectors"]] == ["weather"]

    # the shape is checked too
    for bad in (
        [{"label": "no id", "auth": "open"}],
        [{"id": "x", "auth": "magic"}],
        [{"id": "x" * 65, "auth": "open"}],
        [{"id": "x", "auth": "open", "url": "not a url"}],
        [{"id": "x", "auth": "open", "at": "yesterday"}],
        [{"id": "x", "auth": "open", "enabled": "yes"}],
        [{"id": "x", "auth": "open", "device_id": "someone-else"}],
        ["github"],
        "github",
    ):
        r = client.put("/v1/me/profile", headers=auth, json={"device": "desk-1", "connectors": bad})
        assert r.status_code == 400 and r.json()["error"]["code"] == "bad_request", bad
    r = client.put("/v1/me/profile", headers=auth, json={"connectors": [desk[0]]})  # no device: whose would these be?
    assert r.status_code == 400

    # at most 64 on the account, after the merge
    many = [{"id": f"svc-{i}", "auth": "open"} for i in range(63)]
    assert client.put("/v1/me/profile", headers=auth, json={"device": "phone-1", "connectors": many}).status_code == 200
    r = client.put("/v1/me/profile", headers=auth, json={"device": "desk-1", "connectors": [desk[0], desk[1]]})
    assert r.status_code == 400 and r.json()["error"]["code"] == "too_many_connectors"
    assert client.put("/v1/me/profile", headers=auth, json={"device": "desk-1", "connectors": [desk[0]]}).status_code == 200
    assert len(client.get("/v1/me/profile", headers=auth).json()["connectors"]) == 64

    # DELETE clears the connectors with the look
    assert client.delete("/v1/me/profile", headers=auth).status_code == 204
    assert client.get("/v1/me/profile", headers=auth).json() == {
        "rev": 0,
        "updated_at": None,
        "device": "",
        "name": "",
        "avatar": "",
        "emoji": "",
        "color": "",
        "style": "",
        "description": "",
        "face": None,
        "connectors": [],
    }
