package dev.arbjerg.ukulele.jda

import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.sharding.ShardManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.boot.health.contributor.Status

class JdaHealthIndicatorTest {
    private fun mockShard(
        id: Int,
        total: Int,
        status: JDA.Status,
    ): JDA {
        val jda = mock(JDA::class.java)
        `when`(jda.status).thenReturn(status)
        `when`(jda.shardInfo).thenReturn(JDA.ShardInfo(id, total))
        return jda
    }

    @Test
    fun `health is UP when all shards are connected`() {
        val shard = mockShard(0, 1, JDA.Status.CONNECTED)
        val shardManager = mock(ShardManager::class.java)
        `when`(shardManager.shards).thenReturn(listOf(shard))
        `when`(shardManager.averageGatewayPing).thenReturn(42.0)

        val health = JdaHealthIndicator(shardManager).health()

        assertEquals(Status.UP, health.status)
        assertEquals(1, health.details["shardsTotal"])
        assertEquals(1, health.details["shardsConnected"])
        assertEquals(42.0, health.details["averageGatewayPingMs"])
    }

    @Test
    fun `health is DOWN when any shard is disconnected`() {
        val shard0 = mockShard(0, 2, JDA.Status.CONNECTED)
        val shard1 = mockShard(1, 2, JDA.Status.RECONNECT_QUEUED)
        val shardManager = mock(ShardManager::class.java)
        `when`(shardManager.shards).thenReturn(listOf(shard0, shard1))
        `when`(shardManager.averageGatewayPing).thenReturn(55.0)

        val health = JdaHealthIndicator(shardManager).health()

        assertEquals(Status.DOWN, health.status)
        assertEquals(2, health.details["shardsTotal"])
        assertEquals(1, health.details["shardsConnected"])
    }

    @Test
    fun `health is DOWN when no shards are registered`() {
        val shardManager = mock(ShardManager::class.java)
        `when`(shardManager.shards).thenReturn(emptyList())
        `when`(shardManager.averageGatewayPing).thenReturn(-1.0)

        val health = JdaHealthIndicator(shardManager).health()

        assertEquals(Status.DOWN, health.status)
        assertEquals(0, health.details["shardsTotal"])
        assertEquals(0, health.details["shardsConnected"])
    }
}
