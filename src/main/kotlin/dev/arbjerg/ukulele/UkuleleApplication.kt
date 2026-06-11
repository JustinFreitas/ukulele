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
    runApplication<UkuleleApplication>(*args)
}
