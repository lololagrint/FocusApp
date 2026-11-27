package fr.focuscontrol.monitor

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import fr.focuscontrol.BlockActivity
import fr.focuscontrol.PolicyStore

/**
 * Event-driven foreground app observer. A one-shot timer is scheduled only while
 * a monitored app is in the foreground; no permanent polling loop or wake lock.
 */
class AppMonitorService : AccessibilityService() {
    private var foregroundPackage: String? = null
    private var foregroundSince = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var thresholdTask: Runnable? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui") return

        val nowElapsed = SystemClock.elapsedRealtime()
        val previous = foregroundPackage
        if (previous != null && previous != pkg && foregroundSince > 0) {
            handler.removeCallbacksAndMessages(null)
            account(previous, nowElapsed - foregroundSince)
        }
        if (previous != pkg) {
            foregroundPackage = pkg
            foregroundSince = nowElapsed
        }
        enforce(pkg)
        if (foregroundPackage == pkg) scheduleNextThreshold(pkg)
    }

    private fun account(pkg: String, duration: Long) {
        val policy = PolicyStore.policy(this, pkg) ?: return
        if (duration <= 0) return
        val now = System.currentTimeMillis()
        val state = PolicyStore.addUsage(this, pkg, duration, now)
        val updatedPolicy = PolicyStore.policy(this, pkg) ?: return
        if (updatedPolicy.dailyEnabled && state.usedMs >= updatedPolicy.dailyMinutes * 60_000L) {
            PolicyStore.saveState(this, pkg, state.copy(reason = "Limite journalière", cooldownUntilWall = 0))
        } else if (updatedPolicy.sessionEnabled && duration >= updatedPolicy.sessionMinutes * 60_000L) {
            PolicyStore.startCooldown(this, pkg, "Limite de session", now)
        }
    }

    private fun scheduleNextThreshold(pkg: String) {
        handler.removeCallbacksAndMessages(null)
        val policy = PolicyStore.policy(this, pkg) ?: return
        val nowWall = System.currentTimeMillis()
        val state = PolicyStore.clearCooldownIfExpired(this, pkg, nowWall)
        if (state.reason.isNotBlank()) {
            enforce(pkg)
            return
        }
        val elapsed = (SystemClock.elapsedRealtime() - foregroundSince).coerceAtLeast(0)
        val remaining = mutableListOf<Long>()
        if (policy.sessionEnabled) {
            remaining += policy.sessionMinutes * 60_000L - elapsed
        }
        if (policy.dailyEnabled) {
            remaining += policy.dailyMinutes * 60_000L - state.usedMs - elapsed
        }
        val delay = remaining.filter { it > 0 }.minOrNull() ?: return
        val task = Runnable {
            if (foregroundPackage == pkg && foregroundSince > 0) {
                val duration = SystemClock.elapsedRealtime() - foregroundSince
                account(pkg, duration)
                foregroundSince = SystemClock.elapsedRealtime()
                enforce(pkg)
                if (foregroundPackage == pkg) scheduleNextThreshold(pkg)
            }
        }
        thresholdTask = task
        handler.postDelayed(task, delay.coerceAtLeast(1L))
    }

    private fun enforce(pkg: String) {
        val policy = PolicyStore.policy(this, pkg) ?: return
        val now = System.currentTimeMillis()
        var state = PolicyStore.clearCooldownIfExpired(this, pkg, now)

        if (policy.dailyEnabled && state.usedMs >= policy.dailyMinutes * 60_000L) {
            state = state.copy(reason = "Limite journalière", cooldownUntilWall = 0)
            PolicyStore.saveState(this, pkg, state)
        } else if (!policy.dailyEnabled && state.reason == "Limite journalière") {
            state = state.copy(reason = "", cooldownUntilWall = 0)
            PolicyStore.saveState(this, pkg, state)
        }

        if (!policy.sessionEnabled && state.reason == "Limite de session") {
            state = state.copy(reason = "", cooldownUntilWall = 0)
            PolicyStore.saveState(this, pkg, state)
        } else if (state.reason == "Limite de session" && state.cooldownUntilWall <= now) {
            state = state.copy(reason = "", cooldownUntilWall = 0)
            PolicyStore.saveState(this, pkg, state)
        }

        if (state.reason.isNotBlank()) {
            val intent = Intent(this, BlockActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(BlockActivity.EXTRA_PACKAGE, pkg)
            }
            startActivity(intent)
        }
    }

    override fun onInterrupt() {
        foregroundPackage?.let { pkg ->
            if (foregroundSince > 0) account(pkg, SystemClock.elapsedRealtime() - foregroundSince)
        }
        handler.removeCallbacksAndMessages(null)
        foregroundPackage = null
        foregroundSince = 0
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
