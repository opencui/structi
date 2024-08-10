package io.opencui.sessionmanager

import io.opencui.core.BotInfo


// This is for testing, so we should try to separate from branching.
data class InMemoryBotStore(override val botInfo: BotInfo): IBotStore {
    private val cache = HashMap<String, String>()
    private val listCache = HashMap<String, MutableList<String>>()

    override fun set(key: String, version: String): Boolean {
        cache[getKey(key)] = version
        return true
    }

    override fun get(key: String): String? {
        return cache[getKey(key)]
    }

    override fun rpush(key: String, value: String) {
        if (!listCache.containsKey(getKey(key))) {
            listCache.put(getKey(key), mutableListOf())
        }
        listCache[getKey(key)]!!.add(value)
    }

    override fun lrange(key: String, start: Int, end: Int): List<String> {
        return if (!listCache.containsKey(getKey(key))) emptyList() else listCache[getKey(key)]!!
    }

    override fun lrem(key: String, value: String) : Int {
        return if (listCache[getKey(key)]?.remove(value) == true) 1 else 0
    }

    override fun toString(): String {
        return "InMemoryBotStore(cache=${cache})"
    }
}