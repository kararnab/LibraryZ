package com.libraryz.data

/**
 * A hybrid logical clock for library changes: wall time that never goes
 * backwards, never falls behind a stamp it has seen ([observe]), and a
 * counter for several changes in one millisecond. Stamps are
 * "<unix ms, 13 digits>.<counter, 4 digits>.<device>", so string order is
 * clock order and the device breaks ties, the format the server compares.
 */
class Hlc(val device: String, private val wall: () -> Long) {
    private var ms = 0L
    private var counter = 0

    fun next(): String {
        val now = wall()
        if (now > ms) {
            ms = now
            counter = 0
        } else if (++counter > MAX_COUNTER) {
            ms++
            counter = 0
        }
        return format(ms, counter, device)
    }

    /** Moves past [stamp] (from the server or another device), so the next change sorts after it. */
    fun observe(stamp: String?) {
        val (sMs, sCounter) = parse(stamp) ?: return
        if (sMs > ms || (sMs == ms && sCounter > counter)) {
            ms = sMs
            counter = sCounter
        }
    }

    companion object {
        private const val MAX_COUNTER = 9999

        fun format(ms: Long, counter: Int, device: String): String =
            ms.toString().padStart(13, '0') + "." + counter.toString().padStart(4, '0') + "." + device

        fun parse(stamp: String?): Pair<Long, Int>? {
            val parts = stamp?.split('.') ?: return null
            if (parts.size != 3) return null
            return (parts[0].toLongOrNull() ?: return null) to (parts[1].toIntOrNull() ?: return null)
        }

        /** The device part of [stamp]. */
        fun deviceOf(stamp: String?): String? = stamp?.split('.')?.getOrNull(2)

        /** A new device id: 12 lowercase letters and digits. */
        fun newDevice(random: kotlin.random.Random = kotlin.random.Random.Default): String =
            buildString { repeat(12) { append("abcdefghijklmnopqrstuvwxyz0123456789"[random.nextInt(36)]) } }
    }
}
