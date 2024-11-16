package io.opencui.sessionmanager

import io.opencui.core.BotInfo
import io.opencui.core.ExpirableCache
import io.opencui.core.PerpetualCache
import io.opencui.core.UserSession
import io.opencui.sessionmanager.ISessionStore.Companion.decodeSession
import io.opencui.sessionmanager.ISessionStore.Companion.encodeSession
import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream
import java.util.*
import java.util.concurrent.TimeUnit

class InMemorySessionStore: ISessionStore {
    val cache = ExpirableCache<String, String>(PerpetualCache(), TimeUnit.MINUTES.toNanos(30))
    val cacheRaw = ExpirableCache<String, UserSession>(PerpetualCache(), TimeUnit.MINUTES.toNanos(30))
    val encoded = false

    override fun getSession(channel: String, id:String, botInfo: BotInfo): UserSession? {
        val key = ISessionStore.key(channel, id, botInfo)
        if (encoded) {
            val encodedSession = cache[key] ?: return null
            val customClassLoader = ChatbotLoader.findClassLoader(botInfo)
            return decodeSession(encodedSession, customClassLoader)
        } else {
            return cacheRaw[key]
        }
    }

    override fun deleteSession(channel: String, id:String, botInfo: BotInfo) {
        val key = ISessionStore.key(channel, id, botInfo)
        if (encoded) {
            cache.remove(key)
        } else {
            cacheRaw.remove(key)
        }
    }

    override fun updateSession(channel: String, id: String, botInfo: BotInfo, session: UserSession) {
        val key = ISessionStore.key(channel, id, botInfo)
        if (encoded) {
            if (cache[key] != null) {
                cache[key] = encodeSession(session)
            }
        } else {
            if (cacheRaw[key] != null) {
                cacheRaw[key] = session
            }
        }
    }

    override fun saveSession(channel: String, id: String, botInfo: BotInfo, session: UserSession) {
        val key = ISessionStore.key(channel, id, botInfo)
        if (encoded) {
            cache[key] = encodeSession(session)
        } else {
            cacheRaw[key] = session
        }
    }
}