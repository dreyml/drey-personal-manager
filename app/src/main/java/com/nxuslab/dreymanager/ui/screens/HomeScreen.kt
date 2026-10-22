package com.nxuslab.dreymanager.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nxuslab.dreymanager.R
import com.nxuslab.dreymanager.data.GoalEntity
import com.nxuslab.dreymanager.data.MoneyTransaction
import com.nxuslab.dreymanager.data.PersonalTask
import com.nxuslab.dreymanager.data.RecurringBillEntity
import com.nxuslab.dreymanager.data.TransactionType
import com.nxuslab.dreymanager.money
import com.nxuslab.dreymanager.update.UpdateState

@Composable
internal fun HomeScreen(modifier: Modifier, transactions: List<MoneyTransaction>, tasks: List<PersonalTask>, bills: List<RecurringBillEntity>, goals: List<GoalEntity>, update: UpdateState, openFinance: () -> Unit, openTasks: () -> Unit, exportData: () -> Unit, importData: () -> Unit) {
    val income = transactions.filter { it.type == TransactionType.INCOME }.sumOf { it.amount }
    val expense = transactions.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amount }
    val balance = income - expense
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp), contentPadding = PaddingValues(start = 0.dp, top = 20.dp, end = 0.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) { Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) { Image(painterResource(R.drawable.nxuslife_logo), "Logo NxusLife", Modifier.size(52.dp)); Column(Modifier.weight(1f)) { Text("Olá!", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.SemiBold); Text("Seu dia, no controle.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("Uma visão clara da sua vida", color = MaterialTheme.colorScheme.onSurfaceVariant) } } } }
        if (update is UpdateState.Available) item { UpdateNotice(update) }
        item { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = MaterialTheme.shapes.extraLarge) { Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("SALDO DISPONÍVEL", style = MaterialTheme.typography.labelMedium); Text(money(balance), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Metric("Entradas", money(income)); Metric("Saídas", money(expense)) } } } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) { ActionCard("Finanças", "${transactions.size} lançamentos", Modifier.weight(1f), openFinance); ActionCard("Tarefas", "${tasks.count { !it.completed }} pendentes", Modifier.weight(1f), openTasks) } }
        item { Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text("Resumo rápido", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Metric("Pendentes", tasks.count { !it.completed }.toString()); Metric("Contas", bills.size.toString()); Metric("Metas", goals.size.toString()) } } } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = exportData, modifier = Modifier.weight(1f)) { Text("Backup") }; OutlinedButton(onClick = importData, modifier = Modifier.weight(1f)) { Text("Restaurar") } } }
    }
}

@Composable private fun Metric(label: String, value: String) = Column { Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, fontWeight = FontWeight.SemiBold) }

@Composable private fun ActionCard(title: String, subtitle: String, modifier: Modifier, onClick: () -> Unit) = Card(onClick = onClick, modifier = modifier, shape = MaterialTheme.shapes.large) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(title, fontWeight = FontWeight.Bold); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }

@Composable private fun UpdateNotice(update: UpdateState.Available) { val context = LocalContext.current; Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Atualização disponível: ${update.manifest.versionName}", fontWeight = FontWeight.SemiBold); Text(update.manifest.message); Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.manifest.downloadUrl))) }) { Text("Ver atualização") } } } }
