// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.experience

/** Coalesces cumulative checkpoints during disk trouble, with a hard memory bound. */
internal class ListeningWriteBuffer(private val capacity: Int = 512) {
    private val pending = linkedMapOf<String, ListeningRecord>()
    private var writing: String? = null
    var omitted = 0
        private set

    @Synchronized
    fun offer(record: ListeningRecord): Boolean {
        if (record.id !in pending && pending.size >= capacity) {
            val expendable =
                pending.values.firstOrNull {
                    it.id != writing && (it.kind == "action" || it.reason == "checkpoint")
                }
            if (expendable != null) pending.remove(expendable.id)
            else {
                omitted++
                return false
            }
            omitted++
        }
        pending[record.id] = record
        return true
    }

    @Synchronized
    fun next(): ListeningRecord? = pending.values.firstOrNull()?.also { writing = it.id }

    @Synchronized
    fun saved(record: ListeningRecord) {
        if (pending[record.id] === record) pending.remove(record.id)
        writing = null
    }

    @Synchronized fun takeOmitted(): Int = omitted.also { omitted = 0 }

    @Synchronized fun size() = pending.size
}
