package com.simplelink.app

enum class TransferDirection {
    None,
    Sending,
    Receiving,
    Queued
}

data class TransferProgressState(
    val direction: TransferDirection = TransferDirection.None,
    val done: Long = 0,
    val total: Long = 0,
    val queuedFiles: Int = 0,
    val label: String = ""
) {
    val active: Boolean
        get() = direction == TransferDirection.Sending ||
            direction == TransferDirection.Receiving

    val fraction: Float
        get() = if (total <= 0) 0f else (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}

object TransferProgress {
    fun percent(done: Long, total: Long): Int {
        if (total <= 0) return 0
        return minOf(100, ((done * 100) / total).toInt())
    }

    fun sending(done: Long, total: Long): String =
        "${percent(done, total)}% — Sending…"

    fun receiving(done: Long, total: Long): String =
        "${percent(done, total)}% — Receiving…"

    fun queued(count: Int): String =
        if (count == 1) "1 file queued — waiting for Mac…"
        else "$count files queued — waiting for Mac…"
}
