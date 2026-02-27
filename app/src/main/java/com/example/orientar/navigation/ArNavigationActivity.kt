package com.example.orientar.navigation

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.os.Handler
import android.util.Log
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
import com.example.orientar.navigation.rendering.ARRenderer
import com.example.orientar.navigation.rendering.CoordinateAligner
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
import com.example.orientar.navigation.ar.OutdoorAnchorManager
import com.example.orientar.navigation.ar.RouteSegmentManager
import com.example.orientar.navigation.util.FileLogger
import com.example.orientar.R
// Phase 3: Performance optimized rendering

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

    // ========================================================================================
    // CORE SYSTEMS
    // ========================================================================================
    private lateinit var arRenderer: ARRenderer
    private lateinit var coordinateAligner: CoordinateAligner
    private lateinit var gpsBufferManager: GPSBufferManager
    private lateinit var campusGraph: CampusGraph

    // ========================================================================================
    // AR SESSION & PLANE DETECTION
    // ========================================================================================
    private var isARConfigured = false
    private var planeDetectionStartTime: Long = 0
    private val PLANE_DETECTION_TIMEOUT_MS = 10000L

    // ========================================================================================
    // SENSORS & ORIENTATION
    // ========================================================================================
    private lateinit var sensorManager: SensorManager
    private var rotationVector: Sensor? = null
    private var currentBearing: Float = 0f
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
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
    private var lastIncrementalUpdateTime: Long = 0
    private val incrementalUpdateThrottleMs: Long = 100  // Max 10 updates/second

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
    private var pendingAnchorCreation = false
    private var minCalibrationTimePassed = false


    // ========================================================================================
    // ANCHOR-ROUTE SYNCHRONIZATION STATE
    // ========================================================================================
    // These flags ensure route rendering only happens AFTER anchor is successfully created
    private var isRouteRenderingPending = false  // True when route should render after anchor
    private var anchorCreationAttempts = 0       // Track retry attempts
    private val MAX_ANCHOR_CREATION_ATTEMPTS = 30 // Max attempts (30 * ~500ms = 15 seconds)

    private var lastPlaneLogTime: Long = 0
    // ========================================================================================
    // OUTDOOR ANCHOR MANAGEMENT
    // ========================================================================================
    // The OutdoorAnchorManager provides improved anchor creation for outdoor environments
    // using a priority-based strategy: DepthPoint > Plane > Camera-relative estimation
    private lateinit var outdoorAnchorManager: OutdoorAnchorManager
    // ========================================================================================
    // ROUTE SEGMENTATION
    // ========================================================================================
    // RouteSegmentManager handles multi-anchor route rendering for long routes.
    // This prevents drift issues when spheres are far from a single anchor.
    // ========================================================================================
    private var routeSegmentManager: RouteSegmentManager? = null
    private var useSegmentation = true  // Enable segmentation for routes > threshold
    private val SEGMENTATION_ROUTE_THRESHOLD = 50.0  // Use segmentation for routes > 50m

    // Track which anchor strategy was used (for debugging/UI)
    private var lastAnchorStrategy: String = "None"

    // Flag to use outdoor-optimized anchoring (can be toggled for testing)
    private var useOutdoorAnchoring = true


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

        Log.d("AR_LIFECYCLE", "onCreate")

        initializeUI()
        initializeSensors()
        initializeSystems()

        campusGraph = CampusGraph(this)
        handleStartupMode()
    }

    override fun onResume() {
        super.onResume()

        Log.d("AR_LIFECYCLE", "onResume")

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
                Log.d("AR_SENSORS", "Gyroscope listener registered")
            }
        }
    }

    override fun onPause() {
        super.onPause()

        Log.d("AR_LIFECYCLE", "onPause")

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
        super.onDestroy()

        Log.d("AR_LIFECYCLE", "╔════════════════════════════════════════════════════════════")
        Log.d("AR_LIFECYCLE", "║ onDestroy - Cleaning up resources")
        // Phase 3 cleanup
        try {
            // SpherePositionCalculator shutdown is handled by ARRenderer.destroy()
            Log.d("AR_LIFECYCLE", "║ ✅ Phase 3 cleanup complete")
        } catch (e: Exception) {
            Log.e("AR_LIFECYCLE", "║ Phase 3 cleanup failed: ${e.message}")
            FileLogger.e("LIFECYCLE", "Phase 3 cleanup failed: ${e.message}")
        }
        Log.d("AR_LIFECYCLE", "╚════════════════════════════════════════════════════════════")

        // 1. Unregister sensor listener
        try {
            if (::sensorManager.isInitialized) {
                sensorManager.unregisterListener(this)
                Log.d("AR_LIFECYCLE", "✅ Sensor listener unregistered")
            }
        } catch (e: Exception) {
            Log.e("AR_LIFECYCLE", "Error unregistering sensor: ${e.message}")
            FileLogger.e("LIFECYCLE", "Sensor unregister error: ${e.message}")
        }

        // 2. Stop location updates
        try {
            if (::locationCallback.isInitialized) {
                fusedLocationClient.removeLocationUpdates(locationCallback)
                Log.d("AR_LIFECYCLE", "✅ Location updates stopped")
            }
        } catch (e: Exception) {
            Log.e("AR_LIFECYCLE", "Error stopping location updates: ${e.message}")
            FileLogger.e("LIFECYCLE", "Location stop error: ${e.message}")
        }

        // 3. Destroy AR renderer
        try {
            if (::arRenderer.isInitialized) {
                arRenderer.destroy()
                Log.d("AR_LIFECYCLE", "✅ AR renderer destroyed")
            }
        } catch (e: Exception) {
            Log.e("AR_LIFECYCLE", "Error destroying AR renderer: ${e.message}")
            FileLogger.e("LIFECYCLE", "AR destroy error: ${e.message}")
        }

        // 4. Destroy outdoor anchor manager
        try {
            if (::outdoorAnchorManager.isInitialized) {
                outdoorAnchorManager.destroy()
                Log.d("AR_LIFECYCLE", "✅ Outdoor anchor manager destroyed")
            }
        } catch (e: Exception) {
            Log.e("AR_LIFECYCLE", "Error destroying outdoor anchor manager: ${e.message}")
            FileLogger.e("LIFECYCLE", "Anchor manager destroy error: ${e.message}")
        }

        // 5. Destroy route segment manager
        try {
            routeSegmentManager?.destroy()
            routeSegmentManager = null
            Log.d("AR_LIFECYCLE", "✅ Route segment manager destroyed")
        } catch (e: Exception) {
            Log.e("AR_LIFECYCLE", "Error destroying segment manager: ${e.message}")
            FileLogger.e("LIFECYCLE", "Segment manager destroy error: ${e.message}")
        }

        // 6. Reset coordinate aligner
        try {
            if (::coordinateAligner.isInitialized) {
                coordinateAligner.reset()
                Log.d("AR_LIFECYCLE", "✅ Coordinate aligner reset")
            }
        } catch (e: Exception) {
            Log.e("AR_LIFECYCLE", "Error resetting coordinate aligner: ${e.message}")
            FileLogger.e("LIFECYCLE", "Aligner reset error: ${e.message}")
        }
        // Shutdown file logger
        try {
            FileLogger.shutdown()
            Log.d("AR_LIFECYCLE", "✅ FileLogger shutdown")
        } catch (e: Exception) {
            Log.e("AR_LIFECYCLE", "Error shutting down FileLogger: ${e.message}")
        }
        Log.d("AR_LIFECYCLE", "✅ All resources cleaned up")
    }

    // ========================================================================================
    // AR SESSION INITIALIZATION
    // ========================================================================================
    private fun initializeARSession() {
        Log.d("AR_CONFIG", "Initializing AR session")

        // Check ARCore availability
        val availability = ArCoreApk.getInstance().checkAvailability(this)

        Log.d("AR_CONFIG", "ARCore availability: $availability")

        if (availability != ArCoreApk.Availability.SUPPORTED_INSTALLED) {
            try {
                when (ArCoreApk.getInstance().requestInstall(this, true)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        Log.d("AR_CONFIG", "ARCore installation requested")
                        return
                    }
                    ArCoreApk.InstallStatus.INSTALLED -> {
                        Log.d("AR_CONFIG", "ARCore installed")
                    }
                    null -> {
                        Toast.makeText(this, "❌ ARCore not available", Toast.LENGTH_LONG).show()
                        return
                    }
                }
            } catch (e: Exception) {
                Log.e("AR_CONFIG", "ARCore install failed", e)
                FileLogger.e("AR_CONFIG", "ARCore install failed: ${e.message}")
                Toast.makeText(this, "❌ ARCore error: ${e.message}", Toast.LENGTH_LONG).show()
                return
            }
        }

        // Configure AR scene
        configureARScene()
    }

    private fun configureARScene() {
        Log.d("AR_CONFIG", "Configuring AR scene for outdoor navigation")

        // CRITICAL: Make planes visible for debugging
        arView.planeRenderer.isVisible = true
        arView.planeRenderer.isEnabled = true

        arView.configureSession { session, config ->
            try {
                // ============================================================================
                // LIGHT ESTIMATION
                // ============================================================================
                config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR

                // ============================================================================
                // PLANE FINDING - Enable both horizontal and vertical
                // ============================================================================
                config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL

                // ============================================================================
                // CAMERA FOCUS - Auto focus for outdoor
                // ============================================================================
                config.focusMode = Config.FocusMode.AUTO
                config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE

                // ============================================================================
                // DEPTH MODE - CRITICAL FOR OUTDOOR AR
                // ============================================================================
                // The Depth API provides DepthPoint hit results which are more accurate
                // than plane detection for outdoor environments. 87%+ of devices support this.
                if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                    config.depthMode = Config.DepthMode.AUTOMATIC
                    Log.d("AR_CONFIG", "✅ Depth mode: AUTOMATIC (outdoor-optimized)")
                } else {
                    config.depthMode = Config.DepthMode.DISABLED
                    Log.w("AR_CONFIG", "⚠️ Depth mode: DISABLED (device not supported)")
                    FileLogger.w("AR_CONFIG", "Depth mode: DISABLED")
                }

                // ============================================================================
                // INSTANT PLACEMENT - Fallback for difficult environments
                // ============================================================================
                config.instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
                Log.d("AR_CONFIG", "✅ Instant placement: LOCAL_Y_UP (fallback enabled)")

                // ============================================================================
                // CAMERA CONFIGURATION
                // ============================================================================
                val filter = CameraConfigFilter(session)
                filter.facingDirection = CameraConfig.FacingDirection.BACK

                val configs = session.getSupportedCameraConfigs(filter)
                if (configs.isNotEmpty()) {
                    session.cameraConfig = configs[0]
                    Log.d("AR_CONFIG", "✅ Camera configured: ${configs[0]}")
                }

                Log.d("AR_CONFIG", "✅ Session configured successfully for outdoor AR")
                isARConfigured = true

            } catch (e: Exception) {
                Log.e("AR_CONFIG", "Session configuration failed", e)
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
            // Notify OutdoorAnchorManager that session has started (if not already)
            if (!outdoorAnchorManager.isSessionStarted()) {
                outdoorAnchorManager.onSessionStarted()
            }

            handleARFrameUpdate(session, frame)
        }

        // Log depth support status for diagnostics
        arView.session?.let { session ->
            val depthSupported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
            Log.d("AR_CONFIG", "Device depth support: $depthSupported")
        }
    }

    // ========================================================================================
    // AR FRAME UPDATE - WITH PLANE DETECTION
    // ========================================================================================
    private fun handleARFrameUpdate(session: Session, frame: Frame) {
        val camera = frame.camera

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
                Log.w("AR_TRACKING", "Tracking stopped")
                FileLogger.w("AR_TRACKING", "Tracking stopped!")
            }
        }
    }
    /**
     * Toggle Phase 3 rendering on/off.
     * Useful for testing and comparison.
     */
    private fun togglePhase3Rendering() {
        usePhase3Rendering = !usePhase3Rendering

        if (usePhase3Rendering && !arRenderer.isPhase3Enabled()) {
            arRenderer.initializePhase3Components()
        }

        Toast.makeText(
            this,
            "Phase 3 rendering: ${if (usePhase3Rendering) "ON" else "OFF"}",
            Toast.LENGTH_SHORT
        ).show()

        Log.d("AR_DEBUG", "Phase 3 rendering toggled: $usePhase3Rendering")

        // Force re-render with new mode
        lastAnchorLocation?.let { anchor ->
            lifecycleScope.launch {
                arRenderer.clearRoute()
                renderRouteInAR(anchor, isForced = true)
            }
        }
    }

    /**
     * Handles plane detection and anchor creation during navigation.
     *
     * OUTDOOR AR OPTIMIZATION:
     * This function now uses OutdoorAnchorManager which provides:
     * 1. DepthPoint anchoring (best for outdoor - uses depth-from-motion)
     * 2. Stabilization delay (waits for ARCore to build world model)
     * 3. Multi-strategy fallback (Depth > Plane > Camera-relative)
     * 4. User-calibratable phone height for ground estimation
     *
     * @param frame The current AR frame from ARCore
     */
    private fun handlePlaneDetectionAndAnchor(session: Session, frame: Frame) {
        // Session is now passed directly - no need to get from arView

        // Get all detected planes and filter by state
        val allPlanes = frame.getUpdatedTrackables(Plane::class.java)
        val trackingPlanes = allPlanes.filter { it.trackingState == TrackingState.TRACKING }
        val horizontalPlanes = trackingPlanes.filter { it.type == Plane.Type.HORIZONTAL_UPWARD_FACING }

        // Log plane detection status once per second
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastPlaneLogTime > 1000) {
            lastPlaneLogTime = currentTime
            val stabilizationProgress = outdoorAnchorManager.getStabilizationProgress()
            Log.d("PLANE_DETECT", "Planes: total=${allPlanes.size}, tracking=${trackingPlanes.size}, " +
                    "horizontal=${horizontalPlanes.size}, stabilization=$stabilizationProgress%")
        }

        // ============================================================================
        // CASE 1: Anchor already exists and route rendering is pending
        // ============================================================================
        if (arRenderer.hasAnchor() && isRouteRenderingPending) {
            // ============================================================================
            // TRACKING GATE: Ensure stable tracking before rendering
            // ============================================================================
            val cameraTracking = frame.camera.trackingState == TrackingState.TRACKING
            val anchorTracking = arRenderer.isAnchorTracking()

            if (!cameraTracking || !anchorTracking) {
                Log.d("AR_SYNC", "Waiting for stable tracking: camera=$cameraTracking, anchor=$anchorTracking")
                return  // Don't render yet, wait for next frame
            }

            isRouteRenderingPending = false
            anchorCreationAttempts = 0

            Log.d("AR_SYNC", "Anchor confirmed ($lastAnchorStrategy) - tracking stable, triggering route rendering")
            FileLogger.ar("Anchor stable: $lastAnchorStrategy - rendering route")

            lastAnchorLocation?.let { loc ->
                lifecycleScope.launch {
                    delay(300)

                    // ====================================================================
                    // FIX 1.1: Handle segmentation mode separately
                    // ====================================================================
                    // PROBLEM: renderRouteInAR() returns immediately when segmentation
                    //          is enabled, resulting in no spheres being rendered.
                    //
                    // SOLUTION: When segmentation is enabled, directly trigger initial
                    //           segment creation instead of calling renderRouteInAR().
                    // ====================================================================
                    if (arRenderer.isSegmentationEnabled()) {
                        Log.d("AR_SYNC", "Segmentation mode - triggering initial segments")
                        FileLogger.segment("Initial segment creation triggered")

                        currentUserLocation?.let { userLoc ->
                            val session = arView.session
                            val frame = arView.frame
                            if (session != null && frame != null) {
                                // Force create first segment even if user is "off-route"
                                val created = routeSegmentManager?.forceCreateInitialSegment(
                                    userLoc, session, frame
                                ) ?: false

                                if (created) {
                                    FileLogger.segment("Initial segment created successfully")
                                } else {
                                    FileLogger.e("SEGMENT", "Failed to create initial segment!")
                                }
                            }
                        }
                    } else {
                        // Single anchor mode - use normal rendering
                        renderRouteInAR(loc, isForced = false)
                    }
                }
            }
            return
        }
        // ========================================================================
        // CASE 1.5: Update segment manager if segmentation is active
        // ========================================================================
        // This runs on EVERY frame (except when CASE 1 or CASE 2 trigger)
        // to update segments as user walks along the route.
        // ========================================================================
        if (arRenderer.isSegmentationEnabled() && arRenderer.hasAnchor()) {
            currentUserLocation?.let { userLocation ->
                val updated = routeSegmentManager?.updateUserPosition(userLocation, session, frame) ?: false

                if (updated) {
                    // Only log occasionally to prevent spam
                    if (System.currentTimeMillis() % 5000 < 100) {  // ~every 5 seconds
                        FileLogger.segment("Segments updated at user position")
                    }
                }
            }
        }

        // ============================================================================
        // CASE 2: Waiting for anchor creation
        // ============================================================================
        if (pendingAnchorCreation && !arRenderer.hasAnchor()) {
            anchorCreationAttempts++

            // Use already-available location instead of spamming lastLocation
            currentUserLocation?.let { location ->
                if (location.accuracy < 20.0f) {
                    pendingGpsForAnchor = location
                    if (anchorCreationAttempts % 10 == 1) {
                        Log.d("AR_SYNC", "Using current GPS: (${location.latitude}, ${location.longitude}) accuracy=${location.accuracy}m")
                    }
                }
            }
            // Update user feedback based on current state
            val stabilizationProgress = outdoorAnchorManager.getStabilizationProgress()
            val isReady = outdoorAnchorManager.isReadyForAnchoring(session, frame)

            runOnUiThread {
                val elapsedSeconds = (System.currentTimeMillis() - planeDetectionStartTime) / 1000

                when {
                    !isReady -> {
                        // Still stabilizing
                        tvInfo.text = "🔄 Stabilizing AR... ($stabilizationProgress%)\n" +
                                "👉 Move phone slowly\n" +
                                "🎯 Look at ground ahead"
                    }
                    horizontalPlanes.isNotEmpty() -> {
                        tvInfo.text = "✅ ${horizontalPlanes.size} ground plane(s) found\n" +
                                "🔍 Creating anchor...\n" +
                                "👉 Hold steady"
                    }
                    trackingPlanes.isNotEmpty() -> {
                        tvInfo.text = "⚠️ Planes detected (not horizontal)\n" +
                                "👉 Point camera at flat ground\n" +
                                "⏱️ ${elapsedSeconds}s elapsed"
                    }
                    else -> {
                        tvInfo.text = "🔍 Searching for ground...\n" +
                                "👉 Point camera at floor/ground\n" +
                                "🚶 Move slowly in open area\n" +
                                "⏱️ ${elapsedSeconds}s elapsed"
                    }
                }
            }

            // Only attempt anchor creation if stabilization is complete
            if (!isReady) {
                return
            }

            // ============================================================================
            // TRY OUTDOOR-OPTIMIZED ANCHOR CREATION
            // ============================================================================
            // ============================================================================
            // TRY OUTDOOR-OPTIMIZED ANCHOR CREATION
            // ============================================================================
            if (useOutdoorAnchoring) {
                val anchorNode = outdoorAnchorManager.createBestAnchor(session, frame)

                if (anchorNode != null) {
                    // ============================================================================
                    // CRITICAL FIX: Use setAnchorNode() instead of setAnchor()
                    // ============================================================================
                    // The OutdoorAnchorManager already created the AnchorNode and added it to
                    // the scene. We must use the SAME node, not create a duplicate.
                    //
                    // Before: We extracted the raw Anchor and called setAnchor(), which created
                    //         a SECOND AnchorNode. This caused:
                    //         1. Spheres attached to wrong node (disappeared when walking)
                    //         2. Double-free crash on exit (two nodes with same anchor)
                    //
                    // After: We pass the existing AnchorNode directly and transfer ownership
                    //        to ARRenderer. OutdoorAnchorManager clears its reference.
                    // ============================================================================

                    arRenderer.setAnchorNode(anchorNode)
                    lastAnchorStrategy = outdoorAnchorManager.getLastAnchorType()

                    // Tell ARRenderer what type of anchor this is for Y-height adjustment
                    arRenderer.setAnchorTypeFromString(lastAnchorStrategy)

                    // ============================================================================
                    // CALCULATE ANCHOR-CAMERA OFFSET
                    // ============================================================================
                    // The anchor is placed at the hit-test point, which may be 1-3m in front
                    // of the camera. Calculate this offset for sphere position compensation.
                    // ============================================================================
                    try {
                        val cameraPos = arView.cameraNode.worldPosition
                        val anchorPos = anchorNode.worldPosition

                        val offsetX = anchorPos.x - cameraPos.x
                        val offsetZ = anchorPos.z - cameraPos.z

                        arRenderer.setAnchorCameraOffset(offsetX, offsetZ)

                        Log.d("AR_SYNC", "Anchor-camera offset calculated:")
                        Log.d("AR_SYNC", "   Camera: (${cameraPos.x}, ${cameraPos.z})")
                        Log.d("AR_SYNC", "   Anchor: (${anchorPos.x}, ${anchorPos.z})")
                        Log.d("AR_SYNC", "   Offset: ($offsetX, $offsetZ)")
                    } catch (e: Exception) {
                        Log.w("AR_SYNC", "Could not calculate anchor-camera offset: ${e.message}")
                        FileLogger.w("AR_SYNC", "Anchor offset failed: ${e.message}")
                        arRenderer.clearAnchorCameraOffset()
                    }

                    // Transfer ownership to ARRenderer - prevents double-free on cleanup
                    outdoorAnchorManager.clearAnchorReference()

                    // ============================================================================
                    // GPS FALLBACK: Ensure we always have a location for route rendering
                    // ============================================================================
                    val gpsForAnchor = getBestAvailableGps()

                    if (gpsForAnchor != null) {
                        lastAnchorLocation = gpsForAnchor
                        pendingGpsForAnchor = null  // Clear pending, no longer needed

                        Log.d("AR_SYNC", "✅ GPS origin locked at anchor creation:")
                        Log.d("AR_SYNC", "   GPS: (${lastAnchorLocation?.latitude}, ${lastAnchorLocation?.longitude})")
                        Log.d("AR_SYNC", "   Accuracy: ${lastAnchorLocation?.accuracy}m")

                        // ============================================================================
                        // PHASE 3: Initialize terrain profiler with anchor location
                        // ============================================================================
                        if (usePhase3Rendering) {
                            val groundY = outdoorAnchorManager.getLastGroundY()
                            val confidence = outdoorAnchorManager.getLastGroundConfidence()
                            arRenderer.getTerrainProfiler()?.initialize(gpsForAnchor, groundY)
                            Log.d("AR_SYNC", "Terrain profiler initialized: groundY=$groundY, conf=$confidence")
                        }
                    } else {
                        // Critical: No GPS available - cannot render route
                        Log.e("AR_SYNC", "❌ CRITICAL: Anchor created but no GPS available!")
                        Log.e("AR_SYNC", "   Route rendering will fail!")
                        FileLogger.e("AR_SYNC", "CRITICAL: Anchor created but NO GPS!")

                        runOnUiThread {
                            Toast.makeText(this, "⚠️ GPS unavailable - route may not render", Toast.LENGTH_LONG).show()
                        }

                        // Still clear pending and set flags - maybe GPS arrives later
                        pendingGpsForAnchor = null
                    }

                    pendingAnchorCreation = false
                    isRouteRenderingPending = true

                    Log.d("AR_SYNC", "✅ Outdoor anchor created (strategy: $lastAnchorStrategy) " +
                            "after $anchorCreationAttempts attempts")
                    FileLogger.ar("Anchor created: $lastAnchorStrategy after $anchorCreationAttempts attempts")
                    lastAnchorLocation?.let { loc ->
                        FileLogger.ar("Anchor GPS: (${String.format("%.6f", loc.latitude)}, ${String.format("%.6f", loc.longitude)}), acc=${loc.accuracy}m")
                    }
                    Log.d("AR_SYNC", "   Anchor ownership transferred to ARRenderer")

                    runOnUiThread {
                        tvInfo.text = "✅ Anchor placed ($lastAnchorStrategy)!\n🎯 Rendering route..."
                        Toast.makeText(this, "✅ Anchor: $lastAnchorStrategy mode", Toast.LENGTH_SHORT).show()
                    }
                    return
                }
            }

            // ============================================================================
            // FALLBACK: Use original plane-based anchor creation
            // ============================================================================
            val success = tryCreateAnchorOnPlane(frame)

            if (success) {
                lastAnchorStrategy = "Plane"
                arRenderer.setAnchorTypeFromString("Plane") // Tell ARRenderer this is a Plane anchor for Y-height adjustment
                // ============================================================================
                // GPS FALLBACK: Ensure we always have a location for route rendering
                // ============================================================================
                val gpsForAnchor = getBestAvailableGps()

                if (gpsForAnchor != null) {
                    lastAnchorLocation = gpsForAnchor
                    pendingGpsForAnchor = null
                    Log.d("AR_SYNC", "✅ GPS origin locked at plane anchor creation:")
                    Log.d("AR_SYNC", "   GPS: (${lastAnchorLocation?.latitude}, ${lastAnchorLocation?.longitude})")
                } else {
                    Log.e("AR_SYNC", "❌ CRITICAL: Plane anchor created but no GPS available!")
                    FileLogger.e("AR_SYNC", "CRITICAL: Plane anchor NO GPS!")
                    pendingGpsForAnchor = null
                    runOnUiThread {
                        Toast.makeText(this, "⚠️ GPS unavailable - route may not render", Toast.LENGTH_LONG).show()
                    }
                }
                pendingAnchorCreation = false
                isRouteRenderingPending = true

                Log.d("AR_SYNC", "Plane anchor created after $anchorCreationAttempts attempts")

                runOnUiThread {
                    tvInfo.text = "✅ Anchor placed (Plane)!\n🎯 Rendering route..."
                }
            } else {
                // Check for timeout - use instant placement as fallback
                val elapsed = System.currentTimeMillis() - planeDetectionStartTime

                if (elapsed > PLANE_DETECTION_TIMEOUT_MS) {
                    Log.w("PLANE_DETECT", "Timeout after ${elapsed}ms - attempting instant placement")
                    FileLogger.w("PLANE", "Timeout ${elapsed}ms - trying instant")

                    runOnUiThread {
                        tvInfo.text = "⚠️ Detection timeout\n🔄 Using instant placement..."
                    }

                    tryInstantPlacement(frame)

                    if (arRenderer.hasAnchor()) {
                        lastAnchorStrategy = "Instant"
                        pendingAnchorCreation = false
                        isRouteRenderingPending = true
                    }
                }

                // Safety check: prevent infinite attempts
                if (anchorCreationAttempts > MAX_ANCHOR_CREATION_ATTEMPTS) {
                    Log.e("AR_SYNC", "Max anchor creation attempts reached ($MAX_ANCHOR_CREATION_ATTEMPTS)")
                    FileLogger.e("AR", "Anchor FAILED after $MAX_ANCHOR_CREATION_ATTEMPTS attempts")

                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "❌ Cannot detect ground. Try different location.",
                            Toast.LENGTH_LONG
                        ).show()

                        // Offer calibration option
                        tvInfo.text = "❌ Ground detection failed\n" +
                                "💡 Try: Moving to open area\n" +
                                "📱 Or adjust height calibration"
                    }

                    pendingAnchorCreation = false
                    anchorCreationAttempts = 0
                }
            }
        }
    }

    private fun handleTrackingFailure(reason: TrackingFailureReason?) {
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

    // ========================================================================================
    // ANCHOR CREATION - ENHANCED WITH MULTI-POINT HIT TESTING
    // ========================================================================================

    /**
     * Attempts to create an anchor on a detected plane using multi-point hit testing.
     *
     * PROBLEM SOLVED:
     * The original code only tested the screen center, which in outdoor AR often points
     * at the sky or distant objects. Ground planes are typically visible in the LOWER
     * portion of the screen when the user holds their phone naturally.
     *
     * SOLUTION:
     * We test a grid of points across the lower 2/3 of the screen, prioritizing:
     * 1. Points closer to the center (more stable anchors)
     * 2. Horizontal upward-facing planes (ground)
     * 3. Larger planes (more reliable tracking)
     *
     * @param frame The current AR frame
     * @return true if anchor was successfully created, false otherwise
     */
    private fun tryCreateAnchorOnPlane(frame: Frame): Boolean {
        Log.d("AR_ANCHOR", "Attempting anchor creation with multi-point hit testing")

        // Verify camera is tracking before attempting anchor creation
        if (frame.camera.trackingState != TrackingState.TRACKING) {
            Log.w("AR_ANCHOR", "Camera not tracking - cannot create anchor")
            FileLogger.w("AR_ANCHOR", "Camera not tracking!")
            return false
        }

        val width = arView.width.toFloat()
        val height = arView.height.toFloat()

        // ============================================================================
        // HIT TEST POINTS - OPTIMIZED FOR OUTDOOR GROUND DETECTION
        // ============================================================================
        // Screen layout (looking at phone):
        //   ┌─────────────────┐
        //   │   SKY / FAR     │  <- Top 1/3: AVOID - usually sky or distant objects
        //   │                 │
        //   ├─────────────────┤
        //   │  MIDDLE ZONE    │  <- Middle 1/3: Sometimes ground at distance
        //   │                 │     Try AFTER lower zones
        //   ├─────────────────┤
        //   │  GROUND ZONE    │  <- Bottom 1/3: BEST - most likely ground plane
        //   │   (Start here)  │     Try FIRST for outdoor optimization
        //   └─────────────────┘
        //
        // Priority order: Lower screen first (70% → 85% → 50%)
        // This significantly improves outdoor anchor success rate.
        // ============================================================================
        val hitTestPoints = listOf(
            // Priority 1-3: LOWER CENTER ZONE (most likely to hit ground outdoors)
            Pair(width * 0.5f, height * 0.70f),   // Lower center - best first try
            Pair(width * 0.5f, height * 0.80f),   // Even lower center
            Pair(width * 0.5f, height * 0.85f),   // Very low center (ground right in front)

            // Priority 4-5: LOWER LEFT AND RIGHT (spread across ground zone)
            Pair(width * 0.3f, height * 0.70f),   // Lower left
            Pair(width * 0.7f, height * 0.70f),   // Lower right

            // Priority 6-7: BOTTOM CORNERS (edges of ground zone)
            Pair(width * 0.3f, height * 0.85f),   // Bottom left
            Pair(width * 0.7f, height * 0.85f),   // Bottom right

            // Priority 8: CENTER (fallback - may work indoors or on slopes)
            Pair(width * 0.5f, height * 0.5f),    // Screen center

            // Priority 9-10: MIDDLE LEFT AND RIGHT (last resort)
            Pair(width * 0.3f, height * 0.5f),    // Middle left
            Pair(width * 0.7f, height * 0.5f)     // Middle right
        )

        Log.d("AR_ANCHOR", "Testing ${hitTestPoints.size} points for plane detection")

        // Try each point in priority order
        for ((index, point) in hitTestPoints.withIndex()) {
            val (px, py) = point
            val hitResults = frame.hitTest(px, py)

            if (hitResults.isEmpty()) {
                continue  // No hits at this point, try next
            }

            // Find the best hit result from this point
            val bestHit = findBestHitResult(hitResults)

            if (bestHit != null) {
                // Attempt to create anchor from this hit
                val anchor = bestHit.createAnchorOrNull()

                if (anchor != null && anchor.trackingState == TrackingState.TRACKING) {
                    // Success! Set the anchor and return
                    try { // CALCULATE ANCHOR-CAMERA OFFSET
                        val cameraPos = arView.cameraNode.worldPosition
                        val anchorPose = anchor.pose
                        val offsetX = anchorPose.tx() - cameraPos.x
                        val offsetZ = anchorPose.tz() - cameraPos.z

                        arRenderer.setAnchorCameraOffset(offsetX, offsetZ)
                        Log.d("AR_SYNC", "Plane anchor-camera offset: (${"%.2f".format(offsetX)}, ${"%.2f".format(offsetZ)})")
                    } catch (e: Exception) {
                        Log.w("AR_SYNC", "Could not calculate plane anchor offset: ${e.message}")
                        arRenderer.clearAnchorCameraOffset()
                    }

                    val trackable = bestHit.trackable
                    val trackableType = if (trackable is Plane) {
                        "Plane(${trackable.type})"
                    } else {
                        trackable.javaClass.simpleName
                    }

                    Log.d("AR_ANCHOR", "✅ Anchor created at point #${index + 1} ($px, $py) on $trackableType")

                    runOnUiThread {
                        Toast.makeText(this, "✅ Anchor placed on $trackableType", Toast.LENGTH_SHORT).show()
                    }

                    return true
                } else {
                    // Anchor creation failed or not tracking, clean up and try next point
                    anchor?.detach()
                    Log.d("AR_ANCHOR", "Point #${index + 1}: Anchor creation failed, trying next point")
                }
            }
        }

        // No valid anchor could be created from any test point
        Log.w("AR_ANCHOR", "No valid plane found at any of ${hitTestPoints.size} test points")
        FileLogger.w("AR_ANCHOR", "No plane found at ${hitTestPoints.size} points")
        return false
    }

    private fun findBestHitResult(hitResults: List<HitResult>): HitResult? {
        // Priority 1: Horizontal plane within polygon
        for (hit in hitResults) {
            val trackable = hit.trackable
            if (trackable is Plane &&
                trackable.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                trackable.isPoseInPolygon(hit.hitPose) &&
                hit.isValid(depthPoint = false, point = false)
            ) {
                Log.d("AR_ANCHOR", "Selected: Horizontal plane (in polygon)")
                return hit
            }
        }

        // Priority 2: Any horizontal plane
        for (hit in hitResults) {
            val trackable = hit.trackable
            if (trackable is Plane &&
                trackable.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                hit.isValid(depthPoint = false, point = false)
            ) {
                Log.d("AR_ANCHOR", "Selected: Horizontal plane (any)")
                return hit
            }
        }

        // Priority 3: Any plane
        for (hit in hitResults) {
            val trackable = hit.trackable
            if (trackable is Plane && hit.isValid(depthPoint = false, point = false)) {
                Log.d("AR_ANCHOR", "Selected: ${trackable.type} plane")
                return hit
            }
        }

        // Priority 4: Depth point
        for (hit in hitResults) {
            if (hit.trackable is DepthPoint && hit.isValid(depthPoint = true, point = false)) {
                Log.d("AR_ANCHOR", "Selected: Depth point")
                return hit
            }
        }

        return null
    }

    /**
     * Fallback anchor creation using ARCore's Instant Placement feature.
     *
     * WHEN THIS IS USED:
     * This is called when plane detection times out (after 10 seconds) and no horizontal
     * plane has been found. Instant Placement creates an anchor at an estimated depth
     * without requiring a detected plane.
     *
     * LIMITATIONS:
     * - The anchor position is less accurate than plane-based anchors
     * - The anchor may "jump" when ARCore later refines its understanding of the scene
     * - Best used as a last resort when plane detection is not possible
     *
     * IMPROVEMENT:
     * We now try multiple points (like plane detection) and use a closer distance (1.5m)
     * to place the anchor closer to where the user is standing.
     *
     * @param frame The current AR frame
     */
    private fun tryInstantPlacement(frame: Frame) {
        Log.d("AR_ANCHOR", "Trying instant placement fallback")

        val width = arView.width.toFloat()
        val height = arView.height.toFloat()

        // ============================================================================
        // INSTANT PLACEMENT DISTANCE
        // ============================================================================
        // This is the estimated distance from the camera to place the anchor.
        // - Too close (< 1m): May place anchor inside/behind camera
        // - Too far (> 3m): Anchor will be far from user, causing large positioning errors
        // - Sweet spot (1.5m): About arm's length in front and down
        val placementDistance = 1.5f

        // Try multiple points, focusing on lower screen where ground would be
        val instantPoints = listOf(
            Pair(width * 0.5f, height * 0.70f),  // Lower center
            Pair(width * 0.5f, height * 0.85f),  // Very low center
            Pair(width * 0.5f, height * 0.5f),   // Center
            Pair(width * 0.3f, height * 0.70f),  // Lower left
            Pair(width * 0.7f, height * 0.70f)   // Lower right
        )

        for ((index, point) in instantPoints.withIndex()) {
            val (px, py) = point
            val instantHits = frame.hitTestInstantPlacement(px, py, placementDistance)

            if (instantHits.isEmpty()) {
                continue
            }

            val anchor = instantHits[0].createAnchorOrNull()

            if (anchor != null && anchor.trackingState == TrackingState.TRACKING) {
                arRenderer.setAnchor(anchor)

                // Tell ARRenderer this is an Instant anchor for Y-height adjustment
                arRenderer.setAnchorTypeFromString("Instant")

                // ============================================================================
                // CALCULATE ANCHOR-CAMERA OFFSET FOR INSTANT PLACEMENT
                // ============================================================================
                try {
                    val cameraPos = arView.cameraNode.worldPosition
                    val anchorPose = anchor.pose
                    val offsetX = anchorPose.tx() - cameraPos.x
                    val offsetZ = anchorPose.tz() - cameraPos.z

                    arRenderer.setAnchorCameraOffset(offsetX, offsetZ)
                    Log.d("AR_SYNC", "Instant anchor-camera offset: (${"%.2f".format(offsetX)}, ${"%.2f".format(offsetZ)})")
                } catch (e: Exception) {
                    Log.w("AR_SYNC", "Could not calculate instant anchor offset: ${e.message}")
                    arRenderer.clearAnchorCameraOffset()
                }
                // ============================================================================
                // GPS FALLBACK: Ensure we always have a location for route rendering
                // ============================================================================
                val gpsForAnchor = getBestAvailableGps()

                if (gpsForAnchor != null) {
                    lastAnchorLocation = gpsForAnchor
                    pendingGpsForAnchor = null
                    Log.d("AR_SYNC", "✅ GPS origin locked at instant placement:")
                    Log.d("AR_SYNC", "   GPS: (${lastAnchorLocation?.latitude}, ${lastAnchorLocation?.longitude})")
                } else {
                    Log.e("AR_SYNC", "❌ CRITICAL: Instant anchor created but no GPS available!")
                    FileLogger.e("AR_SYNC", "CRITICAL: Instant anchor NO GPS!")
                    pendingGpsForAnchor = null
                    runOnUiThread {
                        Toast.makeText(this, "⚠️ GPS unavailable - route may not render", Toast.LENGTH_LONG).show()
                    }
                }
                pendingAnchorCreation = false
                isRouteRenderingPending = true
                Log.d("AR_ANCHOR", "✅ Instant placement anchor created at point #${index + 1}")

                runOnUiThread {
                    Toast.makeText(this, "⚠️ Using instant placement (less accurate)", Toast.LENGTH_LONG).show()
                }
                return
            } else {
                anchor?.detach()
            }
        }

        // All instant placement attempts failed
        Log.e("AR_ANCHOR", "❌ All instant placement attempts failed")
        FileLogger.e("AR_ANCHOR", "All instant placement FAILED")
        runOnUiThread {
            Toast.makeText(this, "❌ Cannot place anchor - try moving to a more open area", Toast.LENGTH_LONG).show()
        }
    }

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
                    Log.d("AR_RECALIB", "Double-tap detected - full recalibration")
                    forceRecalibration()
                    lastFlipTime = 0L  // Reset
                } else {
                    // First tap - try 180° flip
                    Log.d("AR_RECALIB", "Single tap - trying 180° flip")
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

        // Phase 3 toggle button
        btnPhase3Toggle.setOnClickListener {
            performHapticFeedback(it)
            usePhase3Rendering = !usePhase3Rendering
            btnPhase3Toggle.isSelected = usePhase3Rendering

            // Initialize Phase 3 if enabling
            if (usePhase3Rendering && !arRenderer.isPhase3Enabled()) {
                arRenderer.initializePhase3Components()
            }

            Toast.makeText(
                this,
                "Phase 3: ${if (usePhase3Rendering) "ON ✅" else "OFF ❌"}",
                Toast.LENGTH_SHORT
            ).show()

            // Force re-render with new mode
            lastAnchorLocation?.let { anchor ->
                lifecycleScope.launch {
                    arRenderer.clearRoute()
                    renderRouteInAR(anchor, isForced = true)
                }
            }
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

            Log.d("AR_NAVIGATION", "🎉 ARRIVED at ${selectedEndNode?.name}")
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
            Log.w("AR_SENSORS", "⚠️ No Gyroscope sensor - heading fusion disabled")
            FileLogger.w("SENSORS", "No gyroscope - fusion disabled")
            useSensorFusion = false
        } else {
            Log.d("AR_SENSORS", "✅ Gyroscope available - heading fusion enabled")
        }
    }

    private fun initializeSystems() {
        coordinateAligner = CoordinateAligner()
        //Initialize sensor fusion filters
        kalmanFilter = KalmanFilter()
        headingFusionFilter = HeadingFusionFilter()
        Log.d("AR_INIT", "Sensor fusion filters initialized")
        gpsBufferManager = GPSBufferManager(
            requiredSampleCount = 8,
            maxAccuracyThreshold = 10.0f,
            maxScatterDistance = 5.0f
        )

        arRenderer = ARRenderer(arView) { coordinateAligner.getYawOffset() }

        // ============================================================================
        // PHASE 3: Initialize performance rendering components
        // ============================================================================
        if (usePhase3Rendering) {
            arRenderer.initializePhase3Components()
            Log.d("AR_INIT", "Phase 3 components initialized")

            // FIX 3.3: Handle Phase 3 calculation errors
            arRenderer.getPositionCalculator()?.setOnCalculationError { error ->
                runOnUiThread {
                    Log.e("AR_RENDER", "Phase 3 calculation error: $error")
                    FileLogger.e("AR", "Phase 3 error: $error")
                    Toast.makeText(this, "⚠️ Render calculation failed", Toast.LENGTH_SHORT).show()

                    // Fallback to Phase 2 rendering
                    lastAnchorLocation?.let { loc ->
                        lifecycleScope.launch {
                            renderRouteInAR(loc, isForced = true)
                        }
                    }
                }
            }
        }

        // Initialize outdoor-optimized anchor manager
        outdoorAnchorManager = OutdoorAnchorManager(this, arView)

        // Initialize route segment manager (created but not started until navigation begins)
        // Note: We pass arView and yawOffset provider, same as ARRenderer
        routeSegmentManager = RouteSegmentManager(arView) { coordinateAligner.getYawOffset() }

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    handleLocationUpdate(location)
                }
            }
        }

        Log.d("AR_INIT", "Systems initialized (outdoor anchoring: $useOutdoorAnchoring, phase3: $usePhase3Rendering)")
    }

    private fun handleStartupMode() {
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
                Log.w("ADAPTIVE_FUSION", "⚠️ GPS accuracy ${location.accuracy.toInt()}m > threshold - fusion temporarily disabled")
                FileLogger.w("FUSION", "Disabled: GPS acc=${location.accuracy.toInt()}m")

                // Update UI to show fusion is paused
                runOnUiThread {
                    updateNavigationUI()  // Refresh route info with warning indicator
                }
            } else if (location.accuracy < ADAPTIVE_FUSION_RECOVERY_THRESHOLD && fusionDisabledByAdaptive) {
                // GPS recovered - re-enable fusion
                fusionDisabledByAdaptive = false
                Log.d("ADAPTIVE_FUSION", "✅ GPS accuracy ${location.accuracy.toInt()}m recovered - fusion re-enabled")
                FileLogger.d("FUSION", "Re-enabled: GPS acc=${location.accuracy.toInt()}m")

                // Reset Kalman filter to avoid using stale predictions
                if (::kalmanFilter.isInitialized) {
                    kalmanFilter.reset()
                    Log.d("ADAPTIVE_FUSION", "Kalman filter reset after recovery")
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
        // Store latest GPS for segment manager updates
        currentUserLocation = location

        checkDriftAndReAnchor(location)

        // ============================================================================
        // PHASE 3: Collect terrain samples during navigation
        // ============================================================================
        if (usePhase3Rendering && arRenderer.isAnchorTracking()) {
            // Add terrain sample from current position
            val groundY = outdoorAnchorManager.getLastGroundY()
            val confidence = outdoorAnchorManager.getLastGroundConfidence()
            if (confidence > 0.3f) {
                arRenderer.getTerrainProfiler()?.addSample(
                    location, groundY, confidence, lastAnchorStrategy
                )
            }
        }

        val arYaw = coordinateAligner.calculateYawFromForward(
            arView.cameraNode.forwardDirection.x,
            arView.cameraNode.forwardDirection.z
        )

        val alignmentUpdated = coordinateAligner.updateWithMotion(location, arYaw)

        // ============================================================================
        // PHASE 4: Apply gradual drift correction during walking
        // ============================================================================
        // Only apply when:
        // 1. User is walking (speed > 1 m/s)
        // 2. GPS bearing is available
        // 3. Helps correct accumulated drift over long walks
        if (location.hasSpeed() && location.speed > 1.0f && location.hasBearing()) {
            val driftCorrected = coordinateAligner.applyDriftCorrection(
                currentLocation = location,
                gpsBearing = location.bearing,
                arForwardYaw = arYaw
            )

            if (driftCorrected) {
                Log.d("AR_DRIFT", "Drift correction applied, new offset: ${coordinateAligner.getYawOffset()}°")
                FileLogger.d("AR_DRIFT", "Drift correction: offset=${coordinateAligner.getYawOffset()}°")
            }
        }

        if (alignmentUpdated) {
            // ============================================================================
            // TRACKING GATE: Don't rerender during poor tracking (causes flicker)
            // ============================================================================

            if (!arRenderer.isAnchorTracking()) {
                Log.d("AR_SYNC", "Skipping alignment rerender - anchor not tracking")
                FileLogger.w("AR_SYNC", "Alignment skip: no tracking")
                return
            }

            // ============================================================================
            // PHASE 3: Use incremental updates instead of full re-render
            // ============================================================================
            if (usePhase3Rendering && arRenderer.isPhase3Enabled()) {
                val now = System.currentTimeMillis()
                if (now - lastIncrementalUpdateTime >= incrementalUpdateThrottleMs) {
                    lastIncrementalUpdateTime = now
                    arRenderer.requestIncrementalUpdate(coordinateAligner.getYawOffset())
                    Log.d("AR_SYNC", "Incremental update requested")
                }
            } else {
                // Fallback to full re-render
                lastAnchorLocation?.let { anchor ->
                    lifecycleScope.launch {
                        arRenderer.clearRoute()
                        renderRouteInAR(anchor, isForced = false)
                    }
                }
            }
        }

        updateProgressOnRoute(location)
        updateLiveUI(location)
    }

    private fun startARNavigation(location: Location, isForced: Boolean) {
        // Record navigation start time
        if (navigationStartTime == 0L) {
            navigationStartTime = System.currentTimeMillis()
        }
        pendingGpsForAnchor = location // CRITICAL FIX: Don't set lastAnchorLocation here! NEW APPROACH: Store GPS in pendingGpsForAnchor, continuously update it, and only set lastAnchorLocation when the anchor is actually created.

        if (!coordinateAligner.isInitialized()) {
            val arYaw = coordinateAligner.calculateYawFromForward(
                arView.cameraNode.forwardDirection.x,
                arView.cameraNode.forwardDirection.z
            )
            coordinateAligner.initialize(currentTrueBearing.toDouble(), arYaw)
        }

        arRenderer.clearAnchor()
        updateStateUI(AppState.STEP_3_NAVIGATION)

        planeDetectionStartTime = System.currentTimeMillis()

        runOnUiThread {
            Toast.makeText(this, "🔍 Looking for floor...", Toast.LENGTH_SHORT).show()
            tvInfo.text = "🔍 Scanning...\n👉 Point at floor\n🤚 Move slowly"
        }

        Handler(Looper.getMainLooper()).postDelayed({
            pendingAnchorCreation = true

            runOnUiThread {
                tvInfo.text = "Ready!\n⚪ Look for white dots\n👉 Point down at 45°"
            }
        }, 2000)
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
        Log.d("AR_ROUTE", "Route length: ${"%.1f".format(routeLength)}m")
        FileLogger.nav("Route: ${routeNodePath.size} nodes, ${routeCoords.size} coords, ${"%.0f".format(routeLength)}m")

        // Decide whether to use segmentation based on route length
        if (useSegmentation && routeLength > SEGMENTATION_ROUTE_THRESHOLD) {
            Log.d("AR_ROUTE", "Route > ${SEGMENTATION_ROUTE_THRESHOLD}m - SEGMENTATION ENABLED")
            arRenderer.enableSegmentation()

            // Initialize segment manager with route data
            routeSegmentManager?.initialize(
                routeCoords = routeCoords,
                routeNodePath = routeNodePath,
                startNode = startNode,
                endNode = endNode,
                visitedNodeIds = visitedNodeIds
            )
        } else {
            Log.d("AR_ROUTE", "Route <= ${SEGMENTATION_ROUTE_THRESHOLD}m - SINGLE ANCHOR MODE")
            arRenderer.disableSegmentation()
        }
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

    private suspend fun renderRouteInAR(anchorLocation: Location, isForced: Boolean) {
        // ============================================================================
        // PHASE 3: Use background position calculation if enabled
        // ============================================================================
        if (usePhase3Rendering && arRenderer.isPhase3Enabled() && !isForced) {
            // Build milestone indices set
            val milestoneIndices = routeNodePath.mapNotNull { node ->
                routeCoords.indexOfFirst { coord ->
                    coord.lat == node.lat && coord.lng == node.lng
                }.takeIf { it >= 0 }
            }.toSet()

            arRenderer.requestPositionCalculation(
                anchorLocation = anchorLocation,
                routeCoords = routeCoords,
                milestoneIndices = milestoneIndices,
                anchorType = lastAnchorStrategy
            )

            Log.d("AR_RENDER", "Phase 3: Background position calculation requested")
            return  // Exit early - rendering will happen via callback
        }

        // Fallback to original rendering...
        // Skip if segmentation is handling route rendering
        if (arRenderer.isSegmentationEnabled()) {
            Log.d("AR_RENDER", "renderRouteInAR() skipped - segmentation mode active")
            FileLogger.ar("Render skipped: segmentation mode active")
            return
        }

        if (!arRenderer.hasAnchor()) {
            Log.w("AR_RENDER", "Cannot render: No anchor")
            FileLogger.w("RENDER", "Cannot render: no anchor")
            return
        }

        val startNode = selectedStartNode ?: return
        val endNode = selectedEndNode ?: return

        // Use the isForced parameter for logging
        val renderType = if (isForced) "FORCED" else "NORMAL"
        Log.d("AR_RENDER", "Starting $renderType render...")
        Log.d("AR_RENDER", arRenderer.getDebugInfo())

        arRenderer.renderRoute(
            anchorLocation = anchorLocation,
            routeCoords = routeCoords,
            routeNodePath = routeNodePath,
            startNode = startNode,
            endNode = endNode,
            visitedNodeIds = visitedNodeIds
        )

        delay(500)

        val sphereCount = arRenderer.getSphereCount()
        Log.d("AR_RENDER", "Render complete ($renderType): $sphereCount spheres")
        FileLogger.ar("Render: $sphereCount spheres ($renderType)")

        if (sphereCount == 0) {
            Log.e("AR_RENDER", "WARNING: No spheres rendered!")
            FileLogger.e("AR_RENDER", "WARNING: 0 spheres rendered!")
            runOnUiThread {
                Toast.makeText(this, "⚠️ Route render failed", Toast.LENGTH_LONG).show()
            }
        } else {
            runOnUiThread {
                Toast.makeText(this, "✅ $sphereCount spheres rendered", Toast.LENGTH_SHORT).show()
            }

            // ============================================================================
            // BEHIND-CAMERA DETECTION
            // ============================================================================
            // Check if spheres are behind the camera (indicates yaw offset error)
            // Wait a moment for user to stabilize phone position before checking
            // ============================================================================
            delay(1000)  // Wait 1 second for user to stabilize

            val behindCameraResult = arRenderer.analyzeBehindCamera()

            if (behindCameraResult.needsRecalibration) {
                Log.w("AR_RENDER", "⚠️ BEHIND-CAMERA DETECTED!")
                Log.w("AR_RENDER", "   ${behindCameraResult.percentBehind}% of spheres are behind camera")
                Log.w("AR_RENDER", "   This suggests yaw offset may be incorrect")
                FileLogger.w("RENDER", "BEHIND-CAMERA: ${behindCameraResult.percentBehind}%! Yaw may be wrong")

                runOnUiThread {
                    // Show warning with flip option
                    tvRecalculating.visibility = View.VISIBLE
                    tvRecalculating.text = "⚠️ Route appears to be behind you!\n\n" +
                            "👆 Tap once: Flip 180°\n" +
                            "👆 Tap twice: Full recalibration"

                    Toast.makeText(
                        this,
                        "⚠️ Spheres behind you! Tap the message to fix.",
                        Toast.LENGTH_LONG
                    ).show()
                }

                // Auto-hide warning after 8 seconds (longer to give user time to read)
                delay(8000)
                runOnUiThread {
                    if (tvRecalculating.visibility == View.VISIBLE) {
                        tvRecalculating.visibility = View.GONE
                    }
                }
            } else if (behindCameraResult.isMostlyBehind) {
                // Less severe - just log a warning
                Log.w("AR_RENDER", "⚠️ Many spheres behind camera (${behindCameraResult.percentBehind}%)")
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "💡 Some spheres may be behind you",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
    private fun updateProgressOnRoute(userLocation: Location) {
        if (routeCoords.isEmpty()) return

        var bestIdx = lastClosestRouteIndex
        var bestDist = Double.MAX_VALUE

        val startIdx = (lastClosestRouteIndex - 30).coerceAtLeast(0)
        val endIdx = (lastClosestRouteIndex + 200).coerceAtMost(routeCoords.size - 1)

        for (i in startIdx..endIdx) {
            val p = routeCoords[i]
            val d = ArUtils.distanceMeters(userLocation.latitude, userLocation.longitude, p.lat, p.lng)
            if (d < bestDist) {
                bestDist = d
                bestIdx = i
            }
        }

        if (bestIdx > lastClosestRouteIndex) {
            lastClosestRouteIndex = bestIdx
            markVisitedNodes(userLocation)

            if (abs(lastClosestRouteIndex - lastRenderProgressIndex) >= RERENDER_PROGRESS_DELTA) {
                lastRenderProgressIndex = lastClosestRouteIndex

                lastAnchorLocation?.let { anchor ->
                    lifecycleScope.launch {
                        arRenderer.clearRoute()
                        renderRouteInAR(anchor, isForced = false)
                    }
                }
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

                // Update segment manager with new visited nodes
                if (arRenderer.isSegmentationEnabled()) {
                    routeSegmentManager?.updateVisitedNodes(visitedNodeIds)
                }

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

    /**
     * Checks for GPS drift and triggers re-anchoring if necessary.
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
    private fun checkDriftAndReAnchor(currentLocation: Location) {
        val now = System.currentTimeMillis()

        // Enforce cooldown between re-anchors
        if (now - lastReAnchorTime < REANCHOR_COOLDOWN_MS) return

        lastAnchorLocation?.let { anchor ->
            // Calculate distance from anchor to current position
            val distance = ArUtils.distanceMeters(
                anchor.latitude, anchor.longitude,
                currentLocation.latitude, currentLocation.longitude
            )

            // Only re-anchor if drift is significant AND GPS is reliable
            if (distance > REANCHOR_THRESHOLD_METERS && currentLocation.accuracy < 15.0f) {
                lastReAnchorTime = now

                Log.d("AR_REANCHOR", "╔════════════════════════════════════════════════════════════")
                Log.d("AR_REANCHOR", "║ DRIFT DETECTED - INITIATING RE-ANCHOR")
                Log.d("AR_REANCHOR", "╠════════════════════════════════════════════════════════════")
                Log.d("AR_REANCHOR", "║ Drift distance: ${"%.1f".format(distance)}m")
                Log.d("AR_REANCHOR", "║ GPS accuracy: ${"%.1f".format(currentLocation.accuracy)}m")
                Log.d("AR_REANCHOR", "║ Old anchor: (${anchor.latitude}, ${anchor.longitude})")
                Log.d("AR_REANCHOR", "║ New anchor: (${currentLocation.latitude}, ${currentLocation.longitude})")
                Log.d("AR_REANCHOR", "╚════════════════════════════════════════════════════════════")
                FileLogger.nav("RE-ANCHOR: drift=${String.format("%.1f", distance)}m, acc=${String.format("%.1f", currentLocation.accuracy)}m")

                // Show user feedback
                runOnUiThread {
                    tvRecalculating.visibility = View.VISIBLE
                    tvRecalculating.text = "⚠️ Position drift: ${distance.toInt()}m\n🔄 Recalibrating..."
                }

                // ====================================================================
                // CRITICAL: Reset all coordinate-related state
                // ====================================================================

                // 1. Clear the old anchor and route
                arRenderer.clearAnchor()

                // 1.5. Clear all segments if segmentation is active
                if (arRenderer.isSegmentationEnabled()) {
                    routeSegmentManager?.clearAllSegments()
                    Log.d("AR_REANCHOR", "Cleared all route segments")
                }

                // 2. Reset the CoordinateAligner
                // The old yaw offset was calculated for the old anchor position
                // It will be wrong for the new anchor because the device has moved and rotated
                coordinateAligner.reset()

                // ============================================================================
                // PHASE 2: Reset sensor fusion filters on drift re-anchor
                // ============================================================================
                if (useSensorFusion) {
                    if (::kalmanFilter.isInitialized) {
                        kalmanFilter.reset()
                        Log.d("AR_REANCHOR", "✅ Kalman filter reset")
                    }
                    if (::headingFusionFilter.isInitialized) {
                        headingFusionFilter.reset()
                        Log.d("AR_REANCHOR", "✅ Heading fusion filter reset")
                    }
                    // Reset adaptive fusion state
                    fusionDisabledByAdaptive = false
                    Log.d("AR_REANCHOR", "✅ Adaptive fusion state reset")
                }

                // ============================================================================
                // PHASE 3: Reset terrain profiler on re-anchor
                // ============================================================================
                if (usePhase3Rendering) {
                    arRenderer.getTerrainProfiler()?.reset()
                    Log.d("AR_REANCHOR", "✅ Terrain profiler reset")
                }

                // 3. Reset anchor location (will be set when new anchor is created)
                // Keep old location as fallback in case GPS fails during re-anchor
                // Don't set to null - getBestAvailableGps() will use it as fallback
                pendingGpsForAnchor = currentLocation  // Start with current GPS

                // 4. Reset rendering state
                anchorCreationAttempts = 0
                isRouteRenderingPending = false

                // 5. Start plane detection for new anchor
                pendingAnchorCreation = true
                planeDetectionStartTime = System.currentTimeMillis()

                // 6. Re-initialize coordinate aligner with current compass and camera yaw
                val arYaw = coordinateAligner.calculateYawFromForward(
                    arView.cameraNode.forwardDirection.x,
                    arView.cameraNode.forwardDirection.z
                )
                coordinateAligner.forceReinitialize(currentTrueBearing.toDouble(), arYaw)
                FileLogger.d("COMPASS", "Calibration complete: bearing=${String.format("%.1f", currentTrueBearing)}°")
                Log.d("AR_REANCHOR", "Coordinate aligner reinitialized with:")
                Log.d("AR_REANCHOR", "  Compass: $currentTrueBearing°")
                Log.d("AR_REANCHOR", "  AR Yaw: $arYaw°")
                Log.d("AR_REANCHOR", "  New Offset: ${coordinateAligner.getYawOffset()}°")

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
        val arYaw = coordinateAligner.calculateYawFromForward(
            arView.cameraNode.forwardDirection.x,
            arView.cameraNode.forwardDirection.z
        )

        val alignmentError = coordinateAligner.getAlignmentError(currentTrueBearing.toDouble(), arYaw)

        // Calculate progress
        val totalPoints = routeCoords.size
        val progress = if (totalPoints > 0) {
            ((lastClosestRouteIndex.toFloat() / totalPoints) * 100).toInt()
        } else 0

        // Calculate distances
        val distanceToNext = calculateDistanceToNext(currentLocation)
        val remainingDistance = calculateRemainingDistance(currentLocation)

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
                    appendLine("📍 Position: $lastClosestRouteIndex/$totalPoints")
                    appendLine("🎯 Next: ${"%.1f".format(distanceToNext)}m")
                    appendLine("🧭 Align error: ${"%.1f".format(alignmentError)}°")
                    appendLine("📡 GPS: ${currentLocation.accuracy.toInt()}m")
                    appendLine("🔵 Spheres: ${arRenderer.getSphereCount()}")
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
                        appendLine("═══════════════════════════════════════")
                        appendLine("🎯 PHASE 3 RENDERING")
                        appendLine("   Enabled: ${if (usePhase3Rendering) "ON" else "OFF"}")
                        if (usePhase3Rendering && arRenderer.isPhase3Enabled()) {
                            arRenderer.getTerrainProfiler()?.getLatestEstimate()?.let { est ->
                                appendLine("   Terrain adj: ${"%.2f".format(est.heightAdjustment)}m")
                                appendLine("   Terrain conf: ${"%.0f".format(est.confidence * 100)}%")
                                appendLine("   Slope: ${"%.1f".format(est.slopeAngle)}°")
                            }
                        }
                    } else {
                        appendLine("   Disabled (manual)")
                    }
                    appendLine(arRenderer.getDebugInfo())
                    if (arRenderer.isSegmentationEnabled()) {
                        appendLine()
                        appendLine(routeSegmentManager?.getDiagnostics() ?: "")
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
        for (node in routeNodePath) {
            if (!visitedNodeIds.contains(node.id)) {
                return node.name ?: "Checkpoint"
            }
        }
        return selectedEndNode?.name ?: "Destination"
    }

    private fun calculateRemainingDistance(currentLocation: Location): Double {
        if (lastClosestRouteIndex >= routeCoords.size - 1) return 0.0

        var remaining = 0.0
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
        FileLogger.section("RECALIBRATION")
        FileLogger.d("RECALIB", "Manual recalibration triggered")
        Log.d("AR_RECALIB", "╔════════════════════════════════════════════════════════════")
        Log.d("AR_RECALIB", "║ MANUAL RECALIBRATION TRIGGERED")
        Log.d("AR_RECALIB", "╚════════════════════════════════════════════════════════════")

        runOnUiThread {
            tvRecalculating.visibility = View.VISIBLE
            tvRecalculating.text = "🔄 Recalibrating...\n👉 Hold phone steady"
        }

        // Get current GPS position for new anchor
        val currentGps = getBestAvailableGps()

        if (currentGps == null) {
            Log.e("AR_RECALIB", "❌ No GPS available for recalibration!")
            FileLogger.e("RECALIB", "No GPS for recalibration!")
            runOnUiThread {
                tvRecalculating.visibility = View.GONE
                Toast.makeText(this, "❌ No GPS signal - cannot recalibrate", Toast.LENGTH_LONG).show()
            }
            return
        }

        // ============================================================================
        // 1. Clear old anchor and route
        // ============================================================================
        arRenderer.clearAnchor()

        if (arRenderer.isSegmentationEnabled()) {
            routeSegmentManager?.clearAllSegments()
            Log.d("AR_RECALIB", "Cleared route segments")
        }

        // ============================================================================
        // 2. Reset coordinate aligner
        // ============================================================================
        coordinateAligner.reset()

        // ============================================================================
        // 3. PHASE 2: Reset sensor fusion filters
        // ============================================================================
        if (useSensorFusion) {
            if (::kalmanFilter.isInitialized) {
                kalmanFilter.reset()
                Log.d("AR_RECALIB", "✅ Kalman filter reset")
            }
            if (::headingFusionFilter.isInitialized) {
                headingFusionFilter.reset()
                Log.d("AR_RECALIB", "✅ Heading fusion filter reset")
            }
            // Reset adaptive fusion state
            fusionDisabledByAdaptive = false
            Log.d("AR_RECALIB", "✅ Adaptive fusion state reset")
        }

        // ============================================================================
        // PHASE 3: Reset terrain profiler
        // ============================================================================
        if (usePhase3Rendering) {
            arRenderer.getTerrainProfiler()?.reset()
            Log.d("AR_RECALIB", "✅ Terrain profiler reset")
        }

        // ============================================================================
        // 4. Re-initialize coordinate aligner with current compass and AR yaw
        // ============================================================================
        val arYaw = coordinateAligner.calculateYawFromForward(
            arView.cameraNode.forwardDirection.x,
            arView.cameraNode.forwardDirection.z
        )
        coordinateAligner.forceReinitialize(currentTrueBearing.toDouble(), arYaw)

        Log.d("AR_RECALIB", "New calibration:")
        Log.d("AR_RECALIB", "  Compass: $currentTrueBearing°")
        Log.d("AR_RECALIB", "  AR Yaw: $arYaw°")
        Log.d("AR_RECALIB", "  New Offset: ${coordinateAligner.getYawOffset()}°")

        // ============================================================================
        // 5. Update anchor location to current GPS
        // ============================================================================
        pendingGpsForAnchor = currentGps
        lastAnchorLocation = null  // Will be set when new anchor is created

        // ============================================================================
        // 6. Trigger new anchor creation
        // ============================================================================
        anchorCreationAttempts = 0
        isRouteRenderingPending = false
        pendingAnchorCreation = true
        planeDetectionStartTime = System.currentTimeMillis()

        Log.d("AR_RECALIB", "Waiting for new anchor creation at GPS: (${currentGps.latitude}, ${currentGps.longitude})")

        runOnUiThread {
            tvInfo.text = "🔄 Recalibrating...\n👉 Point at ground"
        }

        // Hide recalibrating message after timeout
        Handler(Looper.getMainLooper()).postDelayed({
            runOnUiThread {
                if (tvRecalculating.visibility == View.VISIBLE) {
                    tvRecalculating.visibility = View.GONE
                    if (arRenderer.hasAnchor()) {
                        Toast.makeText(this, "✅ Recalibrated!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "⚠️ Recalibration pending - point at ground", Toast.LENGTH_LONG).show()
                    }
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
        Log.d("AR_RECALIB", "╔════════════════════════════════════════════════════════════")
        Log.d("AR_RECALIB", "║ 180° FLIP TRIGGERED")
        Log.d("AR_RECALIB", "╚════════════════════════════════════════════════════════════")
        FileLogger.nav("180° FLIP triggered")

        // Check if we have an anchor to work with
        if (lastAnchorLocation == null) {
            Log.e("AR_RECALIB", "❌ No anchor location - cannot flip")
            FileLogger.e("RECALIB", "Flip failed: no anchor location")
            runOnUiThread {
                Toast.makeText(this, "❌ No anchor - try full recalibration", Toast.LENGTH_LONG).show()
            }
            return
        }

        // Check if anchor is still valid
        if (!arRenderer.hasAnchor()) {
            Log.e("AR_RECALIB", "❌ No AR anchor - cannot flip")
            FileLogger.e("RECALIB", "Flip failed: no AR anchor")
            runOnUiThread {
                Toast.makeText(this, "❌ AR anchor lost - try full recalibration", Toast.LENGTH_LONG).show()
            }
            return
        }

        runOnUiThread {
            tvRecalculating.visibility = View.VISIBLE
            tvRecalculating.text = "🔄 Flipping 180°..."
        }

        // Get current offset and flip it
        val currentOffset = coordinateAligner.getYawOffset()
        val newOffset = (currentOffset + 180.0) % 360.0

        Log.d("AR_RECALIB", "Yaw offset flip:")
        Log.d("AR_RECALIB", "  Before: $currentOffset°")
        Log.d("AR_RECALIB", "  After: $newOffset°")
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
            Log.d("AR_RECALIB", "HeadingFusion synced: $currentFusedHeading° → $newFusedHeading°")
        }

        // Re-render route with new orientation
        lastAnchorLocation?.let { anchor ->
            lifecycleScope.launch {
                // Clear existing spheres
                arRenderer.clearRoute()

                // Also clear segments if using segmentation
                if (arRenderer.isSegmentationEnabled()) {
                    routeSegmentManager?.clearAllSegments()
                }

                // Re-render with flipped orientation
                renderRouteInAR(anchor, isForced = true)

                // Check if flip fixed the problem
                delay(1500)
                val result = arRenderer.analyzeBehindCamera()

                runOnUiThread {
                    tvRecalculating.visibility = View.GONE

                    if (result.isMostlyBehind) {
                        // Flip didn't help - suggest full recalibration
                        Toast.makeText(
                            this@ArNavigationActivity,
                            "⚠️ Still behind - tap again for full recalibration",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(
                            this@ArNavigationActivity,
                            "✅ Flip successful! Spheres should be visible now",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        } ?: run {
            // This shouldn't happen due to earlier check, but just in case
            runOnUiThread {
                tvRecalculating.visibility = View.GONE
                Toast.makeText(this, "❌ Error during flip", Toast.LENGTH_SHORT).show()
            }
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
            Log.d("AR_GPS", "Using pendingGpsForAnchor: (${it.latitude}, ${it.longitude})")
            return it
        }

        // Priority 2: GPSBufferManager's weighted average
        val weightedLocation = gpsBufferManager.calculateWeightedAverage()
        if (weightedLocation != null) {
            Log.w("AR_GPS", "⚠️ FALLBACK: Using GPSBufferManager weighted average: (${weightedLocation.latitude}, ${weightedLocation.longitude})")
            return weightedLocation
        }

        // Priority 3: GPSBufferManager's last sample
        val lastSample = gpsBufferManager.getLastSample()
        if (lastSample != null) {
            Log.w("AR_GPS", "⚠️ FALLBACK: Using GPSBufferManager last sample: (${lastSample.latitude}, ${lastSample.longitude})")
            return lastSample
        }

        // Priority 4: Existing anchor location (for re-anchoring scenarios)
        lastAnchorLocation?.let {
            Log.w("AR_GPS", "⚠️ FALLBACK: Using existing lastAnchorLocation: (${it.latitude}, ${it.longitude})")
            return it
        }

        // Priority 5: No GPS available
        Log.e("AR_GPS", "❌ CRITICAL: No GPS location available!")
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
                    tvStepDesc.text = "Wave phone in figure-8\nWaiting for accuracy..."
                    ivStepIcon.setImageResource(R.drawable.ic_infinite)
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
                SensorManager.getOrientation(rotationMatrix, orientationAngles)

                val azimuthDeg = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
                val trueAzimuth = azimuthDeg + magneticDeclination

                currentTrueBearing = (trueAzimuth + 360) % 360

                // ============================================================================
                // PHASE 2: Update heading fusion filter with compass
                // ============================================================================
                if (useSensorFusion && ::headingFusionFilter.isInitialized) {
                    headingFusionFilter.updateCompass(currentTrueBearing)
                    // Use fused heading instead of raw compass
                    smoothedAzimuth = headingFusionFilter.getFusedHeading()
                } else {
                    // Fallback to original smoothing
                    smoothedAzimuth = (smoothedAzimuth * 0.90f) + (trueAzimuth * 0.10f)
                }

                currentBearing = (smoothedAzimuth + 360) % 360

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