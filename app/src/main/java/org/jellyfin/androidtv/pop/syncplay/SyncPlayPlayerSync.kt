package org.jellyfin.androidtv.pop.syncplay

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.ui.playback.PlaybackController
import org.jellyfin.androidtv.ui.playback.PlaybackControllerContainer
import org.jellyfin.androidtv.util.PlaybackHelper
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.syncPlayApi
import org.jellyfin.sdk.model.api.PlayQueueUpdate
import org.jellyfin.sdk.model.api.PlayQueueUpdateReason
import org.jellyfin.sdk.model.api.PlayRequestDto
import org.jellyfin.sdk.model.api.ReadyRequestDto
import org.jellyfin.sdk.model.api.SeekRequestDto
import org.jellyfin.sdk.model.api.SendCommand
import org.jellyfin.sdk.model.api.SendCommandType
import org.jellyfin.sdk.model.api.SyncPlayQueueItem
import timber.log.Timber
import java.util.UUID
import kotlin.math.abs

/**
 * EXPERIMENTAL, off by default (the "Sync my player" switch on the SyncPlay page): makes upstream's
 * legacy video player (`PlaybackController`, the default player) follow a SyncPlay group.
 *
 * It needs no hook in upstream code. Group → player: commands from the websocket are applied to the
 * active `PlaybackController` at their server time. Player → group: while in a group, [run] watches
 * the player and reports what the local user did (pause, resume, seek, started another item) to the
 * server, which then tells everyone, this device included.
 *
 * Not done: buffering reports, queues longer than one item, the rewritten player (`ui/player/video`),
 * audio. Has not been tested against a second client yet.
 */
class SyncPlayPlayerSync(
	private val context: Context,
	private val api: ApiClient,
	private val playbackControllerContainer: PlaybackControllerContainer,
	private val playbackHelper: PlaybackHelper,
) {
	private companion object {
		const val TICKS_PER_MS = 10_000L
		const val WATCH_INTERVAL_MS = 250L
		const val WAIT_STEP_MS = 200L
		const val LOAD_TIMEOUT_MS = 30_000L
		const val SEEK_TIMEOUT_MS = 10_000L

		/** Positions closer than this to the group's are left alone (a seek costs a rebuffer). */
		const val DRIFT_TOLERANCE_MS = 1_500L

		/** A jump larger than this between two looks at the player is a seek by the user. */
		const val SEEK_DETECT_MS = 3_000L

		/** After applying something from the group, player changes within this time are ours, not the user's. */
		const val SETTLE_MS = 2_000L
	}

	private data class Snapshot(val itemId: UUID, val playing: Boolean, val positionMs: Long, val at: Long)

	/** Set by the repository: server time now in epoch milliseconds. */
	var serverNowMs: () -> Long = System::currentTimeMillis

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
	private var applyJob: Job? = null

	// Touched on the main thread only
	private var queue: PlayQueueUpdate? = null
	private var last: Snapshot? = null
	private var quietUntil = 0L
	private var announcedItemId: UUID? = null

	private val controller: PlaybackController? get() = playbackControllerContainer.playbackController
	private val PlaybackController.itemId: UUID? get() = currentlyPlayingItem?.id
	private val PlaybackController.isSettled: Boolean get() = hasInitializedVideoManager() && (isPlaying || isPaused)
	private val currentEntry: SyncPlayQueueItem? get() = queue?.let { it.playlist.getOrNull(it.playingItemIndex) }

	private fun quiet(forMs: Long = SETTLE_MS) {
		quietUntil = maxOf(quietUntil, SystemClock.elapsedRealtime() + forMs)
		last = null
	}

	fun reset() {
		applyJob?.cancel()
		scope.launch {
			queue = null
			last = null
			announcedItemId = null
		}
	}

	/** Runs while the device is in a group with player sync on: reports the local user's actions. */
	suspend fun run() = withContext(Dispatchers.Main) {
		last = null
		while (true) {
			runCatching { watch() }.onFailure { if (it is CancellationException) throw it else Timber.w(it, "SyncPlay: watching the player failed") }
			delay(WATCH_INTERVAL_MS)
		}
	}

	private suspend fun watch() {
		val controller = playbackControllerContainer.playbackController
		val itemId = controller?.itemId
		// Nothing playing, or in between states (buffering, seeking): keep the last look
		if (controller == null || itemId == null || !controller.isSettled) return

		val now = Snapshot(itemId, controller.isPlaying, controller.currentPosition, SystemClock.elapsedRealtime())
		val previous = last
		last = now
		if (now.at < quietUntil) return

		// The user started something the group isn't playing: it becomes the group's queue
		if (itemId != currentEntry?.itemId) {
			if (announcedItemId != itemId) {
				announcedItemId = itemId
				Timber.i("SyncPlay: sharing %s with the group", itemId)
				request("set new queue") {
					syncPlaySetNewQueue(PlayRequestDto(listOf(itemId), 0, now.positionMs * TICKS_PER_MS))
				}
			}
			return
		}

		if (previous == null || previous.itemId != itemId) return
		val moved = now.positionMs - previous.positionMs
		val expected = if (previous.playing && now.playing) now.at - previous.at else 0L
		when {
			previous.playing && !now.playing -> request("pause") { syncPlayPause() }
			!previous.playing && now.playing -> request("unpause") { syncPlayUnpause() }
			abs(moved - expected) > SEEK_DETECT_MS -> request("seek") { syncPlaySeek(SeekRequestDto(now.positionMs * TICKS_PER_MS)) }
		}
	}

	/** The group's queue changed: play its current item here and tell the server when it is ready. */
	fun onQueue(update: PlayQueueUpdate, apply: Boolean) {
		scope.launch {
			queue = update
			if (!apply) return@launch
			val entry = currentEntry ?: return@launch
			when (update.reason) {
				PlayQueueUpdateReason.NEW_PLAYLIST,
				PlayQueueUpdateReason.SET_CURRENT_ITEM,
				PlayQueueUpdateReason.NEXT_ITEM,
				PlayQueueUpdateReason.PREVIOUS_ITEM -> Unit

				else -> return@launch
			}

			applyJob?.cancel()
			applyJob = launch { applying("queue") { load(entry, update.startPositionTicks) } }
		}
	}

	private suspend fun load(entry: SyncPlayQueueItem, startPositionTicks: Long) {
		quiet(LOAD_TIMEOUT_MS)
		announcedItemId = entry.itemId

		val alreadyHere = controller?.let { it.itemId == entry.itemId && it.isSettled } == true
		if (!alreadyHere) {
			Timber.i("SyncPlay: playing the group's item %s", entry.itemId)
			playbackHelper.retrieveAndPlay(listOf(entry.itemId), false, startPositionTicks, 0, context)
		}

		// Hold at the start until the server says go (it waits for every member to be ready)
		val started = waitFor(LOAD_TIMEOUT_MS) { controller?.takeIf { it.itemId == entry.itemId && it.isPlaying } }
		if (started == null) {
			Timber.w("SyncPlay: the group's item did not start here")
			quiet()
			return
		}
		started.pause()
		sendReady(started.currentPosition, entry.playlistItemId)
		quiet()
	}

	/** A playback command for the whole group, to apply at its server time. */
	fun onCommand(command: SendCommand) {
		scope.launch {
			applyJob?.cancel()
			applyJob = launch { applying(command.command.name) { apply(command) } }
		}
	}

	@Suppress("CyclomaticComplexMethod")
	private suspend fun apply(command: SendCommand) {
		val controller = playbackControllerContainer.playbackController ?: return
		if (controller.itemId == null) return

		val positionMs = command.positionTicks?.div(TICKS_PER_MS)
		// Positive: the command takes effect in the future. Negative: we are late by that much.
		val waitMs = command.`when`.toEpochMs() - serverNowMs()
		Timber.i("SyncPlay: %s at %s ms (in %d ms)", command.command, positionMs, waitMs)

		when (command.command) {
			SendCommandType.PAUSE -> {
				quiet()
				if (controller.isPlaying) controller.pause()
			}

			SendCommandType.UNPAUSE -> {
				quiet(SETTLE_MS + waitMs.coerceAtLeast(0))
				if (controller.isPaused) {
					if (waitMs > 0) delay(waitMs)
					controller.play(controller.currentPosition)
				}
				// Where the group is right now; correct only a clear difference
				if (positionMs != null) {
					val groupNowMs = positionMs + (serverNowMs() - command.`when`.toEpochMs()).coerceAtLeast(0)
					if (abs(controller.currentPosition - groupNowMs) > DRIFT_TOLERANCE_MS && controller.canSeek()) {
						controller.seek(groupNowMs)
					}
				}
				quiet()
			}

			SendCommandType.SEEK -> {
				if (positionMs == null) return
				quiet(SEEK_TIMEOUT_MS + SETTLE_MS)
				controller.seek(positionMs)
				// The legacy player plays on after a seek: hold until the server says go
				delay(WAIT_STEP_MS)
				waitFor(SEEK_TIMEOUT_MS) { controller.takeIf { it.isSettled } }
				if (controller.isPlaying) controller.pause()
				sendReady(positionMs, command.playlistItemId)
				quiet()
			}

			SendCommandType.STOP -> {
				quiet()
				controller.endPlayback(true)
			}
		}
	}

	private suspend fun sendReady(positionMs: Long, playlistItemId: UUID) = request("ready") {
		syncPlayReady(
			ReadyRequestDto(
				`when` = epochMsToLocalDateTime(serverNowMs()),
				positionTicks = positionMs * TICKS_PER_MS,
				isPlaying = false,
				playlistItemId = playlistItemId,
			)
		)
	}

	private suspend fun <T : Any> waitFor(timeoutMs: Long, block: () -> T?): T? {
		val deadline = SystemClock.elapsedRealtime() + timeoutMs
		while (SystemClock.elapsedRealtime() < deadline) {
			block()?.let { return it }
			delay(WAIT_STEP_MS)
		}
		return null
	}

	private suspend fun applying(what: String, block: suspend () -> Unit) = try {
		block()
	} catch (err: CancellationException) {
		throw err
	} catch (@Suppress("TooGenericExceptionCaught") err: Exception) {
		Timber.w(err, "SyncPlay: applying %s failed", what)
	}

	private suspend fun request(what: String, block: suspend org.jellyfin.sdk.api.operations.SyncPlayApi.() -> Unit) = try {
		withContext(Dispatchers.IO) { api.syncPlayApi.block() }
	} catch (err: CancellationException) {
		throw err
	} catch (@Suppress("TooGenericExceptionCaught") err: Exception) {
		Timber.w(err, "SyncPlay: %s request failed", what)
	}
}
