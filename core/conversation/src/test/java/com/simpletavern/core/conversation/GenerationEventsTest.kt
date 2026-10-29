package com.simpletavern.core.conversation

import com.simpletavern.core.model.StreamEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GenerationEventsTest {
    @Test fun lateSubscriberReceivesEarlierDeltasAndDone() = runBlocking {
        val events = generationEventFlow()
        events.emit(StreamEvent.Delta("first"))
        events.emit(StreamEvent.Done(ok = true))
        val replay = events.take(2).toList()
        assertEquals(StreamEvent.Delta("first"), replay.first())
        assertTrue(replay.last() is StreamEvent.Done)
    }

    @Test fun replayIsBoundedAndTerminalEventSurvivesLongGenerations() = runBlocking {
        val events = generationEventFlow()
        repeat(1000) { events.emit(StreamEvent.Delta("$it")) }
        events.emit(StreamEvent.Done(ok = true))
        assertEquals(256, events.replayCache.size)
        assertTrue(events.replayCache.last() is StreamEvent.Done)
        events.resetReplayCache()
        events.emit(StreamEvent.Delta("new generation"))
        assertEquals(StreamEvent.Delta("new generation"), events.first())
    }
}
