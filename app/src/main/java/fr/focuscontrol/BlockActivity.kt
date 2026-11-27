package fr.focuscontrol

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.concurrent.TimeUnit

class BlockActivity : ComponentActivity() {
    companion object { const val EXTRA_PACKAGE = "blocked_package" }
    private val handler = Handler(Looper.getMainLooper())
    private var packageNameBlocked: String = ""
    private val tick = object : Runnable {
        override fun run() {
            if (!isFinishing) {
                setContent { MaterialTheme { BlockScreen(this@BlockActivity, packageNameBlocked) } }
                handler.postDelayed(this, 1000)
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        packageNameBlocked = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        tick.run()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        packageNameBlocked = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
    }
    override fun onDestroy() { handler.removeCallbacks(tick); super.onDestroy() }
}

@Composable
private fun BlockScreen(activity: Activity, pkg: String) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(pkg) {
        while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) }
    }
    val policy = PolicyStore.policy(activity, pkg)
    val state = PolicyStore.state(activity, pkg)
    val label = runCatching {
        activity.packageManager.getApplicationLabel(activity.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
    val usedMin = TimeUnit.MILLISECONDS.toMinutes(state.usedMs)
    val cooldownMs = (state.cooldownUntilWall - now).coerceAtLeast(0)
    val cooldownText = if (state.reason == "Limite journalière") {
        val cycleEnd = state.cycleStartWall + 24L * 60 * 60 * 1000
        formatTime((cycleEnd - now).coerceAtLeast(0))
    } else formatTime(cooldownMs)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Temps écoulé", style = MaterialTheme.typography.headlineLarge)
            Text("$label est temporairement bloqué.")
            Spacer(Modifier.height(24.dp))
            InfoCard("UTILISATION AUJOURD'HUI", "$usedMin min" + (policy?.let { " / ${it.dailyMinutes} min" } ?: ""))
            InfoCard("LIMITE ATTEINTE", state.reason.ifBlank { "Restriction active" })
            InfoCard("TEMPS RESTANT AVANT DÉBLOCAGE", cooldownText)
            Spacer(Modifier.height(24.dp))
            Button(onClick = { activity.finish(); activity.moveTaskToBack(true) }, modifier = Modifier.fillMaxWidth()) {
                Text("CLOSE")
            }
        }
    }
}
@Composable private fun InfoCard(title: String, value: String) {
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(16.dp)) { Text(title, style = MaterialTheme.typography.labelSmall); Text(value, style = MaterialTheme.typography.titleLarge) }
    }
}
private fun formatTime(ms: Long): String {
    val total = TimeUnit.MILLISECONDS.toSeconds(ms)
    return "%02d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
}
