package com.muxuechat.data.repository

/**
 * 一次流式生成过程中可持久化的正文快照。
 *
 * [revision] 只在收到非空正文或 reasoning 增量时递增；[observedAtMillis] 使用本组件维护的
 * 单调时间高水位，因此即使注入的时钟回退，交给 UI/Room 的时间也不会倒退。
 */
internal data class StreamingMessageSnapshot(
    val content: String,
    val reasoningContent: String?,
    val revision: Long,
    val observedAtMillis: Long,
)

/** 正常完成、主动取消和失败都必须走同一条终态强制刷新路径。 */
internal enum class StreamingTerminalReason {
    COMPLETED,
    CANCELLED,
    FAILED,
}

/**
 * 一次快照的消费决策。
 *
 * UI 和 Room 可以使用不同节奏；当两者同时到期时只构造一份 [snapshot]。
 * [terminalReason] 非空时，[flushUi] 与 [flushRoom] 恒为 true。
 */
internal data class StreamingFlushDecision(
    val snapshot: StreamingMessageSnapshot,
    val flushUi: Boolean,
    val flushRoom: Boolean,
    val terminalReason: StreamingTerminalReason? = null,
) {
    init {
        require(flushUi || flushRoom) { "流式快照必须至少有一个消费方" }
        require(terminalReason == null || (flushUi && flushRoom)) {
            "终态快照必须同时刷新 UI 和 Room"
        }
    }
}

/** 标记某个工具调用轮次的起点，用于取得该轮独有的正文/reasoning。 */
@ConsistentCopyVisibility
internal data class StreamingRoundCursor internal constructor(
    internal val contentOffset: Int,
    internal val reasoningOffset: Int,
)

internal data class StreamingRoundSnapshot(
    val content: String,
    val reasoningContent: String?,
)

/**
 * 线性累加流式正文，并按两个固定时间窗决定何时刷新 UI 与 Room。
 *
 * 每个生成 session 应持有一个实例，并只从该 session 的 collector 协程顺序调用：
 *
 * - 正文和 reasoning 始终用 [StringBuilder] 追加，避免逐 delta 的平方级字符串复制。
 * - 第一份有效增量立即刷新 UI；之后 UI 使用较短时间窗合并。
 * - Room 从组件创建时开始按较慢时间窗写入，正常完成、取消和失败时无条件最终刷新。
 * - 时间以观察到的最大值为准；时钟停滞或回退不会触发虚假的周期刷新。
 * - 工具调用换轮时不要重建组件；通过 [markRoundStart] 取得游标即可继续累计总正文。
 *
 * 本类不负责协程同步，也不直接访问 UI 或数据库，从而可以在纯 JVM 测试中验证节流语义。
 */
internal class StreamingMessageAccumulator(
    private val nowMillis: () -> Long,
    private val uiFlushIntervalMillis: Long = DEFAULT_UI_FLUSH_INTERVAL_MILLIS,
    private val roomFlushIntervalMillis: Long = DEFAULT_ROOM_FLUSH_INTERVAL_MILLIS,
) {
    private val content = StringBuilder()
    private val reasoning = StringBuilder()

    private var revision = 0L
    private var lastUiFlushedRevision = 0L
    private var lastRoomFlushedRevision = 0L
    private var hasFlushedUi = false

    private var logicalNowMillis = nowMillis()
    private var lastUiFlushAtMillis = logicalNowMillis
    private var lastRoomFlushAtMillis = logicalNowMillis

    init {
        require(uiFlushIntervalMillis > 0L) { "UI 刷新周期必须大于 0" }
        require(roomFlushIntervalMillis >= uiFlushIntervalMillis) {
            "Room 刷新周期不能短于 UI 刷新周期"
        }
    }

    /**
     * 追加一份有序增量；只有刷新窗口到期时才把 Builder 物化为不可变 String。
     */
    fun append(
        contentDelta: String?,
        reasoningDelta: String?,
    ): StreamingFlushDecision? {
        var changed = false
        if (!contentDelta.isNullOrEmpty()) {
            content.append(contentDelta)
            changed = true
        }
        if (!reasoningDelta.isNullOrEmpty()) {
            reasoning.append(reasoningDelta)
            changed = true
        }
        if (changed) {
            revision++
        }
        return flushIfDueAt(observeNowMillis())
    }

    /**
     * 在线性累加器内追加面向用户的尾部提示，避免调用方直接修改 Message 后又被最终快照覆盖。
     */
    fun appendNotice(notice: String): StreamingFlushDecision? {
        if (notice.isEmpty()) return flushIfDueAt(observeNowMillis())

        var contentEnd = content.length
        while (contentEnd > 0 && content[contentEnd - 1].isWhitespace()) {
            contentEnd--
        }
        content.setLength(contentEnd)
        if (contentEnd > 0) {
            content.append("\n\n")
        }
        content.append(notice)
        revision++
        return flushIfDueAt(observeNowMillis())
    }

    /**
     * 没有新 delta 时也可主动轮询。例如 UI 定时器可刷新尚未到期的最后一小段内容。
     */
    fun flushIfDue(): StreamingFlushDecision? = flushIfDueAt(observeNowMillis())

    /** 工具轮结束时无条件同步当前总文本，但不把消息标记为终态。 */
    fun forceSnapshot(): StreamingFlushDecision = forceSnapshotAt(terminalReason = null)

    /**
     * 为完成、取消或失败无条件生成最终快照。
     *
     * 即使文本自上次刷新后没有变化也同时要求刷新 UI/Room，因为调用方仍需持久化终态状态。
     */
    fun forceSnapshot(reason: StreamingTerminalReason): StreamingFlushDecision =
        forceSnapshotAt(terminalReason = reason)

    private fun forceSnapshotAt(terminalReason: StreamingTerminalReason?): StreamingFlushDecision {
        val now = observeNowMillis()
        lastUiFlushedRevision = revision
        lastRoomFlushedRevision = revision
        hasFlushedUi = true
        lastUiFlushAtMillis = now
        lastRoomFlushAtMillis = now
        return StreamingFlushDecision(
            snapshot = snapshotAt(now),
            flushUi = true,
            flushRoom = true,
            terminalReason = terminalReason,
        )
    }

    /** 记录工具轮起点；后续增量仍继续写入同一对 Builder。 */
    fun markRoundStart(): StreamingRoundCursor = StreamingRoundCursor(
        contentOffset = content.length,
        reasoningOffset = reasoning.length,
    )

    /** 取得某个工具轮次以来新增的文本，不影响总快照或节流状态。 */
    fun snapshotSince(cursor: StreamingRoundCursor): StreamingRoundSnapshot {
        require(cursor.contentOffset in 0..content.length) { "正文轮次游标已失效" }
        require(cursor.reasoningOffset in 0..reasoning.length) { "reasoning 轮次游标已失效" }
        return StreamingRoundSnapshot(
            content = content.substring(cursor.contentOffset),
            reasoningContent = reasoning.substring(cursor.reasoningOffset).takeIf { it.isNotEmpty() },
        )
    }

    private fun flushIfDueAt(now: Long): StreamingFlushDecision? {
        val roomPending = revision != lastRoomFlushedRevision
        val flushRoom = roomPending && intervalElapsed(
            now = now,
            lastFlushAtMillis = lastRoomFlushAtMillis,
            intervalMillis = roomFlushIntervalMillis,
        )

        val uiPending = revision != lastUiFlushedRevision
        val flushUi = uiPending && (
            !hasFlushedUi ||
                flushRoom ||
                intervalElapsed(
                    now = now,
                    lastFlushAtMillis = lastUiFlushAtMillis,
                    intervalMillis = uiFlushIntervalMillis,
                )
            )

        if (!flushUi && !flushRoom) return null

        if (flushUi) {
            lastUiFlushedRevision = revision
            hasFlushedUi = true
            lastUiFlushAtMillis = now
        }
        if (flushRoom) {
            lastRoomFlushedRevision = revision
            lastRoomFlushAtMillis = now
        }
        return StreamingFlushDecision(
            snapshot = snapshotAt(now),
            flushUi = flushUi,
            flushRoom = flushRoom,
        )
    }

    private fun snapshotAt(now: Long): StreamingMessageSnapshot = StreamingMessageSnapshot(
        content = content.toString(),
        reasoningContent = reasoning.toString().takeIf { it.isNotEmpty() },
        revision = revision,
        observedAtMillis = now,
    )

    private fun observeNowMillis(): Long {
        val observed = nowMillis()
        if (observed > logicalNowMillis) {
            logicalNowMillis = observed
        }
        return logicalNowMillis
    }

    private fun intervalElapsed(
        now: Long,
        lastFlushAtMillis: Long,
        intervalMillis: Long,
    ): Boolean {
        check(now >= lastFlushAtMillis) { "逻辑时钟不能倒退" }
        val elapsed = now - lastFlushAtMillis
        // now >= last；若减法得到负数，只可能是跨度超过 Long.MAX_VALUE 后溢出，此时必然已到期。
        return elapsed < 0L || elapsed >= intervalMillis
    }

    companion object {
        const val DEFAULT_UI_FLUSH_INTERVAL_MILLIS = 32L
        const val DEFAULT_ROOM_FLUSH_INTERVAL_MILLIS = 250L
    }
}
