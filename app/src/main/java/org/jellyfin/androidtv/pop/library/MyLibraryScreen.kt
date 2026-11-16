package org.jellyfin.androidtv.pop.library

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.pop.data.PopLibrary
import org.jellyfin.androidtv.pop.data.PopLibraryFilter
import org.jellyfin.androidtv.pop.data.PopLibraryItem
import org.jellyfin.androidtv.pop.data.PopLibrarySort
import org.jellyfin.androidtv.pop.ui.LocalPopPage
import org.jellyfin.androidtv.pop.ui.PickerPill
import org.jellyfin.androidtv.pop.ui.PillOption
import org.jellyfin.androidtv.pop.ui.PopColors
import org.jellyfin.androidtv.pop.ui.PopPage
import org.jellyfin.androidtv.pop.ui.PosterCard
import org.jellyfin.androidtv.pop.ui.TogglePill
import org.jellyfin.androidtv.ui.base.Badge
import org.jellyfin.androidtv.ui.base.CircularProgressIndicator
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.ProvideTextStyle
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.shared.toolbar.MainToolbar
import org.jellyfin.androidtv.ui.shared.toolbar.MainToolbarActiveButton
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.koin.compose.koinInject
import java.util.UUID

private const val COLUMNS = 7
private const val POSTER_MAX_HEIGHT = 420
private const val POSTER_QUALITY = 90
private const val PERCENT = 100f
private const val KEY_REQUESTER = "control:requester"
private val CardSpacing = 16.dp
private val OverscanHorizontal = 48.dp
private val OverscanBottom = 27.dp

private fun continueWatchingKey(id: UUID) = "continue:$id"
private fun libraryKey(id: UUID) = "library:$id"

/**
 * The My library page: Continue watching on top, then the requested titles the viewer can watch
 * with the requester, sort and hide-watched pills (the web tab of jellyfin-plugin-pop-requests).
 */
@Composable
fun MyLibraryScreen(
	viewModel: MyLibraryViewModel,
	onOpenItem: (UUID) -> Unit,
) {
	val state by viewModel.state.collectAsState()
	val api = koinInject<ApiClient>()

	val libraryItems = remember(state.library) {
		state.library?.items.orEmpty().mapNotNull { item -> item.id.toUUIDOrNull()?.let { id -> id to item } }
	}

	// The card to focus when the page is entered: the one left behind, else the first one
	val cardKeys = remember(state.continueWatching, libraryItems) {
		state.continueWatching.map { continueWatchingKey(it.id) } + libraryItems.map { libraryKey(it.first) }
	}
	val focusKey = viewModel.focusedKey?.takeIf { it in cardKeys } ?: cardKeys.firstOrNull() ?: KEY_REQUESTER
	val contentFocusRequester = remember { FocusRequester() }
	val loadingFocusRequester = remember { FocusRequester() }
	val ready by rememberUpdatedState(state.ready)
	var hasFocus by remember { mutableStateOf(false) }
	var loadingHasFocus by remember { mutableStateOf(false) }

	// Something on the page must hold focus at all times: upstream's activity crashes on a key
	// press while the previous screen's (removed) view is still the focused one.
	// Entering the page (first time, or back from item details or playback) focuses the content.
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

	CompositionLocalProvider(LocalPopPage provides PopPage.MY_LIBRARY) {
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
				} else BoxWithConstraints(
					modifier = Modifier.fillMaxSize()
				) {
					val screenWidth = maxWidth
					val cardWidth = (maxWidth - OverscanHorizontal * 2 - CardSpacing * (COLUMNS - 1)) / COLUMNS

					LazyVerticalGrid(
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
						if (state.continueWatching.isNotEmpty()) fullWidthItem("continue") {
							ContinueWatchingRow(
								items = state.continueWatching,
								viewModel = viewModel,
								api = api,
								screenWidth = screenWidth,
								cardWidth = cardWidth,
								focusTarget = { key -> Modifier.focusTarget(key) },
								onOpenItem = onOpenItem,
							)
						} else if (state.continueWatchingFailed) fullWidthItem("continue-failed") {
							Message(stringResource(R.string.pop_library_continue_failed))
						}

						fullWidthItem("controls") {
							LibraryControls(
								filter = state.filter,
								library = state.library,
								onRequester = viewModel::setRequester,
								onSort = viewModel::setSort,
								onHideWatched = viewModel::setHideWatched,
								requesterModifier = Modifier.focusTarget(KEY_REQUESTER),
							)
						}

						val library = state.library
						if (state.libraryFailed) fullWidthItem("library-failed") {
							Message(stringResource(R.string.pop_library_load_failed))
						} else if (library != null && libraryItems.isEmpty()) fullWidthItem("library-empty") {
							Message(emptyMessage(state.filter, library))
						}

						items(libraryItems, key = { (id) -> libraryKey(id) }) { (id, item) ->
							val key = libraryKey(id)
							LibraryCard(
								item = item,
								imageUrl = api.posterUrl(id, item.posterTag),
								showRequesters = state.filter.requester != PopLibrary.REQUESTER_MINE,
								onClick = { onOpenItem(id) },
								onFocused = { viewModel.focusedKey = key },
								modifier = Modifier.focusTarget(key),
							)
						}
					}
				}
			}
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
private fun SectionTitle(text: String, modifier: Modifier = Modifier) = Text(
	text = text,
	fontSize = 16.sp,
	fontWeight = FontWeight.W700,
	modifier = modifier,
)

@Composable
private fun Message(text: String) = Text(
	text = text,
	color = JellyfinTheme.colorScheme.listCaption,
	fontSize = 14.sp,
	modifier = Modifier.padding(vertical = 12.dp),
)

@Composable
private fun emptyMessage(filter: PopLibraryFilter, library: PopLibrary): String = when {
	filter.hideWatched -> stringResource(R.string.pop_library_empty_watched)
	library.requester == PopLibrary.REQUESTER_MINE -> stringResource(R.string.pop_library_empty_mine)
	library.requester == PopLibrary.REQUESTER_ALL -> stringResource(R.string.pop_library_empty_all)
	else -> library.people.firstOrNull { it.jellyfinUserId == library.requester }
		?.let { stringResource(R.string.pop_library_empty_person, it.user) }
		?: stringResource(R.string.pop_library_empty_all)
}

/**
 * The row runs from screen edge to screen edge (the grid around it is padded for overscan), so
 * cards scroll under the edges instead of being cut at the padding.
 */
@Composable
private fun ContinueWatchingRow(
	items: List<BaseItemDto>,
	viewModel: MyLibraryViewModel,
	api: ApiClient,
	screenWidth: Dp,
	cardWidth: Dp,
	focusTarget: (String) -> Modifier,
	onOpenItem: (UUID) -> Unit,
) = Column {
	SectionTitle(stringResource(R.string.pop_library_continue))

	LazyRow(
		state = viewModel.continueWatchingState,
		contentPadding = PaddingValues(horizontal = OverscanHorizontal, vertical = 8.dp),
		horizontalArrangement = Arrangement.spacedBy(CardSpacing),
		modifier = Modifier
			.requiredWidth(screenWidth)
			.focusGroup(),
	) {
		items(items, key = { it.id }) { item ->
			val key = continueWatchingKey(item.id)
			val isEpisode = item.type == BaseItemKind.EPISODE
			val season = item.parentIndexNumber
			val episode = item.indexNumber
			val seriesId = item.seriesId

			val imageUrl = when {
				isEpisode && seriesId != null && item.seriesPrimaryImageTag != null ->
					api.posterUrl(seriesId, item.seriesPrimaryImageTag)

				else -> api.posterUrl(item.id, item.imageTags?.get(ImageType.PRIMARY))
			}
			val subtitle = when {
				!isEpisode -> item.productionYear?.toString().orEmpty()
				season != null && episode != null ->
					stringResource(R.string.pop_episode_short, season, episode) + " · " + item.name.orEmpty()

				else -> item.name.orEmpty()
			}

			PosterCard(
				title = (if (isEpisode) item.seriesName else item.name).orEmpty(),
				subtitle = subtitle,
				imageUrl = imageUrl,
				isSeries = isEpisode,
				progress = item.userData?.playedPercentage?.let { it.toFloat() / PERCENT },
				onClick = { onOpenItem(item.id) },
				onFocused = { viewModel.focusedKey = key },
				modifier = Modifier
					.width(cardWidth)
					.then(focusTarget(key)),
			)
		}
	}
}

@Composable
private fun LibraryControls(
	filter: PopLibraryFilter,
	library: PopLibrary?,
	onRequester: (String) -> Unit,
	onSort: (PopLibrarySort) -> Unit,
	onHideWatched: (Boolean) -> Unit,
	requesterModifier: Modifier,
) = Row(
	verticalAlignment = Alignment.CenterVertically,
	horizontalArrangement = Arrangement.spacedBy(8.dp),
	modifier = Modifier
		.fillMaxWidth()
		.focusGroup(),
) {
	val me = stringResource(R.string.pop_library_me)
	val everyone = stringResource(R.string.pop_library_everyone)
	val requesterOptions = buildList {
		if (library?.hasSeerrUser != false) add(PillOption(PopLibrary.REQUESTER_MINE, me))
		add(PillOption(PopLibrary.REQUESTER_ALL, everyone))
		library?.people.orEmpty().filterNot { it.mine }.forEach { person ->
			add(PillOption(person.jellyfinUserId, stringResource(R.string.pop_library_person, person.user, person.count)))
		}
	}
	val requesterName = when (filter.requester) {
		PopLibrary.REQUESTER_MINE -> me
		PopLibrary.REQUESTER_ALL -> everyone
		else -> library?.people?.firstOrNull { it.jellyfinUserId == filter.requester }?.user ?: everyone
	}

	val sortOptions = PopLibrarySort.entries.map { PillOption(it, stringResource(it.label)) }

	SectionTitle(
		text = stringResource(R.string.pop_library_titles),
		modifier = Modifier.padding(end = 8.dp),
	)

	PickerPill(
		icon = R.drawable.ic_user,
		label = stringResource(R.string.pop_pill_value, stringResource(R.string.pop_library_requester), requesterName),
		options = requesterOptions,
		selected = filter.requester,
		onSelect = onRequester,
		modifier = requesterModifier,
	)
	PickerPill(
		icon = R.drawable.ic_sort,
		label = stringResource(R.string.pop_pill_value, stringResource(R.string.pop_library_sort), stringResource(filter.sort.label)),
		options = sortOptions,
		selected = filter.sort,
		onSelect = onSort,
	)
	TogglePill(
		label = stringResource(R.string.pop_library_hide_watched),
		checked = filter.hideWatched,
		onCheckedChange = onHideWatched,
	)

	Spacer(Modifier.weight(1f))

	if (library != null) {
		Text(
			text = pluralStringResource(R.plurals.pop_library_count, library.items.size, library.items.size),
			color = JellyfinTheme.colorScheme.listCaption,
			fontSize = 12.sp,
		)
	}
}

private val PopLibrarySort.label
	get() = when (this) {
		PopLibrarySort.ADDED -> R.string.pop_library_sort_added
		PopLibrarySort.TITLE -> R.string.pop_library_sort_title
		PopLibrarySort.RELEASED -> R.string.pop_library_sort_released
		PopLibrarySort.RATING -> R.string.pop_library_sort_rating
		PopLibrarySort.REQUESTED -> R.string.pop_library_sort_requested
	}

@Composable
private fun LibraryCard(
	item: PopLibraryItem,
	imageUrl: String?,
	showRequesters: Boolean,
	onClick: () -> Unit,
	onFocused: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val requesters = if (showRequesters) item.requests.map { it.user }.distinct().joinToString(", ") else ""
	val subtitle = listOfNotNull(item.year?.toString(), requesters.ifEmpty { null }).joinToString(" · ")
	val unplayed = item.unplayedCount ?: 0

	PosterCard(
		title = item.title,
		subtitle = subtitle,
		imageUrl = imageUrl,
		isSeries = item.isSeries,
		progress = item.playedPercentage?.takeUnless { item.played }?.let { it.toFloat() / PERCENT },
		onClick = onClick,
		onFocused = onFocused,
		modifier = modifier,
		badge = when {
			item.played -> {
				{
					Badge(containerColor = PopColors.accent, contentColor = PopColors.onAccent) {
						Icon(
							imageVector = ImageVector.vectorResource(R.drawable.ic_check),
							contentDescription = null,
							tint = PopColors.onAccent,
							modifier = Modifier.size(12.dp),
						)
					}
				}
			}

			item.isSeries && unplayed > 0 -> {
				{
					Badge(containerColor = PopColors.accent, contentColor = PopColors.onAccent) {
						Text(unplayed.toString())
					}
				}
			}

			else -> null
		},
	)
}

private fun ApiClient.posterUrl(itemId: UUID, tag: String?): String? = tag?.let {
	imageApi.getItemImageUrl(
		itemId = itemId,
		imageType = ImageType.PRIMARY,
		tag = it,
		maxHeight = POSTER_MAX_HEIGHT,
		quality = POSTER_QUALITY,
	)
}
