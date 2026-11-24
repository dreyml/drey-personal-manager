package com.nxuslab.dreymanager.feature.onboarding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nxuslab.dreymanager.NxusApp
import com.nxuslab.dreymanager.data.repo.Account
import com.nxuslab.dreymanager.data.repo.Category
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class OnboardingUiState(
    val step: Int = 0,
    val draft: OnboardingDraft = OnboardingDraft(),
    val categories: List<Category> = emptyList(),
    /** Só é calculado no passo final. */
    val summary: OnboardingSummary? = null,
    val saving: Boolean = false,
    val error: String? = null,
)

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as NxusApp).repository
    private val today = LocalDate.now()

    private val draft = MutableStateFlow(OnboardingDraft(envelopes = mapOf("delivery" to 0L, "lazer" to 0L)))
    private val step = MutableStateFlow(0)
    private val saving = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    val state: StateFlow<OnboardingUiState> = combine(
        draft, step, saving, error, repository.snapshot.map { it.categories },
    ) { draft, step, saving, error, categories ->
        OnboardingUiState(step, draft, categories, if (step == SUMMARY_STEP) draft.summary(today) else null, saving, error)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OnboardingUiState())

    fun edit(change: (OnboardingDraft) -> OnboardingDraft) = draft.update(change)

    fun next() = step.update { (it + 1).coerceAtMost(SUMMARY_STEP) }

    fun back() = step.update { (it - 1).coerceAtLeast(0) }

    /** Grava tudo de uma vez; [onDone] só é chamado se a gravação deu certo. */
    fun finish(onDone: () -> Unit) {
        if (saving.value) return
        viewModelScope.launch {
            saving.value = true
            error.value = null
            val current = draft.value
            val summary = current.summary(today)
            repository.completeOnboarding(
                account = current.balanceCents?.let { Account(OnboardingDraft.MAIN_ACCOUNT_ID, "Conta principal", it, today) },
                incomes = current.incomes(),
                bills = current.fixedBills(),
                cards = current.cards(),
                debts = current.debts(today),
                envelopes = current.envelopeList(),
                payoffExtraMonthlyCents = summary.appliedExtraCents,
            ).onSuccess { onDone() }.onFailure { error.value = it.message }
            saving.value = false
        }
    }

    companion object {
        const val STEP_COUNT = 5
        const val SUMMARY_STEP = 5
    }
}
