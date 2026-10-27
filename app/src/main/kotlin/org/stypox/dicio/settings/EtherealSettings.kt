package org.stypox.dicio.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.stypox.dicio.io.wake.EtherealClient
import org.stypox.dicio.settings.ui.SettingsCategoryTitle

/** Ethereal fork: PC endpoint settings, stored in `<filesDir>/ethereal.json`. */
fun LazyListScope.etherealSettings() {
    item { SettingsCategoryTitle("Ethereal", topPadding = 4.dp) }
    item { EtherealSettingsBlock() }
}

@Composable
private fun EtherealSettingsBlock() {
    val context = LocalContext.current
    val initial = remember { EtherealClient.loadConfig(context) }
    var url by remember { mutableStateOf(initial.url) }
    var token by remember { mutableStateOf(initial.token) }
    var stream by remember { mutableStateOf(initial.stream) }
    var showToken by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it; status = null },
            label = { Text("Server URL (/v1/turn)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it; status = null },
            label = { Text("Token") },
            singleLine = true,
            visualTransformation =
                if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                TextButton(onClick = { showToken = !showToken }) {
                    Text(if (showToken) "Hide" else "Show")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Stream replies", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Play each sentence as soon as it is ready",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Switch(checked = stream, onCheckedChange = { stream = it; status = null })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                status = try {
                    EtherealClient.saveConfig(context, EtherealClient.Config(url, token, stream))
                    "Saved"
                } catch (e: Exception) {
                    "Could not save: ${e.javaClass.simpleName}"
                }
            }) { Text("Save") }
            OutlinedButton(
                enabled = !testing,
                onClick = {
                    testing = true
                    status = "Testing..."
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            EtherealClient.testConnection(url, token)
                        }
                        status = result
                        testing = false
                    }
                },
            ) { Text("Test connection") }
        }
        status?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
