package dev.arbjerg.ukulele.api

import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component

@Component
class SecurityHeaderFilter : Filter {
    override fun doFilter(
        request: ServletRequest,
        response: ServletResponse,
        chain: FilterChain,
    ) {
        val res = response as HttpServletResponse
        res.setHeader("X-Content-Type-Options", "nosniff")
        res.setHeader("X-Frame-Options", "DENY")
        res.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
        res.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
        res.setHeader("X-XSS-Protection", "0") // Modern browsers don't use this, 0 disables it for security
        chain.doFilter(request, response)
    }
}
