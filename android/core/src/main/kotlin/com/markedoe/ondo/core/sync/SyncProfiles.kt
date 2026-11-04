package com.markedoe.ondo.core.sync

import java.text.Normalizer
import java.util.Locale

/** What a device decides about profiles received by sync, the same on every platform (docs/SYNC.md, "Profiles"). */
object SyncProfiles {
    /**
     * Whether a profile stays a Kids profile when sync brings [received] for it: a profile made Kids always becomes one,
     * but one made not Kids elsewhere stays Kids on a device where a parent set a PIN ([parentProtected]), as its PIN
     * does. Its record (and time) are taken all the same: that device's own snapshot keeps saying Kids, at the same time,
     * so the merge settles on the other device's choice (the smaller tie) and nothing changes from one session to the
     * next.
     */
    fun keptKids(local: Boolean, received: Boolean, parentProtected: Boolean): Boolean = received || (local && parentProtected)

    /** A name as the one-time match compares it: trimmed, without accents, in lower case. */
    fun normalizedName(name: String): String =
        Normalizer.normalize(name.trim(), Normalizer.Form.NFD).replace(MARKS, "").lowercase(Locale.ROOT)

    private val MARKS = Regex("\\p{Mn}+")

    /**
     * The one-time match of profiles made apart on two devices before they synced profiles: each of [local] (this device's,
     * never synced) is one with the profile of [received] (none of which this device has) of the same normalised name and
     * the same Kids switch, the first free one by ID. The smaller ID is kept; the pairs are given as (dropped, kept). The
     * main profile is never matched: it is the same on every device.
     */
    fun matches(local: List<SyncProfile>, received: List<SyncProfile>): List<Pair<String, String>> {
        val localIds = local.mapTo(HashSet()) { it.id }
        val free = received.filter { it.id != SyncProfile.MAIN && it.id !in localIds }.sortedBy { it.id }.toMutableList()
        val receivedIds = received.mapTo(HashSet()) { it.id }
        val pairs = ArrayList<Pair<String, String>>()
        for (mine in local.filter { it.id != SyncProfile.MAIN && it.id !in receivedIds }.sortedBy { it.id }) {
            val name = normalizedName(mine.name)
            val other = free.firstOrNull { normalizedName(it.name) == name && it.kids == mine.kids } ?: continue
            free.remove(other)
            pairs += if (mine.id < other.id) other.id to mine.id else mine.id to other.id
        }
        return pairs
    }
}
