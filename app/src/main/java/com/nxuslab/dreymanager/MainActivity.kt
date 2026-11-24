package com.nxuslab.dreymanager

import android.app.Application
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.nxuslab.dreymanager.app.AppShell
import com.nxuslab.dreymanager.core.theme.NxusTheme
import com.nxuslab.dreymanager.data.db.NxusDatabase
import com.nxuslab.dreymanager.data.repo.FinanceRepository
import com.nxuslab.dreymanager.data.sync.BankSync

/** Guarda as dependências do app: um único banco e um único repositório. */
class NxusApp : Application() {
    val repository: FinanceRepository by lazy { FinanceRepository(NxusDatabase.get(this)) }
    val water: com.nxuslab.dreymanager.data.repo.WaterRepository by lazy {
        com.nxuslab.dreymanager.data.repo.WaterRepository(NxusDatabase.get(this).waterDao())
    }

    override fun onCreate() {
        super.onCreate()
        BankSync.init(this)
    }
}

/** FragmentActivity porque o BiometricPrompt precisa dela. */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dados financeiros não aparecem em capturas de tela nem na lista de apps recentes.
        // Só no release: o build de debug precisa de capturas para testar as telas.
        if (!BuildConfig.DEBUG) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        // O app é sempre claro (pastel): ícones escuros nas barras do sistema, mesmo com o aparelho no tema escuro.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        setContent { NxusTheme { AppShell() } }
    }
}
