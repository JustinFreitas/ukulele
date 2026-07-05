package dev.arbjerg.ukulele.api

import com.github.benmanes.caffeine.cache.Caffeine
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@Service
class SecurityService {
    private val log: Logger = LoggerFactory.getLogger(SecurityService::class.java)

    private val unauthorizedAttempts = AtomicLong(0)

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

    // Failed attempts and banned IPs caches with TTL to prevent unbounded memory growth
    private val failedAttempts =
        Caffeine
            .newBuilder()
            .expireAfterWrite(1, TimeUnit.HOURS)
            .build<String, Int>()

    private val bannedIps =
        Caffeine
            .newBuilder()
            .expireAfterWrite(BAN_DURATION_MS, TimeUnit.MILLISECONDS)
            .build<String, Boolean>()

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
        val current = failedAttempts.asMap().compute(ip) { _, count -> (count ?: 0) + 1 }!!
        if (current >= MAX_FAILURES) {
            bannedIps.put(ip, true)
            failedAttempts.invalidate(ip) // Reset failures after ban
            log.warn("BANNED IP: {} for 10 minutes due to excessive failures.", ip)
        }
    }

    fun isBanned(ip: String): Boolean = bannedIps.getIfPresent(ip) != null

    fun getUnauthorizedCount(): Long = unauthorizedAttempts.get()

    fun resetUnauthorized() {
        unauthorizedAttempts.set(0)
        failedAttempts.invalidateAll()
        bannedIps.invalidateAll()
    }
}
