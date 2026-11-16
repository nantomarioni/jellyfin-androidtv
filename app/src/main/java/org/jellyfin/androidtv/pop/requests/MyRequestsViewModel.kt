package org.jellyfin.androidtv.pop.requests

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.auth.repository.UserRepository
import timber.log.Timber

data class MyRequestsState(
	/** Everyone's requests instead of only the viewer's. */
	val everyone: Boolean = false,
	/** False until the first answer (or failure) arrived. */
	val ready: Boolean = false,
	val board: PopBoard? = null,
	val failed: Boolean = false,
)

class MyRequestsViewModel(
	private val boardApi: PopBoardApi,
	private val preferences: RequestsPreferences,
	userRepository: UserRepository,
) : ViewModel() {
	private val userId = userRepository.currentUser.value?.id
	private val _state = MutableStateFlow(MyRequestsState(everyone = userId?.let(preferences::getEveryone) ?: false))
	val state = _state.asStateFlow()

	// Kept here so the scroll position and focused row survive the view being destroyed
	// while another screen (item details) is on top.
	val listState = LazyListState()
	var focusedKey: String? = null

	/** Counts the times the page was entered (shown first, or returned to); each one focuses the content. */
	var entries by mutableIntStateOf(0)
		private set

	private var job: Job? = null

	fun setEveryone(everyone: Boolean) {
		if (everyone == _state.value.everyone) return
		userId?.let { preferences.setEveryone(it, everyone) }
		_state.update { it.copy(everyone = everyone) }
	}

	fun onEnter() {
		entries++
		refresh()
	}

	/** Load the board again; what is on screen stays until the new answer is in. */
	fun refresh() {
		if (job?.isActive == true) return
		job = viewModelScope.launch {
			val board = try {
				boardApi.getBoard()
			} catch (err: CancellationException) {
				throw err
			} catch (@Suppress("TooGenericExceptionCaught") err: Exception) {
				Timber.w(err, "My requests: loading the board failed")
				null
			}

			_state.update {
				if (board == null) it.copy(failed = true, ready = true)
				else it.copy(board = board, failed = false, ready = true)
			}
		}
	}
}
