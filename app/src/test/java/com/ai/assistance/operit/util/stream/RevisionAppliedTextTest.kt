package com.ai.assistance.operit.util.stream

import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class RevisionAppliedTextTest {

    private var previousSystemLogEnabled = true

    @Before
    fun disableAndroidSystemLogForJvmTests() {
        previousSystemLogEnabled = AppLogger.enableSystemLog
        AppLogger.enableSystemLog = false
    }

    @After
    fun restoreAndroidSystemLog() {
        AppLogger.enableSystemLog = previousSystemLogEnabled
    }

    @Test
    fun plainStreamWithoutEventsIsConcatenated() = runBlocking {
        val chunks = listOf("alpha", "beta", "gamma")

        val collected = chunks.asStream().collectRevisionAppliedText()

        assertEquals("alphabetagamma", collected)
    }

    @Test
    fun rollbackRemovesTheAbortedAttemptFromTheCollectedText() {
        val chunks = listOf("<tool_L6Gi", "<tool_AB12 name=\"device_info\">", "\n</tool_AB12>")

        val collected =
            applyRevisionEvents(
                chunks.joinToString(separator = ""),
                listOf(
                    TextStreamEvent(TextStreamEventType.SAVEPOINT, "attempt", contentLength = 0),
                    TextStreamEvent(
                        TextStreamEventType.ROLLBACK,
                        "attempt",
                        contentLength = 0,
                        discardedLength = "<tool_L6Gi".length
                    )
                )
            )

        assertEquals("<tool_AB12 name=\"device_info\">\n</tool_AB12>", collected)
    }

    @Test
    fun rollbackKeepsTextProducedBeforeTheSavepoint() {
        val collected =
            applyRevisionEvents(
                "kept prefix<tool_L6Gi",
                listOf(
                    TextStreamEvent(TextStreamEventType.SAVEPOINT, "attempt", contentLength = 11),
                    TextStreamEvent(
                        TextStreamEventType.ROLLBACK,
                        "attempt",
                        contentLength = 11,
                        discardedLength = "<tool_L6Gi".length
                    )
                )
            )

        assertEquals("kept prefix", collected)
    }

    @Test
    fun collectForwardsEveryChunk() = runBlocking {
        val events = MutableSharedStream<TextStreamEvent>(replay = Int.MAX_VALUE)
        val source =
            stream {
                events.emit(TextStreamEvent(TextStreamEventType.SAVEPOINT, "attempt", 0))
                emit("<tool_L6Gi")
                events.emit(
                    TextStreamEvent(
                        TextStreamEventType.ROLLBACK,
                        "attempt",
                        contentLength = 0,
                        discardedLength = "<tool_L6Gi".length
                    )
                )
                emit("final")
            }.withEventChannel(events)
        val forwarded = mutableListOf<String>()

        val collected = source.collectRevisionAppliedText { chunk -> forwarded += chunk }

        assertEquals("final", collected)
        assertEquals(listOf("<tool_L6Gi", "final"), forwarded)
    }
}
