package com.nxuslab.dreymanager.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navDeepLink
import com.nxuslab.dreymanager.BuildConfig
import com.nxuslab.dreymanager.NxusApp
import com.nxuslab.dreymanager.R
import com.nxuslab.dreymanager.core.theme.NxusColors
import com.nxuslab.dreymanager.core.ui.AlertCard
import com.nxuslab.dreymanager.core.ui.AlertKind
import com.nxuslab.dreymanager.data.LegacyWipe
import com.nxuslab.dreymanager.feature.gasto.AddExpenseSheet
import com.nxuslab.dreymanager.feature.gastos.WeekScreen
import com.nxuslab.dreymanager.feature.plano.PlanoScreen
import com.nxuslab.dreymanager.feature.mais.MoreScreen
import com.nxuslab.dreymanager.notifications.Notifier
import com.nxuslab.dreymanager.notifications.Reminders
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.nxuslab.dreymanager.feature.plano.PlanoTab
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.nxuslab.dreymanager.feature.home.HomeScreen
import com.nxuslab.dreymanager.feature.onboarding.OnboardingScreen
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.rememberCoroutineScope
import com.nxuslab.dreymanager.update.HttpUpdateRepository
import com.nxuslab.dreymanager.update.UpdateChecker
import com.nxuslab.dreymanager.update.UpdateState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ---------- Estado do app ----------

data class AppState(
    val ready: Boolean = false,
    val onboardingDone: Boolean = false,
    val update: UpdateState = UpdateState.Checking,
    val startupError: String? = null,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as NxusApp).repository
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val wipe = LegacyWipe.runOnce(application, repository)
            val seed = repository.seedCategoriesIfEmpty()
            val error = (wipe.exceptionOrNull() ?: seed.exceptionOrNull())?.let { "Não foi possível preparar o app: ${it.message}" }
            val settings = repository.settings()
            _state.update { it.copy(ready = true, onboardingDone = settings.onboardingDone, startupError = error) }
            Notifier.createChannels(application)
            Reminders.schedule(application, settings.reminderMinutes, replace = false)
            if (settings.onboardingDone) com.nxuslab.dreymanager.data.sync.BankSync.syncIfStale(application)
        }
        viewModelScope.launch {
            val checker = UpdateChecker(HttpUpdateRepository(BuildConfig.UPDATE_MANIFEST_URL), BuildConfig.VERSION_CODE.toLong())
            val result = checker.check()
            _state.update { it.copy(update = result) }
        }
    }

    fun onboardingFinished() = _state.update { it.copy(onboardingDone = true) }

    /** Recusa da permissão fica registrada para o aviso em Mais. */
    fun notificationPermissionResult(granted: Boolean) {
        viewModelScope.launch { repository.updateSettings { it.copy(notificationsDenied = !granted) } }
    }
}

// ---------- Rotas ----------

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val GASTOS = "gastos"
    const val PLANO = "plano"
    const val MAIS = "mais"
    const val DEEP_LINK_BASE = "nxuslife://"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Início", Icons.Filled.Home),
    Tab(Routes.GASTOS, "Gastos", Icons.Filled.ReceiptLong),
    Tab(Routes.PLANO, "Plano", Icons.Filled.CalendarMonth),
    Tab(Routes.MAIS, "Mais", Icons.Filled.MoreHoriz),
)

// ---------- Casca ----------

@Composable
fun AppShell(viewModel: AppViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AppLock {
        if (!state.ready) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            NxusNavHost(state, onOnboardingFinished = viewModel::onboardingFinished, onPermissionResult = viewModel::notificationPermissionResult)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NxusNavHost(state: AppState, onOnboardingFinished: () -> Unit, onPermissionResult: (Boolean) -> Unit) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), onPermissionResult)
    fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val current by navController.currentBackStackEntryAsState()
    val route = current?.destination?.route
    var addExpenseOpen by rememberSaveable { mutableStateOf(false) }
    val showChrome = route != null && route != Routes.ONBOARDING
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = NxusColors.Background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { if (showChrome) BottomBar(navController, route) },
        floatingActionButton = {
            if (showChrome) {
                ExtendedFloatingActionButton(
                    onClick = { addExpenseOpen = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Gasto") },
                    containerColor = NxusColors.Accent,
                    contentColor = androidx.compose.ui.graphics.Color.White,
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = if (state.onboardingDone) Routes.HOME else Routes.ONBOARDING,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.ONBOARDING) {
                OnboardingScreen(onFinished = {
                    onOnboardingFinished()
                    askNotificationPermission()
                    navController.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                })
            }
            composable(Routes.HOME, deepLinks = listOf(navDeepLink { uriPattern = Routes.DEEP_LINK_BASE + Routes.HOME })) {
                HomeScreen(
                    onOpenPlan = { navController.navigateTab(Routes.PLANO) },
                    onOpenForecast = { navController.navigateTab("${Routes.PLANO}?aba=${PlanoTab.PREVISAO.name}") },
                    onOpenMore = { navController.navigateTab(Routes.MAIS) },
                    onMessage = { scope.launch { snackbar.showSnackbar(it) } },
                )
            }
            composable(Routes.GASTOS, deepLinks = listOf(navDeepLink { uriPattern = Routes.DEEP_LINK_BASE + Routes.GASTOS })) {
                WeekScreen(onMessage = { scope.launch { snackbar.showSnackbar(it) } })
            }
            composable(
                Routes.PLANO + "?aba={aba}",
                arguments = listOf(navArgument("aba") { type = NavType.StringType; defaultValue = PlanoTab.CARTOES.name }),
                deepLinks = listOf(navDeepLink { uriPattern = Routes.DEEP_LINK_BASE + Routes.PLANO + "?aba={aba}" }),
            ) { entry ->
                val tab = runCatching { PlanoTab.valueOf(entry.arguments?.getString("aba").orEmpty()) }.getOrDefault(PlanoTab.CARTOES)
                PlanoScreen(initialTab = tab, onMessage = { scope.launch { snackbar.showSnackbar(it) } })
            }
            composable(Routes.MAIS, deepLinks = listOf(navDeepLink { uriPattern = Routes.DEEP_LINK_BASE + Routes.MAIS })) {
                MoreScreen(update = state.update, onMessage = { scope.launch { snackbar.showSnackbar(it) } })
            }
        }
    }

    if (addExpenseOpen) {
        ModalBottomSheet(
            onDismissRequest = { addExpenseOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = NxusColors.Surface,
        ) {
            AddExpenseSheet(onDone = { message ->
                addExpenseOpen = false
                message?.let { scope.launch { snackbar.showSnackbar(it) } }
            })
        }
    }
    if (state.startupError != null) {
        AlertCard("Algo deu errado", state.startupError, AlertKind.DANGER, Modifier.padding(16.dp))
    }
}

/** Troca de aba sem empilhar telas: voltar sempre leva à Início. */
private fun NavHostController.navigateTab(route: String) = navigate(route) {
    popUpTo(Routes.HOME) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
private fun BottomBar(navController: NavHostController, route: String?) {
    NavigationBar(containerColor = NxusColors.Surface) {
        tabs.forEach { tab ->
            NavigationBarItem(
                selected = route?.substringBefore('?') == tab.route,
                onClick = { navController.navigateTab(tab.route) },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = NxusColors.AccentLight,
                    selectedTextColor = NxusColors.AccentLight,
                    indicatorColor = NxusColors.SurfaceHigh,
                    unselectedIconColor = NxusColors.TextMuted,
                    unselectedTextColor = NxusColors.TextMuted,
                ),
            )
        }
    }
}

// ---------- Bloqueio ----------

private const val RELOCK_AFTER_MS = 5 * 60 * 1000L

/**
 * Bloqueio ao abrir: biometria forte ou PIN/padrão/senha do aparelho (na API 28–29 o Android não
 * aceita BIOMETRIC_STRONG junto com DEVICE_CREDENTIAL). Bloqueia de novo depois de 5 minutos em
 * segundo plano. Sem bloqueio de tela configurado no aparelho, não há como autenticar e o app abre.
 */
@Composable
fun AppLock(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val authenticators = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_WEAK or DEVICE_CREDENTIAL
    val canAuthenticate = remember { BiometricManager.from(context).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS }
    var unlocked by rememberSaveable { mutableStateOf(!canAuthenticate) }
    var error by remember { mutableStateOf<String?>(null) }
    var stoppedAt by rememberSaveable { mutableStateOf(0L) }

    fun prompt() {
        val host = activity ?: return
        error = null
        BiometricPrompt(host, ContextCompat.getMainExecutor(host), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { unlocked = true }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_CANCELED) error = errString.toString()
            }
        }).authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Abrir NxusLife")
                .setSubtitle("Use a digital ou o bloqueio do aparelho")
                .setAllowedAuthenticators(authenticators)
                .build(),
        )
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, canAuthenticate) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> stoppedAt = SystemClock.elapsedRealtime()
                Lifecycle.Event.ON_START -> if (canAuthenticate && stoppedAt > 0 && SystemClock.elapsedRealtime() - stoppedAt > RELOCK_AFTER_MS) unlocked = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (unlocked) {
        content()
        return
    }
    LaunchedEffect(Unit) { prompt() }
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(painterResource(R.drawable.nxuslife_logo), contentDescription = null, modifier = Modifier.size(96.dp))
        Spacer(Modifier.height(24.dp))
        Text("NxusLife está bloqueado", style = MaterialTheme.typography.titleLarge)
        error?.let { Text(it, color = NxusColors.Danger, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(24.dp))
        Button(onClick = ::prompt) { Text("Desbloquear") }
    }
}
