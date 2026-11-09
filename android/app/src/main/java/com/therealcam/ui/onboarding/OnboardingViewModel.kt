package com.therealcam.ui.onboarding

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.therealcam.appGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class OnboardingViewModel(app: Application) : AndroidViewModel(app) {
    private val g = app.appGraph

    private val _needed = MutableStateFlow<Boolean?>(null)

    /**
     * Null while settings load. Decided once at start, so importing a model on the first-run
     * screen does not close it before the user has chosen about dataset collection.
     */
    val needed: StateFlow<Boolean?> = _needed.asStateFlow()

    private val _imported = MutableStateFlow<String?>(null)
    val imported: StateFlow<String?> = _imported.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    init {
        viewModelScope.launch {
            val s = g.settings.current()
            // Installs that already collect data or use a model have effectively been onboarded.
            _needed.value = !(s.onboarded || s.datasetMode || s.activeModel != null)
        }
    }

    fun importModel(uri: Uri) = viewModelScope.launch {
        _busy.value = true
        _error.value = null
        g.models.import(uri)
            .onSuccess {
                g.models.select(it.file)
                _imported.value = "«${it.name}» · категорий: ${it.classes.size}"
            }
            .onFailure { _error.value = "Не удалось загрузить модель: ${it.message}" }
        _busy.value = false
    }

    fun finish(enableDataset: Boolean) = viewModelScope.launch {
        if (enableDataset) g.settings.setDatasetMode(true)
        g.settings.setOnboarded(true)
        _needed.value = false
    }
}
