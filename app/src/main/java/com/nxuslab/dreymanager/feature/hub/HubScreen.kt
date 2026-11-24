package com.nxuslab.dreymanager.feature.hub

import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nxuslab.dreymanager.NxusApp
import com.nxuslab.dreymanager.R
import com.nxuslab.dreymanager.core.theme.NxusColors
import com.nxuslab.dreymanager.data.sync.BankSync
import com.nxuslab.dreymanager.domain.advice.StepAction
import com.nxuslab.dreymanager.domain.money.Money
import com.nxuslab.dreymanager.feature.financas.financeOverview
import com.nxuslab.dreymanager.domain.water.WaterPlan
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

class HubViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as NxusApp).repository
    private val water = (application as NxusApp).water
    private val notes = (application as NxusApp).notes

    /** Resumo das Anotações para o card do hub. */
    private val notesLine = combine(notes.reminders, notes.notes, notes.lists) { reminders, n, l ->
        val now = java.time.LocalDateTime.now()
        val today = now.toLocalDate()
        val todays = reminders.filter { r -> r.nextAt?.let { !it.toLocalDate().isAfter(today) } == true }
        val next = todays.mapNotNull { it.nextAt }.filter { it.isAfter(now) }.minOrNull()
        val late = todays.count { it.nextAt!!.isBefore(now) }
        when {
            todays.isNotEmpty() -> buildString {
                append(if (todays.size == 1) "1 lembrete hoje" else "${todays.size} lembretes hoje")
                if (next != null) append(" · próximo às %02d:%02d".format(next.hour, next.minute))
                else if (late > 0) append(" · ${if (late == 1) "1 atrasado" else "$late atrasados"}")
            }
            n.isNotEmpty() || l.isNotEmpty() -> listOfNotNull(
                n.size.takeIf { it > 0 }?.let { if (it == 1) "1 nota" else "$it notas" },
                l.size.takeIf { it > 0 }?.let { if (it == 1) "1 lista" else "$it listas" },
            ).joinToString(" · ")
            else -> "Notas, listas e lembretes"
        }
    }

    data class State(val name: String, val financeLine: String, val waterLine: String, val waterProgress: Float?, val notesLine: String)

    val state: StateFlow<State?> = combine(
        repository.snapshot, BankSync.status, water.settings, water.totalsSince(LocalDate.now()), notesLine,
    ) { snapshot, sync, waterPlan, totals, notesText ->
        val overview = snapshot.financeOverview(LocalDate.now(), sync.lastFailed)
        val line = when {
            overview.nextStep.action != StepAction.NONE -> overview.nextStep.title
            overview.daily != null && overview.daily.overTodayCents > 0 -> "Hoje passou ${Money.formatShort(overview.daily.overTodayCents)}"
            overview.daily != null -> "Hoje dá para gastar ${Money.formatShort(overview.daily.remainingTodayCents)}"
            else -> "Saldo, cartões e plano"
        }
        val drank = totals[LocalDate.now()] ?: 0
        val waterLine = waterPlan?.let { "Água · ${WaterPlan.litersLabel(drank)} de ${WaterPlan.litersLabel(it.goalMl)}" } ?: "Monte seu plano de água"
        State(snapshot.settings.userName, line, waterLine, waterPlan?.let { (drank.toFloat() / it.goalMl).coerceIn(0f, 1f) }, notesText)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun saveName(name: String) {
        viewModelScope.launch { repository.updateSettings { it.copy(userName = name.trim().take(30)) } }
    }
}

private val ptBr = Locale("pt", "BR")

/** Entrada do app: boas-vindas, pensamento do dia e as áreas da vida. */
@Composable
fun HubScreen(onOpenFinance: () -> Unit, onOpenMelhor: () -> Unit, onOpenNotas: () -> Unit, viewModel: HubViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val s = state ?: return
    val today = LocalDate.now()
    val quote = StoicQuotes.of(today)
    var askName by rememberSaveable { mutableStateOf(s.name.isBlank()) }
    val appear = remember { MutableTransitionState(false).apply { targetState = true } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 28.dp, 20.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.nxuslife_mark), contentDescription = "NxusLife", modifier = Modifier.size(44.dp))
                Box(Modifier.weight(1f))
                Text(dateLabel(today), style = MaterialTheme.typography.bodyMedium, color = NxusColors.TextMuted)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (s.name.isBlank()) "Bem-vindo!" else "Bem-vindo, ${s.name}!", fontSize = 30.sp, style = MaterialTheme.typography.headlineMedium)
                Text("Por onde quer começar hoje?", style = MaterialTheme.typography.bodyLarge, color = NxusColors.TextMuted)
            }
        }
        item {
            Column(
                Modifier.fillMaxWidth().background(NxusColors.Surface, RoundedCornerShape(24.dp)).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("PENSAMENTO DO DIA", style = MaterialTheme.typography.labelMedium, color = NxusColors.AccentLight)
                Text("“${quote.text}”", fontSize = 20.sp, fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, lineHeight = 27.sp)
                Text(quote.author, style = MaterialTheme.typography.bodyMedium, color = NxusColors.TextMuted)
            }
        }
        item {
            AnimatedVisibility(appear, enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { it / 6 }) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AreaCard("Controle financeiro", s.financeLine, Icons.Filled.CreditCard, NxusColors.Sage, NxusColors.SageDark, enabled = true, onClick = onOpenFinance)
                    AreaCard("Anotações", s.notesLine, Icons.AutoMirrored.Filled.Notes, NxusColors.Peach, NxusColors.Alert, enabled = true, onClick = onOpenNotas)
                    AreaCard("Seja sua melhor versão", s.waterLine, Icons.Filled.WaterDrop, NxusColors.Sky, NxusColors.SkyDark, enabled = true, progress = s.waterProgress, onClick = onOpenMelhor)
                }
            }
        }
        item {
            TextButton(onClick = { askName = true }, modifier = Modifier.fillMaxWidth()) {
                Text(if (s.name.isBlank()) "Como quer ser chamado?" else "Trocar meu nome", color = NxusColors.TextMuted)
            }
        }
    }

    if (askName) {
        var text by remember { mutableStateOf(s.name) }
        AlertDialog(
            onDismissRequest = { askName = false },
            containerColor = NxusColors.Surface,
            title = { Text("Como quer ser chamado?") },
            text = { OutlinedTextField(text, { text = it }, label = { Text("Seu nome") }, singleLine = true) },
            confirmButton = { Button(onClick = { viewModel.saveName(text); askName = false }, enabled = text.isNotBlank()) { Text("Salvar") } },
            dismissButton = { TextButton(onClick = { askName = false }) { Text("Agora não") } },
        )
    }
}

@Composable
private fun AreaCard(title: String, subtitle: String, icon: ImageVector, fill: Color, ink: Color, enabled: Boolean, progress: Float? = null, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(fill, RoundedCornerShape(26.dp)).clickable(enabled = enabled, onClick = onClick).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(52.dp).background(Color.White, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(26.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = NxusColors.Text.copy(alpha = 0.8f))
        }
        if (enabled) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = ink)
    }
    if (progress != null) {
        Box(Modifier.fillMaxWidth().height(8.dp).background(Color.White.copy(alpha = 0.75f), RoundedCornerShape(4.dp))) {
            Box(Modifier.fillMaxWidth(progress).height(8.dp).background(Color(0xFF5C93C2), RoundedCornerShape(4.dp)))
        }
    }
    }
}

private fun dateLabel(d: LocalDate): String {
    val day = d.dayOfWeek.getDisplayName(TextStyle.FULL, ptBr).substringBefore('-').replaceFirstChar { it.uppercase() }
    return "$day, ${d.dayOfMonth} de ${d.month.getDisplayName(TextStyle.FULL, ptBr)}"
}
