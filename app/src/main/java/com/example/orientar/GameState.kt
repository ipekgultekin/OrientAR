package com.example.orientar

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore

data class Question(
    val id: Int,
    val title: String,
    val text: String,
    val answerImageName: String,
    val answerImageAssetPath: String,
    val modelFilePath: String,
    val modelScale: Float,
    val modelRotationX: Float,
    val modelRotationY: Float,
    val targetKeywords: List<String>
)

object GameState {

    val questions: MutableList<Question> = mutableListOf()

    // best time per question (ms)
    val bestTimes: MutableMap<Int, Long> = mutableMapOf()

    private val solvedIds = mutableSetOf<Int>()
    private var _totalTimeMs: Long = 0L

    val totalSolved: Int get() = solvedIds.size
    val totalTimeMs: Long get() = _totalTimeMs

    fun totalQuestions(): Int = questions.size

    fun markSolved(questionId: Int, elapsedMs: Long) {
        // first time solved => count towards totals
        if (solvedIds.add(questionId)) {
            _totalTimeMs += elapsedMs
        }

        // best time: keep minimum
        val prev = bestTimes[questionId]
        if (prev == null || elapsedMs < prev) {
            bestTimes[questionId] = elapsedMs
        }
    }

    fun isSolved(id: Int): Boolean = solvedIds.contains(id)

    fun nextUnsolved(): Question? =
        questions.firstOrNull { !solvedIds.contains(it.id) }

    fun getNextQuestionInList(currentId: Int): Question? {
        val index = questions.indexOfFirst { it.id == currentId }
        return if (index != -1 && index < questions.size - 1) questions[index + 1] else null
    }

    fun resetProgress() {
        solvedIds.clear()
        _totalTimeMs = 0L
        bestTimes.clear()
    }

    fun loadQuestionsFromFirestore(onComplete: () -> Unit) {
        FirebaseFirestore.getInstance()
            .collection("treasure_questions")
            .get()
            .addOnSuccessListener { snapshot ->

                questions.clear()

                Log.d("GameState", "Firestore documents count: ${snapshot.size()}")

                for (doc in snapshot.documents) {

                    val idLong = doc.getLong("id")
                    if (idLong == null) {
                        Log.w("GameState", "Skipped doc (missing id): ${doc.id}")
                        continue
                    }
                    val id = idLong.toInt()

                    val title = doc.getString("title")
                    if (title == null) {
                        Log.w("GameState", "Skipped doc (missing title) id=$id doc=${doc.id}")
                        continue
                    }

                    val text = doc.getString("text") ?: ""
                    val answerImageName = doc.getString("answerImageName") ?: ""
                    val answerImageAssetPath = doc.getString("answerImageAssetPath") ?: ""
                    val modelFilePath = doc.getString("modelFilePath") ?: ""

                    val modelScale = doc.getDouble("modelScale")?.toFloat() ?: 1f
                    val modelRotationX = doc.getDouble("modelRotationX")?.toFloat() ?: 0f
                    val modelRotationY = doc.getDouble("modelRotationY")?.toFloat() ?: 0f

                    val targetKeywords = (doc.get("targetKeywords") as? List<*>)
                        ?.mapNotNull { it as? String }
                        ?: emptyList()

                    questions.add(
                        Question(
                            id = id,
                            title = title,
                            text = text,
                            answerImageName = answerImageName,
                            answerImageAssetPath = answerImageAssetPath,
                            modelFilePath = modelFilePath,
                            modelScale = modelScale,
                            modelRotationX = modelRotationX,
                            modelRotationY = modelRotationY,
                            targetKeywords = targetKeywords
                        )
                    )
                }

                questions.sortBy { it.id }

                Log.d("GameState", "Loaded questions: ${questions.size}")
                if (questions.isEmpty()) {
                    Log.w("GameState", "No questions found in Firestore collection: treasure_questions")
                }

                onComplete()
            }
            .addOnFailureListener { e ->
                Log.e("GameState", "Firestore load FAILED", e)
                onComplete()
            }
    }
}
