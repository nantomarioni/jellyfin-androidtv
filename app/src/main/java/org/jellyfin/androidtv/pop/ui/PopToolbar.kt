package org.jellyfin.androidtv.pop.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.pop.PopDestinations
import org.jellyfin.androidtv.pop.update.PopUpdateButton
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.base.button.ButtonDefaults
import org.jellyfin.androidtv.ui.navigation.Destination
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.koin.compose.koinInject

/** The fork's pages that have a button in the main toolbar. */
enum class PopPage {
	MY_LIBRARY,
	MY_REQUESTS,
	DISCOVER,
	ACTIVE_STREAMS,
	SYNC_PLAY,
}

/** The fork page being shown, so the toolbar can mark its button; null on upstream screens. */
val LocalPopPage = compositionLocalOf<PopPage?> { null }

private data class PopToolbarEntry(
	val page: PopPage,
	@param:StringRes val label: Int,
	val destination: Destination.Fragment,
)

// Narrower than upstream's buttons: five more pills have to fit between the toolbar's sides
private val PopToolbarButtonPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)

private val entries = listOf(
	PopToolbarEntry(PopPage.MY_LIBRARY, R.string.pop_my_library, PopDestinations.myLibrary),
	PopToolbarEntry(PopPage.MY_REQUESTS, R.string.pop_my_requests, PopDestinations.myRequests),
	PopToolbarEntry(PopPage.DISCOVER, R.string.pop_discover, PopDestinations.discover),
	PopToolbarEntry(PopPage.ACTIVE_STREAMS, R.string.pop_active_streams, PopDestinations.activeStreams),
	PopToolbarEntry(PopPage.SYNC_PLAY, R.string.pop_sync_play, PopDestinations.syncPlay),
)

/**
 * The fork's buttons in upstream's `MainToolbar` (its only hook there). Pages act like tabs:
 * opening one starts a fresh history, so Back leaves the app like it does on Home.
 */
@Composable
fun PopToolbarButtons() {
	val navigationRepository = koinInject<NavigationRepository>()
	val activePage = LocalPopPage.current

	// Only shows while a newer release of the fork is available
	PopUpdateButton()

	// Coming up from the page, focus lands on the pill of the page being shown
	val activeFocusRequester = remember { FocusRequester() }
	Row(
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		verticalAlignment = Alignment.CenterVertically,
		modifier = Modifier
			.then(if (activePage != null) Modifier.focusRestorer(activeFocusRequester) else Modifier)
			.focusGroup(),
	) {
		entries.forEach { entry ->
			val active = activePage == entry.page
			Button(
				onClick = {
					if (!active) navigationRepository.reset(entry.destination, clearHistory = true)
				},
				modifier = if (active) Modifier.focusRequester(activeFocusRequester) else Modifier,
				colors = if (active) {
					ButtonDefaults.colors(
						containerColor = JellyfinTheme.colorScheme.buttonActive,
						contentColor = JellyfinTheme.colorScheme.onButtonActive,
					)
				} else {
					ButtonDefaults.colors()
				},
				contentPadding = PopToolbarButtonPadding,
				content = { Text(stringResource(entry.label)) },
			)
		}
	}
}
