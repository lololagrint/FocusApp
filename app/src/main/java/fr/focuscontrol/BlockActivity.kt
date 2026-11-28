package fr.focuscontrol

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

// Activité plein écran affichée lorsqu'une application suivie atteint une de ses limites.
class BlockActivity : ComponentActivity() {
    companion object { const val EXTRA_PACKAGE = "blocked_package" }
    private var packageNameBlocked by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        packageNameBlocked = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme {
                BlockScreen(this, packageNameBlocked)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        packageNameBlocked = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
    }
}

// Présente la raison du blocage et le compte à rebours avant le déblocage.
@Composable
private fun BlockScreen(activity: Activity, pkg: String) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(pkg) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val policy = PolicyStore.policy(activity, pkg)
    val state = PolicyStore.state(activity, pkg)
    val label = runCatching {
        activity.packageManager.getApplicationLabel(activity.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
    val usedMin = TimeUnit.MILLISECONDS.toMinutes(state.usedMs)
    val cooldownMs = (state.cooldownUntilWall - now).coerceAtLeast(0)
    val cooldownText = if (policy?.permanentLock == true) {
        "Déverrouille cette application depuis Focus Control."
    } else if (state.reason == "Limite journalière") {
        val cycleEnd = state.cycleStartWall + 24L * 60 * 60 * 1000
        formatTime((cycleEnd - now).coerceAtLeast(0))
    } else formatTime(cooldownMs)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Temps écoulé", style = MaterialTheme.typography.headlineLarge)
            Text(if (policy?.permanentLock == true) "$label est verrouillé." else "$label est temporairement bloqué.")
            Spacer(Modifier.height(24.dp))
            InfoCard("UTILISATION AUJOURD'HUI", "$usedMin min" + (policy?.let { " / ${it.dailyMinutes} min" } ?: ""))
            InfoCard("LIMITE ATTEINTE", state.reason.ifBlank { "Restriction active" })
            if (policy?.permanentLock == true) {
                InfoCard("DÉVERROUILLAGE", cooldownText)
            } else {
                InfoCard("TEMPS RESTANT AVANT DÉBLOCAGE", cooldownText)
            }
            Spacer(Modifier.height(24.dp))
            Button(onClick = {
                val home = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                activity.startActivity(home)
                activity.finish()
            }, modifier = Modifier.fillMaxWidth()) {
                Text("CLOSE")
            }
        }
    }
}

// Petit composant réutilisé pour afficher une information de blocage.
@Composable
private fun InfoCard(title: String, value: String) {
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.labelSmall)
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}

// Convertit une durée en affichage heures:minutes:secondes.
private fun formatTime(ms: Long): String {
    val total = TimeUnit.MILLISECONDS.toSeconds(ms)
    return "%02d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
}
