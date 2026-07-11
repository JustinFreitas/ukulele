package dev.arbjerg.ukulele.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SecurityServiceTest {
    private lateinit var service: SecurityService

    @BeforeEach
    fun setUp() {
        service = SecurityService()
    }

    @Test
    fun `isRateLimited allows requests under the read limit`() {
        repeat(SecurityService.MAX_READ_REQUESTS_15M) {
            assertFalse(service.isRateLimited("1.1.1.1", isWrite = false))
        }
    }

    @Test
    fun `isRateLimited blocks requests once the read limit is exceeded`() {
        repeat(SecurityService.MAX_READ_REQUESTS_15M) {
            service.isRateLimited("1.1.1.1", isWrite = false)
        }
        assertTrue(service.isRateLimited("1.1.1.1", isWrite = false))
    }

    @Test
    fun `isRateLimited blocks requests once the write limit is exceeded`() {
        repeat(SecurityService.MAX_WRITE_REQUESTS_15M) {
            assertFalse(service.isRateLimited("1.1.1.1", isWrite = true))
        }
        assertTrue(service.isRateLimited("1.1.1.1", isWrite = true))
    }

    @Test
    fun `read and write buckets are tracked independently`() {
        repeat(SecurityService.MAX_WRITE_REQUESTS_15M) {
            service.isRateLimited("1.1.1.1", isWrite = true)
        }
        assertTrue(service.isRateLimited("1.1.1.1", isWrite = true))
        // The read bucket for the same IP is untouched.
        assertFalse(service.isRateLimited("1.1.1.1", isWrite = false))
    }

    @Test
    fun `different IPs are rate limited independently`() {
        repeat(SecurityService.MAX_WRITE_REQUESTS_15M) {
            service.isRateLimited("1.1.1.1", isWrite = true)
        }
        assertTrue(service.isRateLimited("1.1.1.1", isWrite = true))
        assertFalse(service.isRateLimited("2.2.2.2", isWrite = true))
    }

    @Test
    fun `registerFailedAttempt increments the unauthorized counter`() {
        service.registerFailedAttempt("3.3.3.3")
        service.registerFailedAttempt("3.3.3.3")
        assertEquals(2L, service.getUnauthorizedCount())
    }

    @Test
    fun `an IP is not banned before reaching the failure threshold`() {
        repeat(SecurityService.MAX_FAILURES - 1) {
            service.registerFailedAttempt("4.4.4.4")
        }
        assertFalse(service.isBanned("4.4.4.4"))
    }

    @Test
    fun `an IP is banned once the failure threshold is reached`() {
        repeat(SecurityService.MAX_FAILURES) {
            service.registerFailedAttempt("5.5.5.5")
        }
        assertTrue(service.isBanned("5.5.5.5"))
    }

    @Test
    fun `banning one IP does not affect another`() {
        repeat(SecurityService.MAX_FAILURES) {
            service.registerFailedAttempt("6.6.6.6")
        }
        assertTrue(service.isBanned("6.6.6.6"))
        assertFalse(service.isBanned("7.7.7.7"))
    }

    @Test
    fun `resetUnauthorized clears the counter and bans`() {
        repeat(SecurityService.MAX_FAILURES) {
            service.registerFailedAttempt("8.8.8.8")
        }
        assertTrue(service.isBanned("8.8.8.8"))

        service.resetUnauthorized()

        assertEquals(0L, service.getUnauthorizedCount())
        assertFalse(service.isBanned("8.8.8.8"))
    }
}
