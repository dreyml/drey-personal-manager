package com.nxuslab.dreymanager.feature.melhor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nxuslab.dreymanager.core.theme.NxusColors
import com.nxuslab.dreymanager.domain.water.WaterPlan
import com.nxuslab.dreymanager.domain.water.WaterSettings

private val bottleChoices = listOf(500, 750, 1000)

private fun hhmm(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)

/** Formulário do plano de água: primeira vez e ajustes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaterSetup(
    initial: WaterSettings?,
    notificationsOff: Boolean,
    onSave: (WaterSettings) -> Unit,
    onCancel: (() -> Unit)?,
) {
    var goal by rememberSaveable { mutableStateOf(initial?.goalMl?.toString() ?: "3000") }
    var bottle by rememberSaveable { mutableStateOf(initial?.bottleMl ?: 750) }
    var otherBottle by rememberSaveable { mutableStateOf(initial?.bottleMl?.takeIf { it !in bottleChoices }?.toString() ?: "") }
    var useOther by rememberSaveable { mutableStateOf(initial?.bottleMl?.let { it !in bottleChoices } ?: false) }
    var start by rememberSaveable { mutableStateOf(initial?.startMinute ?: 8 * 60) }
    var end by rememberSaveable { mutableStateOf(initial?.endMinute ?: 17 * 60) }
    var reminders by rememberSaveable { mutableStateOf(initial?.remindersOn ?: true) }
    var picking by rememberSaveable { mutableStateOf<String?>(null) }

    val goalMl = goal.toIntOrNull() ?: 0
    val bottleMl = if (useOther) otherBottle.toIntOrNull() ?: 0 else bottle
    val valid = WaterSettings.isValid(goalMl, bottleMl, start, end)
    val preview = if (valid) WaterSettings(goalMl, bottleMl, start, end, reminders) else null

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Seu plano de água", fontSize = 28.sp, style = MaterialTheme.typography.headlineMedium)
                Text("Três respostas e o app monta o seu dia. Dá para mudar depois.", style = MaterialTheme.typography.bodyLarge, color = NxusColors.TextMuted)
            }
        }
        item {
            Column(
                Modifier.fillMaxWidth().background(NxusColors.Surface, RoundedCornerShape(24.dp)).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    goal, { goal = it.filter(Char::isDigit).take(4) },
                    label = { Text("Meta do dia (ml)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = goalMl !in WaterSettings.GOAL_RANGE,
                    supportingText = { Text("Entre 500 e 8000 ml. Ex.: 3000 = 3 litros.") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Tamanho da sua garrafa", style = MaterialTheme.typography.labelLarge, color = NxusColors.TextMuted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        bottleChoices.forEach { ml ->
                            Chip(if (ml == 1000) "1 L" else "$ml ml", selected = !useOther && bottle == ml, modifier = Modifier.weight(1f)) { useOther = false; bottle = ml }
                        }
                        Chip("Outro", selected = useOther, modifier = Modifier.weight(1f)) { useOther = true }
                    }
                    if (useOther) {
                        OutlinedTextField(
                            otherBottle, { otherBottle = it.filter(Char::isDigit).take(4) },
                            label = { Text("Garrafa (ml)") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            isError = bottleMl !in WaterSettings.BOTTLE_RANGE,
                            supportingText = { Text("Entre 100 e 3000 ml.") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Quando você bebe água", style = MaterialTheme.typography.labelLarge, color = NxusColors.TextMuted)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TimeBox("Começa", hhmm(start), Modifier.weight(1f)) { picking = "start" }
                        Text("até", color = NxusColors.TextMuted)
                        TimeBox("Termina", hhmm(end), Modifier.weight(1f)) { picking = "end" }
                    }
                    if (end <= start) Text("O fim precisa ser depois do começo.", color = NxusColors.Danger, style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Avisar se eu atrasar", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (notificationsOff) "Avisos desligados no Android. Ligue nas configurações do aparelho." else "No máximo 1 aviso por hora",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (notificationsOff) NxusColors.Alert else NxusColors.TextMuted,
                        )
                    }
                    Switch(reminders, { reminders = it }, colors = SwitchDefaults.colors(checkedTrackColor = NxusColors.SkyDark))
                }
            }
        }
        if (preview != null) {
            item {
                val n = WaterPlan.bottleCount(preview)
                Column(
                    Modifier.fillMaxWidth().background(NxusColors.Sky, RoundedCornerShape(24.dp)).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("SEU DIA FICA ASSIM", style = MaterialTheme.typography.labelMedium, color = NxusColors.SkyDark)
                    Text(
                        "$n ${if (n == 1) "garrafa" else "garrafas"} de ${WaterPlan.litersLabel(preview.bottleMl)}, uma a cada ${WaterPlan.durationLabel(WaterPlan.intervalMinutes(preview))}",
                        fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 26.sp,
                    )
                    Text("Até " + WaterPlan.schedule(preview).joinToString(" · ") { it.toString() }, color = NxusColors.SkyDark)
                }
            }
        }
        item {
            Button(
                onClick = { preview?.let(onSave) },
                enabled = valid,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NxusColors.Text, contentColor = Color.White),
                contentPadding = PaddingValues(16.dp),
            ) { Text(if (initial == null) "Começar" else "Salvar", fontWeight = FontWeight.Bold) }
            if (onCancel != null) TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancelar", color = NxusColors.TextMuted) }
        }
    }

    picking?.let { which ->
        val current = if (which == "start") start else end
        val state = rememberTimePickerState(current / 60, current % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { picking = null },
            containerColor = NxusColors.Surface,
            title = { Text(if (which == "start") "Começa às" else "Termina às") },
            text = { TimePicker(state) },
            confirmButton = {
                TextButton(onClick = {
                    val m = state.hour * 60 + state.minute
                    if (which == "start") start = m else end = m
                    picking = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        label,
        modifier = modifier
            .background(if (selected) NxusColors.SkyDark else NxusColors.SurfaceHigh, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        color = if (selected) Color.White else NxusColors.Text,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

@Composable
private fun TimeBox(label: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.background(NxusColors.SurfaceHigh, RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = NxusColors.TextMuted)
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}
