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
import com.google.ar.core.exceptions.NotYetAvailableException
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
import kotlinx.coroutines.withContext
import java.util.Locale

class TreasureHuntGameActivity : AppCompatActivity() {
    // AR renderer + camera feed
    private lateinit var arSceneView: ARSceneView

    // UI: timer and question text
    private lateinit var chronoTimer: Chronometer
    private lateinit var tvQuestionTitle: TextView
    private lateinit var tvQuestionText: TextView
    private lateinit var btnClose: ImageButton
    private lateinit var btnNext: Button

    // SceneView model loader (loads GLB)
    private lateinit var modelLoader: ModelLoader

    // Camera permission request code
    private val CAMERA_REQ = 44

    /**
     * ML Kit Text Recognition client.
     * We keep a single recognizer instance and reuse it (cheaper than creating each time).
     */
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * OCR concurrency guard:
     * - Prevents starting another OCR task while one is still running.
     * - Important because recognizer.process(...) is async and may overlap if we don't guard it.
     */
    private var ocrRunning = false

    /**
     * Timestamp for OCR throttling:
     * - We'll only run OCR once every ocrIntervalMs.
     * - This reduces CPU/GPU pressure and keeps AR tracking smoother.
     */
    private var lastOcrTime = 0L

    // ✅ Make OCR less frequent (reduces camera drop / ANR on mid devices)
    private val ocrIntervalMs = 1500L

    // Holds the currently active question/riddle
    private lateinit var currentQuestion: Question

    /**
     * modelPlaced:
     * - Once we detect correct text and place the 3D model, we stop scanning frames.
     * - Prevents repeated placements and repeated dialog triggers.
     */
    private var modelPlaced = false

    /**
     * popupShown: Prevents showing "Correct" dialog multiple times due to repeated OCR matches.
     */
    private var popupShown = false

    /**
     * questionStartMs: We use elapsedRealtime() to measure solving time reliably (not affected by system clock changes).
     */
    private var questionStartMs: Long = 0L

    /**
     * currentAnchorNode: The anchor node where the model is attached. When switching questions, we remove/destroy old anchor to clean up scene resources.
     */
    private var currentAnchorNode: AnchorNode? = null

    // Popup delay: wait a bit after placement, so the user can visually see the model appear first
    private val popupDelayMs = 3000L
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingPopupRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Inflate the UI layout for the treasure hunt screen
        setContentView(R.layout.activity_treasure_hunt_game)

        // Bind views
        arSceneView = findViewById(R.id.sceneView)
        chronoTimer = findViewById(R.id.chronoTimer)
        tvQuestionTitle = findViewById(R.id.tvQuestionTitle)
        tvQuestionText = findViewById(R.id.tvQuestionText)
        btnClose = findViewById(R.id.btnClose)
        btnNext = findViewById(R.id.btnNext)

        modelLoader = ModelLoader( //ModelLoader uses SceneView's engine
            engine = arSceneView.engine,
            context = this,
            coroutineScope = CoroutineScope(Dispatchers.IO)
        )

        btnClose.setOnClickListener { finish() } // Close button simply exits the activity
        btnNext.setOnClickListener { handleNextOrFinish() } // Next/Finish button logic (go to next question or scoreboard)

        // If permission not granted, request and stop here
        if (!hasCameraPermission()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_REQ)
            return
        }

        // Load questions FIRST from Firestore, then start the game
        GameState.loadQuestionsFromFirestore {
            runOnUiThread {
                val firstQ = GameState.nextUnsolved() ?: GameState.questions.firstOrNull()
                if (firstQ == null) {
                    Toast.makeText(this, "No questions loaded from Firestore!", Toast.LENGTH_LONG).show()
                    finish()
                    return@runOnUiThread
                }
                loadQuestion(firstQ)
            }
        }

        arSceneView.onSessionCreated = { session ->
            arSceneView.planeRenderer.isVisible = true
            val cfg = Config(session).apply {// Configure AR session
                focusMode = Config.FocusMode.AUTO
                lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL // Detect both horizontal and vertical planes (walls + floors)
                instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP // Allows placing objects without fully detected planes (faster start)
            }
            session.configure(cfg)
            Toast.makeText(this, "AR is ready! Scan the sign text...", Toast.LENGTH_SHORT).show()
        }

        arSceneView.onSessionUpdated = update@{ session, frame ->
            if (modelPlaced) return@update // If we already placed the model for this question, stop scanning to save resources.

            // Throttle OCR frequency (important for performance)
            val now = SystemClock.elapsedRealtime()
            if (ocrRunning || now - lastOcrTime < ocrIntervalMs) return@update
            lastOcrTime = now

            try {
                val image = frame.acquireCameraImage() //Acquire the raw camera image from ARCore frame
                ocrRunning = true

                val inputImage = InputImage.fromMediaImage(image, 0) //Build ML Kit InputImage from the camera media image

                recognizer.process(inputImage) //Run ML Kit OCR asynchronously. On success, we get detectedText and compare against target keywords using fuzzy matching.
                    .addOnSuccessListener { visionText ->
                        val detectedText = visionText.text

                        val matched = currentQuestion.targetKeywords.any { kw -> //We check if ANY target keyword matches. currentQuestion.targetKeywords can contain multiple acceptable phrases/synonyms.
                            fuzzyContainsKeyword(detectedText, kw)
                        }

                        if (matched && !modelPlaced) {
                            placeModelInFrontOfCamera(session, frame, currentQuestion)
                            modelPlaced = true
                            scheduleCorrectPopup(currentQuestion.id)
                        }
                    }
                    .addOnCompleteListener { //Always close camera image to avoid memory leaks
                        image.close()
                        ocrRunning = false
                    }

            } catch (_: NotYetAvailableException) {
                // normal case in ARCore - camera image not ready for this frame
            } catch (_: Exception) {
                // Safety: ensure flag resets to avoid "stuck" OCR state
                ocrRunning = false
            }
        }
    }

    // Permission helpers
    private fun hasCameraPermission(): Boolean { //Checks if CAMERA permission is granted, AR and OCR both require camera access
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult( //Permission request callback
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == CAMERA_REQ) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted) {
                recreate() // restart activity with permission granted
            } else {
                Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    //Question Flow
    private fun loadQuestion(q: Question) { //Loads a new question into the UI and resets per-question state
        currentQuestion = q

        // Update UI
        tvQuestionTitle.text = q.title
        tvQuestionText.text = q.text

        // Decide whether button should say NEXT or FINISH based on list position
        val currentIndex = GameState.questions.indexOfFirst { it.id == q.id }
        btnNext.text = if (currentIndex == GameState.questions.size - 1) "FINISH" else "NEXT"

        //Start timing for this question, used elapsedRealtime() for stable timing
        questionStartMs = SystemClock.elapsedRealtime()
        chronoTimer.base = questionStartMs
        chronoTimer.start()

        // Cancel any pending dialog from previous question
        pendingPopupRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingPopupRunnable = null
        popupShown = false

        //Remove any old 3D model anchor from previous question
        currentAnchorNode?.let { old ->
            arSceneView.removeChildNode(old)
            old.destroy()
        }
        currentAnchorNode = null
        // Reset match state for new question
        modelPlaced = false
    }

    private fun handleNextOrFinish() { //Handles NEXT / FINISH button logic
        pendingPopupRunnable?.let { mainHandler.removeCallbacks(it) } // If user moves forward, do not let a delayed dialog pop later

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

    // AR Model Placement
    private fun placeModelInFrontOfCamera(
        session: com.google.ar.core.Session,
        frame: com.google.ar.core.Frame,
        q: Question
    ) {
        try {
            val cameraPose = frame.camera.pose // Current camera pose

            // Move forward on camera's -Z axis (ARCore convention)
            val distanceMeters = 1.0f
            val forward = floatArrayOf(0f, 0f, -distanceMeters)
            val targetPose = cameraPose.compose(Pose.makeTranslation(forward))

            // Create AR anchor at the target pose
            val anchor = session.createAnchor(targetPose)
            val anchorNode = AnchorNode(engine = arSceneView.engine, anchor = anchor)
            arSceneView.addChildNode(anchorNode)
            currentAnchorNode = anchorNode

            // Load model on IO thread (prevents ANR)
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val modelInstance = modelLoader.loadModelInstance(q.modelFilePath)
                    if (modelInstance != null) {
                        val modelNode = ModelNode(
                            modelInstance = modelInstance,
                            scaleToUnits = q.modelScale
                        ).apply {
                            // Set rotation (question-specific tuning)
                            rotation = Rotation(q.modelRotationX, q.modelRotationY, 0f)
                        }

                        // Attach model node to anchor node on main thread
                        withContext(Dispatchers.Main) {
                            anchorNode.addChildNode(modelNode)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

        } catch (_: Exception) { }
        // Fail silently to keep gameplay stable
    }


    // "CORRECT" Popup / Score Logic
    private fun scheduleCorrectPopup(questionId: Int) { //Schedules the "Correct" popup after a short delay
        if (popupShown) return
        pendingPopupRunnable?.let { mainHandler.removeCallbacks(it) }

        val runnable = Runnable {
            if (!isFinishing) showCorrectDialog(questionId)
        }
        pendingPopupRunnable = runnable
        mainHandler.postDelayed(runnable, popupDelayMs)
    }


    private fun showCorrectDialog(questionId: Int) { //Shows a user "Correct" dialog
        if (popupShown) return
        popupShown = true

        chronoTimer.stop() // Stop timing for this question

        // Compute elapsed time and store it
        val elapsedMs = SystemClock.elapsedRealtime() - questionStartMs
        GameState.markSolved(questionId, elapsedMs)

        // Check if game is complete
        val allSolved = GameState.totalSolved == GameState.totalQuestions()

        // Custom dialog view
        val dialogView = layoutInflater.inflate(R.layout.dialog_correct, null)
        val tvDialogTitle = dialogView.findViewById<TextView>(R.id.tvDialogTitle)
        val tvDialogMessage = dialogView.findViewById<TextView>(R.id.tvDialogMessage)
        val tvDialogTime = dialogView.findViewById<TextView>(R.id.tvDialogTime)
        val btnDialogContinue = dialogView.findViewById<Button>(R.id.btnDialogContinue)

        // Customize dialog text based on completion state
        if (allSolved) {
            tvDialogTitle.text = "🎉 Congratulations! 🎉"
            tvDialogMessage.text = "You solved all questions!\nYou're a true treasure hunter!"
            btnDialogContinue.text = "VIEW SCOREBOARD"
        } else {
            tvDialogTitle.text = "✅ Correct!"
            tvDialogMessage.text = "Great job! Get ready for the next challenge."
            btnDialogContinue.text = "NEXT QUESTION"
        }

        val seconds = elapsedMs / 1000.0
        tvDialogTime.text = String.format(Locale.US, "Completed in %.1f seconds", seconds)

        // Build dialog with custom theme + prevent dismissing by tapping outside/back
        val dialog = AlertDialog.Builder(this, R.style.CustomDialogTheme)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        btnDialogContinue.setOnClickListener { // Button action: continue flow
            dialog.dismiss()
            if (allSolved) {
                startActivity(Intent(this, ScoreboardActivity::class.java))
                finish()
            } else {
                //Prefer "next unsolved" so the game can recover even if questions were answered out of order
                val nextQ = GameState.nextUnsolved()
                if (nextQ != null) loadQuestion(nextQ)
                else {
                    startActivity(Intent(this, ScoreboardActivity::class.java))
                    finish()
                }
            }
        }

        dialog.show()
    }

    // LEVENSHTEIN FUZZY MATCH

    /**
     * we need to fuzzy matching because;
     * OCR is not perfect:
     * - Lighting, motion blur, camera angle, reflections, and font styles cause wrong characters
     * - Example: "SMOKE FREE ZONE" might be recognized as "SM0KE F R E E Z0NE" or missing letters
     *
     * Strategy:
     * 1) Normalize text (uppercase, remove punctuation, unify whitespace)
     * 2) First try direct contains
     * 3) If not found, do approximate matching using Levenshtein distance
     *    over word windows of same size as the keyword phrase
     */
    private fun normalizeTextForMatch(s: String): String { //Normalizes text into a consistent format so distance/contains checks are meaningful
        return s.uppercase(Locale.getDefault())
            .replace(Regex("[^A-Z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }


    /**
     * Classic Levenshtein distance (edit distance):
     * Minimum number of single-character edits (insert, delete, substitute)
     * needed to change string a into string b
     *
     * We compute DP table of size (aLen+1) x (bLen+1)
     * Complexity:
     * - Time: O(aLen * bLen)
     * - Space: O(aLen * bLen)
     *
     * Here strings are short (keywords and small windows), so this is acceptable
     */
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
                    dp[i - 1][j] + 1, // deletion
                    dp[i][j - 1] + 1, // insertion
                    dp[i - 1][j - 1] + cost // substitution
                )
            }
        }
        return dp[a.length][b.length]
    }

    private fun isSimilarByLevenshtein(aRaw: String, bRaw: String): Boolean { //Decides whether two strings are "similar enough" to be considered a match
        val a = normalizeTextForMatch(aRaw)
        val b = normalizeTextForMatch(bRaw)
        if (a.isBlank() || b.isBlank()) return false

        val dist = levenshtein(a, b)
        val maxLen = maxOf(a.length, b.length).coerceAtLeast(1)
        val ratio = dist.toDouble() / maxLen.toDouble()

        return when {
            maxLen <= 4 -> dist <= 1 // Very short strings: allow only 0-1 edits
            maxLen <= 8 -> dist <= 2 // Medium strings: allow up to 2 edits
            else -> ratio <= 0.20 // Longer: allow up to 20% edits
        }
    }

    private fun fuzzyContainsKeyword(ocrTextRaw: String, keywordRaw: String): Boolean { //checks returns true if OCR text "contains" the keyword approximately
        val ocrText = normalizeTextForMatch(ocrTextRaw)
        val keyword = normalizeTextForMatch(keywordRaw)

        if (keyword.isBlank() || ocrText.isBlank()) return false

        if (ocrText.contains(keyword)) return true // Fast exact-ish match after normalization

        // Split OCR into tokens to support phrase window matching
        val words = ocrText.split(" ").filter { it.isNotBlank() }
        val keyWords = keyword.split(" ").filter { it.isNotBlank() }
        if (keyWords.isEmpty()) return false

        val windowSize = keyWords.size // Window size equals number of words in the keyword phrase

        if (words.size < windowSize) { // If OCR produced fewer words than keyword, fall back to whole-text similarity
            return isSimilarByLevenshtein(ocrText, keyword)
        }

        for (i in 0..(words.size - windowSize)) { // Slide window over OCR text and compare each phrase chunk
            val window = words.subList(i, i + windowSize).joinToString(" ")
            if (isSimilarByLevenshtein(window, keyword)) return true
        }

        return false
    }
}
