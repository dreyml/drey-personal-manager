package com.nxuslab.dreymanager.feature.notas

import android.app.Application
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nxuslab.dreymanager.NxusApp
import com.nxuslab.dreymanager.core.theme.NxusColors
import com.nxuslab.dreymanager.data.repo.Checklist
import com.nxuslab.dreymanager.data.repo.Note
import com.nxuslab.dreymanager.data.repo.Reminder
import com.nxuslab.dreymanager.domain.notes.Repeat
import com.nxuslab.dreymanager.domain.notes.ReminderSchedule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

enum class NotasTab(val label: String, val fab: String) { NOTAS("Notas", "Nota"), LISTAS("Listas", "Lista"), LEMBRETES("Lembretes", "Lembrete") }

class NotasViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = (application as NxusApp).notes

    data class State(val notes: List<Note>, val lists: List<Checklist>, val reminders: List<Reminder>)

    val state: StateFlow<State?> = combine(repo.notes, repo.lists, repo.reminders) { n, l, r -> State(n, l, r) }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun done(id: Long) {
        viewModelScope.launch { repo.done(id) }
    }

    suspend fun newList(): Long = repo.createList("")
}

/** Cores das notas: fundo e tinta. */
internal val noteColors = listOf(
    NxusColors.Surface to NxusColors.TextMuted,
    NxusColors.Peach to Color(0xFF6E3A22),
    NxusColors.Sky to Color(0xFF234A6B),
    NxusColors.Sage to Color(0xFF24523A),
    NxusColors.Lavender to Color(0xFF3F3566),
)

internal fun colorOf(index: Int) = noteColors[index.coerceIn(0, noteColors.lastIndex)]

private val ptBr = Locale("pt", "BR")

@Composable
fun NotasScreen(
    onBack: () -> Unit,
    onOpenNote: (Long) -> Unit,
    onOpenList: (Long) -> Unit,
    onOpenReminder: (Long) -> Unit,
    viewModel: NotasViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val s = state ?: return
    var tab by rememberSaveable { mutableStateOf(NotasTab.NOTAS) }
    var query by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val now = LocalDateTime.now()

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 110.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack, modifier = Modifier.padding(end = 4.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar") }
                    Text("Anotações", style = MaterialTheme.typography.titleLarge)
                }
            }
            item {
                OutlinedTextField(
                    query, { query = it },
                    placeholder = { Text("Buscar em tudo") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = NxusColors.Surface, focusedContainerColor = NxusColors.Surface, unfocusedBorderColor = Color.Transparent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotBlank()) {
                searchResults(s, query.trim(), now, onOpenNote, onOpenList, onOpenReminder)
            } else {
                item { Tabs(tab) { tab = it } }
                when (tab) {
                    NotasTab.NOTAS -> notesTab(s.notes, onOpenNote)
                    NotasTab.LISTAS -> listsTab(s.lists, onOpenList)
                    NotasTab.LEMBRETES -> remindersTab(s.reminders, now, onOpenReminder, viewModel::done)
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = {
                when (tab) {
                    NotasTab.NOTAS -> onOpenNote(0)
                    NotasTab.LISTAS -> scope.launch { onOpenList(viewModel.newList()) }
                    NotasTab.LEMBRETES -> onOpenReminder(0)
                }
            },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text(tab.fab) },
            containerColor = NxusColors.Text,
            contentColor = Color.White,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        )
    }
}

@Composable
private fun Tabs(selected: NotasTab, onSelect: (NotasTab) -> Unit) {
    Row(Modifier.fillMaxWidth().background(NxusColors.SurfaceHigh, RoundedCornerShape(18.dp)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        NotasTab.entries.forEach { t ->
            val on = t == selected
            Text(
                t.label,
                modifier = Modifier.weight(1f)
                    .background(if (on) NxusColors.Surface else Color.Transparent, RoundedCornerShape(14.dp))
                    .clickable { onSelect(t) }
                    .padding(vertical = 10.dp),
                textAlign = TextAlign.Center,
                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                color = if (on) NxusColors.Text else NxusColors.TextMuted,
            )
        }
    }
}

private fun LazyListScope.label(text: String, color: Color = NxusColors.TextMuted) {
    item { Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = color, modifier = Modifier.padding(top = 4.dp)) }
}

private fun LazyListScope.empty(text: String) {
    item { Text(text, color = NxusColors.TextMuted, modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), textAlign = TextAlign.Center) }
}

// ---------- Notas ----------

private fun LazyListScope.notesTab(notes: List<Note>, onOpen: (Long) -> Unit) {
    if (notes.isEmpty()) return empty("Nenhuma nota ainda. Toque em + Nota para escrever.")
    val pinned = notes.filter { it.pinned }
    val rest = notes.filter { !it.pinned }
    if (pinned.isNotEmpty()) { label("Fixadas", NxusColors.AccentLight); noteGrid(pinned, onOpen) }
    if (rest.isNotEmpty()) { if (pinned.isNotEmpty()) label("Recentes"); noteGrid(rest, onOpen) }
}

private fun LazyListScope.noteGrid(notes: List<Note>, onOpen: (Long) -> Unit) {
    item {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(0, 1).forEach { col ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    notes.filterIndexed { i, _ -> i % 2 == col }.forEach { NoteCard(it) { onOpen(it.id) } }
                }
            }
        }
    }
}

@Composable
private fun NoteCard(note: Note, onClick: () -> Unit) {
    val (bg, ink) = colorOf(note.color)
    Column(Modifier.fillMaxWidth().background(bg, RoundedCornerShape(20.dp)).clickable(onClick = onClick).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (note.title.isNotBlank()) Text(note.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (note.body.isNotBlank()) Text(note.body, style = MaterialTheme.typography.bodySmall, color = ink, maxLines = 6, overflow = TextOverflow.Ellipsis)
        Text(whenLabel(note.updatedAt), style = MaterialTheme.typography.labelSmall, color = ink.copy(alpha = 0.7f))
    }
}

private fun whenLabel(millis: Long): String {
    val d = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when (d) {
        today -> "hoje"
        today.minusDays(1) -> "ontem"
        else -> "%02d/%02d".format(d.dayOfMonth, d.monthValue)
    }
}

// ---------- Listas ----------

private fun LazyListScope.listsTab(lists: List<Checklist>, onOpen: (Long) -> Unit) {
    if (lists.isEmpty()) return empty("Nenhuma lista ainda. Toque em + Lista para começar.")
    lists.forEach { l -> item(key = "l${l.id}") { ListCard(l) { onOpen(l.id) } } }
}

@Composable
private fun ListCard(list: Checklist, onClick: () -> Unit) {
    val total = list.items.size
    val ready = total > 0 && list.doneCount == total
    Column(Modifier.fillMaxWidth().background(NxusColors.Surface, RoundedCornerShape(22.dp)).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(list.title.ifBlank { "Lista sem nome" }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                if (ready) "pronta" else "${list.doneCount} de $total",
                color = if (ready) NxusColors.SageDark else NxusColors.TextMuted,
                fontWeight = if (ready) FontWeight.Bold else FontWeight.Normal,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (total > 0) {
            Box(Modifier.fillMaxWidth().height(6.dp).background(NxusColors.SurfaceHigh, RoundedCornerShape(3.dp))) {
                Box(Modifier.fillMaxWidth(list.doneCount.toFloat() / total).height(6.dp).background(NxusColors.Ring, RoundedCornerShape(3.dp)))
            }
            list.items.take(3).forEach { item ->
                Text(
                    (if (item.done) "✓ " else "○ ") + item.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.done) NxusColors.TextMuted else NxusColors.Text,
                    textDecoration = if (item.done) TextDecoration.LineThrough else null,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (total > 3) Text("+ ${total - 3} itens", style = MaterialTheme.typography.labelSmall, color = NxusColors.TextMuted)
        }
    }
}

// ---------- Lembretes ----------

private fun LazyListScope.remindersTab(reminders: List<Reminder>, now: LocalDateTime, onOpen: (Long) -> Unit, onDone: (Long) -> Unit) {
    if (reminders.isEmpty()) return empty("Nenhum lembrete. Toque em + Lembrete e escolha dia e hora.")
    val today = now.toLocalDate()
    val active = reminders.filter { it.nextAt != null }
    val todays = active.filter { !it.nextAt!!.toLocalDate().isAfter(today) }
    val later = active.filter { it.nextAt!!.toLocalDate().isAfter(today) }
    val done = reminders.filter { it.nextAt == null }
    if (todays.isNotEmpty()) {
        label("Hoje", NxusColors.Alert)
        todays.forEach { r -> item(key = "r${r.id}") { ReminderRow(r, now, onOpen, onDone) } }
    }
    if (later.isNotEmpty()) {
        label("Próximos")
        later.forEach { r -> item(key = "r${r.id}") { ReminderRow(r, now, onOpen, onDone) } }
    }
    if (done.isNotEmpty()) item(key = "feitos") { DoneSection(done, onOpen) }
}

@Composable
private fun ReminderRow(r: Reminder, now: LocalDateTime, onOpen: (Long) -> Unit, onDone: (Long) -> Unit) {
    val at = r.nextAt ?: return
    val late = at.isBefore(now)
    val today = now.toLocalDate()
    Row(
        Modifier.fillMaxWidth().background(if (late) NxusColors.Peach else NxusColors.Surface, RoundedCornerShape(20.dp)).clickable { onOpen(r.id) }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.width(60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (at.toLocalDate() != today) {
                Text(dateShort(at, today), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = if (late) NxusColors.Alert else NxusColors.Text)
            }
            Text("%02d:%02d".format(at.hour, at.minute), fontWeight = FontWeight.Bold, color = if (late) NxusColors.Alert else NxusColors.Text)
            if (late) Text("atrasado", style = MaterialTheme.typography.labelSmall, color = NxusColors.Alert)
        }
        Column(Modifier.weight(1f)) {
            Text(r.text, style = MaterialTheme.typography.titleSmall)
            Text(
                (if (r.rule.repeat == Repeat.ONCE) "" else "↻ ") + ReminderSchedule.repeatLabel(r.rule),
                style = MaterialTheme.typography.bodySmall, color = if (late) Color(0xFF6E3A22) else NxusColors.TextMuted,
            )
        }
        if (late || at.toLocalDate() == today) {
            TextButton(onClick = { onDone(r.id) }) { Text("Feito", fontWeight = FontWeight.Bold, color = if (late) NxusColors.Alert else NxusColors.AccentLight) }
        }
    }
}

private fun dateShort(at: LocalDateTime, today: LocalDate): String {
    val d = at.toLocalDate()
    return when {
        d == today.plusDays(1) -> "amanhã"
        d.isAfter(today) && d.isBefore(today.plusDays(7)) -> d.dayOfWeek.getDisplayName(TextStyle.SHORT, ptBr).take(3).lowercase() + " " + d.dayOfMonth
        else -> "%02d/%02d".format(d.dayOfMonth, d.monthValue)
    }
}

@Composable
private fun DoneSection(done: List<Reminder>, onOpen: (Long) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { open = !open }, modifier = Modifier.fillMaxWidth()) {
            Text("Feitos (${done.size}) " + if (open) "⌃" else "›", color = NxusColors.TextMuted)
        }
        if (open) done.forEach { r ->
            Text(
                r.text, color = NxusColors.TextMuted, textDecoration = TextDecoration.LineThrough,
                modifier = Modifier.fillMaxWidth().background(NxusColors.Surface, RoundedCornerShape(16.dp)).clickable { onOpen(r.id) }.padding(14.dp),
            )
        }
    }
}

// ---------- Busca ----------

private fun LazyListScope.searchResults(
    s: NotasViewModel.State, q: String, now: LocalDateTime,
    onOpenNote: (Long) -> Unit, onOpenList: (Long) -> Unit, onOpenReminder: (Long) -> Unit,
) {
    val notes = s.notes.filter { it.title.contains(q, true) || it.body.contains(q, true) }
    val lists = s.lists.filter { l -> l.title.contains(q, true) || l.items.any { it.text.contains(q, true) } }
    val reminders = s.reminders.filter { it.text.contains(q, true) }
    if (notes.isEmpty() && lists.isEmpty() && reminders.isEmpty()) return empty("Nada encontrado para \"$q\".")
    if (notes.isNotEmpty()) { label("Notas"); noteGrid(notes, onOpenNote) }
    if (lists.isNotEmpty()) { label("Listas"); lists.forEach { l -> item(key = "sl${l.id}") { ListCard(l) { onOpenList(l.id) } } } }
    if (reminders.isNotEmpty()) {
        label("Lembretes")
        reminders.forEach { r ->
            item(key = "sr${r.id}") {
                if (r.nextAt != null) ReminderRow(r, now, onOpenReminder) {}
                else Text(r.text, color = NxusColors.TextMuted, modifier = Modifier.fillMaxWidth().clickable { onOpenReminder(r.id) }.padding(14.dp))
            }
        }
    }
}
