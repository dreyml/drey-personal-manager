package com.nxuslab.dreymanager.feature.notas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nxuslab.dreymanager.NxusApp
import com.nxuslab.dreymanager.core.theme.NxusColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Editor de lista: nome, itens com caixinha, adicionar, limpar marcados. */
@Composable
fun ListEditor(listId: Long, onBack: () -> Unit) {
    val repo = (LocalContext.current.applicationContext as NxusApp).notes
    val list by remember(listId) { repo.lists.map { all -> all.firstOrNull { it.id == listId } } }.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var title by rememberSaveable { mutableStateOf<String?>(null) }
    var newItem by rememberSaveable { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleted by remember { mutableStateOf(false) }

    val l = list
    LaunchedEffect(l?.id) { if (l != null && title == null) title = l.title }
    LaunchedEffect(title) {
        val t = title ?: return@LaunchedEffect
        delay(400)
        if (!deleted && l != null && t != l.title) repo.renameList(listId, t)
    }
    // Lista que ficou sem nome e sem itens não fica guardada.
    val latestEmpty by rememberUpdatedState(title.isNullOrBlank() && (l?.items?.isEmpty() ?: true))
    val latestTitle by rememberUpdatedState(title)
    DisposableEffect(Unit) {
        onDispose {
            if (deleted) return@onDispose
            editorSaveScope.launch {
                if (latestEmpty) repo.deleteList(listId) else latestTitle?.let { repo.renameList(listId, it) }
            }
        }
    }

    fun add() {
        val text = newItem
        if (text.isBlank()) return
        newItem = ""
        scope.launch { repo.addItem(listId, text) }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar") }
            Box(Modifier.weight(1f))
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.DeleteOutline, contentDescription = "Apagar lista", tint = NxusColors.TextMuted) }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                Field(title.orEmpty(), { title = it }, "Nome da lista", TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, color = NxusColors.Text), modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))
            }
            val items = l?.items.orEmpty()
            items(items, key = { it.id }) { item ->
                Row(
                    Modifier.fillMaxWidth().background(NxusColors.Surface, RoundedCornerShape(16.dp)).padding(start = 4.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = item.done,
                        onCheckedChange = { scope.launch { repo.setItemDone(listId, item.id, it) } },
                        colors = CheckboxDefaults.colors(checkedColor = NxusColors.SageDark),
                    )
                    Text(
                        item.text, modifier = Modifier.weight(1f),
                        color = if (item.done) NxusColors.TextMuted else NxusColors.Text,
                        textDecoration = if (item.done) TextDecoration.LineThrough else null,
                    )
                    IconButton(onClick = { scope.launch { repo.deleteItem(item.id) } }) {
                        Icon(Icons.Filled.Close, contentDescription = "Tirar ${item.text}", tint = NxusColors.TextMuted)
                    }
                }
            }
            item {
                OutlinedTextField(
                    newItem, { newItem = it },
                    placeholder = { Text("Adicionar item") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add() }),
                    trailingIcon = { if (newItem.isNotBlank()) TextButton(onClick = ::add) { Text("Adicionar") } },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
            if (items.any { it.done }) {
                item {
                    TextButton(onClick = { scope.launch { repo.clearDone(listId) } }, modifier = Modifier.fillMaxWidth()) {
                        Text("Limpar marcados (${items.count { it.done }})", color = NxusColors.TextMuted)
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = NxusColors.Surface,
            title = { Text("Apagar esta lista?") },
            text = { Text("A lista e todos os itens somem. Não dá para desfazer.", style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = {
                    deleted = true
                    confirmDelete = false
                    editorSaveScope.launch { repo.deleteList(listId) }
                    onBack()
                }) { Text("Apagar", color = NxusColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }
}
