package com.example.orientar

data class Question(
    val id: Int,
    val title: String,
    val text: String,
    val answerImageName: String,
    val answerImageAssetPath: String,
    val modelFilePath: String,
    val modelScale: Float,
    val modelRotationX: Float, // YENİ: X ekseni açısı (Dik durması için)
    val modelRotationY: Float, // Y ekseni açısı (Yönü)
    val targetKeywords: List<String>
)

object GameState {

    val questions: MutableList<Question> = mutableListOf(
        Question(
            id = 1,
            title = "Question 1",
            text = "Show that smoking is prohibited!",
            answerImageName = "batur2",
            answerImageAssetPath = "augmented_images/batur2.jpg",
            modelFilePath = "3d_models/3d_camera_01.glb",
            modelScale = 0.5f,
            modelRotationX = 90f, // Model yere bakıyorsa bunu -90 veya 90 yap
            modelRotationY = 180f,
            targetKeywords = listOf("SMOKE", "FREE", "ZONE")
        ),
        Question(
            id = 2,
            title = "Question 2",
            text = "Show us the favourite hangout spot of all computer engineering students!",
            answerImageName = "ipek",
            answerImageAssetPath = "augmented_images/ipek.jpg",
            modelFilePath = "3d_models/glasses3d.glb",
            modelScale = 0.5f,
            modelRotationX = 90f, // Model yere bakıyorsa bunu -90 veya 90 yap
            modelRotationY = 90f,
            targetKeywords = listOf("BILISIM", "TEKNOLOJILERI", "INFORMATION")
        )
    )

    val bestTimes = mutableMapOf<Int, Long>()

    val totalSolved: Int
        get() = bestTimes.size

    val totalTimeMs: Long
        get() = bestTimes.values.sum()

    fun totalQuestions(): Int = questions.size

    fun markSolved(questionId: Int, elapsedMs: Long) {
        val currentBest = bestTimes[questionId]
        if (currentBest == null || elapsedMs < currentBest) {
            bestTimes[questionId] = elapsedMs
        }
    }

    fun isSolved(id: Int): Boolean = bestTimes.containsKey(id)

    // Sıradaki çözülmemiş soruyu bul, yoksa null dön
    fun nextUnsolved(): Question? =
        questions.firstOrNull { !bestTimes.containsKey(it.id) }

    // ID'den sonraki soruyu bul (Sırayla gitmek için)
    fun getNextQuestionInList(currentId: Int): Question? {
        val index = questions.indexOfFirst { it.id == currentId }
        if (index != -1 && index < questions.size - 1) {
            return questions[index + 1]
        }
        return null
    }

    fun reset() {
        bestTimes.clear()
    }
}