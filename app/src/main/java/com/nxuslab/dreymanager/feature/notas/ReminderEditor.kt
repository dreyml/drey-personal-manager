package com.nxuslab.dreymanager.feature.notas

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.nxuslab.dreymanager.NxusApp
import com.nxuslab.dreymanager.core.theme.NxusColors
import com.nxuslab.dreymanager.domain.notes.Repeat
import com.nxuslab.dreymanager.domain.notes.ReminderRule
import com.nxuslab.dreymanager.domain.notes.ReminderSchedule
import com.nxuslab.dreymanager.notifications.AlarmReminderScheduler
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

private val repeatOptions = listOf(Repeat.ONCE to "Uma vez", Repeat.DAILY to "Todo dia", Repeat.WEEKLY to "Dias da semana", Repeat.MONTHLY to "Todo mês")
private val dayLetters = listOf("S", "T", "Q", "Q", "S", "S", "D")

/** Novo lembrete ou edição: texto, dia, hora e repetição. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ReminderEditor(reminderId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = (context.applicationContext as NxusApp).notes
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()
    var loaded by remember { mutableStateOf(reminderId == 0L) }
    var text by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(today.toString()) }
    var minute by rememberSaveable { mutableIntStateOf(nextRoundMinute()) }
    var repeat by rememberSaveable { mutableStateOf(Repeat.ONCE) }
    var week by rememberSaveable { mutableIntStateOf(ReminderRule.bit(today.dayOfWeek)) }
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var canExact by remember { mutableStateOf(AlarmReminderScheduler.canExact(context)) }

    LaunchedEffect(reminderId) {
        if (reminderId != 0L && !loaded) {
            repo.reminder(reminderId)?.let {
                text = it.text; date = it.rule.startDate.toString(); minute = it.rule.minuteOfDay
                repeat = it.rule.repeat; week = it.rule.weekDays.takeIf { w -> w != 0 } ?: week
            }
            loaded = true
        }
    }
    // Ao voltar da tela de permissão do Android, confere de novo.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { canExact = AlarmReminderScheduler.canExact(context) } }

    val start = LocalDate.parse(date)
    val rule = ReminderRule(start, minute, repeat, if (repeat == Repeat.WEEKLY) week else 0)
    val inPast = repeat == Repeat.ONCE && start.atTime(rule.time).isBefore(java.time.LocalDateTime.now())
    val valid = text.isNotBlank() && rule.isValid() && !inPast

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar") }
            Text(if (reminderId == 0L) "Novo lembrete" else "Lembrete", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (reminderId != 0L) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.DeleteOutline, contentDescription = "Apagar lembrete", tint = NxusColors.TextMuted) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(
                Modifier.fillMaxWidth().background(NxusColors.Surface, RoundedCornerShape(24.dp)).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Field(text, { text = it }, "O que lembrar?", TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = NxusColors.Text))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Picker(if (repeat == Repeat.ONCE) "Dia" else "Começa", ReminderSchedule.dayLabel(start, today), Modifier.weight(1f)) { picking = "date" }
                    Picker("Hora", rule.time.toString(), Modifier.weight(1f)) { picking = "time" }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Repetir", style = MaterialTheme.typography.labelLarge, color = NxusColors.TextMuted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeatOptions.forEach { (r, label) -> Chip(label, repeat == r) { repeat = r } }
                    }
                    if (repeat == Repeat.WEEKLY) {
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            DayOfWeek.entries.forEachIndexed { i, d ->
                                val on = week and ReminderRule.bit(d) != 0
                                Box(
                                    Modifier.size(40.dp).background(if (on) NxusColors.AccentLight else NxusColors.SurfaceHigh, CircleShape)
                                        .clickable { week = week xor ReminderRule.bit(d) },
                                    contentAlignment = Alignment.Center,
                                ) { Text(dayLetters[i], color = if (on) Color.White else NxusColors.TextMuted, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal) }
                            }
                        }
                    }
                }
                Text(
                    if (inPast) "Esse horário já passou. Escolha outro dia ou hora." else ReminderSchedule.summary(rule, today),
                    modifier = Modifier.fillMaxWidth().background(if (inPast) NxusColors.Peach else NxusColors.Lavender, RoundedCornerShape(16.dp)).padding(14.dp),
                    color = if (inPast) NxusColors.Alert else Color(0xFF3F3566),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (!canExact) {
                Column(
                    Modifier.fillMaxWidth().background(NxusColors.Peach, RoundedCornerShape(20.dp)).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Para chegar no minuto certo, permita \"Alarmes e lembretes\".", color = NxusColors.Alert, style = MaterialTheme.typography.bodyMedium)
                    Text("Sem isso o aviso ainda chega, mas pode atrasar alguns minutos.", color = Color(0xFF6E3A22), style = MaterialTheme.typography.bodySmall)
                    AlarmReminderScheduler.permissionIntent(context)?.let { intent ->
                        TextButton(onClick = { runCatching { context.startActivity(intent) } }, contentPadding = PaddingValues(0.dp)) {
                            Text("Abrir configuração", color = NxusColors.Alert, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            Button(
                onClick = {
                    scope.launch {
                        repo.saveReminder(reminderId, text, rule)
                        onBack()
                    }
                },
                enabled = valid,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NxusColors.Text, contentColor = Color.White),
                contentPadding = PaddingValues(16.dp),
            ) { Text("Salvar lembrete", fontWeight = FontWeight.Bold) }
        }
    }

    when (picking) {
        "date" -> {
            val state = rememberDatePickerState(initialSelectedDateMillis = start.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
            DatePickerDialog(
                onDismissRequest = { picking = null },
                confirmButton = {
                    TextButton(onClick = {
                        state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }
                        picking = null
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { picking = null }) { Text("Cancelar") } },
            ) { DatePicker(state) }
        }
        "time" -> {
            val state = rememberTimePickerState(minute / 60, minute % 60, is24Hour = true)
            AlertDialog(
                onDismissRequest = { picking = null },
                containerColor = NxusColors.Surface,
                title = { Text("Hora do aviso") },
                text = { TimePicker(state) },
                confirmButton = { TextButton(onClick = { minute = state.hour * 60 + state.minute; picking = null }) { Text("OK") } },
                dismissButton = { TextButton(onClick = { picking = null }) { Text("Cancelar") } },
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = NxusColors.Surface,
            title = { Text("Apagar este lembrete?") },
            text = { Text("O aviso deixa de tocar. Não dá para desfazer.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    editorSaveScope.launch { repo.deleteReminder(reminderId) }
                    onBack()
                }) { Text("Apagar", color = NxusColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }
}

/** Próxima hora cheia (ou meia hora) a partir de agora. */
private fun nextRoundMinute(): Int {
    val now = LocalTime.now()
    val m = now.hour * 60 + now.minute
    return ((m / 30 + 1) * 30).coerceAtMost(23 * 60 + 30)
}

@Composable
private fun Picker(label: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.background(NxusColors.SurfaceHigh, RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = NxusColors.TextMuted)
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier
            .background(if (selected) NxusColors.AccentLight else NxusColors.SurfaceHigh, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        color = if (selected) Color.White else NxusColors.Text,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
    )
}
