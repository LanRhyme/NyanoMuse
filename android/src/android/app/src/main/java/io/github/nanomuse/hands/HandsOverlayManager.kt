package io.github.nanomuse.hands

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.openminis.app.MinisApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HandsOverlayManager — manages the floating HandsCapsule and HandsStage
 * when AI controls the phone via simulated touch or accessibility gestures outside the app.
 */
object HandsOverlayManager {
    private const val AIM_MS = 240L
    private const val AUTO_DISMISS_DELAY_MS = 3500L

    private val main = Handler(Looper.getMainLooper())
    private var capsule: HandsCapsule? = null
    val stopRequested = AtomicBoolean(false)
    private var isObserverInitialized = false

    private val autoDismissRunnable = Runnable {
        dismiss()
    }

    fun isOverlayAllowed(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    fun isAppBackgrounded(context: Context): Boolean {
        val app = context.applicationContext as? MinisApp ?: return false
        return !app.isAppForeground()
    }

    /**
     * Observes app foreground flow to automatically dismiss the capsule when user returns to nanoMuse.
     */
    fun initAppObserver(app: MinisApp, scope: CoroutineScope) {
        if (isObserverInitialized) return
        isObserverInitialized = true
        scope.launch {
            app.isAppForegroundFlow.collect { isFg ->
                if (isFg) {
                    dismiss()
                }
            }
        }
    }

    @Synchronized
    private fun getOrCreateCapsule(context: Context): HandsCapsule? {
        if (!isOverlayAllowed(context)) return null
        // If HandsOperator is actively running its own run, do not interfere
        if (Hands.active.value) return null

        if (capsule == null) {
            val c = HandsCapsule(context.applicationContext)
            c.onStop = {
                stopRequested.set(true)
                c.bringAppToFront(null)
                dismiss()
            }
            c.onOpenApp = {
                c.bringAppToFront(null)
                dismiss()
            }
            capsule = c
        }
        return capsule
    }

    /**
     * Shows or updates the floating capsule with current action label.
     */
    fun show(context: Context, actionLabel: String, step: Int = 1) {
        if (!isAppBackgrounded(context)) return
        stopRequested.set(false)
        main.removeCallbacks(autoDismissRunnable)
        val c = getOrCreateCapsule(context) ?: return
        c.working(step, actionLabel)
    }

    /**
     * Executes tap with aim indicator, dodge, and touch pass-through on the capsule stage.
     */
    fun <T> withAimAndTap(context: Context, x: Int, y: Int, label: String, gesture: () -> T): T {
        val bg = isAppBackgrounded(context)
        val c = if (bg) getOrCreateCapsule(context) else null
        if (c != null) {
            stopRequested.set(false)
            main.removeCallbacks(autoDismissRunnable)
            c.working(1, label)
            c.dodge(x, y)
            c.stage.aim(x, y, label)
            try {
                Thread.sleep(AIM_MS)
            } catch (_: InterruptedException) {}
            val result = c.passThrough(gesture)
            scheduleAutoDismiss()
            return result
        } else {
            return gesture()
        }
    }

    /**
     * Executes swipe with sweep indicator, dodge, and touch pass-through on the capsule stage.
     */
    fun <T> withSweepAndSwipe(context: Context, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long, label: String, gesture: () -> T): T {
        val bg = isAppBackgrounded(context)
        val c = if (bg) getOrCreateCapsule(context) else null
        if (c != null) {
            stopRequested.set(false)
            main.removeCallbacks(autoDismissRunnable)
            c.working(1, label)
            c.dodge(x1, y1)
            c.stage.sweep(x1, y1, x2, y2, AIM_MS, durationMs, label)
            try {
                Thread.sleep(AIM_MS)
            } catch (_: InterruptedException) {}
            val result = c.passThrough(gesture)
            scheduleAutoDismiss()
            return result
        } else {
            return gesture()
        }
    }

    fun scheduleAutoDismiss(delayMs: Long = AUTO_DISMISS_DELAY_MS) {
        main.removeCallbacks(autoDismissRunnable)
        main.postDelayed(autoDismissRunnable, delayMs)
    }

    fun dismiss() {
        main.removeCallbacks(autoDismissRunnable)
        capsule?.hide()
        capsule = null
    }
}
