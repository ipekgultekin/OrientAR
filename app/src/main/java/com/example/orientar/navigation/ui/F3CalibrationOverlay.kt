package com.example.orientar.navigation.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.orientar.R
import kotlin.math.max

/**
 * F3CalibrationOverlay — bottom card shown during dual-delta walk calibration.
 *
 * # Plan B refactor (SCRUM-107 Step 2C Hot Fix #4 → Plan B)
 *
 * Previous implementation dynamically inflated `f3_calibration_overlay.xml` and added
 * it to `android.R.id.content` (ContentFrameLayout). Despite Hot Fixes #1-#4 setting
 * correct LayoutParams (verified via runtime diagnostic log: gravity=81,
 * width=MATCH_PARENT, height=WRAP_CONTENT, margins all correct), the view rendered
 * at near-fullscreen size (2138px / 2340px) due to a measurement-pass interaction
 * with the dynamic inflate path I couldn't pinpoint from outside.
 *
 * **Solution**: migrate to F4's proven static-include pattern. F3's layout is now
 * statically embedded in `activity_ar_navigation.xml` with `@+id/layoutF3Overlay`.
 * The activity resolves the container via `findViewById` and passes it to this
 * class's constructor. We toggle `visibility` and animate `alpha`; we never
 * inflate, add, or remove views.
 *
 * # Listener-driven model (unchanged)
 * Single entry point [handleState] dispatches all state changes. The activity invokes
 * this via its `walkCalibrationListener` field.
 *
 * # Show timing (unchanged)
 * On [WalkCalState.Waiting], schedules show after **250 ms** (cancellable) to avoid
 * a flash for instant-compass-shortcut users.
 *
 * # Backup timer (unchanged)
 * On [WalkCalState.Waiting], schedules a **35 s wall-clock** backup that fires
 * [WalkCalState.TimeoutFallback] through the listener — safety net if AR frames stop.
 *
 * # Dynamic walk threshold (unchanged)
 * Displays `"X.X / Y m"` where `Y = max(3, 0.5 * gpsAccuracyM)`.
 *
 * # Lifecycle
 * Caller MUST invoke [destroy] from `Activity.onDestroy()` to cancel pending Handler
 * callbacks. View itself is part of the activity layout — destroyed automatically.
 *
 * SCRUM-107 Step 2C Plan B.
 */
class F3CalibrationOverlay(
    private val activity: Activity,
    private val f3Container: View,
    /**
     * Navigation UI overlays (top route header, bottom nav card, compass HUD,
     * debug buttons) to hide while F3 is visible. Restored on terminal state.
     * SCRUM-107 Step 2C Hot Fix #7.
     */
    private val navUIViews: List<View>
) {
    companion object {
        private const val SHOW_DELAY_MS = 250L
        private const val BACKUP_TIMEOUT_MS = 35_000L  // 5s past activity's 30s
        private const val FADE_IN_MS = 300L
        private const val FADE_OUT_MS = 200L
    }

    private val handler = Handler(Looper.getMainLooper())

    private var pendingShowRunnable: Runnable? = null
    private var backupTimeoutRunnable: Runnable? = null

    /** Stored so the backup-timer Runnable can route TimeoutFallback through the same listener path. */
    private var lastListener: ((WalkCalState) -> Unit)? = null

    /** Active fade animator; nullable so terminal states can cancel mid-fade-in. */
    private var currentFadeAnimator: android.view.ViewPropertyAnimator? = null

    /** True while the container is shown or fading in. Guards against duplicate show / stale Progress. */
    private var isVisible: Boolean = false

    // Resolved child view references (cached at construction)
    private val tvTitle: TextView = f3Container.findViewById(R.id.tvF3Title)
    private val tvSubtitle: TextView = f3Container.findViewById(R.id.tvF3Subtitle)
    private val tvBigNumber: TextView = f3Container.findViewById(R.id.tvF3BigNumber)
    private val tvNumberUnit: TextView = f3Container.findViewById(R.id.tvF3NumberUnit)
    private val tvGpsQuality: TextView = f3Container.findViewById(R.id.tvF3GpsQuality)

    // ====================================================================
    // PUBLIC API
    // ====================================================================

    /**
     * Single dispatch entry — activity invokes this for every [WalkCalState] transition.
     * Routes to the appropriate internal handler based on state type.
     */
    fun handleState(state: WalkCalState) {
        when (state) {
            is WalkCalState.Waiting -> onWaiting()
            is WalkCalState.Progress -> onProgress(state)
            is WalkCalState.ShortcutSuccess -> onTerminalState(logTag = "shortcut")
            is WalkCalState.Completed -> onTerminalState(logTag = "completed")
            is WalkCalState.TimeoutFallback -> onTerminalState(logTag = "timeout")
        }
    }

    /**
     * Wire the listener so the backup timer can route `TimeoutFallback` through the same path.
     * Called by the activity once during initialization.
     */
    fun bindListener(listener: ((WalkCalState) -> Unit)?) {
        lastListener = listener
    }

    /**
     * Cancel all pending Handler callbacks. View itself is owned by the activity layout
     * and destroyed automatically when the activity dies — no view removal needed.
     * Call from `Activity.onDestroy()`.
     */
    fun destroy() {
        cancelAllTimers()
        currentFadeAnimator?.cancel()
        currentFadeAnimator = null
        lastListener = null
        // Defensive: ensure view ends in hidden state even if Activity is reused (config change)
        f3Container.visibility = View.GONE
        isVisible = false
        // SCRUM-107 Step 2C Hot Fix #7: restore nav UI on destroy in case F3 was visible
        // when activity died (e.g., user backed out mid-calibration). Harmless if already VISIBLE.
        navUIViews.forEach { it.visibility = View.VISIBLE }
        android.util.Log.d("AR_LIFECYCLE", "F3CalibrationOverlay cleaned up")
    }

    // ====================================================================
    // INTERNAL — state handlers
    // ====================================================================

    /** Schedule show after 250 ms; arm 35 s backup timeout. */
    private fun onWaiting() {
        // Cancel any prior pending show (defensive — should be no-op in typical flow)
        cancelAllTimers()

        // SCRUM-107 Step 2C Hot Fix #8: hide nav UI IMMEDIATELY (before the 250ms show
        // delay) to prevent a flash of route header / Recalibrate / EndNav / compass HUD
        // between F2 fade-out completion and F3 fade-in start. Previously this lived in
        // showNow() which only fires after the 250ms delay — too late.
        //
        // Shortcut edge case: if ShortcutSuccess fires within 33ms (~2 frames at 60fps)
        // before F3 shows, onTerminalState's withEndAction restores nav UI. Net visible
        // gap: ~2 frames of hidden nav UI before it returns — imperceptible.
        navUIViews.forEach { it.visibility = View.GONE }

        pendingShowRunnable = Runnable { showNow() }
        handler.postDelayed(pendingShowRunnable!!, SHOW_DELAY_MS)

        backupTimeoutRunnable = Runnable {
            android.util.Log.w("WALK_CAL", "F3 backup timer fired (AR frames may have stopped)")
            lastListener?.invoke(WalkCalState.TimeoutFallback)
        }
        handler.postDelayed(backupTimeoutRunnable!!, BACKUP_TIMEOUT_MS)
    }

    /** Update live numbers + GPS quality row + dynamic title/subtitle by GPS quality. */
    private fun onProgress(p: WalkCalState.Progress) {
        // Defensive: only update if currently visible (avoids stale Progress from rapid state changes)
        if (!isVisible) return

        // SCRUM-107 Step 2C Hot Fix #9: defensive — keep nav UI hidden while F3 is visible.
        // Progress fires every ~1-3 s during walk calibration; this catches any unexpected
        // VISIBLE override from periodic updates (e.g., handleNavigationUpdate, recalibration
        // side effects, future code paths). No-op in the typical case since onWaiting +
        // showNow already set GONE.
        navUIViews.forEach { it.visibility = View.GONE }

        val requiredMeters = max(3.0f, 0.5f * p.gpsAccuracyM)

        tvBigNumber.text = String.format("%.1f", p.gpsDistanceM)
        tvNumberUnit.text = "/ ${requiredMeters.toInt()} m"

        if (p.gpsAccuracyM > 10f) {
            // Poor GPS — switch to "Move to an open area" messaging
            tvGpsQuality.text = activity.getString(R.string.f3_gps_quality_poor, p.gpsAccuracyM.toInt())
            tvGpsQuality.setTextColor(ContextCompat.getColor(activity, R.color.orientar_primary))
            tvTitle.text = activity.getString(R.string.f3_title_move_open)
            tvSubtitle.text = activity.getString(R.string.f3_subtitle_poor_gps)
        } else {
            // Good GPS — default "Walk forward" messaging
            tvGpsQuality.text = activity.getString(R.string.f3_gps_quality_good)
            tvGpsQuality.setTextColor(ContextCompat.getColor(activity, R.color.status_success))
            tvTitle.text = activity.getString(R.string.f3_title_walk_forward)
            tvSubtitle.text = activity.getString(R.string.f3_subtitle_default)
        }
    }

    /**
     * Shared terminal-state handler for ShortcutSuccess / Completed / TimeoutFallback.
     * Cancels pending show + backup timers, then fades out the container if visible.
     */
    private fun onTerminalState(logTag: String) {
        cancelAllTimers()

        if (!isVisible) return  // never shown — nothing to fade
        currentFadeAnimator?.cancel()
        currentFadeAnimator = f3Container.animate()
            .alpha(0f)
            .setDuration(FADE_OUT_MS)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    f3Container.visibility = View.GONE
                    isVisible = false
                    currentFadeAnimator = null
                    // SCRUM-107 Step 2C Hot Fix #7: restore navigation UI after F3 fully fades.
                    // Restoring AFTER fade-out completes (not before) prevents a visual flash
                    // of overlapping F3 + nav UI during the 200ms fade.
                    navUIViews.forEach { it.visibility = View.VISIBLE }
                }
            })
        currentFadeAnimator?.start()
        android.util.Log.d("WALK_CAL", "F3 fade-out ($logTag)")
    }

    // ====================================================================
    // INTERNAL — show helpers
    // ====================================================================

    private fun showNow() {
        if (isVisible) return  // already showing, no-op

        // SCRUM-107 Step 2C Hot Fix #9: re-hide nav UI to overcome the VISIBLE override
        // from updateStateUI(STEP_3_NAVIGATION) that fires ~1ms after onWaiting (between
        // Waiting and showNow). startARNavigation invokes the listener BEFORE the state
        // transition, so updateStateUI's STEP_3 branch sets nav UI VISIBLE again right
        // after our onWaiting GONE. This re-hide ensures nav UI stays GONE during F3.
        // (Hot Fix #8's onWaiting GONE is still needed to kill the F2→F3 flash.)
        navUIViews.forEach { it.visibility = View.GONE }

        f3Container.alpha = 0f
        f3Container.visibility = View.VISIBLE
        isVisible = true

        // Fade in
        currentFadeAnimator?.cancel()
        currentFadeAnimator = f3Container.animate()
            .alpha(1f)
            .setDuration(FADE_IN_MS)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    currentFadeAnimator = null
                }
            })
        currentFadeAnimator?.start()
        android.util.Log.d("WALK_CAL", "F3 show")

        // Hot Fix #7 diagnostic — confirm upper-center positioning + measurement
        f3Container.post {
            android.util.Log.d(
                "WALK_CAL",
                "F3 measured (upper-center): w=${f3Container.width}px, h=${f3Container.height}px, " +
                    "top=${f3Container.top}px, visibility=${f3Container.visibility}, alpha=${f3Container.alpha}"
            )
        }
    }

    private fun cancelAllTimers() {
        pendingShowRunnable?.let { handler.removeCallbacks(it) }
        pendingShowRunnable = null
        backupTimeoutRunnable?.let { handler.removeCallbacks(it) }
        backupTimeoutRunnable = null
    }
}
