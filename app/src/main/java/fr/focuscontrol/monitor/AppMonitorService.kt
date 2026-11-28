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
    // Nouvel identifiant pour appliquer le niveau élevé même si l’ancien canal existe déjà.
    private val channelId = "focus_reminders_popup"

    companion object {
        // Expose uniquement le paquet actif et l'heure de début monotone pour afficher un compteur live sur l'accueil.
        @Volatile var activePackage: String? = null
            private set
        @Volatile var activeSinceElapsed: Long = 0L
            private set
    }

    // Réagit aux changements d'application au premier plan : comptabilise l'ancienne, puis surveille la nouvelle.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui") return

        val nowElapsed = SystemClock.elapsedRealtime()
        val previous = foregroundPackage
        if (previous != null && previous != pkg && foregroundSince > 0) {
            cancelScheduledTasks()
            NotificationManagerCompat.from(this).cancel(previous.hashCode())
            account(previous, nowElapsed - foregroundSince)
        }
        if (previous != pkg) {
            // Recharge la progression sauvegardée si l'application est rouverte avant expiration du délai.
            PolicyStore.resumeSession(this, pkg, System.currentTimeMillis())
            foregroundPackage = pkg
            foregroundSince = nowElapsed
            activePackage = pkg
            activeSinceElapsed = nowElapsed
        }
        enforce(pkg)
        if (previous != pkg && foregroundPackage == pkg) notifyTimeRemaining(pkg)
        if (foregroundPackage == pkg) {
            scheduleNextThreshold(pkg)
            if (previous != pkg || reminderTask == null) scheduleReminder(pkg)
        }
    }

    // Enregistre le temps passé dans une application et déclenche la restriction qui vient d'être atteinte.
    private fun account(pkg: String, duration: Long) {
        val policy = PolicyStore.policy(this, pkg) ?: return
        if (duration <= 0) return
        val now = System.currentTimeMillis()
        val state = PolicyStore.addUsage(this, pkg, duration, now)
        val updatedPolicy = PolicyStore.policy(this, pkg) ?: return
        if (updatedPolicy.dailyEnabled && state.usedMs >= updatedPolicy.dailyMinutes * 60_000L) {
            PolicyStore.saveState(this, pkg, state.copy(reason = "Limite journalière", cooldownUntilWall = 0))
        } else if (updatedPolicy.sessionEnabled && state.sessionUsedMs >= updatedPolicy.sessionMinutes * 60_000L) {
            PolicyStore.startCooldown(this, pkg, "Limite de session", now)
        }
    }

    // Programme un contrôle juste à l'échéance la plus proche (limite de session ou limite journalière).
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
            remaining += policy.sessionMinutes * 60_000L - state.sessionUsedMs - elapsed
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
                activeSinceElapsed = foregroundSince
                enforce(pkg)
                if (foregroundPackage == pkg) scheduleNextThreshold(pkg)
            }
        }
        thresholdTask = task
        handler.postDelayed(task, delay.coerceAtLeast(1L))
    }

    // Crée le canal Android utilisé pour afficher les rappels de Focus Control.
    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(
                channelId, "Rappels Focus Control (pop-up)", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Rappels avant le blocage d'une application surveillée" })
        }
    }

    // Calcule le temps restant avant la première des limites actuellement activées.
    private fun remainingBeforeBlock(pkg: String): Long? {
        val policy = PolicyStore.policy(this, pkg) ?: return null
        val now = System.currentTimeMillis()
        val state = PolicyStore.clearCooldownIfExpired(this, pkg, now)
        if (state.reason.isNotBlank()) return null
        val elapsed = (SystemClock.elapsedRealtime() - foregroundSince).coerceAtLeast(0L)
        val limits = mutableListOf<Long>()
        if (policy.sessionEnabled) limits += policy.sessionMinutes * 60_000L - state.sessionUsedMs - elapsed
        if (policy.dailyEnabled) limits += policy.dailyMinutes * 60_000L - state.usedMs - elapsed
        return limits.filter { it > 0L }.minOrNull()
    }

    // Affiche ou actualise la notification indiquant dans combien de temps l'application sera bloquée.
    private fun notifyTimeRemaining(pkg: String) {
        val remaining = remainingBeforeBlock(pkg) ?: return
        ensureNotificationChannel()
        val minutes = (remaining + 59_999L) / 60_000L
        val label = PolicyStore.policy(this, pkg)?.label ?: pkg
        val text = "Blocage dans ${minutes}min"
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Focus Control • $label")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text. Le temps restant est calculé selon vos limites actives."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setOnlyAlertOnce(false)
            .setAutoCancel(false)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(pkg.hashCode(), notification) }
    }

    // Organise les rappels : toutes les 5 minutes, puis chaque minute dans les 5 dernières minutes.
    private fun scheduleReminder(pkg: String) {
        reminderTask?.let(handler::removeCallbacks)
        reminderTask = null
        val remaining = remainingBeforeBlock(pkg) ?: return
        val fiveMinutes = 5L * 60_000L
        val delay = if (remaining <= fiveMinutes) {
            minOf(60_000L, remaining)
        } else {
            minOf(5L * 60_000L, remaining - fiveMinutes)
        }
        val task = Runnable {
            if (foregroundPackage == pkg && foregroundSince > 0) {
                reminderTask = null
                notifyTimeRemaining(pkg)
                scheduleReminder(pkg)
            }
        }
        reminderTask = task
        handler.postDelayed(task, delay.coerceAtLeast(1L))
    }

    // Annule les minuteries de contrôle et de rappel quand l'application suivie quitte le premier plan.
    private fun cancelScheduledTasks() {
        thresholdTask?.let(handler::removeCallbacks)
        reminderTask?.let(handler::removeCallbacks)
        thresholdTask = null
        reminderTask = null
    }

    // Vérifie l'état des restrictions et ouvre l'écran de blocage si une limite est dépassée.
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

    // Nettoie le suivi et sauvegarde le temps écoulé si Android interrompt le service.
    override fun onInterrupt() {
        foregroundPackage?.let { pkg ->
            if (foregroundSince > 0) account(pkg, SystemClock.elapsedRealtime() - foregroundSince)
        }
        cancelScheduledTasks()
        foregroundPackage = null
        foregroundSince = 0
        activePackage = null
        activeSinceElapsed = 0L
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
    }

    // Libère les rappels en attente lorsque le service est détruit.
    override fun onDestroy() {
        cancelScheduledTasks()
        activePackage = null
        activeSinceElapsed = 0L
        super.onDestroy()
    }
}
