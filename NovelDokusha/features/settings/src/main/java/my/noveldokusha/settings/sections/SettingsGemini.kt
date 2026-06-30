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
import androidx.compose.ui.res.stringResource
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
    Section(title = stringResource(R.string.gemini_ai_translation)) {
        // API Key input
        var showApiKey by remember { mutableStateOf(false) }

        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                text = stringResource(R.string.gemini_api_key_label),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.gemini_api_key_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                label = { Text(stringResource(R.string.gemini_api_key_field_label)) },
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
            // C4 fix: localized model labels via stringResource. Hardcoded labels meant non-English
            // users saw English text in the Gemini settings section.
            val presetModels = listOf(
                "gemini-2.5-flash" to stringResource(R.string.gemini_model_flash_best_quality),
                "gemini-2.5-flash-lite" to stringResource(R.string.gemini_model_flash_lite_more_quota),
            )
            var expanded by remember { mutableStateOf(false) }
            var isCustomModel by remember {
                mutableStateOf(model !in presetModels.map { it.first })
            }

            Text(
                text = stringResource(R.string.gemini_model_label),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.gemini_model_hint),
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
                            text = { Text(stringResource(R.string.gemini_custom_model)) },
                            onClick = {
                                isCustomModel = true
                                expanded = false
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Button(onClick = { isCustomModel = true }) {
                    Text(stringResource(R.string.gemini_enter_custom_model_name))
                }
            } else {
                OutlinedTextField(
                    value = model,
                    onValueChange = onModelChange,
                    label = { Text(stringResource(R.string.gemini_model_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                Button(onClick = {
                    isCustomModel = false
                    onModelChange("gemini-2.5-flash")
                }) {
                    Text(stringResource(R.string.gemini_use_preset_instead))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Temperature slider
            Text(
                text = stringResource(R.string.gemini_temperature_label, temperature),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.gemini_temperature_hint),
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
                    text = stringResource(R.string.gemini_status_key_configured),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Text(
                    text = stringResource(R.string.gemini_status_no_api_key),
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
