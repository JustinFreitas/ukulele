package dev.arbjerg.ukulele.api

import dev.arbjerg.ukulele.config.BotProps
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.io.PrintWriter

class AuthFilterTest {
    private lateinit var securityService: SecurityService
    private lateinit var botProps: BotProps
    private lateinit var filter: AuthFilter
    private lateinit var chain: FilterChain

    private val testApiToken = "s3cr3t"

    @BeforeEach
    fun setUp() {
        securityService = mock(SecurityService::class.java)
        `when`(securityService.isRateLimited(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyBoolean()))
            .thenReturn(false)
        `when`(securityService.isBanned(org.mockito.ArgumentMatchers.anyString())).thenReturn(false)

        botProps = BotProps().apply { apiToken = testApiToken }
        filter = AuthFilter(botProps, securityService)
        chain = mock(FilterChain::class.java)
    }

    private fun request(
        path: String = "/api/status",
        method: String = "GET",
        authHeader: String? = testApiToken,
        xForwardedFor: String? = null,
        remoteAddr: String = "9.9.9.9",
    ): HttpServletRequest {
        val req = mock(HttpServletRequest::class.java)
        `when`(req.requestURI).thenReturn(path)
        `when`(req.method).thenReturn(method)
        `when`(req.getHeader("Authorization")).thenReturn(authHeader)
        `when`(req.getHeader("X-Forwarded-For")).thenReturn(xForwardedFor)
        `when`(req.remoteAddr).thenReturn(remoteAddr)
        return req
    }

    private fun response(): HttpServletResponse {
        val res = mock(HttpServletResponse::class.java)
        `when`(res.writer).thenReturn(mock(PrintWriter::class.java))
        return res
    }

    @Test
    fun `non-api paths pass through without any auth checks`() {
        val req = request(path = "/health", authHeader = null)
        val res = response()

        filter.doFilter(req, res, chain)

        verify(chain, times(1)).doFilter(req, res)
        verify(securityService, never()).isRateLimited(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyBoolean())
    }

    @Test
    fun `OPTIONS preflight requests pass through`() {
        val req = request(method = "OPTIONS", authHeader = null)
        val res = response()

        filter.doFilter(req, res, chain)

        verify(chain, times(1)).doFilter(req, res)
    }

    @Test
    fun `rate limited requests are rejected with 429 and never reach the chain`() {
        `when`(securityService.isRateLimited("9.9.9.9", false)).thenReturn(true)
        val req = request()
        val res = response()

        filter.doFilter(req, res, chain)

        verify(res).status = 429
        verify(chain, never()).doFilter(req, res)
    }

    @Test
    fun `banned IPs are rejected on normal endpoints`() {
        `when`(securityService.isBanned("9.9.9.9")).thenReturn(true)
        val req = request(path = "/api/queue")
        val res = response()

        filter.doFilter(req, res, chain)

        verify(res).status = 429
        verify(chain, never()).doFilter(req, res)
    }

    @Test
    fun `banned IPs can still reach the security reset endpoint`() {
        `when`(securityService.isBanned("9.9.9.9")).thenReturn(true)
        val req = request(path = "/api/security/reset", method = "POST", authHeader = testApiToken)
        val res = response()

        filter.doFilter(req, res, chain)

        // Not blocked by the ban check; falls through to the (successful) token check.
        verify(chain, times(1)).doFilter(req, res)
    }

    @Test
    fun `first IP in X-Forwarded-For is used for rate limiting and banning`() {
        val req = request(xForwardedFor = " 3.3.3.3 , 4.4.4.4")
        val res = response()

        filter.doFilter(req, res, chain)

        verify(securityService).isRateLimited("3.3.3.3", false)
        verify(securityService).isBanned("3.3.3.3")
    }

    @Test
    fun `falls back to remoteAddr when X-Forwarded-For is absent`() {
        val req = request(xForwardedFor = null, remoteAddr = "5.5.5.5")
        val res = response()

        filter.doFilter(req, res, chain)

        verify(securityService).isRateLimited("5.5.5.5", false)
    }

    @Test
    fun `valid raw token is accepted`() {
        val req = request(authHeader = testApiToken)
        val res = response()

        filter.doFilter(req, res, chain)

        verify(chain, times(1)).doFilter(req, res)
        verify(securityService, never()).registerFailedAttempt(org.mockito.ArgumentMatchers.anyString())
    }

    @Test
    fun `valid Bearer-prefixed token is accepted`() {
        val req = request(authHeader = "Bearer $testApiToken")
        val res = response()

        filter.doFilter(req, res, chain)

        verify(chain, times(1)).doFilter(req, res)
    }

    @Test
    fun `invalid token is rejected with 401 and registers a failed attempt`() {
        val req = request(authHeader = "wrong-token")
        val res = response()

        filter.doFilter(req, res, chain)

        verify(res).status = 401
        verify(securityService, times(1)).registerFailedAttempt("9.9.9.9")
        verify(chain, never()).doFilter(req, res)
    }

    @Test
    fun `missing Authorization header is rejected with 401`() {
        val req = request(authHeader = null)
        val res = response()

        filter.doFilter(req, res, chain)

        verify(res).status = 401
        verify(securityService, times(1)).registerFailedAttempt("9.9.9.9")
        verify(chain, never()).doFilter(req, res)
    }

    @Test
    fun `write requests use the write rate limit bucket`() {
        val req = request(method = "POST", authHeader = testApiToken)
        val res = response()

        filter.doFilter(req, res, chain)

        verify(securityService).isRateLimited("9.9.9.9", true)
    }
}
