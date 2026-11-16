package org.jellyfin.androidtv.pop.requests

import kotlinx.serialization.Serializable

/**
 * Answer of `GET /PopRequests/board` (jellyfin-plugin-pop-requests, `Services/BoardModel.cs`):
 * what was requested and is not (fully) in the library yet, joined with its downloads.
 * Only the fields the TV page shows; unknown ones are ignored, missing ones take the defaults.
 */
@Serializable
data class PopBoard(
	val isAdmin: Boolean = false,
	val hasSeerrUser: Boolean = true,
	val items: List<PopBoardItem> = emptyList(),
	val errors: List<String> = emptyList(),
)

@Serializable
data class PopBoardItem(
	val key: String = "",
	val mediaType: String = "movie",
	val title: String = "",
	val year: Int? = null,
	val poster: String? = null,
	val inLibrary: Boolean = false,
	val jellyfinId: String? = null,
	val requests: List<PopBoardRequest> = emptyList(),
	val downloads: List<PopBoardDownload> = emptyList(),
	val waiting: PopBoardWaiting? = null,
) {
	val isSeries get() = mediaType == "tv"
}

@Serializable
data class PopBoardRequest(
	val id: Int = 0,
	val status: String = "",
	val createdAt: String? = null,
	val user: String = "",
	val jellyfinUserId: String? = null,
	val mine: Boolean = false,
	val seasons: List<Int> = emptyList(),
)

@Serializable
data class PopBoardDownload(
	val app: String = "",
	val hash: String = "",
	val release: String = "",
	val quality: String? = null,
	val indexer: String? = null,
	val episodes: String? = null,
	val upgrade: Boolean = false,
	val status: String? = null,
	val trackedState: String? = null,
	val trackedStatus: String? = null,
	val size: Long = 0,
	val progress: Double = 0.0,
	val added: String? = null,
	val torrent: PopBoardTorrent? = null,
	val race: PopBoardRace? = null,
)

@Serializable
data class PopBoardTorrent(
	val state: String? = null,
	val seeds: Int = 0,
	val peers: Int = 0,
	val downloadSpeed: Long = 0,
	val eta: Long? = null,
	val lastActivity: String? = null,
)

@Serializable
data class PopBoardRace(
	val phase: String = "",
	val detail: String = "",
	val stalledHours: Double = 0.0,
	val searchedAt: String? = null,
	val stage: String? = null,
	val racers: Int = 0,
	val minSeeders: Int = 0,
	val maxRacers: Int = 0,
	val raceAfterHours: Double = 0.0,
	val grabbed: String? = null,
)

@Serializable
data class PopBoardWaiting(
	val reason: String = "",
	val detail: String = "",
	val releaseDate: String? = null,
	val missingEpisodes: Int = 0,
	val missingSeasons: List<Int> = emptyList(),
	val dateKind: String? = null,
	val failed: Boolean = false,
)

/** Answer of `GET /PopRequests/requesters/{itemId}` (`ItemRequesters` in `Services/LibraryModel.cs`). */
@Serializable
data class PopItemRequesters(
	val itemId: String = "",
	val requests: List<PopBoardRequest> = emptyList(),
)

/** The page's sections, in the order shown (the web tab's `sections()`). */
enum class BoardSection {
	DOWNLOADING,
	STUCK,
	LATER,
	UPGRADING,
	UNAVAILABLE,
}

/** One title inside a section, with the downloads that put it there. */
data class BoardEntry(
	val section: BoardSection,
	val item: PopBoardItem,
	val downloads: List<PopBoardDownload>,
) {
	val key get() = "${section.name}:${item.key}"
}
