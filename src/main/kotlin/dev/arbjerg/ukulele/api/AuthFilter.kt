package dev.arbjerg.ukulele.api

import dev.arbjerg.ukulele.config.BotProps
import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component

@Component
class AuthFilter(
    val botProps: BotProps,
    val securityService: SecurityService,
) : Filter {
    override fun doFilter(
        request: ServletRequest,
        response: ServletResponse,
        chain: FilterChain,
    ) {
        val req = request as HttpServletRequest
        val res = response as HttpServletResponse
        val path = req.requestURI

        if (!path.startsWith("/api")) {
            chain.doFilter(request, response)
            return
        }

        // Allow CORS preflight requests
        if (req.method == "OPTIONS") {
            chain.doFilter(request, response)
            return
        }

        val ip = req.remoteAddr ?: "unknown"
        val isResetEndpoint = path == "/api/security/reset" && req.method == "POST"

        // If banned, block everything EXCEPT the reset endpoint (which allows self-recovery if you have the token)
        if (!isResetEndpoint && securityService.isBanned(ip)) {
            res.status = HttpStatus.TOO_MANY_REQUESTS.value()
            res.writer.write("Your IP is temporarily banned due to excessive unauthorized attempts.")
            return
        }

        val authHeader = req.getHeader("Authorization")
        val expectedToken = botProps.apiToken

        // Simple check: "Bearer <token>" or just "<token>"
        val isValid = authHeader != null && (authHeader == expectedToken || authHeader == "Bearer $expectedToken")

        if (!isValid) {
            securityService.registerFailedAttempt(ip)
            res.status = HttpStatus.UNAUTHORIZED.value()
            // We can write a body if we want, but empty is fine to match previous behavior (which setComplete)
            // res.writer.write("Unauthorized")
            return
        }

        chain.doFilter(request, response)
    }
}
