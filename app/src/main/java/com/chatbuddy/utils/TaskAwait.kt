package com.chatbuddy.utils

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.OnCompleteListener
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { continuation ->
    val listener = OnCompleteListener<T> { task ->
        if (!continuation.isActive) return@OnCompleteListener
        if (task.isSuccessful) {
            try {
                continuation.resume(task.result)
            } catch (_: IllegalStateException) {
                // Cancellation can race the task callback; the continuation is terminal.
            }
        } else {
            try {
                continuation.resumeWithException(
                    task.exception ?: IllegalStateException("Google task failed without an exception")
                )
            } catch (_: IllegalStateException) {
                // Cancellation can race the task callback; the continuation is terminal.
            }
        }
    }
    addOnCompleteListener(listener)
}
