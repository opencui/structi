package io.opencui.core

import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import kotlin.reflect.KFunction

interface Cache<Key, Value> {
    val size: Int
    operator fun set(key: Key, value: Value)
    operator fun get(key: Key): Value?
    fun remove(key: Key): Value?
    fun clear()
}

class PerpetualCache<Key, Value> : Cache<Key, Value> {
    private val cache = HashMap<Key, Value>()

    override val size: Int
        get() = cache.size

    val keys: Set<Key>
        get() = cache.keys

    override fun set(key: Key, value: Value) {
        cache[key] = value
    }

    override fun remove(key: Key) = cache.remove(key)

    override fun get(key: Key) = cache[key]

    override fun clear() = cache.clear()
    override fun toString(): String {
        return "PerpetualCache(cache=${cache.toList().joinToString { "${it.first}:${it.second}" }})"
    }
}

class LRUCache<Key, Value>(
    private val delegate: Cache<Key, Value>,
    private val minimalSize: Int = DEFAULT_SIZE
) : Cache<Key, Value> {

    private val keyMap = object : LinkedHashMap<Key, Boolean>(
            minimalSize, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Boolean>): Boolean {
            val tooManyCachedItems = size > minimalSize
             eldestKeyToRemove = if (tooManyCachedItems) eldest.key else null
            return tooManyCachedItems
        }
    }

    private var eldestKeyToRemove: Key? = null

    override val size: Int
        get() = delegate.size

    override fun set(key: Key, value: Value) {
        keyMap[key] = PRESENT
        delegate[key] = value
        cycleKeyMap(key)
    }

    override fun remove(key: Key): Value? {
        keyMap.remove(key)
        return delegate.remove(key)
    }

    override fun get(key: Key): Value? {
        keyMap[key]
        return delegate[key]
    }

    override fun clear() {
        keyMap.clear()
        delegate.clear()
    }

    private fun cycleKeyMap(key: Key) {
        eldestKeyToRemove?.let {
            (delegate.remove(it) as? Recyclable)?.recycle()
        }
        eldestKeyToRemove = null
    }

    companion object {
        private const val DEFAULT_SIZE = 100
        private const val PRESENT = true
    }
}

class ExpirableCache<Key, Value>(
    private val delegate: Cache<Key, Pair<Value, Long>>,
    private val flushInterval: Long = TimeUnit.MINUTES.toNanos(1)
) : Cache<Key, Value> {

    override val size: Int
        get() = delegate.size

    override fun set(key: Key, value: Value) {
        delegate[key] = Pair(value, System.nanoTime())
    }

    override fun remove(key: Key): Value? {
        recycle(key)
        return delegate.remove(key)?.first
    }

    override fun get(key: Key): Value? {
        recycle(key)
        val value = delegate[key]
        return if (value == null) {
            null
        } else {
            set(key, value.first)
            value.first
        }
    }

    override fun clear() = delegate.clear()

    private fun recycle(key: Key) {
        val valuePair = delegate[key] ?: return
        val shouldRecycle = System.nanoTime() - valuePair.second >= flushInterval
        if (!shouldRecycle) return
        delegate.remove(key)?.first?.apply { if (this is Recyclable) this.recycle() }
    }
}

interface Recyclable {
    fun recycle()
}

/**
 * Add support for the common function cache.
 */
class CachedMethod1<A, out R>(val f: (A) -> List<R>, val expireTimeInSeconds: Int = 60) : (A) -> List<R> {
    val values: MutableMap<A, Pair<List<@UnsafeVariance R>, LocalDateTime>> = mutableMapOf()

    override fun invoke(a: A): List<R> {
        val input = a
        val cache = values[input]
        Dispatcher.logger.debug("Enter cached function... for $a")
        if (cache == null || Duration.between(cache!!.second, LocalDateTime.now()).seconds > expireTimeInSeconds) {
            Dispatcher.logger.debug("for some reason we need to refresh: $cache and ${LocalDateTime.now()}")
            values[input] = Pair(f(a), LocalDateTime.now())
        }
        return values[input]!!.first
    }

    fun invalidate(a: A) {
        values.remove(a)
    }
}


class CachedMethod2<A, B, out R>(
    val f: (A, B) -> List<R>, val expireTimeInSeconds: Int = 60) : (A, B) -> List<R> {
    val values: MutableMap<Pair<A, B>, Pair<List<@UnsafeVariance R>, LocalDateTime>> = mutableMapOf()
    override fun invoke(a: A, b: B): List<R> {
        val input = Pair(a, b)
        val cache = values[input]
        Dispatcher.logger.debug("Enter cached function... for $a, $b")
        if (cache == null || Duration.between(cache!!.second, LocalDateTime.now()).seconds > expireTimeInSeconds) {
            Dispatcher.logger.debug("for some reason we need to refresh: $cache and ${LocalDateTime.now()}")
            values[input] = Pair(f(a, b), LocalDateTime.now())
        }
        return values[input]!!.first
    }

    fun invalidate(a: A, b: B) {
        val input = Pair(a, b)
        values.remove(input)
    }
}


data class CachedMethod3<A, B, C, out R>(
    val f: (A, B, C) -> List<R>, val expireTimeInSeconds: Int = 60) : (A, B, C) -> List<R> {
    val values: MutableMap<Triple<A, B, C>, Pair<List<@UnsafeVariance R>, LocalDateTime>> = mutableMapOf()
    override fun invoke(a: A, b: B, c: C): List<R> {
        val input = Triple(a, b, c)
        val cache = values[input]
        Dispatcher.logger.debug("Enter cached function... for $a, $b, $c")
        if (cache == null || Duration.between(cache!!.second, LocalDateTime.now()).seconds > expireTimeInSeconds) {
            Dispatcher.logger.debug("for some reason we need to refresh: $cache and ${LocalDateTime.now()}")
            values[input] = Pair(f(a, b, c), LocalDateTime.now())
        }
        return values[input]!!.first
    }

    fun invalidate(a: A, b: B, c: C) {
        val input = Triple(a, b, c)
        values.remove(input)
    }
}


data class CachedFunction<T>(
    val values: MutableMap<String, Pair<List<@UnsafeVariance T>, LocalDateTime>> = mutableMapOf()) {

    operator fun invoke(func: KFunction<List<T>>, vararg otherParams: Any) : List<T> {
        // Create an array with fixedParam1 followed by otherParams
        val signature = """${func.name}(${otherParams.joinToString(", ")})"""
        if (!values.containsKey(signature)) {
            values[signature] = Pair(func.call(*otherParams), LocalDateTime.now())
        }
        // Use reflection to call the target function with allParams
        return values[signature]!!.first
    }
}