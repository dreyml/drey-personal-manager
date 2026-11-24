package com.nxuslab.dreymanager.feature.melhor

import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nxuslab.dreymanager.NxusApp
import com.nxuslab.dreymanager.core.theme.NxusColors
import com.nxuslab.dreymanager.domain.water.DayBar
import com.nxuslab.dreymanager.domain.water.SlotState
import com.nxuslab.dreymanager.domain.water.WaterDay
import com.nxuslab.dreymanager.domain.water.WaterPhase
import com.nxuslab.dreymanager.domain.water.WaterPlan
import com.nxuslab.dreymanager.domain.water.WaterSettings
import com.nxuslab.dreymanager.notifications.Notifier
import com.nxuslab.dreymanager.notifications.WaterReminders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

class MelhorViewModel(application: Application) : AndroidViewModel(application) {
    private val water = (application as NxusApp).water

    data class State(
        val settings: WaterSettings?,
        val day: WaterDay?,
        val week: List<DayBar>,
        val streak: Int,
        val now: LocalDateTime,
    )

    /** Relógio da tela: atualiza na virada de cada minuto. */
    private val clock = flow {
        while (true) {
            val now = LocalDateTime.now()
            emit(now)
            delay(60_000L - now.second * 1000L)
        }
    }

    val state: StateFlow<State?> = combine(water.settings, water.totalsSince(LocalDate.now().minusDays(8)), clock) { s, totals, now ->
        val today = now.toLocalDate()
        val total = totals[today] ?: 0
        State(
            settings = s,
            day = s?.let { WaterPlan.today(it, total, now.toLocalTime()) },
            week = s?.let { WaterPlan.week(it.goalMl, totals, today) }.orEmpty(),
            streak = s?.let { WaterPlan.streak(it.goalMl, totals, today) } ?: 0,
            now = now,
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _finished = MutableStateFlow<Int?>(null)
    /** Número da garrafa que acabou de ser concluída (mostra o Desfazer). */
    val finished: StateFlow<Int?> = _finished.asStateFlow()

    fun setLeft(day: WaterDay, newLeft: Int) {
        val drank = day.leftInBottleMl - newLeft
        if (drank <= 0) return
        viewModelScope.launch {
            water.drink(drank)
            _finished.value = if (newLeft == 0) day.bottlesDone + 1 else null
        }
    }

    fun undo() {
        viewModelScope.launch {
            water.undoLast(LocalDate.now())
            _finished.value = null
        }
    }

    fun dismissFinished() { _finished.value = null }

    fun save(s: WaterSettings) {
        viewModelScope.launch {
            water.save(s)
            WaterReminders.sync(getApplication(), s.remindersOn)
        }
    }
}

private val ptBr = Locale("pt", "BR")

/** "Seja sua melhor versão": por enquanto, a água do dia. */
@Composable
fun MelhorScreen(onBack: () -> Unit, viewModel: MelhorViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val finished by viewModel.finished.collectAsStateWithLifecycle()
    val s = state ?: return
    var editing by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (editing) editing = false else onBack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
            }
            Text("Melhor versão", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (s.settings != null && !editing) {
                IconButton(onClick = { editing = true }) { Icon(Icons.Filled.Tune, contentDescription = "Ajustar plano de água", tint = NxusColors.TextMuted) }
            }
        }
        if (s.settings == null || editing) {
            WaterSetup(
                initial = s.settings,
                notificationsOff = !Notifier.enabled(context),
                onSave = { viewModel.save(it); editing = false },
                onCancel = if (s.settings != null) ({ editing = false }) else null,
            )
        } else {
            WaterToday(s, finished, viewModel)
        }
    }
}

@Composable
private fun WaterToday(s: MelhorViewModel.State, finished: Int?, viewModel: MelhorViewModel) {
    val day = s.day ?: return
    val quote = StoicBody.of(s.now.toLocalDate())

    LaunchedEffect(finished) {
        if (finished != null) {
            delay(6_000)
            viewModel.dismissFinished()
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Column(
                Modifier.fillMaxWidth().background(NxusColors.Surface, RoundedCornerShape(24.dp)).padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("“${quote.text}”", fontSize = 18.sp, fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, lineHeight = 25.sp)
                Text(quote.author, style = MaterialTheme.typography.bodyMedium, color = NxusColors.TextMuted)
            }
        }
        item { BottleCard(day, finished, viewModel) }
        item { PaceCard(day, s) }
        item { ScheduleCard(day) }
        item { WeekCard(s.week, s.streak, day.goalMl) }
    }
}

@Composable
private fun BottleCard(day: WaterDay, finished: Int?, viewModel: MelhorViewModel) {
    Column(
        Modifier.fillMaxWidth().background(NxusColors.Surface, RoundedCornerShape(28.dp)).padding(horizontal = 20.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("ÁGUA HOJE", style = MaterialTheme.typography.labelMedium, color = NxusColors.SkyDark)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(WaterPlan.litersLabel(day.totalMl), fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text("  de ${WaterPlan.litersLabel(day.goalMl)}", fontSize = 18.sp, color = NxusColors.TextMuted, modifier = Modifier.padding(bottom = 5.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Bottle(day.leftInBottleMl, day.bottleMl, onSetLeft = { viewModel.setLeft(day, it) })
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column {
                    Text("Nesta garrafa", style = MaterialTheme.typography.bodySmall, color = NxusColors.TextMuted)
                    Text("${day.leftInBottleMl} ml", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("ainda dentro", style = MaterialTheme.typography.bodySmall, color = NxusColors.TextMuted)
                }
                Column {
                    Text("Garrafas", style = MaterialTheme.typography.bodySmall, color = NxusColors.TextMuted)
                    Text("${day.bottlesDone} de ${day.bottleCount}", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Text(
            "Arraste o dedo para baixo na garrafa até o nível que sobrou.",
            style = MaterialTheme.typography.bodySmall, color = NxusColors.TextMuted, textAlign = TextAlign.Center,
        )
        AnimatedVisibility(finished != null) {
            Row(
                Modifier.fillMaxWidth().background(NxusColors.Text, RoundedCornerShape(16.dp)).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Garrafa ${finished ?: ""} concluída. Enchendo de novo.", color = Color.White, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = viewModel::undo) { Text("Desfazer", color = Color(0xFFB9AEE6), fontWeight = FontWeight.Bold) }
            }
        }
        if (finished == null && day.totalMl > 0) {
            TextButton(onClick = viewModel::undo) { Text("Desfazer último registro", color = NxusColors.TextMuted) }
        }
    }
}

@Composable
private fun PaceCard(day: WaterDay, s: MelhorViewModel.State) {
    val settings = s.settings ?: return
    val nowLabel = "%02d:%02d".format(s.now.hour, s.now.minute)
    val ideal = WaterPlan.litersLabel(day.idealMl)
    val (bg, fg, title, text) = when {
        day.goalReached -> Quad(NxusColors.Sage, NxusColors.SageDark, "Meta do dia cumprida", "Feito é melhor que perfeito. Amanhã, o mesmo cuidado.")
        day.phase == WaterPhase.BEFORE -> Quad(NxusColors.Sky, NxusColors.SkyDark, "Seu dia de água começa às ${settings.start}", "Quando começar, beba aos poucos. Constância vale mais que pressa.")
        day.phase == WaterPhase.AFTER -> Quad(NxusColors.Peach, NxusColors.Alert, "Hoje ficou em ${WaterPlan.litersLabel(day.totalMl)} de ${WaterPlan.litersLabel(day.goalMl)}", "O que passou não depende mais de você. Amanhã é um novo começo.")
        day.behindMl >= WaterPlan.BEHIND_SHOW_ML -> Quad(NxusColors.Peach, NxusColors.Alert, "Faltam ${day.behindMl} ml para o ritmo", "Até agora ($nowLabel) o ideal era $ideal. Não precisa compensar de uma vez: um copo agora já ajuda.")
        else -> Quad(NxusColors.Sage, NxusColors.SageDark, "Você está no ritmo", "Até agora ($nowLabel) o ideal era $ideal. Constância vale mais que pressa.")
    }
    Column(Modifier.fillMaxWidth().background(bg, RoundedCornerShape(24.dp)).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = fg)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = fg, lineHeight = 20.sp)
    }
}

private data class Quad(val bg: Color, val fg: Color, val title: String, val text: String)

@Composable
private fun ScheduleCard(day: WaterDay) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Cronograma de hoje", style = MaterialTheme.typography.titleMedium)
        Column(Modifier.fillMaxWidth().background(NxusColors.Surface, RoundedCornerShape(24.dp)).padding(horizontal = 18.dp, vertical = 6.dp)) {
            day.slots.forEachIndexed { i, slot ->
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    val (dotBg, dotFg) = when (slot.state) {
                        SlotState.DONE -> NxusColors.Ring to NxusColors.SageDark
                        SlotState.CURRENT -> NxusColors.Sky to NxusColors.SkyDark
                        SlotState.LATER -> NxusColors.SurfaceHigh to NxusColors.TextMuted
                    }
                    Box(Modifier.size(32.dp).background(dotBg, CircleShape), contentAlignment = Alignment.Center) {
                        Text(if (slot.state == SlotState.DONE) "✓" else slot.number.toString(), color = dotFg, fontWeight = FontWeight.Bold)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Garrafa ${slot.number}", style = MaterialTheme.typography.titleSmall)
                        Text(
                            when (slot.state) {
                                SlotState.DONE -> "Concluída"
                                SlotState.CURRENT -> "Agora · faltam ${day.leftInBottleMl} ml"
                                SlotState.LATER -> "Depois"
                            },
                            style = MaterialTheme.typography.bodySmall, color = NxusColors.TextMuted,
                        )
                    }
                    Text(
                        "até ${slot.deadline}",
                        fontWeight = FontWeight.Bold,
                        color = when {
                            slot.late -> NxusColors.Alert
                            slot.state == SlotState.DONE -> NxusColors.SageDark
                            else -> NxusColors.TextMuted
                        },
                    )
                }
                if (i < day.slots.lastIndex) HorizontalDivider(color = NxusColors.SurfaceHigh)
            }
        }
    }
}

@Composable
private fun WeekCard(week: List<DayBar>, streak: Int, goalMl: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Últimos 7 dias", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (streak > 0) Text(if (streak == 1) "1 dia seguido" else "$streak dias seguidos", color = NxusColors.SageDark, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.fillMaxWidth().background(NxusColors.Surface, RoundedCornerShape(24.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val maxMl = maxOf(goalMl * 6 / 5, week.maxOfOrNull { it.totalMl } ?: 0)
            val chartH = 110.dp
            Box(Modifier.fillMaxWidth().height(chartH)) {
                Box(Modifier.fillMaxWidth().padding(top = chartH * (1f - goalMl.toFloat() / maxMl)).height(2.dp).background(Color(0xFFBCD6EC)))
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                    week.forEach { bar ->
                        val h by animateDpAsState(maxOf(8.dp, chartH * (bar.totalMl.toFloat() / maxMl)), label = "barra")
                        Box(
                            Modifier.weight(1f).height(h).background(
                                when {
                                    bar.today -> Color(0xFF5C93C2)
                                    bar.hit -> NxusColors.Ring
                                    else -> Color(0xFFF4C3AA)
                                },
                                RoundedCornerShape(10.dp),
                            ),
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                week.forEach { bar ->
                    Text(
                        if (bar.today) "hoje" else bar.date.dayOfWeek.getDisplayName(TextStyle.SHORT, ptBr).take(3).lowercase(),
                        modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (bar.today) NxusColors.SkyDark else NxusColors.TextMuted,
                        fontWeight = if (bar.today) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
            Text("A linha é a meta de ${WaterPlan.litersLabel(goalMl)}.", style = MaterialTheme.typography.bodySmall, color = NxusColors.TextMuted)
        }
    }
}
