package com.example.orientar.treasure

import android.content.Context
import android.content.SharedPreferences
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
    val modelRotationZ: Float,
    val targetKeywords: List<String> = emptyList()
)

object GameState {
    val questions: MutableList<Question> = mutableListOf()
    val bestTimes: MutableMap<Int, Long> = mutableMapOf()
    private val solvedIds = mutableSetOf<Int>()
    private var _totalTimeMs: Long = 0L

    val totalSolved: Int get() = solvedIds.size
    val totalTimeMs: Long get() = _totalTimeMs

    fun totalQuestions(): Int = questions.size

    // SharedPreferences keys
    private const val PREFS_NAME = "treasure_hunt_progress"
    private const val KEY_SOLVED_IDS = "solved_ids"
    private const val KEY_TOTAL_TIME = "total_time_ms"
    private const val KEY_BEST_TIME_PREFIX = "best_time_"

    // Load saved progress from SharedPreferences into memory
    fun loadProgress(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        solvedIds.clear()
        val savedIds = prefs.getString(KEY_SOLVED_IDS, "") ?: ""
        if (savedIds.isNotEmpty()) {
            savedIds.split(",").mapNotNull { it.trim().toIntOrNull() }.forEach { solvedIds.add(it) }
        }

        _totalTimeMs = prefs.getLong(KEY_TOTAL_TIME, 0L)

        bestTimes.clear()
        // Load best times for all known question IDs
        questions.forEach { q ->
            val t = prefs.getLong("$KEY_BEST_TIME_PREFIX${q.id}", -1L)
            if (t >= 0) bestTimes[q.id] = t
        }
    }

    // Save current progress to SharedPreferences
    private fun saveProgress(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString(KEY_SOLVED_IDS, solvedIds.joinToString(","))
            putLong(KEY_TOTAL_TIME, _totalTimeMs)
            bestTimes.forEach { (id, time) ->
                putLong("$KEY_BEST_TIME_PREFIX$id", time)
            }
            apply()
        }
    }

    // Reset everything — called only on "Play Again"
    fun resetProgress(context: Context? = null) {
        solvedIds.clear()
        bestTimes.clear()
        _totalTimeMs = 0L
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()?.clear()?.apply()
    }

    fun loadQuestionsFromFirestore(onComplete: () -> Unit) {
        FirebaseFirestore.getInstance().collection("treasure_questions").get()
            .addOnSuccessListener { snapshot ->
                questions.clear()
                for (doc in snapshot.documents) {
                    val id = doc.getLong("id")?.toInt() ?: continue
                    val keywords = doc.get("targetKeywords") as? List<String> ?: emptyList()
                    questions.add(
                        Question(
                            id = id,
                            title = doc.getString("title") ?: "",
                            text = doc.getString("text") ?: "",
                            cloudAnchorId = doc.getString("cloudAnchorId") ?: "",
                            modelFilePath = doc.getString("modelFilePath") ?: "",
                            modelScale = doc.getDouble("modelScale")?.toFloat() ?: 1f,
                            modelRotationX = doc.getDouble("modelRotationX")?.toFloat() ?: 0f,
                            modelRotationY = doc.getDouble("modelRotationY")?.toFloat() ?: 0f,
                            modelRotationZ = doc.getDouble("modelRotationZ")?.toFloat() ?: 0f,
                            targetKeywords = keywords
                        )
                    )
                }
                questions.sortBy { it.id }
                onComplete()
            }
            .addOnFailureListener { onComplete() }
    }

    fun markSolved(questionId: Int, elapsedMs: Long, context: Context? = null) {
        if (solvedIds.add(questionId)) { _totalTimeMs += elapsedMs }
        val prev = bestTimes[questionId]
        if (prev == null || elapsedMs < prev) { bestTimes[questionId] = elapsedMs }
        context?.let { saveProgress(it) }
    }

    fun nextUnsolved(): Question? = questions.firstOrNull { !solvedIds.contains(it.id) }

    fun nextUnsolvedAfter(currentId: Int): Question? {
        // First look after current question
        val startIndex = questions.indexOfFirst { it.id == currentId }
        for (i in (startIndex + 1) until questions.size) {
            if (!solvedIds.contains(questions[i].id)) return questions[i]
        }
        // Then wrap around and look before current question too
        for (i in 0 until startIndex) {
            if (!solvedIds.contains(questions[i].id)) return questions[i]
        }
        return null
    }
}