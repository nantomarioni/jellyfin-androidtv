package org.jellyfin.androidtv.pop.discover

import androidx.compose.foundation.lazy.grid.LazyGridState
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
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import timber.log.Timber

enum class DiscoverFailure {
	/** The server's plugin has no discover endpoints yet (404). */
	PLUGIN_OUTDATED,
	FAILED,
}

/** What the request dialog shows after "Request" was pressed. */
sealed interface DiscoverRequestState {
	data object Idle : DiscoverRequestState
	data object Sending : DiscoverRequestState
	data object Done : DiscoverRequestState
	data object NoSeerrUser : DiscoverRequestState
	data class Refused(val message: String?) : DiscoverRequestState
}

data class DiscoverState(
	val kind: PopDiscoverKind = PopDiscoverKind.RECOMMENDED,
	/** False until the first answer (or failure) arrived. */
	val ready: Boolean = false,
	val loading: Boolean = false,
	val items: List<PopDiscoverItem> = emptyList(),
	val hasSeerrUser: Boolean = true,
	val failure: DiscoverFailure? = null,
	val page: Int = 0,
	val totalPages: Int = 1,
	val request: DiscoverRequestState = DiscoverRequestState.Idle,
)

class DiscoverViewModel(
	private val discoverApi: PopDiscoverApi,
) : ViewModel() {
	private companion object {
		const val HTTP_NOT_FOUND = 404
		const val LOAD_MORE_DISTANCE = 14
	}

	private val _state = MutableStateFlow(DiscoverState())
	val state = _state.asStateFlow()

	// Kept here so the scroll position and focused card survive the view being destroyed
	val gridState = LazyGridState()
	var focusedKey: String? = null

	/** Counts the times the page was entered; each one focuses the content. */
	var entries by mutableIntStateOf(0)
		private set

	private var job: Job? = null

	fun onEnter() {
		entries++
		// Suggestions don't change while away: only load what was never loaded
		if (!_state.value.ready && job?.isActive != true) load(reset = true)
	}

	fun setKind(kind: PopDiscoverKind) {
		if (kind == _state.value.kind) return
		_state.update { it.copy(kind = kind) }
		focusedKey = null
		load(reset = true)
	}

	/** A card got focus: fetch the next page when it is near the end of what is loaded. */
	fun onFocused(index: Int, key: String) {
		focusedKey = key
		val state = _state.value
		val nearEnd = index >= state.items.size - LOAD_MORE_DISTANCE
		if (nearEnd && !state.loading && state.failure == null && state.page < state.totalPages) load(reset = false)
	}

	private fun load(reset: Boolean) {
		if (reset) job?.cancel()
		else if (job?.isActive == true) return

		job = viewModelScope.launch {
			val kind = _state.value.kind
			val page = if (reset) 1 else _state.value.page + 1
			_state.update { it.copy(loading = true) }

			try {
				val answer = discoverApi.getDiscover(kind, page)
				_state.update { state ->
					val known = if (reset) mutableSetOf<String>() else state.items.mapTo(mutableSetOf()) { it.key }
					val fresh = answer.items.filter { it.tmdbId > 0 && known.add(it.key) }
					state.copy(
						items = if (reset) fresh else state.items + fresh,
						hasSeerrUser = answer.hasSeerrUser,
						failure = if (reset && fresh.isEmpty() && answer.errors.isNotEmpty()) DiscoverFailure.FAILED else null,
						page = page,
						totalPages = answer.totalPages,
					)
				}
			} catch (err: CancellationException) {
				throw err
			} catch (@Suppress("TooGenericExceptionCaught") err: Exception) {
				Timber.w(err, "Discover: loading %s page %d failed", kind.key, page)
				val failure = when {
					err is InvalidStatusException && err.status == HTTP_NOT_FOUND -> DiscoverFailure.PLUGIN_OUTDATED
					else -> DiscoverFailure.FAILED
				}
				// A failed "more" keeps what is on screen; only a first page shows the failure
				_state.update { if (reset) it.copy(items = emptyList(), failure = failure, page = 0) else it.copy(totalPages = it.page) }
			}

			_state.update { it.copy(loading = false, ready = true) }
		}
	}

	/** The request dialog was opened or closed. */
	fun resetRequest() = _state.update { it.copy(request = DiscoverRequestState.Idle) }

	fun request(item: PopDiscoverItem) {
		if (_state.value.request == DiscoverRequestState.Sending) return
		_state.update { it.copy(request = DiscoverRequestState.Sending) }

		viewModelScope.launch {
			val result = try {
				discoverApi.request(item)
			} catch (err: CancellationException) {
				throw err
			} catch (@Suppress("TooGenericExceptionCaught") err: Exception) {
				Timber.w(err, "Discover: requesting %s failed", item.key)
				null
			}

			_state.update { state ->
				when {
					result == null -> state.copy(request = DiscoverRequestState.Refused(null))
					result.ok -> state.copy(
						request = DiscoverRequestState.Done,
						items = state.items.map {
							if (it.key != item.key) it
							else it.copy(status = result.status.takeUnless { s -> s == PopDiscoverItem.STATUS_NONE } ?: PopDiscoverItem.STATUS_REQUESTED)
						},
					)

					result.error == PopRequestResult.ERROR_NO_SEERR_USER ->
						state.copy(request = DiscoverRequestState.NoSeerrUser, hasSeerrUser = false)

					else -> state.copy(request = DiscoverRequestState.Refused(result.message))
				}
			}
		}
	}
}
