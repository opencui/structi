package io.opencui.kvstore

import io.opencui.core.IExtension

/**
 * This bootstrap the storage.
 */
interface IKVStore : IExtension {
    // This should mimic the redis as much as possible.
    fun set(key: String, value: String): Boolean
    fun get(key: String): String?

    // Value is list.
    fun rpush(key: String, value: String)
    fun lrange(key: String, start: Int, end: Int): List<String>

    fun lrem(key: String, value: String): Int
}