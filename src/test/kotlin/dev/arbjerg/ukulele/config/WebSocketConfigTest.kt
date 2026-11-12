package dev.arbjerg.ukulele.config

import dev.arbjerg.ukulele.api.SecurityService
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.sharding.ShardManager
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.MessagingException
import org.springframework.messaging.simp.config.ChannelRegistration
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.ChannelInterceptor
import org.springframework.messaging.support.MessageBuilder
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration
import org.springframework.web.socket.server.HandshakeInterceptor
import java.net.InetSocketAddress

class WebSocketConfigTest {
    private lateinit var botProps: BotProps
    private lateinit var shardManager: ShardManager
    private lateinit var securityService: SecurityService
    private lateinit var wsConfig: WebSocketConfig
    private lateinit var interceptor: ChannelInterceptor
    private lateinit var handshakeInterceptor: HandshakeInterceptor

    @BeforeEach
    fun setUp() {
        botProps =
            BotProps().apply {
                apiToken = "secret-ws-token"
                requireWebsocketAuth = true
            }
        shardManager = mock(ShardManager::class.java)
        securityService = mock(SecurityService::class.java)
        `when`(securityService.isRateLimited(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyBoolean()))
            .thenReturn(false)
        `when`(securityService.isBanned(org.mockito.ArgumentMatchers.anyString())).thenReturn(false)

        wsConfig = WebSocketConfig(botProps, shardManager, securityService)

        val registration = mock(ChannelRegistration::class.java)
        val interceptorCaptor = ArgumentCaptor.forClass(ChannelInterceptor::class.java)
        wsConfig.configureClientInboundChannel(registration)
        verify(registration).interceptors(interceptorCaptor.capture())
        interceptor = interceptorCaptor.value

        val endpointRegistry = mock(StompEndpointRegistry::class.java)
        val endpointReg = mock(StompWebSocketEndpointRegistration::class.java)
        `when`(endpointRegistry.addEndpoint("/ws")).thenReturn(endpointReg)
        `when`(endpointReg.setAllowedOriginPatterns(org.mockito.ArgumentMatchers.any())).thenReturn(endpointReg)
        val handshakeCaptor = ArgumentCaptor.forClass(HandshakeInterceptor::class.java)
        wsConfig.registerStompEndpoints(endpointRegistry)
        verify(endpointReg).addInterceptors(handshakeCaptor.capture())
        handshakeInterceptor = handshakeCaptor.value
    }

    @Test
    fun `beforeHandshake rejects rate-limited IPs with 429`() {
        `when`(securityService.isRateLimited("10.0.0.5", false)).thenReturn(true)
        val req = mock(ServerHttpRequest::class.java)
        val res = mock(ServerHttpResponse::class.java)
        `when`(req.remoteAddress).thenReturn(InetSocketAddress("10.0.0.5", 12345))
        `when`(req.headers).thenReturn(HttpHeaders())

        val allowed = handshakeInterceptor.beforeHandshake(req, res, mock(WebSocketHandler::class.java), mutableMapOf())

        assertFalse(allowed)
        verify(res).setStatusCode(HttpStatus.TOO_MANY_REQUESTS)
    }

    @Test
    fun `unauthorized SUBSCRIBE registers failed attempt for client IP`() {
        val accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE)
        accessor.destination = "/topic/player/123"
        accessor.sessionAttributes = mutableMapOf("clientIp" to "10.0.0.8", "authorized" to false)
        val message = MessageBuilder.createMessage(ByteArray(0), accessor.messageHeaders)

        assertThrows(MessagingException::class.java) {
            interceptor.preSend(message, mock(MessageChannel::class.java))
        }

        verify(securityService, times(1)).registerFailedAttempt("10.0.0.8")
        verify(securityService, never()).incrementUnauthorized()
    }

    @Test
    fun `SUBSCRIBE to invalid destination registers failed attempt`() {
        val accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE)
        accessor.destination = "/topic/secret"
        accessor.sessionAttributes = mutableMapOf("clientIp" to "10.0.0.9", "authorized" to true)
        val message = MessageBuilder.createMessage(ByteArray(0), accessor.messageHeaders)

        assertThrows(MessagingException::class.java) {
            interceptor.preSend(message, mock(MessageChannel::class.java))
        }

        verify(securityService, times(1)).registerFailedAttempt("10.0.0.9")
    }

    @Test
    fun `rate-limited SUBSCRIBE is rejected before topic authorization`() {
        `when`(securityService.isRateLimited("10.0.0.10", false)).thenReturn(true)
        val accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE)
        accessor.destination = "/topic/player/123"
        accessor.sessionAttributes = mutableMapOf("clientIp" to "10.0.0.10", "authorized" to true)
        val message = MessageBuilder.createMessage(ByteArray(0), accessor.messageHeaders)

        assertThrows(MessagingException::class.java) {
            interceptor.preSend(message, mock(MessageChannel::class.java))
        }
    }

    @Test
    fun `authorized SUBSCRIBE to valid guild topic succeeds`() {
        `when`(shardManager.getGuildById(123L)).thenReturn(mock(Guild::class.java))
        val accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE)
        accessor.destination = "/topic/player/123"
        accessor.sessionAttributes = mutableMapOf("clientIp" to "10.0.0.11", "authorized" to true)
        val message = MessageBuilder.createMessage(ByteArray(0), accessor.messageHeaders)

        val result = interceptor.preSend(message, mock(MessageChannel::class.java))

        assertSame(message, result)
        verify(securityService, never()).registerFailedAttempt(org.mockito.ArgumentMatchers.anyString())
    }
}
