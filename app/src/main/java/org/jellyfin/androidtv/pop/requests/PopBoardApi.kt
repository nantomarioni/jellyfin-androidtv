package org.jellyfin.androidtv.pop.requests

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpMethod
import java.util.UUID

/**
 * The Requests board and "Requested by" of the pop-requests plugin, asked as the signed-in user
 * (the SDK's [ApiClient] adds the `Authorization: MediaBrowser` header).
 */
class PopBoardApi(
	private val api: ApiClient,
) {
	private val json = Json {
		ignoreUnknownKeys = true
		coerceInputValues = true
		explicitNulls = false
	}

	suspend fun getBoard(): PopBoard = get("/PopRequests/board")

	suspend fun getRequesters(itemId: UUID): PopItemRequesters = get("/PopRequests/requesters/$itemId")

	private suspend inline fun <reified T> get(path: String): T = withContext(Dispatchers.IO) {
		val response = api.request(HttpMethod.GET, path, emptyMap(), emptyMap(), null)
		json.decodeFromString<T>(response.body.decodeToString())
	}
}
