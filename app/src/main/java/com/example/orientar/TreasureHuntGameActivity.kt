package com.example.orientar

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.widget.Button
import android.widget.Chronometer
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Rotation
import io.github.sceneview.node.ModelNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TreasureHuntGameActivity : AppCompatActivity() {

    private lateinit var arSceneView: ARSceneView
    private lateinit var chronoTimer: Chronometer
    private lateinit var tvQuestionTitle: TextView
    private lateinit var tvQuestionText: TextView
    private lateinit var btnNext: Button
    private lateinit var modelLoader: ModelLoader

    private var isResolving = false
    private var lastCloudCheckTime = 0L
    private val cloudCheckIntervalMs = 2000L

    private lateinit var currentQuestion: Question
    private var modelPlaced = false
    private var questionStartMs: Long = 0L

    // Cloud Anchor hosting için
    private var isHosting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_treasure_hunt_game)

        arSceneView = findViewById(R.id.sceneView)
        chronoTimer = findViewById(R.id.chronoTimer)
        tvQuestionTitle = findViewById(R.id.tvQuestionTitle)
        tvQuestionText = findViewById(R.id.tvQuestionText)
        btnNext = findViewById(R.id.btnNext)

        modelLoader = ModelLoader(arSceneView.engine, this, CoroutineScope(Dispatchers.IO))

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
            session.configure(config)

            Log.d("ADMIN", "ARCore session created with Cloud Anchor mode: ${config.cloudAnchorMode}")
        }

        arSceneView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                handleTap(event)
                true
            } else {
                false
            }
        }

        arSceneView.onSessionUpdated = { session, _ ->
            if (!modelPlaced && ::currentQuestion.isInitialized && currentQuestion.cloudAnchorId.isNotEmpty()) {
                val now = SystemClock.elapsedRealtime()
                if (!isResolving && now - lastCloudCheckTime >= cloudCheckIntervalMs) {
                    lastCloudCheckTime = now
                    isResolving = true

                    session.resolveCloudAnchorAsync(currentQuestion.cloudAnchorId) { anchor, state ->
                        if (state == Anchor.CloudAnchorState.SUCCESS &&
                            anchor.trackingState == TrackingState.TRACKING
                        ) {
                            runOnUiThread {
                                placeModelOnAnchor(anchor, currentQuestion)
                                modelPlaced = true
                            }
                        }
                        isResolving = false
                    }
                }
            }
        }
    }

    private fun handleTap(event: MotionEvent) {
        if (isHosting) {
            Toast.makeText(this, "Already hosting, please wait...", Toast.LENGTH_SHORT).show()
            return
        }

        val session = arSceneView.session ?: run {
            Toast.makeText(this, "AR Session not ready", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val frame = session.update()

            // Tracking state kontrolü
            if (frame.camera.trackingState != TrackingState.TRACKING) {
                Toast.makeText(this, "Camera not tracking - move your device slowly", Toast.LENGTH_LONG).show()
                return
            }

            val hits = frame.hitTest(event.x, event.y)

            // Sadece düzlem (Plane) hit'lerini al
            val planeHit = hits.firstOrNull { hit ->
                val trackable = hit.trackable
                trackable is Plane && trackable.isPoseInPolygon(hit.hitPose)
            }

            if (planeHit == null) {
                Toast.makeText(this, "No surface detected - point at a flat surface", Toast.LENGTH_LONG).show()
                return
            }

            hostCloudAnchor(planeHit)

        } catch (e: Exception) {
            Log.e("ADMIN", "Error in handleTap", e)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hostCloudAnchor(hit: HitResult) {
        val session = arSceneView.session ?: return

        isHosting = true
        val anchor = hit.createAnchor()

        Toast.makeText(this, "Hosting to Cloud... Please wait", Toast.LENGTH_SHORT).show()
        Log.d("ADMIN", "Starting cloud anchor hosting...")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Cloud Anchor oluştur (ARCore 1.42.0 için senkron)
                val cloudAnchor = session.hostCloudAnchor(anchor)

                // State'i kontrol et ve bekle
                var attempts = 0
                val maxAttempts = 30 // 30 saniye timeout

                while (attempts < maxAttempts) {
                    val state = cloudAnchor.cloudAnchorState

                    Log.d("ADMIN", "Cloud Anchor State (attempt $attempts): $state")

                    when (state) {
                        Anchor.CloudAnchorState.SUCCESS -> {
                            val cloudId = cloudAnchor.cloudAnchorId
                            Log.d("ADMIN", "✅ Cloud Anchor SUCCESS! ID: $cloudId")

                            withContext(Dispatchers.Main) {
                                isHosting = false
                                Toast.makeText(
                                    this@TreasureHuntGameActivity,
                                    "SUCCESS! Cloud ID: $cloudId\n\nNow save this ID to Firestore!",
                                    Toast.LENGTH_LONG
                                ).show()

                                // Burada Firestore'a kaydetme işlemini yapabilirsin
                                // saveCloudAnchorIdToFirestore(cloudId)
                            }
                            return@launch
                        }

                        Anchor.CloudAnchorState.ERROR_INTERNAL,
                        Anchor.CloudAnchorState.ERROR_NOT_AUTHORIZED,
                        Anchor.CloudAnchorState.ERROR_RESOURCE_EXHAUSTED,
                        Anchor.CloudAnchorState.ERROR_HOSTING_DATASET_PROCESSING_FAILED,
                        Anchor.CloudAnchorState.ERROR_CLOUD_ID_NOT_FOUND,
                        Anchor.CloudAnchorState.ERROR_RESOLVING_LOCALIZATION_NO_MATCH,
                        Anchor.CloudAnchorState.ERROR_RESOLVING_SDK_VERSION_TOO_OLD,
                        Anchor.CloudAnchorState.ERROR_RESOLVING_SDK_VERSION_TOO_NEW,
                        Anchor.CloudAnchorState.ERROR_HOSTING_SERVICE_UNAVAILABLE -> {
                            Log.e("ADMIN", "❌ Cloud Anchor FAILED: $state")

                            withContext(Dispatchers.Main) {
                                isHosting = false
                                showErrorDialog(state)
                            }
                            return@launch
                        }

                        Anchor.CloudAnchorState.NONE,
                        Anchor.CloudAnchorState.TASK_IN_PROGRESS -> {
                            // Devam et, bekle
                            delay(1000)
                            attempts++
                        }

                        Anchor.CloudAnchorState.ERROR_SERVICE_UNAVAILABLE -> TODO()
                    }
                }

                // Timeout
                withContext(Dispatchers.Main) {
                    isHosting = false
                    Toast.makeText(
                        this@TreasureHuntGameActivity,
                        "Timeout - Hosting took too long. Try again.",
                        Toast.LENGTH_LONG
                    ).show()
                }

            } catch (e: Exception) {
                Log.e("ADMIN", "Exception during hosting", e)
                withContext(Dispatchers.Main) {
                    isHosting = false
                    Toast.makeText(
                        this@TreasureHuntGameActivity,
                        "Error: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun showErrorDialog(state: Anchor.CloudAnchorState) {
        val message = when (state) {
            Anchor.CloudAnchorState.ERROR_INTERNAL ->
                "Internal error. Please try again."
            Anchor.CloudAnchorState.ERROR_NOT_AUTHORIZED ->
                "❌ CRITICAL: ARCore Cloud Anchor API not enabled!\n\n" +
                        "Go to: https://console.cloud.google.com/\n" +
                        "1. Enable 'ARCore Cloud Anchor API'\n" +
                        "2. Add API Key to AndroidManifest.xml"
            Anchor.CloudAnchorState.ERROR_RESOURCE_EXHAUSTED ->
                "API quota exceeded. Wait or upgrade quota."
            Anchor.CloudAnchorState.ERROR_HOSTING_DATASET_PROCESSING_FAILED ->
                "Not enough visual features. Point at a textured surface."
            Anchor.CloudAnchorState.ERROR_HOSTING_SERVICE_UNAVAILABLE ->
                "Cloud service unavailable. Check internet connection."
            else ->
                "Unknown error: $state"
        }

        AlertDialog.Builder(this)
            .setTitle("Hosting Failed")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun loadQuestion(q: Question) {
        currentQuestion = q
        tvQuestionTitle.text = "Question ${q.id}"
        tvQuestionText.text = q.text
        questionStartMs = SystemClock.elapsedRealtime()
        chronoTimer.base = questionStartMs
        chronoTimer.start()
        modelPlaced = false
    }

    private fun placeModelOnAnchor(anchor: Anchor, q: Question) {
        val anchorNode = AnchorNode(arSceneView.engine, anchor)
        arSceneView.addChildNode(anchorNode)

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
                        showCorrectDialog()
                    }
                }
            } catch (e: Exception) {
                Log.e("ADMIN", "Error loading model", e)
            }
        }
    }

    private fun showCorrectDialog() {
        chronoTimer.stop()
        val elapsedMs = SystemClock.elapsedRealtime() - questionStartMs
        GameState.markSolved(currentQuestion.id, elapsedMs)

        AlertDialog.Builder(this)
            .setTitle("Correct!")
            .setMessage("You found the treasure!")
            .setPositiveButton("Next") { _, _ ->
                val next = GameState.nextUnsolvedAfter(currentQuestion.id)
                if (next != null) {
                    loadQuestion(next)
                } else {
                    Toast.makeText(this, "Congratulations! All questions solved!", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
            .setCancelable(false)
            .show()
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        arSceneView.destroy()
        super.onDestroy()
    }
}