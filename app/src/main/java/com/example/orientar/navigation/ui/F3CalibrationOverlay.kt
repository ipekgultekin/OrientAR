package com.example.orientar.navigation.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.orientar.R
import com.example.orientar.navigation.util.FileLogger
import com.example.orientar.navigation.util.GpsQuality
import com.example.orientar.navigation.util.GpsQualityClassifier

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
 * # SCRUM-107 Stage 3 — Internal state machine
 *
 * The overlay drives its own 5-state FSM ([InternalState]) distinct from the external
 * [WalkCalState] event stream:
 *
 *  - **HIDDEN** — initial; reached again after fade-out completes
 *  - **ONSET** — F3 just shown; "Calibrating route" title, spinner + walk-dot pulse running
 *  - **WAITING_FOR_WALK** — compass-disagreement throttled-confirmed (audit Q1, fired by Stage 4);
 *    subtitle nudges user to walk
 *  - **WALK_ACTIVE** — Progress event arrived with displacement > [WALK_ACTIVE_THRESHOLD_M]
 *    (audit Risk R2); title swaps to "Walk forward"
 *  - **TERMINAL** — success terminal (Calibrated + check icon + walk-dot becomes green check)
 *    for [TERMINAL_MIN_DURATION_MS] before fade-out
 *
 * Failure terminal ([WalkCalState.TimeoutFallback]) skips TERMINAL entirely and fades
 * immediately. Early shortcut (< 250 ms before F3 even appears) cancels timers without
 * showing anything — the success notification suffices (audit Risk R3 Option A).
 *
 * # Subtitle precedence
 * Poor GPS > Fair GPS > internal-state-driven copy. See [computeSubtitle].
 *
 * # GPS quality
 * [updateGpsQuality] is public so Stage 4's Activity-level GPS listener can drive the pill
 * before any Progress event fires. Idempotent — repeated equal-quality calls no-op.
 *
 * # Lifecycle
 * Caller MUST invoke [destroy] from `Activity.onDestroy()` to cancel pending Handler callbacks
 * and the pulse animator. View itself is part of the activity layout — destroyed automatically.
 *
 * SCRUM-107 Step 2C Plan B → Stage 3 state machine.
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
        /** Minimum visible duration for the success TERMINAL state before fade-out begins (audit Risk R3). */
        private const val TERMINAL_MIN_DURATION_MS = 1500L
        /** Walk-dot pulse cycle period (scale 1.0→1.15→1.0, alpha 1.0→0.85→1.0). */
        private const val PULSE_DURATION_MS = 1400L
        /** Distance threshold (m) for transitioning ONSET/WAITING_FOR_WALK → WALK_ACTIVE (audit Risk R2). */
        private const val WALK_ACTIVE_THRESHOLD_M = 1.5f
    }

    /** Internal FSM driving F3's visual state. Distinct from external [WalkCalState] events. */
    private enum class InternalState { HIDDEN, ONSET, WAITING_FOR_WALK, WALK_ACTIVE, TERMINAL }

    private val handler = Handler(Looper.getMainLooper())

    private var pendingShowRunnable: Runnable? = null
    private var backupTimeoutRunnable: Runnable? = null
    private var terminalDelayRunnable: Runnable? = null

    /** Stored so the backup-timer Runnable can route TimeoutFallback through the same listener path. */
    private var lastListener: ((WalkCalState) -> Unit)? = null

    /** Active fade animator; nullable so terminal states can cancel mid-fade-in. */
    private var currentFadeAnimator: android.view.ViewPropertyAnimator? = null

    /** Walk-dot pulse animator; created on enterOnset, cancelled on TERMINAL / destroy. */
    private var walkDotPulseAnimator: ObjectAnimator? = null

    /** True while the container is shown or fading in. Guards against duplicate show / stale Progress. */
    private var isVisible: Boolean = false

    private var internalState: InternalState = InternalState.HIDDEN
    private var currentGpsQuality: GpsQuality = GpsQuality.GOOD

    /**
     * Tracks first [updateGpsQuality] application — prevents the initial GOOD==GOOD early-return
     * from skipping visual setup on the first call. Reset on [destroy].
     */
    private var gpsQualityApplied: Boolean = false

    // Resolved child view references (cached at construction)
    private val tvTitle: TextView = f3Container.findViewById(R.id.tvF3Title)
    private val tvSubtitle: TextView = f3Container.findViewById(R.id.tvF3Subtitle)
    private val tvGpsQuality: TextView = f3Container.findViewById(R.id.tvF3GpsQuality)

    // SCRUM-107 Stage 2: new view references. dotCompass/dotGps stay as bg_step_dot_done
    // throughout F3's lifetime (they were "done" by the time F3 shows); only dotWalk is
    // mutated by Stage 3. They remain declared so the XML refactor stays validated at
    // construction time.
    @Suppress("unused") private val dotCompass: View = f3Container.findViewById(R.id.dotF3Compass)
    @Suppress("unused") private val dotGps: View = f3Container.findViewById(R.id.dotF3Gps)
    private val dotWalk: View = f3Container.findViewById(R.id.dotF3Walk)
    private val progressSpinner: ProgressBar = f3Container.findViewById(R.id.progressF3TitleSpinner)
    private val ivTerminalCheck: ImageView = f3Container.findViewById(R.id.ivF3TerminalCheck)

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
            is WalkCalState.WaitingForWalk -> onWaitingForWalk()
            is WalkCalState.Progress -> onProgress(state)
            is WalkCalState.ShortcutSuccess -> onTerminalSuccess(logTag = "shortcut")
            is WalkCalState.Completed -> onTerminalSuccess(logTag = "completed")
            is WalkCalState.TimeoutFallback -> onTerminalFailure(logTag = "timeout")
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
     * SCRUM-107 Stage 3 — GPS quality update channel.
     *
     * - Classifies via [GpsQualityClassifier] (5m / 10m thresholds shared with F4 GPS ring).
     * - Updates the pill (text + background drawable + text color + padding).
     * - Refreshes subtitle because subtitle precedence is Poor > Fair > state-driven.
     * - Idempotent: equal-quality calls after the first no-op.
     *
     * Public so Stage 4's Activity-level GPS listener can drive the pill even before any
     * Progress event fires, and so Progress events themselves can route through here.
     */
    fun updateGpsQuality(accuracyM: Float) {
        val newQuality = GpsQualityClassifier.classify(accuracyM)
        if (gpsQualityApplied && newQuality == currentGpsQuality) return
        gpsQualityApplied = true
        currentGpsQuality = newQuality
        when (newQuality) {
            GpsQuality.GOOD -> {
                tvGpsQuality.setText(R.string.f3_gps_quality_good)
                tvGpsQuality.setBackgroundResource(R.drawable.bg_pill_good)
                tvGpsQuality.setTextColor(ContextCompat.getColor(activity, R.color.status_success))
            }
            GpsQuality.FAIR -> {
                tvGpsQuality.setText(R.string.f3_gps_quality_fair)
                tvGpsQuality.setBackgroundResource(R.drawable.bg_pill_fair)
                tvGpsQuality.setTextColor(ContextCompat.getColor(activity, R.color.status_warning))
            }
            GpsQuality.POOR -> {
                tvGpsQuality.setText(R.string.f3_gps_quality_poor_short)
                tvGpsQuality.setBackgroundResource(R.drawable.bg_pill_poor)
                tvGpsQuality.setTextColor(ContextCompat.getColor(activity, R.color.orientar_primary))
            }
        }
        // Pill padding (8dp horizontal, 3dp vertical) so the text isn't flush against
        // the rounded edges. Note: because tvF3GpsQuality is layout_width=0dp +
        // layout_weight=1 (Hot Fix #6), the background drawable will span the full
        // weighted width with text right-aligned inside; the pill aesthetic is
        // bounded-on-the-right rather than wrap-around-text. A wrap-around-text pill
        // requires a small XML refactor (e.g., wrapping tvF3GpsQuality in a FrameLayout
        // with layout_gravity=end) — out of Stage 3 scope.
        val density = activity.resources.displayMetrics.density
        val hPx = (8f * density).toInt()
        val vPx = (3f * density).toInt()
        tvGpsQuality.setPadding(hPx, vPx, hPx, vPx)
        refreshSubtitle()
    }

    /**
     * SCRUM-107 polish: lets the Activity skip redundant in-app notifications when F3 is on screen.
     * Returns true while F3 is showing any state (ONSET / WAITING_FOR_WALK / WALK_ACTIVE / TERMINAL,
     * including the fade-in window after [showNow] is called); returns false when F3 is HIDDEN
     * (pre-show window or post-fade-out). The internal `isVisible` field is set inside [showNow]
     * before the fade-in animation begins and cleared inside [startFadeOut]'s onAnimationEnd.
     */
    fun isVisible(): Boolean = isVisible

    /**
     * Cancel all pending Handler callbacks + animator. View itself is owned by the activity
     * layout and destroyed automatically when the activity dies — no view removal needed.
     * Call from `Activity.onDestroy()`.
     */
    fun destroy() {
        cancelAllTimers()
        stopWalkDotPulse()
        currentFadeAnimator?.cancel()
        currentFadeAnimator = null
        lastListener = null
        // Defensive: ensure view ends in hidden state even if Activity is reused (config change)
        f3Container.visibility = View.GONE
        isVisible = false
        internalState = InternalState.HIDDEN
        currentGpsQuality = GpsQuality.GOOD
        gpsQualityApplied = false
        tvSubtitle.alpha = 1f
        // SCRUM-107 Step 2C Hot Fix #7: restore nav UI on destroy in case F3 was visible
        // when activity died (e.g., user backed out mid-calibration). Harmless if already VISIBLE.
        navUIViews.forEach { it.visibility = View.VISIBLE }
        android.util.Log.d("AR_LIFECYCLE", "F3CalibrationOverlay cleaned up")
    }

    // ====================================================================
    // INTERNAL — state handlers
    // ====================================================================

    /** Schedule show after 250 ms; arm 35 s backup timeout. Show enters ONSET on completion. */
    private fun onWaiting() {
        // Cancel any prior pending show (defensive — should be no-op in typical flow)
        cancelAllTimers()

        // SCRUM-107 Step 2C Hot Fix #8: hide nav UI IMMEDIATELY (before the 250ms show
        // delay) to prevent a flash of route header / Recalibrate / EndNav / compass HUD
        // between F2 fade-out completion and F3 fade-in start. Previously this lived in
        // showNow() which only fires after the 250ms delay — too late.
        //
        // Shortcut edge case: if ShortcutSuccess fires within 33ms (~2 frames at 60fps)
        // before F3 shows, onTerminalSuccess's early-return bails (isVisible=false) and
        // nav UI is never re-shown via F3 fade-out — but Activity-level state updates
        // restore it naturally.
        navUIViews.forEach { it.visibility = View.GONE }

        pendingShowRunnable = Runnable {
            showNow()
            enterOnset()
        }
        handler.postDelayed(pendingShowRunnable!!, SHOW_DELAY_MS)

        backupTimeoutRunnable = Runnable {
            FileLogger.w("WALK_CAL", "F3 backup timer fired (AR frames may have stopped)")
            lastListener?.invoke(WalkCalState.TimeoutFallback)
        }
        handler.postDelayed(backupTimeoutRunnable!!, BACKUP_TIMEOUT_MS)
    }

    /**
     * Enter ONSET state: F3 has just been shown. Title "Calibrating route", subtitle from
     * [computeSubtitle], spinner visible, walk-dot pulsing, check icon hidden.
     *
     * Only transitions from HIDDEN — re-entries from any later state no-op (no regression).
     */
    private fun enterOnset() {
        if (internalState != InternalState.HIDDEN) return
        internalState = InternalState.ONSET
        tvTitle.setText(R.string.f3_title_calibrating_route)
        progressSpinner.visibility = View.VISIBLE
        ivTerminalCheck.visibility = View.GONE
        dotWalk.setBackgroundResource(R.drawable.bg_step_dot_active)
        tvSubtitle.alpha = 1f  // defensive reset in case prior session left it at 0
        refreshSubtitle()
        startWalkDotPulse()
        FileLogger.d("WALK_CAL", "F3 enter ONSET")
    }

    /**
     * Enter WAITING_FOR_WALK state: compass disagreement throttled-confirmed (audit Q1).
     * Title stays "Calibrating route"; subtitle refreshes to nudge the user to walk.
     *
     * Idempotent — fires every ~1 s while compass disagrees; only first call does work.
     * Does not regress from TERMINAL or WALK_ACTIVE.
     */
    private fun onWaitingForWalk() {
        if (!isVisible) return
        if (internalState == InternalState.WAITING_FOR_WALK) return
        if (internalState == InternalState.TERMINAL) return
        if (internalState == InternalState.WALK_ACTIVE) return
        internalState = InternalState.WAITING_FOR_WALK
        refreshSubtitle()
        FileLogger.d("WALK_CAL", "F3 enter WAITING_FOR_WALK")
    }

    /**
     * Per-sample Progress handler.
     * - Updates GPS quality pill + subtitle via [updateGpsQuality]
     * - Transitions to WALK_ACTIVE once gpsDistanceM crosses [WALK_ACTIVE_THRESHOLD_M] (audit R2)
     */
    private fun onProgress(p: WalkCalState.Progress) {
        if (!isVisible || internalState == InternalState.TERMINAL) return

        // SCRUM-107 Step 2C Hot Fix #9 (preserved): defensive — keep nav UI hidden while
        // F3 is visible. Progress fires every ~1-3 s; catches any unexpected VISIBLE override.
        navUIViews.forEach { it.visibility = View.GONE }

        updateGpsQuality(p.gpsAccuracyM)

        if (p.gpsDistanceM > WALK_ACTIVE_THRESHOLD_M && internalState != InternalState.WALK_ACTIVE) {
            internalState = InternalState.WALK_ACTIVE
            tvTitle.setText(R.string.f3_title_walk_forward)
            refreshSubtitle()
            FileLogger.d("WALK_CAL", "F3 enter WALK_ACTIVE (walked=${p.gpsDistanceM}m)")
        }
    }

    /**
     * Success terminal path ([WalkCalState.ShortcutSuccess] / [WalkCalState.Completed]).
     *
     * If F3 never appeared (early shortcut within the 250 ms show delay), cancel timers
     * and bail without showing terminal — user sees only the positive notification
     * (audit Risk R3 Option A).
     *
     * Otherwise enter TERMINAL: title "Calibrated", spinner hidden, check icon shown,
     * walk dot swapped to green check, pulse stopped, subtitle hidden via alpha (preserves
     * layout height). Hold for [TERMINAL_MIN_DURATION_MS], then start fade-out.
     */
    private fun onTerminalSuccess(logTag: String) {
        cancelAllTimers()
        if (!isVisible) return
        if (internalState == InternalState.TERMINAL) return  // idempotent

        internalState = InternalState.TERMINAL
        tvTitle.setText(R.string.f3_title_calibrated)
        tvSubtitle.alpha = 0f  // hide without layout shift; text intentionally not cleared
        progressSpinner.visibility = View.GONE
        ivTerminalCheck.visibility = View.VISIBLE
        dotWalk.setBackgroundResource(R.drawable.bg_step_dot_check)
        stopWalkDotPulse()
        FileLogger.d("WALK_CAL", "F3 enter TERMINAL ($logTag)")

        terminalDelayRunnable = Runnable { startFadeOut(logTag) }
        handler.postDelayed(terminalDelayRunnable!!, TERMINAL_MIN_DURATION_MS)
    }

    /**
     * Failure terminal path ([WalkCalState.TimeoutFallback]). Skip the visual terminal
     * state entirely; fade out immediately. The fallback notification handles user feedback.
     */
    private fun onTerminalFailure(logTag: String) {
        cancelAllTimers()
        if (!isVisible) return
        startFadeOut(logTag)
    }

    /** Shared fade-out animation used by both success (post-delay) and failure paths. */
    private fun startFadeOut(logTag: String) {
        currentFadeAnimator?.cancel()
        currentFadeAnimator = f3Container.animate()
            .alpha(0f)
            .setDuration(FADE_OUT_MS)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    f3Container.visibility = View.GONE
                    isVisible = false
                    internalState = InternalState.HIDDEN
                    currentFadeAnimator = null
                    tvSubtitle.alpha = 1f  // reset for next session
                    stopWalkDotPulse()  // defensive in case failure path skipped TERMINAL
                    // SCRUM-107 Step 2C Hot Fix #7: restore navigation UI after F3 fully fades.
                    // Restoring AFTER fade-out completes (not before) prevents a visual flash
                    // of overlapping F3 + nav UI during the 200ms fade.
                    navUIViews.forEach { it.visibility = View.VISIBLE }
                }
            })
        currentFadeAnimator?.start()
        FileLogger.d("WALK_CAL", "F3 fade-out ($logTag)")
    }

    // ====================================================================
    // INTERNAL — subtitle composition
    // ====================================================================

    /**
     * Subtitle precedence: Poor GPS > Fair GPS > state-driven copy.
     * TERMINAL / HIDDEN intentionally return empty — TERMINAL also sets alpha=0 to keep
     * the layout height stable while hiding the text.
     */
    private fun computeSubtitle(state: InternalState, quality: GpsQuality): String = when (quality) {
        GpsQuality.POOR -> activity.getString(R.string.f3_subtitle_poor_gps_v2)
        GpsQuality.FAIR -> activity.getString(R.string.f3_subtitle_fair_gps)
        GpsQuality.GOOD -> when (state) {
            InternalState.ONSET -> activity.getString(R.string.f3_subtitle_onset)
            InternalState.WAITING_FOR_WALK -> activity.getString(R.string.f3_subtitle_waiting_for_walk)
            InternalState.WALK_ACTIVE -> activity.getString(R.string.f3_subtitle_walk_active)
            InternalState.TERMINAL, InternalState.HIDDEN -> ""
        }
    }

    private fun refreshSubtitle() {
        tvSubtitle.text = computeSubtitle(internalState, currentGpsQuality)
    }

    // ====================================================================
    // INTERNAL — walk-dot pulse animator
    // ====================================================================

    private fun startWalkDotPulse() {
        if (walkDotPulseAnimator?.isRunning == true) return
        walkDotPulseAnimator = ObjectAnimator.ofPropertyValuesHolder(
            dotWalk,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.15f, 1.0f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.15f, 1.0f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 1.0f, 0.85f, 1.0f)
        ).apply {
            duration = PULSE_DURATION_MS
            repeatCount = ValueAnimator.INFINITE
            // 3-keyframe (1.0 → 1.15 → 1.0) makes one full breathing cycle per duration
            // without needing repeatMode=REVERSE.
            start()
        }
    }

    private fun stopWalkDotPulse() {
        walkDotPulseAnimator?.cancel()
        walkDotPulseAnimator = null
        // Reset transform / alpha so the dot renders cleanly when re-shown next session.
        dotWalk.scaleX = 1.0f
        dotWalk.scaleY = 1.0f
        dotWalk.alpha = 1.0f
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
        FileLogger.d("WALK_CAL", "F3 show")

        // Hot Fix #7 diagnostic — confirm upper-center positioning + measurement
        f3Container.post {
            FileLogger.d(
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
        terminalDelayRunnable?.let { handler.removeCallbacks(it) }
        terminalDelayRunnable = null
    }
}
