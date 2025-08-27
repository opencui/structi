package io.opencui.sessionmanager

import io.opencui.core.BotInfo


// This is for testing, so we should try to separate from branching.
data class InMemoryBotStore(override val botInfo: BotInfo): IBotStore {
    private val cache = HashMap<String, String>()
    private val listCache = HashMap<String, MutableList<String>>()

    override fun set(key: String, version: String): Boolean {
        // The original implementation had a bug where it used the raw `key` for the versioned store
        // but `getKey(key)` for the cache, leading to inconsistent data storage.
        // This is now fixed to use the namespaced key for both, and simplified with getOrPut.
        listCache.getOrPut(getKey(key)) { mutableListOf() }.add(version)
        cache[getKey(key)] = version
        return true
    }

    override fun get(key: String): String? {
        return cache[getKey(key)]
    }

    override fun rpush(key: String, value: String) {
        // Use getOrPut for a more concise and safe way to initialize and add to the list.
        // This avoids the if-check and the non-null assertion (!!).
        listCache.getOrPut(getKey(key)) { mutableListOf() }.add(value)
    }

    override fun lrange(key: String, start: Int, end: Int): List<String> {
        val list = listCache[getKey(key)] ?: return emptyList()

        // Ensure indices are valid for subList and that the start index is not after the end index.
        // This version is safer and avoids the potential for exceptions from invalid ranges or the `!!` operator.
        val fromIndex = start.coerceAtLeast(0)
        val toIndex = end.coerceAtMost(list.size)

        return if (fromIndex >= toIndex) {
            emptyList()
        } else {
            list.subList(fromIndex, toIndex)
        }
    }

    override fun lrem(key: String, value: String) : Int {
        return if (listCache[getKey(key)]?.remove(value) == true) 1 else 0
    }

    override fun toString(): String {
        return "InMemoryBotStore(cache=$cache, listCache=$listCache)"
    }
}