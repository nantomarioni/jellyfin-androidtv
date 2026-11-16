package org.jellyfin.androidtv.pop.data

import android.content.Context
import androidx.core.content.edit
import java.util.UUID

data class PopLibraryFilter(
	val requester: String = PopLibrary.REQUESTER_MINE,
	val sort: PopLibrarySort = PopLibrarySort.ADDED,
	val hideWatched: Boolean = false,
)

/**
 * The My library choices (requester, sort, hide watched), remembered on this device per user.
 */
class PopLibraryPreferences(context: Context) {
	private val preferences = context.getSharedPreferences("pop_library", Context.MODE_PRIVATE)

	fun get(userId: UUID) = PopLibraryFilter(
		requester = preferences.getString("$userId.requester", null) ?: PopLibrary.REQUESTER_MINE,
		sort = PopLibrarySort.fromKey(preferences.getString("$userId.sort", null)),
		hideWatched = preferences.getBoolean("$userId.hideWatched", false),
	)

	fun set(userId: UUID, filter: PopLibraryFilter) = preferences.edit {
		putString("$userId.requester", filter.requester)
		putString("$userId.sort", filter.sort.key)
		putBoolean("$userId.hideWatched", filter.hideWatched)
	}
}
