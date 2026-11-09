package com.therealcam.ui.settings

import android.app.Application
import android.hardware.camera2.CameraManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.therealcam.ai.ModelFileInfo
import com.therealcam.appGraph
import com.therealcam.camera.LensProbe
import com.therealcam.core.RootCamera
import com.therealcam.data.AppSettings
import com.therealcam.data.db.CategoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val g = app.appGraph
    val settings: StateFlow<AppSettings> = g.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val models: StateFlow<List<ModelFileInfo>> = g.models.files
    val modelError: StateFlow<String?> = g.models.activeError
    val categories: StateFlow<List<CategoryEntity>> = g.categories.all.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val datasetCount: StateFlow<Int> = g.captures.datasetCount.stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val pendingCount: StateFlow<Int> = g.captures.pendingCount.stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val datasetPath: String = g.paths.dataset.absolutePath

    private val _status = MutableStateFlow<String?>(null)
    val status = _status.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _lenses = MutableStateFlow<List<String>>(emptyList())
    /** Detected lenses of both sides, e.g. "0.6× Ультраширик · 13 мм · 12 МП". */
    val lenses = _lenses.asStateFlow()

    init {
        viewModelScope.launch { g.models.refresh() }
        viewModelScope.launch {
            val manager = app.getSystemService(CameraManager::class.java)
            _lenses.value = withContext(Dispatchers.IO) {
                runCatching {
                    val specs = LensProbe.read(manager)
                    val all = specs.lenses(front = false) + specs.lenses(front = true)
                    all.map { l -> l.description + if (l.hidden) " · скрытый модуль, id ${l.cameraId}" else "" } +
                        "Камер в списке системы: ${specs.listed.size}, найдено скрытых модулей: ${specs.hidden.size}"
                }.getOrElse { listOf("Не удалось прочитать камеры: ${it.message}") }
            }
        }
    }

    fun clearStatus() {
        _status.value = null
    }

    fun setDatasetMode(on: Boolean) = viewModelScope.launch { g.settings.setDatasetMode(on) }
    fun setGeotag(on: Boolean) = viewModelScope.launch { g.settings.setGeotag(on) }
    fun setAskRating(on: Boolean) = viewModelScope.launch { g.settings.setAskRating(on) }
    fun setSaveToGallery(on: Boolean) = viewModelScope.launch { g.settings.setSaveToGallery(on) }
    fun setGrid(on: Boolean) = viewModelScope.launch { g.settings.setGrid(on) }
    fun setLevel(on: Boolean) = viewModelScope.launch { g.settings.setLevel(on) }
    fun setVolumeShutter(on: Boolean) = viewModelScope.launch { g.settings.setVolumeShutter(on) }
    fun setVoice(on: Boolean) = viewModelScope.launch { g.settings.setVoice(on) }
    fun setProMode(on: Boolean) = viewModelScope.launch { g.settings.setProMode(on) }
    fun setNoiseReduction(v: Int) = viewModelScope.launch { g.settings.setNoiseReduction(v) }
    fun setEdgeMode(v: Int) = viewModelScope.launch { g.settings.setEdgeMode(v) }
    fun setOisMode(v: Int) = viewModelScope.launch { g.settings.setOisMode(v) }

    private val _rootLog = MutableStateFlow<String?>(null)
    /** Output of the last root script, shown under the switch. */
    val rootLog = _rootLog.asStateFlow()

    /** Root switch: the setting is kept on only when su was granted and the vendor list now contains the app. */
    fun setRootCamera(on: Boolean) = viewModelScope.launch {
        _busy.value = true
        val pkg = getApplication<Application>().packageName
        val r = withContext(Dispatchers.IO) { if (on) RootCamera.enable(pkg) else RootCamera.disable(pkg) }
        if (on) {
            val ok = RootCamera.applied(r, pkg)
            g.settings.setRootCamera(ok)
            _status.value = if (ok) "Root: все модули камеры открыты для TheRealCam. Вернитесь в камеру"
            else "Root не сработал: нет доступа su или прошивка не использует этот список"
        } else {
            g.settings.setRootCamera(false)
            _status.value = "Root-доступ к камерам выключен"
        }
        _rootLog.value = r.output.ifBlank { null }
        _busy.value = false
    }
    fun setJpegQuality(q: Int) = viewModelScope.launch { g.settings.setJpegQuality(q) }
    fun setDatasetImageMaxSide(px: Int) = viewModelScope.launch { g.settings.setDatasetImageMaxSide(px) }

    fun importModel(uri: Uri) = viewModelScope.launch {
        _busy.value = true
        g.models.import(uri)
            .onSuccess {
                _status.value = "Модель «${it.name}» загружена: ${it.classes.size} категорий"
                g.models.select(it.file)
            }
            .onFailure { _status.value = "Не удалось загрузить модель: ${it.message}" }
        _busy.value = false
    }

    fun selectModel(file: File?) = viewModelScope.launch { g.models.select(file) }
    fun deleteModel(file: File) = viewModelScope.launch { g.models.delete(file) }

    fun exportDataset(uri: Uri) = viewModelScope.launch {
        _busy.value = true
        runCatching {
            g.categories.list().let { g.dataset.writeCategories(it) }
            val out = getApplication<Application>().contentResolver.openOutputStream(uri) ?: error("не удалось открыть файл")
            out.use { g.dataset.exportZip(it) }
        }.onSuccess { n -> _status.value = "Экспортировано снимков: $n" }
            .onFailure { _status.value = "Ошибка экспорта: ${it.message}" }
        _busy.value = false
    }

    fun createCategory(name: String, icon: String) = viewModelScope.launch { g.categories.create(name, icon) }
    fun updateCategory(c: CategoryEntity) = viewModelScope.launch { g.categories.update(c) }
    fun deleteCategory(id: String) = viewModelScope.launch { g.categories.delete(id) }
}
