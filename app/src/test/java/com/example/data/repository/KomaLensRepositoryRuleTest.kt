package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.DetectedBubble
import com.example.data.model.GlossaryEntry
import com.google.common.truth.Truth.assertThat
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.fail
import org.junit.Test

class KomaLensRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun addAndObserveGlossary_authenticatedOwner_succeeds() = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        val repository = KomaLensRepository(firestore, auth)

        val entry = GlossaryEntry(
            sourceTerm = "領域展開",
            targetTerm = "Domain Expansion",
            sourceLanguage = "JA",
            seriesTitle = "Jujutsu Kaisen",
            category = "Skill",
            notes = "Supreme barrier technique"
        )

        val addResult = withTimeout(DEFAULT_TIMEOUT_MS) { repository.addGlossaryEntry(entry) }
        assertThat(addResult.isSuccess).isTrue()
        val entryId = addResult.getOrThrow()
        assertThat(entryId).isNotEmpty()

        val emitted = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeGlossary(uid).first { list -> list.any { it.id == entryId } }
        }
        assertThat(emitted.map { it.targetTerm }).contains("Domain Expansion")
    }

    @Test
    fun saveAndObserveHistory_authenticatedOwner_succeeds() = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        val repository = KomaLensRepository(firestore, auth)

        val bubbles = listOf(
            DetectedBubble(
                id = "b1",
                index = 1,
                rawText = "일어나라",
                sourceLanguage = "KO",
                mlKitTranslation = "Arise",
                geminiLocalizedText = "Arise."
            )
        )

        val saveResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.saveTranslationHistory(
                pageTitle = "Solo Leveling Ch. 110",
                sourceLanguage = "KO",
                sourceType = "ScreenOverlay",
                bubbles = bubbles
            )
        }
        assertThat(saveResult.isSuccess).isTrue()
        val historyId = saveResult.getOrThrow()

        val emitted = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeHistory(uid).first { list -> list.any { it.id == historyId } }
        }
        assertThat(emitted.first { it.id == historyId }.pageTitle).isEqualTo("Solo Leveling Ch. 110")
    }

    @Test
    fun getGlossaryById_crossUserAccess_failsWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val aliceRepo = KomaLensRepository(firestore, auth)
        val entryId = withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.addGlossaryEntry(
                GlossaryEntry(
                    sourceTerm = "성진우",
                    targetTerm = "Sung Jin-Woo",
                    sourceLanguage = "KO",
                    seriesTitle = "Solo Leveling",
                    category = "Character"
                )
            ).getOrThrow()
        }

        signInTestUser(BOB_EMAIL)
        val bobRepo = KomaLensRepository(firestore, auth)
        val crossResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            bobRepo.getGlossaryById(aliceUid, entryId)
        }
        assertThat(crossResult.isFailure).isTrue()
        val ex = crossResult.exceptionOrNull() as? FirebaseFirestoreException
        assertThat(ex?.code).isEqualTo(FirebaseFirestoreException.Code.PERMISSION_DENIED)
    }

    @Test
    fun observeGlossary_unauthenticatedUser_failsWithPermissionDenied() = runBlocking {
        auth.signOut()
        val repository = KomaLensRepository(firestore, auth)

        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                repository.observeGlossary("unauth_test_user").first()
            }
            fail("Expected FirebaseFirestoreException PERMISSION_DENIED")
        } catch (e: Throwable) {
            val firestoreEx = generateSequence(e) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            assertThat(firestoreEx).isNotNull()
            assertThat(firestoreEx?.code).isEqualTo(FirebaseFirestoreException.Code.PERMISSION_DENIED)
        }
    }

    private companion object {
        const val ALICE_EMAIL = "alice_koma@test.com"
        const val BOB_EMAIL = "bob_koma@test.com"
        const val DEFAULT_TIMEOUT_MS = 10000L
        const val FLOW_TIMEOUT_MS = 10000L
    }
}
