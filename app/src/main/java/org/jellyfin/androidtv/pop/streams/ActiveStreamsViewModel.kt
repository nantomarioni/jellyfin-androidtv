package org.jellyfin.androidtv.pop.streams

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.model.api.SessionInfoDto
import timber.log.Timber
import kotlin.time.Duration.Companion.seconds

data class ActiveStreamsState(
	/** False until the first answer (or failure) arrived. */
	val ready: Boolean = false,
	/** Sessions that are playing something, in a stable order. */
	val streams: List<SessionInfoDto> = emptyList(),
	val failed: Boolean = false,
	/** Jellyfin only shows a non-admin their own sessions. */
	val ownOnly: Boolean = false,
)

/** What identifies a stream between refreshes (and its card for focus). */
val SessionInfoDto.streamKey: String
	get() = "stream:" + (id ?: deviceId ?: "${userId}-${nowPlayingItem?.id}")

class ActiveStreamsViewModel(
	private val api: ApiClient,
	userRepository: UserRepository,
) : ViewModel() {
	private companion object {
		val REFRESH_INTERVAL = 5.seconds

		// Sessions idle for longer are stale (a client that went away without stopping)
		const val ACTIVE_WITHIN_SECONDS = 960
	}

	// Jellyfin's sessions API answers a non-admin with their own sessions only. Showing everyone's
	// streams to every user needs an endpoint in jellyfin-plugin-pop-requests (not there yet).
	private val _state = MutableStateFlow(
		ActiveStreamsState(ownOnly = userRepository.currentUser.value?.policy?.isAdministrator != true)
	)
	val state = _state.asStateFlow()

	// Kept here so the scroll position and focused card survive the view being destroyed
	// while another screen (item details) is on top.
	val gridState = LazyGridState()
	var focusedKey: String? = null

	/** Counts the times the page was entered (shown first, or returned to); each one focuses the content. */
	var entries by mutableIntStateOf(0)
		private set

	private var job: Job? = null

	/** The page is shown (again): load now, then keep refreshing while it is visible. */
	fun onEnter() {
		entries++

		job?.cancel()
		job = viewModelScope.launch {
			while (isActive) {
				refresh()
				delay(REFRESH_INTERVAL)
			}
		}
	}

	/** The page is no longer visible: stop refreshing. */
	fun onLeave() {
		job?.cancel()
		job = null
	}

	private suspend fun refresh() {
		val streams = try {
			withContext(Dispatchers.IO) {
				// (controllableByUserId, deviceId, activeWithinSeconds)
				api.sessionApi.getSessions(null, null, ACTIVE_WITHIN_SECONDS).content
			}
				.filter { it.nowPlayingItem != null }
				.sortedWith(compareBy({ it.userName.orEmpty().lowercase() }, { it.streamKey }))
		} catch (err: CancellationException) {
			throw err
		} catch (@Suppress("TooGenericExceptionCaught") err: Exception) {
			Timber.w(err, "Active streams: loading sessions failed")
			null
		}

		_state.update {
			if (streams == null) it.copy(ready = true, failed = true)
			else it.copy(ready = true, failed = false, streams = streams)
		}
	}
}
