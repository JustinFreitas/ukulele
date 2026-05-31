package dev.arbjerg.ukulele.api

import dev.arbjerg.ukulele.config.BotProps
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class ConfigDto(
    val useWebsockets: Boolean,
    val pollIntervalFast: Long = 1000,
    val pollIntervalSlow: Long = 5000,
)

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = ["*"])
class ConfigController(
    val botProps: BotProps,
) {
    @GetMapping("/config")
    fun getConfig(): ConfigDto =
        ConfigDto(
            useWebsockets = botProps.useWebsockets,
        )
}
