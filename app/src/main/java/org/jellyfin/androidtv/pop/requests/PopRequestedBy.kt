package org.jellyfin.androidtv.pop.requests

import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.koin.android.ext.android.get
import timber.log.Timber
import kotlin.time.Duration.Companion.seconds

/**
 * The "Requested by X, Y" line on upstream's item details screen (`FullDetailsFragment`), for
 * movies, series, seasons and episodes someone asked for in Seerr.
 *
 * Upstream's screen is Leanback with a presenter that rewrites its views when it rebinds, so this
 * does not add a view: it appends to the genre line (`fdGenreRow`) and puts the text back when the
 * presenter replaced it. Anything going wrong leaves the screen as upstream draws it.
 */
object PopRequestedBy {
	private const val SEPARATOR = "  ·  "
	private val CheckInterval = 1.seconds

	/** Call from the details fragment's `onResume`; works until the fragment is paused. */
	@JvmStatic
	fun attach(fragment: Fragment) {
		val itemId = fragment.arguments?.getString("ItemId")?.toUUIDOrNull() ?: return
		val owner = runCatching { fragment.viewLifecycleOwner }.getOrNull() ?: return

		owner.lifecycleScope.launch {
			val line = try {
				val requesters = fragment.get<PopBoardApi>().getRequesters(itemId)
				val people = requesters.requests
					.map { if (it.mine) fragment.getString(R.string.pop_requests_you) else it.user }
					.filter { it.isNotBlank() }
					.distinct()
				if (people.isEmpty()) return@launch
				fragment.getString(R.string.pop_requests_requested_by, people.joinToString(", "))
			} catch (err: CancellationException) {
				throw err
			} catch (@Suppress("TooGenericExceptionCaught") err: Exception) {
				// Not requested through Seerr (404), plugin missing, offline: no line
				Timber.d(err, "Requested by: nothing for item %s", itemId)
				return@launch
			}

			while (fragment.isResumed) {
				val genreRow = fragment.view?.findViewById<TextView>(R.id.fdGenreRow)
				if (genreRow != null && !genreRow.text.endsWith(line)) {
					val genres = genreRow.text?.toString().orEmpty()
					genreRow.text = if (genres.isBlank()) line else genres + SEPARATOR + line
					genreRow.isVisible = true
				}
				delay(CheckInterval)
			}
		}
	}
}
