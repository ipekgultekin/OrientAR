package com.example.orientar.treasure

import android.util.Log
import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

data class Question(
    val id: Int,
    val title: String,
    val text: String,
    val cloudAnchorId: String = "",
    val cloudAnchorIds: List<String> = emptyList(),
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
    private var loadedForUid: String? = null  // track which user's data is in memory

    val totalSolved: Int get() = solvedIds.size
    val totalTimeMs: Long get() = _totalTimeMs

    fun totalQuestions(): Int = questions.size

    // SharedPreferences name is per-user so users never share progress
    private fun prefsName(): String {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: "guest"
        return "treasure_hunt_progress_$uid"
    }

    // Call this on login/logout to wipe in-memory state
    fun clearMemory() {
        solvedIds.clear()
        bestTimes.clear()
        _totalTimeMs = 0L
        loadedForUid = null
    }

    fun loadProgress(context: Context) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid

        // If different user than what is in memory, wipe first
        if (uid != loadedForUid) {
            solvedIds.clear()
            bestTimes.clear()
            _totalTimeMs = 0L
            loadedForUid = uid
        }

        val prefs = context.getSharedPreferences(prefsName(), Context.MODE_PRIVATE)
        solvedIds.clear()
        val savedIds = prefs.getString("solved_ids", "") ?: ""
        if (savedIds.isNotEmpty()) {
            savedIds.split(",").mapNotNull { it.trim().toIntOrNull() }.forEach { solvedIds.add(it) }
        }
        _totalTimeMs = prefs.getLong("total_time_ms", 0L)
        bestTimes.clear()
        questions.forEach { q ->
            val t = prefs.getLong("best_time_${q.id}", -1L)
            if (t >= 0) bestTimes[q.id] = t
        }
    }

    private fun saveProgress(context: Context) {
        val prefs = context.getSharedPreferences(prefsName(), Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("solved_ids", solvedIds.joinToString(","))
            putLong("total_time_ms", _totalTimeMs)
            bestTimes.forEach { (id, time) -> putLong("best_time_$id", time) }
            apply()
        }
    }

    fun resetProgress(context: Context? = null) {
        solvedIds.clear()
        bestTimes.clear()
        _totalTimeMs = 0L
        context?.getSharedPreferences(prefsName(), Context.MODE_PRIVATE)?.edit()?.clear()?.apply()
    }

    fun clearQuestions() {
        questions.clear()
    }

    fun loadQuestionsFromFirestore(onComplete: () -> Unit) {
        Log.d("TH_FIRESTORE", "Loading treasure_questions from Firestore")

        FirebaseFirestore.getInstance().collection("treasure_questions").get()
            .addOnSuccessListener { snapshot ->
                questions.clear()

                Log.d("TH_FIRESTORE", "Firestore returned ${snapshot.documents.size} treasure question documents")

                for (doc in snapshot.documents) {
                    val id = doc.getLong("id")?.toInt()
                    if (id == null) {
                        Log.e("TH_FIRESTORE", "Skipping document ${doc.id}: missing id")
                        continue
                    }

                    val keywords = doc.get("targetKeywords") as? List<String> ?: emptyList()
                    val anchorIds = doc.get("cloudAnchorIds") as? List<String> ?: emptyList()

                    val question = Question(
                        id = id,
                        title = doc.getString("title") ?: "",
                        text = doc.getString("text") ?: "",
                        cloudAnchorId = doc.getString("cloudAnchorId") ?: "",
                        cloudAnchorIds = anchorIds,
                        modelFilePath = doc.getString("modelFilePath") ?: "",
                        modelScale = doc.getDouble("modelScale")?.toFloat() ?: 1f,
                        modelRotationX = doc.getDouble("modelRotationX")?.toFloat() ?: 0f,
                        modelRotationY = doc.getDouble("modelRotationY")?.toFloat() ?: 0f,
                        modelRotationZ = doc.getDouble("modelRotationZ")?.toFloat() ?: 0f,
                        targetKeywords = keywords
                    )

                    Log.d(
                        "TH_FIRESTORE",
                        "Loaded question: doc=${doc.id}, id=${question.id}, title='${question.title}', hasAnchor=${question.cloudAnchorId.isNotEmpty()}, keywords=${question.targetKeywords.size}, model='${question.modelFilePath}'"
                    )

                    questions.add(question)
                }

                questions.sortBy { it.id }
                Log.d("TH_FIRESTORE", "Questions sorted. order=${questions.map { it.id }}")

                onComplete()
            }
            .addOnFailureListener { e ->
                Log.e("TH_FIRESTORE", "Failed to load treasure questions", e)
                onComplete()
            }
    }

    fun markSolved(questionId: Int, elapsedMs: Long, context: Context? = null) {
        if (solvedIds.add(questionId)) { _totalTimeMs += elapsedMs }
        val prev = bestTimes[questionId]
        if (prev == null || elapsedMs < prev) { bestTimes[questionId] = elapsedMs }
        context?.let { saveProgress(it) }
    }

    fun nextUnsolved(): Question? = questions.firstOrNull { !solvedIds.contains(it.id) }

    fun nextUnsolvedAfter(currentId: Int): Question? {
        val startIndex = questions.indexOfFirst { it.id == currentId }
        for (i in (startIndex + 1) until questions.size) {
            if (!solvedIds.contains(questions[i].id)) return questions[i]
        }
        for (i in 0 until startIndex) {
            if (!solvedIds.contains(questions[i].id)) return questions[i]
        }
        return null
    }
}