package com.example.orientar.navigation.ar

/**
 * Centralized configuration for AR rendering performance.
 * All tunable parameters in one place for easy adjustment.
 */
object ARPerformanceConfig {

    // ========================================================================================
    // THREADING
    // ========================================================================================

    /** Use background thread for position calculations */
    const val USE_BACKGROUND_CALCULATION = true

    /** Maximum time to spend on main thread per frame (ms) */
    const val MAX_MAIN_THREAD_TIME_MS = 8L  // Half of 16ms frame budget

    // ========================================================================================
    // SPHERE RENDERING
    // ========================================================================================

    /** Base height above anchor (meters) */
    const val BASE_SPHERE_HEIGHT = 1.2f

    /** Minimum sphere height (prevents underground) */
    const val MIN_SPHERE_HEIGHT = 0.5f

    /** Maximum sphere height (prevents flying) */
    const val MAX_SPHERE_HEIGHT = 3.0f

    /** Base radius for path spheres (meters) */
    const val PATH_SPHERE_RADIUS = 0.20f

    /** Base radius for milestone spheres (meters) */
    const val MILESTONE_SPHERE_RADIUS = 0.45f

    // ========================================================================================
    // DISTANCE-BASED SCALING (Cutoff Prevention)
    // ========================================================================================

    // ========================================================================================
    // DISTANCE-BASED SCALING (Cutoff Prevention)
    // ========================================================================================

    // ============================================================================
    // BUG-006 FIX: Centralized MAX_RENDER_DISTANCE constant
    // ============================================================================
    // PROBLEM: SpherePositionCalculator used 100f while ArUtils used 500.0
    //          This inconsistency caused spheres to appear/disappear unexpectedly.
    //
    // SOLUTION: Single source of truth for render distance limit.
    //           All files should reference this constant instead of local values.
    //
    // VALUE RATIONALE:
    //   - 100m is practical limit for AR visibility (objects beyond blur together)
    //   - Performance: fewer spheres = smoother rendering
    //   - Segmentation handles long routes by creating nearby anchors
    // ============================================================================
    /** Maximum distance to render spheres from anchor (meters) */
    const val MAX_RENDER_DISTANCE = 100.0f

    /** Maximum reasonable distance for AR calculations (meters) - for validation */
    const val MAX_AR_CALCULATION_DISTANCE = 500.0f

    /** Distance at which scaling starts (meters) */
    const val SCALE_START_DISTANCE = 10f

    /** Maximum scale multiplier for distant spheres */
    const val MAX_DISTANCE_SCALE = 2.5f

    /** Distance for maximum scale (meters) */
    const val SCALE_MAX_DISTANCE = 50f

    /** Far clip plane distance (meters) */
    const val FAR_CLIP_DISTANCE = 100f

    // ========================================================================================
    // RENDER UPDATE THROTTLING
    // ========================================================================================

    /** Minimum angle change to trigger re-render (degrees) */
    const val MIN_ANGLE_CHANGE_FOR_RENDER = 1.0

    /** Minimum position change to trigger re-render (meters) */
    const val MIN_POSITION_CHANGE_FOR_RENDER = 0.5

    /** Maximum render updates per second */
    const val MAX_RENDER_UPDATES_PER_SECOND = 10

    /** Use incremental updates instead of full re-render */
    const val USE_INCREMENTAL_UPDATES = true

    // ========================================================================================
    // TERRAIN PROFILING
    // ========================================================================================

    /** Enable terrain learning for height adjustment */
    const val ENABLE_TERRAIN_PROFILING = true

    /** Maximum terrain samples to keep */
    const val MAX_TERRAIN_SAMPLES = 100

    /** Terrain sample validity duration (ms) */
    const val TERRAIN_SAMPLE_VALIDITY_MS = 60000L  // 1 minute

    // ========================================================================================
    // GROUND VALIDATION
    // ========================================================================================

    /** Minimum plane area for ground (m²) */
    const val MIN_GROUND_PLANE_AREA = 1.5f

    /** Minimum surface normal Y component (0.85 = ~30° from horizontal) */
    const val MIN_SURFACE_NORMAL_Y = 0.85f

    /** Valid height range below camera (meters) */
    const val MIN_GROUND_HEIGHT_BELOW_CAMERA = 0.7f
    const val MAX_GROUND_HEIGHT_BELOW_CAMERA = 2.2f

    // ========================================================================================
    // SEGMENTATION
    // ========================================================================================

    /** Segment length (meters) */
    const val SEGMENT_LENGTH = 15.0f

    /** Overlap between segments (meters) */
    const val SEGMENT_OVERLAP = 3.0f

    /** Maximum active segments */
    const val MAX_ACTIVE_SEGMENTS = 5

    /** Segments to create ahead of user */
    const val SEGMENT_LOOKAHEAD = 3
}