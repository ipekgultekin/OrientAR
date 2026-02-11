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
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.node.ModelNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
            session.configure(config)
        }

        // Enable plane detection
        arSceneView.planeRenderer.isEnabled = true

        // Handle touch events for hosting anchors
        @Suppress("ClickableViewAccessibility")
        arSceneView.setOnTouchListener { _, motionEvent ->
            if (motionEvent.action == MotionEvent.ACTION_UP) {
                handleTap(motionEvent)
                true
            } else {
                false
            }
        }

        arSceneView.onSessionUpdated = { session, _ ->
            // PLAYER MODE: Resolve cloud anchor
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
        val session = arSceneView.session ?: return
        val frame = session.update() ?: return

        val hits = frame.hitTest(event.x, event.y)
        val planeHit = hits.firstOrNull { hit ->
            val trackable = hit.trackable
            trackable is Plane &&
                    trackable.isPoseInPolygon(hit.hitPose) &&
                    trackable.type == Plane.Type.HORIZONTAL_UPWARD_FACING
        }

        planeHit?.let { hit ->
            val anchor = hit.createAnchor()
            Toast.makeText(this, "Hosting to Cloud...", Toast.LENGTH_SHORT).show()

            session.hostCloudAnchorAsync(anchor, 365) { cloudAnchor, state ->
                if (state == Anchor.CloudAnchorState.SUCCESS) {
                    // Get cloud anchor ID using string parsing
                    val cloudId = try {
                        val anchorStr = cloudAnchor.toString()
                        Log.d("ADMIN", "Anchor toString: $anchorStr")

                        when {
                            anchorStr.contains("cloudAnchorId=") -> {
                                anchorStr.substringAfter("cloudAnchorId=")
                                    .substringBefore(",")
                                    .substringBefore("}")
                                    .trim()
                            }
                            else -> {
                                Log.w("ADMIN", "Could not parse cloudAnchorId")
                                "PARSE_ERROR"
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("ADMIN", "Error parsing cloud ID", e)
                        "ERROR"
                    }

                    Log.d("ADMIN", "Cloud Anchor ID: $cloudId")
                    runOnUiThread {
                        Toast.makeText(this, "Hosted! ID: $cloudId", Toast.LENGTH_LONG).show()
                    }
                } else {
                    Log.e("ADMIN", "Hosting failed: $state")
                    runOnUiThread {
                        Toast.makeText(this, "Hosting failed: $state", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
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
            val modelInstance = modelLoader.loadModelInstance(q.modelFilePath)
            withContext(Dispatchers.Main) {
                modelInstance?.let {
                    val modelNode = ModelNode(it).apply {
                        this.scale = Scale(q.modelScale)
                        this.rotation = Rotation(q.modelRotationX, q.modelRotationY, q.modelRotationZ)
                    }
                    anchorNode.addChildNode(modelNode)
                    showCorrectDialog()
                }
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
                if (next != null) loadQuestion(next) else finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
}