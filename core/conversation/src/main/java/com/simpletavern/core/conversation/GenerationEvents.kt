package com.simpletavern.core.conversation

import com.simpletavern.core.model.StreamEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow

/** Bounded late-subscriber notifications. Full message bodies remain in chat storage. */
internal fun generationEventFlow() = MutableSharedFlow<StreamEvent>(
    replay = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
