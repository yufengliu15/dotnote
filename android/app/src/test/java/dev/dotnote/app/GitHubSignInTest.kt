package dev.dotnote.app

import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GitHubSignInTest {
    private val login =
        DeviceLogin("same-device-code", "USER-CODE", "https://github.com/login/device", 5, 900)

    private fun token() = JSONObject().put("access_token", "test-token")

    private fun pending() = JSONObject().put("error", "authorization_pending")

    private class Clock {
        var time = 0L
        val waits = mutableListOf<Long>()

        suspend fun pause(millis: Long) {
            waits += millis
            time += millis
        }
    }

    @Test
    fun browserBackgroundWaitsForReturnBeforeExchangingCode() = runBlocking {
        val clock = Clock()
        val foreground = CompletableDeferred<Unit>()
        var polls = 0
        val signIn =
            GitHubSignIn(
                oauth = { _, _ ->
                    polls++
                    token()
                },
                user = { "octocat" },
                now = { clock.time },
                pause = clock::pause,
                awaitForeground = { foreground.await() },
            )
        val result = async(start = CoroutineStart.UNDISPATCHED) { signIn.complete("client", login) }
        assertEquals(0, polls)
        assertFalse(result.isCompleted)
        foreground.complete(Unit)
        assertEquals("octocat", result.await().second)
        assertEquals(1, polls)
    }

    @Test
    fun dnsFailureAfterConsentRetriesSameCodeAndClearsWaitingMessage() = runBlocking {
        val clock = Clock()
        val codes = mutableListOf<String?>()
        val statuses = mutableListOf<String?>()
        val signIn =
            GitHubSignIn(
                oauth = { _, values ->
                    codes += values["device_code"]
                    when (codes.size) {
                        1 -> pending()
                        2 -> throw UnknownHostException("github.com")
                        else -> token()
                    }
                },
                user = { "octocat" },
                now = { clock.time },
                pause = clock::pause,
                onNetworkWait = { statuses += it },
            )
        assertEquals("octocat", signIn.complete("client", login).second)
        assertEquals(List(3) { login.code }, codes)
        assertEquals(listOf(5000L, 5000L, 10000L), clock.waits)
        assertTrue(statuses.any { it?.contains("Retrying automatically") == true })
        assertNull(statuses.last())
    }

    @Test
    fun approvedTokenSurvivesAccountLookupNetworkFailure() = runBlocking {
        val clock = Clock()
        var exchanges = 0
        val tokens = mutableListOf<String>()
        val signIn =
            GitHubSignIn(
                oauth = { _, _ ->
                    exchanges++
                    token()
                },
                user = {
                    tokens += it
                    if (tokens.size == 1) throw UnknownHostException("api.github.com")
                    "octocat"
                },
                now = { clock.time },
                pause = clock::pause,
            )
        val (response, name) = signIn.complete("client", login)
        assertEquals("octocat", name)
        assertEquals("test-token", response.getString("access_token"))
        assertEquals(listOf("test-token", "test-token"), tokens)
        assertEquals(1, exchanges)
    }

    @Test
    fun slowDownAndConnectionTimeoutIncreasePollInterval() = runBlocking {
        val clock = Clock()
        var polls = 0
        val signIn =
            GitHubSignIn(
                oauth = { _, _ ->
                    when (++polls) {
                        1 -> JSONObject().put("error", "slow_down")
                        2 -> throw SocketTimeoutException()
                        3 -> pending()
                        else -> token()
                    }
                },
                user = { "octocat" },
                now = { clock.time },
                pause = clock::pause,
            )
        signIn.complete("client", login)
        assertEquals(listOf(5000L, 10000L, 20000L, 20000L), clock.waits)
    }

    @Test
    fun permanentDnsFailureStopsAtCodeExpiryWithoutAnotherRequest() = runBlocking {
        val clock = Clock()
        var polls = 0
        val signIn =
            GitHubSignIn(
                oauth = { _, _ ->
                    polls++
                    throw UnknownHostException("github.com")
                },
                user = { error("Must not look up an account") },
                now = { clock.time },
                pause = clock::pause,
            )
        val error =
            runCatching { signIn.complete("client", login.copy(expires = 12)) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("Private DNS"))
        assertEquals(1, polls)
        assertEquals(12000L, clock.time)
    }

    @Test
    fun codeExpiringWhileBrowserIsOpenDoesNotGetExchanged() = runBlocking {
        val clock = Clock()
        var polls = 0
        val signIn =
            GitHubSignIn(
                oauth = { _, _ ->
                    polls++
                    token()
                },
                now = { clock.time },
                pause = clock::pause,
                awaitForeground = { clock.time = 900_001 },
            )
        assertTrue(runCatching { signIn.complete("client", login) }.isFailure)
        assertEquals(0, polls)
    }

    @Test
    fun declinedAuthorizationDoesNotRetry() = runBlocking {
        val clock = Clock()
        var polls = 0
        val signIn =
            GitHubSignIn(
                oauth = { _, _ ->
                    polls++
                    JSONObject().put("error", "access_denied")
                },
                now = { clock.time },
                pause = clock::pause,
            )
        val error = runCatching { signIn.complete("client", login) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("declined"))
        assertEquals(1, polls)
    }

    @Test
    fun cancellationDuringNetworkRetryStopsPolling() = runBlocking {
        val clock = Clock()
        var polls = 0
        val cancellation = CancellationException("Cancelled sign-in")
        val signIn =
            GitHubSignIn(
                oauth = { _, _ ->
                    polls++
                    throw UnknownHostException()
                },
                now = { clock.time },
                pause = { if (polls > 0) throw cancellation else clock.pause(it) },
            )
        assertSame(cancellation, runCatching { signIn.complete("client", login) }.exceptionOrNull())
        assertEquals(1, polls)
    }

    @Test
    fun initialCodeRequestRetriesDnsAndKeepsClientId() = runBlocking {
        val clock = Clock()
        var requests = 0
        val signIn =
            GitHubSignIn(
                oauth = { endpoint, values ->
                    assertEquals("device/code", endpoint)
                    assertEquals("client", values["client_id"])
                    if (++requests == 1) throw UnknownHostException()
                    JSONObject()
                        .put("device_code", login.code)
                        .put("user_code", login.userCode)
                        .put("verification_uri", login.uri)
                        .put("expires_in", login.expires)
                },
                now = { clock.time },
                pause = clock::pause,
            )
        assertEquals(login, signIn.start("client"))
        assertEquals(2, requests)
    }

    @Test
    fun authAndTlsFailuresAreNotClassifiedAsTemporaryNetworkFailures() {
        assertFalse(isTemporaryGitHubNetworkFailure(GitHubFailure(401, "Revoked")))
        assertFalse(isTemporaryGitHubNetworkFailure(SSLHandshakeException("Bad certificate")))
    }
}
