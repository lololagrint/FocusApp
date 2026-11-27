package fr.focuscontrol.monitor

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import fr.focuscontrol.BlockActivity
import fr.focuscontrol.PolicyStore
import fr.focuscontrol.BlockState

/**
 * Event-driven foreground app observer. No polling loop or permanent wake lock.
 * Android/Samsung may still suspend or kill accessibility services; see README.
 */
class AppMonitorService : AccessibilityService() {
    private var foregroundPackage: String? = null
    private var foregroundSince = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui") return
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        val previous = foregroundPackage
        if (previous != null && previous != pkg && foregroundSince > 0) {
            account(previous, nowElapsed - foregroundSince)
        }
        if (previous != pkg) {
            foregroundPackage = pkg
            foregroundSince = nowElapsed
        }
        enforce(pkg)
    }

    private fun account(pkg: String, duration: Long) {
        if (PolicyStore.policy(this, pkg) == null) return
        val now = System.currentTimeMillis()
        val state = PolicyStore.addUsage(this, pkg, duration, now)
        val policy = PolicyStore.policy(this, pkg) ?: return
        if (state.usedMs >= policy.dailyMinutes * 60_000L) {
            val daily = state.copy(reason = "Limite journalière", cooldownUntilWall = 0)
            PolicyStore.saveState(this, pkg, daily)
        } else if (duration > 0 && duration >= policy.sessionMinutes * 60_000L) {
            PolicyStore.startCooldown(this, pkg, "Limite de session", now)
        }
    }

    private fun enforce(pkg: String) {
        val policy = PolicyStore.policy(this, pkg) ?: return
        val now = System.currentTimeMillis()
        var state = PolicyStore.clearCooldownIfExpired(this, pkg, now)
        if (state.usedMs >= policy.dailyMinutes * 60_000L) {
            state = state.copy(reason = "Limite journalière", cooldownUntilWall = 0)
            PolicyStore.saveState(this, pkg, state)
        } else if (state.cooldownUntilWall > now) {
            state = state.copy(reason = if (state.reason.isBlank()) "Limite de session" else state.reason)
        } else if (state.reason == "Limite journalière") {
            // Daily block persists until the rolling 24-hour cycle expires.
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
            if (foregroundSince > 0) account(pkg, android.os.SystemClock.elapsedRealtime() - foregroundSince)
        }
        foregroundPackage = null
        foregroundSince = 0
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
    }
}
