package dev.dotnote.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpdateRulesTest {
    private val hash = "a".repeat(64)
    private fun release(name: String, preview: Boolean = false, draft: Boolean = false): JSONObject = JSONObject()
        .put("tag_name", "v$name").put("prerelease", preview).put("draft", draft)
        .put("assets", JSONArray().put(JSONObject().put("name", "dotnote-$name.apk").put("state", "uploaded")
            .put("browser_download_url", "https://github.com/yufengliu15/dotnote/releases/download/v$name/dotnote-$name.apk")))
    private fun manifest() = JSONObject().put("versionName", "0.11.0").put("versionCode", 19)
        .put("apkSha256", hash).put("certificateSha256", hash)
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
    }

    @Test fun numericalVersionsAndPreviewOptIn() {
        val releases = JSONArray().put(release("0.9.9")).put(release("0.12.0", true))
            .put(release("0.11.0")).put(release("0.13.0", draft = true))
        assertEquals("v0.11.0", UpdateRules.release(releases, "0.10.1", false)!!.getString("tag_name"))
        assertEquals("v0.12.0", UpdateRules.release(releases, "0.10.1", true)!!.getString("tag_name"))
        assertNull(UpdateRules.release(releases, "0.12.0", true))
        rejected { UpdateRules.version("../../bad") }
    }

    @Test fun releaseManifestMustAdvanceCodeAndMatchTag() {
        assertEquals(19L, UpdateRules.manifest(manifest(), release("0.11.0"), 18).code)
        rejected { UpdateRules.manifest(manifest(), release("0.11.0"), 19) }
        rejected { UpdateRules.manifest(manifest(), release("0.12.0"), 18) }
        rejected { UpdateRules.manifest(manifest().put("apkSha256", "bad"), release("0.11.0"), 18) }
    }

    @Test fun rejectsMissingDuplicateOrExternalAssets() {
        val r = release("0.11.0")
        rejected { UpdateRules.asset(r, "release-0.11.0.json") }
        val asset = r.getJSONArray("assets").getJSONObject(0)
        asset.put("browser_download_url", "https://example.com/app.apk")
        rejected { UpdateRules.asset(r, "dotnote-0.11.0.apk") }
        r.getJSONArray("assets").put(asset)
        rejected { UpdateRules.asset(r, "dotnote-0.11.0.apk") }
    }

    @Test fun redirectsStayOnHttpsGitHubHosts() {
        assertTrue(UpdateRules.allowedDownload("https://release-assets.githubusercontent.com/asset?token=x"))
        listOf("http://github.com/a", "https://github.com.evil.test/a", "https://evil.test/a",
            "https://user@github.com/a", "https://github.com:444/a").forEach { assertFalse(UpdateRules.allowedDownload(it)) }
    }

    @Test fun streamedDownloadIsBoundedAndCancelable() {
        val input = ByteArray(33_000) { 7 }
        val output = ByteArrayOutputStream()
        UpdateRules.copyBounded(ByteArrayInputStream(input), output, input.size.toLong())
        assertArrayEquals(input, output.toByteArray())
        rejected { UpdateRules.copyBounded(ByteArrayInputStream(input), ByteArrayOutputStream(), 32_999) }
        try {
            UpdateRules.copyBounded(ByteArrayInputStream(input), ByteArrayOutputStream(), 50_000) {
                throw java.util.concurrent.CancellationException()
            }
            fail("Expected cancellation")
        } catch (_: java.util.concurrent.CancellationException) { }
    }

    @Test fun rejectsWrongPackageCodeVersionOrCertificate() {
        val u = AppUpdate("0.11.0", 19, "", hash, hash)
        UpdateRules.validateIdentity(u, "dev.dotnote.app", 19, "0.11.0", setOf(hash), setOf(hash))
        rejected { UpdateRules.validateIdentity(u, "other.app", 19, "0.11.0", setOf(hash), setOf(hash)) }
        rejected { UpdateRules.validateIdentity(u, "dev.dotnote.app", 18, "0.11.0", setOf(hash), setOf(hash)) }
        rejected { UpdateRules.validateIdentity(u, "dev.dotnote.app", 19, "0.12.0", setOf(hash), setOf(hash)) }
        rejected { UpdateRules.validateIdentity(u, "dev.dotnote.app", 19, "0.11.0", setOf("b"), setOf(hash)) }
        rejected { UpdateRules.validateIdentity(u, "dev.dotnote.app", 19, "0.11.0", emptySet(), emptySet()) }
    }

    @Test fun releaseNotesCoverSkippedVersionsNewestFirst() {
        fun withBody(name: String, body: String, preview: Boolean = false) = release(name, preview).put("body", body)
        val releases = JSONArray()
            .put(withBody("0.14.0", "Dotnote 0.14.0 (Android build 25)\n\n- Old"))
            .put(withBody("0.16.0", "Dotnote 0.16.0 (Android build 27)\r\n\r\n- Show `what's new`\n  before **installing**.\n- Second\n\nBuilt from abc. Install the APK as an update; keep your existing app data.\n"))
            .put(withBody("0.15.0", "Dotnote 0.15.0 (Android build 26)\n\n- Recent row", preview = true))
            .put(withBody("0.17.0", "- Too new"))
            .put(release("0.15.5", draft = true).put("body", "- Draft"))
        val all = UpdateRules.notes(releases, "0.14.0", "0.16.0", previews = true)
        assertEquals(listOf("0.16.0", "0.15.0"), all.map { it.version })
        assertEquals(listOf("Show what's new before installing.", "Second"), all[0].items)
        assertEquals(listOf("Recent row"), all[1].items)
        assertEquals(listOf("0.16.0"), UpdateRules.notes(releases, "0.14.0", "0.16.0", previews = false).map { it.version })
        assertTrue(UpdateRules.noteItems("").isEmpty())
        assertEquals(UpdateRules.MAX_NOTE_ITEMS, UpdateRules.noteItems((1..30).joinToString("\n") { "- item $it" }).size)
    }
}
