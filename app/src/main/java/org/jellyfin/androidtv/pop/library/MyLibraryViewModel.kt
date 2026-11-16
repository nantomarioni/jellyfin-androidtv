package org.jellyfin.androidtv.pop.library

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.pop.data.PopLibrary
import org.jellyfin.androidtv.pop.data.PopLibraryFilter
import org.jellyfin.androidtv.pop.data.PopLibraryPreferences
import org.jellyfin.androidtv.pop.data.PopLibrarySort
import org.jellyfin.androidtv.pop.data.PopRequestsApi
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.request.GetNextUpRequest
import org.jellyfin.sdk.model.api.request.GetResumeItemsRequest
import timber.log.Timber
import java.time.LocalDateTime
import java.util.UUID

data class MyLibraryState(
	/** The filter in effect (what the controls show). */
	val filter: PopLibraryFilter = PopLibraryFilter(),
	/** False until the first answer (or failure) of both sources arrived. */
	val ready: Boolean = false,
	val loading: Boolean = false,
	val library: PopLibrary? = null,
	val libraryFailed: Boolean = false,
	val continueWatching: List<BaseItemDto> = emptyList(),
	val continueWatchingFailed: Boolean = false,
)

class MyLibraryViewModel(
	private val api: ApiClient,
	private val popRequestsApi: PopRequestsApi,
	private val preferences: PopLibraryPreferences,
	userRepository: UserRepository,
) : ViewModel() {
	private companion object {
		const val CONTINUE_WATCHING_LIMIT = 16
		const val NEXT_UP_CUTOFF_DAYS = 365L
	}

	private val userId = userRepository.currentUser.value?.id
	private val _state = MutableStateFlow(MyLibraryState(filter = userId?.let(preferences::get) ?: PopLibraryFilter()))
	val state = _state.asStateFlow()

	// Kept here so the scroll position and focused card survive the view being destroyed
	// while another screen (item details, playback) is on top.
	val gridState = LazyGridState()
	val continueWatchingState = LazyListState()
	var focusedKey: String? = null

	/** Counts the times the page was entered (shown first, or returned to); each one focuses the content. */
	var entries by mutableIntStateOf(0)
		private set

	private var job: Job? = null

	fun setRequester(requester: String) = setFilter(_state.value.filter.copy(requester = requester))
	fun setSort(sort: PopLibrarySort) = setFilter(_state.value.filter.copy(sort = sort))
	fun setHideWatched(hideWatched: Boolean) = setFilter(_state.value.filter.copy(hideWatched = hideWatched))

	private fun setFilter(filter: PopLibraryFilter) {
		if (filter == _state.value.filter) return
		userId?.let { preferences.set(it, filter) }
		_state.update { it.copy(filter = filter) }
		refresh()
	}

	/** The page is shown (again): progress and watched state may have changed while away. */
	fun onEnter() {
		entries++
		refresh()
	}

	/** Load both sources again; what is on screen stays until the new answer is in. */
	fun refresh() {
		job?.cancel()
		job = viewModelScope.launch {
			_state.update { it.copy(loading = true) }

			coroutineScope {
				launch {
					val result = attempt("library") { loadLibrary(_state.value.filter) }
					_state.update {
						if (result == null) it.copy(libraryFailed = true)
						else it.copy(library = result.first, filter = result.second, libraryFailed = false)
					}
				}
				launch {
					val result = attempt("continue watching") { loadContinueWatching() }
					_state.update {
						if (result == null) it.copy(continueWatchingFailed = true)
						else it.copy(continueWatching = result, continueWatchingFailed = false)
					}
				}
			}

			_state.update { it.copy(loading = false, ready = true) }
		}
	}

	private suspend fun <T> attempt(what: String, block: suspend () -> T): T? = try {
		block()
	} catch (err: CancellationException) {
		throw err
	} catch (@Suppress("TooGenericExceptionCaught") err: Exception) {
		Timber.w(err, "My library: loading %s failed", what)
		null
	}

	/**
	 * Same fallbacks as the web tab: without a Seerr account "mine" means nothing, so show everyone
	 * (the saved choice stays); a person who no longer has titles here goes back to everyone.
	 */
	private suspend fun loadLibrary(filter: PopLibraryFilter): Pair<PopLibrary, PopLibraryFilter> {
		val library = popRequestsApi.getLibrary(filter.requester, filter.sort, filter.hideWatched)

		val noSeerrUser = library.requester == PopLibrary.REQUESTER_MINE && !library.hasSeerrUser
		val personGone = library.requester != PopLibrary.REQUESTER_MINE &&
			library.requester != PopLibrary.REQUESTER_ALL &&
			library.people.none { it.jellyfinUserId == library.requester }
		if (!noSeerrUser && !personGone) return library to filter

		val everyone = filter.copy(requester = PopLibrary.REQUESTER_ALL)
		if (personGone) userId?.let { preferences.set(it, everyone) }
		return popRequestsApi.getLibrary(everyone.requester, everyone.sort, everyone.hideWatched) to everyone
	}

	/** Jellyfin's Resume (started movies/episodes), then Next Up for shows not already listed. */
	private suspend fun loadContinueWatching(): List<BaseItemDto> = withContext(Dispatchers.IO) {
		coroutineScope {
			val fields = listOf(ItemFields.PRIMARY_IMAGE_ASPECT_RATIO)
			val imageTypes = listOf(ImageType.PRIMARY)

			val resume = async {
				api.itemsApi.getResumeItems(
					GetResumeItemsRequest(
						limit = CONTINUE_WATCHING_LIMIT,
						mediaTypes = listOf(MediaType.VIDEO),
						fields = fields,
						enableImageTypes = imageTypes,
						imageTypeLimit = 1,
						enableTotalRecordCount = false,
					)
				).content.items
			}
			val nextUp = async {
				api.tvShowsApi.getNextUp(
					GetNextUpRequest(
						limit = CONTINUE_WATCHING_LIMIT,
						enableResumable = false,
						nextUpDateCutoff = LocalDateTime.now().minusDays(NEXT_UP_CUTOFF_DAYS),
						fields = fields,
						enableImageTypes = imageTypes,
						imageTypeLimit = 1,
						enableTotalRecordCount = false,
					)
				).content.items
			}

			val seenSeries = mutableSetOf<UUID>()
			buildList {
				resume.await().forEach { item ->
					add(item)
					item.seriesId?.let(seenSeries::add)
				}
				nextUp.await().forEach { item ->
					val seriesId = item.seriesId
					if (seriesId == null || seenSeries.add(seriesId)) add(item)
				}
			}
		}
	}
}
