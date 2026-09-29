package dev.dotnote.app

import org.junit.Assert.*
import org.junit.Test

class VaultRulesTest {
    @Test
    fun editBasedDelayResetsAndClampsAtDueTime() {
        assertEquals(15 * 60_000L, backupDelayMillis(10_000, 15, 10_000))
        assertEquals(6 * 60 * 60_000L, backupDelayMillis(10_000, 360, 10_000))
        assertEquals(0, backupDelayMillis(0, 15, 2_000_000))
        assertTrue(backupDelayMillis(500_000, 15, 600_000) > backupDelayMillis(0, 15, 600_000))
        assertTrue(runCatching { backupDelayMillis(0, 14, 0) }.isFailure)
        assertTrue(runCatching { backupDelayMillis(0, 361, 0) }.isFailure)
    }

    @Test
    fun remoteAndLocalPathsAreRestrictedToVaultData() {
        assertEquals("me/notes", repoName("https://github.com/me/notes.git"))
        assertTrue(runCatching { repoName("https://evil.example/me/notes") }.isFailure)
        assertTrue(runCatching { repoName("../notes") }.isFailure)
        assertTrue(managedVaultPath("Physics/lecture.dotnote"))
        assertTrue(managedVaultPath(".dotnote/vault.json"))
        assertTrue(managedVaultPath("attachments/abcdef.pdf"))
        listOf(
                "../credentials",
                "/absolute.dotnote",
                "a/../../b.dotnote",
                "a\\b.dotnote",
                ".dotnote/transaction.json",
                ".github/workflows/code.yml",
                "attachments/a/escape.pdf",
            )
            .forEach { assertFalse(it, managedVaultPath(it)) }
    }
}
