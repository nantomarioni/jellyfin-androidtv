package org.jellyfin.androidtv.pop.discover

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.exception.InvalidStatusException

/**
 * Seerr through the pop-requests plugin (`/PopRequests/discover`, `/PopRequests/request`): the plugin
 * calls Seerr for the signed-in viewer, so the app needs no Seerr account or password.
 */
class PopDiscoverApi(
	private val api: ApiClient,
) {
	private companion object {
		const val HTTP_FORBIDDEN = 403
	}

	private val json = Json {
		ignoreUnknownKeys = true
		coerceInputValues = true
		explicitNulls = false
	}

	suspend fun getDiscover(kind: PopDiscoverKind, page: Int): PopDiscover = withContext(Dispatchers.IO) {
		val query = mapOf("kind" to kind.key, "page" to page)
		val response = api.request(HttpMethod.GET, "/PopRequests/discover", emptyMap(), query, null)
		json.decodeFromString<PopDiscover>(response.body.decodeToString())
	}

	/** Requests a title as the viewer (a series: every season). A viewer without a Seerr account gets `noSeerrUser`. */
	suspend fun request(item: PopDiscoverItem): PopRequestResult = withContext(Dispatchers.IO) {
		val query = mapOf("mediaType" to item.mediaType, "tmdbId" to item.tmdbId)
		try {
			val response = api.request(HttpMethod.POST, "/PopRequests/request", emptyMap(), query, null)
			json.decodeFromString<PopRequestResult>(response.body.decodeToString())
		} catch (err: InvalidStatusException) {
			if (err.status == HTTP_FORBIDDEN) PopRequestResult(error = PopRequestResult.ERROR_NO_SEERR_USER)
			else throw err
		}
	}
}
