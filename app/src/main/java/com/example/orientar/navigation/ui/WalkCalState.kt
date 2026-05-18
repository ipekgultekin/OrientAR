package com.example.orientar.navigation.ui

/**
 * Walk calibration state events fired by [com.example.orientar.navigation.ArNavigationActivity]
 * to subscribers (currently [F3CalibrationOverlay]).
 *
 * Listener-driven UI pattern — no polling, no isInitialized() gate. The activity decides when
 * each event fires based on the dual-delta state machine; F3 reacts via [F3CalibrationOverlay.handleState].
 *
 * SCRUM-107 Step 2C.
 */
sealed class WalkCalState {
    /** Calibration started; F3 should show after a brief 250 ms delay (cancellable on ShortcutSuccess). */
    object Waiting : WalkCalState()

    /** Compass disagreed long enough to confirm a walk is needed. F3 subtitle updates to proactive walk hint. Fires every ~1s while compass disagreement persists (handler must be idempotent). */
    object WaitingForWalk : WalkCalState()

    /**
     * Per-sample progress update. F3 updates live numbers + GPS-quality row.
     * Dynamic walk threshold = `max(3.0f, 0.5f * gpsAccuracyM)`.
     */
    data class Progress(
        val gpsDistanceM: Float,
        val arDistanceM: Float,
        val gpsAccuracyM: Float,
        val weight: Float
    ) : WalkCalState()

    /** Compass agreed with route (within 15°); instant alignment. F3 cancels pending show or hides immediately. */
    object ShortcutSuccess : WalkCalState()

    /** Dual-delta converged. F3 fades out (200 ms). */
    object Completed : WalkCalState()

    /** 30 s timeout fired (or F3's 35 s backup timer if AR frames stopped). F3 fades out, fallback notification fires. */
    object TimeoutFallback : WalkCalState()
}
