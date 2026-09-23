package com.layer.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class UidPackageCacheTest {
    @Test
    fun remembersPackagesForTheSameUid() {
        var calls = 0
        val cache = UidPackageCache()
        val lookup: (Int) -> Array<String>? = {
            calls += 1
            arrayOf("org.telegram.messenger", "org.telegram.messenger.web")
        }
        assertEquals(
            listOf("org.telegram.messenger", "org.telegram.messenger.web"),
            cache.packages(10123, lookup),
        )
        assertEquals(
            listOf("org.telegram.messenger", "org.telegram.messenger.web"),
            cache.packages(10123, lookup),
        )
        assertEquals(1, calls)
    }

    @Test
    fun cachesEmptyResults() {
        var calls = 0
        val cache = UidPackageCache()
        assertEquals(emptyList<String>(), cache.packages(1000) {
            calls += 1
            null
        })
        assertEquals(emptyList<String>(), cache.packages(1000) {
            calls += 1
            arrayOf("should-not-run")
        })
        assertEquals(1, calls)
    }

    @Test
    fun keepsUidsIndependentAndReloadsAfterClear() {
        var telegramCalls = 0
        var gmsCalls = 0
        val cache = UidPackageCache()
        cache.packages(10123) {
            telegramCalls += 1
            arrayOf("org.telegram.messenger")
        }
        cache.packages(10113) {
            gmsCalls += 1
            arrayOf("com.google.android.gms")
        }
        cache.clear()
        cache.packages(10123) {
            telegramCalls += 1
            arrayOf("org.telegram.messenger")
        }
        assertEquals(2, telegramCalls)
        assertEquals(1, gmsCalls)
    }

    @Test
    fun lookupStartedBeforeClearRetriesAndDoesNotReturnStale() {
        val cache = UidPackageCache()
        val lookupStarted = CountDownLatch(1)
        val allowFinish = CountDownLatch(1)
        val staleDone = CountDownLatch(1)
        val firstThreadResult = AtomicReference<List<String>>()
        val firstLookups = AtomicInteger(0)
        Thread {
            firstThreadResult.set(
                cache.packages(42) {
                    if (firstLookups.getAndIncrement() == 0) {
                        lookupStarted.countDown()
                        check(allowFinish.await(2, TimeUnit.SECONDS))
                        arrayOf("stale")
                    } else {
                        arrayOf("fresh")
                    }
                },
            )
            staleDone.countDown()
        }.start()
        assertTrue(lookupStarted.await(2, TimeUnit.SECONDS))
        cache.clear()
        assertEquals(listOf("fresh"), cache.packages(42) { arrayOf("fresh") })
        allowFinish.countDown()
        assertTrue(staleDone.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("fresh"), firstThreadResult.get())
        var postClearLookups = 0
        assertEquals(
            listOf("fresh"),
            cache.packages(42) {
                postClearLookups += 1
                arrayOf("fresh")
            },
        )
        assertEquals(0, postClearLookups)
    }

    @Test
    fun cachedFastPathDoesNotReturnStaleWhileClearRunsConcurrently() {
        val cache = UidPackageCache()
        val replaced = AtomicBoolean(false)
        val clearsDone = AtomicBoolean(false)
        val lookup: (Int) -> Array<String>? = {
            if (replaced.get()) arrayOf("new") else arrayOf("old")
        }
        cache.packages(7, lookup)
        val readersReady = CountDownLatch(READERS)
        val start = CountDownLatch(1)
        val staleHits = AtomicInteger(0)
        val threads = (1..READERS).map {
            Thread {
                readersReady.countDown()
                check(start.await(5, TimeUnit.SECONDS))
                var sawNew = false
                repeat(20_000) {
                    val got = cache.packages(7, lookup)
                    if (got == listOf("new")) {
                        sawNew = true
                    } else if (sawNew || clearsDone.get()) {
                        staleHits.incrementAndGet()
                    }
                }
            }.also { it.start() }
        }
        assertTrue(readersReady.await(5, TimeUnit.SECONDS))
        start.countDown()
        replaced.set(true)
        repeat(200) { cache.clear() }
        clearsDone.set(true)
        threads.forEach { it.join(10_000) }
        threads.forEach { assertFalse(it.isAlive) }
        assertEquals(0, staleHits.get())
        assertEquals(listOf("new"), cache.packages(7, lookup))
    }

    private companion object {
        const val READERS = 8
    }
}
