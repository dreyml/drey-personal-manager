package com.nxuslab.dreymanager.feature.mais

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nxuslab.dreymanager.BuildConfig
import com.nxuslab.dreymanager.NxusApp
import com.nxuslab.dreymanager.core.theme.NxusColors
import com.nxuslab.dreymanager.core.ui.AlertCard
import com.nxuslab.dreymanager.core.ui.AlertKind
import com.nxuslab.dreymanager.core.ui.MoneyField
import com.nxuslab.dreymanager.core.ui.SectionHeader
import com.nxuslab.dreymanager.data.repo.AppSettings
import com.nxuslab.dreymanager.data.repo.FinanceRepository
import com.nxuslab.dreymanager.data.repo.FinanceSnapshot
import com.nxuslab.dreymanager.domain.budget.BudgetCalculator
import com.nxuslab.dreymanager.domain.budget.OverrideCheck
import com.nxuslab.dreymanager.domain.money.Money
import com.nxuslab.dreymanager.feature.common.budgetFor
import com.nxuslab.dreymanager.notifications.Notifier
import com.nxuslab.dreymanager.notifications.Reminders
import com.nxuslab.dreymanager.update.UpdateState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate

class MoreViewModel(application: Application) : AndroidViewModel(application) {
    val repository: FinanceRepository = (application as NxusApp).repository
    val snapshot: StateFlow<FinanceSnapshot?> = repository.snapshot.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Grava ajustes; se o horário do lembrete mudou, reagenda as notificações. */
    suspend fun updateSettings(change: (AppSettings) -> AppSettings): Result<Unit> {
        val before = repository.settings().reminderMinutes
        return repository.updateSettings(change).onSuccess {
            val after = repository.settings().reminderMinutes
            if (after != before) Reminders.schedule(getApplication(), after, replace = true)
        }
    }
}

/** Executa uma gravação e mostra sucesso ou o erro. */
internal fun CoroutineScope.save(action: suspend () -> Result<Unit>, onMessage: (String) -> Unit, success: String) {
    launch { action().fold({ onMessage(success) }, { onMessage(it.message ?: "Algo deu errado.") }) }
}

internal enum class Section(val title: String) {
    RENDA("Renda e extras"), CONTAS_FIXAS("Contas fixas"), CONTAS_VARIAVEIS("Contas variáveis"), CONTAS("Contas e carteira"),
    CATEGORIAS("Categorias e envelopes"), CARTOES("Cartões e dívidas"), AJUSTES("Ajustes"),
}

@Composable
fun MoreScreen(update: UpdateState, onMessage: (String) -> Unit, viewModel: MoreViewModel = viewModel()) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val data = snapshot ?: return
    var section by rememberSaveable { mutableStateOf<Section?>(null) }
    BackHandler(enabled = section != null) { section = null }
    ImportFlow.Host(viewModel, onMessage)

    when (section) {
        null -> Menu(data, update, onOpen = { section = it }, onImport = { ImportFlow.start() })
        Section.RENDA -> IncomeSection(data, viewModel, onMessage)
        Section.CONTAS_FIXAS -> BillsSection(data, viewModel, onMessage)
        Section.CONTAS_VARIAVEIS -> VariableBillsSection(data, viewModel, onMessage)
        Section.CONTAS -> AccountsSection(data, viewModel, onMessage)
        Section.CATEGORIAS -> CategoriesSection(data, viewModel, onMessage)
        Section.CARTOES -> CardsSection(data, viewModel, onMessage)
        Section.AJUSTES -> SettingsSection(data, viewModel, onMessage)
    }
}

@Composable
private fun Menu(data: FinanceSnapshot, update: UpdateState, onOpen: (Section) -> Unit, onImport: () -> Unit) {
    val context = LocalContext.current
    val notificationsOn = remember { Notifier.enabled(context) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 16.dp, 20.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Mais", style = MaterialTheme.typography.headlineMedium) }
        if (!notificationsOn) {
            item {
                AlertCard(
                    "Notificações desligadas",
                    "Sem elas o app não lembra de lançar os gastos nem avisa de vencimentos e faltas.",
                    AlertKind.WARNING,
                    actionLabel = "Ativar",
                    onAction = {
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    },
                )
            }
        }
        val counts = mapOf(
            Section.RENDA to "${data.incomes.size} cadastrada(s)",
            Section.CONTAS_FIXAS to "${data.fixedBills.size} conta(s) · ${Money.format(data.fixedBills.filter { it.active }.sumOf { it.amountCents })}/mês",
            Section.CONTAS_VARIAVEIS to "${data.variableBills.size} conta(s) · energia, celular…",
            Section.CONTAS to "saldo ${Money.format(data.openingBalanceCents)}",
            Section.CATEGORIAS to "${data.envelopes.size} envelope(s)",
            Section.CARTOES to "${data.cards.size} cartão(ões) · ${Money.format(data.debts.sumOf { it.balanceCents })} em dívidas",
            Section.AJUSTES to "semana, lembrete, reserva e limite",
        )
        Section.entries.forEach { s ->
            item {
                Row(
                    Modifier.fillMaxWidth().background(NxusColors.Surface, RoundedCornerShape(14.dp)).clickable { onOpen(s) }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(s.title, style = MaterialTheme.typography.titleMedium)
                        Text(counts[s].orEmpty(), color = NxusColors.TextMuted, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text("›", style = MaterialTheme.typography.titleLarge, color = NxusColors.TextMuted)
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().background(NxusColors.Surface, RoundedCornerShape(14.dp)).clickable(onClick = onImport).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Importar dados", style = MaterialTheme.typography.titleMedium)
                    Text("Arquivo .json gerado para o NxusLife", color = NxusColors.TextMuted, style = MaterialTheme.typography.bodyMedium)
                }
                Text("›", style = MaterialTheme.typography.titleLarge, color = NxusColors.TextMuted)
            }
        }
        item { SectionHeader("Atualização") }
        item {
            when (update) {
                is UpdateState.Available -> AlertCard(
                    "Nova versão ${update.manifest.versionName}", update.manifest.message, AlertKind.INFO,
                    actionLabel = "Baixar", onAction = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.manifest.downloadUrl))) },
                )
                UpdateState.Checking -> Text("Procurando atualizações…", color = NxusColors.TextMuted)
                UpdateState.UpToDate -> Text("NxusLife ${BuildConfig.VERSION_NAME} está atualizado.", color = NxusColors.TextMuted)
                UpdateState.Unavailable -> Text("Não foi possível verificar atualizações agora.", color = NxusColors.TextMuted)
            }
        }
    }
}

@Composable
private fun SettingsSection(data: FinanceSnapshot, viewModel: MoreViewModel, onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val settings = data.settings
    val today = LocalDate.now()
    val inputs = data.budgetFor(today)
    val suggested = BudgetCalculator.suggestedWeeklyLimit(BudgetCalculator.weekStartOf(today, settings.budget.weekStart), inputs)
    var reserveText by remember { mutableStateOf(centsText(settings.budget.reserveCents)) }
    var reserve by remember { mutableStateOf<Long?>(settings.budget.reserveCents) }
    var split by remember { mutableFloatStateOf(settings.budget.extraSplitToDebtPercent.toFloat()) }
    var overrideText by remember { mutableStateOf(settings.budget.weeklyLimitOverrideCents?.let(::centsText).orEmpty()) }
    var override by remember { mutableStateOf(settings.budget.weeklyLimitOverrideCents) }
    val overrideCheck = override?.let { BudgetCalculator.validateOverride(it, today, inputs) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 16.dp, 20.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Ajustes", style = MaterialTheme.typography.headlineMedium) }

        item { SectionHeader("A semana começa no") }
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(DayOfWeek.MONDAY to "Segunda", DayOfWeek.SUNDAY to "Domingo").forEachIndexed { i, (day, label) ->
                    SegmentedButton(
                        selected = settings.budget.weekStart == day,
                        onClick = { scope.save({ viewModel.updateSettings { it.copy(budget = it.budget.copy(weekStart = day)) } }, onMessage, "A semana agora começa no(a) ${label.lowercase()}.") },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                    ) { Text(label) }
                }
            }
        }

        item { SectionHeader("Lembrete \"lançou tudo de hoje?\"") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (19..23).forEach { hour ->
                    FilterChip(
                        selected = settings.reminderMinutes == hour * 60,
                        onClick = { scope.save({ viewModel.updateSettings { it.copy(reminderMinutes = hour * 60) } }, onMessage, "Lembrete às ${hour}h.") },
                        label = { Text("${hour}h") },
                    )
                }
            }
        }

        item { SectionHeader("Reserva mensal") }
        item {
            MoneyField(reserveText, { reserveText = it }, "Guardar todo mês antes das semanas", onValid = { reserve = it })
            OutlinedButton(
                onClick = { scope.save({ viewModel.updateSettings { it.copy(budget = it.budget.copy(reserveCents = reserve ?: 0)) } }, onMessage, "Reserva salva.") },
                enabled = reserveText.isBlank() || reserve != null,
            ) { Text("Salvar reserva") }
        }

        item { SectionHeader("Renda extra: parte que vai para a quitação") }
        item {
            Text("${split.toInt()}% para a quitação · ${100 - split.toInt()}% para o mês")
            Slider(value = split, onValueChange = { split = it }, valueRange = 0f..100f, steps = 9, onValueChangeFinished = {
                scope.save({ viewModel.updateSettings { it.copy(budget = it.budget.copy(extraSplitToDebtPercent = split.toInt())) } }, onMessage, "Divisão das rendas extras atualizada.")
            })
        }

        item { SectionHeader("Limite da semana") }
        item {
            Text("Sugerido pelo orçamento: ${Money.format(suggested)}. Você pode baixar, nunca subir.", color = NxusColors.TextMuted)
            MoneyField(overrideText, { overrideText = it }, "Meu limite semanal", onValid = { override = it })
            if (overrideCheck is OverrideCheck.TooHigh) Text("Acima do que o orçamento comporta (${Money.format(overrideCheck.maxCents)}).", color = NxusColors.Danger)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { scope.save({ viewModel.updateSettings { it.copy(budget = it.budget.copy(weeklyLimitOverrideCents = override)) } }, onMessage, "Limite da semana ajustado.") },
                    enabled = override != null && overrideCheck is OverrideCheck.Ok,
                ) { Text("Usar este limite") }
                OutlinedButton(onClick = {
                    overrideText = ""; override = null
                    scope.save({ viewModel.updateSettings { it.copy(budget = it.budget.copy(weeklyLimitOverrideCents = null)) } }, onMessage, "Voltou ao limite sugerido.")
                }) { Text("Usar sugerido") }
            }
        }
    }
}

/** Importação: escolhe o arquivo, valida tudo, mostra o resumo e só grava depois da confirmação. */
private object ImportFlow {
    private var launch: (() -> Unit)? = null
    fun start() { launch?.invoke() }

    @Composable
    fun Host(viewModel: MoreViewModel, onMessage: (String) -> Unit) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var pending by remember { mutableStateOf<com.nxuslab.dreymanager.data.repo.ImportBundle?>(null) }
        val launcher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                try {
                    val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val input = context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("sem acesso ao arquivo")
                        input.use {
                            val bytes = it.readBytes()
                            if (bytes.size > com.nxuslab.dreymanager.data.ImportCodec.MAX_BYTES) throw java.io.IOException("arquivo grande demais")
                            String(bytes, Charsets.UTF_8)
                        }
                    }
                    pending = com.nxuslab.dreymanager.data.ImportCodec.decode(text)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onMessage(e.message ?: "Não foi possível ler o arquivo.")
                }
            }
        }
        launch = { launcher.launch(arrayOf("application/json", "text/plain", "*/*")) }

        pending?.let { bundle ->
            val debts = bundle.cards.sumOf { it.debts.size }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { pending = null },
                containerColor = NxusColors.Surface,
                title = { Text("Importar dados?") },
                text = {
                    Text(
                        "${bundle.cards.size} cartão(ões): ${bundle.cards.joinToString { it.card.name }} · $debts dívida(s)" +
                            (if (bundle.accounts.isNotEmpty()) " · ${bundle.accounts.size} conta(s)" else "") +
                            (if (bundle.fixedBills.size + bundle.variableBills.size > 0) " · ${bundle.fixedBills.size + bundle.variableBills.size} conta(s) a pagar" else "") +
                            ".\n\nCartões com o mesmo nome terão as dívidas substituídas.",
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        pending = null
                        scope.launch {
                            viewModel.repository.importData(bundle).fold(
                                { s -> onMessage("Importado: ${s.cards} cartão(ões) e ${s.debts} dívida(s)" + if (s.replacedCards.isNotEmpty()) " (substituídas: ${s.replacedCards.joinToString()})." else ".") },
                                { onMessage(it.message ?: "Erro ao importar.") },
                            )
                        }
                    }) { Text("Importar") }
                },
                dismissButton = { androidx.compose.material3.TextButton(onClick = { pending = null }) { Text("Cancelar") } },
            )
        }
    }
}

internal fun centsText(cents: Long): String = if (cents <= 0) "" else "${cents / 100},${(cents % 100).toString().padStart(2, '0')}"
