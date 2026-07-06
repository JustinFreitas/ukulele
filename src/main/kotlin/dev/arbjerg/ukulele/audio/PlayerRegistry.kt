package dev.arbjerg.ukulele.audio

import dev.arbjerg.ukulele.data.GuildProperties
import net.dv8tion.jda.api.entities.Guild
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class PlayerRegistry(
    val playerBeans: Player.Beans,
) : DisposableBean {
    private val players = ConcurrentHashMap<Long, Player>()

    fun get(
        guild: Guild,
        guildProperties: GuildProperties,
    ) = players.computeIfAbsent(guild.idLong) { Player(playerBeans, guildProperties) }

    fun getExisting(guildId: Long): Player? = players[guildId]

    fun remove(guildId: Long): Player? {
        val player = players.remove(guildId)
        player?.destroy()
        return player
    }

    override fun destroy() {
        players.values.forEach { it.destroy() }
        players.clear()
    }
}
