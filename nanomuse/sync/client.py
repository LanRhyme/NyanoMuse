"""The relay's conversation-sync API (``/v1/sync/*``, relay 0.19, contract C7), as the
runtime calls it. Thin: every method is one request with the account's key; errors come
back as :class:`nanomuse.cloud.CloudError` (``sync_off`` when the person turned it off on
another device, ``bad_key`` when the sign-in ended)."""

from __future__ import annotations

from typing import Any

from nanomuse.cloud import CloudClient

DEFAULT_PAGE = 500


class SyncClient:
    def __init__(self, cloud: CloudClient):
        self.cloud = cloud

    async def state(self) -> dict[str, Any]:
        """``{enabled, cursor, counts{conversations, messages}, limits{messages, text_bytes}}``."""
        return await self.cloud._request("GET", "/v1/sync/state")

    async def set_enabled(self, enabled: bool) -> dict[str, Any]:
        """Off deletes everything the relay stores for the account; on starts empty."""
        return await self.cloud._request("PUT", "/v1/sync/state", {"enabled": bool(enabled)})

    async def changes(
        self, since: int = 0, limit: int = DEFAULT_PAGE, scope: str = "all", tail: int = 0
    ) -> dict[str, Any]:
        """Everything after ``since`` in seq order: ``{cursor, more, conversations[], messages[]}``.
        ``scope="main"`` is the main conversation only; ``tail=K`` with ``since=0`` asks for
        the newest K messages and their conversations (plus ``skipped``) — relay 0.20, C9."""
        query = f"since={int(since)}&limit={int(limit)}"
        if scope != "all":
            query += f"&scope={scope}"
        if tail:
            query += f"&tail={int(tail)}"
        return await self.cloud._request("GET", f"/v1/sync/changes?{query}")

    async def working(self, cid: str, working: bool, device: str) -> None:
        """Presence (C9): a turn started (``True``) or ended (``False``) on that conversation
        here. The relay tells the account's other devices; 204, nothing to read."""
        await self.cloud._request(
            "POST", "/v1/sync/working", {"cid": cid, "working": bool(working), "device": device}
        )

    async def push(
        self, device: str, conversations: list[dict[str, Any]], messages: list[dict[str, Any]]
    ) -> dict[str, Any]:
        """``{cursor, accepted, rejected[{cid|mid, reason, cid_main?}]}``; at most 200 messages."""
        return await self.cloud._request(
            "POST",
            "/v1/sync/changes",
            {"device": device, "conversations": conversations, "messages": messages},
        )

    async def delete_conversation(self, cid: str) -> dict[str, Any]:
        """Tombstone one chat for every device; the relay drops its texts at once."""
        return await self.cloud._request("DELETE", f"/v1/sync/conversations/{cid}")

    async def wipe(self) -> dict[str, Any]:
        """Empty the account's store; the switch stays where it is."""
        return await self.cloud._request("DELETE", "/v1/sync/changes")

    async def push_memory(self, device: str, ops: list[dict[str, Any]]) -> dict[str, Any]:
        """``{cursor, accepted, assigned[{op_id, seq}]}``: the queued memory changes go up
        and the relay gives each an account-wide seq (C15)."""
        return await self.cloud._request(
            "POST", "/v1/sync/memory/push", {"device": device, "ops": ops}
        )

    async def pull_memory(self, since: int = 0, limit: int = 200) -> dict[str, Any]:
        """``{cursor, more, ops[]}``: the account's memory changes after ``since`` (C15)."""
        return await self.cloud._request(
            "GET", f"/v1/sync/memory/pull?since={int(since)}&limit={int(limit)}"
        )
