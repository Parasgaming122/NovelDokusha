package my.noveldokusha.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import my.noveldokusha.strings.R

/**
 * Gemini AI Translation settings section.
 *
 * Allows the user to:
 * - Enter their Gemini API key (with show/hide toggle)
 * - Select the Gemini model (preset or custom)
 * - Adjust the translation temperature
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsGemini(
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    model: String,
    onModelChange: (String) -> Unit,
    temperature: Float,
    onTemperatureChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Section(title = "Gemini AI Translation") {
        // API Key input
        var showApiKey by remember { mutableStateOf(false) }

        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                text = "API Key",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Get your free key at aistudio.google.com",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                label = { Text("Gemini API Key") },
                visualTransformation = if (showApiKey) VisualTransformation.None
                else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    Switch(
                        checked = showApiKey,
                        onCheckedChange = { showApiKey = it },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Model selection
            val presetModels = listOf(
                "gemini-2.5-flash" to "Flash (Best quality, 10 RPM/250 RPD)",
                "gemini-2.5-flash-lite" to "Flash Lite (More quota, 30 RPM/1000 RPD)",
            )
            var expanded by remember { mutableStateOf(false) }
            var isCustomModel by remember {
                mutableStateOf(model !in presetModels.map { it.first })
            }

            Text(
                text = "Model",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Select a preset or enter a custom model name",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (!isCustomModel) {
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it }
                ) {
                    OutlinedTextField(
                        value = presetModels.find { it.first == model }?.second ?: model,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )

                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        presetModels.forEach { (modelId, label) ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(label, style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            modelId,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = {
                                    onModelChange(modelId)
                                    expanded = false
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Custom model...") },
                            onClick = {
                                isCustomModel = true
                                expanded = false
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Button(onClick = { isCustomModel = true }) {
                    Text("Enter custom model name")
                }
            } else {
                OutlinedTextField(
                    value = model,
                    onValueChange = onModelChange,
                    label = { Text("Model name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                Button(onClick = {
                    isCustomModel = false
                    onModelChange("gemini-2.5-flash")
                }) {
                    Text("Use preset instead")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Temperature slider
            Text(
                text = "Temperature: ${String.format("%.2f", temperature)}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "0.3 = consistent, 0.55 = balanced, 0.7 = creative",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            Slider(
                value = temperature,
                onValueChange = onTemperatureChange,
                valueRange = 0.0f..1.0f,
                steps = 9,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Status info
            if (apiKey.isNotBlank()) {
                Text(
                    text = "Status: Key configured. Use TimoTxt (Gemini) source to read novels with AI translation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Text(
                    text = "Status: No API key. TimoTxt (Gemini) source requires a Gemini API key to translate.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun Section(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.padding(vertical = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        content()
    }
}
