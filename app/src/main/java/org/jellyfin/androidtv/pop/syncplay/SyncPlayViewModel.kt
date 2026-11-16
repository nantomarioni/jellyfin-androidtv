package org.jellyfin.androidtv.pop.syncplay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.auth.repository.UserRepository
import java.util.UUID

data class SyncPlayPageState(
	val sync: SyncPlayState = SyncPlayState(),
	/** Groups on the server to join; null until the first answer. */
	val groups: List<SyncPlayGroup>? = null,
	val groupsFailed: Boolean = false,
	/** A create, join or leave request is on its way. */
	val busy: Boolean = false,
	/** The last create, join or leave request failed. */
	val actionFailed: Boolean = false,
)

class SyncPlayViewModel(
	private val repository: SyncPlayRepository,
	private val userRepository: UserRepository,
) : ViewModel() {
	private companion object {
		const val GROUPS_REFRESH_MS = 5_000L
	}

	private val page = MutableStateFlow(SyncPlayPageState())
	val state = combine(page, repository.state) { page, sync -> page.copy(sync = sync) }
		.stateIn(viewModelScope, SharingStarted.Eagerly, SyncPlayPageState(sync = repository.state.value))

	/** Counts the times the page was entered; each one focuses the main button. */
	var entries by mutableIntStateOf(0)
		private set

	private var refreshJob: Job? = null

	/** The page is shown: list the groups, and keep the list fresh while it stays in front. */
	fun onEnter() {
		entries++
		refreshJob?.cancel()
		refreshJob = viewModelScope.launch {
			while (true) {
				if (repository.state.value.group == null) loadGroups()
				delay(GROUPS_REFRESH_MS)
			}
		}
	}

	fun onLeave() {
		refreshJob?.cancel()
	}

	private suspend fun loadGroups() {
		val groups = repository.getGroups()
		page.update {
			if (groups == null) it.copy(groupsFailed = true)
			else it.copy(groups = groups, groupsFailed = false)
		}
	}

	fun createGroup(fallbackName: String) = action {
		repository.createGroup(userRepository.currentUser.value?.name?.takeIf { it.isNotBlank() } ?: fallbackName)
	}

	fun joinGroup(id: UUID) = action { repository.joinGroup(id) }

	fun leaveGroup() = action {
		repository.leaveGroup().also { loadGroups() }
	}

	fun setPlayerSync(enabled: Boolean) = repository.setPlayerSync(enabled)

	private fun action(block: suspend () -> Boolean) {
		if (page.value.busy) return
		page.update { it.copy(busy = true, actionFailed = false) }
		viewModelScope.launch {
			val done = block()
			page.update { it.copy(busy = false, actionFailed = !done) }
		}
	}
}
