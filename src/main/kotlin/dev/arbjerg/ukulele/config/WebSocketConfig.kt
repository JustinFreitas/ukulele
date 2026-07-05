package dev.arbjerg.ukulele.config

import dev.arbjerg.ukulele.api.SecurityService
import net.dv8tion.jda.api.sharding.ShardManager
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.MessagingException
import org.springframework.messaging.simp.config.ChannelRegistration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.ChannelInterceptor
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer
import org.springframework.web.socket.server.HandshakeInterceptor

@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig(
    private val botProps: BotProps,
    private val shardManager: ShardManager,
    private val securityService: SecurityService,
) : WebSocketMessageBrokerConfigurer {
    private val allowedOrigins: Array<String>
        get() =
            botProps.corsOrigins
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toTypedArray()

    override fun configureMessageBroker(config: MessageBrokerRegistry) {
        config.enableSimpleBroker("/topic")
        config.setApplicationDestinationPrefixes("/app")
    }

    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        registry
            .addEndpoint("/ws")
            // Configurable via config.corsOrigins (default "*"); lock to the dashboard origin to harden.
            .setAllowedOriginPatterns(*allowedOrigins)
            .addInterceptors(
                object : HandshakeInterceptor {
                    override fun beforeHandshake(
                        request: ServerHttpRequest,
                        response: ServerHttpResponse,
                        wsHandler: WebSocketHandler,
                        attributes: MutableMap<String, Any>,
                    ): Boolean {
                        val xForwardedFor = request.headers.getFirst("X-Forwarded-For")
                        val ip =
                            if (!xForwardedFor.isNullOrEmpty()) {
                                xForwardedFor.split(",").first().trim()
                            } else {
                                request.remoteAddress.address?.hostAddress ?: "unknown"
                            }
                        attributes["clientIp"] = ip

                        if (securityService.isBanned(ip)) {
                            response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS)
                            return false
                        }
                        return true
                    }

                    override fun afterHandshake(
                        request: ServerHttpRequest,
                        response: ServerHttpResponse,
                        wsHandler: WebSocketHandler,
                        exception: Exception?,
                    ) {
                    }
                },
            )
    }

    /**
     * STOMP CONNECT & SUBSCRIBE authentication. The /ws endpoint is not behind [api.AuthFilter] (which
     * only guards /api). When [BotProps.requireWebsocketAuth] is enabled, clients must send the same apiToken in
     * the CONNECT frame's `Authorization` header (raw or "Bearer <token>"). We store authentication state in the
     * session attributes to authorize SUBSCRIBE frames and prevent IDOR / unauthorized topic access.
     */
    override fun configureClientInboundChannel(registration: ChannelRegistration) {
        registration.interceptors(
            object : ChannelInterceptor {
                override fun preSend(
                    message: Message<*>,
                    channel: MessageChannel,
                ): Message<*> {
                    val accessor = StompHeaderAccessor.wrap(message)
                    val ip = accessor.sessionAttributes?.get("clientIp") as? String ?: "unknown"

                    if (StompCommand.CONNECT == accessor.command) {
                        if (securityService.isBanned(ip)) {
                            throw MessagingException("Your IP is temporarily banned due to excessive unauthorized attempts.")
                        }

                        val auth = accessor.getFirstNativeHeader("Authorization")
                        val expected = botProps.apiToken
                        val valid =
                            auth != null && (
                                java.security.MessageDigest.isEqual(auth.toByteArray(), expected.toByteArray()) ||
                                    java.security.MessageDigest.isEqual(auth.toByteArray(), "Bearer $expected".toByteArray())
                            )

                        if (botProps.requireWebsocketAuth) {
                            if (!valid) {
                                securityService.registerFailedAttempt(ip)
                                throw MessagingException("Unauthorized WebSocket connection")
                            }
                            accessor.sessionAttributes?.put("authorized", true)
                        } else {
                            if (valid) {
                                accessor.sessionAttributes?.put("authorized", true)
                            }
                        }
                    } else if (StompCommand.SUBSCRIBE == accessor.command) {
                        if (securityService.isBanned(ip)) {
                            throw MessagingException("Your IP is temporarily banned due to excessive unauthorized attempts.")
                        }

                        if (botProps.requireWebsocketAuth) {
                            val authorized = accessor.sessionAttributes?.get("authorized") as? Boolean ?: false
                            if (!authorized) {
                                securityService.incrementUnauthorized()
                                throw MessagingException("Unauthorized WebSocket subscription")
                            }
                        }

                        // Validate subscription destination: must be /topic/player/{guildId} and the guild must exist
                        val destination = accessor.destination
                        if (destination == null) {
                            securityService.incrementUnauthorized()
                            throw MessagingException("Access Denied: Missing subscription destination")
                        }
                        val matchResult = "^/topic/player/(\\d+)$".toRegex().find(destination)
                        if (matchResult != null) {
                            val guildId = matchResult.groupValues[1].toLongOrNull()
                            if (guildId == null || shardManager.getGuildById(guildId) == null) {
                                throw MessagingException("Access Denied: Invalid or inaccessible Guild ID")
                            }
                        } else {
                            securityService.incrementUnauthorized()
                            throw MessagingException("Access Denied: Invalid subscription destination")
                        }
                    }
                    return message
                }
            },
        )
    }
}
