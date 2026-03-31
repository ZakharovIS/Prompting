package ru.zis.prompting

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.zis.prompting.db.WeatherRecord
import ru.zis.prompting.db.WeatherRepository

class WeatherHistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: WeatherRepository = (application as App).weatherRepository

    val records = mutableStateListOf<WeatherRecord>()
    var loading by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    init {
        refresh()
    }

    fun refresh() {
        if (loading) return
        loading = true
        error = null

        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.getAll() }
                .onSuccess { data ->
                    withContext(Dispatchers.Main) {
                        records.clear()
                        records.addAll(data)
                        loading = false
                    }
                }
                .onFailure { t ->
                    withContext(Dispatchers.Main) {
                        error = t.message ?: t.toString()
                        loading = false
                    }
                }
        }
    }

    fun clearAll() {
        if (loading) return
        loading = true
        error = null

        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.clearAll() }
                .onSuccess {
                    val data = repository.getAll()
                    withContext(Dispatchers.Main) {
                        records.clear()
                        records.addAll(data)
                        loading = false
                    }
                }
                .onFailure { t ->
                    withContext(Dispatchers.Main) {
                        error = t.message ?: t.toString()
                        loading = false
                    }
                }
        }
    }
}
