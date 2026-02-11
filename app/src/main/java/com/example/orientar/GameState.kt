package com.example.orientar

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore

data class Question(
    val id: Int,
    val title: String,
    val text: String,
    val cloudAnchorId: String = "",
    val modelFilePath: String,
    val modelScale: Float,
    val modelRotationX: Float,
    val modelRotationY: Float,
    val modelRotationZ: Float
)

object GameState {
    val questions: MutableList<Question> = mutableListOf()
    val bestTimes: MutableMap<Int, Long> = mutableMapOf()
    private val solvedIds = mutableSetOf<Int>()
    private var _totalTimeMs: Long = 0L

    val totalSolved: Int get() = solvedIds.size
    val totalTimeMs: Long get() = _totalTimeMs

    fun loadQuestionsFromFirestore(onComplete: () -> Unit) {
        FirebaseFirestore.getInstance().collection("treasure_questions").get()
            .addOnSuccessListener { snapshot ->
                questions.clear()
                for (doc in snapshot.documents) {
                    val id = doc.getLong("id")?.toInt() ?: continue
                    questions.add(Question(
                        id = id,
                        title = doc.getString("title") ?: "",
                        text = doc.getString("text") ?: "",
                        cloudAnchorId = doc.getString("cloudAnchorId") ?: "",
                        modelFilePath = doc.getString("modelFilePath") ?: "",
                        modelScale = doc.getDouble("modelScale")?.toFloat() ?: 1f,
                        modelRotationX = doc.getDouble("modelRotationX")?.toFloat() ?: 0f,
                        modelRotationY = doc.getDouble("modelRotationY")?.toFloat() ?: 0f,
                        modelRotationZ = doc.getDouble("modelRotationZ")?.toFloat() ?: 0f
                    ))
                }
                questions.sortBy { it.id }
                onComplete()
            }
            .addOnFailureListener { onComplete() }
    }

    fun markSolved(questionId: Int, elapsedMs: Long) {
        if (solvedIds.add(questionId)) { _totalTimeMs += elapsedMs }
        val prev = bestTimes[questionId]
        if (prev == null || elapsedMs < prev) { bestTimes[questionId] = elapsedMs }
    }

    fun nextUnsolved(): Question? = questions.firstOrNull { !solvedIds.contains(it.id) }

    fun nextUnsolvedAfter(currentId: Int): Question? {
        val startIndex = questions.indexOfFirst { it.id == currentId }
        for (i in (startIndex + 1) until questions.size) {
            if (!solvedIds.contains(questions[i].id)) return questions[i]
        }
        return null
    }
}