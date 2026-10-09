package com.cedrickgd.morgenbriefing.data

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.cedrickgd.morgenbriefing.data.model.AiProviderId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProvidersMigrationTest {
    private val anthropic = AiProviderId.ANTHROPIC
    private val limit = doublePreferencesKey("ai_monthly_limit_usd")
    private val liveAuto = booleanPreferencesKey("live_auto_refresh")
    private val theme = stringPreferencesKey("theme_mode")

    /** A v1.2 settings file with a Claude key saved untested and later refused. */
    private val v12 = preferencesOf(
        AiPrefKeys.LEGACY_KEY to "CIPHERTEXT==",
        AiPrefKeys.LEGACY_UNTESTED to true,
        AiPrefKeys.LEGACY_REJECTED to true,
        AiPrefKeys.LEGACY_MODEL to "OPUS",
        limit to 12.0,
        liveAuto to true,
        theme to "DARK",
    )

    @Test
    fun `the legacy key and its flags move into the Anthropic slot`() {
        val migrated = migrateAiPrefs(v12)

        assertEquals("CIPHERTEXT==", migrated[AiPrefKeys.keyEnc(anthropic)])
        assertEquals(true, migrated[AiPrefKeys.untested(anthropic)])
        assertEquals(true, migrated[AiPrefKeys.rejected(anthropic)])
        assertEquals("claude-opus-5-5", migrated[AiPrefKeys.model(anthropic)])
        assertEquals("anthropic", migrated[AiPrefKeys.PROVIDER])
        assertEquals(2, migrated[AiPrefKeys.SCHEMA])
        // The legacy keys are gone.
        assertNull(migrated[AiPrefKeys.LEGACY_KEY])
        assertNull(migrated[AiPrefKeys.LEGACY_UNTESTED])
        assertNull(migrated[AiPrefKeys.LEGACY_REJECTED])
        assertNull(migrated[AiPrefKeys.LEGACY_MODEL])
        // Global AI settings and everything else stay untouched.
        assertEquals(12.0, migrated[limit]!!, 0.0)
        assertEquals(true, migrated[liveAuto])
        assertEquals("DARK", migrated[theme])
        // No Live provider of its own: Live follows the chat.
        assertNull(migrated[AiPrefKeys.LIVE_PROVIDER])
    }

    @Test
    fun `the migrated settings read like the v1_2 ones`() {
        val ai = readAiSettings(migrateAiPrefs(v12), debugOverrides = false)
        assertEquals(anthropic, ai.provider)
        assertEquals(anthropic, ai.effectiveLiveProvider)
        val slot = ai.of(anthropic)
        assertTrue(slot.hasKey)
        assertTrue(slot.keyUntested)
        assertTrue(slot.keyRejected)
        assertEquals("claude-opus-5-5", slot.model)
        assertTrue(ai.isConfigured(anthropic))
        AiProviderId.entries.filter { it != anthropic }.forEach { assertFalse(ai.of(it).hasKey) }
    }

    @Test
    fun `old model names map to ids, anything else falls back to the default`() {
        fun modelAfter(legacy: String?): String? {
            val before = if (legacy == null) emptyPreferences() else preferencesOf(AiPrefKeys.LEGACY_MODEL to legacy)
            return migrateAiPrefs(before)[AiPrefKeys.model(anthropic)]
        }
        assertEquals("claude-sonnet-5-5", modelAfter("SONNET"))
        assertEquals("claude-opus-5-5", modelAfter("OPUS"))
        assertNull(modelAfter("HAIKU"))
        assertNull(modelAfter(""))
        assertNull(modelAfter(null))
    }

    @Test
    fun `a fresh install ends up with Anthropic and schema 2`() {
        val migrated = migrateAiPrefs(emptyPreferences())
        assertEquals("anthropic", migrated[AiPrefKeys.PROVIDER])
        assertEquals(2, migrated[AiPrefKeys.SCHEMA])
        assertNull(migrated[AiPrefKeys.keyEnc(anthropic)])
        assertEquals(2, migrated.asMap().size)
        assertFalse(readAiSettings(migrated, debugOverrides = false).isConfigured(anthropic))
    }

    @Test
    fun `flags without a key are not carried over`() {
        val migrated = migrateAiPrefs(preferencesOf(AiPrefKeys.LEGACY_KEY to "  ", AiPrefKeys.LEGACY_UNTESTED to true))
        assertNull(migrated[AiPrefKeys.keyEnc(anthropic)])
        assertNull(migrated[AiPrefKeys.untested(anthropic)])
        assertNull(migrated[AiPrefKeys.LEGACY_UNTESTED])
    }

    @Test
    fun `the migration is idempotent`() {
        val once = migrateAiPrefs(v12)
        assertEquals(once, migrateAiPrefs(once))
        val fresh = migrateAiPrefs(emptyPreferences())
        assertEquals(fresh, migrateAiPrefs(fresh))
    }

    @Test
    fun `a key in the new slot is never overwritten`() {
        val mixed = preferencesOf(
            AiPrefKeys.LEGACY_KEY to "OLD==",
            AiPrefKeys.LEGACY_MODEL to "OPUS",
            AiPrefKeys.keyEnc(anthropic) to "NEW==",
            AiPrefKeys.model(anthropic) to "claude-haiku-5-5",
            AiPrefKeys.PROVIDER to "gemini",
        )
        val migrated = migrateAiPrefs(mixed)
        assertEquals("NEW==", migrated[AiPrefKeys.keyEnc(anthropic)])
        assertNull(migrated[AiPrefKeys.untested(anthropic)])
        assertEquals("claude-haiku-5-5", migrated[AiPrefKeys.model(anthropic)])
        assertEquals("gemini", migrated[AiPrefKeys.PROVIDER])
        assertNull(migrated[AiPrefKeys.LEGACY_KEY])
    }

    @Test
    fun `the DataStore migration only runs below schema 2`() = runBlocking<Unit> {
        assertTrue(AiProvidersMigration.shouldMigrate(emptyPreferences()))
        assertTrue(AiProvidersMigration.shouldMigrate(v12))
        val migrated: Preferences = AiProvidersMigration.migrate(v12)
        assertFalse(AiProvidersMigration.shouldMigrate(migrated))
        assertEquals(migrateAiPrefs(v12), migrated)
    }
}
