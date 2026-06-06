package dev.arbjerg.ukulele.api

import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@Service
class SecurityService {
    private val unauthorizedAttempts = AtomicLong(0)
    private val failedAttempts = ConcurrentHashMap<String, Int>()
    private val bannedIps = ConcurrentHashMap<String, Long>()

    // Rate limiting buckets
    private val readRateLimitCache =
        Caffeine
            .newBuilder()
            .expireAfterWrite(15, TimeUnit.MINUTES)
            .build<String, AtomicInteger>()

    private val writeRateLimitCache =
        Caffeine
            .newBuilder()
            .expireAfterWrite(15, TimeUnit.MINUTES)
            .build<String, AtomicInteger>()

    companion object {
        const val MAX_FAILURES = 10
        const val BAN_DURATION_MS = 10 * 60 * 1000L // 10 minutes
        const val MAX_READ_REQUESTS_15M = 5000
        const val MAX_WRITE_REQUESTS_15M = 50
    }

    fun isRateLimited(
        ip: String,
        isWrite: Boolean,
    ): Boolean {
        val cache = if (isWrite) writeRateLimitCache else readRateLimitCache
        val limit = if (isWrite) MAX_WRITE_REQUESTS_15M else MAX_READ_REQUESTS_15M

        val count = cache.get(ip) { AtomicInteger(0) }.incrementAndGet()
        return count > limit
    }

    fun incrementUnauthorized() {
        unauthorizedAttempts.incrementAndGet()
    }

    fun registerFailedAttempt(ip: String) {
        incrementUnauthorized()
        val current = failedAttempts.compute(ip) { _, count -> (count ?: 0) + 1 }!!
        if (current >= MAX_FAILURES) {
            bannedIps[ip] = System.currentTimeMillis() + BAN_DURATION_MS
            failedAttempts.remove(ip) // Reset failures after ban
            println("BANNED IP: $ip for 10 minutes due to excessive failures.")
        }
    }

    fun isBanned(ip: String): Boolean {
        val expiration = bannedIps[ip] ?: return false
        if (System.currentTimeMillis() > expiration) {
            bannedIps.remove(ip)
            return false
        }
        return true
    }

    fun getUnauthorizedCount(): Long = unauthorizedAttempts.get()

    fun resetUnauthorized() {
        unauthorizedAttempts.set(0)
        failedAttempts.clear()
        bannedIps.clear()
    }
}
