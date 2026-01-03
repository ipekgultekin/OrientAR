package com.example.orientar

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore //firestore lib

data class Question(
    val id: Int,
    val title: String, // Short display title
    val text: String, // The actual riddle/clue text shown to the user
    val answerImageName: String, // logical name of the answer image
    val answerImageAssetPath: String, // asset path for answer image
    val modelFilePath: String, // 3D model asset path used in AR when the answer is detected
    val modelScale: Float, // Scale tuning for each model (since GLB files can have different native sizes)
    val modelRotationX: Float, // Per-question model rotation tuning (helps face the camera correctly)
    val modelRotationY: Float,
    val modelRotationZ: Float,
    val targetKeywords: List<String> // List of acceptable keywords/phrases that can trigger the "correct" state
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

    fun isSolved(id: Int): Boolean = solvedIds.contains(id) //Convenience check: has a question been solved?

    fun nextUnsolved(): Question? = //Returns the first question that is not solved yet
        questions.firstOrNull { !solvedIds.contains(it.id) }

    fun getNextQuestionInList(currentId: Int): Question? { //Returns the next question in list order after the given question ID
        val index = questions.indexOfFirst { it.id == currentId }
        return if (index != -1 && index < questions.size - 1) questions[index + 1] else null
    }

    fun resetProgress() { //Resets all progress for "Play Again"
        solvedIds.clear()
        _totalTimeMs = 0L
        bestTimes.clear()
    }

    fun loadQuestionsFromFirestore(onComplete: () -> Unit) { //Loads questions from Firestore collection: "treasure_questions"
        FirebaseFirestore.getInstance() // gets firestore connection(json+initializeapp)
            .collection("treasure_questions")//our question data collection in firestore
            .get()//read collection
            //if succesfull
            .addOnSuccessListener { snapshot ->

                questions.clear() // Clear any previous items before adding new ones

                Log.d("GameState", "Firestore documents count: ${snapshot.size()}")

                for (doc in snapshot.documents) { //read each document individually to convert it into a Question object.

                    val idLong = doc.getLong("id") //reads firestore field
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

                    // we default to empty string so the app won't crash
                    val text = doc.getString("text") ?: ""
                    val answerImageName = doc.getString("answerImageName") ?: ""
                    val answerImageAssetPath = doc.getString("answerImageAssetPath") ?: ""
                    val modelFilePath = doc.getString("modelFilePath") ?: ""

                    //Firestore numeric values are typically Double when read with getDouble()
                    val modelScale = doc.getDouble("modelScale")?.toFloat() ?: 1f
                    val modelRotationX = doc.getDouble("modelRotationX")?.toFloat() ?: 0f
                    val modelRotationY = doc.getDouble("modelRotationY")?.toFloat() ?: 0f
                    val modelRotationZ = doc.getDouble("modelRotationZ")?.toFloat() ?: 0f


                    val targetKeywords = (doc.get("targetKeywords") as? List<*>) //We read target keywords as List<*> then map only String values safely
                        ?.mapNotNull { it as? String }
                        ?: emptyList()

                    questions.add( // Construct Question object and add into list
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
                            modelRotationZ = modelRotationZ,
                            targetKeywords = targetKeywords
                        )
                    )
                }

                questions.sortBy { it.id } // Sort by question id to maintain stable game order

                // Logs help debug cases like empty collection or schema mismatch
                Log.d("GameState", "Loaded questions: ${questions.size}")
                if (questions.isEmpty()) {
                    Log.w("GameState", "No questions found in Firestore collection: treasure_questions")
                }

                onComplete() // Notify caller
            }
            .addOnFailureListener { e -> //failure msg
                Log.e("GameState", "Firestore load FAILED", e)
                onComplete()
            }
    }

    fun nextUnsolvedAfter(currentId: Int): Question? {
        val startIndex = questions.indexOfFirst { it.id == currentId }
        if (startIndex == -1) return nextUnsolved()

        for (i in (startIndex + 1) until questions.size) {
            val q = questions[i]
            if (!isSolved(q.id)) return q
        }
        return null
    }
}

