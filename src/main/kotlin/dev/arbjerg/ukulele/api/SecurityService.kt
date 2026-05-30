package dev.arbjerg.ukulele.api

import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@Service
class SecurityService {
    private val unauthorizedAttempts = AtomicLong(0)
    private val failedAttempts = ConcurrentHashMap<String, Int>()
    private val bannedIps = ConcurrentHashMap<String, Long>()

    companion object {
        const val MAX_FAILURES = 10
        const val BAN_DURATION_MS = 10 * 60 * 1000L // 10 minutes
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

    fun getUnauthorizedCount(): Long {
        return unauthorizedAttempts.get()
    }

    fun resetUnauthorized() {
        unauthorizedAttempts.set(0)
        failedAttempts.clear()
        bannedIps.clear()
    }
}
