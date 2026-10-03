package dev.dotnote.app

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class AppUpdate(
    val version: String,
    val code: Long,
    val url: String,
    val sha256: String,
    val certificate: String,
)

internal object UpdateRules {
    const val REPOSITORY = "yufengliu15/dotnote"
    const val MAX_APK_BYTES = 150L * 1024 * 1024
    private val versionPattern = Regex("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}")
    private val hashPattern = Regex("[0-9a-f]{64}")

    fun version(value: String): List<Int> {
        require(versionPattern.matches(value)) { "Invalid update version." }
        return value.split('.').map(String::toInt)
    }

    fun newer(candidate: String, installed: String): Boolean {
        val a = version(candidate)
        val b = version(installed)
        for (i in a.indices) if (a[i] != b[i]) return a[i] > b[i]
        return false
    }

    fun release(releases: JSONArray, installed: String, previews: Boolean): JSONObject? =
        (0 until releases.length()).map { releases.getJSONObject(it) }
            .filter { !it.optBoolean("draft") && (previews || !it.optBoolean("prerelease")) }
            .filter { versionPattern.matches(it.optString("tag_name").removePrefix("v")) }
            .filter { newer(it.getString("tag_name").removePrefix("v"), installed) }
            .maxWithOrNull { a, b ->
                val av = a.getString("tag_name").removePrefix("v")
                val bv = b.getString("tag_name").removePrefix("v")
                when { av == bv -> 0; newer(av, bv) -> 1; else -> -1 }
            }

    fun asset(release: JSONObject, name: String): String {
        val assets = release.getJSONArray("assets")
        val matches = (0 until assets.length()).map { assets.getJSONObject(it) }
            .filter { it.optString("name") == name && it.optString("state") == "uploaded" }
        require(matches.size == 1) { "This release is missing its verified update files." }
        val url = matches.single().getString("browser_download_url")
        val expected = "https://github.com/$REPOSITORY/releases/download/${release.getString("tag_name")}/$name"
        require(url == expected) { "Unexpected update download address." }
        return url
    }

    fun manifest(json: JSONObject, release: JSONObject, installedCode: Long): AppUpdate {
        val name = json.getString("versionName")
        version(name)
        require(release.getString("tag_name") == "v$name") { "Release version does not match its update files." }
        val code = json.getLong("versionCode")
        require(code > installedCode) { "This release is not newer than your installed build." }
        val hash = json.getString("apkSha256")
        val certificate = json.getString("certificateSha256")
        require(hashPattern.matches(hash) && hashPattern.matches(certificate)) { "Invalid update verification data." }
        return AppUpdate(name, code, asset(release, "dotnote-$name.apk"), hash, certificate)
    }

    fun allowedDownload(url: String): Boolean {
        val uri = URI(url)
        return uri.scheme == "https" && uri.userInfo == null && (uri.port == -1 || uri.port == 443) &&
            uri.host in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
    }

    fun copyBounded(input: InputStream, output: OutputStream, limit: Long, checkActive: () -> Unit = {}) {
        val buffer = ByteArray(32 * 1024)
        var count = 0L
        while (true) {
            checkActive()
            val n = input.read(buffer)
            if (n < 0) break
            count += n
            require(count <= limit) { "Update download exceeded its size limit." }
            output.write(buffer, 0, n)
        }
    }

    fun validateIdentity(update: AppUpdate, packageName: String, code: Long, name: String?,
                         signers: Set<String>, installedSigners: Set<String>) {
        require(packageName == "dev.dotnote.app" && code == update.code && name == update.version) {
            "Downloaded APK does not match the update."
        }
        require(signers.isNotEmpty() && signers == installedSigners && signers == setOf(update.certificate)) {
            "This update uses a different signing key and cannot safely replace Dotnote."
        }
    }
}

internal class AppUpdates(private val context: Context) {
    private suspend fun <T> download(url: String, consume: suspend (InputStream) -> T): T {
        var next = url
        repeat(6) {
            currentCoroutineContext().ensureActive()
            require(UpdateRules.allowedDownload(next)) { "Untrusted update download address." }
            val connection = URI(next).toURL().openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("User-Agent", "Dotnote/${BuildConfig.VERSION_NAME}")
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    next = URI(next).resolve(connection.getHeaderField("Location") ?: error("Missing download redirect.")).toString()
                } else {
                    check(status == 200) {
                        if (status == 403 || status == 429) "GitHub is limiting update checks. Try again later."
                        else "Could not download update information (HTTP $status). Try again later."
                    }
                    return connection.inputStream.use { consume(it) }
                }
            } finally { connection.disconnect() }
        }
        error("Too many update download redirects.")
    }

    private suspend fun text(url: String): String = download(url) { input ->
        val coroutine = currentCoroutineContext()
        java.io.ByteArrayOutputStream().use { output ->
            UpdateRules.copyBounded(input, output, 2L * 1024 * 1024) { coroutine.ensureActive() }
            output.toString("UTF-8")
        }
    }

    suspend fun check(previews: Boolean): AppUpdate? = withContext(Dispatchers.IO) {
        val releases = JSONArray(text("https://api.github.com/repos/${UpdateRules.REPOSITORY}/releases?per_page=100"))
        val release = UpdateRules.release(releases, BuildConfig.VERSION_NAME, previews) ?: return@withContext null
        val version = release.getString("tag_name").removePrefix("v")
        UpdateRules.manifest(JSONObject(text(UpdateRules.asset(release, "release-$version.json"))), release, BuildConfig.VERSION_CODE.toLong())
    }

    suspend fun fetch(update: AppUpdate): File = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val partial = File.createTempFile("download-", ".part", directory)
        try {
            download(update.url) { input ->
                val coroutine = currentCoroutineContext()
                partial.outputStream().use { output ->
                    UpdateRules.copyBounded(input, output, UpdateRules.MAX_APK_BYTES) { coroutine.ensureActive() }
                }
            }
            verify(partial, update)
            currentCoroutineContext().ensureActive()
            val ready = File(directory, "dotnote-${update.version}.apk")
            check(partial.renameTo(ready)) { "Could not prepare the update file." }
            directory.listFiles()?.filter { it != ready }?.forEach { it.delete() }
            ready
        } finally { partial.delete() }
    }

    @Suppress("DEPRECATION")
    suspend fun verify(file: File, update: AppUpdate) = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        require(digest.digest().hex() == update.sha256) { "Update verification failed. Download it again." }
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: error("The downloaded APK is invalid.")
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        require(archive.longVersionCode > installed.longVersionCode) { "This update is already installed or older." }
        fun signers(info: android.content.pm.PackageInfo) = info.signingInfo?.apkContentsSigners
            ?.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }?.toSet().orEmpty()
        UpdateRules.validateIdentity(update, archive.packageName, archive.longVersionCode, archive.versionName,
            signers(archive), signers(installed))
    }

    fun installer(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            clipData = ClipData.newRawUri("Dotnote update", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
