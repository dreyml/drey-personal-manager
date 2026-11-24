package com.nxuslab.dreymanager.feature.notas

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nxuslab.dreymanager.NxusApp
import com.nxuslab.dreymanager.core.theme.NxusColors
import com.nxuslab.dreymanager.data.repo.Note
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Salvamentos que precisam terminar mesmo depois de a tela fechar. */
internal val editorSaveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Editor de nota: salva sozinho; nota vazia não fica guardada. */
@Composable
fun NoteEditor(noteId: Long, onBack: () -> Unit) {
    val repo = (LocalContext.current.applicationContext as NxusApp).notes
    var loaded by remember { mutableStateOf(noteId == 0L) }
    var id by rememberSaveable { mutableLongStateOf(noteId) }
    var title by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    var color by rememberSaveable { mutableStateOf(0) }
    var pinned by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleted by remember { mutableStateOf(false) }

    LaunchedEffect(noteId) {
        if (noteId != 0L && !loaded) {
            repo.note(noteId)?.let { title = it.title; body = it.body; color = it.color; pinned = it.pinned }
            loaded = true
        }
    }
    // Um salvamento por vez, sempre com o id mais recente: evita criar a mesma nota duas vezes.
    val lock = remember { Mutex() }
    fun save(note: Note) {
        editorSaveScope.launch {
            lock.withLock {
                val saved = repo.saveNote(note.copy(id = id))
                withContext(Dispatchers.Main) { id = saved }
            }
        }
    }
    // Salva meio segundo depois da última mudança.
    LaunchedEffect(title, body, color, pinned, loaded) {
        if (!loaded || deleted) return@LaunchedEffect
        delay(500)
        save(Note(0, title, body, color, pinned, 0))
    }
    val latest by rememberUpdatedState(Note(0, title, body, color, pinned, 0))
    DisposableEffect(Unit) {
        onDispose { if (loaded && !deleted) save(latest) }
    }

    val (bg, _) = colorOf(color)
    Column(Modifier.fillMaxSize().background(bg).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar") }
            Box(Modifier.weight(1f))
            IconButton(onClick = { pinned = !pinned }) {
                Icon(if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin, contentDescription = if (pinned) "Desafixar" else "Fixar no topo", tint = if (pinned) NxusColors.AccentLight else NxusColors.TextMuted)
            }
            if (id != 0L) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.DeleteOutline, contentDescription = "Apagar nota", tint = NxusColors.TextMuted) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Field(title, { title = it }, "Título", TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, color = NxusColors.Text))
            Field(body, { body = it }, "Escreva aqui…", TextStyle(fontSize = 17.sp, lineHeight = 26.sp, color = NxusColors.Text), single = false)
        }
        Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Cor", style = MaterialTheme.typography.labelLarge, color = NxusColors.TextMuted)
            noteColors.forEachIndexed { i, (c, _) ->
                Box(
                    Modifier.size(32.dp).background(c, CircleShape)
                        .border(if (i == color) 3.dp else 1.dp, if (i == color) NxusColors.AccentLight else NxusColors.SurfaceHigh, CircleShape)
                        .clickable { color = i },
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = NxusColors.Surface,
            title = { Text("Apagar esta nota?") },
            text = { Text("Não dá para desfazer.") },
            confirmButton = {
                TextButton(onClick = {
                    deleted = true
                    confirmDelete = false
                    val toDelete = id
                    editorSaveScope.launch { repo.deleteNote(toDelete) }
                    onBack()
                }) { Text("Apagar", color = NxusColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
internal fun Field(value: String, onChange: (String) -> Unit, hint: String, style: TextStyle, single: Boolean = true, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth()) {
        if (value.isEmpty()) Text(hint, style = style.copy(color = NxusColors.TextMuted.copy(alpha = 0.6f)))
        BasicTextField(value, onChange, textStyle = style, singleLine = single, cursorBrush = SolidColor(NxusColors.AccentLight), modifier = Modifier.fillMaxWidth())
    }
}
