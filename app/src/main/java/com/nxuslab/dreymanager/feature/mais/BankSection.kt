package com.nxuslab.dreymanager.feature.mais

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nxuslab.dreymanager.core.theme.NxusColors
import com.nxuslab.dreymanager.core.ui.AlertCard
import com.nxuslab.dreymanager.core.ui.AlertKind
import com.nxuslab.dreymanager.core.ui.SectionHeader
import com.nxuslab.dreymanager.data.sync.BankSync
import com.nxuslab.dreymanager.data.sync.BankSyncStatus
import kotlinx.coroutines.launch

/** Mais › Bancos conectados: conecta o app ao serviço da VM e mostra o resultado da última sincronização. */
@Composable
internal fun BankSection(onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by BankSync.status.collectAsStateWithLifecycle()
    var server by remember { mutableStateOf(status.serverUrl) }
    var token by remember { mutableStateOf("") }
    var confirmDisconnect by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 16.dp, 20.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Bancos conectados", style = MaterialTheme.typography.headlineMedium) }
        item {
            Text(
                "Saldos, limites, faturas e gastos chegam sozinhos pelo Open Finance (Pluggy), 2× por dia e ao abrir o app. " +
                    "Gastos que você já lançou à mão não entram de novo.",
                color = NxusColors.TextMuted, style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (status.configured) {
            item {
                AlertCard(
                    if (status.running) "Sincronizando…" else syncedLabel(status),
                    status.lastMessage ?: "Ainda não sincronizou.",
                    if (status.lastFailed) AlertKind.WARNING else AlertKind.INFO,
                    actionLabel = if (status.running) null else "Sincronizar agora",
                    onAction = {
                        scope.launch { BankSync.run(context).onFailure { onMessage(it.message ?: "Não foi possível sincronizar.") } }
                    },
                )
            }
            item { SectionHeader("Conexão") }
            item { Text("Servidor: ${status.serverUrl}", color = NxusColors.TextMuted, style = MaterialTheme.typography.bodyMedium) }
            item { OutlinedButton(onClick = { confirmDisconnect = true }, modifier = Modifier.fillMaxWidth()) { Text("Desconectar este aparelho") } }
        } else {
            item { SectionHeader("Conectar") }
            item { OutlinedTextField(server, { server = it.trim() }, label = { Text("Endereço do servidor") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            item {
                OutlinedTextField(
                    token, { token = it.trim() },
                    label = { Text("Chave de acesso (token)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    supportingText = { Text("A mesma do NXUS_API_TOKEN da VM. Fica cifrada neste aparelho.") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Button(
                    onClick = {
                        BankSync.configure(context, server, token)
                        token = ""
                        scope.launch {
                            BankSync.run(context).fold({ onMessage("Bancos conectados.") }, { onMessage(it.message ?: "Não foi possível sincronizar.") })
                        }
                    },
                    enabled = server.startsWith("https://") && token.length >= 32,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Conectar e sincronizar") }
            }
        }
    }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Desconectar?") },
            text = { Text("O app para de buscar os dados dos bancos. O que já foi lançado continua aqui.") },
            confirmButton = { Button(onClick = { confirmDisconnect = false }) { Text("Manter") } },
            dismissButton = {
                TextButton(onClick = { BankSync.disconnect(context); confirmDisconnect = false; onMessage("Bancos desconectados.") }) {
                    Text("Desconectar", color = NxusColors.Danger)
                }
            },
        )
    }
}

internal fun syncedLabel(status: BankSyncStatus, now: Long = System.currentTimeMillis()): String {
    if (status.lastSyncMillis <= 0) return "Ainda não sincronizado"
    val minutes = (now - status.lastSyncMillis) / 60_000
    return when {
        minutes < 2 -> "Sincronizado agora"
        minutes < 60 -> "Sincronizado há $minutes min"
        minutes < 48 * 60 -> "Sincronizado há ${minutes / 60} h"
        else -> "Sincronizado há ${minutes / 1440} dias"
    }
}
