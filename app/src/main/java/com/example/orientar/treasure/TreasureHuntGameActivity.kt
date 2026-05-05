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
    private val cloudCheckIntervalMs = 10000L
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
    private val minScanMs = 40_000L

    private val TAG_FLOW = "TH_FLOW"
    private val TAG_HOST = "TH_HOST"
    private val TAG_RESOLVE = "TH_RESOLVE"
    private val TAG_OCR = "TH_OCR"
    private val TAG_TRIGGER = "TH_TRIGGER"
    private val TAG_MODEL = "TH_MODEL"
    private val TAG_LEADERBOARD = "TH_LEADERBOARD"
    private val TAG_GAMESTATE = "TH_GAMESTATE"

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

        Log.d(TAG_FLOW, "Firestore -> Question Loading started")
        // Always reload questions fresh, then load progress
        GameState.loadQuestionsFromFirestore {
            Log.d(
                TAG_FLOW,
                "Firestore -> Question Loading completed. totalQuestions=${GameState.totalQuestions()}"
            )
            GameState.loadProgress(this@TreasureHuntGameActivity)
            runOnUiThread {
                val firstQ = if (isNewGame) GameState.questions.firstOrNull()
                else GameState.nextUnsolved() ?: GameState.questions.firstOrNull()

                Log.d(TAG_GAMESTATE, "First question selected: id=${firstQ?.id}, title=${firstQ?.title}")

                if (firstQ != null && GameState.totalSolved < GameState.totalQuestions()) {
                    loadQuestion(firstQ)
                } else {
                    finishGame()
                }
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
                        Log.d(
                            "TH_RESOLVE",
                            "Q=${currentQuestion.id}, anchorId=${currentQuestion.cloudAnchorId}, state=$state"
                        )

                        if (state == Anchor.CloudAnchorState.SUCCESS) {
                            runOnUiThread {
                                if (!modelPlaced) {
                                    Log.d(TAG_TRIGGER, "Triggered by Cloud Anchor for Q=${currentQuestion.id}")
                                    placeModelOnAnchor(anchor, currentQuestion)
                                } else {
                                    Log.d(TAG_TRIGGER, "Cloud Anchor resolved but model already placed for Q=${currentQuestion.id}")
                                }
                            }
                        } else if (state.isError) {
                            Log.e(TAG_RESOLVE, "Cloud Anchor resolving failed for Q=${currentQuestion.id}, state=$state")
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

        val frame = lastFrame ?: run {
            Toast.makeText(this, "Frame not ready, try again", Toast.LENGTH_SHORT).show()
            return
        }

        if (frame.camera.trackingState != TrackingState.TRACKING) {
            Toast.makeText(this, "Camera is not tracking yet. Move the phone slowly.", Toast.LENGTH_SHORT).show()
            return
        }

        isHosting = true

        Toast.makeText(
            this,
            "Scan the area/object slowly for ${minScanMs / 1000} seconds.",
            Toast.LENGTH_LONG
        ).show()

        mainHandler.postDelayed({
            val latestSession = arSession
            val latestFrame = lastFrame

            if (latestSession == null || latestFrame == null) {
                isHosting = false
                Toast.makeText(this, "AR session/frame lost. Try again.", Toast.LENGTH_LONG).show()
                return@postDelayed
            }

            if (latestFrame.camera.trackingState != TrackingState.TRACKING) {
                isHosting = false
                Toast.makeText(this, "Camera tracking lost. Try scanning again.", Toast.LENGTH_LONG).show()
                return@postDelayed
            }

            val quality = latestSession.estimateFeatureMapQualityForHosting(latestFrame.camera.pose)

            Log.d("TH_HOST", "Feature map quality: $quality")

            if (quality == Session.FeatureMapQuality.INSUFFICIENT) {
                Log.w(TAG_HOST, "Feature map quality is INSUFFICIENT, but hosting will continue for testing/demo.")
                Toast.makeText(
                    this,
                    "Visual quality is low, but hosting will continue.",
                    Toast.LENGTH_LONG
                ).show()
            }

            val centerX = arSceneView.width / 2f
            val centerY = arSceneView.height / 2f

            val hit = latestFrame.hitTest(centerX, centerY).firstOrNull { hitResult ->
                val trackable = hitResult.trackable
                (trackable is Plane && trackable.isPoseInPolygon(hitResult.hitPose)) ||
                        trackable is Point
            }

            val anchor = hit?.createAnchor()
                ?: latestSession.createAnchor(
                    latestFrame.camera.pose.compose(Pose.makeTranslation(0f, 0f, -0.7f))
                )

            Toast.makeText(this, "Hosting Cloud Anchor...", Toast.LENGTH_SHORT).show()

            latestSession.hostCloudAnchorAsync(anchor, 1) { cloudAnchorId, state ->
                runOnUiThread {
                    isHosting = false

                    if (state == Anchor.CloudAnchorState.SUCCESS) {
                        saveAnchorId(cloudAnchorId)
                        Toast.makeText(this, "Cloud Anchor saved!", Toast.LENGTH_LONG).show()
                    } else {
                        anchor.detach()
                        Toast.makeText(this, "Hosting failed: $state", Toast.LENGTH_LONG).show()
                        Log.e("TH_HOST", "Hosting failed: $state")
                    }
                }
            }
        }, minScanMs)
    }

    private fun saveAnchorId(anchorId: String) {
        val docName = "q${currentQuestion.id}"

        Log.d(TAG_HOST, "Saving Cloud Anchor ID to Firestore. doc=$docName, anchorId=$anchorId")

        FirebaseFirestore.getInstance()
            .collection("treasure_questions")
            .document(docName)
            .set(mapOf("cloudAnchorId" to anchorId), SetOptions.merge())
            .addOnSuccessListener {
                currentQuestion = currentQuestion.copy(cloudAnchorId = anchorId)
                Log.d(TAG_HOST, "Cloud Anchor ID saved successfully. doc=$docName")
                Toast.makeText(this, "Saved! ID: $anchorId", Toast.LENGTH_LONG).show()
            }
            .addOnFailureListener { e ->
                Log.e(TAG_HOST, "Cloud Anchor ID save failed. doc=$docName", e)
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
                    val matchedKeyword = currentQuestion.targetKeywords.firstOrNull { kw ->
                        fuzzyContainsKeyword(visionText.text, kw)
                    }

                    Log.d(
                        "TH_OCR",
                        "Q=${currentQuestion.id}, OCR='${visionText.text}', matchedKeyword=$matchedKeyword"
                    )

                    if (matchedKeyword != null && !modelPlaced) {
                        Log.d(TAG_TRIGGER, "Triggered by OCR for Q=${currentQuestion.id}, keyword=$matchedKeyword")
                        placeModelInFrontOfCamera(session, frame, currentQuestion)
                    }

                    if (matchedKeyword == null) {
                        Log.d(TAG_OCR, "OCR negative path: no valid keyword matched for Q=${currentQuestion.id}")
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
        Log.d(TAG_MODEL, "Model placement requested from Cloud Anchor. Q=${q.id}")
        if (modelPlaced) return
        modelPlaced = true
        val anchorNode = AnchorNode(arSceneView.engine, anchor)
        arSceneView.addChildNode(anchorNode)
        currentAnchorNode = anchorNode
        loadModel(anchorNode, q)
    }

    private fun placeModelInFrontOfCamera(session: Session, frame: Frame, q: Question) {
        Log.d(TAG_MODEL, "Model placement requested from OCR camera-relative anchor. Q=${q.id}")
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
        Log.d(
            TAG_MODEL,
            "Loading model. Q=${q.id}, path=${q.modelFilePath}, scale=${q.modelScale}, rotation=(${q.modelRotationX}, ${q.modelRotationY}, ${q.modelRotationZ})"
        )

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val modelInstance = modelLoader.loadModelInstance(q.modelFilePath)

                withContext(Dispatchers.Main) {
                    if (modelInstance == null) {
                        modelPlaced = false
                        Log.e("TH_MODEL", "Model instance is null. Path=${q.modelFilePath}")
                        Toast.makeText(
                            this@TreasureHuntGameActivity,
                            "Model could not be loaded: ${q.modelFilePath}",
                            Toast.LENGTH_LONG
                        ).show()
                        return@withContext
                    }

                    val modelNode = ModelNode(
                        modelInstance = modelInstance,
                        scaleToUnits = q.modelScale
                    ).apply {
                        rotation = Rotation(q.modelRotationX, q.modelRotationY, q.modelRotationZ)

                        // Makes the model less affected by dark AR shadows
                        isShadowCaster = false
                        isShadowReceiver = false
                    }

                    anchorNode.addChildNode(modelNode)

                    Log.d("TH_MODEL", "Model added successfully.")
                    mainHandler.postDelayed({ showCorrectDialog(q.id) }, 2000)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    modelPlaced = false
                    Toast.makeText(
                        this@TreasureHuntGameActivity,
                        "Model load failed: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
                Log.e("TH_MODEL", "Model Load Failed", e)
            }
        }
    }

    private fun showCorrectDialog(questionId: Int) {
        if (popupShown || isFinishing) return
        popupShown = true
        chronoTimer.stop()

        val elapsedMs = SystemClock.elapsedRealtime() - questionStartMs
        GameState.markSolved(questionId, elapsedMs, this)
        Log.d(
            TAG_GAMESTATE,
            "Question solved. questionId=$questionId, elapsedMs=$elapsedMs, totalSolved=${GameState.totalSolved}, totalTimeMs=${GameState.totalTimeMs}"
        )

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
        return GameState.nextUnsolvedAfter(currentQuestion.id)
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
        Log.d(TAG_FLOW, "Finish game requested. solved=${GameState.totalSolved}, total=${GameState.totalQuestions()}")
        val total = GameState.totalQuestions()
        val solved = GameState.totalSolved
        // Guard: if questions not loaded yet, just go to menu
        if (total == 0) { goToMenu(); return }
        if (solved == total) fetchUserNameAndSaveToLeaderboard() else goToMenu()
    }

    private fun fetchUserNameAndSaveToLeaderboard() {
        val user = FirebaseAuth.getInstance().currentUser
        val uid = user?.uid

        if (uid == null) {
            goToMenu()
            return
        }

        val db = FirebaseFirestore.getInstance()

        fun saveFromDoc(doc: com.google.firebase.firestore.DocumentSnapshot?) {
            val firstName = doc?.getString("firstName") ?: ""
            val lastName = doc?.getString("lastName") ?: ""
            val fullName = "$firstName $lastName".trim()

            val fallbackName = user.email ?: "Unknown User"
            Log.d(
                TAG_LEADERBOARD,
                "Resolved leaderboard name. fullName='$fullName', fallback='${user.email}'"
            )
            saveToLeaderboard(if (fullName.isNotEmpty()) fullName else fallbackName)
        }

        db.collection("users")
            .document(uid)
            .get()
            .addOnSuccessListener { directDoc ->
                if (directDoc.exists()) {
                    saveFromDoc(directDoc)
                } else {
                    db.collection("users")
                        .whereEqualTo("authUid", uid)
                        .limit(1)
                        .get()
                        .addOnSuccessListener { query ->
                            saveFromDoc(query.documents.firstOrNull())
                        }
                        .addOnFailureListener {
                            saveToLeaderboard(user.email ?: "Unknown User")
                        }
                }
            }
            .addOnFailureListener {
                saveToLeaderboard(user.email ?: "Unknown User")
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

        Log.d(TAG_LEADERBOARD, "Saving leaderboard entry: $entry")
        // Use uid as document ID so each user has one entry (overwrites on replay)

        db.collection("leaderboard")
            .document(uid)
            .set(entry)
            .addOnSuccessListener {
                Toast.makeText(this, "Score saved! Good luck 🏆", Toast.LENGTH_SHORT).show()
                Log.d("TH_LEADERBOARD", "Leaderboard save success for uid=$uid")
                goToMenu()
            }
            .addOnFailureListener { e ->
                Log.e(TAG_LEADERBOARD, "Leaderboard save failed for uid=$uid", e)
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

    override fun onDestroy() {
        recognizer.close()
        cleanupCurrentAnchor()
        arSceneView.destroy()
        super.onDestroy()
    }
}

internal fun fuzzyContainsKeyword(ocrText: String, keyword: String): Boolean {
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

internal fun normalize(s: String): String {
    return s.uppercase(java.util.Locale.US)
        .replace("İ", "I")
        .replace("Ü", "U")
        .replace("Ö", "O")
        .replace("Ş", "S")
        .replace("Ğ", "G")
        .replace("Ç", "C")
        .replace(Regex("[^A-Z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

internal fun isSimilar(a: String, b: String): Boolean {
    val dist = levenshtein(a, b)
    val maxLen = maxOf(a.length, b.length).coerceAtLeast(1)
    return (dist.toDouble() / maxLen) <= 0.20
}

internal fun levenshtein(a: String, b: String): Int {
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