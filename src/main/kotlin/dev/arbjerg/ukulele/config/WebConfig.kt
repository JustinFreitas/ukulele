package dev.arbjerg.ukulele.config

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Centralised CORS configuration for the remote-control API, driven by [BotProps.corsOrigins]
 * (default "*"). This replaces the hardcoded per-controller `@CrossOrigin(origins = ["*"])`
 * annotations so the allowed origins can be locked down to the dashboard's origin via config
 * without code changes.
 */
@Configuration
class WebConfig(
    private val botProps: BotProps,
) : WebMvcConfigurer {
    override fun addCorsMappings(registry: CorsRegistry) {
        val origins =
            botProps.corsOrigins
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toTypedArray()

        registry
            .addMapping("/api/**")
            .allowedOrigins(*origins)
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
    }
}
