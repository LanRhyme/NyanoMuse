package io.github.nanomuse.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** C15's wire shapes: what goes up in a push and what a pull hands back. */
class MemorySyncJsonTest {

    @Test
    fun pushBodyCarriesTheDeviceAndTheQueuedOps() {
        val body = SyncJson.memoryPushBody(
            "OPD2413",
            listOf(
                OutMemoryOp(
                    opId = "op-1",
                    opType = "upsert_memory",
                    clientTime = 1740000000000L,
                    payload = JSONObject().put("id", "mem_1").put("content", "喜欢莫兰迪色系"),
                ),
            ),
        )
        assertEquals("OPD2413", body.getString("device"))
        val op = body.getJSONArray("ops").getJSONObject(0)
        assertEquals("op-1", op.getString("op_id"))
        assertEquals("upsert_memory", op.getString("op_type"))
        assertEquals(1740000000000L, op.getLong("client_time"))
        assertEquals("mem_1", op.getJSONObject("payload").getString("id"))
    }

    @Test
    fun pushResultKeepsTheAssignedSeqsAndDropsEmptyOnes() {
        val res = SyncJson.memoryPushResult(
            JSONObject()
                .put("cursor", 12)
                .put("accepted", 1)
                .put(
                    "assigned",
                    org.json.JSONArray()
                        .put(JSONObject().put("op_id", "op-1").put("seq", 11))
                        .put(JSONObject().put("op_id", "").put("seq", 12)),
                ),
        )
        assertEquals(12L, res.cursor)
        assertEquals(1, res.accepted)
        assertEquals(1, res.assigned.size)
        assertEquals("op-1", res.assigned.first().opId)
        assertEquals(11L, res.assigned.first().seq)
    }

    @Test
    fun pullResultParsesTheOpsAndTheCursor() {
        val res = SyncJson.memoryPullResult(
            JSONObject()
                .put("cursor", 9)
                .put("more", true)
                .put(
                    "ops",
                    org.json.JSONArray().put(
                        JSONObject()
                            .put("op_id", "remote-1")
                            .put("seq", 8)
                            .put("op_type", "upsert_memory")
                            .put("client_time", 1740000000000L)
                            .put("payload", JSONObject().put("id", "mem_2").put("content", "住在北京")),
                    ),
                ),
        )
        assertEquals(9L, res.cursor)
        assertTrue(res.more)
        assertEquals(1, res.ops.size)
        val op = res.ops.first()
        assertEquals("remote-1", op.opId)
        assertEquals(8L, op.seq)
        assertEquals("mem_2", op.payload.getString("id"))
    }

    @Test
    fun anEmptyPullIsNotAnError() {
        val res = SyncJson.memoryPullResult(JSONObject())
        assertFalse(res.more)
        assertEquals(0, res.ops.size)
        assertEquals(0L, res.cursor)
    }
}
