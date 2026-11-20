package com.nxuslab.dreymanager.feature.plano

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nxuslab.dreymanager.core.theme.NxusColors
import com.nxuslab.dreymanager.core.ui.AlertCard
import com.nxuslab.dreymanager.core.ui.AlertKind
import com.nxuslab.dreymanager.core.ui.DatePickerField
import com.nxuslab.dreymanager.core.ui.MoneyField
import com.nxuslab.dreymanager.core.ui.br
import com.nxuslab.dreymanager.data.repo.FinanceSnapshot
import com.nxuslab.dreymanager.domain.model.Wish
import com.nxuslab.dreymanager.domain.model.WishPriority
import com.nxuslab.dreymanager.domain.model.WishStatus
import com.nxuslab.dreymanager.domain.money.Money
import com.nxuslab.dreymanager.domain.wish.WishPlanner
import com.nxuslab.dreymanager.domain.wish.WishProjection
import com.nxuslab.dreymanager.feature.common.wishProjections
import java.time.LocalDate
import java.util.UUID

private val priorityLabels = listOf(WishPriority.ALTA to "Alta", WishPriority.MEDIA to "Média", WishPriority.BAIXA to "Baixa")

/** Lista de desejos: juntar antes de comprar, com o cartão só como comparação (especificação §6.8). */
@Composable
fun WishesTab(data: FinanceSnapshot, viewModel: PlanoViewModel, onMessage: (String) -> Unit) {
    val today = LocalDate.now()
    val scope = rememberCoroutineScope()
    val projections = data.wishProjections(today)
    var editing by remember { mutableStateOf<Wish?>(null) }
    var saving by remember { mutableStateOf<WishProjection?>(null) }
    val cardRate = data.cards.mapNotNull { it.monthlyInterestBp }.maxOrNull()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 16.dp, 20.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth()) {
                Text("Desejos", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                Button(onClick = { editing = Wish(UUID.randomUUID().toString(), "", 0, null, WishPriority.MEDIA, createdAt = System.currentTimeMillis()) }) { Text("Novo") }
            }
            Text("Junte antes de comprar. Alta prioridade reserva dinheiro todo mês; média e baixa usam o que sobra da semana.", color = NxusColors.TextMuted)
        }
        if (projections.isEmpty()) item { AlertCard("Nenhum desejo ainda", "Adicione algo que você quer comprar e veja quanto guardar por mês.", AlertKind.INFO) }
        items(projections, key = { it.wish.id }) { p ->
            WishCard(
                projection = p,
                comparison = WishPlanner.compareWithCard(p.wish.priceCents, 10, cardRate, data.settings.budget.payoffExtraMonthlyCents).message,
                onSave = { saving = p },
                onEdit = { editing = p.wish },
                onBought = { scope.launchResult({ viewModel.saveWish(p.wish.copy(status = WishStatus.COMPRADO)) }, onMessage, "${p.wish.name} marcado como comprado.") },
                onGiveUp = { scope.launchResult({ viewModel.saveWish(p.wish.copy(status = WishStatus.DESISTI)) }, onMessage, "Desejo arquivado.") },
            )
        }
    }

    editing?.let { wish ->
        WishDialog(
            initial = wish,
            data = data,
            onDismiss = { editing = null },
            onDelete = { editing = null; scope.launchResult({ viewModel.deleteWish(wish.id) }, onMessage, "Desejo excluído.") },
            onSave = { saved -> editing = null; scope.launchResult({ viewModel.saveWish(saved) }, onMessage, "${saved.name} salvo.") },
        )
    }
    saving?.let { p ->
        SaveMoneyDialog(p, onDismiss = { saving = null }) { cents ->
            saving = null
            scope.launchResult({ viewModel.addSaving(p.wish.id, cents) }, onMessage, "${Money.format(cents)} guardados para ${p.wish.name}.")
        }
    }
}

@Composable
private fun WishCard(projection: WishProjection, comparison: String, onSave: () -> Unit, onEdit: () -> Unit, onBought: () -> Unit, onGiveUp: () -> Unit) {
    val wish = projection.wish
    var showCompare by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(NxusColors.Surface, RoundedCornerShape(16.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row {
            Text(wish.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(priorityLabels.first { it.first == wish.priority }.second, color = if (wish.priority == WishPriority.ALTA) NxusColors.AccentLight else NxusColors.TextMuted, style = MaterialTheme.typography.labelLarge)
        }
        Text("${Money.format(projection.savedCents)} de ${Money.format(wish.priceCents)}", color = NxusColors.TextMuted)
        LinearProgressIndicator(
            progress = { (projection.savedCents.toFloat() / wish.priceCents.coerceAtLeast(1)).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = if (projection.ready) NxusColors.Positive else NxusColors.Accent,
            trackColor = NxusColors.SurfaceHigh,
            drawStopIndicator = {},
        )
        if (!projection.ready) {
            if (projection.monthlyNeededCents > 0) Text("Guardar ${Money.format(projection.monthlyNeededCents)} por mês", style = MaterialTheme.typography.bodyMedium)
            projection.realisticDate?.let { Text("Dá para comprar em ${it.br()}", style = MaterialTheme.typography.bodyMedium, color = NxusColors.AccentLight) }
            if (projection.weeklyReductionCents > 0) Text("Reduz sua semana em ${Money.format(projection.weeklyReductionCents)}", style = MaterialTheme.typography.bodyMedium, color = NxusColors.Alert)
        }
        projection.message?.let { Text(it, color = if (projection.ready) NxusColors.Positive else NxusColors.TextMuted, style = MaterialTheme.typography.bodyMedium) }
        if (showCompare) Text(comparison, color = NxusColors.Alert, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (projection.ready) TextButton(onClick = onBought) { Text("Comprei") } else TextButton(onClick = onSave) { Text("Guardar") }
            TextButton(onClick = { showCompare = !showCompare }) { Text(if (showCompare) "Ocultar" else "E no cartão?") }
            TextButton(onClick = onEdit) { Text("Editar") }
            if (!projection.ready) TextButton(onClick = onGiveUp) { Text("Desisti", color = NxusColors.TextMuted) }
        }
    }
}

@Composable
private fun WishDialog(initial: Wish, data: FinanceSnapshot, onDismiss: () -> Unit, onDelete: () -> Unit, onSave: (Wish) -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var priceText by remember { mutableStateOf(if (initial.priceCents > 0) "${initial.priceCents / 100},${(initial.priceCents % 100).toString().padStart(2, '0')}" else "") }
    var price by remember { mutableStateOf(initial.priceCents.takeIf { it > 0 }) }
    var target by remember { mutableStateOf(initial.targetDate) }
    var priority by remember { mutableStateOf(initial.priority) }
    val isNew = initial.name.isBlank()
    val draft = price?.let { initial.copy(name = name.trim(), priceCents = it, targetDate = target, priority = priority) }
    // Antes de escolher prioridade alta, mostra quanto a semana diminui.
    val reduction = draft?.takeIf { it.priority == WishPriority.ALTA }?.let { d ->
        WishPlanner.plan(listOf(d), data.savedByWish, LocalDate.now(), 0).firstOrNull()?.weeklyReductionCents
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NxusColors.Surface,
        title = { Text(if (isNew) "Novo desejo" else "Editar desejo") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("O que você quer") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                MoneyField(priceText, { priceText = it }, "Preço", onValid = { price = it })
                DatePickerField("Quando (opcional)", target, { target = it }, allowClear = true)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    priorityLabels.forEachIndexed { i, (value, label) ->
                        SegmentedButton(selected = priority == value, onClick = { priority = value }, shape = SegmentedButtonDefaults.itemShape(i, priorityLabels.size)) { Text(label) }
                    }
                }
                reduction?.let { Text("Prioridade alta reserva dinheiro todo mês: sua semana diminui ${Money.format(it)}.", color = NxusColors.Alert, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = { Button(onClick = { onSave(draft!!) }, enabled = draft != null && name.isNotBlank()) { Text(if (reduction != null) "Reservar e salvar" else "Salvar") } },
        dismissButton = {
            Row {
                if (!isNew) TextButton(onClick = onDelete) { Text("Excluir", color = NxusColors.Danger) }
                TextButton(onClick = onDismiss) { Text("Cancelar") }
            }
        },
    )
}

@Composable
private fun SaveMoneyDialog(projection: WishProjection, onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    var text by remember { mutableStateOf("") }
    var cents by remember { mutableStateOf<Long?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NxusColors.Surface,
        title = { Text("Guardar para ${projection.wish.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Faltam ${Money.format(projection.remainingCents)}.", color = NxusColors.TextMuted)
                MoneyField(text, { text = it }, "Quanto guardou", onValid = { cents = it })
            }
        },
        confirmButton = { Button(onClick = { onConfirm(cents!!) }, enabled = cents != null) { Text("Guardar") } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
