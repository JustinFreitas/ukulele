package dev.arbjerg.ukulele

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class UkuleleApplication

fun main(args: Array<String>) {
    val logFile = java.io.File("lifecycle-debug.log")
    logFile.appendText("Application starting at ${java.time.Instant.now()}\n")

    Runtime.getRuntime().addShutdownHook(
        Thread {
            logFile.appendText("JVM Global Shutdown Hook caught signal at ${java.time.Instant.now()}\n")
        },
    )

    System.setProperty("spring.config.name", "ukulele")
    System.setProperty("spring.config.title", "ukulele")
    val context = runApplication<UkuleleApplication>(*args)
    val botProps = context.getBean(dev.arbjerg.ukulele.config.BotProps::class.java)
    val log = org.slf4j.LoggerFactory.getLogger(UkuleleApplication::class.java)
    if (botProps.apiToken == "secret" || botProps.apiToken.isBlank()) {
        log.warn("WARNING: The remote control apiToken is set to default 'secret' or is blank! Please configure a strong custom token in ukulele.yml under config.apiToken.")
    }
}
