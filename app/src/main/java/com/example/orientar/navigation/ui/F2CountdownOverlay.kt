package com.example.orientar.navigation.ui

import android.animation.ObjectAnimator
import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.orientar.R

/**
 * F2CountdownOverlay — full-screen 3 → 2 → 1 countdown shown before compass
 * calibration starts and again before AR walk guidance begins.
 *
 * Mirrors [NotificationManager] in shape and lifecycle hygiene:
 * - Same `(Activity, rootView)` constructor pattern
 * - Same WindowInsets-aware positioning (full-screen MATCH_PARENT, so insets
 *   are applied to inner padding rather than outer margins)
 * - Same `dp()` helper, same try/catch destroy
 * - Same Handler-on-main-looper Runnable cancellation in [destroy]
 *
 * # Single-slot semantics
 * Only one overlay is visible at any time. Calling [show] while a countdown is
 * already in flight cancels the pending Handler ticks, removes the previous
 * view synchronously, and starts fresh — matches NotificationManager's
 * "newest wins" behavior.
 *
 * # Elevation
 * Set to `dp(5)` so the countdown floats above NotificationManager overlays
 * (which sit at `dp(4)`). During the brief 3-second countdown window, the
 * overlay should fully dominate the screen — notifications stay hidden behind.
 *
 * # Lifecycle
 * Caller MUST invoke [destroy] from `Activity.onDestroy()` to cancel any
 * pending Handler callbacks. SCRUM-121 hygiene pattern.
 *
 * # Example
 * ```
 * f2Overlay = F2CountdownOverlay(this, findViewById(android.R.id.content))
 * // ...
 * f2Overlay.show(F2CountdownOverlay.Mode.COMPASS_PRE) {
 *     startCompassCalibrationStep()
 * }
 * // ...
 * override fun onDestroy() {
 *     f2Overlay.destroy()
 *     super.onDestroy()
 * }
 * ```
 */
class F2CountdownOverlay(
    private val activity: Activity,
    private val rootView: ViewGroup
) {
    /**
     * Which pre-phase the countdown is announcing. Drives kicker label, hint
     * texts, ready-pill visibility, and icon animation.
     */
    enum class Mode {
        /** Pre-compass calibration. Plain "Hold phone flat" hint. */
        COMPASS_PRE,

        /** Pre-AR walk start. Adds the green "Compass + GPS ready" pill. */
        WALK_PRE
    }

    private val inflater = LayoutInflater.from(activity)
    private val handler = Handler(Looper.getMainLooper())

    /** Pending Handler callbacks — cleared on hide/destroy to prevent leaks. */
    private val pendingRunnables = mutableListOf<Runnable>()

    private var currentView: View? = null
    private var currentIconAnimator: ObjectAnimator? = null

    // ====================================================================
    // PUBLIC API
    // ====================================================================

    /**
     * Show a 3-second countdown (3 → 2 → 1) then fade out and invoke
     * [onComplete]. Calling [show] while already visible cancels the prior
     * countdown and starts a fresh one (no double-fire of onComplete).
     */
    fun show(mode: Mode, onComplete: () -> Unit) {
        // Cancel any in-flight countdown — single-slot semantics.
        cancelPending()
        currentView?.let { safeRemove(it); currentView = null }

        android.util.Log.d("F2_TIMING", "show() called — mode=${mode.name}, t=${System.currentTimeMillis()}")

        val view = inflater.inflate(R.layout.f2_countdown_overlay, rootView, false)

        // Bind copy by mode
        val kicker = view.findViewById<TextView>(R.id.tvF2Kicker)
        val hintTitle = view.findViewById<TextView>(R.id.tvF2HintTitle)
        val hintSub = view.findViewById<TextView>(R.id.tvF2HintSub)
        val readyPill = view.findViewById<LinearLayout>(R.id.layoutF2ReadyPill)
        val iconView = view.findViewById<ImageView>(R.id.ivF2Icon)
        val numberView = view.findViewById<TextView>(R.id.tvF2Number)

        when (mode) {
            Mode.COMPASS_PRE -> {
                kicker.text = activity.getString(R.string.cal_f2_compass_kicker)
                hintTitle.text = activity.getString(R.string.cal_f2_compass_hint_title)
                hintSub.text = activity.getString(R.string.cal_f2_compass_hint_sub)
                readyPill.visibility = View.GONE
                // Simple horizontal-tilt animation on phone icon (Option A v2 simplified)
                // TODO(SCRUM-107 Step 2C polish): replace with full Option A v2 circular-path animation
                currentIconAnimator = ObjectAnimator.ofFloat(iconView, "rotation", -8f, 8f).apply {
                    duration = 1400
                    repeatCount = ObjectAnimator.INFINITE
                    repeatMode = ObjectAnimator.REVERSE
                    start()
                }
            }
            Mode.WALK_PRE -> {
                kicker.text = activity.getString(R.string.cal_f2_walk_kicker)
                hintTitle.text = activity.getString(R.string.cal_f2_walk_hint_title)
                hintSub.text = activity.getString(R.string.cal_f2_walk_hint_sub)
                readyPill.visibility = View.VISIBLE
                // Apply rounded-pill background programmatically (green tinted glass)
                readyPill.background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(Color.parseColor("#214CAF50"))
                    setStroke(dp(1), Color.parseColor("#524CAF50"))
                }
                // Subtle Y-axis rotation on phone for "upright wobble" hint
                // TODO(SCRUM-107 Step 2C polish): refine to ±12° with bounce + upward arrow indicator
                currentIconAnimator = ObjectAnimator.ofFloat(iconView, "rotationY", -12f, 12f).apply {
                    duration = 2400
                    repeatCount = ObjectAnimator.INFINITE
                    repeatMode = ObjectAnimator.REVERSE
                    start()
                }
            }
        }

        // Full-screen layout params, MATCH_PARENT × MATCH_PARENT
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        view.layoutParams = params
        view.alpha = 0f
        view.elevation = dp(5).toFloat()  // above NotificationManager (dp(4))

        rootView.addView(view)
        currentView = view

        // Apply WindowInsets for safe-area awareness — inner LinearLayout padding
        // is already 24dp so we just need to make sure status/nav bars don't clip
        // the centered content. Insets are applied as inner padding on the root.
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(view)

        // Fade in
        view.animate().alpha(1f).setDuration(300).start()

        // Start the countdown ticks: 3 (now) → 2 (+1s) → 1 (+2s) → fade-out (+3s)
        showTick(numberView, "3")

        val r2 = Runnable {
            showTick(numberView, "2")
            android.util.Log.d("F2_TIMING", "tick — secondsLeft=2, t=${System.currentTimeMillis()}")
        }
        val r1 = Runnable {
            showTick(numberView, "1")
            android.util.Log.d("F2_TIMING", "tick — secondsLeft=1, t=${System.currentTimeMillis()}")
        }
        val rDone = Runnable {
            // Fade out then invoke onComplete
            currentIconAnimator?.cancel()
            currentIconAnimator = null
            view.animate().alpha(0f).setDuration(200).withEndAction {
                safeRemove(view)
                if (currentView === view) currentView = null
                android.util.Log.d("F2_TIMING", "onComplete fired — mode=${mode.name}, t=${System.currentTimeMillis()}")
                onComplete()
            }.start()
        }
        pendingRunnables.add(r2)
        pendingRunnables.add(r1)
        pendingRunnables.add(rDone)

        handler.postDelayed(r2, 1000L)
        handler.postDelayed(r1, 2000L)
        handler.postDelayed(rDone, 3000L)
    }

    /**
     * Immediately hide the overlay (no callback). Used for emergency cancel
     * scenarios where the caller has decided to abandon the countdown.
     */
    fun hide() {
        cancelPending()
        currentIconAnimator?.cancel()
        currentIconAnimator = null
        val view = currentView ?: return
        currentView = null
        view.animate().alpha(0f).setDuration(200).withEndAction { safeRemove(view) }.start()
    }

    /**
     * Cancel all pending Handler callbacks and remove any visible overlay
     * synchronously. Call from `Activity.onDestroy()`.
     */
    fun destroy() {
        cancelPending()
        handler.removeCallbacksAndMessages(null)
        currentIconAnimator?.cancel()
        currentIconAnimator = null
        currentView?.let { safeRemove(it) }
        currentView = null
    }

    // ====================================================================
    // INTERNAL HELPERS
    // ====================================================================

    /** Update the big number with a brief scale-bounce per tick. */
    private fun showTick(numberView: TextView, text: String) {
        numberView.text = text
        numberView.scaleX = 0.6f
        numberView.scaleY = 0.6f
        numberView.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(150)
            .setInterpolator(OvershootInterpolator(2f))
            .start()
    }

    private fun cancelPending() {
        pendingRunnables.forEach { handler.removeCallbacks(it) }
        pendingRunnables.clear()
    }

    private fun safeRemove(view: View) {
        try {
            (view.parent as? ViewGroup)?.removeView(view)
        } catch (_: Throwable) {
            // View already detached or removeView raced with another teardown — ignore.
        }
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
