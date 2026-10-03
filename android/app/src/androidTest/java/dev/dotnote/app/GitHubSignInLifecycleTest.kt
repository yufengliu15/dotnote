package dev.dotnote.app

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.withResumed
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GitHubSignInLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun backgroundOwner(): LifecycleOwner {
        lateinit var owner: LifecycleOwner
        instrumentation.runOnMainSync {
            owner =
                object : LifecycleOwner {
                    override val lifecycle =
                        LifecycleRegistry(this).apply { currentState = Lifecycle.State.STARTED }
                }
        }
        return owner
    }

    @Test
    fun returningFromBrowserResumesAuthorizationWithSameCode() = runBlocking {
        val owner = backgroundOwner()
        val waiting = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val signIn =
            GitHubSignIn(
                oauth = { _, values ->
                    assertEquals("original-device-code", values["device_code"])
                    calls.incrementAndGet()
                    JSONObject().put("access_token", "test-token")
                },
                user = { "octocat" },
                pause = {},
                awaitForeground = {
                    waiting.complete(Unit)
                    owner.lifecycle.withResumed {}
                },
            )
        val result =
            async(Dispatchers.Main) {
                signIn.complete(
                    "client",
                    DeviceLogin(
                        "original-device-code",
                        "CODE",
                        "https://github.com/login/device",
                        5,
                        900,
                    ),
                )
            }
        withTimeout(5000) { waiting.await() }
        assertEquals(0, calls.get())
        assertFalse(result.isCompleted)
        withContext(Dispatchers.Main) {
            (owner.lifecycle as LifecycleRegistry).currentState = Lifecycle.State.RESUMED
        }
        assertEquals("octocat", withTimeout(5000) { result.await() }.second)
        assertEquals(1, calls.get())
        withContext(Dispatchers.Main) {
            (owner.lifecycle as LifecycleRegistry).currentState = Lifecycle.State.DESTROYED
        }
    }

    @Test
    fun cancellingWhileBrowserIsOpenPreventsTokenExchangeOnReturn() = runBlocking {
        val owner = backgroundOwner()
        val waiting = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val signIn =
            GitHubSignIn(
                oauth = { _, _ ->
                    calls.incrementAndGet()
                    JSONObject()
                },
                pause = {},
                awaitForeground = {
                    waiting.complete(Unit)
                    owner.lifecycle.withResumed {}
                },
            )
        val result =
            async(Dispatchers.Main) {
                signIn.complete(
                    "client",
                    DeviceLogin("code", "CODE", "https://github.com/login/device", 5, 900),
                )
            }
        withTimeout(5000) { waiting.await() }
        result.cancel()
        result.join()
        withContext(Dispatchers.Main) {
            (owner.lifecycle as LifecycleRegistry).currentState = Lifecycle.State.RESUMED
        }
        assertTrue(result.isCancelled)
        assertEquals(0, calls.get())
        withContext(Dispatchers.Main) {
            (owner.lifecycle as LifecycleRegistry).currentState = Lifecycle.State.DESTROYED
        }
    }
}
