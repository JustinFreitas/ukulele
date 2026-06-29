package dev.arbjerg.ukulele.jda

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Component

/**
 * Lifecycle-managed coroutine scope for command invocations.
 *
 * Replaces the previous `GlobalScope.launch` usage: a [SupervisorJob] keeps one failing command from
 * cancelling the others, the [CoroutineExceptionHandler] makes sure exceptions are logged instead of
 * silently swallowed, and [destroy] cancels everything cleanly on application shutdown.
 */
@Component
class CommandScope :
    CoroutineScope,
    DisposableBean {
    private val log = LoggerFactory.getLogger(CommandScope::class.java)

    private val exceptionHandler =
        CoroutineExceptionHandler { _, throwable ->
            log.error("Unhandled exception in command coroutine", throwable)
        }

    override val coroutineContext =
        SupervisorJob() + Dispatchers.Default + CoroutineName("ukulele-commands") + exceptionHandler

    override fun destroy() {
        cancel("CommandScope is shutting down")
    }
}
