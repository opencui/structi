package io.opencui.sessionmanager

import io.opencui.core.BotInfo

data class InMemoryBotStore(override val botInfo: BotInfo): IBotStore {
    private val cache = HashMap<String, String>()
    private val listCache = HashMap<String, MutableList<String>>()
    override fun set(key: String, version: String): Boolean {
        cache["agent:${botInfo.fullName}:${botInfo.lang}:${botInfo.branch}|$key"] = version
        return true
    }

    override fun get(key: String): String? {
        return cache["agent:${botInfo.fullName}:${botInfo.lang}:${botInfo.branch}|$key"]
    }

    override fun rpush(key: String, value: String) {
        if (!listCache.containsKey(key)) {
            listCache.put(key, mutableListOf())
        }
        listCache[key]!!.add(value)
    }

    override fun lrange(key: String, start: Int, end: Int): List<String> {
        return if (!listCache.containsKey(key)) emptyList() else listCache[key]!!
    }

    override fun lrem(key: String, value: String) : Int {
        return if (listCache[key]?.remove(value) == true) 1 else 0
    }

    override fun toString(): String {
        return "InMemoryBotStore(cache=${cache})"
    }
}