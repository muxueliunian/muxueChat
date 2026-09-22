package com.muxuechat.data.repository

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingMessageAccumulatorTest {

    @Test
    fun defaultCadenceIsImmediateThen32MillisForUiAnd250MillisForRoom() {
        var now = 0L
        val accumulator = StreamingMessageAccumulator(nowMillis = { now })

        val first = requireNotNull(accumulator.append("A", null))
        assertTrue(first.flushUi)
        assertFalse(first.flushRoom)

        now = 31L
        assertNull(accumulator.append("B", null))
        now = 32L
        val ui = requireNotNull(accumulator.append("C", null))
        assertTrue(ui.flushUi)
        assertFalse(ui.flushRoom)

        now = 249L
        val uiBeforeRoom = requireNotNull(accumulator.append("D", null))
        assertTrue(uiBeforeRoom.flushUi)
        assertFalse(uiBeforeRoom.flushRoom)
        now = 250L
        val room = requireNotNull(accumulator.append("E", null))
        assertTrue(room.flushUi)
        assertTrue(room.flushRoom)
    }

    @Test
    fun keepsMoreThanOneThousandDeltasByteExactAndThrottlesRoomWrites() {
        var now = 10_000L
        val accumulator = StreamingMessageAccumulator(
            nowMillis = { now },
        )
        val contentDeltas = (0 until 1_537).map { index -> "正文[$index]🙂\n" }
        val reasoningDeltas = (0 until 1_537).map { index -> "推理[$index]🧠|" }
        var uiDecisionCount = 0
        val simulatedDaoWrites = mutableListOf<StreamingMessageSnapshot>()

        contentDeltas.zip(reasoningDeltas).forEach { (content, reasoning) ->
            val decision = accumulator.append(content, reasoning)
            if (decision?.flushUi == true) uiDecisionCount++
            if (decision?.flushRoom == true) simulatedDaoWrites += decision.snapshot
            now++
        }

        val terminal = accumulator.forceSnapshot(StreamingTerminalReason.COMPLETED)
        if (terminal.flushUi) uiDecisionCount++
        if (terminal.flushRoom) simulatedDaoWrites += terminal.snapshot

        assertArrayEquals(
            contentDeltas.joinToString(separator = "").toByteArray(StandardCharsets.UTF_8),
            terminal.snapshot.content.toByteArray(StandardCharsets.UTF_8),
        )
        assertArrayEquals(
            reasoningDeltas.joinToString(separator = "").toByteArray(StandardCharsets.UTF_8),
            terminal.snapshot.reasoningContent.orEmpty().toByteArray(StandardCharsets.UTF_8),
        )
        assertEquals(contentDeltas.size.toLong(), terminal.snapshot.revision)
        assertEquals(terminal.snapshot, simulatedDaoWrites.last())
        assertTrue("UI 决策应远少于 delta", uiDecisionCount < contentDeltas.size / 8)
        assertTrue("Room/DAO 写入决策应远少于 delta", simulatedDaoWrites.size < contentDeltas.size / 50)
    }

    @Test
    fun completedCancelledAndFailedAlwaysForceBothConsumers() {
        StreamingTerminalReason.entries.forEach { reason ->
            val now = 500L
            val accumulator = StreamingMessageAccumulator(
                nowMillis = { now },
                uiFlushIntervalMillis = 20L,
                roomFlushIntervalMillis = 200L,
            )
            val first = accumulator.append(contentDelta = "只到一小段", reasoningDelta = "尚未落库")
            assertEquals(true, first?.flushUi)
            assertEquals(false, first?.flushRoom)

            // 时钟没有前进，普通节流不会写 Room；终态仍必须强制写入并通知 UI。
            assertNull(accumulator.flushIfDue())
            val terminal = accumulator.forceSnapshot(reason)

            assertTrue(terminal.flushUi)
            assertTrue(terminal.flushRoom)
            assertEquals(reason, terminal.terminalReason)
            assertEquals("只到一小段", terminal.snapshot.content)
            assertEquals("尚未落库", terminal.snapshot.reasoningContent)
            assertEquals(now, terminal.snapshot.observedAtMillis)
        }
    }

    @Test
    fun stationaryAndBackwardClockNeverCauseSpuriousFlushOrTimestampRegression() {
        var now = 1_000L
        val accumulator = StreamingMessageAccumulator(
            nowMillis = { now },
            uiFlushIntervalMillis = 10L,
            roomFlushIntervalMillis = 100L,
        )
        val first = requireNotNull(accumulator.append("A", null))
        assertTrue(first.flushUi)
        assertFalse(first.flushRoom)
        assertEquals(1_000L, first.snapshot.observedAtMillis)

        repeat(64) {
            assertNull(accumulator.append("x", "r"))
        }
        now = 900L
        assertNull(accumulator.append("B", "R"))
        now = 1_009L
        assertNull(accumulator.append("C", null))

        now = 1_010L
        val uiFlush = requireNotNull(accumulator.append("D", null))
        assertTrue(uiFlush.flushUi)
        assertFalse(uiFlush.flushRoom)
        assertEquals(1_010L, uiFlush.snapshot.observedAtMillis)

        now = 850L
        val terminal = accumulator.forceSnapshot(StreamingTerminalReason.CANCELLED)
        assertEquals(1_010L, terminal.snapshot.observedAtMillis)
        assertTrue(terminal.snapshot.observedAtMillis >= uiFlush.snapshot.observedAtMillis)
        assertTrue(terminal.flushRoom)
    }

    @Test
    fun roomCanFlushPendingSnapshotWhenNoNewDeltaArrives() {
        var now = 0L
        val accumulator = StreamingMessageAccumulator(
            nowMillis = { now },
            uiFlushIntervalMillis = 10L,
            roomFlushIntervalMillis = 100L,
        )
        val initialUi = requireNotNull(accumulator.append("pending", "thinking"))
        assertTrue(initialUi.flushUi)
        assertFalse(initialUi.flushRoom)

        now = 99L
        assertNull(accumulator.flushIfDue())
        now = 100L
        val roomFlush = requireNotNull(accumulator.flushIfDue())

        assertFalse(roomFlush.flushUi)
        assertTrue(roomFlush.flushRoom)
        assertEquals("pending", roomFlush.snapshot.content)
        assertEquals("thinking", roomFlush.snapshot.reasoningContent)
    }

    @Test
    fun keepsAppendingAcrossToolRoundsAndExposesPerRoundText() {
        var now = 0L
        val accumulator = StreamingMessageAccumulator(
            nowMillis = { now },
            uiFlushIntervalMillis = 10L,
            roomFlushIntervalMillis = 100L,
        )

        val firstRound = accumulator.markRoundStart()
        accumulator.append("先检查设置。", "第一轮思考")
        accumulator.append("准备调用工具。", null)
        val firstRoundFlush = accumulator.forceSnapshot()
        assertTrue(firstRoundFlush.flushUi)
        assertTrue(firstRoundFlush.flushRoom)
        assertNull(firstRoundFlush.terminalReason)
        assertEquals(
            StreamingRoundSnapshot(
                content = "先检查设置。准备调用工具。",
                reasoningContent = "第一轮思考",
            ),
            accumulator.snapshotSince(firstRound),
        )

        val secondRound = accumulator.markRoundStart()
        now = 25L
        accumulator.append("设置已更新，", "第二轮思考：")
        accumulator.append("继续回答。", "完成。")
        accumulator.forceSnapshot()
        assertEquals(
            StreamingRoundSnapshot(
                content = "设置已更新，继续回答。",
                reasoningContent = "第二轮思考：完成。",
            ),
            accumulator.snapshotSince(secondRound),
        )

        val terminal = accumulator.forceSnapshot(StreamingTerminalReason.COMPLETED)
        assertEquals("先检查设置。准备调用工具。设置已更新，继续回答。", terminal.snapshot.content)
        assertEquals("第一轮思考第二轮思考：完成。", terminal.snapshot.reasoningContent)
    }

    @Test
    fun noticeIsKeptByLaterTerminalSnapshot() {
        val now = 0L
        val accumulator = StreamingMessageAccumulator(nowMillis = { now })
        accumulator.append("正文末尾   \n", null)
        accumulator.forceSnapshot()

        accumulator.appendNotice("（已达到工具调用次数上限。）")
        val terminal = accumulator.forceSnapshot(StreamingTerminalReason.COMPLETED)

        assertEquals("正文末尾\n\n（已达到工具调用次数上限。）", terminal.snapshot.content)
    }

    @Test
    fun emptyDeltasDoNotAdvanceRevisionOrCreatePeriodicWork() {
        var now = 0L
        val accumulator = StreamingMessageAccumulator(
            nowMillis = { now },
            uiFlushIntervalMillis = 10L,
            roomFlushIntervalMillis = 100L,
        )

        assertNull(accumulator.append(null, null))
        assertNull(accumulator.append("", ""))
        now = 1_000L
        assertNull(accumulator.flushIfDue())

        val terminal = accumulator.forceSnapshot(StreamingTerminalReason.FAILED)
        assertEquals(0L, terminal.snapshot.revision)
        assertEquals("", terminal.snapshot.content)
        assertNull(terminal.snapshot.reasoningContent)
    }
}
