package com.simpletavern.core.data

import com.simpletavern.core.model.TaskSnapshot

/** Called before a new active row is persisted, including registrations inside a Room transaction. */
interface TaskRegistrationHost {
    suspend fun beforeRegister(task: TaskSnapshot)
    fun registrationFailed(taskId: String)
    fun retainHandoff(): AutoCloseable? = null
}
