package com.example.orientar.navigation.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.orientar.R

/**
 * NotificationManager — single-slot, in-app notification system for AR Navigation.
 *
 * Designed for SCRUM-107 F4+F3+F2 + Debug Panel notification unification. Replaces
 * scattered Toast / tvRecalculating banner usage with a consistent visual system
 * built around the existing glass-card pattern and SCRUM-107 visual tokens
 * (orientar_primary, status_warning, status_success, calibration_idle).
 *
 * # Single-slot semantics
 * One notification is visible at any time. Any new `show*()` call replaces the
 * current notification: the previous view fades out (200 ms), the new view
 * fades in (300 ms). No queue, no overlap. Callers that need sequenced
 * announcements should orchestrate them themselves (e.g., chain via the
 * Success auto-dismiss callback or just call `show*()` again at the right
 * moment).
 *
 * # Lifecycle hygiene (SCRUM-121 lessons)
 * Auto-dismiss timing uses `Handler(Looper.getMainLooper())`. This class
 * stores ALL pending Handler callbacks and CANCELS them in `destroy()`. The
 * caller MUST invoke `destroy()` from `Activity.onDestroy()` to avoid
 * leaked callbacks firing on a torn-down view hierarchy. This pattern is
 * borrowed from the SCRUM-121 anchor-cleanup hygiene work — historically
 * the codebase has no handler-cancellation on lifecycle; this class
 * introduces it for the notification subsystem.
 *
 * # rootView requirements
 * `rootView` SHOULD be a [FrameLayout] (or a ViewGroup whose LayoutParams
 * honor gravity from `FrameLayout.LayoutParams`). The activity's root
 * `android.R.id.content` is always a FrameLayout and is the recommended
 * value to pass. Other ViewGroup subclasses will display the notification
 * but may ignore the gravity-based positioning.
 *
 * # Variants
 * | API | Position | Auto-dismiss | Actions |
 * |---|---|---|---|
 * | [showInstruction] | top | no | n/a |
 * | [showSuccess]     | bottom | yes (default 2.5 s) | n/a |
 * | [showWarning]     | top | no | optional dismiss × |
 * | [showError]       | center | no | optional 1 or 2 buttons |
 *
 * # Example (Step 2 will use this pattern)
 * ```
 * notifications = NotificationManager(this, findViewById(android.R.id.content))
 * // ...
 * notifications.showInstruction("Hold phone upright", "Walk forward to calibrate")
 * notifications.showSuccess("Heading calibrated")
 * notifications.showWarning("GPS weak", "Move to open sky")
 * notifications.showError(
 *     title = "ARCore not available",
 *     description = "Please install Google Play Services for AR",
 *     action = ErrorAction.Single("Exit") { finish() }
 * )
 * // ...
 * override fun onDestroy() {
 *     notifications.destroy()
 *     super.onDestroy()
 * }
 * ```
 */
class NotificationManager(
    private val activity: Activity,
    private val rootView: ViewGroup
) {
    /**
     * Action payload for [showError]. Buttons render end-aligned. Each button's
     * `onClick` lambda fires THEN the notification auto-hides; callers should
     * NOT call [hide] manually inside `onClick`.
     */
    sealed class ErrorAction {
        /** Single action button. Renders as a filled red primary button. */
        data class Single(val label: String, val onClick: () -> Unit) : ErrorAction()

        /**
         * Two action buttons side-by-side. Secondary renders on the left
         * (outlined), primary on the right (filled red). Convention: primary
         * is the destructive / forward action (e.g., "Exit"), secondary is
         * the recovery action (e.g., "Retry").
         */
        data class Two(
            val primary: Single,
            val secondary: Single
        ) : ErrorAction()
    }

    private val inflater = LayoutInflater.from(activity)
    private val dismissHandler = Handler(Looper.getMainLooper())
    private var currentView: View? = null
    private var pendingDismiss: Runnable? = null

    // ====================================================================
    // PUBLIC API
    // ====================================================================

    /**
     * Show an Instruction notification at the top of the screen. Persists
     * until replaced by another `show*()` call or [hide] is invoked.
     */
    fun showInstruction(title: String, description: String? = null) {
        val view = inflater.inflate(R.layout.notification_instruction, rootView, false)
        view.findViewById<TextView>(R.id.notification_title).text = title
        bindOptionalDescription(view, description)
        showView(view, gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL, marginPxTop = dp(12))
    }

    /**
     * Show a Success notification at the bottom of the screen. Auto-dismisses
     * after [autoDismissMs] milliseconds (default 2500 ms).
     */
    fun showSuccess(title: String, autoDismissMs: Long = 2500L) {
        val view = inflater.inflate(R.layout.notification_success, rootView, false)
        view.findViewById<TextView>(R.id.notification_title).text = title
        showView(view, gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, marginPxBottom = dp(12))
        scheduleAutoDismiss(autoDismissMs)
    }

    /**
     * Show a Warning notification at the top of the screen. Persists until
     * replaced or dismissed. When [dismissable] is true, a close (×) button
     * is shown; tapping it invokes [onDismiss] (if provided) then hides the
     * notification.
     */
    fun showWarning(
        title: String,
        description: String? = null,
        dismissable: Boolean = true,
        onDismiss: (() -> Unit)? = null
    ) {
        val view = inflater.inflate(R.layout.notification_warning, rootView, false)
        view.findViewById<TextView>(R.id.notification_title).text = title
        bindOptionalDescription(view, description)

        val dismissBtn = view.findViewById<ImageView>(R.id.notification_dismiss)
        if (dismissable) {
            dismissBtn.visibility = View.VISIBLE
            dismissBtn.setOnClickListener {
                onDismiss?.invoke()
                hide()
            }
        } else {
            dismissBtn.visibility = View.GONE
        }
        showView(view, gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL, marginPxTop = dp(12))
    }

    /**
     * Show an Error notification centered on the screen. Persists until
     * replaced or an action button is tapped (which calls the action's
     * `onClick` lambda then auto-hides the notification).
     */
    fun showError(
        title: String,
        description: String? = null,
        action: ErrorAction? = null
    ) {
        val view = inflater.inflate(R.layout.notification_error, rootView, false)
        view.findViewById<TextView>(R.id.notification_title).text = title
        bindOptionalDescription(view, description)

        val actionContainer = view.findViewById<LinearLayout>(R.id.notification_actions)
        when (action) {
            is ErrorAction.Single -> {
                addActionButton(actionContainer, action.label, primary = true, onClick = action.onClick)
                actionContainer.visibility = View.VISIBLE
            }
            is ErrorAction.Two -> {
                // Secondary on the LEFT, primary on the RIGHT — matches Android dialog convention.
                addActionButton(actionContainer, action.secondary.label, primary = false, onClick = action.secondary.onClick)
                addActionButton(actionContainer, action.primary.label, primary = true, onClick = action.primary.onClick)
                actionContainer.visibility = View.VISIBLE
            }
            null -> actionContainer.visibility = View.GONE
        }
        showView(view, gravity = Gravity.CENTER, marginPxTop = 0)
    }

    /**
     * Hide the currently-visible notification (if any) with a 200 ms fade
     * out. Cancels any pending auto-dismiss. No-op if no notification is
     * currently shown.
     */
    fun hide() {
        cancelAutoDismiss()
        val view = currentView ?: return
        currentView = null
        view.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction { safeRemove(view) }
            .start()
    }

    /**
     * Cancel all pending Handler callbacks and remove any visible
     * notification synchronously. Call from `Activity.onDestroy()`.
     */
    fun destroy() {
        cancelAutoDismiss()
        dismissHandler.removeCallbacksAndMessages(null)
        currentView?.let { safeRemove(it) }
        currentView = null
    }

    // ====================================================================
    // INTERNAL HELPERS
    // ====================================================================

    private fun bindOptionalDescription(view: View, description: String?) {
        val descView = view.findViewById<TextView>(R.id.notification_description)
            ?: return  // success layout omits the description view
        if (!description.isNullOrEmpty()) {
            descView.text = description
            descView.visibility = View.VISIBLE
        } else {
            descView.visibility = View.GONE
        }
    }

    private fun showView(
        view: View,
        gravity: Int,
        marginPxTop: Int = 0,
        marginPxBottom: Int = 0
    ) {
        cancelAutoDismiss()
        // Fade out previous notification (if any) and remove it.
        val previous = currentView
        if (previous != null) {
            previous.animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction { safeRemove(previous) }
                .start()
        }
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        params.gravity = gravity
        params.topMargin = marginPxTop
        params.bottomMargin = marginPxBottom
        params.marginStart = dp(16)
        params.marginEnd = dp(16)
        view.layoutParams = params
        view.alpha = 0f
        rootView.addView(view)
        view.animate().alpha(1f).setDuration(300).start()
        currentView = view
    }

    private fun scheduleAutoDismiss(delayMs: Long) {
        cancelAutoDismiss()
        val r = Runnable { hide() }
        pendingDismiss = r
        dismissHandler.postDelayed(r, delayMs)
    }

    private fun cancelAutoDismiss() {
        pendingDismiss?.let { dismissHandler.removeCallbacks(it) }
        pendingDismiss = null
    }

    private fun addActionButton(
        container: LinearLayout,
        label: String,
        primary: Boolean,
        onClick: () -> Unit
    ) {
        val btn = Button(activity).apply {
            text = label
            textSize = 14f
            isAllCaps = false
            setTextColor(0xFFFFFFFF.toInt())
            background = ContextCompat.getDrawable(
                activity,
                if (primary) R.drawable.bg_button_red else R.drawable.bg_button_outline
            )
            minWidth = dp(96)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener {
                onClick()
                hide()
            }
        }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.marginStart = if (container.childCount == 0) 0 else dp(8)
        container.addView(btn, params)
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
