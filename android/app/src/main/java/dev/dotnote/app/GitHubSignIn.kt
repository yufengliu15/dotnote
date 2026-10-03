package dev.dotnote.app

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** Keep one device authorization alive across browser visits and temporary network failures. */
internal class GitHubSignIn(
    private val oauth: suspend (String, Map<String, String>) -> JSONObject = { endpoint, values ->
        withContext(Dispatchers.IO) { GitHub("").oauth(endpoint, values) }
    },
    private val user: suspend (String) -> String = { token ->
        withContext(Dispatchers.IO) { GitHub(token).user() }
    },
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val awaitForeground: suspend () -> Unit = {},
    private val onNetworkWait: (String?) -> Unit = {},
) {
    suspend fun start(clientId: String): DeviceLogin {
        val response =
            retryNetwork(now() + 60_000) {
                oauth("device/code", mapOf("client_id" to clientId, "scope" to "repo"))
            }
        require(response.has("device_code")) {
            "GitHub rejected this client ID. Enable device authorization on the app registration."
        }
        return DeviceLogin(
                response.getString("device_code"),
                response.getString("user_code"),
                response.getString("verification_uri"),
                response.optInt("interval", 5),
                response.getInt("expires_in"),
            )
            .also {
                require(it.uri == "https://github.com/login/device") { "Unexpected sign-in URL" }
            }
    }

    suspend fun complete(clientId: String, login: DeviceLogin): Pair<JSONObject, String> {
        val deadline = now() + login.expires * 1000L
        var interval = login.interval.coerceAtLeast(5) * 1000L
        var lastNetworkError: IOException? = null
        while (now() < deadline) {
            pause(minOf(interval, deadline - now()))
            if (now() >= deadline) break
            foregroundBefore(deadline)
            val response =
                try {
                    oauth(
                        "oauth/access_token",
                        mapOf(
                            "client_id" to clientId,
                            "device_code" to login.code,
                            "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                        ),
                    )
                } catch (e: IOException) {
                    if (!isTemporaryGitHubNetworkFailure(e)) throw e
                    lastNetworkError = e
                    onNetworkWait(
                        gitHubNetworkMessage(e) + " Retrying automatically; keep Dotnote open."
                    )
                    // RFC 8628 requires a reduced polling frequency after a connection timeout.
                    interval = maxOf(interval, minOf(interval * 2, 60_000))
                    continue
                }
            lastNetworkError = null
            onNetworkWait(null)
            if (response.has("access_token")) {
                // Never exchange the device code again if /user fails after approval.
                val loginName =
                    retryNetwork(now() + 60_000) { user(response.getString("access_token")) }
                return response to loginName
            }
            when (response.optString("error")) {
                "authorization_pending" -> Unit
                "slow_down" -> interval += 5000
                else ->
                    throw IOException(
                        "GitHub authorization expired or was declined. Try connecting again."
                    )
            }
        }
        lastNetworkError?.let { throw IOException(gitHubNetworkMessage(it), it) }
        throw IOException("GitHub authorization timed out. Try connecting again.")
    }

    private suspend fun foregroundBefore(deadline: Long) {
        val remaining = deadline - now()
        if (
            remaining <= 0 ||
                withTimeoutOrNull(remaining) {
                    awaitForeground()
                    true
                } != true ||
                now() >= deadline
        ) {
            throw IOException(
                "GitHub sign-in timed out. Return to Dotnote and try connecting again."
            )
        }
    }

    private suspend fun <T> retryNetwork(deadline: Long, request: suspend () -> T): T {
        var interval = 5000L
        while (true) {
            foregroundBefore(deadline)
            try {
                return request().also { onNetworkWait(null) }
            } catch (e: IOException) {
                if (!isTemporaryGitHubNetworkFailure(e)) throw e
                val remaining = deadline - now()
                if (remaining <= interval) throw IOException(gitHubNetworkMessage(e), e)
                onNetworkWait(
                    gitHubNetworkMessage(e) + " Retrying automatically; keep Dotnote open."
                )
                pause(interval)
                interval = minOf(interval * 2, 30_000)
            }
        }
    }
}

internal fun isTemporaryGitHubNetworkFailure(error: IOException): Boolean =
    error is UnknownHostException ||
        error is SocketTimeoutException ||
        error is ConnectException ||
        error is NoRouteToHostException ||
        error is SocketException

internal fun gitHubNetworkMessage(error: IOException): String =
    if (error is UnknownHostException)
        "Dotnote cannot resolve GitHub's address. Check Wi-Fi, Private DNS, VPN, and Dotnote's network access."
    else "Dotnote cannot reach GitHub. Check your internet connection and Dotnote's network access."
