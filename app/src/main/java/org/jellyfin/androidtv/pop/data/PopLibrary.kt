package org.jellyfin.androidtv.pop.data

import kotlinx.serialization.Serializable

/**
 * Answer of `GET /PopRequests/library` (jellyfin-plugin-pop-requests, `Services/LibraryModel.cs`).
 * Ids are Jellyfin ids without dashes. Unknown fields are ignored, missing ones take the defaults.
 */
@Serializable
data class PopLibrary(
	val hasSeerrUser: Boolean = true,
	val requester: String = REQUESTER_MINE,
	val sort: String = PopLibrarySort.ADDED.key,
	val hideWatched: Boolean = false,
	val people: List<PopLibraryPerson> = emptyList(),
	val items: List<PopLibraryItem> = emptyList(),
	val errors: List<String> = emptyList(),
) {
	companion object {
		const val REQUESTER_MINE = "mine"
		const val REQUESTER_ALL = "all"
	}
}

@Serializable
data class PopLibraryPerson(
	val jellyfinUserId: String = "",
	val user: String = "",
	val mine: Boolean = false,
	val count: Int = 0,
)

@Serializable
data class PopLibraryItem(
	val id: String = "",
	val mediaType: String = "movie",
	val title: String = "",
	val year: Int? = null,
	val posterTag: String? = null,
	val rating: Float? = null,
	val played: Boolean = false,
	val playedPercentage: Double? = null,
	val unplayedCount: Int? = null,
	val requests: List<PopLibraryRequest> = emptyList(),
) {
	val isSeries get() = mediaType == "tv"
}

@Serializable
data class PopLibraryRequest(
	val id: Int = 0,
	val user: String = "",
	val jellyfinUserId: String? = null,
	val mine: Boolean = false,
)

/** The `sort` values the plugin accepts, in the order the picker shows them. */
enum class PopLibrarySort(val key: String) {
	ADDED("added"),
	TITLE("title"),
	RELEASED("released"),
	RATING("rating"),
	REQUESTED("requested");

	companion object {
		fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: ADDED
	}
}
