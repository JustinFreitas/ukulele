package dev.arbjerg.ukulele.api

import dev.arbjerg.ukulele.config.BotProps
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Service

@Service
class PlayerEventPublisher(
    val messagingTemplate: SimpMessagingTemplate,
    val botProps: BotProps,
) {
    fun publishUpdate(
        guildId: String,
        status: PlayerStatusDto,
    ) {
        if (botProps.useWebsockets) {
            messagingTemplate.convertAndSend("/topic/player/$guildId", status)
        }
    }
}
