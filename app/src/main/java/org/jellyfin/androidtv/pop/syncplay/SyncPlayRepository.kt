package org.jellyfin.androidtv.pop.syncplay

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.coroutineScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.syncPlayApi
import org.jellyfin.sdk.api.client.extensions.timeSyncApi
import org.jellyfin.sdk.api.sockets.subscribe
import org.jellyfin.sdk.model.api.GroupInfoDto
import org.jellyfin.sdk.model.api.GroupStateType
import org.jellyfin.sdk.model.api.IgnoreWaitRequestDto
import org.jellyfin.sdk.model.api.JoinGroupRequestDto
import org.jellyfin.sdk.model.api.NewGroupRequestDto
import org.jellyfin.sdk.model.api.PingRequestDto
import org.jellyfin.sdk.model.api.SyncPlayCommandMessage
import org.jellyfin.sdk.model.api.SyncPlayGroupDoesNotExistUpdate
import org.jellyfin.sdk.model.api.SyncPlayGroupJoinedUpdate
import org.jellyfin.sdk.model.api.SyncPlayGroupLeftUpdate
import org.jellyfin.sdk.model.api.SyncPlayGroupUpdateMessage
import org.jellyfin.sdk.model.api.SyncPlayLibraryAccessDeniedUpdate
import org.jellyfin.sdk.model.api.SyncPlayNotInGroupUpdate
import org.jellyfin.sdk.model.api.SyncPlayPlayQueueUpdate
import org.jellyfin.sdk.model.api.SyncPlayStateUpdate
import org.jellyfin.sdk.model.api.SyncPlayUserJoinedUpdate
import org.jellyfin.sdk.model.api.SyncPlayUserLeftUpdate
import timber.log.Timber
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

data class SyncPlayGroup(
	val id: UUID,
	val name: String,
	val state: GroupStateType,
	val participants: List<String>,
)

data class SyncPlayState(
	/** The group this device is in, if any. */
	val group: SyncPlayGroup? = null,
	/** Whether this device's player follows the group (experimental); off = group membership only. */
	val playerSync: Boolean = false,
	/** Round trip to the server in milliseconds, once measured. */
	val pingMs: Long? = null,
)

/** The SDK reads and writes dates as local date-times in the system zone. */
internal fun LocalDateTime.toEpochMs(): Long = atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
internal fun epochMsToLocalDateTime(ms: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())

private fun GroupInfoDto.toGroup() = SyncPlayGroup(groupId, groupName.orEmpty(), state, participants.orEmpty())

/**
 * SyncPlay ("watch together"): groups on the server, this device's membership, the clock difference
 * with the server, and the group's messages from the websocket. The player side is [SyncPlayPlayerSync].
 * Nothing here runs unless the device is in a group, except the websocket subscription.
 */
class SyncPlayRepository(
	context: Context,
	private val api: ApiClient,
	private val playerSync: SyncPlayPlayerSync,
	lifecycle: Lifecycle,
) {
	private companion object {
		const val PREFERENCE_PLAYER_SYNC = "player_sync"
		const val TIME_SYNC_FAST_ROUNDS = 4
		const val TIME_SYNC_FAST_MS = 2_000L
		const val TIME_SYNC_SLOW_MS = 60_000L
		const val TIME_SYNC_KEEP = 8
	}

	private val preferences = context.getSharedPreferences("pop_syncplay", Context.MODE_PRIVATE)
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
	private val _state = MutableStateFlow(SyncPlayState(playerSync = preferences.getBoolean(PREFERENCE_PLAYER_SYNC, false)))
	val state = _state.asStateFlow()

	// (round trip, offset) of the latest measurements; the one with the shortest round trip is the most exact
	private val measurements = ArrayDeque<Pair<Long, Long>>()
	@Volatile
	private var offsetMs = 0L
	private var groupJob: Job? = null

	init {
		playerSync.serverNowMs = ::serverNowMs

		// Like upstream's SocketHandler: the websocket only stays open while the app is in front
		lifecycle.coroutineScope.launch(Dispatchers.IO) {
			lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
				api.webSocket.subscribe<SyncPlayGroupUpdateMessage>()
					.onEach { message -> runCatching { onGroupUpdate(message) }.onFailure { Timber.w(it, "SyncPlay: group update failed") } }
					.launchIn(this)
				api.webSocket.subscribe<SyncPlayCommandMessage>()
					.onEach { message ->
						val command = message.data
						@Suppress("SENSELESS_COMPARISON")
						if (command != null && _state.value.group != null && _state.value.playerSync) playerSync.onCommand(command)
					}
					.launchIn(this)
			}
		}
	}

	/** Server time now, in epoch milliseconds, as estimated from the time sync. */
	fun serverNowMs(): Long = System.currentTimeMillis() + offsetMs

	suspend fun getGroups(): List<SyncPlayGroup>? = attempt("list groups") {
		api.syncPlayApi.syncPlayGetGroups().content.map { it.toGroup() }
	}

	suspend fun createGroup(name: String): Boolean = attempt("create group") {
		val group = api.syncPlayApi.syncPlayCreateGroup(NewGroupRequestDto(name)).content
		// The websocket sends the same; this covers a missed message
		onJoined(group.toGroup())
	} != null

	suspend fun joinGroup(id: UUID): Boolean = attempt("join group") {
		api.syncPlayApi.syncPlayJoinGroup(JoinGroupRequestDto(id))
	} != null

	suspend fun leaveGroup(): Boolean = attempt("leave group") {
		api.syncPlayApi.syncPlayLeaveGroup()
		onLeft()
	} != null

	fun setPlayerSync(enabled: Boolean) {
		preferences.edit().putBoolean(PREFERENCE_PLAYER_SYNC, enabled).apply()
		_state.update { it.copy(playerSync = enabled) }
		if (_state.value.group != null) restartGroupJobs()
	}

	private fun onGroupUpdate(message: SyncPlayGroupUpdateMessage) {
		when (val update = message.data) {
			is SyncPlayGroupJoinedUpdate -> update.data?.toGroup()?.let(::onJoined)
			is SyncPlayGroupLeftUpdate,
			is SyncPlayNotInGroupUpdate,
			is SyncPlayGroupDoesNotExistUpdate,
			is SyncPlayLibraryAccessDeniedUpdate -> onLeft()

			is SyncPlayUserJoinedUpdate -> updateGroup { it.copy(participants = (it.participants + listOfNotNull(update.data)).distinct()) }
			is SyncPlayUserLeftUpdate -> updateGroup { it.copy(participants = it.participants - listOfNotNull(update.data).toSet()) }
			is SyncPlayStateUpdate -> update.data?.state?.let { state -> updateGroup { it.copy(state = state) } }
			is SyncPlayPlayQueueUpdate -> update.data?.let { queue ->
				if (_state.value.group != null) playerSync.onQueue(queue, apply = _state.value.playerSync)
			}

			else -> Unit
		}
	}

	private fun updateGroup(block: (SyncPlayGroup) -> SyncPlayGroup) = _state.update { state ->
		state.copy(group = state.group?.let(block))
	}

	private fun onJoined(group: SyncPlayGroup) {
		val wasIn = _state.value.group?.id == group.id
		_state.update { it.copy(group = group) }
		if (!wasIn) restartGroupJobs()
	}

	private fun onLeft() {
		groupJob?.cancel()
		groupJob = null
		playerSync.reset()
		_state.update { it.copy(group = null) }
	}

	/** While in a group: keep the clock difference fresh, and follow the player when asked to. */
	private fun restartGroupJobs() {
		groupJob?.cancel()
		val followPlayer = _state.value.playerSync
		groupJob = scope.launch {
			// A device that doesn't follow must not keep the others waiting for it
			attempt("set ignore wait") { api.syncPlayApi.syncPlaySetIgnoreWait(IgnoreWaitRequestDto(!followPlayer)) }

			launch {
				var round = 0
				while (true) {
					measureTime()
					delay(if (round++ < TIME_SYNC_FAST_ROUNDS) TIME_SYNC_FAST_MS else TIME_SYNC_SLOW_MS)
				}
			}
			if (followPlayer) launch { playerSync.run() } else playerSync.reset()
		}
	}

	/** One NTP-style exchange with the server's time endpoint. */
	private suspend fun measureTime() {
		val measurement = attempt("time sync") {
			val sent = System.currentTimeMillis()
			val response = api.timeSyncApi.getUtcTime().content
			val received = System.currentTimeMillis()
			val serverReceived = response.requestReceptionTime.toEpochMs()
			val serverSent = response.responseTransmissionTime.toEpochMs()

			val roundTrip = (received - sent) - (serverSent - serverReceived)
			val offset = ((serverReceived - sent) + (serverSent - received)) / 2
			roundTrip.coerceAtLeast(0) to offset
		} ?: return

		val best = synchronized(measurements) {
			measurements.addLast(measurement)
			while (measurements.size > TIME_SYNC_KEEP) measurements.removeFirst()
			measurements.minBy { it.first }
		}
		offsetMs = best.second
		_state.update { it.copy(pingMs = best.first) }

		// The server adds each member's ping to the delay before a group command takes effect
		attempt("ping") { api.syncPlayApi.syncPlayPing(PingRequestDto(best.first / 2)) }
	}

	private suspend fun <T> attempt(what: String, block: suspend () -> T): T? = try {
		withContext(Dispatchers.IO) { block() }
	} catch (err: CancellationException) {
		throw err
	} catch (@Suppress("TooGenericExceptionCaught") err: Exception) {
		Timber.w(err, "SyncPlay: %s failed", what)
		null
	}
}
