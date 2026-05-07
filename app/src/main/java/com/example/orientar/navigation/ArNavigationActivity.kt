package com.example.orientar.navigation

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.os.Handler
import android.view.View
import android.widget.*
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.*
import com.example.orientar.navigation.logic.ArUtils
import com.example.orientar.navigation.logic.CampusGraph
import com.example.orientar.navigation.logic.Node
import com.example.orientar.navigation.logic.Coordinate
import com.example.orientar.navigation.logic.PhantomRouteResult
import com.example.orientar.navigation.logic.PhantomStartMode
import com.example.orientar.navigation.rendering.CoordinateAligner
import com.example.orientar.navigation.rendering.SphereRefresher
import com.example.orientar.navigation.location.GPSBufferManager
import com.google.ar.core.*
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.arcore.createAnchorOrNull
import io.github.sceneview.ar.arcore.isValid
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.math.abs
import com.example.orientar.navigation.location.KalmanFilter
import com.example.orientar.navigation.location.HeadingFusionFilter
import com.example.orientar.navigation.util.FileLogger
import com.example.orientar.R

enum class AppState {
    STEP_0_ROUTE_SELECTION,
    STEP_1_COMPASS_CALIBRATION,
    STEP_2_GPS_COLLECTION,
    STEP_3_NAVIGATION
}

/**
 * ArNavigationActivity - CORRECTED for SceneView 2.0.3
 *
 * Key Fixes:
 * 1. Removed arView.resume() and arView.pause() (don't exist in 2.0.3)
 * 2. Fixed permission constant (ACCESS_FINE_LOCATION)
 * 3. Proper lifecycle management for SceneView 2.0.3
 */
class ArNavigationActivity : AppCompatActivity(), SensorEventListener {

    // ========================================================================================
    // UI COMPONENTS
    // ========================================================================================
    private lateinit var arView: ARSceneView
    private lateinit var tvInfo: TextView
    private lateinit var btnForceStart: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var layoutRouteSelection: LinearLayout
    private lateinit var spinnerStartNode: Spinner
    private lateinit var spinnerEndNode: Spinner
    private lateinit var btnConfirmRoute: Button
    private lateinit var layoutCalibration: LinearLayout
    private lateinit var tvStepTitle: TextView
    private lateinit var tvStepDesc: TextView
    private lateinit var ivStepIcon: ImageView
    private lateinit var layoutCompassHud: FrameLayout
    private lateinit var ivCompassArrow: ImageView
    private lateinit var tvCompassBearing: TextView
    private lateinit var tvRecalculating: TextView

    // Navigation UI elements
    private lateinit var layoutTopBar: LinearLayout
    private lateinit var layoutBottomCard: LinearLayout
    private lateinit var layoutDebugPanel: LinearLayout  // Changed from ScrollView
    private lateinit var layoutDebugButtons: LinearLayout  // NEW
    private lateinit var btnDebugToggle: ImageButton
    private lateinit var btnPhase3Toggle: ImageButton  // NEW
    private lateinit var btnDebugRecalibrate: Button  // NEW
    private lateinit var btnDebugFlip: Button  // NEW
    private lateinit var btnShareLogs: Button  // Share logs button
    private lateinit var btnCloseDebugPanel: ImageButton
    private lateinit var btnRecalibrate: Button
    private lateinit var btnEndNavigation: Button
    private lateinit var tvDestinationName: TextView
    private lateinit var tvRouteInfo: TextView
    private lateinit var tvRemainingDistance: TextView
    private lateinit var progressNavigation: ProgressBar
    private lateinit var tvProgressPercent: TextView
    private lateinit var tvNextCheckpoint: TextView
    private lateinit var tvETA: TextView
    private lateinit var tvDebugInfo: TextView

    //LOG
    private var gpsLogCounter = 0


    // UI State
    private var isDebugMode = false
    private var hasArrivedAtDestination = false
    private var navigationStartTime: Long = 0L

    // Arrival celebration views
    private lateinit var layoutArrivalCelebration: LinearLayout
    private lateinit var tvArrivalDestination: TextView
    private lateinit var tvArrivalTime: TextView
    private lateinit var tvArrivalDistance: TextView
    private lateinit var btnArrivalClose: Button
    private lateinit var btnArrivalShareLog: Button

    // ========================================================================================
    // CORE SYSTEMS
    // ========================================================================================
    private lateinit var coordinateAligner: CoordinateAligner
    private lateinit var gpsBufferManager: GPSBufferManager
    private lateinit var campusGraph: CampusGraph

    // ========================================================================================
    // AR SESSION & PLANE DETECTION
    // ========================================================================================
    private var isARConfigured = false
    private var planeDetectionStartTime: Long = 0

    // ========================================================================================
    // SENSORS & ORIENTATION
    // ========================================================================================
    private lateinit var sensorManager: SensorManager
    private var rotationVector: Sensor? = null
    private var currentBearing: Float = 0f
    private val rotationMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)  // For remapCoordinateSystem (upright phone)
    private val orientationAngles = FloatArray(3)
    private var lastRemapLogTime = 0L  // Throttle COMPASS_REMAP diagnostic log
    private var smoothedAzimuth: Float = 0f
    private var magneticDeclination: Float = 0f
    private var lastKnownAccuracy = 0
    private var currentTrueBearing: Float = 0f
    // FOR SENSOR FUSION
    private lateinit var kalmanFilter: KalmanFilter
    private lateinit var headingFusionFilter: HeadingFusionFilter
    private var gyroscope: Sensor? = null
    private var useSensorFusion: Boolean = true  // Toggle for testing
    //GPS Throttling
    private var lastLocationUpdateTime = 0L
    private val MIN_LOCATION_UPDATE_INTERVAL_MS = 100L  // Max 10 updates/second

    // ========================================================================================
    // ADAPTIVE SENSOR FUSION
    // ========================================================================================
    private var adaptiveFusionEnabled: Boolean = true  // Auto-disable in poor GPS
    private val ADAPTIVE_FUSION_GPS_THRESHOLD = 20.0f  // Disable fusion if accuracy > 20m
    private val ADAPTIVE_FUSION_RECOVERY_THRESHOLD = 12.0f  // Re-enable if accuracy < 12m
    private var fusionDisabledByAdaptive: Boolean = false  // Track if disabled by adaptive logic
    // ========================================================================================
    // PHASE 3: Performance Rendering State
    // ========================================================================================
    private var usePhase3Rendering: Boolean = true  // Toggle for testing

    // ========================================================================================
    // LOCATION SERVICES
    // ========================================================================================
    private val fusedLocationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private lateinit var locationCallback: LocationCallback

    // ========================================================================================
    // ROUTING & NAVIGATION STATE
    // ========================================================================================
    private var currentState = AppState.STEP_0_ROUTE_SELECTION
    private var lastAnchorLocation: Location? = null
    private var pendingGpsForAnchor: Location? = null  // GPS to use when anchor is created
    private var currentUserLocation: Location? = null  // Latest GPS for segment updates
    private var staticRouteReport: String = ""
    private var selectedStartNode: Node? = null
    private var selectedEndNode: Node? = null
    private var routeCoords: List<Coordinate> = emptyList()
    private var routeNodePath: List<Node> = emptyList()
    private var lastClosestRouteIndex: Int = 0
    private var lastRenderProgressIndex: Int = 0
    private val visitedNodeIds = HashSet<Int>()

    // ========================================================================================
    // CONFIGURATION
    // ========================================================================================
    private val REANCHOR_THRESHOLD_METERS = 8.0f
    private val REANCHOR_COOLDOWN_MS = 5000L
    private val VISITATION_DISTANCE_THRESHOLD = 5.0f
    private val RERENDER_PROGRESS_DELTA = 12
    private var lastReAnchorTime: Long = 0
    @Volatile
    private var pendingAnchorCreation = false
    private var minCalibrationTimePassed = false


    // ========================================================================================
    // ANCHOR-ROUTE SYNCHRONIZATION STATE
    // ========================================================================================
    // These flags ensure route rendering only happens AFTER anchor is successfully created
    @Volatile
    private var isRouteRenderingPending = false  // True when route should render after anchor
    private var anchorCreationAttempts = 0       // Track retry attempts

    private var lastPlaneLogTime: Long = 0

    // Track which anchor strategy was used (for debugging/UI)
    private var lastAnchorStrategy: String = "None"

    // Flag to use outdoor-optimized anchoring (can be toggled for testing)
    private var useOutdoorAnchoring = true
    // Compass sampling (kept for HeadingFusionFilter, NOT used for yaw init)
    private val compassHistory = ArrayDeque<Float>(20)
    private var lastCompassSampleTime = 0L
    private val COMPASS_SAMPLE_INTERVAL_MS = 200L

    // Dual-delta alignment state
    private var waitingForDualDelta = false
    private var smoothedCameraY: Float = Float.NaN
    private val CAMERA_Y_SMOOTHING = 0.15f  // Lower = smoother, higher = more responsive
    private var dualDeltaStartTime = 0L
    private var lastHeadingInitLogTime = 0L
    private val DUAL_DELTA_TIMEOUT_SECONDS = 30  // Compass fallback after 30s
    private var lastCompassLogTime = 0L  // Diagnostic: throttle compass logging in STEP_1
    private var previousGpsForBearing: Location? = null  // For computing own GPS bearing
    private var sphereRefresher: SphereRefresher? = null  // Periodic full-recreate renderer
    private var pendingCompassInit = false  // Deferred compass init until phone is upright
    private var isRecalibrating = false  // Guard: prevent refresh during recalibration
    private var pendingCompassInitLocation: Location? = null
    private var lastGoodArYaw: Double = 0.0  // Cached yaw for low xzMagnitude frames

    // Diagnostic fields
    private var lastTrackingState: TrackingState? = null
    private var trackingStateChangeTime = 0L
    private var lastFusionLogTime = 0L
    private var frameCount = 0
    private var lastFpsLogTime = 0L

    /** Extract yaw from ARCore pose quaternion (tilt-independent), with xzMagnitude filter */
    private fun getArYaw(): Double {
        val pose = arView.frame?.camera?.displayOrientedPose

        if (pose != null) {
            val zAxis = pose.zAxis
            val xzMagnitude = kotlin.math.sqrt(zAxis[0] * zAxis[0] + zAxis[2] * zAxis[2])

            if (xzMagnitude > 0.7) {
                val yaw = coordinateAligner.calculateYawFromPose(pose)
                lastGoodArYaw = yaw
                return yaw
            } else {
                FileLogger.d("YAW_CALC", "Low xzMag=${String.format("%.2f", xzMagnitude)} — using cached yaw=${lastGoodArYaw.toInt()}°")
                return lastGoodArYaw
            }
        }

        // Fallback to forwardDirection
        return coordinateAligner.calculateYawFromForward(
            arView.cameraNode.forwardDirection.x,
            arView.cameraNode.forwardDirection.z
        )
    }

    // ========================================================================================
    // PERMISSION HANDLING - FIXED
    // ========================================================================================
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cam = permissions[Manifest.permission.CAMERA] ?: false
        val loc = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false  // FIXED

        if (cam && loc) {
            initSensorsAndLocation()
        } else {
            Toast.makeText(this, "❌ Permissions required", Toast.LENGTH_LONG).show()
            updateStateUI(AppState.STEP_0_ROUTE_SELECTION)
        }
    }

    // ========================================================================================
    // LIFECYCLE
    // ========================================================================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize file logger for offline debugging
        FileLogger.init(this)
        FileLogger.section("APP STARTED")
        setContentView(R.layout.activity_ar_navigation)

        FileLogger.d("AR_LIFECYCLE", "onCreate")

        initializeUI()
        initializeSensors()
        initializeSystems()

        campusGraph = CampusGraph(this)
        handleStartupMode()
    }

    override fun onResume() {
        super.onResume()

        FileLogger.d("AR_LIFECYCLE", "onResume")

        // Initialize AR session in onResume
        if (!isARConfigured) {
            initializeARSession()
        }

        rotationVector?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        //Register gyroscope for heading fusion
        if (useSensorFusion) {
            gyroscope?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
                FileLogger.d("AR_SENSORS", "Gyroscope listener registered")
            }
        }
    }

    override fun onPause() {
        super.onPause()

        FileLogger.d("AR_LIFECYCLE", "onPause")

        sensorManager.unregisterListener(this)
        if (this::locationCallback.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }

        // SceneView 2.0.3 handles pause automatically via lifecycle
    }

    /**
     * Cleanup all resources when activity is destroyed.
     *
     * This ensures proper cleanup of:
     * 1. Sensor listeners
     * 2. Location updates
     * 3. AR renderer (clears anchor, spheres, materials)
     * 4. Outdoor anchor manager
     * 5. Coordinate aligner
     */
    override fun onDestroy() {
        try {
            FileLogger.d("AR_LIFECYCLE", "onDestroy — cleaning up resources")

            // 1. SphereRefresher cleanup (SceneView still alive)
            try {
                sphereRefresher?.clearAll()
                sphereRefresher = null
                FileLogger.d("AR_LIFECYCLE", "SphereRefresher cleared")
            } catch (e: Exception) {
                android.util.Log.e("AR_LIFECYCLE", "SphereRefresher cleanup failed", e)
            }

            // 2. Unregister sensor listener
            try {
                if (::sensorManager.isInitialized) {
                    sensorManager.unregisterListener(this)
                }
            } catch (e: Exception) {
                android.util.Log.e("AR_LIFECYCLE", "Sensor unregister failed", e)
            }

            // 3. Stop location updates
            try {
                if (::locationCallback.isInitialized) {
                    fusedLocationClient.removeLocationUpdates(locationCallback)
                }
            } catch (e: Exception) {
                android.util.Log.e("AR_LIFECYCLE", "Location stop failed", e)
            }

            // 4. Reset coordinate aligner
            try {
                if (::coordinateAligner.isInitialized) {
                    coordinateAligner.reset()
                }
            } catch (e: Exception) {
                android.util.Log.e("AR_LIFECYCLE", "CoordinateAligner reset failed", e)
            }

            // 8. Shutdown FileLogger LAST (no FileLogger calls after this)
            try {
                FileLogger.d("AR_LIFECYCLE", "All resources cleaned up — shutting down logger")
                FileLogger.shutdown()
            } catch (e: Exception) {
                android.util.Log.e("AR_LIFECYCLE", "FileLogger shutdown failed", e)
            }

        } catch (e: Exception) {
            // Top-level catch — prevent any exception from crashing the process
            android.util.Log.e("AR_LIFECYCLE", "onDestroy cleanup failed", e)
        } finally {
            // super.onDestroy() LAST — destroys view hierarchy (SceneView, ARCore session)
            super.onDestroy()
        }
    }

    // ========================================================================================
    // AR SESSION INITIALIZATION
    // ========================================================================================
    private fun initializeARSession() {
        FileLogger.d("AR_CONFIG", "Initializing AR session")

        // Check ARCore availability
        val availability = ArCoreApk.getInstance().checkAvailability(this)

        FileLogger.d("AR_CONFIG", "ARCore availability: $availability")

        if (availability != ArCoreApk.Availability.SUPPORTED_INSTALLED) {
            try {
                when (ArCoreApk.getInstance().requestInstall(this, true)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        FileLogger.d("AR_CONFIG", "ARCore installation requested")
                        return
                    }
                    ArCoreApk.InstallStatus.INSTALLED -> {
                        FileLogger.d("AR_CONFIG", "ARCore installed")
                    }
                    null -> {
                        Toast.makeText(this, "❌ ARCore not available", Toast.LENGTH_LONG).show()
                        return
                    }
                }
            } catch (e: Exception) {
                FileLogger.e("AR_CONFIG", "ARCore install failed", e)
                FileLogger.e("AR_CONFIG", "ARCore install failed: ${e.message}")
                Toast.makeText(this, "❌ ARCore error: ${e.message}", Toast.LENGTH_LONG).show()
                return
            }
        }

        // Configure AR scene
        configureARScene()
    }

    private fun configureARScene() {
        FileLogger.d("AR_CONFIG", "Configuring AR scene for outdoor navigation")

        // Disable plane renderer — SphereRefresher doesn't use planes
        arView.planeRenderer.isVisible = false
        arView.planeRenderer.isEnabled = false

        arView.configureSession { session, config ->
            try {
                // ============================================================================
                // MINIMAL CONFIG: Only enable what SphereRefresher needs
                // ============================================================================
                // SphereRefresher creates identity-rotation anchors from camera pose.
                // It doesn't use planes, depth, light estimation, or instant placement.
                // Disabling these frees ~55% CPU on Samsung A26, preventing tracking PAUSED.
                // ============================================================================

                config.lightEstimationMode = Config.LightEstimationMode.DISABLED
                config.planeFindingMode = Config.PlaneFindingMode.DISABLED
                config.depthMode = Config.DepthMode.DISABLED
                config.instantPlacementMode = Config.InstantPlacementMode.DISABLED

                // Keep: auto focus (needed for outdoor) and latest camera image (needed for tracking)
                config.focusMode = Config.FocusMode.AUTO
                config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE

                FileLogger.d("AR_CONFIG", "✅ Minimal config: planes=OFF, depth=OFF, light=OFF, instant=OFF")

                // ============================================================================
                // CAMERA CONFIGURATION
                // ============================================================================
                val filter = CameraConfigFilter(session)
                filter.facingDirection = CameraConfig.FacingDirection.BACK

                val configs = session.getSupportedCameraConfigs(filter)
                if (configs.isNotEmpty()) {
                    session.cameraConfig = configs[0]
                    FileLogger.d("AR_CONFIG", "✅ Camera configured: ${configs[0]}")
                }

                FileLogger.d("AR_CONFIG", "✅ Session configured successfully for outdoor AR")
                isARConfigured = true

            } catch (e: Exception) {
                FileLogger.e("AR_CONFIG", "Session configuration failed", e)
                FileLogger.e("AR_CONFIG", "Session config failed: ${e.message}")
                runOnUiThread {
                    Toast.makeText(this, "⚠️ AR config failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        // ============================================================================
        // FRAME UPDATE CALLBACK
        // ============================================================================
        arView.onSessionUpdated = { session, frame ->
            handleARFrameUpdate(session, frame)
        }

        // Log depth support status for diagnostics
        arView.session?.let { session ->
            val depthSupported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
            FileLogger.d("AR_CONFIG", "Device depth support: $depthSupported")
        }
    }

    // ========================================================================================
    // AR FRAME UPDATE - WITH PLANE DETECTION
    // ========================================================================================
    private fun handleARFrameUpdate(session: Session, frame: Frame) {
        val camera = frame.camera

        // LOG 1: Tracking state transitions + Recovery refresh (Fix 2B)
        val currentTrackingState = camera.trackingState
        if (currentTrackingState != lastTrackingState) {
            val duration = if (trackingStateChangeTime > 0) System.currentTimeMillis() - trackingStateChangeTime else 0
            FileLogger.d("TRACKING_TRANSITION",
                "${lastTrackingState?.name ?: "INIT"} → ${currentTrackingState.name} " +
                "(was ${lastTrackingState?.name ?: "INIT"} for ${duration}ms)")

            // Recovery refresh: when tracking resumes after a gap, trigger immediate sphere refresh
            if (currentTrackingState == TrackingState.TRACKING && lastTrackingState == TrackingState.PAUSED) {
                val lastSuccess = sphereRefresher?.lastSuccessfulRefreshTime ?: 0L
                val loc = currentUserLocation
                if (lastSuccess > 0L) {
                    val gap = System.currentTimeMillis() - lastSuccess
                    if (gap > 1500 && loc != null && sphereRefresher?.isCurrentlyRefreshing != true
                        && coordinateAligner.isInitialized() && !isRecalibrating) {
                        FileLogger.d("RECOVERY_REFRESH", "Tracking recovered, gap=${gap}ms — triggering refresh")
                        sphereRefresher?.refresh(loc, frame, session)
                    }
                }
            }

            lastTrackingState = currentTrackingState
            trackingStateChangeTime = System.currentTimeMillis()
        }

        // LOG 4: FPS counter — DISABLED (confirmed stable 60fps in log28-30)
        // frameCount++
        // val fpsNow = System.currentTimeMillis()
        // if (fpsNow - lastFpsLogTime > 10000) {
        //     val elapsed = fpsNow - lastFpsLogTime
        //     val fps = if (elapsed > 0) frameCount * 1000f / elapsed else 0f
        //     FileLogger.d("AR_FPS", "fps=${String.format("%.1f", fps)} frames=$frameCount in ${elapsed}ms")
        //     frameCount = 0
        //     lastFpsLogTime = fpsNow
        // }

        when (camera.trackingState) {
            TrackingState.TRACKING -> {
                if (currentState == AppState.STEP_3_NAVIGATION) {
                    handlePlaneDetectionAndAnchor(session, frame)
                }
            }
            TrackingState.PAUSED -> {
                val reason = camera.trackingFailureReason
                handleTrackingFailure(reason)
            }
            TrackingState.STOPPED -> {
                // FileLogger.w("AR_TRACKING", "Tracking stopped")
            }
        }
    }
    /**
     * Toggle Phase 3 rendering on/off.
     * Useful for testing and comparison.
     */
    /**
     * Handles plane detection and anchor creation during navigation.
     * SphereRefresher manages its own identity-rotation anchors.
     *
     * @param frame The current AR frame from ARCore
     */
    private fun handlePlaneDetectionAndAnchor(session: Session, frame: Frame) {
        // ====================================================================
        // DUAL-DELTA HEADING INIT: Compass shortcut + timeout
        // ====================================================================
        if (waitingForDualDelta && !coordinateAligner.isInitialized()) {
            val pose = frame.camera.displayOrientedPose
            val zAxis = pose.zAxis
            val xzMagnitude = kotlin.math.sqrt(zAxis[0] * zAxis[0] + zAxis[2] * zAxis[2])

            if (xzMagnitude > 0.7) {
                val arYaw = getArYaw()

                // Compute route bearing (same logic as COMPASS_SANITY)
                val userLoc = currentUserLocation
                if (userLoc != null && routeCoords.size > 1) {
                    var routeTargetIdx = 1
                    for (i in 1 until minOf(routeCoords.size, 10)) {
                        val dist = ArUtils.distanceMeters(
                            userLoc.latitude, userLoc.longitude,
                            routeCoords[i].lat, routeCoords[i].lng
                        )
                        if (dist > 10.0) { routeTargetIdx = i; break }
                    }
                    val routeBearing = ArUtils.bearingDeg(
                        userLoc.latitude, userLoc.longitude,
                        routeCoords[routeTargetIdx].lat, routeCoords[routeTargetIdx].lng
                    )
                    val compassVsRoute = ArUtils.normalizeAngleDeg(currentTrueBearing.toDouble() - routeBearing)
                    val compassDiff = Math.abs(compassVsRoute)

                    // SHORTCUT: Compass agrees with route within 15° — trust it immediately
                    if (compassDiff < 15.0) {
                        FileLogger.d("HEADING_INIT", "Compass shortcut: compass=${currentTrueBearing.toInt()}° " +
                            "agrees with route=${routeBearing.toInt()}° (diff=${compassDiff.toInt()}°) — using compass immediately")
                        coordinateAligner.initialize(currentTrueBearing.toDouble(), arYaw)
                        waitingForDualDelta = false
                        pendingCompassInit = false
                        isRecalibrating = false
                        if (sphereRefresher == null) initializeSphereRefresher()
                        runOnUiThread {
                            Toast.makeText(this, "🧭 Heading ready — navigation starting", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        val now = System.currentTimeMillis()
                        if (now - lastHeadingInitLogTime > 1000L) {
                            lastHeadingInitLogTime = now
                            FileLogger.d("HEADING_INIT", "Compass disagrees with route: compass=${currentTrueBearing.toInt()}° " +
                                "vs route=${routeBearing.toInt()}° (diff=${compassDiff.toInt()}°) — waiting for walk calibration")
                        }
                    }
                }

                // TIMEOUT: Fall back to compass via pendingCompassInit after 30s
                val elapsed = System.currentTimeMillis() - dualDeltaStartTime
                if (waitingForDualDelta && elapsed > DUAL_DELTA_TIMEOUT_SECONDS * 1000L) {
                    FileLogger.w("HEADING_INIT", "Dual-delta timeout (${elapsed / 1000}s) — falling back to compass init")
                    waitingForDualDelta = false
                    // pendingCompassInit is already true — let the existing compass init flow handle it
                    // The existing COMPASS_SANITY check will apply route bearing fallback if compass is bad
                }
            }
        }

        // ====================================================================
        // DEFERRED COMPASS INIT: Wait for phone to be upright (fallback path)
        // ====================================================================
        if (pendingCompassInit && !waitingForDualDelta) {
            val pose = frame.camera.displayOrientedPose
            val zAxis = pose.zAxis
            val xzMagnitude = kotlin.math.sqrt(zAxis[0] * zAxis[0] + zAxis[2] * zAxis[2])

            if (xzMagnitude > 0.7) {
                val arYaw = getArYaw()

                // Route-direction sanity check: use route bearing if compass is >40° off
                var initBearing = currentTrueBearing.toDouble()
                val userLoc = currentUserLocation
                if (userLoc != null && routeCoords.size > 1) {
                    // Find a route point >10m away for stable bearing
                    var routeTargetIdx = 1
                    for (i in 1 until minOf(routeCoords.size, 10)) {
                        val dist = ArUtils.distanceMeters(
                            userLoc.latitude, userLoc.longitude,
                            routeCoords[i].lat, routeCoords[i].lng
                        )
                        if (dist > 10.0) { routeTargetIdx = i; break }
                    }
                    val routeBearing = ArUtils.bearingDeg(
                        userLoc.latitude, userLoc.longitude,
                        routeCoords[routeTargetIdx].lat, routeCoords[routeTargetIdx].lng
                    )
                    val compassVsRoute = ArUtils.normalizeAngleDeg(currentTrueBearing.toDouble() - routeBearing)
                    if (Math.abs(compassVsRoute) > 40.0) {
                        FileLogger.w("COMPASS_SANITY",
                            "Compass ${currentTrueBearing.toInt()}° disagrees with route ${routeBearing.toInt()}° " +
                            "by ${compassVsRoute.toInt()}° — USING ROUTE BEARING")
                        initBearing = routeBearing
                    } else {
                        FileLogger.d("COMPASS_SANITY",
                            "Compass ${currentTrueBearing.toInt()}° agrees with route ${routeBearing.toInt()}° " +
                            "(diff=${compassVsRoute.toInt()}°) — using compass")
                    }
                }

                coordinateAligner.initialize(initBearing, arYaw)
                pendingCompassInit = false
                isRecalibrating = false  // Deferred init done — allow refresh

                FileLogger.d("COMPASS_INIT_DETAIL", "Deferred init fired: " +
                    "bearing=${initBearing.toInt()}°, arYaw=${arYaw.toInt()}°, " +
                    "offset=${coordinateAligner.getYawOffset().toInt()}°, xzMag=${String.format("%.2f", xzMagnitude)}")

                FileLogger.d("COMPASS_INIT_REMAP",
                    "bearing=${String.format("%.1f", currentTrueBearing)}° " +
                    "xzMag=${String.format("%.3f", xzMagnitude)} " +
                    "arYaw=${String.format("%.1f", arYaw)}° " +
                    "yawOffset=${String.format("%.1f", coordinateAligner.getYawOffset())}°")

                // Initialize SphereRefresher now that heading is available
                if (sphereRefresher == null) initializeSphereRefresher()

                runOnUiThread {
                    Toast.makeText(this, "🧭 Navigation starting...", Toast.LENGTH_SHORT).show()
                }
            }
            // Don't return — SphereRefresher will create spheres on next GPS update
        }

        // ========================================================================
        // PER-FRAME: Update sphere Y positions for ARCore vertical drift
        // ========================================================================
        // SphereRefresher manages its own anchor and spheres. We just need to
        // update Y positions every frame for smooth vertical tracking.
        // Sphere creation/destruction happens in handleNavigationUpdate() on GPS.
        // ========================================================================
        if (sphereRefresher?.hasActiveSpheres() == true) {
            val rawCameraY = frame.camera.pose.ty()

            // Guard: Reset smoother if camera Y jumped >10m (tracking loss/recovery)
            if (!smoothedCameraY.isNaN() && Math.abs(rawCameraY - smoothedCameraY) > 10f) {
                FileLogger.w("Y_JUMP", "Camera Y jumped ${String.format("%.0f", rawCameraY - smoothedCameraY)}m " +
                    "(raw=${String.format("%.1f", rawCameraY)}, smoothed=${String.format("%.1f", smoothedCameraY)}). Resetting.")
                smoothedCameraY = rawCameraY
            }

            smoothedCameraY = if (smoothedCameraY.isNaN()) {
                rawCameraY
            } else {
                smoothedCameraY + CAMERA_Y_SMOOTHING * (rawCameraY - smoothedCameraY)
            }
            sphereRefresher?.updateYPositions(smoothedCameraY)

            if (System.currentTimeMillis() % 3000 < 100) {
                FileLogger.d("REFRESH_Y", "cameraY=${String.format("%.1f", rawCameraY)}, smoothed=${String.format("%.1f", smoothedCameraY)}")
            }
        }

        // ============================================================================
        // NOTE: Anchor creation and sphere rendering are now handled by
        // SphereRefresher in handleNavigationUpdate(). No CASE 2 needed.
        // SphereRefresher creates its own anchors at camera position on each
        // GPS update, keeping spheres within 8m of their anchor per ARCore best practices.
        // ============================================================================
    }

    private var lastTrackingLogTime = 0L

    private fun handleTrackingFailure(reason: TrackingFailureReason?) {
        // AR_TRACKING log — DISABLED (confirmed all NONE in log30)
        // val now = System.currentTimeMillis()
        // if (now - lastTrackingLogTime > 3000) {
        //     lastTrackingLogTime = now
        //     FileLogger.d("AR_TRACKING", "PAUSED: reason=${reason?.name ?: "UNKNOWN"}")
        // }

        val message = when (reason) {
            TrackingFailureReason.NONE -> return
            TrackingFailureReason.BAD_STATE -> "⚠️ AR initializing..."
            TrackingFailureReason.INSUFFICIENT_LIGHT -> "💡 Need more light!"
            TrackingFailureReason.EXCESSIVE_MOTION -> "🤚 Move phone slower"
            TrackingFailureReason.INSUFFICIENT_FEATURES -> "🎯 Point at textured surface"
            TrackingFailureReason.CAMERA_UNAVAILABLE -> "📷 Camera unavailable"
            else -> "⚠️ AR tracking lost"
        }

        runOnUiThread {
            tvInfo.text = message
        }
    }

    // (Legacy anchor creation functions removed — SphereRefresher handles anchors)

    // ========================================================================================
    // UI & SYSTEM INITIALIZATION
    // ========================================================================================
    private fun initializeUI() {
        arView = findViewById(R.id.arView)
        tvInfo = findViewById(R.id.tvInfo)
        tvRecalculating = findViewById(R.id.tvRecalculating)
        var lastFlipTime = 0L
        tvRecalculating.setOnClickListener {
            if (tvRecalculating.visibility == View.VISIBLE) {
                val now = System.currentTimeMillis()

                // If tapped within 2 seconds of last flip, do full recalibration
                if (now - lastFlipTime < 2000) {
                    FileLogger.d("AR_RECALIB", "Double-tap detected - full recalibration")
                    forceRecalibration()
                    lastFlipTime = 0L  // Reset
                } else {
                    // First tap - try 180° flip
                    FileLogger.d("AR_RECALIB", "Single tap - trying 180° flip")
                    flip180Degrees()
                    lastFlipTime = now
                }
            }
        }
        btnForceStart = findViewById(R.id.btnForceStart)
        progressBar = findViewById(R.id.progressBar)
        layoutRouteSelection = findViewById(R.id.layoutRouteSelection)
        spinnerStartNode = findViewById(R.id.spinnerStartNode)
        spinnerEndNode = findViewById(R.id.spinnerEndNode)
        btnConfirmRoute = findViewById(R.id.btnConfirmRoute)
        layoutCalibration = findViewById(R.id.layoutCalibration)
        tvStepTitle = findViewById(R.id.tvStepTitle)
        tvStepDesc = findViewById(R.id.tvStepDesc)
        ivStepIcon = findViewById(R.id.ivStepIcon)
        layoutCompassHud = findViewById(R.id.layoutCompassHud)
        ivCompassArrow = findViewById(R.id.ivCompassArrow)
        tvCompassBearing = findViewById(R.id.tvCompassBearing)

        // Navigation UI
        layoutTopBar = findViewById(R.id.layoutTopBar)
        layoutBottomCard = findViewById(R.id.layoutBottomCard)
        layoutDebugPanel = findViewById(R.id.layoutDebugPanel)
        layoutDebugButtons = findViewById(R.id.layoutDebugButtons)
        btnDebugToggle = findViewById(R.id.btnDebugToggle)
        btnPhase3Toggle = findViewById(R.id.btnPhase3Toggle)
        btnDebugRecalibrate = findViewById(R.id.btnDebugRecalibrate)
        btnDebugFlip = findViewById(R.id.btnDebugFlip)
        btnShareLogs = findViewById(R.id.btnShareLogs)
        btnCloseDebugPanel = findViewById(R.id.btnCloseDebugPanel)
        btnRecalibrate = findViewById(R.id.btnRecalibrate)
        btnEndNavigation = findViewById(R.id.btnEndNavigation)
        tvDestinationName = findViewById(R.id.tvDestinationName)
        tvRouteInfo = findViewById(R.id.tvRouteInfo)
        tvRemainingDistance = findViewById(R.id.tvRemainingDistance)
        progressNavigation = findViewById(R.id.progressNavigation)
        tvProgressPercent = findViewById(R.id.tvProgressPercent)
        tvNextCheckpoint = findViewById(R.id.tvNextCheckpoint)
        tvETA = findViewById(R.id.tvETA)
        tvDebugInfo = findViewById(R.id.tvDebugInfo)
        // Arrival celebration views
        layoutArrivalCelebration = findViewById(R.id.layoutArrivalCelebration)
        tvArrivalDestination = findViewById(R.id.tvArrivalDestination)
        tvArrivalTime = findViewById(R.id.tvArrivalTime)
        tvArrivalDistance = findViewById(R.id.tvArrivalDistance)
        btnArrivalClose = findViewById(R.id.btnArrivalClose)
        btnArrivalShareLog = findViewById(R.id.btnArrivalShareLog)

        // Setup navigation button listeners
        setupNavigationUI()


        btnForceStart.setOnClickListener { handleForceStart() }
        btnConfirmRoute.setOnClickListener { confirmRouteAndStart() }
    }
    private fun setupNavigationUI() {
        // Debug toggle button
        btnDebugToggle.setOnClickListener {
            performHapticFeedback(it)
            isDebugMode = !isDebugMode
            btnDebugToggle.isSelected = isDebugMode
            layoutDebugPanel.visibility = if (isDebugMode) View.VISIBLE else View.GONE

            // Force immediate UI update when debug is shown
            if (isDebugMode) {
                currentUserLocation?.let { updateLiveUI(it) }
            }
        }
        btnCloseDebugPanel.setOnClickListener {
            performHapticFeedback(it)
            isDebugMode = false
            btnDebugToggle.isSelected = false
            layoutDebugPanel.visibility = View.GONE
        }

        // Quick recalibrate button in debug panel
        btnDebugRecalibrate.setOnClickListener {
            performHapticFeedback(it)
            forceRecalibration()
        }

        // Quick flip 180 button in debug panel
        btnDebugFlip.setOnClickListener {
            performHapticFeedback(it)
            flip180Degrees()
        }
        // Share logs button
        btnShareLogs.setOnClickListener {
            performHapticFeedback(it)
            FileLogger.shareLogFile(this)
        }

        // Recalibrate button
        btnRecalibrate.setOnClickListener {
            performHapticFeedback(it)
            forceRecalibration()
        }

        // End navigation button
        btnEndNavigation.setOnClickListener {
            performHapticFeedback(it)
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("End Navigation?")
                .setMessage("Are you sure you want to stop navigating?")
                .setPositiveButton("End") { _, _ -> finish() }
                .setNegativeButton("Cancel", null)
                .show()
        }
        // Arrival close button
        btnArrivalClose.setOnClickListener {
            performHapticFeedback(it)
            finish()
        }
        // Arrival Share Log button
        btnArrivalShareLog.setOnClickListener {
            performHapticFeedback(it)
            FileLogger.shareLogFile(this)
        }
    }

    private fun performHapticFeedback(view: View) {
        view.performHapticFeedback(
            android.view.HapticFeedbackConstants.VIRTUAL_KEY,
            android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
    }
    private fun playCheckpointReachedAnimation(nodeName: String) {
        runOnUiThread {
            // Haptic feedback - stronger for checkpoint
            progressNavigation.performHapticFeedback(
                android.view.HapticFeedbackConstants.LONG_PRESS,
                android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )

            // Pulse animation on progress bar
            progressNavigation.animate()
                .scaleX(1.1f)
                .scaleY(1.3f)
                .setDuration(150)
                .withEndAction {
                    progressNavigation.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(150)
                        .start()
                }
                .start()

            // Flash animation on bottom card
            layoutBottomCard.animate()
                .alpha(0.7f)
                .setDuration(100)
                .withEndAction {
                    layoutBottomCard.animate()
                        .alpha(1f)
                        .setDuration(100)
                        .start()
                }
                .start()

            // Show checkpoint toast with name
            Toast.makeText(
                this,
                "✅ Reached: $nodeName",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    private fun showArrivalCelebration() {
        if (hasArrivedAtDestination) return  // Prevent showing twice
        hasArrivedAtDestination = true

        runOnUiThread {
            // Strong haptic feedback for arrival
            layoutArrivalCelebration.performHapticFeedback(
                android.view.HapticFeedbackConstants.LONG_PRESS,
                android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )

            // Calculate trip stats
            val tripDurationMs = System.currentTimeMillis() - navigationStartTime
            val tripMinutes = (tripDurationMs / 1000 / 60).toInt()
            val tripSeconds = ((tripDurationMs / 1000) % 60).toInt()
            val tripTimeFormatted = String.format("%d:%02d", tripMinutes, tripSeconds)

            val totalDistance = calculateRouteLength()
            val distanceFormatted = formatDistance(totalDistance)

            // Set celebration content
            tvArrivalDestination.text = selectedEndNode?.name ?: "Destination"
            tvArrivalTime.text = tripTimeFormatted
            tvArrivalDistance.text = distanceFormatted

            // Hide navigation UI
            layoutTopBar.visibility = View.GONE
            layoutBottomCard.visibility = View.GONE
            layoutCompassHud.visibility = View.GONE
            layoutDebugButtons.visibility = View.GONE
            layoutDebugPanel.visibility = View.GONE

            // Show celebration with animation
            layoutArrivalCelebration.alpha = 0f
            layoutArrivalCelebration.visibility = View.VISIBLE
            layoutArrivalCelebration.animate()
                .alpha(1f)
                .setDuration(500)
                .start()

            // ============================================================================
            // BUG-009 FIX: Safe child view access for emoji animation
            // ============================================================================
            // PROBLEM: getChildAt(0) can throw IndexOutOfBoundsException if layout is empty.
            //          While ?.let handles null, it doesn't protect against index errors.
            //
            // SOLUTION: Check childCount before accessing child by index.
            // ============================================================================
            if (layoutArrivalCelebration.childCount > 0) {
                val emojiView = layoutArrivalCelebration.getChildAt(0)
                emojiView?.let {
                    it.scaleX = 0f
                    it.scaleY = 0f
                    it.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(600)
                        .setStartDelay(200)
                        .setInterpolator(android.view.animation.OvershootInterpolator())
                        .start()
                }
            }

            FileLogger.d("AR_NAVIGATION", "🎉 ARRIVED at ${selectedEndNode?.name}")
            // When user arrives at destination
            FileLogger.nav("ARRIVED at: ${selectedEndNode?.name}")
        }
    }

    private fun initializeSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        // Rotation vector sensor (for compass bearing)
        rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        if (rotationVector == null) {
            Toast.makeText(this, "⚠️ No Rotation Vector sensor", Toast.LENGTH_LONG).show()
            lastKnownAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
            minCalibrationTimePassed = true
        }

        // ============================================================================
        // PHASE 2: Add gyroscope for heading fusion
        // ============================================================================
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        if (gyroscope == null) {
            FileLogger.w("AR_SENSORS", "⚠️ No Gyroscope sensor - heading fusion disabled")
            FileLogger.w("SENSORS", "No gyroscope - fusion disabled")
            useSensorFusion = false
        } else {
            FileLogger.d("AR_SENSORS", "✅ Gyroscope available - heading fusion enabled")
        }
    }

    private fun initializeSystems() {
        coordinateAligner = CoordinateAligner()
        //Initialize sensor fusion filters
        kalmanFilter = KalmanFilter()
        headingFusionFilter = HeadingFusionFilter()
        FileLogger.d("AR_INIT", "Sensor fusion filters initialized")
        gpsBufferManager = GPSBufferManager(
            requiredSampleCount = 8,
            maxAccuracyThreshold = 10.0f,
            maxScatterDistance = 5.0f
        )

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    handleLocationUpdate(location)
                }
            }
        }

        FileLogger.d("AR_INIT", "Systems initialized (outdoor anchoring: $useOutdoorAnchoring, phase3: $usePhase3Rendering)")
    }

    private fun handleStartupMode() {
        // D10: virtual-start dispatch runs BEFORE the node-ID check. A virtual intent has
        // END_NODE_ID but deliberately omits START_NODE_ID, which would otherwise fall through
        // to setupRouteSelectionUI.
        val startMode = intent.getStringExtra("START_MODE")
        if (startMode == "VIRTUAL") {
            val virtualLat = intent.getDoubleExtra("VIRTUAL_LAT", Double.NaN)
            val virtualLng = intent.getDoubleExtra("VIRTUAL_LNG", Double.NaN)
            val accuracy = intent.getFloatExtra("VIRTUAL_ACCURACY", Float.MAX_VALUE)
            val targetId = intent.getIntExtra("END_NODE_ID", -1)
            handleVirtualStartNavigation(virtualLat, virtualLng, accuracy, targetId)
            return
        }

        val intentStartId = intent.getIntExtra("START_NODE_ID", -1)
        val intentEndId = intent.getIntExtra("END_NODE_ID", -1)

        if (intentStartId != -1 && intentEndId != -1) {
            handleIntentNavigation(intentStartId, intentEndId)
        } else {
            setupRouteSelectionUI()
            checkPermissionsAndStart()
        }
    }

    private fun handleIntentNavigation(startId: Int, endId: Int) {
        if (!checkPermissions()) {
            requestPermissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)  // FIXED
            )
            return
        }

        initSensorsAndLocation()

        val startNode = campusGraph.nodes[startId]
        val endNode = campusGraph.nodes[endId]

        if (startNode == null || endNode == null) {
            Toast.makeText(this, "❌ Route points not found!", Toast.LENGTH_SHORT).show()
            setupRouteSelectionUI()
            return
        }

        selectedStartNode = startNode
        selectedEndNode = endNode
        layoutRouteSelection.visibility = View.GONE

        calculateRouteOnce()
        Toast.makeText(this, "Route: ${startNode.name} → ${endNode.name}", Toast.LENGTH_LONG).show()

        startCompassCalibrationStep()
    }

    /**
     * Handles navigation launched with `START_MODE=VIRTUAL`. The user's GPS is projected onto the
     * campus path network via phantom-node routing (see SCRUM-56 Phase 2 / [CampusGraph.findPathFromPoint]).
     *
     * Parallel to [handleIntentNavigation] for the named-destination flow. Mirrors the same
     * permission gate + sensor init + UI transition + compass-calibration handoff, but substitutes
     * [CampusGraph.findPathFromPoint] for `calculateRouteOnce` so the route starts at the user's
     * actual GPS position rather than a named node.
     *
     * `selectedStartNode` intentionally stays null on this path — verified safe (only null-safe
     * readers downstream). `selectedEndNode` IS assigned explicitly (D18) because ~8 downstream
     * call sites depend on it.
     */
    private fun handleVirtualStartNavigation(
        virtualLat: Double,
        virtualLng: Double,
        accuracy: Float,
        targetId: Int
    ) {
        // Defensive: NaN / missing extras (should never occur if caller passed correct extras).
        if (virtualLat.isNaN() || virtualLng.isNaN()) {
            Toast.makeText(this, "Missing location data", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (targetId == -1) {
            Toast.makeText(this, "Missing destination", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Permission gate — mirror the pattern used by handleIntentNavigation.
        if (!checkPermissions()) {
            requestPermissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
            )
            return
        }

        initSensorsAndLocation()

        // Resolve target node. Fall back to manual route selection on lookup failure, matching
        // handleIntentNavigation's behavior for bad node IDs.
        val endNode = campusGraph.nodes[targetId]
        if (endNode == null) {
            Toast.makeText(this, "❌ Destination not found!", Toast.LENGTH_SHORT).show()
            setupRouteSelectionUI()
            return
        }

        // Run phantom-node routing.
        val result = campusGraph.findPathFromPoint(virtualLat, virtualLng, accuracy, targetId)

        when (result) {
            is PhantomRouteResult.Success -> {
                // --- Mirror calculateRouteOnce side effects ---
                routeCoords = result.polyline
                routeNodePath = result.nodePath

                // D18: selectedEndNode has ~8 downstream readers (arrival UI, milestone logic, etc.)
                // selectedStartNode stays null — verified safe (null-safe elvis fallbacks only).
                selectedEndNode = endNode

                val startName = "Your Location"  // D19
                val endName = endNode.name ?: "Destination"
                val offNetSuffix = if (result.isOffNetwork) " ⚠ Off-network" else ""
                staticRouteReport =
                    "✅ Route: $startName → $endName\n" +
                    "${routeCoords.size} points, ${routeNodePath.size} nodes\n" +
                    "Mode: ${result.startMode}, Snap: ${"%.1f".format(result.snapDistance)}m$offNetSuffix\n\n"

                val routeLength = calculateRouteLength()
                FileLogger.d("AR_ROUTE", "Route length: ${"%.1f".format(routeLength)}m")
                FileLogger.nav("Route: ${routeNodePath.size} nodes, ${routeCoords.size} coords, ${"%.0f".format(routeLength)}m (phantom, ${result.startMode})")
                FileLogger.d("AR_ROUTE", "Route ${routeLength.toInt()}m — SphereRefresher will handle rendering")

                // D16: PHANTOM_ROUTE verification log — structured fields so we can confirm in
                // the log that firstCoord == projection (not the raw user GPS). Critical for
                // catching silent bugs where reconstruction drops the phantom.
                val firstCoord = result.polyline.firstOrNull()
                FileLogger.d(
                    "PHANTOM_ROUTE",
                    "mode=${result.startMode} " +
                        "userGps=($virtualLat, $virtualLng) " +
                        "firstCoord=(${firstCoord?.lat}, ${firstCoord?.lng}) " +
                        "snapDist=${"%.1f".format(result.snapDistance)}m " +
                        "offNetwork=${result.isOffNetwork} " +
                        "accuracy=${"%.1f".format(accuracy)}m"
                )

                // Off-network UX warning (MVP: Toast-only; dashed connector deferred).
                if (result.isOffNetwork) {
                    Toast.makeText(
                        this,
                        "Route starts ${result.snapDistance.toInt()}m from your position — walk to the path",
                        Toast.LENGTH_LONG
                    ).show()
                }

                layoutRouteSelection.visibility = View.GONE

                Toast.makeText(
                    this,
                    "Navigating from your location to $endName",
                    Toast.LENGTH_SHORT
                ).show()

                startCompassCalibrationStep()
            }

            is PhantomRouteResult.TooFar -> {
                Toast.makeText(
                    this,
                    "You're too far from any path. Please walk closer.",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }

            is PhantomRouteResult.AccuracyTooLow -> {
                Toast.makeText(
                    this,
                    "GPS signal too weak (${result.accuracy.toInt()}m). Please move to an open area.",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }

            is PhantomRouteResult.NoPath -> {
                Toast.makeText(
                    this,
                    "No route available to destination.",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
    }

    private fun setupRouteSelectionUI() {
        if (campusGraph.destinations.isEmpty()) {
            val fallbackList = listOf("⚠️ No Destinations")
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, fallbackList)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerStartNode.adapter = adapter
            spinnerEndNode.adapter = adapter
            btnConfirmRoute.isEnabled = false
            return
        }

        val nodeNames = campusGraph.destinations.map { it.name ?: "Unknown" }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, nodeNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        spinnerStartNode.adapter = adapter
        spinnerEndNode.adapter = adapter
        btnConfirmRoute.isEnabled = true

        if (nodeNames.size > 1) {
            spinnerStartNode.setSelection(0)
            spinnerEndNode.setSelection(1)
        }
    }

    private fun confirmRouteAndStart() {
        if (campusGraph.destinations.size < 2) {
            Toast.makeText(this, "Need at least 2 destinations", Toast.LENGTH_LONG).show()
            return
        }

        val startIndex = spinnerStartNode.selectedItemPosition
        val endIndex = spinnerEndNode.selectedItemPosition

        if (startIndex == endIndex) {
            Toast.makeText(this, "Start and destination must be different", Toast.LENGTH_LONG).show()
            return
        }

        selectedStartNode = campusGraph.destinations.getOrNull(startIndex)
        selectedEndNode = campusGraph.destinations.getOrNull(endIndex)

        if (selectedStartNode == null || selectedEndNode == null) {
            Toast.makeText(this, "❌ Invalid selection", Toast.LENGTH_LONG).show()
            return
        }

        Toast.makeText(this, "Route: ${selectedStartNode?.name} → ${selectedEndNode?.name}", Toast.LENGTH_SHORT).show()

        calculateRouteOnce()
        startCompassCalibrationStep()
    }

    private fun startCompassCalibrationStep() {
        updateStateUI(AppState.STEP_1_COMPASS_CALIBRATION)

        val handler = Handler(Looper.getMainLooper())
        val calibrationChecker = object : Runnable {
            var attempts = 0

            override fun run() {
                attempts++

                if (lastKnownAccuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) {
                    minCalibrationTimePassed = true
                    Toast.makeText(this@ArNavigationActivity, "✅ Compass Ready!", Toast.LENGTH_SHORT).show()
                    updateStateUI(AppState.STEP_2_GPS_COLLECTION)
                } else if (attempts < 10) {
                    handler.postDelayed(this, 4000)
                } else {
                    minCalibrationTimePassed = true
                    Toast.makeText(this@ArNavigationActivity, "⚠️ Compass timeout", Toast.LENGTH_LONG).show()
                    updateStateUI(AppState.STEP_2_GPS_COLLECTION)
                }
            }
        }

        handler.postDelayed(calibrationChecker, 4000)
    }

    private fun handleLocationUpdate(location: Location) {
        // Throttle rapid GPS updates
        val now = System.currentTimeMillis()
        if (now - lastLocationUpdateTime < MIN_LOCATION_UPDATE_INTERVAL_MS) {
            return  // Skip too-frequent updates
        }
        lastLocationUpdateTime = now

        gpsLogCounter++
        if (gpsLogCounter % 5 == 0) {  // Log every 5th update
            FileLogger.gps("GPS #$gpsLogCounter: acc=${String.format("%.1f", location.accuracy)}m, " +
                    "speed=${String.format("%.1f", location.speed)}m/s")
        }
        // ============================================================================
        // Adaptive Sensor Fusion Toggle
        // ============================================================================
        // In poor GPS conditions (accuracy > 20m), raw GPS might be more reliable
        // than filtered GPS because the Kalman filter assumes smooth motion
        // which may not match reality when GPS is jumping around
        // ============================================================================
        if (adaptiveFusionEnabled) {
            if (location.accuracy > ADAPTIVE_FUSION_GPS_THRESHOLD && !fusionDisabledByAdaptive) {
                // GPS is poor - temporarily disable fusion
                fusionDisabledByAdaptive = true
                FileLogger.w("ADAPTIVE_FUSION", "⚠️ GPS accuracy ${location.accuracy.toInt()}m > threshold - fusion temporarily disabled")
                FileLogger.w("FUSION", "Disabled: GPS acc=${location.accuracy.toInt()}m")

                // Update UI to show fusion is paused
                runOnUiThread {
                    updateNavigationUI()  // Refresh route info with warning indicator
                }
            } else if (location.accuracy < ADAPTIVE_FUSION_RECOVERY_THRESHOLD && fusionDisabledByAdaptive) {
                // GPS recovered - re-enable fusion
                fusionDisabledByAdaptive = false
                FileLogger.d("ADAPTIVE_FUSION", "✅ GPS accuracy ${location.accuracy.toInt()}m recovered - fusion re-enabled")
                FileLogger.d("FUSION", "Re-enabled: GPS acc=${location.accuracy.toInt()}m")

                // Reset Kalman filter to avoid using stale predictions
                if (::kalmanFilter.isInitialized) {
                    kalmanFilter.reset()
                    FileLogger.d("ADAPTIVE_FUSION", "Kalman filter reset after recovery")
                }

                // Update UI to show fusion is active again
                runOnUiThread {
                    updateNavigationUI()
                }
            }
        }

        // ============================================================================
        // PHASE 2: Apply Kalman filter to smooth GPS
        // ============================================================================
        val shouldUseFusion = useSensorFusion && !fusionDisabledByAdaptive
        val filteredLocation = if (shouldUseFusion && ::kalmanFilter.isInitialized) {
            kalmanFilter.filter(location)
        } else {
            location
        }
        if (magneticDeclination == 0f) {
            val geoField = GeomagneticField(
                filteredLocation.latitude.toFloat(),
                filteredLocation.longitude.toFloat(),
                filteredLocation.altitude.toFloat(),
                System.currentTimeMillis()
            )
            magneticDeclination = geoField.declination
        }

        when (currentState) {
            AppState.STEP_0_ROUTE_SELECTION -> { /* Waiting */ }
            AppState.STEP_1_COMPASS_CALIBRATION -> { /* Waiting */ }
            AppState.STEP_2_GPS_COLLECTION -> processGPSSample(filteredLocation)
            AppState.STEP_3_NAVIGATION -> handleNavigationUpdate(filteredLocation)
        }
    }

    private fun processGPSSample(location: Location) {
        val state = gpsBufferManager.addSample(location)
        progressBar.progress = gpsBufferManager.getProgress()

        runOnUiThread {
            tvInfo.text = "GPS: ${gpsBufferManager.getSampleCount()}/8\nAccuracy: ${location.accuracy.toInt()}m"
        }

        if (state == GPSBufferManager.State.READY) {
            val finalLocation = gpsBufferManager.calculateWeightedAverage()
            if (finalLocation != null) {
                startARNavigation(finalLocation, isForced = false)
            }
        }
    }

    private fun handleNavigationUpdate(location: Location) {
        currentUserLocation = location

        // Feed dual-delta samples while waiting for walk calibration
        // No !isInitialized() guard — addAlignmentSample() has its own internal guards
        // This allows dual-delta refinement after recalibration (where isInitialized=true)
        if (waitingForDualDelta) {
            val frame = arView.frame
            if (frame != null) {
                val pose = frame.camera.pose
                val aligned = coordinateAligner.addAlignmentSample(
                    location.latitude, location.longitude, location.accuracy,
                    pose.tx(), pose.tz()
                )
                if (aligned) {
                    waitingForDualDelta = false
                    pendingCompassInit = false
                    isRecalibrating = false
                    FileLogger.d("HEADING_INIT", "Dual-delta alignment succeeded: " +
                        "yawOffset=${coordinateAligner.getYawOffset().toInt()}°")
                    if (sphereRefresher == null) initializeSphereRefresher()
                    runOnUiThread {
                        Toast.makeText(this, "🧭 Heading calibrated — navigation starting", Toast.LENGTH_SHORT).show()
                    }
                    // Fall through — let the rest of handleNavigationUpdate run for the first time
                } else {
                    return  // Still waiting for enough displacement
                }
            } else {
                return  // No AR frame available yet
            }
        }

        // Skip navigation updates until aligner is initialized
        if (!coordinateAligner.isInitialized()) return

        val arYaw = getArYaw()

        // Compute own GPS bearing from consecutive Kalman-filtered positions
        var computedBearing: Double? = null
        var lastComputedDisplacement: Double? = null
        val prevGps = previousGpsForBearing
        if (prevGps != null) {
            val displacement = ArUtils.distanceMeters(
                prevGps.latitude, prevGps.longitude,
                location.latitude, location.longitude
            )
            lastComputedDisplacement = displacement
            if (displacement > 3.0) {
                computedBearing = ArUtils.bearingDeg(
                    prevGps.latitude, prevGps.longitude,
                    location.latitude, location.longitude
                )
                previousGpsForBearing = location
                FileLogger.d("BEARING_QUALITY", "displacement=${String.format("%.1f", displacement)}m, " +
                    "accuracy=${String.format("%.1f", location.accuracy)}m, " +
                    "ratio=${String.format("%.2f", displacement / location.accuracy)}, " +
                    "bearing=${String.format("%.0f", computedBearing)}°")
            }
        } else {
            previousGpsForBearing = location
        }

        // Diagnostic: log every GPS update to see which gate blocks motion updates
        FileLogger.d("MOTION_GATE", "GPS: speed=${String.format("%.2f", location.speed)}m/s, " +
            "acc=${String.format("%.1f", location.accuracy)}m, " +
            "hasBearing=${location.hasBearing()}, " +
            "computedBearing=${computedBearing?.toInt() ?: "N/A"}°, " +
            "arYaw=${String.format("%.0f", arYaw)}°, " +
            "currentOffset=${String.format("%.0f", coordinateAligner.getYawOffset())}°")

        coordinateAligner.updateWithMotion(location, arYaw, computedBearing, lastComputedDisplacement)

        // Update render distance based on heading confidence
        sphereRefresher?.updateHeadingConfidence(coordinateAligner.getMotionUpdateCount())

        // ============================================================================
        // SPHERE REFRESH: Recreate all spheres with current GPS + heading
        // ============================================================================
        // SphereRefresher handles its own anchor, spheres, and rate-limiting.
        // It uses the improved yaw offset from updateWithMotion above.
        // ============================================================================
        val frame = arView.frame
        val session = arView.session
        if (frame != null && session != null && coordinateAligner.isInitialized() && !isRecalibrating) {
            sphereRefresher?.refresh(location, frame, session)
        }

        updateProgressOnRoute(location)
        updateLiveUI(location)
    }

    /**
     * Create and initialize SphereRefresher with route data and materials.
     * Called after compass init succeeds (either immediate or deferred).
     */
    private fun initializeSphereRefresher() {
        if (routeCoords.isEmpty()) {
            FileLogger.w("REFRESH", "Cannot init SphereRefresher — no route coords")
            return
        }

        try {
            val materialLoader = arView.materialLoader
            val blueMat = materialLoader.createColorInstance(
                color = io.github.sceneview.math.Color(0.0f, 0.5f, 1.0f, 1.0f)
            )
            val yellowMat = materialLoader.createColorInstance(
                color = io.github.sceneview.math.Color(1.0f, 0.9f, 0.0f, 1.0f)
            )

            sphereRefresher = SphereRefresher(
                arView = arView,
                routeCoordinates = routeCoords,
                routeNodePath = routeNodePath,
                yawOffsetProvider = { coordinateAligner.getYawOffset() },
                pathMaterial = blueMat,
                milestoneMaterial = yellowMat
            ).also { it.initialize() }

            FileLogger.d("REFRESH", "SphereRefresher created: ${routeCoords.size} route points")
        } catch (e: Exception) {
            FileLogger.e("REFRESH", "Failed to create SphereRefresher: ${e.message}")
        }
    }

    private fun startARNavigation(location: Location, isForced: Boolean) {
        if (navigationStartTime == 0L) {
            navigationStartTime = System.currentTimeMillis()
        }
        pendingGpsForAnchor = location

        if (!coordinateAligner.isInitialized() && !pendingCompassInit) {
            // ====================================================================
            // DEFERRED COMPASS INITIALIZATION
            // ====================================================================
            // Don't read compass here — phone may still be transitioning from flat
            // (STEP_1) to upright (AR view). Defer to handlePlaneDetectionAndAnchor()
            // which checks pose xzMagnitude > 0.7 before initializing.
            // ====================================================================
            pendingCompassInit = true  // Fallback: compass init if dual-delta times out
            pendingCompassInitLocation = location
            waitingForDualDelta = true
            dualDeltaStartTime = System.currentTimeMillis()
            lastHeadingInitLogTime = 0L
            coordinateAligner.resetDualDelta()

            FileLogger.d("HEADING_INIT", "Dual-delta wait started — walk to calibrate, compass fallback in ${DUAL_DELTA_TIMEOUT_SECONDS}s")
            runOnUiThread {
                Toast.makeText(this, "🧭 Hold phone upright, then walk forward to calibrate", Toast.LENGTH_LONG).show()
            }
        }

        smoothedCameraY = Float.NaN
        previousGpsForBearing = null
        lastGoodArYaw = 0.0
        updateStateUI(AppState.STEP_3_NAVIGATION)

        FileLogger.d("INIT", "Navigation started: SphereRefresher ONLY. Old renderer disabled.")

        runOnUiThread {
            Toast.makeText(this, "🔍 Looking for floor...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun calculateRouteOnce() {
        val startNode = selectedStartNode ?: return
        val endNode = selectedEndNode ?: return

        routeCoords = campusGraph.findPath(startNode.id, endNode.id)
        routeNodePath = campusGraph.findNodePath(startNode.id, endNode.id)

        if (routeCoords.isEmpty()) {
            runOnUiThread { tvInfo.text = "❌ No route found!" }
            return
        }

        staticRouteReport = "✅ Route: ${startNode.name} → ${endNode.name}\n${routeCoords.size} points, ${routeNodePath.size} nodes\n\n"

        // Calculate total route length to decide on segmentation
        val routeLength = calculateRouteLength()
        FileLogger.d("AR_ROUTE", "Route length: ${"%.1f".format(routeLength)}m")
        FileLogger.nav("Route: ${routeNodePath.size} nodes, ${routeCoords.size} coords, ${"%.0f".format(routeLength)}m")

        // SphereRefresher handles all rendering — no segmentation/anchor mode needed
        FileLogger.d("AR_ROUTE", "Route ${routeLength.toInt()}m — SphereRefresher will handle rendering")
    }

    /**
     * Calculates the total length of the route in meters.
     */
    private fun calculateRouteLength(): Double {
        if (routeCoords.size < 2) return 0.0

        var totalLength = 0.0
        for (i in 0 until routeCoords.size - 1) {
            val p1 = routeCoords[i]
            val p2 = routeCoords[i + 1]
            totalLength += ArUtils.distanceMeters(p1.lat, p1.lng, p2.lat, p2.lng)
        }
        return totalLength
    }

    // (renderRouteInAR removed — SphereRefresher handles all rendering)

    private fun updateProgressOnRoute(userLocation: Location) {
        if (routeCoords.isEmpty()) return

        var bestIdx = lastClosestRouteIndex
        var bestDist = Double.MAX_VALUE

        // Search window: look behind (for backtracking) and ahead
        val startIdx = (lastClosestRouteIndex - 50).coerceAtLeast(0)
        val endIdx = (lastClosestRouteIndex + 200).coerceAtMost(routeCoords.size - 1)

        for (i in startIdx..endIdx) {
            val p = routeCoords[i]
            val d = ArUtils.distanceMeters(userLocation.latitude, userLocation.longitude, p.lat, p.lng)
            if (d < bestDist) {
                bestDist = d
                bestIdx = i
            }
        }

        // Allow both forward and backward movement
        if (bestIdx != lastClosestRouteIndex) {
            lastClosestRouteIndex = bestIdx
            markVisitedNodes(userLocation)

            // Rendering is handled by SphereRefresher — no explicit re-render needed.
            // SphereRefresher.refresh() runs on every GPS update and picks up progress changes.
            if (abs(lastClosestRouteIndex - lastRenderProgressIndex) >= RERENDER_PROGRESS_DELTA) {
                lastRenderProgressIndex = lastClosestRouteIndex
                FileLogger.d("PROGRESS", "Progress updated: nearest=$lastClosestRouteIndex, visited=${visitedNodeIds.size}")
            }
        }
    }

    private fun markVisitedNodes(userLocation: Location) {
        for (node in routeNodePath) {
            if (visitedNodeIds.contains(node.id)) continue

            val distance = ArUtils.distanceMeters(
                userLocation.latitude, userLocation.longitude,
                node.lat, node.lng
            )

            if (distance < VISITATION_DISTANCE_THRESHOLD) {
                visitedNodeIds.add(node.id)

                // Check if this is the final destination
                val isDestination = (node.id == selectedEndNode?.id)

                if (isDestination) {
                    // Show arrival celebration!
                    showArrivalCelebration()
                } else {
                    // Play checkpoint animation
                    playCheckpointReachedAnimation(node.name ?: "Checkpoint")
                }
            }
        }
    }

    // (checkDriftAndReAnchor removed — SphereRefresher recreates anchors every 8m)
    // Dead code block follows — will be removed in future cleanup
    /**
     * REMOVED: Checks for GPS drift and triggers re-anchoring if necessary.
     *
     * DRIFT DETECTION:
     * As the user walks, the AR anchor stays in place while the GPS position moves.
     * If the distance between the anchor's GPS position and the current GPS position
     * exceeds REANCHOR_THRESHOLD_METERS, we need to create a new anchor closer to
     * the user's current position.
     *
     * RE-ANCHORING PROCESS:
     * 1. Clear the old anchor and route
     * 2. Reset the CoordinateAligner (CRITICAL - the old yaw offset is invalid)
     * 3. Update the anchor location to current GPS
     * 4. Wait for plane detection to create a new anchor
     * 5. Re-render the route from the new anchor
     *
     * RATE LIMITING:
     * Re-anchoring is expensive (clears and re-renders everything), so we enforce
     * a cooldown period between re-anchors.
     *
     * @param currentLocation The user's current GPS location
     */
    // TODO(SCRUM-56 Phase 2): If re-enabling this drift-detection path, phantom-flow
    // routes (selectedStartNode == null) require rebuilding via campusGraph.findPathFromPoint
    // followed by an explicit initializeSphereRefresher() call — see forceRecalibration() Fix 1+3
    // for the reference pattern. Without that rebuild, spheres will disappear after re-anchor
    // on phantom routes and never come back. Bug reproduced and fixed 2026-04-24 via three
    // iterations; see fix_phantom_recalibration.md and fix_sphere_reinit_after_recalib.md.
    private fun checkDriftAndReAnchor(currentLocation: Location) {
        val now = System.currentTimeMillis()

        // Enforce cooldown between re-anchors
        if (now - lastReAnchorTime < REANCHOR_COOLDOWN_MS) return

        val effectiveThreshold = REANCHOR_THRESHOLD_METERS

        lastAnchorLocation?.let { anchor ->
            // Calculate distance from anchor to current position
            val distance = ArUtils.distanceMeters(
                anchor.latitude, anchor.longitude,
                currentLocation.latitude, currentLocation.longitude
            )

            // Only re-anchor if drift is significant AND GPS is reliable
            if (distance > effectiveThreshold && currentLocation.accuracy < 15.0f) {
                lastReAnchorTime = now

                FileLogger.d("AR_REANCHOR", "╔════════════════════════════════════════════════════════════")
                FileLogger.d("AR_REANCHOR", "║ DRIFT DETECTED - INITIATING RE-ANCHOR")
                FileLogger.d("AR_REANCHOR", "╠════════════════════════════════════════════════════════════")
                FileLogger.d("AR_REANCHOR", "║ Drift distance: ${"%.1f".format(distance)}m")
                FileLogger.d("AR_REANCHOR", "║ GPS accuracy: ${"%.1f".format(currentLocation.accuracy)}m")
                FileLogger.d("AR_REANCHOR", "║ Old anchor: (${anchor.latitude}, ${anchor.longitude})")
                FileLogger.d("AR_REANCHOR", "║ New anchor: (${currentLocation.latitude}, ${currentLocation.longitude})")
                FileLogger.d("AR_REANCHOR", "╚════════════════════════════════════════════════════════════")
                FileLogger.nav("RE-ANCHOR: drift=${String.format("%.1f", distance)}m, acc=${String.format("%.1f", currentLocation.accuracy)}m")

                // Show user feedback
                runOnUiThread {
                    tvRecalculating.visibility = View.VISIBLE
                    tvRecalculating.text = "⚠️ Position drift: ${distance.toInt()}m\n🔄 Recalibrating..."
                }

                // ====================================================================
                // CRITICAL: Reset all coordinate-related state
                // ====================================================================

                // 1. SphereRefresher manages its own anchor — no legacy clearAnchor needed

                // 2. DO NOT reset CoordinateAligner — the yaw offset is still valid
                // within the same AR session. Resetting and re-initializing from
                // compass would use potentially unreliable magnetometer data.

                // 2b. DO NOT reset Kalman filter — it has converged and provides good smoothing.
                // DO NOT reset heading fusion filter — it has accumulated useful gyro data.
                // Only reset adaptive fusion flag (cheap to re-evaluate).
                fusionDisabledByAdaptive = false
                FileLogger.d("AR_REANCHOR", "Filters preserved (Kalman + HeadingFusion kept)")

                // Reset dual-delta state and re-initialize from compass
                coordinateAligner.resetDualDelta()
                previousGpsForBearing = null
                lastGoodArYaw = 0.0

                val raPose = arView.frame?.camera?.displayOrientedPose
                val raZAxis = raPose?.zAxis
                val raXzMag = if (raZAxis != null) kotlin.math.sqrt(raZAxis[0] * raZAxis[0] + raZAxis[2] * raZAxis[2]) else 0f

                if (raXzMag > 0.7) {
                    val arYaw = getArYaw()
                    // Re-anchor uses compass immediately (user is mid-walk, drift detected)
                    // Dual-delta will refine heading if user keeps walking
                    coordinateAligner.forceReinitialize(currentTrueBearing.toDouble(), arYaw)
                    FileLogger.align("Re-anchor compass reinit: bearing=${currentTrueBearing.toInt()}°, offset=${coordinateAligner.getYawOffset().toInt()}°")
                    waitingForDualDelta = true
                    dualDeltaStartTime = System.currentTimeMillis()
                    lastHeadingInitLogTime = 0L
                    coordinateAligner.resetDualDelta()
                    pendingCompassInit = true  // Fallback
                    FileLogger.d("HEADING_INIT", "Re-anchor: compass used as initial, dual-delta wait for refinement")
                } else {
                    pendingCompassInit = true
                    waitingForDualDelta = true
                    dualDeltaStartTime = System.currentTimeMillis()
                    lastHeadingInitLogTime = 0L
                    coordinateAligner.resetDualDelta()
                    FileLogger.d("HEADING_INIT", "Re-anchor deferred (phone flat): dual-delta wait started")
                }

                // 3. Reset anchor location (will be set when new anchor is created)
                // Keep old location as fallback in case GPS fails during re-anchor
                // Don't set to null - getBestAvailableGps() will use it as fallback
                pendingGpsForAnchor = currentLocation  // Start with current GPS

                // 4. Reset rendering state
                anchorCreationAttempts = 0
                isRouteRenderingPending = false

                // 5. SphereRefresher handles anchor creation on next refresh
                pendingAnchorCreation = false
                sphereRefresher?.clearAll()  // Force fresh anchor + spheres on next GPS

                // Aligner already reinitialized from compass above (step 2b)

                // Hide the recalculating message after a delay
                Handler(Looper.getMainLooper()).postDelayed({
                    runOnUiThread {
                        tvRecalculating.visibility = View.GONE
                    }
                }, 5000)
            }
        }
    }

    private fun updateLiveUI(currentLocation: Location) {
        val arYaw = getArYaw()

        val alignmentError = coordinateAligner.getAlignmentError(currentTrueBearing.toDouble(), arYaw)

        // Calculate progress — use SphereRefresher's monotonic progress (furthestReachedIndex)
        val progress = sphereRefresher?.getProgressPercent() ?: 0
        val remainingDistance = sphereRefresher?.getRemainingDistanceMeters()
            ?: calculateRemainingDistance(currentLocation)

        // Calculate distance to next checkpoint
        val distanceToNext = calculateDistanceToNext(currentLocation)

        // Find next checkpoint
        val nextCheckpoint = findNextCheckpoint()

        runOnUiThread {
            // Update progress
            progressNavigation.progress = progress
            tvProgressPercent.text = "$progress%"

            // Update remaining distance
            tvRemainingDistance.text = formatDistance(remainingDistance)

            // Update next checkpoint
            tvNextCheckpoint.text = "Next: $nextCheckpoint"

            // Update ETA (assuming 1.4 m/s walking speed)
            val etaMinutes = (remainingDistance / 1.4 / 60).toInt()
            tvETA.text = when {
                etaMinutes < 1 -> "ETA: <1 min"
                etaMinutes == 1 -> "ETA: 1 min"
                else -> "ETA: $etaMinutes min"
            }

            // Update debug panel if visible
            if (isDebugMode) {
                val debugText = buildString {
                    appendLine("🛠️ DEBUG MODE")
                    appendLine("═══════════════════════")
                    appendLine("📍 Position: $lastClosestRouteIndex/${routeCoords.size}")
                    appendLine("🎯 Next: ${"%.1f".format(distanceToNext)}m")
                    appendLine("🧭 Align error: ${"%.1f".format(alignmentError)}°")
                    appendLine("📡 GPS: ${currentLocation.accuracy.toInt()}m")
                    appendLine("═══════════════════════")
                    appendLine("🔬 SENSOR FUSION (Phase 2)")
                    if (useSensorFusion) {
                        // Show adaptive fusion status (Optional Improvement 3)
                        if (fusionDisabledByAdaptive) {
                            appendLine("   ⚠️ PAUSED (poor GPS)")
                            appendLine("   Waiting for accuracy < ${ADAPTIVE_FUSION_RECOVERY_THRESHOLD.toInt()}m")
                        } else {
                            if (::kalmanFilter.isInitialized && kalmanFilter.isInitialized()) {
                                appendLine("   Kalman: Active ✅")
                                appendLine("   Gain: ${"%.3f".format(kalmanFilter.getApproximateGain())}")
                            }
                            if (::headingFusionFilter.isInitialized && headingFusionFilter.isInitialized()) {
                                appendLine("   Heading Fusion: Active ✅")
                                appendLine("   Compass diff: ${headingFusionFilter.getCompassDifference().toInt()}°")
                            }
                        }
                        // Show adaptive thresholds
                        appendLine("   Adaptive: ${if (adaptiveFusionEnabled) "ON" else "OFF"}")
                    } else {
                        appendLine("   Disabled (manual)")
                    }
                }
                tvDebugInfo.text = debugText
            }

            // Also update old tvInfo for backward compatibility
            tvInfo.text = "Navigation active - Debug: ${if (isDebugMode) "ON" else "OFF"}"
        }
    }

    private fun formatDistance(meters: Double): String {
        return when {
            meters >= 1000 -> String.format("%.1fkm", meters / 1000)
            else -> String.format("%.0fm", meters)
        }
    }

    private fun findNextCheckpoint(): String {
        // Use SphereRefresher's progress to determine which nodes have been passed
        val progressPercent = sphereRefresher?.getProgressPercent() ?: 0
        val nodeCount = routeNodePath.size
        if (nodeCount <= 1) return selectedEndNode?.name ?: "Destination"

        for (i in routeNodePath.indices) {
            val nodeProgressPercent = (i * 100) / (nodeCount - 1)
            if (nodeProgressPercent > progressPercent) {
                return routeNodePath[i].name ?: "Checkpoint"
            }
        }
        return selectedEndNode?.name ?: "Destination"
    }

    private fun calculateRemainingDistance(currentLocation: Location): Double {
        if (lastClosestRouteIndex >= routeCoords.size - 1) return 0.0

        // Include the perpendicular distance from user to closest route point
        val closestPoint = routeCoords[lastClosestRouteIndex]
        var remaining = ArUtils.distanceMeters(
            currentLocation.latitude, currentLocation.longitude,
            closestPoint.lat, closestPoint.lng
        )

        // Add remaining route distance from closest point to destination
        for (i in lastClosestRouteIndex until routeCoords.size - 1) {
            val p1 = routeCoords[i]
            val p2 = routeCoords[i + 1]
            remaining += ArUtils.distanceMeters(p1.lat, p1.lng, p2.lat, p2.lng)
        }
        return remaining
    }

    // ========================================================================================
    // MANUAL RECALIBRATION
    // ========================================================================================

    /**
     * Forces a recalibration of the AR coordinate system.
     * Call this when user reports spheres are in wrong direction.
     *
     * FIXED ISSUES:
     * 1. Now creates new anchor at current GPS position
     * 2. Handles null lastAnchorLocation
     * 3. Properly clears old anchor before creating new one
     * 4. Resets sensor fusion filters (Phase 2)
     */
    private fun forceRecalibration() {
        isRecalibrating = true  // Guard: prevent SphereRefresher.refresh() during recalib
        FileLogger.section("RECALIBRATION")
        FileLogger.d("RECALIB", "Manual recalibration triggered")
        FileLogger.d("AR_RECALIB", "╔════════════════════════════════════════════════════════════")
        FileLogger.d("AR_RECALIB", "║ MANUAL RECALIBRATION TRIGGERED")
        FileLogger.d("AR_RECALIB", "╚════════════════════════════════════════════════════════════")

        runOnUiThread {
            tvRecalculating.visibility = View.VISIBLE
            tvRecalculating.text = "🔄 Recalibrating...\n👉 Hold phone steady"
        }

        // Get current GPS position for new anchor
        val currentGps = getBestAvailableGps()

        if (currentGps == null) {
            FileLogger.e("AR_RECALIB", "❌ No GPS available for recalibration!")
            FileLogger.e("RECALIB", "No GPS for recalibration!")
            runOnUiThread {
                tvRecalculating.visibility = View.GONE
                Toast.makeText(this, "❌ No GPS signal - cannot recalibrate", Toast.LENGTH_LONG).show()
            }
            return
        }

        // ============================================================================
        // 1. Clear SphereRefresher (preserves progress, resets render distance)
        // ============================================================================
        smoothedCameraY = Float.NaN

        // Clear rendering but preserve route progress — user is still at same position
        // clearForRecalibration() now does partial reset (70% of current, min 10m)
        // Do NOT call updateHeadingConfidence(0) — that would force back to 6m, undoing the partial reset
        sphereRefresher?.clearForRecalibration()
        FileLogger.d("AR_RECALIB", "Cleared SphereRefresher (progress preserved, render distance partially reset)")

        // ============================================================================
        // 2. DO NOT reset coordinate aligner — yaw offset is still valid
        // within the same AR session. Will be updated via GPS bearing below.
        // ============================================================================

        // ============================================================================
        // 3. DO NOT reset Kalman or HeadingFusion — they have converged./
        // ============================================================================
        fusionDisabledByAdaptive = false
        FileLogger.d("AR_RECALIB", "Filters preserved (Kalman + HeadingFusion kept)")

        // Reset dual-delta state (clears old samples)
        coordinateAligner.resetDualDelta()

        // ============================================================================
        // SCRUM-56 Phase 2: phantom-flow recalibration
        // ============================================================================
        // If the original route was launched via virtual-start (chip), rebuild it from
        // the user's CURRENT GPS. Otherwise the partial-edge slice in routeCoords[0..N]
        // can leave routeCoords[1] pointing backward after the user has walked forward,
        // which trips the compass-vs-route diff check at the bearing sanity pass below
        // and locks us in dual-delta wait until the user walks ≥3m.
        //
        // Named flow leaves this block untouched (selectedStartNode non-null).
        // ============================================================================
        val isPhantomRoute = selectedStartNode == null
        if (isPhantomRoute) {
            val targetNode = selectedEndNode
            val currentLoc = currentUserLocation
            if (targetNode != null && currentLoc != null) {
                FileLogger.d("AR_RECALIB", "Phantom flow detected — rebuilding route from current GPS")
                val result = campusGraph.findPathFromPoint(
                    currentLoc.latitude,
                    currentLoc.longitude,
                    currentLoc.accuracy,
                    targetNode.id
                )
                when (result) {
                    is PhantomRouteResult.Success -> {
                        routeCoords = result.polyline
                        routeNodePath = result.nodePath
                        FileLogger.d("AR_RECALIB",
                            "Phantom route rebuilt: ${result.nodePath.size} nodes, " +
                                "${result.polyline.size} coords, mode=${result.startMode}, " +
                                "snapDist=${"%.1f".format(result.snapDistance)}m")
                        val firstCoord = result.polyline.firstOrNull()
                        FileLogger.d("PHANTOM_ROUTE",
                            "recalib mode=${result.startMode} " +
                                "userGps=(${currentLoc.latitude}, ${currentLoc.longitude}) " +
                                "firstCoord=(${firstCoord?.lat}, ${firstCoord?.lng}) " +
                                "snapDist=${"%.1f".format(result.snapDistance)}m " +
                                "offNetwork=${result.isOffNetwork} " +
                                "accuracy=${"%.1f".format(currentLoc.accuracy)}m")
                        // Explicitly re-create SphereRefresher with the new route data.
                        // The existing `if (sphereRefresher == null) initializeSphereRefresher()`
                        // guards elsewhere (lines 619/700/1459) only fire during initial setup —
                        // not on recalibration — so we must trigger re-creation ourselves.
                        // Field test (2026-04-24 17:20, 17:24) proved nulling alone is insufficient.
                        sphereRefresher = null
                        initializeSphereRefresher()
                        FileLogger.d("AR_RECALIB",
                            "SphereRefresher re-initialized with rebuilt phantom route")
                    }
                    is PhantomRouteResult.TooFar -> {
                        FileLogger.w("AR_RECALIB",
                            "Route rebuild: TooFar — keeping old route, proceeding with standard recalib")
                    }
                    is PhantomRouteResult.NoPath -> {
                        FileLogger.w("AR_RECALIB",
                            "Route rebuild: NoPath — keeping old route, proceeding with standard recalib")
                    }
                    is PhantomRouteResult.AccuracyTooLow -> {
                        FileLogger.w("AR_RECALIB",
                            "Route rebuild: AccuracyTooLow (${result.accuracy}m) — keeping old route, proceeding with standard recalib")
                    }
                }
            } else {
                FileLogger.w("AR_RECALIB",
                    "Phantom recalib skipped: targetNode=${targetNode != null}, currentLoc=${currentLoc != null}")
            }
        }

        // Re-initialize heading from compass — check phone orientation first
        smoothedCameraY = Float.NaN
        previousGpsForBearing = null
        lastGoodArYaw = 0.0

        val pose = arView.frame?.camera?.displayOrientedPose
        val zAxis = pose?.zAxis
        val xzMag = if (zAxis != null) kotlin.math.sqrt(zAxis[0] * zAxis[0] + zAxis[2] * zAxis[2]) else 0f

        if (xzMag > 0.7) {
            // Phone is upright — check compass agreement with route
            val arYaw = getArYaw()
            val userLoc = currentUserLocation
            var compassDiff = 999.0
            var routeBearing = 0.0
            if (userLoc != null && routeCoords.size > 1) {
                var routeTargetIdx = 1
                for (i in 1 until minOf(routeCoords.size, 10)) {
                    val dist = ArUtils.distanceMeters(userLoc.latitude, userLoc.longitude, routeCoords[i].lat, routeCoords[i].lng)
                    if (dist > 10.0) { routeTargetIdx = i; break }
                }
                routeBearing = ArUtils.bearingDeg(userLoc.latitude, userLoc.longitude, routeCoords[routeTargetIdx].lat, routeCoords[routeTargetIdx].lng)
                compassDiff = Math.abs(ArUtils.normalizeAngleDeg(currentTrueBearing.toDouble() - routeBearing))
            }

            if (compassDiff < 15.0) {
                // Compass agrees with route — trust it immediately
                coordinateAligner.forceReinitialize(currentTrueBearing.toDouble(), arYaw)
                isRecalibrating = false
                waitingForDualDelta = false
                pendingCompassInit = false
                FileLogger.d("HEADING_INIT", "Recalib compass shortcut: compass=${currentTrueBearing.toInt()}° " +
                    "agrees with route=${routeBearing.toInt()}° (diff=${compassDiff.toInt()}°)")
            } else {
                // Compass disagrees — enter dual-delta wait for walk calibration
                // Use compass+COMPASS_SANITY as initial estimate, then dual-delta will refine
                var recalibBearing = currentTrueBearing.toDouble()
                if (compassDiff > 40.0) {
                    FileLogger.w("COMPASS_SANITY", "Recalib: compass ${currentTrueBearing.toInt()}° vs route ${routeBearing.toInt()}° diff=${compassDiff.toInt()}° — USING ROUTE")
                    recalibBearing = routeBearing
                }
                coordinateAligner.forceReinitialize(recalibBearing, arYaw)
                isRecalibrating = false
                // Enter dual-delta for refinement — will override if user walks
                waitingForDualDelta = true
                dualDeltaStartTime = System.currentTimeMillis()
                lastHeadingInitLogTime = 0L
                coordinateAligner.resetDualDelta()
                pendingCompassInit = true  // Fallback if dual-delta times out
                FileLogger.d("HEADING_INIT", "Recalib: compass disagrees (diff=${compassDiff.toInt()}°) — " +
                    "using ${recalibBearing.toInt()}° as initial, waiting for walk refinement")
            }
        } else {
            // Rare: phone is flat during recalib — defer via dual-delta
            isRecalibrating = false  // Bug fix: Branches A and B already clear this; Branch C forgot.
            waitingForDualDelta = true
            dualDeltaStartTime = System.currentTimeMillis()
            lastHeadingInitLogTime = 0L
            coordinateAligner.resetDualDelta()
            pendingCompassInit = true  // Fallback
            pendingCompassInitLocation = currentUserLocation ?: currentGps
            FileLogger.d("HEADING_INIT", "Recalib deferred (phone flat, xzMag=${String.format("%.2f", xzMag)}) — dual-delta wait started")
        }

        // ============================================================================
        // 3b. Keep terrain profiler — recalibration is in the same physical area
        // ============================================================================
        FileLogger.d("AR_RECALIB", "Terrain profiler kept (same location)")

        // ============================================================================
        // 4. Update anchor location to current GPS
        // ============================================================================
        pendingGpsForAnchor = currentGps
        // Keep lastAnchorLocation as fallback — don't null it.
        // Setting null breaks flip180Degrees() and getBestAvailableGps() during anchor recreation.
        // It will be overwritten when the new anchor's GPS is locked.

        // ============================================================================
        // 5. SphereRefresher handles anchor creation on next GPS update
        // ============================================================================
        anchorCreationAttempts = 0
        isRouteRenderingPending = false
        pendingAnchorCreation = false  // SphereRefresher manages its own anchors

        FileLogger.d("AR_RECALIB", "Compass reinit complete — SphereRefresher will recreate on next GPS at (${currentGps.latitude}, ${currentGps.longitude})")

        runOnUiThread {
            tvInfo.text = "🔄 Recalibrating...\n👉 Point at ground"
        }

        FileLogger.d("RECALIB", "State after recalib: alignerInit=${coordinateAligner.isInitialized()}, dualDeltaDone=${coordinateAligner.isDualDeltaCompleted()}, waitingDD=$waitingForDualDelta, pendingAnchor=$pendingAnchorCreation")

        // Hide recalibrating message after timeout
        Handler(Looper.getMainLooper()).postDelayed({
            runOnUiThread {
                if (tvRecalculating.visibility == View.VISIBLE) {
                    tvRecalculating.visibility = View.GONE
                    Toast.makeText(this, "✅ Recalibrated!", Toast.LENGTH_SHORT).show()
                }
            }
        }, 5000)
    }

    /**
     * Flips the yaw offset by 180 degrees.
     *
     * This is useful when the compass was exactly backwards (common outdoor issue).
     * Instead of full recalibration, we just flip the direction.
     *
     * NOTE: This keeps the same anchor - only rotates sphere positions.
     * Use forceRecalibration() if you need a completely new anchor.
     */
    private fun flip180Degrees() {
        FileLogger.d("AR_RECALIB", "╔════════════════════════════════════════════════════════════")
        FileLogger.d("AR_RECALIB", "║ 180° FLIP TRIGGERED")
        FileLogger.d("AR_RECALIB", "╚════════════════════════════════════════════════════════════")
        FileLogger.nav("180° FLIP triggered")

        // Check if we have an anchor to work with
        if (lastAnchorLocation == null) {
            FileLogger.e("AR_RECALIB", "❌ No anchor location - cannot flip")
            FileLogger.e("RECALIB", "Flip failed: no anchor location")
            runOnUiThread {
                Toast.makeText(this, "❌ No anchor - try full recalibration", Toast.LENGTH_LONG).show()
            }
            return
        }

        // Check if aligner is initialized (needed for flip)
        if (!coordinateAligner.isInitialized()) {
            FileLogger.e("AR_RECALIB", "❌ Aligner not initialized - cannot flip")
            runOnUiThread {
                Toast.makeText(this, "❌ Not initialized - try full recalibration", Toast.LENGTH_LONG).show()
            }
            return
        }

        runOnUiThread {
            tvRecalculating.visibility = View.VISIBLE
            tvRecalculating.text = "🔄 Flipping 180°..."
        }

        // Get current offset and flip it by 180°
        val currentOffset = coordinateAligner.getYawOffset()
        val newOffset = ArUtils.normalizeAngleDeg(currentOffset + 180.0)

        FileLogger.d("AR_RECALIB", "Yaw offset flip:")
        FileLogger.d("AR_RECALIB", "  Before: $currentOffset°")
        FileLogger.d("AR_RECALIB", "  After: $newOffset°")
        FileLogger.d("RECALIB", "Flip: $currentOffset° -> $newOffset°")

        // Apply the flipped offset
        coordinateAligner.setYawOffset(newOffset)

        // ============================================================================
        // PHASE 2: Sync heading fusion filter with new offset
        // ============================================================================
        if (useSensorFusion && ::headingFusionFilter.isInitialized) {
            // Update the fused heading to match the flip
            val currentFusedHeading = headingFusionFilter.getFusedHeading()
            val newFusedHeading = (currentFusedHeading + 180f) % 360f
            headingFusionFilter.setHeading(newFusedHeading)
            FileLogger.d("AR_RECALIB", "HeadingFusion synced: $currentFusedHeading° → $newFusedHeading°")
        }

        // SphereRefresher will use the flipped offset on next refresh() call
        sphereRefresher?.clearForRecalibration()  // Fresh render with new heading, preserve progress
        FileLogger.d("AR_RECALIB", "SphereRefresher cleared — will recreate with flipped offset on next GPS")

        runOnUiThread {
            tvRecalculating.visibility = View.GONE
            Toast.makeText(this, "🔄 Heading flipped 180° — spheres refreshing...", Toast.LENGTH_SHORT).show()
        }
    }

    // ========================================================================================
    // GPS FALLBACK HELPER
    // ========================================================================================

    /**
     * Gets the best available GPS location with fallback chain.
     *
     * Priority:
     * 1. pendingGpsForAnchor (freshest, captured during stabilization)
     * 2. GPSBufferManager's weighted average (reliable average)
     * 3. GPSBufferManager's last sample (most recent raw sample)
     * 4. Existing lastAnchorLocation (if re-anchoring)
     * 5. null (should never happen if location permissions granted)
     *
     * @return Best available Location, or null if none available
     */
    private fun getBestAvailableGps(): Location? {
        // Priority 1: Pending GPS (freshest)
        pendingGpsForAnchor?.let {
            FileLogger.d("AR_GPS", "Using pendingGpsForAnchor: (${it.latitude}, ${it.longitude})")
            return it
        }

        // Priority 2: GPSBufferManager's weighted average
        val weightedLocation = gpsBufferManager.calculateWeightedAverage()
        if (weightedLocation != null) {
            FileLogger.w("AR_GPS", "⚠️ FALLBACK: Using GPSBufferManager weighted average: (${weightedLocation.latitude}, ${weightedLocation.longitude})")
            return weightedLocation
        }

        // Priority 3: GPSBufferManager's last sample
        val lastSample = gpsBufferManager.getLastSample()
        if (lastSample != null) {
            FileLogger.w("AR_GPS", "⚠️ FALLBACK: Using GPSBufferManager last sample: (${lastSample.latitude}, ${lastSample.longitude})")
            return lastSample
        }

        // Priority 4: Existing anchor location (for re-anchoring scenarios)
        lastAnchorLocation?.let {
            FileLogger.w("AR_GPS", "⚠️ FALLBACK: Using existing lastAnchorLocation: (${it.latitude}, ${it.longitude})")
            return it
        }

        // Priority 5: No GPS available
        FileLogger.e("AR_GPS", "❌ CRITICAL: No GPS location available!")
        FileLogger.e("GPS", "CRITICAL: No GPS available anywhere!")
        return null
    }

    private fun calculateDistanceToNext(location: Location): Double {
        val nextIdx = (lastClosestRouteIndex + 1).coerceAtMost(routeCoords.lastIndex)
        val target = routeCoords.getOrNull(nextIdx) ?: return 0.0

        return ArUtils.distanceMeters(location.latitude, location.longitude, target.lat, target.lng)
    }

    private fun updateStateUI(newState: AppState) {
        currentState = newState
        FileLogger.i("STATE", ">> $newState")

        runOnUiThread {
            when (newState) {
                AppState.STEP_0_ROUTE_SELECTION -> {
                    layoutRouteSelection.visibility = View.VISIBLE
                    layoutCalibration.visibility = View.GONE
                    progressBar.visibility = View.GONE
                    btnForceStart.visibility = View.GONE
                }

                AppState.STEP_1_COMPASS_CALIBRATION -> {
                    layoutRouteSelection.visibility = View.GONE
                    layoutCalibration.visibility = View.VISIBLE
                    progressBar.visibility = View.GONE
                    btnForceStart.visibility = View.VISIBLE
                    btnForceStart.text = "SKIP"
                    tvStepTitle.text = "STEP 1: COMPASS"
                    tvStepDesc.text = getString(R.string.compass_instruction)
                    ivStepIcon.setImageResource(R.drawable.ic_compass_arrow)
                }

                AppState.STEP_2_GPS_COLLECTION -> {
                    layoutRouteSelection.visibility = View.GONE
                    layoutCalibration.visibility = View.VISIBLE
                    progressBar.visibility = View.VISIBLE
                    btnForceStart.visibility = View.VISIBLE
                    btnForceStart.text = "FORCE START"
                    tvStepTitle.text = "STEP 2: GPS"
                    tvStepDesc.text = "Acquiring GPS...\nStand in open area"
                    ivStepIcon.setImageResource(android.R.drawable.ic_menu_mylocation)
                }

                AppState.STEP_3_NAVIGATION -> {
                    // Hide setup panels
                    layoutRouteSelection.visibility = View.GONE
                    layoutCalibration.visibility = View.GONE
                    progressBar.visibility = View.GONE
                    btnForceStart.visibility = View.GONE

                    // Show navigation UI
                    layoutTopBar.visibility = View.VISIBLE
                    layoutBottomCard.visibility = View.VISIBLE
                    layoutCompassHud.visibility = View.VISIBLE
                    layoutDebugButtons.visibility = View.VISIBLE  // FIXED: Show button container
                    // Set initial Phase 3 toggle state
                    btnPhase3Toggle.isSelected = usePhase3Rendering

                    // Hide debug panel by default (user can toggle)
                    layoutDebugPanel.visibility = View.GONE
                    isDebugMode = false

                    // Hide old debug panel
                    findViewById<ScrollView>(R.id.debugPanel).visibility = View.GONE

                    // Update destination info
                    updateNavigationUI()
                }
            }
        }
    }
    private fun updateNavigationUI() {
        runOnUiThread {
            // Set destination name
            selectedEndNode?.let { endNode ->
                tvDestinationName.text = endNode.name
            }

            // Set route info with fusion status indicator (Optional Improvement 1)
            val startName = selectedStartNode?.name ?: "Start"
            val endName = selectedEndNode?.name ?: "End"
            val fusionIndicator = when {
                !useSensorFusion -> ""  // Fusion disabled entirely
                fusionDisabledByAdaptive -> " ⚠️"  // Temporarily disabled by adaptive
                else -> " 🔬"  // Fusion active
            }
            tvRouteInfo.text = "$startName → $endName$fusionIndicator"
        }
    }

    private fun handleForceStart() {
        when (currentState) {
            AppState.STEP_1_COMPASS_CALIBRATION -> {
                minCalibrationTimePassed = true
                updateStateUI(AppState.STEP_2_GPS_COLLECTION)
            }
            AppState.STEP_2_GPS_COLLECTION -> {
                val location = gpsBufferManager.forceGetBestLocation()
                if (location != null) {
                    startARNavigation(location, isForced = true)
                } else {
                    Toast.makeText(this, "No GPS data", Toast.LENGTH_SHORT).show()
                }
            }
            else -> {}
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        when (event?.sensor?.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

                // === DIAGNOSTIC: old azimuth (without remap) for comparison ===
                val diagAngles = FloatArray(3)
                SensorManager.getOrientation(rotationMatrix, diagAngles)
                val oldAzimuth = Math.toDegrees(diagAngles[0].toDouble()).toFloat()

                // Remap coordinate system for upright/portrait phone orientation.
                // Without this, getOrientation() returns azimuth assuming phone is FLAT,
                // which gives ±10-30° error when phone is tilted 50-70° (normal AR posture).
                // AXIS_X, AXIS_Z remaps so azimuth = rotation around vertical axis
                // regardless of phone tilt.
                SensorManager.remapCoordinateSystem(
                    rotationMatrix,
                    SensorManager.AXIS_X,
                    SensorManager.AXIS_Z,
                    remappedMatrix
                )
                SensorManager.getOrientation(remappedMatrix, orientationAngles)

                val azimuthDeg = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()

                // Rate-limited diagnostic: compare old vs new azimuth
                val remapLogNow = System.currentTimeMillis()
                if (remapLogNow - lastRemapLogTime > 2000) {
                    lastRemapLogTime = remapLogNow
                    val diff = ((azimuthDeg - oldAzimuth + 540) % 360) - 180
                    FileLogger.d("COMPASS_REMAP",
                        "old=${String.format("%.1f", oldAzimuth)}° " +
                        "new=${String.format("%.1f", azimuthDeg)}° " +
                        "diff=${String.format("%.1f", diff)}° " +
                        "trueBearing=${String.format("%.1f", (azimuthDeg + magneticDeclination + 360) % 360)}°")
                }
                val trueAzimuth = azimuthDeg + magneticDeclination

                currentTrueBearing = (trueAzimuth + 360) % 360

                // Add inside onSensorChanged, after currentTrueBearing is set:
                val now = System.currentTimeMillis()
                if (now - lastCompassSampleTime >= COMPASS_SAMPLE_INTERVAL_MS) {
                    lastCompassSampleTime = now
                    compassHistory.addLast(currentTrueBearing)
                    if (compassHistory.size > 20) compassHistory.removeFirst()
                }

                // ============================================================================
                // PHASE 2: Update heading fusion filter with compass
                // ============================================================================
                if (useSensorFusion && ::headingFusionFilter.isInitialized) {
                    headingFusionFilter.updateCompass(currentTrueBearing)
                    smoothedAzimuth = headingFusionFilter.getFusedHeading()

                    // HEADING_COMPARE log — DISABLED (confirmed fused ≈ raw in log28-30)
                    // val fusionNow = System.currentTimeMillis()
                    // if (fusionNow - lastFusionLogTime > 5000) {
                    //     lastFusionLogTime = fusionNow
                    //     val fusedDiff = ((smoothedAzimuth - currentTrueBearing + 540) % 360) - 180
                    //     FileLogger.d("HEADING_COMPARE", ...)
                    // }
                } else {
                    // Fallback to original smoothing
                    smoothedAzimuth = (smoothedAzimuth * 0.90f) + (trueAzimuth * 0.10f)
                }

                currentBearing = (smoothedAzimuth + 360) % 360

                // Diagnostic: log compass during STEP_1 every 2 seconds
                if (currentState == AppState.STEP_1_COMPASS_CALIBRATION) {
                    val now = System.currentTimeMillis()
                    if (now - lastCompassLogTime > 2000) {
                        FileLogger.d("COMPASS_STEP1", "bearing=${currentTrueBearing.toInt()}°, " +
                            "smoothed=${currentBearing.toInt()}°, " +
                            "accuracy=$lastKnownAccuracy, " +
                            "declination=${String.format("%.1f", magneticDeclination)}°")
                        lastCompassLogTime = now
                    }
                }

                runOnUiThread {
                    ivCompassArrow.rotation = -currentBearing
                    tvCompassBearing.text = "${currentBearing.toInt()}°"
                }
            }

            // ============================================================================
            // PHASE 2: Handle gyroscope for heading fusion
            // ============================================================================
            Sensor.TYPE_GYROSCOPE -> {
                if (useSensorFusion && ::headingFusionFilter.isInitialized) {
                    // Gyro Z axis = rotation around vertical (yaw)
                    val gyroZ = event.values[2]
                    headingFusionFilter.updateGyro(gyroZ, event.timestamp)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
            lastKnownAccuracy = accuracy
        }
    }

    private fun checkPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkPermissionsAndStart() {
        if (checkPermissions()) {
            initSensorsAndLocation()
        } else {
            requestPermissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)  // FIXED
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun initSensorsAndLocation() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setMinUpdateDistanceMeters(0.0f)
            .setWaitForAccurateLocation(true)
            .build()

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }
}