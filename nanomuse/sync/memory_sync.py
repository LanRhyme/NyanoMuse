"""Memory changes with the account (C15), the runtime's half.

Every write in ``nanomuse.memory.store`` is queued with an id of its own; this pushes the
queued ones to the relay, which gives each an account-wide ``seq``, and pulls what the other
devices wrote since this runtime's cursor. Only text and flags travel: the store keeps its
own index and rebuilds it from the text.

Push first, then pull: a local edit that goes up gets a seq of its own, so the echo that
comes back is recognised by ``op_id`` and any older remote change loses to it.
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from nanomuse.logger import logger
from nanomuse.memory.store import MemoryStore

if TYPE_CHECKING:
    from nanomuse.sync.client import SyncClient

PUSH_BATCH = 100
PULL_PAGE = 200
# a very large first pull (a fresh runtime) takes several rounds; this stops a runaway loop
MAX_ROUNDS = 50


class MemorySync:
    """The runtime's half of the account's memories."""

    def __init__(self, store: MemoryStore, client: SyncClient, device: str) -> None:
        self.store = store
        self.client = client
        self.device = device

    async def run(self) -> tuple[int, int]:
        """One pass: push what is queued here, then take what is new on the account."""
        pushed = await self._push()
        applied = await self._pull()
        if applied:
            logger.info("sync: {} memory change(s) from another device", applied)
        return pushed, applied

    async def _push(self) -> int:
        pushed = 0
        for _ in range(MAX_ROUNDS):
            ops = self.store.pending_ops(PUSH_BATCH)
            if not ops:
                break
            out = await self.client.push_memory(self.device, ops)
            assigned = [
                (str(a.get("op_id") or ""), int(a.get("seq") or 0))
                for a in (out.get("assigned") or [])
                if a.get("op_id")
            ]
            self.store.mark_accepted(assigned)
            pushed += int(out.get("accepted") or 0)
            # nothing accepted and nothing assigned: the relay knows these ids already
            if pushed == 0 and not assigned:
                break
        return pushed

    async def _pull(self) -> int:
        applied = 0
        since = self.store.cursor()
        for _ in range(MAX_ROUNDS):
            page = await self.client.pull_memory(since, PULL_PAGE)
            for op in page.get("ops") or []:
                if self.store.apply_remote(op):
                    applied += 1
            cursor = int(page.get("cursor") or since)
            if cursor > since:
                since = cursor
            self.store.set_cursor(since)
            if not page.get("more"):
                break
        return applied
