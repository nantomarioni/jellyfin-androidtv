package org.jellyfin.androidtv.pop.requests

import android.content.res.Resources
import androidx.core.os.ConfigurationCompat
import org.jellyfin.androidtv.R
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** How a chip or note is coloured. */
enum class BoardTone { GOOD, INFO, WARN, BAD, MUTED }

data class BoardChip(val label: String, val tone: BoardTone)

/** Split the board into sections; a title may show in two (new episodes + an upgrade). Same rules as the web tab. */
fun boardSections(items: List<PopBoardItem>): Map<BoardSection, List<BoardEntry>> {
	val result = BoardSection.entries.associateWith { mutableListOf<BoardEntry>() }

	items.forEach { item ->
		val fresh = item.downloads.filterNot { it.upgrade }
		val upgrades = item.downloads.filter { it.upgrade }
		val waiting = item.waiting

		if (fresh.isNotEmpty()) {
			result.getValue(BoardSection.DOWNLOADING) += BoardEntry(BoardSection.DOWNLOADING, item, fresh)
		} else if (waiting != null) {
			val section = when (waiting.reason) {
				"unreleased", "nextEpisode" -> BoardSection.LATER
				"unavailable" -> BoardSection.UNAVAILABLE
				else -> BoardSection.STUCK
			}
			result.getValue(section) += BoardEntry(section, item, emptyList())
		}
		if (upgrades.isNotEmpty()) {
			result.getValue(BoardSection.UPGRADING) += BoardEntry(BoardSection.UPGRADING, item, upgrades)
		}
	}

	fun lastActivity(entry: BoardEntry) = maxOf(
		entry.item.downloads.maxOfOrNull { parseInstant(it.added)?.toEpochMilli() ?: 0L } ?: 0L,
		entry.item.requests.maxOfOrNull { parseInstant(it.createdAt)?.toEpochMilli() ?: 0L } ?: 0L,
	)

	return result.mapValues { (section, entries) ->
		if (section == BoardSection.LATER) {
			entries.sortedBy { parseInstant(it.item.waiting?.releaseDate)?.toEpochMilli() ?: Long.MAX_VALUE }
		} else {
			entries.sortedByDescending(::lastActivity)
		}
	}
}

fun parseInstant(iso: String?): Instant? = iso?.let {
	runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull()
}

/**
 * The board's phrases in the viewer's language, built from the structured fields the plugin
 * sends (it never sends sentences): the same wording as the web tab's `STRINGS`.
 */
@Suppress("TooManyFunctions", "MagicNumber")
class BoardText(private val res: Resources) {
	private val locale: Locale get() = ConfigurationCompat.getLocales(res.configuration)[0] ?: Locale.getDefault()

	fun sectionTitle(section: BoardSection) = res.getString(
		when (section) {
			BoardSection.DOWNLOADING -> R.string.pop_requests_sec_downloading
			BoardSection.STUCK -> R.string.pop_requests_sec_stuck
			BoardSection.LATER -> R.string.pop_requests_sec_later
			BoardSection.UPGRADING -> R.string.pop_requests_sec_upgrading
			BoardSection.UNAVAILABLE -> R.string.pop_requests_sec_unavailable
		}
	)

	fun sectionHint(section: BoardSection) = res.getString(
		when (section) {
			BoardSection.DOWNLOADING -> R.string.pop_requests_hint_downloading
			BoardSection.STUCK -> R.string.pop_requests_hint_stuck
			BoardSection.LATER -> R.string.pop_requests_hint_later
			BoardSection.UPGRADING -> R.string.pop_requests_hint_upgrading
			BoardSection.UNAVAILABLE -> R.string.pop_requests_hint_unavailable
		}
	)

	/** "You, Ana · requested Mon 3 Nov · S01, S02", or that nobody asked for it in Seerr. */
	fun requestLine(item: PopBoardItem): String {
		if (item.requests.isEmpty()) return res.getString(R.string.pop_requests_not_requested)

		val people = item.requests
			.filter { it.status != "declined" }
			.map { if (it.mine) res.getString(R.string.pop_requests_you) else it.user }
			.distinct()
			.joinToString(", ")
		val seasons = item.requests.flatMap { it.seasons }.distinct().sorted()
		val seasonText = when {
			seasons.isEmpty() -> null
			seasons.size > 4 -> res.getString(R.string.pop_requests_seasons, seasons.size)
			else -> seasonList(seasons)
		}
		val requested = day(item.requests.first().createdAt).takeIf { it.isNotEmpty() }
			?.let { res.getString(R.string.pop_requests_requested_on, it) }

		return listOfNotNull(people.ifEmpty { null }, requested, seasonText).joinToString(" · ")
	}

	/** What is happening to a download right now. */
	fun downloadState(download: PopBoardDownload): BoardChip {
		val state = download.torrent?.state.orEmpty()
		fun chip(label: Int, tone: BoardTone) = BoardChip(res.getString(label), tone)

		return when {
			download.trackedState == "failed" || download.trackedState == "failedPending" || download.status == "failed" ->
				chip(R.string.pop_requests_st_failed, BoardTone.BAD)

			download.trackedState == "importing" -> chip(R.string.pop_requests_st_importing, BoardTone.INFO)
			download.trackedState == "importPending" ->
				if (download.trackedStatus == "warning") chip(R.string.pop_requests_st_import_blocked, BoardTone.WARN)
				else chip(R.string.pop_requests_st_wait_import, BoardTone.INFO)

			download.torrent == null ->
				if (download.status == "delay") chip(R.string.pop_requests_st_delayed, BoardTone.WARN)
				else chip(R.string.pop_requests_st_not_in_client, BoardTone.WARN)

			state == "metaDL" || state == "forcedMetaDL" -> chip(R.string.pop_requests_st_meta, BoardTone.WARN)
			state == "stalledDL" -> chip(R.string.pop_requests_st_stalled, BoardTone.WARN)
			state == "queuedDL" -> chip(R.string.pop_requests_st_queued, BoardTone.MUTED)
			state == "pausedDL" || state == "stoppedDL" -> chip(R.string.pop_requests_st_paused, BoardTone.MUTED)
			state.startsWith("checking") -> chip(R.string.pop_requests_st_checking, BoardTone.MUTED)
			state == "error" || state == "missingFiles" -> chip(R.string.pop_requests_st_error, BoardTone.BAD)
			download.progress >= 1 -> chip(R.string.pop_requests_st_downloaded, BoardTone.INFO)
			else -> chip(R.string.pop_requests_st_downloading, BoardTone.GOOD)
		}
	}

	/** "S01E02 · WEBDL-1080p · indexer · 4.2 GB". */
	fun downloadMeta(download: PopBoardDownload) = listOfNotNull(
		download.episodes?.ifBlank { null },
		download.quality?.ifBlank { null },
		download.indexer?.ifBlank { null },
		if (download.size > 0) bytes(download.size) else null,
	).joinToString(" · ")

	/** Speed and time left, or how long it has been without data. */
	fun downloadProgress(download: PopBoardDownload): String {
		val torrent = download.torrent
		val state = torrent?.state.orEmpty()
		val noMeta = state == "metaDL" || state == "forcedMetaDL"
		val stalledHours = download.race?.stalledHours ?: 0.0
		val eta = torrent?.eta?.takeIf { it in 1 until INFINITE_ETA_SECONDS }

		return when {
			torrent != null && torrent.downloadSpeed > 0 -> listOfNotNull(
				bytes(torrent.downloadSpeed) + "/s",
				eta?.let { res.getString(R.string.pop_requests_left, duration(it.toDouble())) },
			).joinToString(" · ")

			noMeta && download.added != null -> res.getString(R.string.pop_requests_no_meta, relative(download.added))
			stalledHours >= 1 -> res.getString(R.string.pop_requests_no_data, duration(stalledHours * 3600))
			torrent?.lastActivity != null && download.progress < 1 ->
				res.getString(R.string.pop_requests_last_data, relative(torrent.lastActivity))

			else -> ""
		}
	}

	/** download-race's verdict about a stalled download; null when there is none worth showing. */
	fun raceNote(race: PopBoardRace?): Pair<String, BoardTone>? {
		if (race == null || race.phase.isEmpty() || race.phase == "moving") return null

		val stalled = duration(race.stalledHours * 3600)
		val searched = relative(race.searchedAt)
		val (phase, text) = when (race.phase) {
			"stalled" -> R.string.pop_requests_rp_stalled to
				res.getString(R.string.pop_requests_r_stalled, stalled, number(race.raceAfterHours.takeIf { it > 0 } ?: 6.0))

			"multi_pack" -> R.string.pop_requests_rp_multi_pack to res.getString(R.string.pop_requests_r_multi_pack, stalled)
			"other_moving" -> R.string.pop_requests_rp_other_moving to res.getString(R.string.pop_requests_r_other_moving)
			"racing" -> R.string.pop_requests_rp_racing to res.getString(R.string.pop_requests_r_racing, searched)
			"grabbed" -> R.string.pop_requests_rp_grabbed to res.getString(R.string.pop_requests_r_grabbed, race.grabbed.orEmpty())
			"searching" -> R.string.pop_requests_rp_searching to res.getString(R.string.pop_requests_r_searching)
			"no_alternative" -> R.string.pop_requests_rp_no_alternative to res.getString(
				if (race.stage == "any") R.string.pop_requests_r_no_alt_any else R.string.pop_requests_r_no_alt_good,
				searched,
				race.minSeeders.takeIf { it > 0 } ?: 5,
			)

			"max_racers" -> R.string.pop_requests_rp_max_racers to
				res.getString(R.string.pop_requests_r_max_racers, race.racers, race.maxRacers)

			else -> return race.detail.takeIf { it.isNotBlank() }?.let { it to BoardTone.MUTED }
		}
		val tone = when (race.phase) {
			"stalled", "multi_pack", "no_alternative", "max_racers" -> BoardTone.WARN
			else -> BoardTone.INFO
		}

		return "${res.getString(R.string.pop_requests_race_title)} · ${res.getString(phase)}: $text" to tone
	}

	/** Why nothing is downloading, as a chip. */
	fun waitingChip(waiting: PopBoardWaiting): BoardChip = when (waiting.reason) {
		"approval" -> BoardChip(res.getString(R.string.pop_requests_w_approval), BoardTone.MUTED)
		"notAdded" -> BoardChip(res.getString(R.string.pop_requests_w_not_added), BoardTone.BAD)
		"unreleased" -> BoardChip(res.getString(R.string.pop_requests_w_unreleased), BoardTone.INFO)
		"nextEpisode" -> BoardChip(res.getString(R.string.pop_requests_w_next_episode), BoardTone.INFO)
		"notFound" -> BoardChip(res.getString(R.string.pop_requests_w_not_found), BoardTone.WARN)
		"unavailable" -> BoardChip(res.getString(R.string.pop_requests_w_unavailable), BoardTone.MUTED)
		else -> BoardChip(waiting.reason, BoardTone.MUTED)
	}

	/** Why nothing is downloading, as a sentence. */
	fun waitingText(item: PopBoardItem, waiting: PopBoardWaiting): String {
		val date = day(waiting.releaseDate)
		val app = if (item.isSeries) "Sonarr" else "Radarr"

		return when (waiting.reason) {
			"approval" -> res.getString(R.string.pop_requests_wd_approval)
			"unavailable" -> res.getString(R.string.pop_requests_wd_unavailable)
			"notAdded" -> res.getString(if (waiting.failed) R.string.pop_requests_wd_failed else R.string.pop_requests_wd_not_added, app)
			"unreleased" -> when (waiting.dateKind) {
				"digital" -> res.getString(R.string.pop_requests_wd_digital, date)
				"physical" -> res.getString(R.string.pop_requests_wd_physical, date)
				"cinemas" -> res.getString(R.string.pop_requests_wd_cinemas, date)
				"cinemasSince" -> res.getString(R.string.pop_requests_wd_cinemas_since, date)
				else -> res.getString(R.string.pop_requests_wd_none)
			}

			"nextEpisode" ->
				if (waiting.releaseDate != null) res.getString(R.string.pop_requests_wd_airing, date)
				else res.getString(R.string.pop_requests_wd_nothing_missing)

			"notFound" -> when {
				!item.isSeries -> res.getString(R.string.pop_requests_wd_movie_not_found)
				waiting.missingEpisodes <= 0 -> res.getString(R.string.pop_requests_wd_nothing_missing)
				else -> res.getQuantityString(
					R.plurals.pop_requests_wd_episodes,
					waiting.missingEpisodes,
					waiting.missingEpisodes,
					seasonList(waiting.missingSeasons),
				)
			}

			else -> waiting.detail
		}
	}

	fun unreachable(sources: List<String>) = res.getString(R.string.pop_requests_unreachable, sources.joinToString(", "))

	private fun seasonList(seasons: List<Int>) = seasons.joinToString(", ") { "S%02d".format(it) }

	private fun number(value: Double, decimals: Int = 0) = String.format(locale, "%.${decimals}f", value)

	private fun bytes(value: Long): String {
		if (value <= 0) return "0 B"
		val units = listOf("B", "KB", "MB", "GB", "TB")
		val index = (ln(value.toDouble()) / ln(1024.0)).toInt().coerceIn(0, units.lastIndex)
		val scaled = value / 1024.0.pow(index)
		return number(scaled, if (scaled >= 100 || index == 0) 0 else 1) + " " + units[index]
	}

	private fun duration(seconds: Double): String {
		val sec = seconds.coerceAtLeast(0.0).roundToLong()
		return when {
			sec < 90 -> res.getString(R.string.pop_requests_u_s, sec.toString())
			sec < 5400 -> res.getString(R.string.pop_requests_u_min, (sec / 60.0).roundToInt().toString())
			sec < 172_800 -> res.getString(R.string.pop_requests_u_h, (sec / 3600.0).roundToInt().toString())
			else -> {
				val days = sec / 86_400.0
				val shown = if (days < 10) (days * 10).roundToInt() / 10.0 else days.roundToInt().toDouble()
				val whole = shown % 1.0 == 0.0
				res.getQuantityString(
					R.plurals.pop_requests_u_d,
					if (whole) shown.toInt() else 2,
					number(shown, if (whole) 0 else 1),
				)
			}
		}
	}

	/** "in 3 h", "2 days ago", "just now". */
	private fun relative(iso: String?): String {
		val instant = parseInstant(iso) ?: return ""
		val diff = (instant.toEpochMilli() - System.currentTimeMillis()) / 1000.0
		return when {
			abs(diff) < 45 -> res.getString(R.string.pop_requests_just_now)
			diff >= 0 -> res.getString(R.string.pop_requests_in_time, duration(diff))
			else -> res.getString(R.string.pop_requests_ago, duration(-diff))
		}
	}

	/** "Mon 3 Nov", with the year when it is not this one. */
	private fun day(iso: String?): String {
		val date = parseInstant(iso)?.atZone(ZoneId.systemDefault()) ?: return ""
		val pattern = if (date.year == java.time.LocalDate.now().year) "EEE d MMM" else "EEE d MMM yyyy"
		return DateTimeFormatter.ofPattern(pattern, locale).format(date)
	}

	private companion object {
		/** qBittorrent reports 8640000 s (100 days) for "unknown". */
		const val INFINITE_ETA_SECONDS = 8_640_000L
	}
}
