package com.nxuslab.dreymanager

import android.Manifest
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.biometric.BiometricPrompt
import androidx.biometric.BiometricManager
import androidx.fragment.app.FragmentActivity
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.nxuslab.dreymanager.data.MoneyTransaction
import com.nxuslab.dreymanager.data.CategoryEntity
import com.nxuslab.dreymanager.data.GoalEntity
import com.nxuslab.dreymanager.data.PersonalTask
import com.nxuslab.dreymanager.data.RecurringBillEntity
import com.nxuslab.dreymanager.data.VaultItemEntity
import com.nxuslab.dreymanager.data.InstallmentPlanEntity
import com.nxuslab.dreymanager.data.DebtEntity
import com.nxuslab.dreymanager.data.BankEntity
import com.nxuslab.dreymanager.data.SavingsJarEntity
import com.nxuslab.dreymanager.data.TransactionType
import com.nxuslab.dreymanager.update.UpdateState
import com.nxuslab.dreymanager.ui.DreyManagerTheme
import com.nxuslab.dreymanager.ui.screens.HomeScreen
import com.nxuslab.dreymanager.notifications.ReminderWorker
import com.nxuslab.dreymanager.notifications.NotificationHelper
import com.nxuslab.dreymanager.security.VaultCrypto
import java.util.concurrent.TimeUnit
import java.text.NumberFormat
import java.time.Instant
import java.time.YearMonth
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

private data class CalendarEvent(val title: String, val amount: Double? = null, val kind: String)

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
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
        setContent { DreyManagerTheme { AppUnlockGate { PersonalManagerApp() } } }
    }
}

@Composable
private fun AppUnlockGate(content: @Composable () -> Unit) {
    var stage by remember { mutableStateOf("locked") }
    var authenticationError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val activity = context as? FragmentActivity

    fun requestAuthentication() {
        val host = activity ?: return
        authenticationError = null
        BiometricPrompt(host, host.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { stage = "intro" }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) authenticationError = errString.toString()
            }
        }).authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Abrir NxusLife")
                .setSubtitle("Confirme sua digital para continuar")
                .setNegativeButtonText("Cancelar")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build(),
        )
    }

    LaunchedEffect(Unit) { requestAuthentication() }
    LaunchedEffect(stage) { if (stage == "intro") { delay(950); stage = "open" } }

    when (stage) {
        "open" -> content()
        "intro" -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AnimatedVisibility(visible = true, enter = fadeIn() + scaleIn(initialScale = 0.78f)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
                        Image(painterResource(R.drawable.nxuslife_logo), "NxusLife", Modifier.size(116.dp).padding(18.dp))
                    }
                    Text("Bem-vindo, Andrey", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
            }
        }
        else -> Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
            Card(shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Image(painterResource(R.drawable.nxuslife_logo), "NxusLife", Modifier.size(76.dp))
                    Text("Bem-vindo, Andrey", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Use sua digital para abrir o seu espaço.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    authenticationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(onClick = ::requestAuthentication, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Desbloquear com digital") }
                }
            }
        }
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
    val vaultItems by viewModel.vaultItems.collectAsStateWithLifecycle()
    val installmentPlans by viewModel.installmentPlans.collectAsStateWithLifecycle()
    val debts by viewModel.debts.collectAsStateWithLifecycle()
    val banks by viewModel.banks.collectAsStateWithLifecycle()
    val savingsJars by viewModel.savingsJars.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var transactionDialog by remember { mutableStateOf(false) }
    var taskDialog by remember { mutableStateOf(false) }
    var editingTransaction by remember { mutableStateOf<MoneyTransaction?>(null) }
    var editingTask by remember { mutableStateOf<PersonalTask?>(null) }
    var editingInstallment by remember { mutableStateOf<InstallmentPlanEntity?>(null) }
    var editingDebt by remember { mutableStateOf<DebtEntity?>(null) }
    var editingGoal by remember { mutableStateOf<GoalEntity?>(null) }
    var editingBill by remember { mutableStateOf<RecurringBillEntity?>(null) }
    var editingVaultItem by remember { mutableStateOf<VaultItemEntity?>(null) }
    var recurringDialog by remember { mutableStateOf(false) }
    var goalDialog by remember { mutableStateOf(false) }
    var moreDestination by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 8.dp, modifier = Modifier.height(80.dp)) {
                val navigationItems = listOf(
                    Triple("Início", Icons.Filled.Home, 0),
                    Triple("Finanças", Icons.Filled.AccountBalanceWallet, 1),
                    Triple("Planejar", Icons.Filled.CalendarMonth, 2),
                    Triple("Tarefas", Icons.Filled.CheckCircle, 3),
                    Triple("Mais", Icons.Filled.MoreHoriz, 4),
                )
                navigationItems.forEach { (name, icon, index) ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index; moreDestination = null },
                        icon = { Icon(icon, contentDescription = name) },
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
            if (tab == 1 || tab == 2 || tab == 3) FloatingActionButton(containerColor = MaterialTheme.colorScheme.primary, onClick = {
                if (tab == 1) transactionDialog = true else if (tab == 2) recurringDialog = true else taskDialog = true
            }) { Text("+") }
        },
    ) { padding ->
        when (tab) {
            0 -> HomeScreen(Modifier.padding(padding), transactions, tasks, recurringBills, goals, updates, { tab = 1 }, { tab = 2 }, { exportLauncher.launch("drey-manager-backup.json") }, { importLauncher.launch(arrayOf("application/json", "text/json")) })
            1 -> FinanceScreen(Modifier.padding(padding), transactions, categories, recurringBills, installmentPlans, debts, viewModel::deleteTransaction, { editingTransaction = it }, { editingInstallment = it }, viewModel::deleteInstallment, viewModel::markInstallmentPaid, { editingDebt = it }, viewModel::deleteDebt, viewModel::setDebtSettled, viewModel::addInstallment, viewModel::addDebt)
            2 -> PlanningScreen(Modifier.padding(padding), recurringBills, goals, categories, viewModel::addRecurringBill, viewModel::addGoal, viewModel::addGoalContribution, { editingGoal = it }, viewModel::setRecurringBillActive, viewModel::deleteRecurringBill, { editingBill = it }, { goalDialog = true })
            3 -> TasksScreen(Modifier.padding(padding), tasks, viewModel::toggleTask, viewModel::deleteTask, { editingTask = it })
            4 -> when (moreDestination) {
                "reports" -> ReportsScreen(Modifier.padding(padding), transactions, categories)
                "vault" -> VaultScreen(Modifier.padding(padding), vaultItems, viewModel::deleteVaultItem, viewModel::addVaultItem, { editingVaultItem = it })
                "banks" -> BanksScreen(Modifier.padding(padding), banks, viewModel::addBank, viewModel::updateBank, viewModel::deleteBank)
                "jars" -> SavingsJarsScreen(Modifier.padding(padding), savingsJars, viewModel::addSavingsJar, viewModel::updateSavingsJar, viewModel::deleteSavingsJar)
                else -> MoreScreen(Modifier.padding(padding), onReports = { moreDestination = "reports" }, onVault = { moreDestination = "vault" }, onBanks = { moreDestination = "banks" }, onJars = { moreDestination = "jars" })
            }
        }
    }
    if (transactionDialog) TransactionDialog(
        onDismiss = { transactionDialog = false },
        categories = categories,
        onSave = { description, value, type, categoryId -> viewModel.addTransaction(description, value, type, categoryId); transactionDialog = false },
    )
    editingTransaction?.let { transaction -> TransactionDialog(
        onDismiss = { editingTransaction = null }, categories = categories, existing = transaction,
        onSave = { description, value, type, categoryId -> viewModel.updateTransaction(transaction.id, description, value, type, categoryId); editingTransaction = null },
    ) }
    if (taskDialog) TaskDialog(
        onDismiss = { taskDialog = false },
        onSave = { title, due -> viewModel.addTask(title, due); taskDialog = false },
    )
    editingTask?.let { task -> TaskDialog(
        onDismiss = { editingTask = null }, existing = task,
        onSave = { title, due -> viewModel.updateTask(task.id, title, due); editingTask = null },
    ) }
    if (recurringDialog) RecurringDialog(
        onDismiss = { recurringDialog = false },
        categories = categories,
        onSave = { title, amount, day, categoryId -> viewModel.addRecurringBill(title, amount, day, categoryId); recurringDialog = false },
    )
    if (goalDialog) GoalDialog(
        onDismiss = { goalDialog = false },
        onSave = { title, target, deadline -> viewModel.addGoal(title, target, deadline); goalDialog = false },
    )
    editingInstallment?.let { plan -> InstallmentDialog(onDismiss = { editingInstallment = null }, existing = plan, onSave = { description, total, count, firstDue -> viewModel.updateInstallment(plan, description, total, count, firstDue); editingInstallment = null }) }
    editingDebt?.let { debt -> DebtDialog(onDismiss = { editingDebt = null }, existing = debt, onSave = { person, description, amount, due -> viewModel.updateDebt(debt, person, description, amount, due); editingDebt = null }) }
    editingGoal?.let { goal -> GoalDialog(onDismiss = { editingGoal = null }, existing = goal, onSave = { title, target, deadline -> viewModel.updateGoal(goal, title, target, deadline); editingGoal = null }) }
    editingBill?.let { bill -> RecurringDialog(categories = categories, onDismiss = { editingBill = null }, existing = bill, onSave = { title, amount, day, categoryId -> viewModel.updateRecurringBill(bill, title, amount, day, categoryId); editingBill = null }) }
    editingVaultItem?.let { item -> VaultItemDialog(onDismiss = { editingVaultItem = null }, existing = item, onSave = { title, username, secret -> viewModel.updateVaultItem(item, title, username, secret, null); editingVaultItem = null }) }
}

/*private fun HomeScreen(modifier: Modifier, transactions: List<MoneyTransaction>, tasks: List<PersonalTask>, bills: List<RecurringBillEntity>, goals: List<GoalEntity>, update: UpdateState, openFinance: () -> Unit, openTasks: () -> Unit, exportData: () -> Unit, importData: () -> Unit) {
    val balance = transactions.sumOf { if (it.type == TransactionType.INCOME) it.amount else -it.amount }
    val income = transactions.filter { it.type == TransactionType.INCOME }.sumOf { it.amount }
    val expense = transactions.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amount }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp), contentPadding = PaddingValues(top = 24.dp, bottom = 112.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) { Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) { Image(painterResource(R.drawable.nxuslife_logo), contentDescription = "Logo NxusLife", modifier = Modifier.size(52.dp)); Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) { Text("Olá!", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.SemiBold); Text("Seu dia, no controle.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("Uma visão clara da sua vida", color = MaterialTheme.colorScheme.onSurfaceVariant) } } } }
        if (update is UpdateState.Available) item { UpdateNotice(update) }
        item { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = MaterialTheme.shapes.extraLarge) { Column(Modifier.padding(26.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("SALDO DISPONÍVEL", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer); Text("VISÃO GERAL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer) }; Text(money(balance), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold); Text("Seu panorama financeiro em um só lugar", color = MaterialTheme.colorScheme.onPrimaryContainer); LinearProgressIndicator(progress = { if (income > 0) (balance / income).coerceIn(0.0, 1.0).toFloat() else 0f }, Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondary, trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.18f)); HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Column { Text("Entradas", style = MaterialTheme.typography.labelSmall); Text(money(income), fontWeight = FontWeight.SemiBold) }; Column(horizontalAlignment = Alignment.End) { Text("Saídas", style = MaterialTheme.typography.labelSmall); Text(money(expense), fontWeight = FontWeight.SemiBold) } } } } }
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
}*/

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
private fun FinanceScreen(modifier: Modifier, transactions: List<MoneyTransaction>, categories: List<CategoryEntity>, recurringBills: List<RecurringBillEntity>, installments: List<InstallmentPlanEntity>, debts: List<DebtEntity>, onDelete: (String) -> Unit, onEdit: (MoneyTransaction) -> Unit, onEditInstallment: (InstallmentPlanEntity) -> Unit, onDeleteInstallment: (String) -> Unit, onMarkInstallmentPaid: (String) -> Unit, onEditDebt: (DebtEntity) -> Unit, onDeleteDebt: (String) -> Unit, onSetDebtSettled: (String, Boolean) -> Unit, onAddInstallment: (String, Double, Int, String) -> Unit, onAddDebt: (String, String, Double, String?) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var typeFilter by remember { mutableStateOf<TransactionType?>(null) }
    var section by rememberSaveable { mutableIntStateOf(0) }
    var installmentDialog by remember { mutableStateOf(false) }
    var debtDialog by remember { mutableStateOf(false) }
    val current = YearMonth.now()
    var selectedMonth by rememberSaveable { mutableStateOf(current.toString()) }
    val displayedMonth = YearMonth.parse(selectedMonth)
    val isFuture = displayedMonth.isAfter(current)
    val month = transactions.filter { YearMonth.from(Instant.ofEpochMilli(it.createdAt).atZone(ZoneId.systemDefault())) == displayedMonth }
    val income = month.filter { it.type == TransactionType.INCOME }.sumOf { it.amount }
    val expense = month.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amount }
    val projectedBills = if (isFuture) recurringBills.sumOf { it.amountCents / 100.0 } else 0.0
    val projectedInstallments = if (isFuture) installments.filter { plan ->
        val start = runCatching { YearMonth.parse(plan.startMonth) }.getOrNull()
        val end = runCatching { YearMonth.parse(plan.endMonth) }.getOrNull()
        start != null && end != null && displayedMonth in start..end
    }.sumOf { it.installmentCents / 100.0 } else 0.0
    val projectedExpense = projectedBills + projectedInstallments
    val eventsByDay = remember(displayedMonth, transactions, recurringBills, installments, debts) {
        buildMap<Int, MutableList<CalendarEvent>> {
            transactions.filter { YearMonth.from(Instant.ofEpochMilli(it.createdAt).atZone(ZoneId.systemDefault())) == displayedMonth }.forEach { transaction ->
                val day = Instant.ofEpochMilli(transaction.createdAt).atZone(ZoneId.systemDefault()).dayOfMonth
                getOrPut(day) { mutableListOf() }.add(CalendarEvent(transaction.description, transaction.amount, if (transaction.type == TransactionType.INCOME) "Entrada" else "Saída"))
            }
            recurringBills.forEach { bill -> if (bill.dayOfMonth <= displayedMonth.lengthOfMonth()) getOrPut(bill.dayOfMonth) { mutableListOf() }.add(CalendarEvent(bill.title, bill.amountCents / 100.0, "Conta")) }
            installments.filter { plan ->
                val start = runCatching { YearMonth.parse(plan.startMonth) }.getOrNull(); val end = runCatching { YearMonth.parse(plan.endMonth) }.getOrNull()
                start != null && end != null && displayedMonth in start..end && plan.dueDay <= displayedMonth.lengthOfMonth()
            }.forEach { plan -> getOrPut(plan.dueDay) { mutableListOf() }.add(CalendarEvent("${plan.description} • parcela", plan.installmentCents / 100.0, "Parcela")) }
            debts.filter { !it.settled && it.dueDate?.let { date -> runCatching { YearMonth.from(LocalDate.parse(date)) == displayedMonth }.getOrDefault(false) } == true }.forEach { debt ->
                val day = LocalDate.parse(debt.dueDate).dayOfMonth
                getOrPut(day) { mutableListOf() }.add(CalendarEvent("Receber de ${debt.person}", debt.amountCents / 100.0, "A receber"))
            }
        }
    }
    var selectedDay by rememberSaveable(selectedMonth) { mutableIntStateOf(LocalDate.now().takeIf { YearMonth.from(it) == displayedMonth }?.dayOfMonth ?: 1) }
    val filtered = (if (isFuture) emptyList() else month).filter { row ->
        query.isBlank() || row.description.contains(query, ignoreCase = true) ||
            categories.firstOrNull { it.id == row.categoryId }?.name?.contains(query, ignoreCase = true) == true
    }.filter { typeFilter == null || it.type == typeFilter }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp), contentPadding = PaddingValues(top = 24.dp, bottom = 112.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Finanças", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text("Seu dinheiro organizado, sem complicação.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) { Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { TextButton(onClick = { selectedMonth = displayedMonth.minusMonths(1).toString() }, modifier = Modifier.height(44.dp)) { Text("‹") }; Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(displayedMonth.month.getDisplayName(TextStyle.FULL, Locale("pt", "BR")).replaceFirstChar { it.titlecase(Locale("pt", "BR")) }, fontWeight = FontWeight.Bold); Text(displayedMonth.year.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }; TextButton(onClick = { selectedMonth = displayedMonth.plusMonths(1).toString() }, modifier = Modifier.height(44.dp)) { Text("›") } } } }
        item { FinanceCalendar(displayedMonth, eventsByDay, selectedDay, onDaySelected = { selectedDay = it }) }
        eventsByDay[selectedDay]?.takeIf { it.isNotEmpty() }?.let { dayEvents ->
            item {
                Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Dia $selectedDay", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        dayEvents.forEach { event ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text(event.title, fontWeight = FontWeight.SemiBold)
                                    Text(event.kind, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                event.amount?.let { amount ->
                                    Text(money(amount), fontWeight = FontWeight.Bold, color = if (event.kind == "Entrada" || event.kind == "A receber") MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
        item { ScrollableTabRow(selectedTabIndex = section, edgePadding = 0.dp) { listOf("Lançamentos", "Parcelamentos", "A receber").forEachIndexed { index, title -> Tab(selected = section == index, onClick = { section = index }, text = { Text(title) }) } } }
        if (section == 1) {
            item { Button(onClick = { installmentDialog = true }, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Adicionar parcelamento") } }
            if (installments.isEmpty()) item { Empty("Nenhum parcelamento", "Cadastre compras parceladas para acompanhar o fim.") }
            items(installments, key = { it.id }) { plan -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(plan.description, Modifier.weight(1f), fontWeight = FontWeight.SemiBold); Text(money(plan.installmentCents / 100.0), fontWeight = FontWeight.Bold) }; Text("${plan.paidCount}/${plan.installmentCount} pagas • vence dia ${plan.dueDay}", color = MaterialTheme.colorScheme.onSurfaceVariant); Text("De ${plan.startMonth} até ${plan.endMonth}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); LinearProgressIndicator(progress = { plan.paidCount.toFloat() / plan.installmentCount }, modifier = Modifier.fillMaxWidth()); Button(onClick = { onMarkInstallmentPaid(plan.id) }, enabled = plan.paidCount < plan.installmentCount, modifier = Modifier.fillMaxWidth().height(46.dp)) { Text("Marcar parcela paga") }; Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) { OutlinedButton(onClick = { onEditInstallment(plan) }, modifier = Modifier.weight(1f).height(44.dp)) { Text("Editar") }; TextButton(onClick = { onDeleteInstallment(plan.id) }, modifier = Modifier.height(44.dp)) { Text("Excluir") } } } } }
        } else if (section == 2) {
            item { Button(onClick = { debtDialog = true }, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Adicionar valor a receber") } }
            if (debts.isEmpty()) item { Empty("Nada a receber", "Cadastre quem está devendo e o motivo.") }
            items(debts, key = { it.id }) { debt -> Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(debt.person, fontWeight = FontWeight.SemiBold); Text(debt.description, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(if (debt.settled) "Recebido" else debt.dueDate?.let { "Vencimento: $it" } ?: "Sem vencimento", style = MaterialTheme.typography.bodySmall) }; Column(horizontalAlignment = Alignment.End) { Text(money(debt.amountCents / 100.0), color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold); TextButton(onClick = { onSetDebtSettled(debt.id, !debt.settled) }) { Text(if (debt.settled) "Reabrir" else "Marcar recebido") }; TextButton(onClick = { onEditDebt(debt) }) { Text("Editar") }; TextButton(onClick = { onDeleteDebt(debt.id) }) { Text("Excluir") } } } } }
        } else {
        item { Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(if (isFuture) "PREVISÃO DO MÊS" else "RESULTADO DO MÊS", style = MaterialTheme.typography.labelMedium); Text(money(if (isFuture) -projectedExpense else income - expense), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Column { Text(if (isFuture) "Contas" else "Entradas", style = MaterialTheme.typography.labelSmall); Text(money(if (isFuture) projectedBills else income), fontWeight = FontWeight.SemiBold) }; Column(horizontalAlignment = Alignment.End) { Text(if (isFuture) "Parcelas" else "Saídas", style = MaterialTheme.typography.labelSmall); Text(money(if (isFuture) projectedInstallments else expense), fontWeight = FontWeight.SemiBold) } }; if (isFuture) Text("Valores previstos; lançamentos reais aparecerão quando forem registrados.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer) } } }
        item { OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Buscar lançamentos") }, placeholder = { Text("Descrição ou categoria") }) }
        item {
            ScrollableTabRow(selectedTabIndex = when (typeFilter) { null -> 0; TransactionType.INCOME -> 1; TransactionType.EXPENSE -> 2 }, edgePadding = 0.dp) { listOf("Todos", "Entradas", "Saídas").forEachIndexed { index, title -> Tab(selected = (index == 0 && typeFilter == null) || (index == 1 && typeFilter == TransactionType.INCOME) || (index == 2 && typeFilter == TransactionType.EXPENSE), onClick = { typeFilter = when (index) { 1 -> TransactionType.INCOME; 2 -> TransactionType.EXPENSE; else -> null } }, text = { Text(title) }) } }
        }
        item { Text("Lançamentos", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
        if (filtered.isEmpty()) item { Empty(if (transactions.isEmpty()) "Nenhum lançamento ainda" else "Nenhum resultado", if (transactions.isEmpty()) "Use o botão + para registrar uma entrada ou saída." else "Tente outra descrição ou categoria.") }
        items(filtered, key = { it.id }) { item -> TransactionItem(item, categories, onDelete, onEdit) }
        }
    }
    if (installmentDialog) InstallmentDialog(onDismiss = { installmentDialog = false }, onSave = { d, total, count, start -> onAddInstallment(d, total, count, start); installmentDialog = false })
    if (debtDialog) DebtDialog(onDismiss = { debtDialog = false }, onSave = { person, description, amount, due -> onAddDebt(person, description, amount, due); debtDialog = false })
}

@Composable
private fun FilterButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick) { Text(label) }
    else OutlinedButton(onClick = onClick) { Text(label) }
}

@Composable
private fun FinanceCalendar(month: YearMonth, eventsByDay: Map<Int, List<CalendarEvent>>, selectedDay: Int, onDaySelected: (Int) -> Unit) {
    val leadingBlanks = month.atDay(1).dayOfWeek.value - 1
    val cells: List<Int?> = List(leadingBlanks) { null } + (1..month.lengthOfMonth()).toList()
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Calendário financeiro", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth()) { listOf("SEG", "TER", "QUA", "QUI", "SEX", "SÁB", "DOM").forEach { label -> Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center) } }
            cells.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    week.forEach { day ->
                        if (day == null) Spacer(Modifier.weight(1f).aspectRatio(1f))
                        else {
                            val hasEvents = eventsByDay[day]?.isNotEmpty() == true
                            Surface(
                                modifier = Modifier.weight(1f).aspectRatio(1f).padding(2.dp).clickable { onDaySelected(day) },
                                shape = MaterialTheme.shapes.medium,
                                color = when {
                                    day == selectedDay -> MaterialTheme.colorScheme.primary
                                    hasEvents -> MaterialTheme.colorScheme.primaryContainer
                                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                },
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    Text(day.toString(), style = MaterialTheme.typography.labelMedium, color = if (day == selectedDay) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                                    if (hasEvents) Text("•", color = if (day == selectedDay) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    repeat(7 - week.size) { Spacer(Modifier.weight(1f).aspectRatio(1f)) }
                }
            }
            Text("• Dia com movimentação, conta, parcela ou valor a receber", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InstallmentDialog(onDismiss: () -> Unit, onSave: (String, Double, Int, String) -> Unit, existing: InstallmentPlanEntity? = null) {
    var description by remember(existing?.id) { mutableStateOf(existing?.description.orEmpty()) }; var total by remember(existing?.id) { mutableStateOf(existing?.totalCents?.div(100.0)?.toString().orEmpty()) }; var count by remember(existing?.id) { mutableStateOf(existing?.installmentCount?.toString().orEmpty()) }; var firstDueDate by remember(existing?.id) { mutableStateOf(existing?.let { "${it.startMonth}-${it.dueDay.toString().padStart(2, '0')}" } ?: LocalDate.now().toString()) }
    val parsedDate = runCatching { LocalDate.parse(firstDueDate) }.getOrNull()
    val end = parsedDate?.plusMonths((count.toIntOrNull() ?: 1).coerceAtLeast(1) - 1L)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (existing == null) "Novo parcelamento" else "Editar parcelamento") }, text = { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { OutlinedTextField(description, { description = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Descrição") }, singleLine = true); OutlinedTextField(total, { total = it.replace(',', '.') }, modifier = Modifier.fillMaxWidth(), label = { Text("Valor total") }, singleLine = true); OutlinedTextField(count, { count = it.filter(Char::isDigit) }, modifier = Modifier.fillMaxWidth(), label = { Text("Quantidade de parcelas") }, singleLine = true); DateField("Primeiro vencimento", firstDueDate) { firstDueDate = it }; val parcel = (total.toDoubleOrNull() ?: 0.0) / (count.toIntOrNull() ?: 1); Text("Parcela estimada: ${money(parcel)}", color = MaterialTheme.colorScheme.onSurfaceVariant); end?.let { Text("Última parcela: ${it.dayOfMonth}/${it.monthValue.toString().padStart(2, '0')}/${it.year}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) } } }, confirmButton = { Button(onClick = { onSave(description, total.toDoubleOrNull() ?: 0.0, count.toIntOrNull() ?: 0, firstDueDate) }, modifier = Modifier.height(48.dp), enabled = description.isNotBlank() && (total.toDoubleOrNull() ?: 0.0) > 0 && (count.toIntOrNull() ?: 0) > 0 && parsedDate != null) { Text("Salvar") } }, dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.height(48.dp)) { Text("Cancelar") } })
}

@Composable
private fun DebtDialog(onDismiss: () -> Unit, onSave: (String, String, Double, String?) -> Unit, existing: DebtEntity? = null) {
    var person by remember(existing?.id) { mutableStateOf(existing?.person.orEmpty()) }; var description by remember(existing?.id) { mutableStateOf(existing?.description.orEmpty()) }; var amount by remember(existing?.id) { mutableStateOf(existing?.amountCents?.div(100.0)?.toString().orEmpty()) }; var due by remember(existing?.id) { mutableStateOf(existing?.dueDate.orEmpty()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (existing == null) "Valor a receber" else "Editar valor a receber") }, text = { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { OutlinedTextField(person, { person = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Pessoa") }, singleLine = true); OutlinedTextField(description, { description = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Descrição") }, singleLine = true); OutlinedTextField(amount, { amount = it.replace(',', '.') }, modifier = Modifier.fillMaxWidth(), label = { Text("Valor") }, singleLine = true); DateField("Vencimento opcional", due) { due = it } } }, confirmButton = { Button(onClick = { onSave(person, description, amount.toDoubleOrNull() ?: 0.0, due) }, modifier = Modifier.height(48.dp), enabled = person.isNotBlank() && description.isNotBlank() && (amount.toDoubleOrNull() ?: 0.0) > 0) { Text("Salvar") } }, dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.height(48.dp)) { Text("Cancelar") } })
}

@Composable private fun Summary(label: String, value: String, modifier: Modifier) = Card(modifier, shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium) } }

@Composable private fun TransactionItem(item: MoneyTransaction, categories: List<CategoryEntity>, onDelete: (String) -> Unit, onEdit: (MoneyTransaction) -> Unit) = Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Column(Modifier.weight(1f)) { Text(item.description, fontWeight = FontWeight.SemiBold); Text(categories.firstOrNull { it.id == item.categoryId }?.name ?: if (item.type == TransactionType.INCOME) "Entrada" else "Saída", color = MaterialTheme.colorScheme.onSurfaceVariant) }; Text((if (item.type == TransactionType.INCOME) "+ " else "− ") + money(item.amount), fontWeight = FontWeight.Bold, color = if (item.type == TransactionType.INCOME) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) { OutlinedButton(onClick = { onEdit(item) }, modifier = Modifier.weight(1f).height(42.dp)) { Text("Editar") }; TextButton(onClick = { onDelete(item.id) }, modifier = Modifier.height(42.dp)) { Text("Excluir") } }
    }
}

@Composable
private fun ReportsScreen(modifier: Modifier, transactions: List<MoneyTransaction>, categories: List<CategoryEntity>) {
    val categoryNames = categories.associate { it.id to it.name }
    val incomeTotal = transactions.filter { it.type == TransactionType.INCOME }.sumOf { it.amount }
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
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { Summary("Entradas", money(incomeTotal), Modifier.weight(1f)); Summary("Resultado", money(incomeTotal - total), Modifier.weight(1f)) } }
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
private fun BanksScreen(modifier: Modifier, banks: List<BankEntity>, onAdd: (String, Double) -> Unit, onUpdate: (BankEntity, String, Double) -> Unit, onDelete: (String) -> Unit) {
    var dialog by remember { mutableStateOf(false) }; var editing by remember { mutableStateOf<BankEntity?>(null) }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp), contentPadding = PaddingValues(top = 24.dp, bottom = 112.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Bancos", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text("Saldos que você escolheu acompanhar.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) { Column(Modifier.padding(20.dp)) { Text("TOTAL CADASTRADO", style = MaterialTheme.typography.labelMedium); Text(money(banks.sumOf { it.balanceCents } / 100.0), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) } } }
        item { Button(onClick = { dialog = true }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Adicionar banco") } }
        if (banks.isEmpty()) item { Empty("Nenhum banco cadastrado", "Adicione os bancos que deseja acompanhar.") }
        items(banks, key = { it.id }) { bank -> Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) { Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(bank.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); Text("Saldo informado", color = MaterialTheme.colorScheme.onSurfaceVariant) }; Column(horizontalAlignment = Alignment.End) { Text(money(bank.balanceCents / 100.0), fontWeight = FontWeight.Bold); TextButton(onClick = { editing = bank }) { Text("Editar") }; TextButton(onClick = { onDelete(bank.id) }) { Text("Excluir") } } } } }
    }
    if (dialog) BankDialog(onDismiss = { dialog = false }, onSave = { name, balance -> onAdd(name, balance); dialog = false })
    editing?.let { bank -> BankDialog(existing = bank, onDismiss = { editing = null }, onSave = { name, balance -> onUpdate(bank, name, balance); editing = null }) }
}

@Composable
private fun SavingsJarsScreen(modifier: Modifier, jars: List<SavingsJarEntity>, onAdd: (String, Double, Double?) -> Unit, onUpdate: (SavingsJarEntity, String, Double, Double?) -> Unit, onDelete: (String) -> Unit) {
    var dialog by remember { mutableStateOf(false) }; var editing by remember { mutableStateOf<SavingsJarEntity?>(null) }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp), contentPadding = PaddingValues(top = 24.dp, bottom = 112.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Cofrinhos", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text("Reservas separadas para os seus objetivos.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) { Column(Modifier.padding(20.dp)) { Text("TOTAL GUARDADO", style = MaterialTheme.typography.labelMedium); Text(money(jars.sumOf { it.currentCents } / 100.0), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) } } }
        item { Button(onClick = { dialog = true }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Adicionar cofrinho") } }
        if (jars.isEmpty()) item { Empty("Nenhum cofrinho criado", "Crie reservas para seus planos e objetivos.") }
        items(jars, key = { it.id }) { jar -> val progress = jar.targetCents?.takeIf { it > 0 }?.let { (jar.currentCents.toFloat() / it).coerceIn(0f, 1f) }; Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(jar.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); Text(money(jar.currentCents / 100.0), fontWeight = FontWeight.Bold) }; progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()); Text("Meta: ${money((jar.targetCents ?: 0) / 100.0)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Row { TextButton(onClick = { editing = jar }) { Text("Editar") }; TextButton(onClick = { onDelete(jar.id) }) { Text("Excluir") } } } } }
    }
    if (dialog) SavingsJarDialog(onDismiss = { dialog = false }, onSave = { name, current, target -> onAdd(name, current, target); dialog = false })
    editing?.let { jar -> SavingsJarDialog(existing = jar, onDismiss = { editing = null }, onSave = { name, current, target -> onUpdate(jar, name, current, target); editing = null }) }
}

@Composable private fun BankDialog(onDismiss: () -> Unit, onSave: (String, Double) -> Unit, existing: BankEntity? = null) {
    var name by remember(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }; var balance by remember(existing?.id) { mutableStateOf(existing?.balanceCents?.div(100.0)?.toString().orEmpty()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (existing == null) "Novo banco" else "Editar banco") }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Nome do banco") }, singleLine = true); OutlinedTextField(balance, { balance = it.replace(',', '.') }, modifier = Modifier.fillMaxWidth(), label = { Text("Saldo atual") }, singleLine = true) } }, confirmButton = { Button(onClick = { onSave(name, balance.toDoubleOrNull() ?: 0.0) }, enabled = name.isNotBlank()) { Text("Salvar") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}

@Composable private fun SavingsJarDialog(onDismiss: () -> Unit, onSave: (String, Double, Double?) -> Unit, existing: SavingsJarEntity? = null) {
    var name by remember(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }; var current by remember(existing?.id) { mutableStateOf(existing?.currentCents?.div(100.0)?.toString().orEmpty()) }; var target by remember(existing?.id) { mutableStateOf(existing?.targetCents?.div(100.0)?.toString().orEmpty()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (existing == null) "Novo cofrinho" else "Editar cofrinho") }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Nome") }, singleLine = true); OutlinedTextField(current, { current = it.replace(',', '.') }, modifier = Modifier.fillMaxWidth(), label = { Text("Valor guardado") }, singleLine = true); OutlinedTextField(target, { target = it.replace(',', '.') }, modifier = Modifier.fillMaxWidth(), label = { Text("Meta opcional") }, singleLine = true) } }, confirmButton = { Button(onClick = { onSave(name, current.toDoubleOrNull() ?: 0.0, target.toDoubleOrNull()) }, enabled = name.isNotBlank() && (current.toDoubleOrNull() ?: 0.0) >= 0) { Text("Salvar") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}

@Composable
private fun MoreScreen(modifier: Modifier, onReports: () -> Unit, onVault: () -> Unit, onBanks: () -> Unit, onJars: () -> Unit) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 24.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("Mais", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Ferramentas para cuidar da sua vida em um só lugar.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { MoreActionCard("Relatórios", "Entenda seus gastos por categoria", Icons.Filled.Assessment, onReports) }
        item { MoreActionCard("Bancos", "Cadastre e acompanhe seus saldos", Icons.Filled.AccountBalanceWallet, onBanks) }
        item { MoreActionCard("Cofrinhos", "Acompanhe suas reservas separadas", Icons.Filled.AccountBalanceWallet, onJars) }
        item { MoreActionCard("Cofre", "Senhas e informações protegidas", Icons.Filled.Lock, onVault) }
    }
}

@Composable
private fun MoreActionCard(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large) {
                Icon(icon, contentDescription = null, modifier = Modifier.padding(14.dp).size(26.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun VaultScreen(modifier: Modifier, items: List<VaultItemEntity>, onDelete: (String) -> Unit, onAdd: (String, String, String, String?) -> Unit, onEdit: (VaultItemEntity) -> Unit) {
    var unlocked by remember { mutableStateOf(false) }
    var addDialog by remember { mutableStateOf(false) }
    var revealedSecret by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    LazyColumn(modifier.fillMaxSize().padding(20.dp), contentPadding = PaddingValues(bottom = 92.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Cofre", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text("Seus dados sensíveis ficam protegidos no aparelho.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (!unlocked) item { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text("Cofre bloqueado", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text("Use biometria ou o bloqueio do aparelho para visualizar seus itens."); Button(onClick = { activity?.let { host -> val prompt = BiometricPrompt(host, host.mainExecutor, object : BiometricPrompt.AuthenticationCallback() { override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { unlocked = true } }); prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Desbloquear NxusLife").setSubtitle("Confirme sua identidade para abrir o cofre").setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL).build()) } }) { Text("Desbloquear cofre") } } } }
        else { item { Button(onClick = { addDialog = true }) { Text("Adicionar item seguro") } }; if (items.isEmpty()) item { Empty("Cofre vazio", "Adicione sua primeira senha ou anotação segura.") }; items(items, key = { it.id }) { item -> Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(item.title, fontWeight = FontWeight.SemiBold); Text(item.username, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Column(horizontalAlignment = Alignment.End) { TextButton(onClick = { revealedSecret = runCatching { VaultCrypto.decrypt(item.encryptedSecret) }.getOrNull() }) { Text("Ver") }; TextButton(onClick = { onEdit(item) }) { Text("Editar") }; TextButton(onClick = { onDelete(item.id) }) { Text("Excluir") } } } } } }
    }
    if (addDialog) VaultItemDialog(onDismiss = { addDialog = false }, onSave = { title, username, secret -> onAdd(title, username, secret, null); addDialog = false })
    revealedSecret?.let { secret -> AlertDialog(onDismissRequest = { revealedSecret = null }, title = { Text("Segredo") }, text = { Text(secret) }, confirmButton = { TextButton(onClick = { revealedSecret = null }) { Text("Fechar") } }) }
}

@Composable
private fun VaultItemDialog(onDismiss: () -> Unit, onSave: (String, String, String) -> Unit, existing: VaultItemEntity? = null) {
    var title by remember(existing?.id) { mutableStateOf(existing?.title.orEmpty()) }; var username by remember(existing?.id) { mutableStateOf(existing?.username.orEmpty()) }; var secret by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (existing == null) "Novo item seguro" else "Editar item seguro") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(title, { title = it }, label = { Text("Nome") }, singleLine = true); OutlinedTextField(username, { username = it }, label = { Text("Usuário") }, singleLine = true); OutlinedTextField(secret, { secret = it }, label = { Text(if (existing == null) "Senha ou segredo" else "Nova senha (opcional)") }, visualTransformation = PasswordVisualTransformation(), singleLine = true) } }, confirmButton = { Button(onClick = { onSave(title, username, secret) }, enabled = title.isNotBlank() && (existing != null || secret.isNotBlank())) { Text("Salvar") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}

@Composable
private fun TasksScreen(modifier: Modifier, tasks: List<PersonalTask>, onToggle: (String) -> Unit, onDelete: (String) -> Unit, onEdit: (PersonalTask) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var onlyPending by rememberSaveable { mutableStateOf(false) }
    val visibleTasks = tasks.filter { task ->
        (!onlyPending || !task.completed) && (query.isBlank() || task.title.contains(query, ignoreCase = true))
    }.sortedWith(compareBy<PersonalTask> { it.completed }.thenBy { it.dueDate ?: "9999-99-99" })
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp), contentPadding = PaddingValues(top = 24.dp, bottom = 112.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Tarefas", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text("Acompanhe o que precisa ser feito.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { Summary("Pendentes", tasks.count { !it.completed }.toString(), Modifier.weight(1f)); Summary("Concluídas", tasks.count { it.completed }.toString(), Modifier.weight(1f)) } }
        item { OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Buscar tarefas") }, singleLine = true) }
        item { FilterButton(if (onlyPending) "Somente pendentes" else "Todas as tarefas", onlyPending) { onlyPending = !onlyPending } }
        if (visibleTasks.isEmpty()) item { Empty(if (tasks.isEmpty()) "Nenhuma tarefa ainda" else "Nenhum resultado", if (tasks.isEmpty()) "Use o botão + para criar sua primeira tarefa." else "Ajuste a busca ou o filtro.") }
        items(visibleTasks, key = { it.id }) { task -> Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(containerColor = if (task.completed) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)) {
            val overdue = !task.completed && task.dueDate?.let { runCatching { LocalDate.parse(it).isBefore(LocalDate.now()) }.getOrDefault(false) } == true
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(task.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textDecoration = if (task.completed) TextDecoration.LineThrough else null)
                task.dueDate?.let { Text(if (overdue) "Atrasada • prazo: $it" else "Prazo: $it", style = MaterialTheme.typography.bodyMedium, color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                Button(onClick = { onToggle(task.id) }, modifier = Modifier.fillMaxWidth().height(46.dp)) { Text(if (task.completed) "Reabrir tarefa" else "Concluir tarefa") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) { OutlinedButton(onClick = { onEdit(task) }, modifier = Modifier.weight(1f).height(44.dp)) { Text("Editar") }; TextButton(onClick = { onDelete(task.id) }, modifier = Modifier.height(44.dp)) { Text("Excluir") } }
            }
        } }
    }
}

@Composable private fun Empty(title: String, body: String) = Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp)) { Text(title, fontWeight = FontWeight.SemiBold); Text(body) } }

/*@Composable private fun UpdateNotice(update: UpdateState.Available) {
    val context = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Atualização disponível: ${update.manifest.versionName}", fontWeight = FontWeight.SemiBold); Text(update.manifest.message)
        Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.manifest.downloadUrl))) }) { Text("Ver atualização") }
    } }
}*/

@Composable private fun TransactionDialog(categories: List<CategoryEntity>, onDismiss: () -> Unit, onSave: (String, Double, TransactionType, String?) -> Unit, existing: MoneyTransaction? = null) {
    var description by remember(existing?.id) { mutableStateOf(existing?.description.orEmpty()) }; var amount by remember(existing?.id) { mutableStateOf(existing?.amount?.toString().orEmpty()) }; var type by remember(existing?.id) { mutableStateOf(existing?.type ?: TransactionType.EXPENSE) }; var categoryId by remember(existing?.id) { mutableStateOf(existing?.categoryId) }; var categoryMenu by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (existing == null) "Novo lançamento" else "Editar lançamento") }, text = { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(description, { description = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Descrição") }, singleLine = true)
        OutlinedTextField(amount, { amount = it.replace(',', '.') }, modifier = Modifier.fillMaxWidth(), label = { Text("Valor") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = { type = TransactionType.INCOME }) { Text(if (type == TransactionType.INCOME) "✓ Entrada" else "Entrada") }; OutlinedButton(onClick = { type = TransactionType.EXPENSE }) { Text(if (type == TransactionType.EXPENSE) "✓ Saída" else "Saída") } }
        Box { OutlinedButton(onClick = { categoryMenu = true }) { Text(categories.firstOrNull { it.id == categoryId }?.name ?: "Escolher categoria") }; DropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) { categories.forEach { category -> DropdownMenuItem(text = { Text(category.name) }, onClick = { categoryId = category.id; categoryMenu = false }) } } }
    } }, confirmButton = { Button(onClick = { amount.toDoubleOrNull()?.let { onSave(description, it, type, categoryId) } }, modifier = Modifier.height(48.dp), enabled = description.isNotBlank() && (amount.toDoubleOrNull() ?: 0.0) > 0) { Text("Salvar") } }, dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.height(48.dp)) { Text("Cancelar") } })
}

@Composable
private fun PlanningScreen(modifier: Modifier, bills: List<RecurringBillEntity>, goals: List<GoalEntity>, categories: List<CategoryEntity>, onAddBill: (String, Double, Int, String?) -> Unit, onAddGoal: (String, Double, String?) -> Unit, onAddContribution: (String, Double) -> Unit, onEditGoal: (GoalEntity) -> Unit, onSetBillActive: (String, Boolean) -> Unit, onDeleteBill: (String) -> Unit, onEditBill: (RecurringBillEntity) -> Unit, onAddGoalClick: () -> Unit) {
    var contributionGoalId by remember { mutableStateOf<String?>(null) }
    val recurringTotal = bills.sumOf { it.amountCents } / 100.0
    val goalTotal = goals.sumOf { it.targetCents } / 100.0
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp), contentPadding = PaddingValues(top = 24.dp, bottom = 112.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Planejamento", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text("Antecipe seus compromissos e objetivos.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { Summary("Contas/mês", money(recurringTotal), Modifier.weight(1f)); Summary("Metas", money(goalTotal), Modifier.weight(1f)) } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("Metas", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold); TextButton(onClick = onAddGoalClick) { Text("+ Meta") } } }
        if (goals.isEmpty()) item { Empty("Nenhuma meta criada", "Crie uma meta para acompanhar seu progresso.") }
        items(goals, key = { it.id }) { goal ->
            val progress = if (goal.targetCents == 0L) 0f else (goal.currentCents.toFloat() / goal.targetCents).coerceIn(0f, 1f)
            Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(goal.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); Text("${(progress * 100).toInt()}%", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }; LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth()); Text("${money(goal.currentCents / 100.0)} de ${money(goal.targetCents / 100.0)}", color = MaterialTheme.colorScheme.onSurfaceVariant); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) { Button(onClick = { contributionGoalId = goal.id }, modifier = Modifier.weight(1f).height(46.dp), enabled = goal.currentCents < goal.targetCents) { Text("Adicionar valor") }; OutlinedButton(onClick = { onEditGoal(goal) }, modifier = Modifier.height(46.dp)) { Text("Editar") } } } }
        }
        item { Text("Contas recorrentes", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
        if (bills.isEmpty()) item { Empty("Nenhuma conta recorrente", "Use o botão + para registrar uma cobrança mensal.") }
        items(bills, key = { it.id }) { bill -> Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Column(Modifier.weight(1f)) { Text(bill.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); Text("Vencimento todo dia ${bill.dayOfMonth}", color = MaterialTheme.colorScheme.onSurfaceVariant); Text(categories.firstOrNull { it.id == bill.categoryId }?.name ?: "Sem categoria", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }; Text(money(bill.amountCents / 100.0), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium) }; OutlinedButton(onClick = { onSetBillActive(bill.id, false) }, modifier = Modifier.fillMaxWidth().height(46.dp)) { Text("Pausar conta") }; Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) { OutlinedButton(onClick = { onEditBill(bill) }, modifier = Modifier.weight(1f).height(44.dp)) { Text("Editar") }; TextButton(onClick = { onDeleteBill(bill.id) }, modifier = Modifier.height(44.dp)) { Text("Excluir") } } } } }
    }
    contributionGoalId?.let { goalId -> ContributionDialog(onDismiss = { contributionGoalId = null }, onSave = { value -> onAddContribution(goalId, value); contributionGoalId = null }) }
}

@Composable private fun ContributionDialog(onDismiss: () -> Unit, onSave: (Double) -> Unit) {
    var amount by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Adicionar à meta") }, text = { OutlinedTextField(amount, { amount = it.replace(',', '.') }, label = { Text("Valor") }, singleLine = true) }, confirmButton = { Button(onClick = { onSave(amount.toDoubleOrNull() ?: 0.0) }, enabled = (amount.toDoubleOrNull() ?: 0.0) > 0) { Text("Adicionar") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}

@Composable private fun RecurringDialog(categories: List<CategoryEntity>, onDismiss: () -> Unit, onSave: (String, Double, Int, String?) -> Unit, existing: RecurringBillEntity? = null) {
    var title by remember(existing?.id) { mutableStateOf(existing?.title.orEmpty()) }; var amount by remember(existing?.id) { mutableStateOf(existing?.amountCents?.div(100.0)?.toString().orEmpty()) }; var day by remember(existing?.id) { mutableStateOf(existing?.dayOfMonth?.toString() ?: "1") }; var categoryId by remember(existing?.id) { mutableStateOf(existing?.categoryId) }; var menu by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (existing == null) "Nova conta recorrente" else "Editar conta recorrente") }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { OutlinedTextField(title, { title = it }, label = { Text("Nome") }, singleLine = true); OutlinedTextField(amount, { amount = it.replace(',', '.') }, label = { Text("Valor mensal") }, singleLine = true); OutlinedTextField(day, { day = it.filter(Char::isDigit) }, label = { Text("Dia do mês") }, singleLine = true); Box { OutlinedButton(onClick = { menu = true }) { Text(categories.firstOrNull { it.id == categoryId }?.name ?: "Escolher categoria") }; DropdownMenu(menu, { menu = false }) { categories.forEach { category -> DropdownMenuItem(text = { Text(category.name) }, onClick = { categoryId = category.id; menu = false }) } } } } }, confirmButton = { Button(onClick = { amount.toDoubleOrNull()?.let { onSave(title, it, day.toIntOrNull() ?: 1, categoryId) } }, enabled = title.isNotBlank() && (amount.toDoubleOrNull() ?: 0.0) > 0 && (day.toIntOrNull() ?: 0) in 1..31) { Text("Salvar") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}

@Composable private fun GoalDialog(onDismiss: () -> Unit, onSave: (String, Double, String?) -> Unit, existing: GoalEntity? = null) {
    var title by remember(existing?.id) { mutableStateOf(existing?.title.orEmpty()) }; var target by remember(existing?.id) { mutableStateOf(existing?.targetCents?.div(100.0)?.toString().orEmpty()) }; var deadline by remember(existing?.id) { mutableStateOf(existing?.deadline.orEmpty()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (existing == null) "Nova meta" else "Editar meta") }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { OutlinedTextField(title, { title = it }, label = { Text("Nome da meta") }, singleLine = true); OutlinedTextField(target, { target = it.replace(',', '.') }, label = { Text("Valor alvo") }, singleLine = true); DateField("Prazo opcional", deadline) { deadline = it } } }, confirmButton = { Button(onClick = { target.toDoubleOrNull()?.let { onSave(title, it, deadline) } }, enabled = title.isNotBlank() && (target.toDoubleOrNull() ?: 0.0) > 0) { Text("Salvar") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}

@Composable private fun TaskDialog(onDismiss: () -> Unit, onSave: (String, String?) -> Unit, existing: PersonalTask? = null) {
    var title by remember(existing?.id) { mutableStateOf(existing?.title.orEmpty()) }; var date by remember(existing?.id) { mutableStateOf(existing?.dueDate.orEmpty()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (existing == null) "Nova tarefa" else "Editar tarefa") }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { OutlinedTextField(title, { title = it }, label = { Text("Título") }, singleLine = true); DateField("Prazo opcional", date) { date = it } } }, confirmButton = { Button(onClick = { onSave(title, date) }, enabled = title.isNotBlank()) { Text("Salvar") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}

@Composable private fun DateField(label: String, value: String, onValueChange: (String) -> Unit) {
    val context = LocalContext.current
    OutlinedButton(onClick = {
        val initial = runCatching { LocalDate.parse(value) }.getOrDefault(LocalDate.now())
        DatePickerDialog(context, { _, year, month, day -> onValueChange(LocalDate.of(year, month + 1, day).toString()) }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
    }, modifier = Modifier.fillMaxWidth()) { Text(if (value.isBlank()) label else "$label: $value") }
}

internal fun money(value: Double): String = NumberFormat.getCurrencyInstance(Locale("pt", "BR")).format(value)
