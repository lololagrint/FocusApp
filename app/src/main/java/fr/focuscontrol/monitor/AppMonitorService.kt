package fr.focuscontrol.monitor

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
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
    private var reminderTask: Runnable? = null
    private val channelId = "focus_reminders"

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui") return

        val nowElapsed = SystemClock.elapsedRealtime()
        val previous = foregroundPackage
        if (previous != null && previous != pkg && foregroundSince > 0) {
            cancelScheduledTasks()
            account(previous, nowElapsed - foregroundSince)
        }
        if (previous != pkg) {
            foregroundPackage = pkg
            foregroundSince = nowElapsed
        }
        enforce(pkg)
        if (previous != pkg && foregroundPackage == pkg) notifyTimeRemaining(pkg)
        if (foregroundPackage == pkg) {
            scheduleNextThreshold(pkg)
            scheduleReminder(pkg)
        }
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
        thresholdTask?.let(handler::removeCallbacks)
        thresholdTask = null
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

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(
                channelId, "Rappels Focus Control", NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Rappels avant le blocage d'une application surveillée" })
        }
    }

    private fun remainingBeforeBlock(pkg: String): Long? {
        val policy = PolicyStore.policy(this, pkg) ?: return null
        val now = System.currentTimeMillis()
        val state = PolicyStore.clearCooldownIfExpired(this, pkg, now)
        if (state.reason.isNotBlank()) return null
        val elapsed = (SystemClock.elapsedRealtime() - foregroundSince).coerceAtLeast(0L)
        val limits = mutableListOf<Long>()
        if (policy.sessionEnabled) limits += policy.sessionMinutes * 60_000L - elapsed
        if (policy.dailyEnabled) limits += policy.dailyMinutes * 60_000L - state.usedMs - elapsed
        return limits.filter { it > 0L }.minOrNull()
    }

    private fun notifyTimeRemaining(pkg: String) {
        val remaining = remainingBeforeBlock(pkg) ?: return
        ensureNotificationChannel()
        val minutes = (remaining + 59_999L) / 60_000L
        val label = PolicyStore.policy(this, pkg)?.label ?: pkg
        val text = if (minutes <= 1L) "Blocage dans moins d'une minute" else "Blocage dans environ $minutes minutes"
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Focus Control • $label")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text. Le temps restant est calculé selon vos limites actives."))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(false)
            .setAutoCancel(false)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(pkg.hashCode(), notification) }
    }

    private fun scheduleReminder(pkg: String) {
        reminderTask?.let(handler::removeCallbacks)
        reminderTask = null
        val remaining = remainingBeforeBlock(pkg) ?: return
        val interval = if (remaining <= 5L * 60_000L) 60_000L else 10L * 60_000L
        val delay = minOf(interval, remaining)
        val task = Runnable {
            if (foregroundPackage == pkg && foregroundSince > 0) {
                notifyTimeRemaining(pkg)
                scheduleReminder(pkg)
            }
        }
        reminderTask = task
        handler.postDelayed(task, delay.coerceAtLeast(1L))
    }

    private fun cancelScheduledTasks() {
        thresholdTask?.let(handler::removeCallbacks)
        reminderTask?.let(handler::removeCallbacks)
        thresholdTask = null
        reminderTask = null
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
        cancelScheduledTasks()
        foregroundPackage = null
        foregroundSince = 0
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
    }

    override fun onDestroy() {
        cancelScheduledTasks()
        super.onDestroy()
    }
}
