package com.nxuslab.dreymanager.feature.financas

import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nxuslab.dreymanager.NxusApp
import com.nxuslab.dreymanager.core.theme.NxusColors
import com.nxuslab.dreymanager.core.ui.AlertCard
import com.nxuslab.dreymanager.core.ui.AlertKind
import com.nxuslab.dreymanager.core.ui.CompactRow
import com.nxuslab.dreymanager.core.ui.MoneyField
import com.nxuslab.dreymanager.data.repo.Account
import com.nxuslab.dreymanager.data.repo.FinanceSnapshot
import com.nxuslab.dreymanager.data.sync.BankSync
import com.nxuslab.dreymanager.domain.advice.StepAction
import com.nxuslab.dreymanager.domain.daily.DayState
import com.nxuslab.dreymanager.domain.daily.DailyView
import com.nxuslab.dreymanager.domain.money.Money
import com.nxuslab.dreymanager.feature.mais.syncedLabel
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
import kotlin.math.absoluteValue

class FinancasViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as NxusApp).repository

    data class State(val snapshot: FinanceSnapshot, val overview: FinanceOverview)

    val state: StateFlow<State?> = combine(repository.snapshot, BankSync.status) { snapshot, sync ->
        State(snapshot, snapshot.financeOverview(LocalDate.now(), sync.lastFailed))
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun saveBalance(account: Account, cents: Long, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch { onResult(repository.saveAccount(account.copy(balanceCents = cents))) }
    }
}

private val ptBr = Locale("pt", "BR")

/** Finanças · Início: próximo passo, valor de hoje, carteira de contas e cartões e o que vence. */
@Composable
fun FinancasScreen(
    onBack: () -> Unit,
    onOpenPlan: () -> Unit,
    onOpenForecast: () -> Unit,
    onOpenMore: () -> Unit,
    onMessage: (String) -> Unit,
    viewModel: FinancasViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val bank by BankSync.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var editing by remember { mutableStateOf<Account?>(null) }
    val s = state ?: run {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val overview = s.overview
    val today = LocalDate.now()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = onBack, modifier = Modifier.background(NxusColors.Surface, CircleShape)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar para a entrada")
                }
                Column {
                    Text("Controle financeiro", style = MaterialTheme.typography.bodyMedium, color = NxusColors.TextMuted)
                    Text(dateLabel(today), style = MaterialTheme.typography.titleLarge)
                }
            }
        }

        item {
            val step = overview.nextStep
            Column(
                Modifier.fillMaxWidth().background(NxusColors.Text, RoundedCornerShape(26.dp)).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("SEU PRÓXIMO PASSO", style = MaterialTheme.typography.labelMedium, color = Color(0xFFCFC6F2), fontWeight = FontWeight.Bold)
                Text(step.title, style = MaterialTheme.typography.titleLarge, color = Color.White)
                Text(step.detail, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFDAD6E4))
                if (step.actionLabel != null) {
                    Button(
                        onClick = {
                            when (step.action) {
                                StepAction.PLANO -> onOpenPlan()
                                StepAction.PREVISAO -> onOpenForecast()
                                StepAction.CONTAS -> if (bank.lastFailed) BankSync.requestNow(context) else editing = s.snapshot.accounts.firstOrNull()
                                StepAction.CONTAS_VARIAVEIS -> onOpenMore()
                                StepAction.NONE -> Unit
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NxusColors.Lavender, contentColor = Color(0xFF3A2F6B)),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text(step.actionLabel) }
                }
            }
        }

        item {
            when {
                overview.daily != null -> DailyCard(overview.daily)
                overview.incompleteReason != null -> AlertCard("Falta pouco", overview.incompleteReason, AlertKind.INFO, actionLabel = "Completar", onAction = onOpenMore)
            }
        }

        if (overview.wallet.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("Contas e cartões", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (bank.configured) {
                        Text(
                            if (bank.running) "Sincronizando…" else syncedLabel(bank),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (bank.lastFailed) NxusColors.Alert else NxusColors.Positive,
                            modifier = Modifier.clickable(enabled = !bank.running) { BankSync.requestNow(context) }.padding(vertical = 6.dp),
                        )
                    }
                }
            }
            item { Wallet(overview.wallet, s.snapshot.settings.userName, onEdit = { editing = it }) }
            item { TotalCard(overview.totalCents) }
        }

        if (overview.upcoming.isNotEmpty()) {
            item { Text("Vence em breve", style = MaterialTheme.typography.titleLarge) }
            items(overview.upcoming, key = { "${it.date}-${it.title}" }) { item ->
                val days = java.time.temporal.ChronoUnit.DAYS.between(today, item.date)
                CompactRow(item.title, when (days) { 0L -> "hoje"; 1L -> "amanhã"; else -> "em $days dias" }, Money.format(item.amountCents))
            }
        }
    }

    editing?.let { account ->
        var text by remember(account.id) { mutableStateOf("") }
        var cents by remember(account.id) { mutableStateOf<Long?>(null) }
        AlertDialog(
            onDismissRequest = { editing = null },
            containerColor = NxusColors.Surface,
            title = { Text("Saldo de ${account.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Quanto tem agora? Os próximos lançamentos ajustam o saldo sozinhos.", color = NxusColors.TextMuted)
                    MoneyField(text, { text = it }, "Saldo de agora", onValid = { cents = it })
                    TextButton(onClick = { text = ""; cents = 0 }) { Text("Está zerado") }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val value = cents ?: return@Button
                    editing = null
                    viewModel.saveBalance(account, value) { r -> onMessage(r.fold({ "Saldo atualizado." }, { it.message ?: "Erro ao salvar." })) }
                }, enabled = cents != null) { Text("Salvar") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun DailyCard(daily: DailyView) {
    val over = daily.overTodayCents > 0
    Column(
        Modifier.fillMaxWidth().background(NxusColors.Surface, RoundedCornerShape(26.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(if (over) "Hoje passou" else "Hoje dá para gastar", style = MaterialTheme.typography.bodyMedium, color = NxusColors.TextMuted)
            Text(
                Money.formatShort(if (over) daily.overTodayCents else daily.remainingTodayCents),
                fontSize = 44.sp, fontWeight = FontWeight.Bold, color = if (over) NxusColors.Alert else NxusColors.Text,
            )
            Text(
                buildList {
                    when {
                        over -> daily.tomorrowCents?.let { add("Tudo bem: amanhã fica ${Money.formatShort(it)}") } ?: add("A semana que vem começa do zero")
                        daily.todayAllowanceCents < 100 -> add("Sem folga hoje: o dinheiro até o salário já está reservado para as contas.")
                        else -> add("Já gastou ${Money.formatShort(daily.spentTodayCents)} de ${Money.formatShort(daily.todayAllowanceCents)} hoje")
                    }
                    if (!over && daily.carriedCents >= 100) add("inclui ${Money.formatShort(daily.carriedCents)} que sobraram")
                }.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium, color = NxusColors.TextMuted,
            )
        }
        val ratio = if (daily.todayAllowanceCents <= 0) 1f else (daily.spentTodayCents.toFloat() / daily.todayAllowanceCents).coerceIn(0f, 1f)
        val animated by animateFloatAsState(ratio, tween(700), label = "gasto")
        LinearProgressIndicator(
            progress = { animated },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            color = if (over) Color(0xFFF4B9A0) else NxusColors.Ring,
            trackColor = NxusColors.SurfaceHigh,
            drawStopIndicator = {},
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            daily.days.forEach { d ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val modifier = Modifier.size(28.dp)
                    when (d.state) {
                        DayState.OK -> Box(modifier.background(NxusColors.Ring, CircleShape))
                        DayState.OVER -> Box(modifier.background(Color(0xFFF4B9A0), CircleShape))
                        DayState.TODAY -> Box(modifier.background(NxusColors.Surface, CircleShape).clip(CircleShape)) {
                            Canvas(Modifier.size(28.dp)) { drawCircle(NxusColors.Accent, style = Stroke(2.dp.toPx())) }
                        }
                        DayState.FUTURE -> Box(modifier.background(NxusColors.SurfaceHigh, CircleShape))
                    }
                    Text(
                        d.date.dayOfWeek.getDisplayName(TextStyle.SHORT, ptBr).take(3),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (d.state == DayState.TODAY) NxusColors.AccentLight else NxusColors.TextMuted,
                        fontWeight = if (d.state == DayState.TODAY) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/** Carteira de cartões empilhados: desliza para trocar, toque para ver os detalhes. */
@Composable
private fun Wallet(items: List<WalletItem>, userName: String, onEdit: (Account) -> Unit) {
    val pager = rememberPagerState { items.size }
    var expanded by remember { mutableStateOf(false) }
    val current = items[pager.currentPage.coerceIn(0, items.lastIndex)]
    LaunchedEffect(pager.currentPage) { expanded = false }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.fillMaxWidth()) {
            // Os próximos cartões aparecem atrás, menores e mais claros.
            listOf(2 to 0.45f, 1 to 0.75f).forEach { (ahead, alpha) ->
                val next = items.getOrNull(pager.currentPage + ahead) ?: return@forEach
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = (13 * ahead).dp).padding(top = ((2 - ahead) * 12).dp)
                        .aspectRatio(1.586f).graphicsLayer { this.alpha = alpha }
                        .background(Brush.linearGradient(listOf(Color(next.brand.start), Color(next.brand.end))), RoundedCornerShape(20.dp)),
                )
            }
            HorizontalPager(state = pager, modifier = Modifier.padding(top = 26.dp), pageSpacing = 16.dp) { page ->
                val item = items[page]
                val offset = ((pager.currentPage - page) + pager.currentPageOffsetFraction).absoluteValue.coerceIn(0f, 1f)
                BankCard(
                    item, userName,
                    Modifier.graphicsLayer {
                        val scale = 1f - 0.08f * offset
                        scaleX = scale; scaleY = scale; alpha = 1f - 0.35f * offset
                    }.clickable { expanded = !expanded },
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            items.indices.forEach { i ->
                val width by animateDpAsState(if (i == pager.currentPage) 20.dp else 8.dp, label = "dot")
                Box(Modifier.padding(horizontal = 3.dp).width(width).height(8.dp).background(if (i == pager.currentPage) NxusColors.AccentLight else NxusColors.Outline, RoundedCornerShape(4.dp)))
            }
        }
        Text(current.sub, style = MaterialTheme.typography.bodyMedium, color = NxusColors.TextMuted, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        AnimatedVisibility(expanded, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column(Modifier.fillMaxWidth().background(NxusColors.Surface, RoundedCornerShape(22.dp)).padding(horizontal = 18.dp, vertical = 8.dp)) {
                current.details.forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        Text(k, color = NxusColors.TextMuted, modifier = Modifier.weight(1f))
                        Text(v, style = MaterialTheme.typography.titleMedium)
                    }
                }
                current.account?.let { acc ->
                    OutlinedButton(onClick = { onEdit(acc) }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) { Text("Corrigir saldo") }
                }
            }
        }
    }
}

@Composable
private fun BankCard(item: WalletItem, userName: String, modifier: Modifier = Modifier) {
    val fg = if (item.brand.lightText) Color.White else Color(0xFF3A1D10)
    val description = "${item.name}, ${item.kind.label.lowercase()}, ${item.mainLabel}: ${Money.format(item.mainCents)}"
    Box(
        modifier.fillMaxWidth().aspectRatio(1.586f)
            .shadow(14.dp, RoundedCornerShape(20.dp), ambientColor = Color(0x552D2A38), spotColor = Color(0x552D2A38))
            .background(Brush.linearGradient(listOf(Color(item.brand.start), Color(item.brand.end))), RoundedCornerShape(20.dp))
            .semantics { contentDescription = description },
    ) {
        Canvas(Modifier.fillMaxSize()) { drawCircle(Color.White.copy(alpha = 0.12f), radius = size.width * 0.32f, center = Offset(size.width * 0.92f, size.height * 0.02f)) }
        Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 18.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(item.brand.wordmark, color = fg, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(item.kind.label, color = fg.copy(alpha = 0.9f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Chip()
                Text(item.tag, color = fg.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyMedium)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(item.mainLabel, color = fg.copy(alpha = 0.9f), style = MaterialTheme.typography.labelMedium)
                    Text(Money.format(item.mainCents), color = fg, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                }
                if (userName.isNotBlank()) Text(userName.uppercase(ptBr), color = fg.copy(alpha = 0.9f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun Chip() {
    Canvas(Modifier.size(width = 40.dp, height = 30.dp)) {
        val r = CornerRadius(6.dp.toPx())
        drawRoundRect(Color(0xFFE9D9A6), cornerRadius = r)
        drawRoundRect(Color(0xFFC9B57A), cornerRadius = r, style = Stroke(1.dp.toPx()))
        val line = Color(0xFFC9B57A)
        val w = size.width; val h = size.height
        drawLine(line, Offset(w / 3, 0f), Offset(w / 3, h), 1.dp.toPx())
        drawLine(line, Offset(2 * w / 3, 0f), Offset(2 * w / 3, h), 1.dp.toPx())
        drawLine(line, Offset(0f, h / 3), Offset(w / 3, h / 3), 1.dp.toPx())
        drawLine(line, Offset(2 * w / 3, h / 3), Offset(w, h / 3), 1.dp.toPx())
        drawLine(line, Offset(0f, 2 * h / 3), Offset(w / 3, 2 * h / 3), 1.dp.toPx())
        drawLine(line, Offset(2 * w / 3, 2 * h / 3), Offset(w, 2 * h / 3), 1.dp.toPx())
    }
}

/** Patrimônio total, contando até o valor quando a tela abre. */
@Composable
private fun TotalCard(totalCents: Long) {
    var target by remember { mutableStateOf(0f) }
    LaunchedEffect(totalCents) { target = totalCents.toFloat() }
    val shown by animateFloatAsState(target, tween(900), label = "total")
    Row(
        Modifier.fillMaxWidth().background(NxusColors.Surface, RoundedCornerShape(22.dp)).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Patrimônio total", style = MaterialTheme.typography.bodyMedium, color = NxusColors.TextMuted)
            Text("dinheiro nas contas e na carteira", style = MaterialTheme.typography.labelMedium, color = NxusColors.TextMuted)
        }
        Text(Money.format(shown.toLong()), fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}

private fun dateLabel(d: LocalDate): String {
    val day = d.dayOfWeek.getDisplayName(TextStyle.FULL, ptBr).substringBefore('-').replaceFirstChar { it.uppercase() }
    return "$day, ${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, ptBr).trimEnd('.')}"
}
