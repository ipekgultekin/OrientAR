package com.example.orientar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import com.google.ar.core.Config
import com.google.ar.core.Pose
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Rotation
import io.github.sceneview.node.ModelNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

class TreasureHuntGameActivity : AppCompatActivity() {

    private lateinit var arSceneView: ARSceneView
    private lateinit var chronoTimer: Chronometer
    private lateinit var tvQuestionTitle: TextView
    private lateinit var tvQuestionText: TextView
    private lateinit var btnClose: ImageButton
    private lateinit var btnNext: Button

    private lateinit var modelLoader: ModelLoader
    private val CAMERA_REQ = 44

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var ocrRunning = false
    private var lastOcrTime = 0L
    private val ocrIntervalMs = 500L

    private lateinit var currentQuestion: Question

    private var modelPlaced = false
    private var popupShown = false
    private var questionStartMs: Long = 0L
    private var currentAnchorNode: AnchorNode? = null

    // Popup delay: 3 seconds
    private val popupDelayMs = 3000L
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingPopupRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_treasure_hunt_game)

        arSceneView     = findViewById(R.id.sceneView)
        chronoTimer     = findViewById(R.id.chronoTimer)
        tvQuestionTitle = findViewById(R.id.tvQuestionTitle)
        tvQuestionText  = findViewById(R.id.tvQuestionText)
        btnClose        = findViewById(R.id.btnClose)
        btnNext         = findViewById(R.id.btnNext)

        modelLoader = ModelLoader(
            engine = arSceneView.engine,
            context = this,
            coroutineScope = CoroutineScope(Dispatchers.IO)
        )

        btnClose.setOnClickListener { finish() }
        btnNext.setOnClickListener { handleNextOrFinish() }

        ensureCameraPermission()

        val firstQ = GameState.nextUnsolved() ?: GameState.questions.first()
        loadQuestion(firstQ)

        arSceneView.onSessionCreated = { session ->
            arSceneView.planeRenderer.isVisible = true
            val cfg = Config(session).apply {
                focusMode = Config.FocusMode.AUTO
                lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
            }
            session.configure(cfg)
            Toast.makeText(this, "AR is ready! Scan the sign text...", Toast.LENGTH_SHORT).show()
        }

        arSceneView.onSessionUpdated = update@{ session, frame ->
            if (modelPlaced) return@update

            val now = SystemClock.elapsedRealtime()
            if (ocrRunning || now - lastOcrTime < ocrIntervalMs) return@update
            lastOcrTime = now

            try {
                val image = frame.acquireCameraImage()
                if (image == null) return@update

                ocrRunning = true
                val inputImage = InputImage.fromMediaImage(image, 0)

                recognizer.process(inputImage)
                    .addOnSuccessListener { visionText ->
                        val detectedText = visionText.text

                        // ✅ FUZZY MATCH (Levenshtein)
                        val matched = currentQuestion.targetKeywords.any { kw ->
                            fuzzyContainsKeyword(detectedText, kw)
                        }

                        if (matched) {
                            placeModelInFrontOfCamera(session, frame, currentQuestion)
                            modelPlaced = true
                            scheduleCorrectPopup(currentQuestion.id)
                        }
                    }
                    .addOnCompleteListener {
                        image.close()
                        ocrRunning = false
                    }

            } catch (_: Exception) {
                ocrRunning = false
            }
        }
    }

    private fun loadQuestion(q: Question) {
        currentQuestion = q
        tvQuestionTitle.text = q.title
        tvQuestionText.text  = q.text

        // Button label: FINISH on last question, else NEXT
        val currentIndex = GameState.questions.indexOfFirst { it.id == q.id }
        btnNext.text = if (currentIndex == GameState.questions.size - 1) "FINISH" else "NEXT"

        questionStartMs = SystemClock.elapsedRealtime()
        chronoTimer.base = questionStartMs
        chronoTimer.start()

        pendingPopupRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingPopupRunnable = null
        popupShown = false

        currentAnchorNode?.let { old ->
            arSceneView.removeChildNode(old)
            old.destroy()
        }
        currentAnchorNode = null
        modelPlaced = false
    }

    private fun handleNextOrFinish() {
        pendingPopupRunnable?.let { mainHandler.removeCallbacks(it) }

        if (btnNext.text == "FINISH") {
            startActivity(Intent(this, ScoreboardActivity::class.java))
            finish()
        } else {
            val nextQ = GameState.getNextQuestionInList(currentQuestion.id)
            if (nextQ != null) {
                loadQuestion(nextQ)
            } else {
                Toast.makeText(this, "No more questions.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun placeModelInFrontOfCamera(
        session: com.google.ar.core.Session,
        frame: com.google.ar.core.Frame,
        q: Question
    ) {
        try {
            val cameraPose = frame.camera.pose
            val distanceMeters = 1.0f
            val forward = floatArrayOf(0f, 0f, -distanceMeters)
            val targetPose = cameraPose.compose(Pose.makeTranslation(forward))

            val anchor = session.createAnchor(targetPose)
            val anchorNode = AnchorNode(engine = arSceneView.engine, anchor = anchor)
            arSceneView.addChildNode(anchorNode)
            currentAnchorNode = anchorNode

            lifecycleScope.launch {
                try {
                    val modelInstance = modelLoader.loadModelInstance(q.modelFilePath)
                    if (modelInstance != null) {
                        val modelNode = ModelNode(
                            modelInstance = modelInstance,
                            scaleToUnits = q.modelScale
                        ).apply {
                            rotation = Rotation(q.modelRotationX, q.modelRotationY, 0f)
                        }
                        anchorNode.addChildNode(modelNode)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } catch (_: Exception) { }
    }

    private fun scheduleCorrectPopup(questionId: Int) {
        if (popupShown) return
        pendingPopupRunnable?.let { mainHandler.removeCallbacks(it) }

        val runnable = Runnable {
            if (!isFinishing) showCorrectDialog(questionId)
        }
        pendingPopupRunnable = runnable
        mainHandler.postDelayed(runnable, popupDelayMs)
    }

    private fun showCorrectDialog(questionId: Int) {
        if (popupShown) return
        popupShown = true
        chronoTimer.stop()

        val elapsedMs = SystemClock.elapsedRealtime() - questionStartMs
        GameState.markSolved(questionId, elapsedMs)

        val allSolved = GameState.totalSolved == GameState.totalQuestions()

        val msg = if (allSolved) {
            "Congratulations! You solved all questions."
        } else {
            "Correct! Get ready for the next one."
        }

        AlertDialog.Builder(this)
            .setTitle("Great job!")
            .setMessage(msg)
            .setCancelable(false)
            .setPositiveButton(if (allSolved) "Scoreboard" else "Next Question") { d, _ ->
                d.dismiss()
                if (allSolved) {
                    startActivity(Intent(this, ScoreboardActivity::class.java))
                    finish()
                } else {
                    val nextQ = GameState.nextUnsolved()
                    if (nextQ != null) loadQuestion(nextQ)
                    else {
                        startActivity(Intent(this, ScoreboardActivity::class.java))
                        finish()
                    }
                }
            }
            .show()
    }

    private fun ensureCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_REQ)
        }
    }

    // -----------------------------
    // ✅ LEVENSHTEIN FUZZY MATCH
    // -----------------------------

    private fun normalizeTextForMatch(s: String): String {
        return s.uppercase(Locale.getDefault())
            .replace(Regex("[^A-Z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j

        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[a.length][b.length]
    }

    private fun isSimilarByLevenshtein(aRaw: String, bRaw: String): Boolean {
        val a = normalizeTextForMatch(aRaw)
        val b = normalizeTextForMatch(bRaw)
        if (a.isBlank() || b.isBlank()) return false

        val dist = levenshtein(a, b)
        val maxLen = maxOf(a.length, b.length).coerceAtLeast(1)
        val ratio = dist.toDouble() / maxLen.toDouble()

        return when {
            maxLen <= 4 -> dist <= 1
            maxLen <= 8 -> dist <= 2
            else -> ratio <= 0.20
        }
    }

    private fun fuzzyContainsKeyword(ocrTextRaw: String, keywordRaw: String): Boolean {
        val ocrText = normalizeTextForMatch(ocrTextRaw)
        val keyword = normalizeTextForMatch(keywordRaw)

        if (keyword.isBlank() || ocrText.isBlank()) return false

        // Fast path
        if (ocrText.contains(keyword)) return true

        // Sliding window on words
        val words = ocrText.split(" ").filter { it.isNotBlank() }
        val keyWords = keyword.split(" ").filter { it.isNotBlank() }
        if (keyWords.isEmpty()) return false

        val windowSize = keyWords.size

        if (words.size < windowSize) {
            return isSimilarByLevenshtein(ocrText, keyword)
        }

        for (i in 0..(words.size - windowSize)) {
            val window = words.subList(i, i + windowSize).joinToString(" ")
            if (isSimilarByLevenshtein(window, keyword)) return true
        }

        return false
    }
}
