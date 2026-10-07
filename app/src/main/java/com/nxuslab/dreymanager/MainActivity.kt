package com.nxuslab.dreymanager

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nxuslab.dreymanager.data.MoneyTransaction
import com.nxuslab.dreymanager.data.PersonalTask
import com.nxuslab.dreymanager.data.TransactionType
import com.nxuslab.dreymanager.update.UpdateState
import java.text.NumberFormat
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MaterialTheme { PersonalManagerApp() } }
    }
}

@Composable
private fun PersonalManagerApp(viewModel: MainViewModel = viewModel()) {
    val updates by viewModel.updateState.collectAsStateWithLifecycle()
    val transactions by viewModel.transactions.collectAsStateWithLifecycle()
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var transactionDialog by remember { mutableStateOf(false) }
    var taskDialog by remember { mutableStateOf(false) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                listOf("Início", "Finanças", "Tarefas").forEachIndexed { index, name ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Text(listOf("⌂", "R$", "✓")[index]) },
                        label = { Text(name) },
                    )
                }
            }
        },
        floatingActionButton = {
            if (tab > 0) FloatingActionButton(onClick = {
                if (tab == 1) transactionDialog = true else taskDialog = true
            }) { Text("+") }
        },
    ) { padding ->
        when (tab) {
            0 -> HomeScreen(Modifier.padding(padding), transactions, tasks, updates, { tab = 1 }, { tab = 2 })
            1 -> FinanceScreen(Modifier.padding(padding), transactions, viewModel::deleteTransaction)
            else -> TasksScreen(Modifier.padding(padding), tasks, viewModel::toggleTask, viewModel::deleteTask)
        }
    }
    if (transactionDialog) TransactionDialog(
        onDismiss = { transactionDialog = false },
        onSave = { description, value, type -> viewModel.addTransaction(description, value, type); transactionDialog = false },
    )
    if (taskDialog) TaskDialog(
        onDismiss = { taskDialog = false },
        onSave = { title, due -> viewModel.addTask(title, due); taskDialog = false },
    )
}

@Composable
private fun HomeScreen(modifier: Modifier, transactions: List<MoneyTransaction>, tasks: List<PersonalTask>, update: UpdateState, openFinance: () -> Unit, openTasks: () -> Unit) {
    val balance = transactions.sumOf { if (it.type == TransactionType.INCOME) it.amount else -it.amount }
    LazyColumn(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Drey Manager", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text("Seu gerenciamento pessoal em um só lugar.") }
        if (update is UpdateState.Available) item { UpdateNotice(update) }
        item { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) { Column(Modifier.padding(20.dp)) { Text("Saldo atual"); Text(money(balance), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) } } }
        item { Shortcut("Finanças", "${transactions.size} lançamento(s) registrados", openFinance) }
        item { Shortcut("Tarefas", "${tasks.count { !it.completed }} pendente(s)", openTasks) }
        item { Text("V1 local", fontWeight = FontWeight.SemiBold); Text("Os dados ficam salvos somente neste aparelho.") }
    }
}

@Composable private fun Shortcut(title: String, subtitle: String, onClick: () -> Unit) = Card(Modifier.fillMaxWidth()) {
    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Text(subtitle) }
        TextButton(onClick = onClick) { Text("Abrir") }
    }
}

@Composable
private fun FinanceScreen(modifier: Modifier, transactions: List<MoneyTransaction>, onDelete: (String) -> Unit) {
    val current = YearMonth.now()
    val month = transactions.filter { YearMonth.from(Instant.ofEpochMilli(it.createdAt).atZone(ZoneId.systemDefault())) == current }
    val income = month.filter { it.type == TransactionType.INCOME }.sumOf { it.amount }
    val expense = month.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amount }
    LazyColumn(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Finanças", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text("Resumo deste mês") }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) { Summary("Entradas", money(income), Modifier.weight(1f)); Summary("Saídas", money(expense), Modifier.weight(1f)) } }
        item { Text("Lançamentos", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
        if (transactions.isEmpty()) item { Empty("Nenhum lançamento ainda", "Use o botão + para registrar uma entrada ou saída.") }
        items(transactions, key = { it.id }) { item -> TransactionItem(item, onDelete) }
    }
}

@Composable private fun Summary(label: String, value: String, modifier: Modifier) = Card(modifier) { Column(Modifier.padding(14.dp)) { Text(label); Text(value, fontWeight = FontWeight.Bold) } }

@Composable private fun TransactionItem(item: MoneyTransaction, onDelete: (String) -> Unit) = Card(Modifier.fillMaxWidth()) {
    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(item.description, fontWeight = FontWeight.SemiBold); Text(if (item.type == TransactionType.INCOME) "Entrada" else "Saída") }
        Column(horizontalAlignment = Alignment.End) { Text((if (item.type == TransactionType.INCOME) "+ " else "− ") + money(item.amount), fontWeight = FontWeight.Bold); TextButton(onClick = { onDelete(item.id) }) { Text("Excluir") } }
    }
}

@Composable
private fun TasksScreen(modifier: Modifier, tasks: List<PersonalTask>, onToggle: (String) -> Unit, onDelete: (String) -> Unit) {
    LazyColumn(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Tarefas", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text("Acompanhe o que precisa ser feito.") }
        if (tasks.isEmpty()) item { Empty("Nenhuma tarefa ainda", "Use o botão + para criar sua primeira tarefa.") }
        items(tasks, key = { it.id }) { task -> Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(task.completed, { onToggle(task.id) })
                Column(Modifier.weight(1f)) { Text(task.title, fontWeight = FontWeight.SemiBold, textDecoration = if (task.completed) TextDecoration.LineThrough else null); task.dueDate?.let { Text("Prazo: $it", style = MaterialTheme.typography.bodySmall) } }
                TextButton(onClick = { onDelete(task.id) }) { Text("Excluir") }
            }
        } }
    }
}

@Composable private fun Empty(title: String, body: String) = Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp)) { Text(title, fontWeight = FontWeight.SemiBold); Text(body) } }

@Composable private fun UpdateNotice(update: UpdateState.Available) {
    val context = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Atualização disponível: ${update.manifest.versionName}", fontWeight = FontWeight.SemiBold); Text(update.manifest.message)
        Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.manifest.downloadUrl))) }) { Text("Ver atualização") }
    } }
}

@Composable private fun TransactionDialog(onDismiss: () -> Unit, onSave: (String, Double, TransactionType) -> Unit) {
    var description by remember { mutableStateOf("") }; var amount by remember { mutableStateOf("") }; var type by remember { mutableStateOf(TransactionType.EXPENSE) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Novo lançamento") }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(description, { description = it }, label = { Text("Descrição") }, singleLine = true)
        OutlinedTextField(amount, { amount = it.replace(',', '.') }, label = { Text("Valor") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = { type = TransactionType.INCOME }) { Text(if (type == TransactionType.INCOME) "✓ Entrada" else "Entrada") }; OutlinedButton(onClick = { type = TransactionType.EXPENSE }) { Text(if (type == TransactionType.EXPENSE) "✓ Saída" else "Saída") } }
    } }, confirmButton = { Button(onClick = { amount.toDoubleOrNull()?.let { onSave(description, it, type) } }, enabled = description.isNotBlank() && (amount.toDoubleOrNull() ?: 0.0) > 0) { Text("Salvar") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}

@Composable private fun TaskDialog(onDismiss: () -> Unit, onSave: (String, String?) -> Unit) {
    var title by remember { mutableStateOf("") }; var date by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Nova tarefa") }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { OutlinedTextField(title, { title = it }, label = { Text("Título") }, singleLine = true); OutlinedTextField(date, { date = it }, label = { Text("Prazo opcional, ex.: 10/10") }, singleLine = true) } }, confirmButton = { Button(onClick = { onSave(title, date) }, enabled = title.isNotBlank()) { Text("Salvar") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}

private fun money(value: Double): String = NumberFormat.getCurrencyInstance(Locale("pt", "BR")).format(value)
