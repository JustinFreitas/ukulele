package dev.arbjerg.ukulele.config

import org.springframework.context.annotation.Configuration
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.MessagingException
import org.springframework.messaging.simp.config.ChannelRegistration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.ChannelInterceptor
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer

@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig(
    private val botProps: BotProps,
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
        // .withSockJS() // Optional: Enable if we want SockJS fallback
    }

    /**
     * Optional STOMP CONNECT authentication. The /ws endpoint is not behind [api.AuthFilter] (which
     * only guards /api), so by default it is unauthenticated and only broadcasts read-only player
     * status. When [BotProps.requireWebsocketAuth] is enabled, clients must send the same apiToken in
     * the CONNECT frame's `Authorization` header (raw or "Bearer <token>"). Default off so an external
     * dashboard that does not yet send the token keeps working.
     */
    override fun configureClientInboundChannel(registration: ChannelRegistration) {
        if (!botProps.requireWebsocketAuth) {
            return
        }

        registration.interceptors(
            object : ChannelInterceptor {
                override fun preSend(
                    message: Message<*>,
                    channel: MessageChannel,
                ): Message<*> {
                    val accessor = StompHeaderAccessor.wrap(message)
                    if (StompCommand.CONNECT == accessor.command) {
                        val auth = accessor.getFirstNativeHeader("Authorization")
                        val expected = botProps.apiToken
                        val valid = auth != null && (auth == expected || auth == "Bearer $expected")
                        if (!valid) {
                            throw MessagingException("Unauthorized WebSocket connection")
                        }
                    }
                    return message
                }
            },
        )
    }
}
