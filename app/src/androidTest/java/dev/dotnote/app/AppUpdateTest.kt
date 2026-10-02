package dev.dotnote.app

import android.content.Intent
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppUpdateTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun installerGrantsOnlyUpdateCacheReadAccess() {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val apk = File(directory, "test-provider.apk").apply { writeText("fixture") }
        val privateNote = File(context.filesDir, "update-provider-test.dotnote").apply { writeText("private") }
        try {
            val intent = AppUpdates(context).installer(apk)
            assertEquals("content", intent.data!!.scheme)
            assertEquals("${context.packageName}.updates", intent.data!!.authority)
            assertEquals("application/vnd.android.package-archive", intent.type)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            assertEquals("fixture", context.contentResolver.openInputStream(intent.data!!)!!.bufferedReader().use { it.readText() })
            try {
                FileProvider.getUriForFile(context, "${context.packageName}.updates", privateNote)
                fail("Provider exposed private files")
            } catch (_: IllegalArgumentException) { }
        } finally { apk.delete(); privateNote.delete() }
    }

    @Test fun rejectsCorruptedAndAlreadyInstalledApks() = runBlocking {
        val updater = AppUpdates(context)
        val installed = File(context.applicationInfo.sourceDir)
        val digest = MessageDigest.getInstance("SHA-256")
        installed.inputStream().use { input ->
            val buffer = ByteArray(32768)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        val update = AppUpdate(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong(), "", hash, "a".repeat(64))
        try { updater.verify(installed, update); fail("Same version accepted") }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("already installed")) }
        try { updater.verify(installed, update.copy(sha256 = "0".repeat(64))); fail("Wrong digest accepted") }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("verification failed")) }
    }
}
