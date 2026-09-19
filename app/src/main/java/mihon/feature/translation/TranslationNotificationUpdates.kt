package mihon.feature.translation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

/** Keeps the last progress update pending; important state changes bypass the progress interval. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T : Any> Flow<T>.notificationUpdates(
    intervalMillis: Long = 1_000,
    clock: () -> Long = { System.nanoTime() / 1_000_000 },
    stateKey: (T) -> Any?,
): Flow<T> = flow {
    require(intervalMillis > 0)
    coroutineScope {
        val values = produce(capacity = Channel.CONFLATED) {
            this@notificationUpdates.collect { send(it) }
        }
        var published = false
        var lastValue: T? = null
        var lastKey: Any? = null
        var lastTime = 0L
        var pending: T? = null
        var completed = false
        try {
            while (true) {
                if (pending == null) {
                    val next = values.receiveCatching()
                    if (next.isClosed) {
                        next.exceptionOrNull()?.let { throw it }
                        break
                    }
                    pending = next.getOrThrow()
                }
                val value = checkNotNull(pending)
                if (published && value == lastValue) {
                    pending = null
                    continue
                }
                val key = stateKey(value)
                val remaining = if (published && key == lastKey) {
                    (intervalMillis - (clock() - lastTime).coerceAtLeast(0)).coerceIn(0, intervalMillis)
                } else {
                    0
                }
                if (remaining > 0) {
                    if (completed) {
                        delay(remaining)
                    } else {
                        select {
                            values.onReceiveCatching { next ->
                                if (next.isClosed) {
                                    next.exceptionOrNull()?.let { throw it }
                                    completed = true
                                } else {
                                    pending = next.getOrThrow()
                                }
                            }
                            onTimeout(remaining) { }
                        }
                    }
                    continue
                }
                // Emit calls the downstream publisher sequentially. Its completion sets the rate clock;
                // new upstream snapshots neither cancel that effect nor start a competing publication.
                emit(value)
                lastValue = value
                lastKey = key
                lastTime = clock()
                published = true
                pending = null
            }
        } finally {
            values.cancel()
        }
    }
}
