@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package fr.focuscontrol

import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class InstalledApp(val packageName: String, val label: String)

// Point d'entrée de l'application : demande l'autorisation de notification puis affiche l'interface principale.
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4102)
        }
        setContent { MaterialTheme { FocusHome(this) } }
    }
}

// Écran d'accueil : charge les règles enregistrées, liste les applications suivies et permet d'en ajouter.
@Composable
private fun FocusHome(activity: MainActivity) {
    var refresh by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<AppPolicy?>(null) }
    var retentionText by remember { mutableStateOf(PolicyStore.sessionRetentionMinutes(activity).toString()) }
    val policies = remember(refresh) { PolicyStore.policies(activity) }
    val installed = remember {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        activity.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map { InstalledApp(it.activityInfo.packageName, it.loadLabel(activity.packageManager).toString()) }
            .filter { it.packageName != activity.packageName }
            .distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
    }

    if (selected != null) {
        PolicyEditor(selected!!, onSave = {
            PolicyStore.savePolicy(activity, it); selected = null; refresh++
        }, onDelete = {
            PolicyStore.removePolicy(activity, it.packageName); selected = null; refresh++
        }, onBack = { selected = null })
        return
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Focus Control") }) }) { padding ->
        LazyColumn(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Surveillance", style = MaterialTheme.typography.titleMedium)
                Text("Active le service d'accessibilité et l'accès aux statistiques pour appliquer les limites.")
                Button(onClick = { activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                    Text("Activer l'accessibilité")
                }
                OutlinedButton(onClick = { activity.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) {
                    Text("Autoriser les statistiques d'utilisation")
                }
                Divider()
                Text("Conservation du compteur de session", style = MaterialTheme.typography.titleMedium)
                Text("Après avoir quitté une application suivie, son temps de session est conservé pendant cette durée. Si tu reviens après, le compteur de session repart de zéro. Le temps journalier reste conservé.")
                OutlinedTextField(
                    value = retentionText,
                    onValueChange = { retentionText = it.filter(Char::isDigit).take(4) },
                    label = { Text("Durée en minutes (1 à 1440)") },
                    singleLine = true
                )
                Button(onClick = {
                    val minutes = retentionText.toIntOrNull()?.coerceIn(1, 1440) ?: 10
                    PolicyStore.setSessionRetentionMinutes(activity, minutes)
                    retentionText = minutes.toString()
                }) { Text("Enregistrer la durée") }
                Text("Applications configurées", style = MaterialTheme.typography.titleMedium)
            }
            items(policies, key = { it.packageName }) { p ->
                Card(onClick = { selected = p }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(p.label, style = MaterialTheme.typography.titleMedium)
                        Text("Jour : ${if (p.dailyEnabled) "activé" else "désactivé"} • Session : ${if (p.sessionEnabled) "activée" else "désactivée"}")
                        Text("Jour : ${p.dailyMinutes} min  •  Session : ${p.sessionMinutes} min  •  Cooldown : ${p.cooldownMinutes} min")
                    }
                }
            }
            item {
                var expanded by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Ajouter une application")
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    installed.filter { app -> policies.none { it.packageName == app.packageName } }
                        .take(100).forEach { app ->
                            DropdownMenuItem(text = { Text(app.label) }, onClick = {
                                selected = AppPolicy(app.packageName, app.label); expanded = false
                            })
                        }
                }
            }
        }
    }
}

// Éditeur d'une règle : active séparément les limites journalières et par session, puis enregistre les valeurs.
@Composable
private fun PolicyEditor(policy: AppPolicy, onSave: (AppPolicy) -> Unit, onDelete: (AppPolicy) -> Unit, onBack: () -> Unit) {
    var daily by remember(policy) { mutableStateOf(policy.dailyMinutes.toString()) }
    var session by remember(policy) { mutableStateOf(policy.sessionMinutes.toString()) }
    var cooldown by remember(policy) { mutableStateOf(policy.cooldownMinutes.toString()) }
    var dailyEnabled by remember(policy) { mutableStateOf(policy.dailyEnabled) }
    var sessionEnabled by remember(policy) { mutableStateOf(policy.sessionEnabled) }

    Scaffold(topBar = { TopAppBar(title = { Text(policy.label) }, navigationIcon = {
        TextButton(onClick = onBack) { Text("Retour") }
    }) }) { padding ->
        Column(Modifier.padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Limite journalière", style = MaterialTheme.typography.titleMedium)
                    Text("Activer ou désactiver cette restriction")
                }
                Switch(checked = dailyEnabled, onCheckedChange = { dailyEnabled = it })
            }
            Text("Limite journalière (minutes)")
            OutlinedTextField(daily, { daily = it.filter(Char::isDigit) }, singleLine = true, enabled = dailyEnabled)

            Divider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Limite par session", style = MaterialTheme.typography.titleMedium)
                    Text("Activer ou désactiver cette restriction")
                }
                Switch(checked = sessionEnabled, onCheckedChange = { sessionEnabled = it })
            }
            Text("Durée maximale d'une session (minutes)")
            OutlinedTextField(session, { session = it.filter(Char::isDigit) }, singleLine = true, enabled = sessionEnabled)
            Text("Cooldown après une session (minutes)")
            OutlinedTextField(cooldown, { cooldown = it.filter(Char::isDigit) }, singleLine = true, enabled = sessionEnabled)

            Button(onClick = {
                onSave(policy.copy(
                    dailyMinutes = daily.toIntOrNull()?.coerceIn(1, 1440) ?: policy.dailyMinutes,
                    sessionMinutes = session.toIntOrNull()?.coerceIn(1, 1440) ?: policy.sessionMinutes,
                    cooldownMinutes = cooldown.toIntOrNull()?.coerceIn(0, 1440) ?: policy.cooldownMinutes,
                    dailyEnabled = dailyEnabled,
                    sessionEnabled = sessionEnabled
                ))
            }, modifier = Modifier.fillMaxWidth()) { Text("Enregistrer") }
            TextButton(onClick = { onDelete(policy) }) { Text("Retirer cette application") }
        }
    }
}
