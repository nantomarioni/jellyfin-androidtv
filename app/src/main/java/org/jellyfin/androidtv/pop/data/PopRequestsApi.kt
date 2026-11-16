package org.jellyfin.androidtv.pop.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpMethod

/**
 * Client for the pop-requests Jellyfin plugin (`/PopRequests/...`). Requests go through the SDK's
 * [ApiClient], so they carry the signed-in user's `Authorization: MediaBrowser ... Token="..."` header:
 * the plugin answers as that viewer.
 */
class PopRequestsApi(
	private val api: ApiClient,
) {
	private val json = Json {
		ignoreUnknownKeys = true
		coerceInputValues = true
		explicitNulls = false
	}

	suspend fun getLibrary(
		requester: String,
		sort: PopLibrarySort,
		hideWatched: Boolean,
	): PopLibrary = get(
		path = "/PopRequests/library",
		query = mapOf(
			"requester" to requester,
			"sort" to sort.key,
			"hideWatched" to hideWatched,
		),
	)

	private suspend inline fun <reified T> get(
		path: String,
		query: Map<String, Any?> = emptyMap(),
	): T = withContext(Dispatchers.IO) {
		val response = api.request(HttpMethod.GET, path, emptyMap(), query, null)
		json.decodeFromString<T>(response.body.decodeToString())
	}
}
