package com.example.orientar.treasure

import com.example.orientar.R

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Button
import android.widget.Chronometer
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.ar.core.*
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Rotation
import io.github.sceneview.node.ModelNode
import kotlinx.coroutines.*
import java.util.Locale

class TreasureHuntGameActivity : AppCompatActivity() {
    private lateinit var arSceneView: ARSceneView
    private lateinit var chronoTimer: Chronometer
    private lateinit var tvQuestionTitle: TextView
    private lateinit var tvQuestionText: TextView
    private lateinit var btnClose: ImageButton
    private lateinit var btnNext: Button
    private lateinit var modelLoader: ModelLoader

    // State Guards
    private var modelPlaced = false
    private var isResolving = false
    private var ocrRunning = false
    private var popupShown = false

    // Intervals
    private var lastCloudCheckTime = 0L
    private val cloudCheckIntervalMs = 2000L
    private var lastOcrTime = 0L
    private val ocrIntervalMs = 1500L

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private lateinit var currentQuestion: Question
    private var currentAnchorNode: AnchorNode? = null
    private var questionStartMs: Long = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val CAMERA_REQ = 44

    // Admin
    private var arSession: Session? = null
    private var lastFrame: Frame? = null
    private var isHosting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_treasure_hunt_game)

        arSceneView = findViewById(R.id.sceneView)
        chronoTimer = findViewById(R.id.chronoTimer)
        tvQuestionTitle = findViewById(R.id.tvQuestionTitle)
        tvQuestionText = findViewById(R.id.tvQuestionText)
        btnClose = findViewById(R.id.btnClose)
        btnNext = findViewById(R.id.btnNext)

        modelLoader = ModelLoader(arSceneView.engine, this, lifecycleScope)

        btnClose.setOnClickListener { finish() }
        btnNext.setOnClickListener { skipToNextQuestion() }

        // Admin trigger: tap question title to start hosting for current question
        tvQuestionTitle.setOnClickListener { startHosting() }

        if (!hasCameraPermission()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_REQ)
            return
        }

        val isNewGame = intent.getBooleanExtra("isNewGame", false)
        // Always clear stale memory before loading fresh state for current user
        GameState.clearMemory()
        if (isNewGame) {
            GameState.resetProgress(this)
            // Delete old leaderboard entry so replay starts fresh
            val uid = FirebaseAuth.getInstance().currentUser?.uid
            if (uid != null) {
                FirebaseFirestore.getInstance()
                    .collection("leaderboard")
                    .document(uid)
                    .delete()
            }
        }
        // Always reload questions fresh, then load progress
        GameState.loadQuestionsFromFirestore {
            GameState.loadProgress(this@TreasureHuntGameActivity)
            runOnUiThread {
                val firstQ = if (isNewGame) GameState.questions.firstOrNull()
                else GameState.nextUnsolved() ?: GameState.questions.firstOrNull()
                firstQ?.let { loadQuestion(it) }
            }
        }

        arSceneView.onSessionCreated = { session ->
            arSession = session
            val config = session.config
            config.cloudAnchorMode = Config.CloudAnchorMode.ENABLED
            config.focusMode = Config.FocusMode.AUTO
            config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            config.depthMode = Config.DepthMode.DISABLED
            session.configure(config)
        }

        arSceneView.onSessionUpdated = onSessionUpdated@{ session, frame ->
            lastFrame = frame

            if (modelPlaced) return@onSessionUpdated
            if (!::currentQuestion.isInitialized) return@onSessionUpdated

            val now = SystemClock.elapsedRealtime()

            // 1. 3D CLOUD ANCHOR
            if (currentQuestion.cloudAnchorId.isNotEmpty() && !isResolving) {
                if (now - lastCloudCheckTime >= cloudCheckIntervalMs) {
                    lastCloudCheckTime = now
                    isResolving = true
                    session.resolveCloudAnchorAsync(currentQuestion.cloudAnchorId) { anchor, state ->
                        if (state == Anchor.CloudAnchorState.SUCCESS) {
                            runOnUiThread {
                                if (!modelPlaced) {
                                    placeModelOnAnchor(anchor, currentQuestion)
                                }
                            }
                        }
                        isResolving = false
                    }
                }
            }

            // 2. 2D OCR
            if (!modelPlaced && !ocrRunning && (now - lastOcrTime >= ocrIntervalMs)) {
                lastOcrTime = now
                runOcrTask(session, frame)
            }
        }
    }

    private fun startHosting() {
        if (isHosting) return
        val session = arSession ?: run {
            Toast.makeText(this, "AR session not ready", Toast.LENGTH_SHORT).show()
            return
        }
        val frame = lastFrame ?: run {
            Toast.makeText(this, "Frame not ready, try again", Toast.LENGTH_SHORT).show()
            return
        }

        isHosting = true
        Toast.makeText(this, "Scanning... Walk around the object for 20 seconds.", Toast.LENGTH_LONG).show()

        val cameraPose = frame.camera.pose
        val targetPose = cameraPose.compose(Pose.makeTranslation(0f, 0f, -0.5f))
        val anchor = session.createAnchor(targetPose)

        val hostingStartTime = SystemClock.elapsedRealtime()
        val minScanMs = 20_000L // 20 seconds minimum

        session.hostCloudAnchorAsync(anchor, 1) { cloudAnchorId, state ->
            val elapsed = SystemClock.elapsedRealtime() - hostingStartTime
            val remaining = minScanMs - elapsed

            if (state == Anchor.CloudAnchorState.SUCCESS) {
                if (remaining > 0) {
                    // ID ready but wait for minimum scan time
                    runOnUiThread {
                        Toast.makeText(this, "Almost done! Keep scanning for ${remaining / 1000}s more...", Toast.LENGTH_SHORT).show()
                    }
                    mainHandler.postDelayed({
                        runOnUiThread {
                            isHosting = false
                            anchor.detach()
                            saveAnchorId(cloudAnchorId)
                        }
                    }, remaining)
                } else {
                    runOnUiThread {
                        isHosting = false
                        anchor.detach()
                        saveAnchorId(cloudAnchorId)
                    }
                }
            } else {
                runOnUiThread {
                    isHosting = false
                    anchor.detach()
                    Toast.makeText(this, "Hosting failed: $state", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun saveAnchorId(anchorId: String) {
        val docName = "q${currentQuestion.id}"
        FirebaseFirestore.getInstance()
            .collection("treasure_questions")
            .document(docName)
            .set(mapOf("cloudAnchorId" to anchorId), SetOptions.merge())
            .addOnSuccessListener {
                currentQuestion = currentQuestion.copy(cloudAnchorId = anchorId)
                Toast.makeText(this, "Saved! ID: $anchorId", Toast.LENGTH_LONG).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Firebase error: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun runOcrTask(session: Session, frame: Frame) {
        try {
            val image = frame.acquireCameraImage()
            ocrRunning = true
            val inputImage = InputImage.fromMediaImage(image, 0)

            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    val matched = currentQuestion.targetKeywords.any { kw ->
                        fuzzyContainsKeyword(visionText.text, kw)
                    }
                    if (matched && !modelPlaced) {
                        placeModelInFrontOfCamera(session, frame, currentQuestion)
                    }
                }
                .addOnCompleteListener {
                    image.close()
                    ocrRunning = false
                }
        } catch (_: NotYetAvailableException) {
        } catch (e: Exception) { ocrRunning = false }
    }

    private fun loadQuestion(q: Question) {
        currentQuestion = q
        val idx = GameState.questions.indexOfFirst { it.id == q.id } + 1
        tvQuestionTitle.text = "Question $idx / ${GameState.totalQuestions()}"
        tvQuestionText.text = q.text
        btnNext.text = if (idx == GameState.questions.size) "FINISH" else "NEXT"

        questionStartMs = SystemClock.elapsedRealtime()
        chronoTimer.base = questionStartMs
        chronoTimer.start()

        modelPlaced = false
        popupShown = false
        cleanupCurrentAnchor()
    }

    private fun cleanupCurrentAnchor() {
        currentAnchorNode?.let {
            arSceneView.removeChildNode(it)
            it.anchor?.detach()
            it.destroy()
        }
        currentAnchorNode = null
    }

    private fun placeModelOnAnchor(anchor: Anchor, q: Question) {
        if (modelPlaced) return
        modelPlaced = true
        val anchorNode = AnchorNode(arSceneView.engine, anchor)
        arSceneView.addChildNode(anchorNode)
        currentAnchorNode = anchorNode
        loadModel(anchorNode, q)
    }

    private fun placeModelInFrontOfCamera(session: Session, frame: Frame, q: Question) {
        if (modelPlaced) return
        modelPlaced = true
        val cameraPose = frame.camera.pose
        val targetPose = cameraPose.compose(Pose.makeTranslation(0f, 0.05f, -0.7f))
        val anchor = session.createAnchor(targetPose)
        val anchorNode = AnchorNode(arSceneView.engine, anchor)
        arSceneView.addChildNode(anchorNode)
        currentAnchorNode = anchorNode
        loadModel(anchorNode, q)
    }

    private fun loadModel(anchorNode: AnchorNode, q: Question) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val modelInstance = modelLoader.loadModelInstance(q.modelFilePath)
                withContext(Dispatchers.Main) {
                    modelInstance?.let {
                        val modelNode = ModelNode(
                            modelInstance = it,
                            scaleToUnits = q.modelScale
                        ).apply {
                            rotation = Rotation(q.modelRotationX, q.modelRotationY, q.modelRotationZ)
                        }
                        anchorNode.addChildNode(modelNode)
                        mainHandler.postDelayed({ showCorrectDialog(q.id) }, 2000)
                    }
                }
            } catch (e: Exception) {
                Log.e("AR", "Model Load Failed: ${e.message}")
            }
        }
    }

    private fun showCorrectDialog(questionId: Int) {
        if (popupShown || isFinishing) return
        popupShown = true
        chronoTimer.stop()

        val elapsedMs = SystemClock.elapsedRealtime() - questionStartMs
        GameState.markSolved(questionId, elapsedMs, this)

        val total = GameState.totalQuestions()
        val isLast = total > 0 && GameState.totalSolved == total
        val dialogView = layoutInflater.inflate(R.layout.dialog_correct, null)
        dialogView.findViewById<android.widget.TextView>(R.id.tvDialogTitle).text =
            if (isLast) "🎉 All Found!" else "✅ Correct!"
        dialogView.findViewById<android.widget.TextView>(R.id.tvDialogMessage).text =
            if (isLast) "Amazing! You found all treasures!" else "Great job! Get ready for the next challenge."
        val btnNext = dialogView.findViewById<android.widget.Button>(R.id.btnDialogContinue)
        btnNext.text = if (isLast) "VIEW RESULTS" else "NEXT QUESTION"

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setView(dialogView)
            .setCancelable(false)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        btnNext.setOnClickListener {
            dialog.dismiss()
            handleNextOrFinish()
        }
        dialog.show()
    }

    // Get the next question in order (index+1), regardless of solved status
    private fun nextQuestionInOrder(): Question? {
        val idx = GameState.questions.indexOfFirst { it.id == currentQuestion.id }
        return if (idx >= 0 && idx + 1 < GameState.questions.size) GameState.questions[idx + 1] else null
    }

    // Called from NEXT/FINISH button — always go in order, no leaderboard check
    private fun skipToNextQuestion() {
        val next = nextQuestionInOrder()
        if (next != null) {
            loadQuestion(next)
        } else {
            // Last question reached via NEXT/FINISH — go to menu, check if all solved
            finishGame()
        }
    }

    // Called from correct dialog NEXT QUESTION button — go in order
    private fun handleNextOrFinish() {
        val next = nextQuestionInOrder()
        if (next != null) {
            loadQuestion(next)
        } else {
            // Last question reached via correct dialog — go to menu, check if all solved
            finishGame()
        }
    }

    // Common finish: ALL questions must be solved (and questions must be loaded)
    private fun finishGame() {
        val total = GameState.totalQuestions()
        val solved = GameState.totalSolved
        // Guard: if questions not loaded yet, just go to menu
        if (total == 0) { goToMenu(); return }
        if (solved == total) fetchUserNameAndSaveToLeaderboard() else goToMenu()
    }

    private fun fetchUserNameAndSaveToLeaderboard() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            goToMenu()
            return
        }
        FirebaseFirestore.getInstance()
            .collection("users")
            .document(uid)
            .get()
            .addOnSuccessListener { doc ->
                val firstName = doc.getString("firstName") ?: ""
                val lastName = doc.getString("lastName") ?: ""
                val fullName = "$firstName $lastName".trim()
                saveToLeaderboard(if (fullName.isNotEmpty()) fullName else uid)
            }
            .addOnFailureListener {
                goToMenu()
            }
    }

    private fun saveToLeaderboard(name: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val db = FirebaseFirestore.getInstance()
        val entry = hashMapOf(
            "name" to name,
            "solvedCount" to GameState.totalSolved,
            "totalTimeMs" to GameState.totalTimeMs,
            "timestamp" to com.google.firebase.Timestamp.now()
        )
        // Use uid as document ID so each user has one entry (overwrites on replay)
        db.collection("leaderboard")
            .document(uid)
            .set(entry)
            .addOnSuccessListener {
                Toast.makeText(this, "Score saved! Good luck 🏆", Toast.LENGTH_SHORT).show()
                goToMenu()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Couldn't save score, but well done!", Toast.LENGTH_SHORT).show()
                goToMenu()
            }
    }

    private fun goToMenu() {
        val intent = Intent(this, ScoreboardActivity::class.java)
        intent.putExtra("forcedSolved", GameState.totalSolved)
        intent.putExtra("forcedTotal", GameState.totalQuestions())
        startActivity(intent)
        finish()
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun fuzzyContainsKeyword(ocrText: String, keyword: String): Boolean {
        val nOcr = normalize(ocrText)
        val nKey = normalize(keyword)
        if (nKey.isBlank() || nOcr.isBlank()) return false
        if (nOcr.contains(nKey)) return true

        val words = nOcr.split(" ")
        val keyWords = nKey.split(" ")
        if (words.size < keyWords.size) return isSimilar(nOcr, nKey)

        for (i in 0..(words.size - keyWords.size)) {
            val window = words.subList(i, i + keyWords.size).joinToString(" ")
            if (isSimilar(window, nKey)) return true
        }
        return false
    }

    private fun normalize(s: String) =
        s.uppercase(Locale.getDefault())
            .replace(Regex("[^A-Z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun isSimilar(a: String, b: String): Boolean {
        val dist = levenshtein(a, b)
        val maxLen = maxOf(a.length, b.length).coerceAtLeast(1)
        return (dist.toDouble() / maxLen) <= 0.20
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[a.length][b.length]
    }

    override fun onDestroy() {
        recognizer.close()
        cleanupCurrentAnchor()
        arSceneView.destroy()
        super.onDestroy()
    }
}