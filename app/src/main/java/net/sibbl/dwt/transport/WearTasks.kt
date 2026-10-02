package net.sibbl.dwt.transport

import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
suspend fun <T> Task<T>.awaitWearTask(onCancelledResult: (T) -> Unit = {}): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (task.isSuccessful) {
            val result = task.result
            if (continuation.isActive) continuation.resume(result) { onCancelledResult(result) }
            else onCancelledResult(result)
        } else if (continuation.isActive) {
            continuation.resumeWithException(task.exception ?: java.io.IOException("Wear API unavailable"))
        }
    }
}
