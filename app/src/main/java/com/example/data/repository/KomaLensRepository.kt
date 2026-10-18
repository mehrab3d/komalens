package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.DetectedBubble
import com.example.data.model.GlossaryEntry
import com.example.data.model.TranslationHistoryItem
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class KomaLensRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private val json = Json { ignoreUnknownKeys = true }

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    // --- Glossary Operations ---

    fun observeGlossary(userId: String = requireUserId()): Flow<List<GlossaryEntry>> = flow {
        val path = "users/$userId/glossary"
        emitAll(
            db.collection("users").document(userId).collection("glossary")
                .whereEqualTo("userId", userId)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        GlossaryEntry::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                }
                .catch { error ->
                    if (error is Exception) {
                        handleFirestoreError(error, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun getGlossaryEntries(userId: String = requireUserId()): Result<List<GlossaryEntry>> {
        val path = "users/$userId/glossary"
        return try {
            val snapshot = db.collection("users").document(userId).collection("glossary")
                .whereEqualTo("userId", userId)
                .get()
                .await()
            val list = snapshot.toObjects(
                GlossaryEntry::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            ).sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
            Result.success(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, path)
            Result.failure(e)
        }
    }

    suspend fun getGlossaryById(targetUserId: String, entryId: String): Result<GlossaryEntry?> {
        val path = "users/$targetUserId/glossary/$entryId"
        return try {
            val doc = db.collection("users").document(targetUserId)
                .collection("glossary").document(entryId)
                .get()
                .await()
            Result.success(
                doc.toObject(
                    GlossaryEntry::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, path)
            Result.failure(e)
        }
    }

    suspend fun addGlossaryEntry(entry: GlossaryEntry): Result<String> {
        val uid = requireUserId()
        val colRef = db.collection("users").document(uid).collection("glossary")
        return try {
            val payload = entry.toCreateMap(uid)
            val docRef = colRef.add(payload).await()
            Result.success(docRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, colRef.path)
            Result.failure(e)
        }
    }

    suspend fun updateGlossaryEntry(entry: GlossaryEntry): Result<Unit> {
        val uid = requireUserId()
        require(entry.id.isNotBlank()) { "GlossaryEntry ID must not be blank for update" }
        val docRef = db.collection("users").document(uid).collection("glossary").document(entry.id)
        return try {
            val updates = mutableMapOf<String, Any>(
                "sourceTerm" to entry.sourceTerm.trim(),
                "targetTerm" to entry.targetTerm.trim(),
                "sourceLanguage" to entry.sourceLanguage,
                "seriesTitle" to entry.seriesTitle.trim().ifBlank { "General" },
                "category" to entry.category,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            entry.notes?.trim()?.takeIf { it.isNotEmpty() }?.let { updates["notes"] = it }
            docRef.update(updates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun deleteGlossaryEntry(entryId: String): Result<Unit> {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid).collection("glossary").document(entryId)
        return try {
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            Result.failure(e)
        }
    }

    // --- Translation History Operations ---

    fun observeHistory(userId: String = requireUserId()): Flow<List<TranslationHistoryItem>> = flow {
        val path = "users/$userId/history"
        emitAll(
            db.collection("users").document(userId).collection("history")
                .whereEqualTo("userId", userId)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        TranslationHistoryItem::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                }
                .catch { error ->
                    if (error is Exception) {
                        handleFirestoreError(error, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun saveTranslationHistory(
        pageTitle: String,
        sourceLanguage: String,
        sourceType: String,
        bubbles: List<DetectedBubble>
    ): Result<String> {
        val uid = requireUserId()
        val colRef = db.collection("users").document(uid).collection("history")
        return try {
            val rawPreview = bubbles.joinToString(" • ") { it.rawText }.take(4500)
            val translatedPreview = bubbles.joinToString(" • ") { it.activeTranslation }.take(4500)
            val bubblesPayload = json.encodeToString(bubbles)
            val item = TranslationHistoryItem(
                userId = uid,
                pageTitle = pageTitle,
                sourceLanguage = sourceLanguage,
                sourceType = sourceType,
                bubbleCount = bubbles.size.toLong(),
                rawPreview = rawPreview,
                translatedPreview = translatedPreview,
                bubblesPayload = bubblesPayload
            )
            val docRef = colRef.add(item.toCreateMap(uid)).await()
            Result.success(docRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, colRef.path)
            Result.failure(e)
        }
    }

    suspend fun deleteHistoryItem(historyId: String): Result<Unit> {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid).collection("history").document(historyId)
        return try {
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            Result.failure(e)
        }
    }

    fun decodeBubbles(payload: String): List<DetectedBubble> {
        return try {
            json.decodeFromString<List<DetectedBubble>>(payload)
        } catch (e: Exception) {
            emptyList()
        }
    }
}
