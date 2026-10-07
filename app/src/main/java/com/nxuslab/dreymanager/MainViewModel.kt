package com.nxuslab.dreymanager

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nxuslab.dreymanager.update.HttpUpdateRepository
import com.nxuslab.dreymanager.update.UpdateChecker
import com.nxuslab.dreymanager.update.UpdateState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(
    private val updateChecker: UpdateChecker = UpdateChecker(
        repository = HttpUpdateRepository(BuildConfig.UPDATE_MANIFEST_URL),
        installedVersionCode = BuildConfig.VERSION_CODE.toLong(),
    ),
) : ViewModel() {
    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Checking)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    init {
        viewModelScope.launch {
            _updateState.value = updateChecker.check()
        }
    }
}

