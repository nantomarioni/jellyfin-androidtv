package org.jellyfin.androidtv.pop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.pop.PopDestinations
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.base.button.ButtonDefaults
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.koin.compose.koinInject

/** The fork's pages that have a button in the main toolbar. */
enum class PopPage {
	MY_LIBRARY,
}

/** The fork page being shown, so the toolbar can mark its button; null on upstream screens. */
val LocalPopPage = compositionLocalOf<PopPage?> { null }

/**
 * The fork's buttons in upstream's `MainToolbar` (its only hook there). Pages act like tabs:
 * opening one starts a fresh history, so Back leaves the app like it does on Home.
 */
@Composable
fun PopToolbarButtons() {
	val navigationRepository = koinInject<NavigationRepository>()
	val activePage = LocalPopPage.current

	val myLibraryActive = activePage == PopPage.MY_LIBRARY
	Button(
		onClick = {
			if (!myLibraryActive) navigationRepository.reset(PopDestinations.myLibrary, clearHistory = true)
		},
		colors = if (myLibraryActive) {
			ButtonDefaults.colors(
				containerColor = JellyfinTheme.colorScheme.buttonActive,
				contentColor = JellyfinTheme.colorScheme.onButtonActive,
			)
		} else {
			ButtonDefaults.colors()
		},
		content = { Text(stringResource(R.string.pop_my_library)) },
	)
}
