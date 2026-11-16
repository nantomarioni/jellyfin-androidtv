package org.jellyfin.androidtv.pop.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.pop.ui.LocalPopPage
import org.jellyfin.androidtv.pop.ui.PickerPill
import org.jellyfin.androidtv.pop.ui.PillOption
import org.jellyfin.androidtv.pop.ui.PopColors
import org.jellyfin.androidtv.pop.ui.PopPage
import org.jellyfin.androidtv.pop.ui.PosterCard
import org.jellyfin.androidtv.ui.base.Badge
import org.jellyfin.androidtv.ui.base.CircularProgressIndicator
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.ProvideTextStyle
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.base.dialog.DialogBase
import org.jellyfin.androidtv.ui.shared.toolbar.MainToolbar
import org.jellyfin.androidtv.ui.shared.toolbar.MainToolbarActiveButton
import java.util.Locale

private const val COLUMNS = 7
private const val POSTER_ASPECT_RATIO = 2f / 3f
private const val TMDB_POSTER_BASE = "https://image.tmdb.org/t/p/w342"
private const val KEY_KIND = "control:kind"
private val CardSpacing = 16.dp
private val OverscanHorizontal = 48.dp
private val OverscanBottom = 27.dp
private val DialogShape = RoundedCornerShape(8.dp)

private fun PopDiscoverItem.posterUrl() = posterPath?.takeIf { it.isNotBlank() }?.let { TMDB_POSTER_BASE + it }

private val PopDiscoverKind.label
	get() = when (this) {
		PopDiscoverKind.RECOMMENDED -> R.string.pop_discover_kind_recommended
		PopDiscoverKind.TRENDING -> R.string.pop_discover_kind_trending
		PopDiscoverKind.MOVIES -> R.string.pop_discover_kind_movies
		PopDiscoverKind.TV -> R.string.pop_discover_kind_tv
	}

/**
 * The Discover page: titles Seerr suggests (through the pop-requests plugin) as a poster grid;
 * pressing one asks to request it. Same structure and focus rules as My library.
 */
@Composable
fun DiscoverScreen(
	viewModel: DiscoverViewModel,
) {
	val state by viewModel.state.collectAsState()

	// The card to focus when the page is entered: the one left behind, else the first one
	val cardKeys = remember(state.items) { state.items.map { it.key } }
	val focusKey = viewModel.focusedKey?.takeIf { it in cardKeys } ?: cardKeys.firstOrNull() ?: KEY_KIND
	val contentFocusRequester = remember { FocusRequester() }
	val loadingFocusRequester = remember { FocusRequester() }
	val ready by rememberUpdatedState(state.ready)
	var hasFocus by remember { mutableStateOf(false) }
	var loadingHasFocus by remember { mutableStateOf(false) }
	var selectedKey by remember { mutableStateOf<String?>(null) }

	// Something on the page must hold focus at all times (see AGENTS.md, focus rules)
	LaunchedEffect(viewModel.entries) {
		withFrameNanos { }
		runCatching { (if (ready) contentFocusRequester else loadingFocusRequester).requestFocus() }
	}
	// The content replaced the spinner: it takes over the focus, unless the user moved to the toolbar
	LaunchedEffect(state.ready) {
		if (!state.ready) return@LaunchedEffect
		withFrameNanos { }
		if (loadingHasFocus || !hasFocus) runCatching { contentFocusRequester.requestFocus() }
		loadingHasFocus = false
	}

	fun Modifier.focusTarget(key: String) = if (key == focusKey) focusRequester(contentFocusRequester) else this

	CompositionLocalProvider(LocalPopPage provides PopPage.DISCOVER) {
		ProvideTextStyle(JellyfinTheme.typography.default.copy(color = JellyfinTheme.colorScheme.onBackground)) {
			Column(
				modifier = Modifier
					.fillMaxSize()
					.onFocusChanged { hasFocus = it.hasFocus }
			) {
				MainToolbar(MainToolbarActiveButton.None)

				if (!state.ready) {
					Box(
						modifier = Modifier
							.fillMaxSize()
							.focusRequester(loadingFocusRequester)
							// Its removal (ready) also reports "not focused": only a change while loading counts
							.onFocusChanged { if (!ready) loadingHasFocus = it.isFocused }
							.focusable(),
						contentAlignment = Alignment.Center,
					) {
						CircularProgressIndicator(Modifier.size(48.dp))
					}
				} else LazyVerticalGrid(
					columns = GridCells.Fixed(COLUMNS),
					state = viewModel.gridState,
					contentPadding = PaddingValues(
						start = OverscanHorizontal,
						end = OverscanHorizontal,
						top = 4.dp,
						bottom = OverscanBottom,
					),
					horizontalArrangement = Arrangement.spacedBy(CardSpacing),
					verticalArrangement = Arrangement.spacedBy(CardSpacing),
					modifier = Modifier.fillMaxSize(),
				) {
					fullWidthItem("controls") {
						DiscoverControls(
							kind = state.kind,
							loading = state.loading,
							onKind = viewModel::setKind,
							kindModifier = Modifier.focusTarget(KEY_KIND),
						)
					}

					when {
						state.failure == DiscoverFailure.PLUGIN_OUTDATED -> fullWidthItem("outdated") {
							Message(stringResource(R.string.pop_discover_plugin_outdated))
						}

						state.failure != null -> fullWidthItem("failed") {
							Message(stringResource(R.string.pop_discover_failed))
						}

						state.items.isEmpty() && !state.loading -> fullWidthItem("empty") {
							Message(stringResource(R.string.pop_discover_empty))
						}
					}

					itemsIndexed(state.items, key = { _, item -> item.key }) { index, item ->
						DiscoverCard(
							item = item,
							onClick = {
								viewModel.resetRequest()
								selectedKey = item.key
							},
							onFocused = { viewModel.onFocused(index, item.key) },
							modifier = Modifier.focusTarget(item.key),
						)
					}
				}
			}
		}
	}

	// The dialog shows the item as it is in the list now, so its status follows a request
	val selected = selectedKey?.let { key -> state.items.firstOrNull { it.key == key } }
	var shown by remember { mutableStateOf<PopDiscoverItem?>(null) }
	if (selected != null) shown = selected

	DialogBase(
		visible = selected != null,
		onDismissRequest = { selectedKey = null },
	) {
		shown?.let { item ->
			RequestDialog(
				item = item,
				hasSeerrUser = state.hasSeerrUser,
				request = state.request,
				onRequest = { viewModel.request(item) },
				onDismiss = { selectedKey = null },
			)
		}
	}
}

private fun LazyGridScope.fullWidthItem(key: String, content: @Composable () -> Unit) = item(
	key = key,
	span = { GridItemSpan(maxLineSpan) },
) {
	content()
}

@Composable
private fun Message(text: String) = Text(
	text = text,
	color = JellyfinTheme.colorScheme.listCaption,
	fontSize = 14.sp,
	modifier = Modifier.padding(vertical = 12.dp),
)

@Composable
private fun DiscoverControls(
	kind: PopDiscoverKind,
	loading: Boolean,
	onKind: (PopDiscoverKind) -> Unit,
	kindModifier: Modifier,
) = Row(
	verticalAlignment = Alignment.CenterVertically,
	horizontalArrangement = Arrangement.spacedBy(12.dp),
	modifier = Modifier
		.fillMaxWidth()
		.focusGroup(),
) {
	PickerPill(
		icon = R.drawable.ic_lightbulb,
		label = stringResource(R.string.pop_pill_value, stringResource(R.string.pop_discover_show), stringResource(kind.label)),
		options = PopDiscoverKind.entries.map { PillOption(it, stringResource(it.label)) },
		selected = kind,
		onSelect = onKind,
		modifier = kindModifier,
	)

	if (loading) CircularProgressIndicator(Modifier.size(18.dp))
}

@Composable
private fun DiscoverCard(
	item: PopDiscoverItem,
	onClick: () -> Unit,
	onFocused: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val type = stringResource(if (item.isSeries) R.string.pop_discover_series else R.string.pop_discover_movie)

	PosterCard(
		title = item.title,
		subtitle = listOfNotNull(item.year?.toString(), type).joinToString(" · "),
		imageUrl = item.posterUrl(),
		isSeries = item.isSeries,
		onClick = onClick,
		onFocused = onFocused,
		modifier = modifier,
		badge = when (item.status) {
			PopDiscoverItem.STATUS_AVAILABLE -> {
				{ StatusBadge(R.drawable.ic_check, available = true) }
			}

			PopDiscoverItem.STATUS_REQUESTED -> {
				{ StatusBadge(R.drawable.ic_time, available = false) }
			}

			else -> null
		},
	)
}

@Composable
private fun StatusBadge(icon: Int, available: Boolean) = Badge(
	containerColor = if (available) PopColors.accent else JellyfinTheme.colorScheme.surface,
	contentColor = PopColors.onAccent,
) {
	Icon(
		imageVector = ImageVector.vectorResource(icon),
		contentDescription = null,
		tint = PopColors.onAccent,
		modifier = Modifier.size(12.dp),
	)
}

/**
 * Asks before requesting. The first button is the same node in every state (Request, Requesting…,
 * Close), so the dialog never loses its focused element when the request finishes.
 */
@Composable
private fun RequestDialog(
	item: PopDiscoverItem,
	hasSeerrUser: Boolean,
	request: DiscoverRequestState,
	onRequest: () -> Unit,
	onDismiss: () -> Unit,
) {
	val sending = request == DiscoverRequestState.Sending
	val canRequest = item.status == PopDiscoverItem.STATUS_NONE && hasSeerrUser && request == DiscoverRequestState.Idle

	val note = when {
		request == DiscoverRequestState.Done -> stringResource(R.string.pop_discover_done)
		request == DiscoverRequestState.NoSeerrUser || !hasSeerrUser -> stringResource(R.string.pop_discover_no_seerr_user)
		request is DiscoverRequestState.Refused -> request.message
			?.let { stringResource(R.string.pop_discover_refused_message, it) }
			?: stringResource(R.string.pop_discover_refused)

		item.status == PopDiscoverItem.STATUS_AVAILABLE -> stringResource(R.string.pop_discover_available)
		item.status == PopDiscoverItem.STATUS_REQUESTED -> stringResource(R.string.pop_discover_requested)
		item.isSeries -> stringResource(R.string.pop_discover_series_hint)
		else -> null
	}

	ProvideTextStyle(JellyfinTheme.typography.default.copy(color = JellyfinTheme.colorScheme.onBackground)) {
		Row(
			horizontalArrangement = Arrangement.spacedBy(20.dp),
			modifier = Modifier
				.width(560.dp)
				.clip(DialogShape)
				.background(JellyfinTheme.colorScheme.surface)
				.padding(20.dp),
		) {
			AsyncImage(
				model = item.posterUrl(),
				contentDescription = null,
				contentScale = ContentScale.Crop,
				modifier = Modifier
					.width(120.dp)
					.aspectRatio(POSTER_ASPECT_RATIO)
					.clip(RoundedCornerShape(6.dp))
					.background(JellyfinTheme.colorScheme.background),
			)

			Column(
				verticalArrangement = Arrangement.spacedBy(8.dp),
				modifier = Modifier.weight(1f),
			) {
				Text(
					text = item.title,
					fontSize = 20.sp,
					fontWeight = FontWeight.W700,
					maxLines = 2,
					overflow = TextOverflow.Ellipsis,
				)
				Text(
					text = listOfNotNull(
						item.year?.toString(),
						stringResource(if (item.isSeries) R.string.pop_discover_series else R.string.pop_discover_movie),
						item.voteAverage?.takeIf { it > 0 }?.let { String.format(Locale.getDefault(), "★ %.1f", it) },
					).joinToString(" · "),
					color = JellyfinTheme.colorScheme.listCaption,
					fontSize = 12.sp,
				)
				if (!item.overview.isNullOrBlank()) {
					Text(
						text = item.overview,
						fontSize = 13.sp,
						lineHeight = 18.sp,
						maxLines = 6,
						overflow = TextOverflow.Ellipsis,
					)
				}
				if (note != null) {
					Text(
						text = note,
						color = JellyfinTheme.colorScheme.listCaption,
						fontSize = 12.sp,
					)
				}

				Row(
					horizontalArrangement = Arrangement.spacedBy(8.dp),
					modifier = Modifier
						.padding(top = 8.dp)
						.focusGroup(),
				) {
					Button(
						onClick = {
							when {
								sending -> Unit
								canRequest -> onRequest()
								else -> onDismiss()
							}
						},
					) {
						Text(
							stringResource(
								when {
									sending -> R.string.pop_discover_sending
									canRequest -> R.string.pop_discover_request
									else -> R.string.pop_discover_close
								}
							)
						)
					}

					if (canRequest) {
						Button(onClick = onDismiss) {
							Text(stringResource(R.string.pop_discover_cancel))
						}
					}
				}
			}
		}
	}
}
