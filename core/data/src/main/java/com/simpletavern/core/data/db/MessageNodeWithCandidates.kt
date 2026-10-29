package com.simpletavern.core.data.db

import androidx.room.Embedded
import androidx.room.Relation

/** Room observes both tables and reads the node/selection/candidates in a single transaction. */
data class MessageNodeWithCandidates(
    @Embedded val node: MessageNodeEntity,
    @Relation(parentColumn = "id", entityColumn = "nodeId") val candidates: List<MessageCandidateEntity>,
)
