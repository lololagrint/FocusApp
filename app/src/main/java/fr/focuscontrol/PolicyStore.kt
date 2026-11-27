package fr.focuscontrol

import android.content.Context
import org.json.JSONObject

// Configuration des restrictions appliquées à une application suivie.
data class AppPolicy(
    val packageName: String,
    val label: String,
    val dailyMinutes: Int = 60,
    val sessionMinutes: Int = 15,
    val cooldownMinutes: Int = 30,
    val dailyEnabled: Boolean = true,
    val sessionEnabled: Boolean = true
)

// État d'utilisation et de blocage conservé entre les ouvertures de l'application.
data class BlockState(
    val usedMs: Long,
    val reason: String,
    val cooldownUntilWall: Long,
    val cycleStartWall: Long
)

// Centralise la lecture et l'écriture locale des règles et des compteurs (SharedPreferences).
object PolicyStore {
    private const val PREFS = "focus_control"
    private const val POLICIES = "policies"
    private const val STATES = "states"
    private const val DAY_MS = 24L * 60 * 60 * 1000

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun policies(context: Context): List<AppPolicy> {
        val root = JSONObject(prefs(context).getString(POLICIES, "{}") ?: "{}")
        return root.keys().asSequence().mapNotNull { key ->
            runCatching {
                val o = root.getJSONObject(key)
                AppPolicy(
                    packageName = key,
                    label = o.optString("label", key),
                    dailyMinutes = o.optInt("daily", 60),
                    sessionMinutes = o.optInt("session", 15),
                    cooldownMinutes = o.optInt("cooldown", 30),
                    dailyEnabled = o.optBoolean("dailyEnabled", true),
                    sessionEnabled = o.optBoolean("sessionEnabled", true)
                )
            }.getOrNull()
        }.sortedBy { it.label.lowercase() }.toList()
    }

    fun policy(context: Context, packageName: String): AppPolicy? =
        policies(context).firstOrNull { it.packageName == packageName }

    fun savePolicy(context: Context, p: AppPolicy) {
        val root = JSONObject(prefs(context).getString(POLICIES, "{}") ?: "{}")
        root.put(p.packageName, JSONObject()
            .put("label", p.label)
            .put("daily", p.dailyMinutes.coerceIn(1, 1440))
            .put("session", p.sessionMinutes.coerceIn(1, 1440))
            .put("cooldown", p.cooldownMinutes.coerceIn(0, 1440))
            .put("dailyEnabled", p.dailyEnabled)
            .put("sessionEnabled", p.sessionEnabled))
        prefs(context).edit().putString(POLICIES, root.toString()).apply()
    }

    fun removePolicy(context: Context, packageName: String) {
        val root = JSONObject(prefs(context).getString(POLICIES, "{}") ?: "{}")
        root.remove(packageName)
        prefs(context).edit().putString(POLICIES, root.toString()).apply()
    }

    fun state(context: Context, packageName: String): BlockState {
        val root = JSONObject(prefs(context).getString(STATES, "{}") ?: "{}")
        val o = root.optJSONObject(packageName) ?: JSONObject()
        return BlockState(o.optLong("used", 0), o.optString("reason", ""),
            o.optLong("cooldown", 0), o.optLong("cycle", 0))
    }

    fun saveState(context: Context, packageName: String, s: BlockState) {
        val root = JSONObject(prefs(context).getString(STATES, "{}") ?: "{}")
        root.put(packageName, JSONObject().put("used", s.usedMs).put("reason", s.reason)
            .put("cooldown", s.cooldownUntilWall).put("cycle", s.cycleStartWall))
        prefs(context).edit().putString(STATES, root.toString()).apply()
    }

    fun resetIfExpired(context: Context, packageName: String, now: Long): BlockState {
        val old = state(context, packageName)
        if (old.cycleStartWall == 0L || now - old.cycleStartWall >= DAY_MS) {
            return BlockState(0, "", 0, now).also { saveState(context, packageName, it) }
        }
        return old
    }

    // Ajoute une durée d'utilisation au compteur journalier et mémorise si la limite est atteinte.
    fun addUsage(context: Context, packageName: String, delta: Long, now: Long): BlockState {
        val old = resetIfExpired(context, packageName, now)
        val p = policy(context, packageName) ?: return old
        val used = old.usedMs + delta.coerceAtLeast(0)
        val reason = if (p.dailyEnabled && used >= p.dailyMinutes * 60_000L) {
            "Limite journalière"
        } else if (old.reason == "Limite journalière" && p.dailyEnabled) {
            old.reason
        } else old.reason
        val updated = old.copy(usedMs = used, reason = reason)
        saveState(context, packageName, updated)
        return updated
    }

    // Démarre la période d'attente après le dépassement de la limite par session.
    fun startCooldown(context: Context, packageName: String, reason: String, now: Long): BlockState {
        val p = policy(context, packageName) ?: return state(context, packageName)
        if (!p.sessionEnabled) return state(context, packageName)
        val old = resetIfExpired(context, packageName, now)
        val updated = old.copy(reason = reason, cooldownUntilWall = now + p.cooldownMinutes * 60_000L)
        saveState(context, packageName, updated)
        return updated
    }

    // Retire automatiquement un blocage de session lorsque son délai est écoulé.
    fun clearCooldownIfExpired(context: Context, packageName: String, now: Long): BlockState {
        val s = resetIfExpired(context, packageName, now)
        if (s.cooldownUntilWall in 1..now && s.reason != "Limite journalière") {
            return s.copy(reason = "", cooldownUntilWall = 0).also { saveState(context, packageName, it) }
        }
        return s
    }
}
