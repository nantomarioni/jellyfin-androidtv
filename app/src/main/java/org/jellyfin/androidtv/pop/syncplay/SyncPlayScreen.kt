package org.jellyfin.androidtv.pop.syncplay

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.pop.ui.LocalPopPage
import org.jellyfin.androidtv.pop.ui.PopPage
import org.jellyfin.androidtv.pop.ui.TogglePill
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.ProvideTextStyle
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.shared.toolbar.MainToolbar
import org.jellyfin.androidtv.ui.shared.toolbar.MainToolbarActiveButton
import org.jellyfin.sdk.model.api.GroupStateType
import java.util.UUID

/**
 * The SyncPlay page: join or create a group to watch together, see who is in it, leave it.
 * The first button (create, or leave when in a group) is always there and takes the focus on entry,
 * so the page holds focus from its first frame.
 */
@Composable
fun SyncPlayScreen(viewModel: SyncPlayViewModel) {
	val state by viewModel.state.collectAsState()
	val group = state.sync.group
	val mainButton = remember { FocusRequester() }
	val defaultGroupName = stringResource(R.string.pop_syncplay_default_group_name)

	// Entering the page, and joining or leaving a group (the focused row disappears), focus the main button
	LaunchedEffect(viewModel.entries, group?.id) {
		withFrameNanos { }
		runCatching { mainButton.requestFocus() }
	}

	CompositionLocalProvider(LocalPopPage provides PopPage.SYNC_PLAY) {
		ProvideTextStyle(JellyfinTheme.typography.default.copy(color = JellyfinTheme.colorScheme.onBackground)) {
			Column(modifier = Modifier.fillMaxSize()) {
				MainToolbar(MainToolbarActiveButton.None)

				Column(
					verticalArrangement = Arrangement.spacedBy(12.dp),
					modifier = Modifier
						.fillMaxSize()
						.verticalScroll(rememberScrollState())
						.padding(start = 48.dp, end = 48.dp, top = 4.dp, bottom = 27.dp),
				) {
					Text(
						text = group?.name ?: stringResource(R.string.pop_syncplay_title),
						fontSize = 16.sp,
						fontWeight = FontWeight.W700,
					)

					Row(
						horizontalArrangement = Arrangement.spacedBy(8.dp),
						verticalAlignment = Alignment.CenterVertically,
						modifier = Modifier.focusGroup(),
					) {
						// One button for both states: it must not leave the composition while focused
						Button(
							onClick = { if (group != null) viewModel.leaveGroup() else viewModel.createGroup(defaultGroupName) },
							modifier = Modifier.focusRequester(mainButton),
						) {
							Text(stringResource(if (group != null) R.string.pop_syncplay_leave else R.string.pop_syncplay_create))
						}

						TogglePill(
							label = stringResource(R.string.pop_syncplay_player_sync),
							checked = state.sync.playerSync,
							onCheckedChange = viewModel::setPlayerSync,
						)
					}

					Caption(
						stringResource(
							if (state.sync.playerSync) R.string.pop_syncplay_player_sync_on else R.string.pop_syncplay_player_sync_off
						)
					)
					if (state.actionFailed) Caption(stringResource(R.string.pop_syncplay_action_failed))

					if (group != null) GroupDetails(group, state.sync.pingMs)
					else GroupList(state, onJoin = viewModel::joinGroup)
				}
			}
		}
	}
}

@Composable
private fun Caption(text: String) = Text(
	text = text,
	color = JellyfinTheme.colorScheme.listCaption,
	fontSize = 12.sp,
	modifier = Modifier.widthIn(max = 620.dp),
)

@Composable
private fun groupStateText(state: GroupStateType) = stringResource(
	when (state) {
		GroupStateType.IDLE -> R.string.pop_syncplay_state_idle
		GroupStateType.WAITING -> R.string.pop_syncplay_state_waiting
		GroupStateType.PAUSED -> R.string.pop_syncplay_state_paused
		GroupStateType.PLAYING -> R.string.pop_syncplay_state_playing
	}
)

@Composable
private fun GroupDetails(group: SyncPlayGroup, pingMs: Long?) {
	Text(
		text = groupStateText(group.state),
		fontSize = 14.sp,
	)
	Text(
		text = pluralStringResource(R.plurals.pop_syncplay_members, group.participants.size, group.participants.size),
		fontSize = 14.sp,
		fontWeight = FontWeight.W600,
	)
	group.participants.forEach { name ->
		Text(
			text = name,
			fontSize = 14.sp,
			modifier = Modifier.padding(start = 12.dp),
		)
	}
	if (pingMs != null) Caption(stringResource(R.string.pop_syncplay_ping, pingMs))
}

@Composable
private fun GroupList(state: SyncPlayPageState, onJoin: (UUID) -> Unit) {
	val groups = state.groups
	when {
		state.groupsFailed && groups.isNullOrEmpty() -> Caption(stringResource(R.string.pop_syncplay_groups_failed))
		groups == null -> Caption(stringResource(R.string.pop_syncplay_loading))
		groups.isEmpty() -> Caption(stringResource(R.string.pop_syncplay_no_groups))
		else -> {
			Text(
				text = stringResource(R.string.pop_syncplay_join),
				fontSize = 14.sp,
				fontWeight = FontWeight.W600,
			)
			groups.forEach { group ->
				// Never disabled: a button that turns disabled while focused drops the focus
				Button(onClick = { onJoin(group.id) }) {
					Text(
						stringResource(
							R.string.pop_syncplay_group_row,
							group.name,
							group.participants.joinToString(", "),
							groupStateText(group.state),
						)
					)
				}
			}
		}
	}
}
