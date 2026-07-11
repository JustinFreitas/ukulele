package dev.arbjerg.ukulele.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import reactor.core.publisher.Mono

class GuildPropertiesServiceTest {
    private lateinit var repo: GuildPropertiesRepository
    private lateinit var service: GuildPropertiesService

    @BeforeEach
    fun setUp() {
        repo = mock(GuildPropertiesRepository::class.java)
        `when`(repo.save(any())).thenAnswer { invocation -> Mono.just(invocation.getArgument<GuildProperties>(0)) }
        service = GuildPropertiesService(repo)
    }

    @Test
    fun `transform on a cache miss starts from default properties and persists the mutation`() {
        `when`(repo.findById(42L)).thenReturn(Mono.empty())

        val result = service.transform(42L) { it.volume = 777 }.block()!!

        assertEquals(42L, result.guildId)
        assertEquals(777, result.volume)
        assertFalse(result.new)
        verify(repo, times(1)).findById(42L)
    }

    @Test
    fun `transform clones cached properties instead of mutating them in place`() {
        `when`(repo.findById(1L)).thenReturn(Mono.just(GuildProperties(1L, 100)))

        val before = service.get(1L).block()!!
        val after = service.transform(1L) { it.volume = 200 }.block()!!

        assertEquals(100, before.volume)
        assertEquals(200, after.volume)
        assertNotSame(before, after)
    }

    @Test
    fun `transform re-populates the cache so a later get does not hit the repository again`() {
        `when`(repo.findById(2L)).thenReturn(Mono.just(GuildProperties(2L, 100)))

        service.transform(2L) { it.volume = 300 }.block()
        val cached = service.get(2L).block()!!

        assertEquals(300, cached.volume)
        verify(repo, times(1)).findById(2L)
    }

    @Test
    fun `a fresh guild's properties are marked new until first persisted`() {
        `when`(repo.findById(3L)).thenReturn(Mono.empty())

        val loaded = service.get(3L).block()!!

        assertTrue(loaded.new)
        assertEquals(1000, loaded.volume)
    }
}
