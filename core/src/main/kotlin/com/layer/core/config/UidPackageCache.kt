package com.layer.core.config

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * `findConnectionOwner` asks PackageManager for every new TUN socket.
 * UID → packages is stable until an install/uninstall, so one IPC per uid
 * is enough for the life of the VPN session.
 *
 * Cache reads and [clear] share a lock so a generation bump cannot be
 * observed against a stale entry. A lookup that started before [clear]
 * is discarded and retried.
 */
class UidPackageCache {
    private data class Cached(val generation: Int, val packages: List<String>)

    private val lock = Any()
    private val packagesByUid = ConcurrentHashMap<Int, Cached>()
    private val generation = AtomicInteger(0)

    fun packages(uid: Int, lookup: (Int) -> Array<String>?): List<String> {
        while (true) {
            val gen: Int
            synchronized(lock) {
                gen = generation.get()
                val cached = packagesByUid[uid]
                if (cached != null && cached.generation == gen) {
                    return cached.packages
                }
            }
            val value = lookup(uid)?.toList().orEmpty()
            synchronized(lock) {
                if (generation.get() != gen) return@synchronized
                packagesByUid[uid] = Cached(gen, value)
                return value
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            generation.incrementAndGet()
            packagesByUid.clear()
        }
    }
}
