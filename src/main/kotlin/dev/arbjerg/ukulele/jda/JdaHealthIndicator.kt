package dev.arbjerg.ukulele.jda

import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.sharding.ShardManager
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.stereotype.Component

@Component("jda")
class JdaHealthIndicator(
    private val shardManager: ShardManager,
) : HealthIndicator {
    override fun health(): Health {
        val shards = shardManager.shards
        val total = shards.size
        val connected = shards.count { it.status == JDA.Status.CONNECTED }
        val statuses =
            shards.associate { shard ->
                shard.shardInfo.shardId.toString() to shard.status.name
            }

        val builder =
            if (total > 0 && connected == total) {
                Health.up()
            } else {
                Health.down()
            }

        return builder
            .withDetail("shardsTotal", total)
            .withDetail("shardsConnected", connected)
            .withDetail("averageGatewayPingMs", shardManager.averageGatewayPing)
            .withDetail("shards", statuses)
            .build()
    }
}
