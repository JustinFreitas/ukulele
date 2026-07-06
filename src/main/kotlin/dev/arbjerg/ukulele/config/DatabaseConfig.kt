package dev.arbjerg.ukulele.config

import io.r2dbc.h2.H2ConnectionConfiguration
import io.r2dbc.h2.H2ConnectionFactory
import io.r2dbc.pool.ConnectionPool
import io.r2dbc.pool.ConnectionPoolConfiguration
import io.r2dbc.spi.ConnectionFactory
import org.flywaydb.core.Flyway
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

@Configuration
class DatabaseConfig(
    private val botProps: BotProps,
) {
    @Bean(destroyMethod = "dispose")
    fun connectionFactory(): ConnectionFactory {
        val factory =
            H2ConnectionFactory(
                H2ConnectionConfiguration
                    .builder()
                    .file(botProps.database + ";DATABASE_TO_UPPER=false")
                    .build(),
            )
        val config =
            ConnectionPoolConfiguration
                .builder(factory)
                .maxIdleTime(Duration.ofMinutes(30))
                .initialSize(5)
                .maxSize(20)
                .build()
        return ConnectionPool(config)
    }

    @Bean(initMethod = "migrate")
    fun flyway(): Flyway =
        Flyway(
            Flyway.configure().dataSource(
                "jdbc:h2:" + botProps.database + ";DATABASE_TO_UPPER=false",
                "",
                "",
            ),
        )
}
