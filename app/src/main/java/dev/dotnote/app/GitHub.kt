package dev.dotnote.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Base64OutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

// Public OAuth application identifier; never an access token or secret.
const val DEFAULT_GITHUB_CLIENT_ID = "Ov23lisfSrWV5wQwk2we"

class GitHubFailure(val status: Int, message: String) : IOException(message)

class RemoteChanged :
    IOException(
        "This repository has newer changes. Restore it as another vault to review them; your local notes are safe."
    )

class Credentials(private val context: Context) {
    private val prefs = context.getSharedPreferences("github-credentials", 0)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("dotnote-github", null) as? SecretKey)?.let {
            return it
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                            "dotnote-github",
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
            }
            .generateKey()
    }

    fun read(): JSONObject =
        synchronized(lock) {
            val data = prefs.getString("encrypted", null) ?: return JSONObject()
            try {
                val packed = JSONObject(data)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    key(),
                    GCMParameterSpec(128, Base64.decode(packed.getString("iv"), Base64.NO_WRAP)),
                )
                JSONObject(
                    cipher
                        .doFinal(Base64.decode(packed.getString("data"), Base64.NO_WRAP))
                        .toString(Charsets.UTF_8)
                )
            } catch (e: Exception) {
                throw IOException(
                    "GitHub credentials cannot be read. Disconnect and sign in again.",
                    e,
                )
            }
        }

    fun save(value: JSONObject) =
        synchronized(lock) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val packed =
                JSONObject()
                    .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                    .put(
                        "data",
                        Base64.encodeToString(
                            cipher.doFinal(value.toString().toByteArray()),
                            Base64.NO_WRAP,
                        ),
                    )
            check(prefs.edit().putString("encrypted", packed.toString()).commit())
        }

    fun clear() {
        prefs.edit().clear().commit()
    }

    companion object {
        private val lock = Any()
    }
}

data class GitRepo(
    val name: String,
    val branch: String,
    val privateRepo: Boolean,
    val writable: Boolean,
)

data class DeviceLogin(
    val code: String,
    val userCode: String,
    val uri: String,
    val interval: Int,
    val expires: Int,
)

data class GitEntry(val path: String, val sha: String, val size: Long, val mode: String = "100644")

fun repoName(value: String): String {
    val name =
        value.trim().removePrefix("https://github.com/").removeSuffix("/").removeSuffix(".git")
    require(
        name.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) &&
            name.split('/').none { it == "." || it == ".." }
    ) {
        "Use an owner/repository name or a github.com repository URL"
    }
    return name
}

fun urlPart(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

/** All requests stay on GitHub's API; redirects never receive an authorization header. */
open class GitHub(private val token: String) {
    private fun connection(url: String, method: String, authenticated: Boolean): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 20000
        c.readTimeout = 45000
        c.instanceFollowRedirects = false
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "Dotnote-Android")
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        if (authenticated) c.setRequestProperty("Authorization", "Bearer $token")
        return c
    }

    private fun result(c: HttpURLConnection): String {
        try {
            val status = c.responseCode
            val stream = if (status in 200..299) c.inputStream else c.errorStream
            val body =
                stream?.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(16384)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        require(out.size() + n <= 32 * 1024 * 1024) {
                            "GitHub response is too large"
                        }
                        out.write(buffer, 0, n)
                    }
                    out.toString("UTF-8")
                } ?: ""
            if (status !in 200..299) {
                val explanation =
                    when (status) {
                        401 ->
                            "GitHub sign-in expired or was revoked. Reconnect in GitHub settings."
                        403 ->
                            "GitHub denied access or limited requests. Check repository permissions and try again later."
                        404 ->
                            "Repository or branch is unavailable. Check its name and GitHub access."
                        422 ->
                            "GitHub rejected this update. The branch may have changed or be protected."
                        else -> "GitHub request failed ($status). Try again."
                    }
                throw GitHubFailure(status, explanation)
            }
            return body
        } finally {
            c.disconnect()
        }
    }

    open fun request(path: String, method: String = "GET", body: JSONObject? = null): String {
        require(path.startsWith('/') && !path.contains(".."))
        val c = connection("https://api.github.com$path", method, true)
        if (body != null) {
            val bytes = body.toString().toByteArray()
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
        }
        return result(c)
    }

    fun oauth(endpoint: String, values: Map<String, String>): JSONObject {
        require(endpoint in setOf("device/code", "oauth/access_token"))
        val c = connection("https://github.com/login/$endpoint", "POST", false)
        val bytes =
            values.entries
                .joinToString("&") { urlPart(it.key) + "=" + urlPart(it.value) }
                .toByteArray()
        c.doOutput = true
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.setFixedLengthStreamingMode(bytes.size)
        try {
            c.outputStream.use { it.write(bytes) }
            return JSONObject(result(c))
        } finally {
            // Writing the POST body can fail before result() gets a chance to clean up.
            c.disconnect()
        }
    }

    fun user() = JSONObject(request("/user")).getString("login")

    fun repos(): List<GitRepo> {
        val result = mutableListOf<GitRepo>()
        fun append(a: JSONArray) {
            for (i in 0 until a.length()) {
                val r = a.getJSONObject(i)
                result.add(
                    GitRepo(
                        r.getString("full_name"),
                        r.optString("default_branch", "main").ifBlank { "main" },
                        r.getBoolean("private"),
                        r.optJSONObject("permissions")?.optBoolean("push", false) ?: false,
                    )
                )
            }
        }
        if (token.startsWith("ghu_")) {
            for (page in 1..20) {
                val installations =
                    JSONObject(request("/user/installations?per_page=100&page=$page"))
                        .getJSONArray("installations")
                for (i in 0 until installations.length()) {
                    val id = installations.getJSONObject(i).getLong("id")
                    for (p in 1..100) {
                        val a =
                            JSONObject(
                                    request(
                                        "/user/installations/$id/repositories?per_page=100&page=$p"
                                    )
                                )
                                .getJSONArray("repositories")
                        append(a)
                        if (a.length() < 100) break
                    }
                }
                if (installations.length() < 100) break
            }
        } else
            for (page in 1..100) {
                val a = JSONArray(request("/user/repos?per_page=100&sort=updated&page=$page"))
                append(a)
                if (a.length() < 100) break
            }
        return result.distinctBy { it.name }
    }

    open fun repo(name: String): GitRepo {
        val r = JSONObject(request("/repos/${repoName(name)}"))
        return GitRepo(
            r.getString("full_name"),
            r.optString("default_branch", "main").ifBlank { "main" },
            r.getBoolean("private"),
            r.optJSONObject("permissions")?.optBoolean("push", false) ?: false,
        )
    }

    open fun head(repo: String, branch: String): String? =
        try {
            JSONObject(request("/repos/$repo/git/ref/heads/${urlPart(branch)}"))
                .getJSONObject("object")
                .getString("sha")
        } catch (e: GitHubFailure) {
            if (e.status == 409 || e.status == 404) null else throw e
        }

    open fun tree(repo: String, commit: String): Pair<String, List<GitEntry>> {
        val tree =
            JSONObject(request("/repos/$repo/git/commits/$commit"))
                .getJSONObject("tree")
                .getString("sha")
        val data = JSONObject(request("/repos/$repo/git/trees/$tree?recursive=1"))
        require(!data.optBoolean("truncated")) { "Repository is too large to restore safely" }
        val a = data.getJSONArray("tree")
        val entries =
            (0 until a.length())
                .map { a.getJSONObject(it) }
                .filter { it.getString("type") != "tree" }
                .map {
                    require(it.getString("type") == "blob" && it.getString("mode") == "100644") {
                        "Linked files and submodules are not supported in a vault"
                    }
                    GitEntry(
                        it.getString("path"),
                        it.getString("sha"),
                        it.optLong("size", -1),
                        it.getString("mode"),
                    )
                }
        require(entries.size <= 10000) { "Vault contains too many files" }
        return tree to entries
    }

    open fun upload(repo: String, file: File): String {
        require(file.length() < 100L * 1024 * 1024) {
            "${file.name} exceeds GitHub's 100 MiB file limit. This backup was not uploaded."
        }
        val c = connection("https://api.github.com/repos/$repo/git/blobs", "POST", true)
        val prefix = "{\"encoding\":\"base64\",\"content\":\"".toByteArray()
        val suffix = "\"}".toByteArray()
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setFixedLengthStreamingMode(prefix.size + ((file.length() + 2) / 3) * 4 + suffix.size)
        c.outputStream.use { output ->
            output.write(prefix)
            val encoded = Base64OutputStream(output, Base64.NO_WRAP or Base64.NO_CLOSE)
            file.inputStream().use { it.copyTo(encoded) }
            encoded.close()
            output.write(suffix)
        }
        return JSONObject(result(c)).getString("sha")
    }

    open fun download(repo: String, entry: GitEntry, destination: File) {
        require(entry.size in 0 until 100L * 1024 * 1024)
        val c = connection("https://api.github.com/repos/$repo/git/blobs/${entry.sha}", "GET", true)
        c.setRequestProperty("Accept", "application/vnd.github.raw+json")
        try {
            if (c.responseCode !in 200..299) {
                result(c)
                return
            }
            destination.parentFile!!.mkdirs()
            destination.outputStream().use { output ->
                c.inputStream.use { input ->
                    val buffer = ByteArray(65536)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        require(total <= entry.size) { "Attachment size mismatch" }
                        output.write(buffer, 0, n)
                    }
                    require(total == entry.size) { "Incomplete download" }
                }
            }
            require(digest(destination, true) == entry.sha) { "Downloaded file checksum mismatch" }
        } finally {
            c.disconnect()
        }
    }
}

object GitHubAuth {
    private val lock = Any()

    fun token(context: Context): String =
        synchronized(lock) {
            val vault = Credentials(context)
            val value = vault.read()
            val token = value.optString("token")
            require(token.isNotBlank()) { "Connect GitHub in vault settings first" }
            if (value.optLong("expiresAt", Long.MAX_VALUE) > System.currentTimeMillis() + 60000)
                return token
            val refresh = value.optString("refresh")
            require(refresh.isNotBlank()) { "GitHub sign-in expired. Reconnect in settings." }
            val response =
                GitHub("")
                    .oauth(
                        "oauth/access_token",
                        mapOf(
                            "client_id" to value.getString("clientId"),
                            "refresh_token" to refresh,
                            "grant_type" to "refresh_token",
                        ),
                    )
            require(response.has("access_token")) {
                "GitHub needs you to reconnect in settings before backups can resume."
            }
            saveResponse(value, response)
            vault.save(value)
            value.getString("token")
        }

    fun saveResponse(target: JSONObject, response: JSONObject) {
        target.put("token", response.getString("access_token"))
        if (response.has("refresh_token"))
            target.put("refresh", response.getString("refresh_token"))
        if (response.has("expires_in"))
            target.put(
                "expiresAt",
                System.currentTimeMillis() + response.getLong("expires_in") * 1000,
            )
        else target.remove("expiresAt")
    }
}
