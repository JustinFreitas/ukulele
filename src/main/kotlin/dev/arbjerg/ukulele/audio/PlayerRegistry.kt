package dev.arbjerg.ukulele.audio

import dev.arbjerg.ukulele.audio.local.LocalFileMemoryCache
import dev.arbjerg.ukulele.data.GuildProperties
import net.dv8tion.jda.api.entities.Guild
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class PlayerRegistry
    @Autowired
    constructor(
        val playerBeans: Player.Beans,
        @Autowired(required = false) val localFileCache: LocalFileMemoryCache?,
    ) : DisposableBean {
        constructor(playerBeans: Player.Beans) : this(playerBeans, null)

        private val players = ConcurrentHashMap<Long, Player>()

        init {
            localFileCache?.bindPlayerCallbacks(
                snapshotProvider = {
                    players.mapValues { (_, p) -> p.activeTracksForCacheSnapshot() }
                },
                stateNotifier = { guildIds ->
                    guildIds.forEach { gid -> players[gid]?.triggerStateUpdate() }
                },
            )
        }

        fun get(
            guild: Guild,
            guildProperties: GuildProperties,
        ) = players.computeIfAbsent(guild.idLong) { Player(playerBeans, guildProperties) }

        fun getExisting(guildId: Long): Player? = players[guildId]

        fun remove(guildId: Long): Player? {
            val player = players.remove(guildId)
            player?.destroy()
            localFileCache?.reconcileAndPrefetch()
            return player
        }

        override fun destroy() {
            players.values.forEach { it.destroy() }
            players.clear()
            localFileCache?.reconcileAndPrefetch()
        }
    }
