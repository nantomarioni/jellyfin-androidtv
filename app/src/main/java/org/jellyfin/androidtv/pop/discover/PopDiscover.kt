package org.jellyfin.androidtv.pop.discover

import kotlinx.serialization.Serializable

/**
 * Answer of `GET /PopRequests/discover` (jellyfin-plugin-pop-requests, `Services/DiscoverModel.cs`):
 * titles Seerr suggests to the viewer. Unknown fields are ignored, missing ones take the defaults.
 */
@Serializable
data class PopDiscover(
	val hasSeerrUser: Boolean = true,
	val kind: String = PopDiscoverKind.RECOMMENDED.key,
	val page: Int = 1,
	val totalPages: Int = 1,
	val items: List<PopDiscoverItem> = emptyList(),
	val errors: List<String> = emptyList(),
)

@Serializable
data class PopDiscoverItem(
	val tmdbId: Int = 0,
	val mediaType: String = "movie",
	val title: String = "",
	val year: Int? = null,
	val posterPath: String? = null,
	val overview: String? = null,
	val voteAverage: Double? = null,
	val status: String = STATUS_NONE,
) {
	val isSeries get() = mediaType == "tv"
	val key get() = "$mediaType:$tmdbId"

	companion object {
		const val STATUS_NONE = "none"
		const val STATUS_REQUESTED = "requested"
		const val STATUS_AVAILABLE = "available"
	}
}

/** Answer of `POST /PopRequests/request`. */
@Serializable
data class PopRequestResult(
	val ok: Boolean = false,
	val error: String? = null,
	val message: String? = null,
	val status: String = PopDiscoverItem.STATUS_NONE,
) {
	companion object {
		const val ERROR_NO_SEERR_USER = "noSeerrUser"
	}
}

/** The `kind` values the plugin accepts, in the order the picker shows them. */
enum class PopDiscoverKind(val key: String) {
	RECOMMENDED("recommended"),
	TRENDING("trending"),
	MOVIES("movies"),
	TV("tv"),
}
