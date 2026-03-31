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
import ru.zis.prompting.agent.InvariantItem
import ru.zis.prompting.db.InvariantRepository
import ru.zis.prompting.db.UserProfileRepository
import ru.zis.prompting.profile.UserProfile

class ProfileViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: UserProfileRepository =
        (application as App).userProfileRepository

    private val invariantRepository: InvariantRepository =
        (application as App).invariantRepository

    val profiles = mutableStateListOf<UserProfile>()
    val invariants = mutableStateListOf<InvariantItem>()

    var activeProfileId by mutableStateOf<String?>(null)
        private set

    var loading by mutableStateOf(true)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val items = repository.getAll()
                val active = repository.getActive()
                val loadedInvariants = invariantRepository.getAll()
                withContext(Dispatchers.Main) {
                    profiles.clear()
                    profiles.addAll(items)
                    invariants.clear()
                    invariants.addAll(loadedInvariants)
                    activeProfileId = active?.id
                    error = null
                    loading = false
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    error = t.message ?: t.toString()
                    loading = false
                }
            }
        }
    }

    fun addInvariant(rule: String) {
        val normalized = rule.trim()
        if (normalized.isBlank()) {
            error = "Инвариант не может быть пустым"
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                invariantRepository.upsert(InvariantItem(rule = normalized))
                val loaded = invariantRepository.getAll()
                withContext(Dispatchers.Main) {
                    invariants.clear()
                    invariants.addAll(loaded)
                    error = null
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    error = t.message ?: t.toString()
                }
            }
        }
    }

    fun deleteInvariant(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                invariantRepository.delete(id)
                val loaded = invariantRepository.getAll()
                withContext(Dispatchers.Main) {
                    invariants.clear()
                    invariants.addAll(loaded)
                    error = null
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    error = t.message ?: t.toString()
                }
            }
        }
    }

    fun saveProfile(
        profileId: String?,
        name: String,
        style: String,
        constraints: String,
        context: String,
        setActive: Boolean
    ) {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) {
            error = "Название профиля не может быть пустым"
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val profile = UserProfile(
                    id = profileId ?: UserProfile(name = trimmedName).id,
                    name = trimmedName,
                    style = style.trim(),
                    constraints = constraints.trim(),
                    context = context.trim()
                )
                repository.save(profile, setActive = setActive)
                val items = repository.getAll()
                val active = repository.getActive()
                withContext(Dispatchers.Main) {
                    profiles.clear()
                    profiles.addAll(items)
                    activeProfileId = active?.id
                    error = null
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    error = t.message ?: t.toString()
                }
            }
        }
    }

    fun setActive(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.setActive(id)
                val active = repository.getActive()
                withContext(Dispatchers.Main) {
                    activeProfileId = active?.id
                    error = null
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    error = t.message ?: t.toString()
                }
            }
        }
    }

    fun deleteProfile(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.delete(id)
                val items = repository.getAll()
                val active = repository.getActive()
                withContext(Dispatchers.Main) {
                    profiles.clear()
                    profiles.addAll(items)
                    activeProfileId = active?.id
                    error = null
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    error = t.message ?: t.toString()
                }
            }
        }
    }
}
