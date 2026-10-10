package com.nxuslab.dreymanager

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.nxuslab.dreymanager.data.MoneyTransaction
import com.nxuslab.dreymanager.data.CategoryEntity
import com.nxuslab.dreymanager.data.GoalEntity
import com.nxuslab.dreymanager.data.PersonalTask
import com.nxuslab.dreymanager.data.RecurringBillEntity
import com.nxuslab.dreymanager.data.TransactionType
import com.nxuslab.dreymanager.update.UpdateState
import com.nxuslab.dreymanager.ui.DreyManagerTheme
import com.nxuslab.dreymanager.notifications.ReminderWorker
import com.nxuslab.dreymanager.notifications.NotificationHelper
import java.util.concurrent.TimeUnit
import java.text.NumberFormat
import java.time.Instant
import java.time.YearMonth
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        NotificationHelper.createChannel(this)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 700)
        }
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "drey-daily-reminders", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS).build(),
        )
        setContent { DreyManagerTheme { PersonalManagerApp() } }
    }
}

@Composable
private fun PersonalManagerApp(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    var exportUri by remember { mutableStateOf<Uri?>(null) }
    var importUri by remember { mutableStateOf<Uri?>(null) }
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> exportUri = uri }
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> importUri = uri }
    LaunchedEffect(exportUri) {
        exportUri?.let { uri ->
            val snapshot = viewModel.exportSnapshot()
            context.contentResolver.openOutputStream(uri)?.use { it.write(snapshot.toByteArray()) }
            exportUri = null
        }
    }
    LaunchedEffect(importUri) {
        importUri?.let { uri ->
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { viewModel.restoreSnapshot(it.readText()) }
            importUri = null
        }
    }
    val updates by viewModel.updateState.collectAsStateWithLifecycle()
    val transactions by viewModel.transactions.collectAsStateWithLifecycle()
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val recurringBills by viewModel.recurringBills.collectAsStateWithLifecycle()
    val goals by viewModel.goals.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var transactionDialog by remember { mutableStateOf(false) }
    var taskDialog by remember { mutableStateOf(false) }
    var recurringDialog by remember { mutableStateOf(false) }
    var goalDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 8.dp) {
                listOf("Início", "Finanças", "Planejar", "Relatórios", "Tarefas").forEachIndexed { index, name ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Text(listOf("◉", "◈", "⌁", "▥", "✓")[index]) },
                        label = { Text(name) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        },
        floatingActionButton = {
            if (tab == 1 || tab == 2 || tab == 4) FloatingActionButton(containerColor = MaterialTheme.colorScheme.primary, onClick = {
                if (tab == 1) transactionDialog = true else if (tab == 2) recurringDialog = true else taskDialog = true
            }) { Text("+") }
        },
    ) { padding ->
        when (tab) {
            0 -> HomeScreen(Modifier.padding(padding), transactions, tasks, recurringBills, goals, updates, { tab = 1 }, { tab = 2 }, { exportLauncher.launch("drey-manager-backup.json") }, { importLauncher.launch(arrayOf("application/json", "text/json")) })
            1 -> FinanceScreen(Modifier.padding(padding), transactions, categories, viewModel::deleteTransaction)
            2 -> PlanningScreen(Modifier.padding(padding), recurringBills, goals, categories, viewModel::addRecurringBill, viewModel::addGoal, { goalDialog = true })
            3 -> ReportsScreen(Modifier.padding(padding), transactions, categories)
            else -> TasksScreen(Modifier.padding(padding), tasks, viewModel::toggleTask, viewModel::deleteTask)
        }
    }
    if (transactionDialog) TransactionDialog(
        onDismiss = { transactionDialog = false },
        categories = categories,
        onSave = { description, value, type, categoryId -> viewModel.addTransaction(description, value, type, categoryId); transactionDialog = false },
    )
    if (taskDialog) TaskDialog(
        onDismiss = { taskDialog = false },
        onSave = { title, due -> viewModel.addTask(title, due); taskDialog = false },
    )
    if (recurringDialog) RecurringDialog(
        onDismiss = { recurringDialog = false },
        categories = categories,
        onSave = { title, amount, day, categoryId -> viewModel.addRecurringBill(title, amount, day, categoryId); recurringDialog = false },
    )
    if (goalDialog) GoalDialog(
        onDismiss = { goalDialog = false },
        onSave = { title, target, deadline -> viewModel.addGoal(title, target, deadline); goalDialog = false },
    )
}

@Composable
private fun HomeScreen(modifier: Modifier, transactions: List<MoneyTransaction>, tasks: List<PersonalTask>, bills: List<RecurringBillEntity>, goals: List<GoalEntity>, update: UpdateState, openFinance: () -> Unit, openTasks: () -> Unit, exportData: () -> Unit, importData: () -> Unit) {
    val balance = transactions.sumOf { if (it.type == TransactionType.INCOME) it.amount else -it.amount }
    val income = transactions.filter { it.type == TransactionType.INCOME }.sumOf { it.amount }
    val expense = transactions.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amount }
    LazyColumn(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("Olá!", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.SemiBold)
            Text("Sua vida, no controle.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text("Visão geral do seu dia", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (update is UpdateState.Available) item { UpdateNotice(update) }
        item { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = MaterialTheme.shapes.extraLarge) { Column(Modifier.padding(26.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text("SALDO DISPONÍVEL", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer); Text(money(balance), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold); Text("Seu panorama financeiro em um só lugar", color = MaterialTheme.colorScheme.onPrimaryContainer); HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Column { Text("Entradas", style = MaterialTheme.typography.labelSmall); Text(money(income), fontWeight = FontWeight.SemiBold) }; Column(horizontalAlignment = Alignment.End) { Text("Saídas", style = MaterialTheme.typography.labelSmall); Text(money(expense), fontWeight = FontWeight.SemiBold) } } } } }
        item { Shortcut("Finanças", "${transactions.size} lançamento(s) registrados", openFinance) }
        item { Shortcut("Tarefas", "${tasks.count { !it.completed }} pendente(s)", openTasks) }
        item { Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text("Resumo rápido", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { QuickMetric("Pendentes", tasks.count { !it.completed }.toString()); QuickMetric("Contas", bills.size.toString()); QuickMetric("Metas", goals.size.toString()) } } } }
        item { Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Fluxo financeiro", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); Text("Entradas x saídas", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; LinearProgressIndicator(progress = { if (income > 0) (expense / income).coerceIn(0.0, 1.0).toFloat() else 0f }, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondary); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Entradas ${money(income)}", color = MaterialTheme.colorScheme.secondary); Text("Saídas ${money(expense)}", color = MaterialTheme.colorScheme.error) } } } }
        item { Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text("Próximas contas", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); if (bills.isEmpty()) Text("Nenhuma conta recorrente cadastrada", color = MaterialTheme.colorScheme.onSurfaceVariant) else bills.take(3).forEach { bill -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Column { Text(bill.title, fontWeight = FontWeight.SemiBold); Text("Dia ${bill.dayOfMonth}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Text(money(bill.amountCents / 100.0), fontWeight = FontWeight.Bold) } } } } }
        item { Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text("Metas", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); if (goals.isEmpty()) Text("Crie uma meta para acompanhar seu progresso", color = MaterialTheme.colorScheme.onSurfaceVariant) else goals.take(3).forEach { goal -> val progress = if (goal.targetCents > 0) (goal.currentCents.toFloat() / goal.targetCents).coerceIn(0f, 1f) else 0f; Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(goal.title, fontWeight = FontWeight.SemiBold); Text("${(progress * 100).toInt()}%", color = MaterialTheme.colorScheme.primary) }; LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth()) } } } } }
        item { Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text("Atividade recente", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); if (transactions.isEmpty()) Text("Seus lançamentos aparecerão aqui", color = MaterialTheme.colorScheme.onSurfaceVariant) else transactions.take(3).forEach { transaction -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(transaction.description, fontWeight = FontWeight.SemiBold); Text((if (transaction.type == TransactionType.INCOME) "+ " else "− ") + money(transaction.amount), color = if (transaction.type == TransactionType.INCOME) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold) } } } } }
        item { Shortcut("Backup dos dados", "Exportar um arquivo JSON deste aparelho", exportData) }
        item { Shortcut("Restaurar backup", "Importar um arquivo JSON salvo", importData) }
        item { Text("Privacidade em primeiro lugar", fontWeight = FontWeight.SemiBold); Text("Os dados desta versão ficam protegidos e salvos somente neste aparelho.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun QuickMetric(label: String, value: String) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary); Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }

@Composable private fun Shortcut(title: String, subtitle: String, onClick: () -> Unit) = Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) {
            Text(title.take(1), Modifier.padding(horizontal = 15.dp, vertical = 12.dp), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        TextButton(onClick = onClick) { Text("Abrir") }
    }
}

@Composable
private fun FinanceScreen(modifier: Modifier, transactions: List<MoneyTransaction>, categories: List<CategoryEntity>, onDelete: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var typeFilter by remember { mutableStateOf<TransactionType?>(null) }
    val current = YearMonth.now()
    val month = transactions.filter { YearMonth.from(Instant.ofEpochMilli(it.createdAt).atZone(ZoneId.systemDefault())) == current }
    val income = month.filter { it.type == TransactionType.INCOME }.sumOf { it.amount }
    val expense = month.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amount }
    val filtered = transactions.filter { row ->
        query.isBlank() || row.description.contains(query, ignoreCase = true) ||
            categories.firstOrNull { it.id == row.categoryId }?.name?.contains(query, ignoreCase = true) == true
    }.filter { typeFilter == null || it.type == typeFilter }
    LazyColumn(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Finanças", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text("Resumo deste mês", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) { Summary("Entradas", money(income), Modifier.weight(1f)); Summary("Saídas", money(expense), Modifier.weight(1f)) } }
        item { OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Buscar lançamentos") }, placeholder = { Text("Descrição ou categoria") }) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterButton("Todos", typeFilter == null) { typeFilter = null }
                FilterButton("Entradas", typeFilter == TransactionType.INCOME) { typeFilter = TransactionType.INCOME }
                FilterButton("Saídas", typeFilter == TransactionType.EXPENSE) { typeFilter = TransactionType.EXPENSE }
            }
        }
        item { Text("Lançamentos", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
        if (filtered.isEmpty()) item { Empty(if (transactions.isEmpty()) "Nenhum lançamento ainda" else "Nenhum resultado", if (transactions.isEmpty()) "Use o botão + para registrar uma entrada ou saída." else "Tente outra descrição ou categoria.") }
        items(filtered, key = { it.id }) { item -> TransactionItem(item, categories, onDelete) }
    }
}

@Composable
private fun FilterButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick) { Text(label) }
    else OutlinedButton(onClick = onClick) { Text(label) }
}

@Composable private fun Summary(label: String, value: String, modifier: Modifier) = Card(modifier, shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium) } }

@Composable private fun TransactionItem(item: MoneyTransaction, categories: List<CategoryEntity>, onDelete: (String) -> Unit) = Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(item.description, fontWeight = FontWeight.SemiBold); Text(categories.firstOrNull { it.id == item.categoryId }?.name ?: if (item.type == TransactionType.INCOME) "Entrada" else "Saída", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Column(horizontalAlignment = Alignment.End) { Text((if (item.type == TransactionType.INCOME) "+ " else "− ") + money(item.amount), fontWeight = FontWeight.Bold, color = if (item.type == TransactionType.INCOME) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error); TextButton(onClick = { onDelete(item.id) }) { Text("Excluir") } }
    }
}

@Composable
private fun ReportsScreen(modifier: Modifier, transactions: List<MoneyTransaction>, categories: List<CategoryEntity>) {
    val categoryNames = categories.associate { it.id to it.name }
    val expenses = transactions.filter { it.type == TransactionType.EXPENSE }
    val total = expenses.sumOf { it.amount }
    val grouped = expenses.groupBy { it.categoryId ?: "other" }
        .map { (id, rows) -> (categoryNames[id] ?: "Outros") to rows.sumOf { it.amount } }
        .sortedByDescending { it.second }
    LazyColumn(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("Relatórios", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Acompanhe para onde seu dinheiro está indo.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), shape = MaterialTheme.shapes.extraLarge) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("DESPESAS REGISTRADAS", style = MaterialTheme.typography.labelMedium)
                    Text(money(total), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("por categoria", color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }
        if (grouped.isEmpty()) item { EmptyState("Adicione despesas para visualizar seu relatório.") }
        items(grouped) { (name, value) ->
            val progress = if (total > 0) (value / total).toFloat() else 0f
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(name, fontWeight = FontWeight.SemiBold)
                    Text(money(value), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Text(message, Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TasksScreen(modifier: Modifier, tasks: List<PersonalTask>, onToggle: (String) -> Unit, onDelete: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var onlyPending by rememberSaveable { mutableStateOf(false) }
    val visibleTasks = tasks.filter { task ->
        (!onlyPending || !task.completed) && (query.isBlank() || task.title.contains(query, ignoreCase = true))
    }.sortedWith(compareBy<PersonalTask> { it.completed }.thenBy { it.dueDate ?: "9999-99-99" })
    LazyColumn(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Tarefas", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text("Acompanhe o que precisa ser feito.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Buscar tarefas") }, singleLine = true) }
        item { FilterButton(if (onlyPending) "Somente pendentes" else "Todas as tarefas", onlyPending) { onlyPending = !onlyPending } }
        if (visibleTasks.isEmpty()) item { Empty(if (tasks.isEmpty()) "Nenhuma tarefa ainda" else "Nenhum resultado", if (tasks.isEmpty()) "Use o botão + para criar sua primeira tarefa." else "Ajuste a busca ou o filtro.") }
        items(visibleTasks, key = { it.id }) { task -> Card(Modifier.fillMaxWidth()) {
            val overdue = !task.completed && task.dueDate?.let { runCatching { LocalDate.parse(it).isBefore(LocalDate.now()) }.getOrDefault(false) } == true
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(task.completed, { onToggle(task.id) })
                Column(Modifier.weight(1f)) { Text(task.title, fontWeight = FontWeight.SemiBold, textDecoration = if (task.completed) TextDecoration.LineThrough else null); task.dueDate?.let { Text(if (overdue) "Atrasada • prazo: $it" else "Prazo: $it", style = MaterialTheme.typography.bodySmall, color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) } }
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

@Composable private fun TransactionDialog(categories: List<CategoryEntity>, onDismiss: () -> Unit, onSave: (String, Double, TransactionType, String?) -> Unit) {
    var description by remember { mutableStateOf("") }; var amount by remember { mutableStateOf("") }; var type by remember { mutableStateOf(TransactionType.EXPENSE) }; var categoryId by remember { mutableStateOf<String?>(null) }; var categoryMenu by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Novo lançamento") }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(description, { description = it }, label = { Text("Descrição") }, singleLine = true)
        OutlinedTextField(amount, { amount = it.replace(',', '.') }, label = { Text("Valor") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = { type = TransactionType.INCOME }) { Text(if (type == TransactionType.INCOME) "✓ Entrada" else "Entrada") }; OutlinedButton(onClick = { type = TransactionType.EXPENSE }) { Text(if (type == TransactionType.EXPENSE) "✓ Saída" else "Saída") } }
        Box { OutlinedButton(onClick = { categoryMenu = true }) { Text(categories.firstOrNull { it.id == categoryId }?.name ?: "Escolher categoria") }; DropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) { categories.forEach { category -> DropdownMenuItem(text = { Text(category.name) }, onClick = { categoryId = category.id; categoryMenu = false }) } } }
    } }, confirmButton = { Button(onClick = { amount.toDoubleOrNull()?.let { onSave(description, it, type, categoryId) } }, enabled = description.isNotBlank() && (amount.toDoubleOrNull() ?: 0.0) > 0) { Text("Salvar") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}

@Composable
private fun PlanningScreen(modifier: Modifier, bills: List<RecurringBillEntity>, goals: List<GoalEntity>, categories: List<CategoryEntity>, onAddBill: (String, Double, Int, String?) -> Unit, onAddGoal: (String, Double, String?) -> Unit, onAddGoalClick: () -> Unit) {
    LazyColumn(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Planejamento", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text("Antecipe seus compromissos e objetivos.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("Metas", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold); TextButton(onClick = onAddGoalClick) { Text("+ Meta") } } }
        if (goals.isEmpty()) item { Empty("Nenhuma meta criada", "Crie uma meta para acompanhar seu progresso.") }
        items(goals, key = { it.id }) { goal ->
            val progress = if (goal.targetCents == 0L) 0f else (goal.currentCents.toFloat() / goal.targetCents).coerceIn(0f, 1f)
            Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(goal.title, fontWeight = FontWeight.SemiBold); Text("${(progress * 100).toInt()}%") }; LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth()); Text("${money(goal.currentCents / 100.0)} de ${money(goal.targetCents / 100.0)}", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        }
        item { Text("Contas recorrentes", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
        if (bills.isEmpty()) item { Empty("Nenhuma conta recorrente", "Use o botão + para registrar uma cobrança mensal.") }
        items(bills, key = { it.id }) { bill -> Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) { Column(Modifier.weight(1f)) { Text(bill.title, fontWeight = FontWeight.SemiBold); Text("Todo dia ${bill.dayOfMonth}", color = MaterialTheme.colorScheme.onSurfaceVariant); Text(categories.firstOrNull { it.id == bill.categoryId }?.name ?: "Sem categoria", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }; Text(money(bill.amountCents / 100.0), fontWeight = FontWeight.Bold) } } }
    }
}

@Composable private fun RecurringDialog(categories: List<CategoryEntity>, onDismiss: () -> Unit, onSave: (String, Double, Int, String?) -> Unit) {
    var title by remember { mutableStateOf("") }; var amount by remember { mutableStateOf("") }; var day by remember { mutableStateOf("1") }; var categoryId by remember { mutableStateOf<String?>(null) }; var menu by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Nova conta recorrente") }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { OutlinedTextField(title, { title = it }, label = { Text("Nome") }, singleLine = true); OutlinedTextField(amount, { amount = it.replace(',', '.') }, label = { Text("Valor mensal") }, singleLine = true); OutlinedTextField(day, { day = it.filter(Char::isDigit) }, label = { Text("Dia do mês") }, singleLine = true); Box { OutlinedButton(onClick = { menu = true }) { Text(categories.firstOrNull { it.id == categoryId }?.name ?: "Escolher categoria") }; DropdownMenu(menu, { menu = false }) { categories.forEach { category -> DropdownMenuItem(text = { Text(category.name) }, onClick = { categoryId = category.id; menu = false }) } } } } }, confirmButton = { Button(onClick = { amount.toDoubleOrNull()?.let { onSave(title, it, day.toIntOrNull() ?: 1, categoryId) } }, enabled = title.isNotBlank() && (amount.toDoubleOrNull() ?: 0.0) > 0 && (day.toIntOrNull() ?: 0) in 1..31) { Text("Salvar") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}

@Composable private fun GoalDialog(onDismiss: () -> Unit, onSave: (String, Double, String?) -> Unit) {
    var title by remember { mutableStateOf("") }; var target by remember { mutableStateOf("") }; var deadline by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Nova meta") }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { OutlinedTextField(title, { title = it }, label = { Text("Nome da meta") }, singleLine = true); OutlinedTextField(target, { target = it.replace(',', '.') }, label = { Text("Valor alvo") }, singleLine = true); OutlinedTextField(deadline, { deadline = it }, label = { Text("Prazo opcional") }, singleLine = true) } }, confirmButton = { Button(onClick = { target.toDoubleOrNull()?.let { onSave(title, it, deadline) } }, enabled = title.isNotBlank() && (target.toDoubleOrNull() ?: 0.0) > 0) { Text("Salvar") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}

@Composable private fun TaskDialog(onDismiss: () -> Unit, onSave: (String, String?) -> Unit) {
    var title by remember { mutableStateOf("") }; var date by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Nova tarefa") }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { OutlinedTextField(title, { title = it }, label = { Text("Título") }, singleLine = true); OutlinedTextField(date, { date = it }, label = { Text("Prazo opcional, ex.: 10/10") }, singleLine = true) } }, confirmButton = { Button(onClick = { onSave(title, date) }, enabled = title.isNotBlank()) { Text("Salvar") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}

private fun money(value: Double): String = NumberFormat.getCurrencyInstance(Locale("pt", "BR")).format(value)
