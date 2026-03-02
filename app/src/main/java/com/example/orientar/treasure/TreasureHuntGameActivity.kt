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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.ar.core.*
import com.google.ar.core.exceptions.NotYetAvailableException
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_treasure_hunt_game)

        arSceneView = findViewById(R.id.sceneView)
        chronoTimer = findViewById(R.id.chronoTimer)
        tvQuestionTitle = findViewById(R.id.tvQuestionTitle)
        tvQuestionText = findViewById(R.id.tvQuestionText)
        btnClose = findViewById(R.id.btnClose)
        btnNext = findViewById(R.id.btnNext)

        modelLoader = ModelLoader(arSceneView.engine, this, CoroutineScope(Dispatchers.IO))

        btnClose.setOnClickListener { finish() }
        btnNext.setOnClickListener { handleNextOrFinish() }

        if (!hasCameraPermission()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_REQ)
            return
        }

        GameState.loadQuestionsFromFirestore {
            runOnUiThread {
                val firstQ = GameState.nextUnsolved() ?: GameState.questions.firstOrNull()
                firstQ?.let { loadQuestion(it) }
            }
        }

        arSceneView.onSessionCreated = { session ->
            val config = session.config
            config.cloudAnchorMode = Config.CloudAnchorMode.ENABLED
            config.focusMode = Config.FocusMode.AUTO
            config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL

            config.depthMode = Config.DepthMode.DISABLED

            session.configure(config)
        }

        arSceneView.onSessionUpdated = onSessionUpdated@{ session, frame ->
            if (modelPlaced) return@onSessionUpdated

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
        currentAnchorNode?.let { arSceneView.removeChildNode(it); it.destroy() }
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
        GameState.markSolved(questionId, elapsedMs)

        AlertDialog.Builder(this, R.style.CustomDialogTheme)
            .setTitle("✅ Correct!")
            .setMessage(if(GameState.totalSolved == GameState.totalQuestions()) "You found the last treasure!" else "Great job! Keep going.")
            .setPositiveButton("Continue") { _, _ -> handleNextOrFinish() }
            .setCancelable(false)
            .show()
    }

    private fun handleNextOrFinish() {
        val nextQ = GameState.nextUnsolvedAfter(currentQuestion.id)
        if (nextQ != null) loadQuestion(nextQ)
        else {
            startActivity(Intent(this, ScoreboardActivity::class.java))
            finish()
        }
    }

    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    // OCR Fuzzy Match Methods
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

    private fun normalize(s: String) = s.uppercase(Locale.getDefault()).replace(Regex("[^A-Z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

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
                dp[i][j] = minOf(dp[i-1][j]+1, dp[i][j-1]+1, dp[i-1][j-1]+cost)
            }
        }
        return dp[a.length][b.length]
    }

    override fun onDestroy() {
        arSceneView.destroy()
        super.onDestroy()
    }
}