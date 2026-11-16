package org.jellyfin.androidtv.pop.update

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.ButtonDefaults
import org.jellyfin.androidtv.ui.base.button.ProgressButton
import org.koin.compose.koinInject

private const val PERCENT = 100

/**
 * Toolbar pill offering a newer fork release: nothing while there is none, then "Update to pop.N",
 * the download progress and the hand-over to the system installer. Looks for a release once per
 * app process, the first time it is shown.
 *
 * One and the same button for every state, so it keeps the focus while its text changes.
 */
@Composable
fun PopUpdateButton(
	modifier: Modifier = Modifier,
) {
	val repository = koinInject<PopUpdateRepository>()
	val state by repository.state.collectAsState()

	LaunchedEffect(repository) { repository.check() }

	val current = state
	if (current is PopUpdateState.Idle) return

	val text = when (current) {
		is PopUpdateState.Available -> stringResource(R.string.pop_update_available, current.release.label)
		is PopUpdateState.Downloading -> stringResource(R.string.pop_update_downloading, (current.progress * PERCENT).toInt())
		is PopUpdateState.ReadyToInstall -> stringResource(R.string.pop_update_installing)
		is PopUpdateState.Failed -> stringResource(R.string.pop_update_failed)
		PopUpdateState.Idle -> ""
	}

	ProgressButton(
		progress = (current as? PopUpdateState.Downloading)?.progress ?: 0f,
		// Ignored by the repository while a download is running
		onClick = repository::downloadAndInstall,
		modifier = modifier,
		colors = ButtonDefaults.colors(
			containerColor = JellyfinTheme.colorScheme.buttonActive,
			contentColor = JellyfinTheme.colorScheme.onButtonActive,
		),
	) {
		Icon(
			imageVector = ImageVector.vectorResource(R.drawable.ic_upload),
			contentDescription = null,
			modifier = Modifier.size(16.dp),
		)
		Spacer(Modifier.width(8.dp))
		Text(text)
	}
}
