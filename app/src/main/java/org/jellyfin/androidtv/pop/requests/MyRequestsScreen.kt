package org.jellyfin.androidtv.pop.requests

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.pop.ui.LocalPopPage
import org.jellyfin.androidtv.pop.ui.PickerPill
import org.jellyfin.androidtv.pop.ui.PillOption
import org.jellyfin.androidtv.pop.ui.PopColors
import org.jellyfin.androidtv.pop.ui.PopPage
import org.jellyfin.androidtv.ui.base.CircularProgressIndicator
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.ProvideTextStyle
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.shared.toolbar.MainToolbar
import org.jellyfin.androidtv.ui.shared.toolbar.MainToolbarActiveButton
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

private const val KEY_SCOPE = "control:scope"
private val RefreshInterval = 30.seconds
private val OverscanHorizontal = 48.dp
private val OverscanBottom = 27.dp
private val CardShape = RoundedCornerShape(8.dp)
private val PosterShape = RoundedCornerShape(4.dp)

/**
 * The My requests page: what was requested and is still on its way, as the web tab's sections
 * (downloading, stuck, coming soon, upgrading, unavailable). One focusable card per title with
 * its downloads and the reason it waits. Read-only.
 */
@Composable
fun MyRequestsScreen(
	viewModel: MyRequestsViewModel,
	onOpenItem: (UUID) -> Unit,
) {
	val state by viewModel.state.collectAsState()
	val resources = LocalContext.current.resources
	val text = remember(resources) { BoardText(resources) }

	val board = state.board
	// Without a Seerr account "mine" means nothing: show everyone (the saved choice stays)
	val everyone = state.everyone || board?.hasSeerrUser == false
	val sections = remember(board, everyone) {
		val items = board?.items.orEmpty().filter { item -> everyone || item.requests.any { it.mine } }
		boardSections(items).filterValues { it.isNotEmpty() }
	}

	// The card to focus when the page is entered: the one left behind, else the first one
	val entryKeys = remember(sections) { sections.values.flatten().map { it.key } }
	val focusKey = viewModel.focusedKey?.takeIf { it in entryKeys } ?: entryKeys.firstOrNull() ?: KEY_SCOPE
	val contentFocusRequester = remember { FocusRequester() }
	val loadingFocusRequester = remember { FocusRequester() }
	val ready by rememberUpdatedState(state.ready)
	var hasFocus by remember { mutableStateOf(false) }
	var loadingHasFocus by remember { mutableStateOf(false) }

	// Something on the page must hold focus at all times (see AGENTS.md, focus rules).
	// Entering the page (first time, or back from item details) focuses the content.
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
	// Downloads move: ask again while the page is shown
	LaunchedEffect(Unit) {
		while (true) {
			delay(RefreshInterval)
			viewModel.refresh()
		}
	}

	fun Modifier.focusTarget(key: String) = if (key == focusKey) focusRequester(contentFocusRequester) else this

	CompositionLocalProvider(LocalPopPage provides PopPage.MY_REQUESTS) {
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
				} else LazyColumn(
					state = viewModel.listState,
					contentPadding = PaddingValues(
						start = OverscanHorizontal,
						end = OverscanHorizontal,
						top = 4.dp,
						bottom = OverscanBottom,
					),
					verticalArrangement = Arrangement.spacedBy(10.dp),
					modifier = Modifier.fillMaxSize(),
				) {
					item(key = "controls") {
						RequestsControls(
							everyone = everyone,
							canMine = board?.hasSeerrUser != false,
							onEveryone = viewModel::setEveryone,
							scopeModifier = Modifier.focusTarget(KEY_SCOPE),
						)
					}

					if (state.failed) item(key = "failed") {
						Message(stringResource(R.string.pop_requests_load_failed))
					}
					if (board != null && board.errors.isNotEmpty()) item(key = "unreachable") {
						Message(text.unreachable(board.errors))
					}
					if (board != null && sections.isEmpty()) item(key = "empty") {
						Message(stringResource(if (everyone) R.string.pop_requests_empty_all else R.string.pop_requests_empty_mine))
					}

					sections.forEach { (section, entries) ->
						item(key = "section:${section.name}") {
							SectionHeader(
								title = text.sectionTitle(section),
								hint = text.sectionHint(section),
								count = entries.size,
							)
						}

						items(entries, key = { it.key }) { entry ->
							val itemId = entry.item.jellyfinId?.toUUIDOrNull()
							EntryCard(
								entry = entry,
								text = text,
								onClick = { if (itemId != null) onOpenItem(itemId) },
								onFocused = { viewModel.focusedKey = entry.key },
								modifier = Modifier.focusTarget(entry.key),
							)
						}
					}
				}
			}
		}
	}
}

@Composable
private fun RequestsControls(
	everyone: Boolean,
	canMine: Boolean,
	onEveryone: (Boolean) -> Unit,
	scopeModifier: Modifier,
) = Row(
	verticalAlignment = Alignment.CenterVertically,
	horizontalArrangement = Arrangement.spacedBy(8.dp),
	modifier = Modifier
		.fillMaxWidth()
		.focusGroup(),
) {
	val mine = stringResource(R.string.pop_requests_mine)
	val all = stringResource(R.string.pop_requests_everyone)

	Text(
		text = stringResource(R.string.pop_requests_title),
		fontSize = 16.sp,
		fontWeight = FontWeight.W700,
		modifier = Modifier.padding(end = 8.dp),
	)

	PickerPill(
		icon = R.drawable.ic_user,
		label = stringResource(
			R.string.pop_pill_value,
			stringResource(R.string.pop_requests_whose),
			if (everyone) all else mine,
		),
		options = buildList {
			if (canMine) add(PillOption(false, mine))
			add(PillOption(true, all))
		},
		selected = everyone,
		onSelect = onEveryone,
		modifier = scopeModifier,
	)
}

@Composable
private fun SectionHeader(title: String, hint: String, count: Int) = Row(
	verticalAlignment = Alignment.Bottom,
	horizontalArrangement = Arrangement.spacedBy(8.dp),
	modifier = Modifier.padding(top = 8.dp),
) {
	Text(
		text = "$title ($count)",
		fontSize = 15.sp,
		fontWeight = FontWeight.W700,
	)
	Text(
		text = hint,
		color = JellyfinTheme.colorScheme.listCaption,
		fontSize = 12.sp,
	)
}

@Composable
private fun Message(text: String) = Text(
	text = text,
	color = JellyfinTheme.colorScheme.listCaption,
	fontSize = 14.sp,
	modifier = Modifier.padding(vertical = 8.dp),
)

@Composable
private fun EntryCard(
	entry: BoardEntry,
	text: BoardText,
	onClick: () -> Unit,
	onFocused: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val item = entry.item
	// Not from an interaction source: that one keeps saying "focused" when the page is left with the card focused
	var focused by remember { mutableStateOf(false) }

	Row(
		horizontalArrangement = Arrangement.spacedBy(12.dp),
		modifier = modifier
			.fillMaxWidth()
			.onFocusChanged {
				focused = it.isFocused
				if (it.isFocused) onFocused()
			}
			.clip(CardShape)
			.background(if (focused) Color.White.copy(alpha = 0.12f) else JellyfinTheme.colorScheme.surface.copy(alpha = 0.6f))
			.then(if (focused) Modifier.border(2.dp, Color.White, CardShape) else Modifier)
			.clickable(
				interactionSource = null,
				indication = null,
				onClick = onClick,
			)
			.padding(10.dp),
	) {
		Poster(item)

		Column(
			verticalArrangement = Arrangement.spacedBy(4.dp),
			modifier = Modifier.weight(1f),
		) {
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(8.dp),
			) {
				Text(
					text = item.title + (item.year?.let { " ($it)" } ?: ""),
					fontSize = 14.sp,
					fontWeight = FontWeight.W600,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.weight(1f, fill = false),
				)
				if (entry.downloads.isEmpty()) item.waiting?.let { Chip(text.waitingChip(it)) }
			}

			Text(
				text = text.requestLine(item),
				color = JellyfinTheme.colorScheme.listCaption,
				fontSize = 12.sp,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)

			if (entry.downloads.isEmpty()) {
				item.waiting?.let { waiting ->
					Text(
						text = text.waitingText(item, waiting),
						fontSize = 12.sp,
					)
				}
			} else {
				entry.downloads.forEach { download -> DownloadRow(download, text) }
			}
		}
	}
}

@Composable
private fun Poster(item: PopBoardItem) = Box(
	contentAlignment = Alignment.Center,
	modifier = Modifier
		.width(48.dp)
		.height(72.dp)
		.clip(PosterShape)
		.background(Color.White.copy(alpha = 0.06f)),
) {
	if (item.poster != null) {
		AsyncImage(
			model = item.poster,
			contentDescription = null,
			contentScale = ContentScale.Crop,
			modifier = Modifier.fillMaxSize(),
		)
	} else {
		Icon(
			imageVector = ImageVector.vectorResource(if (item.isSeries) R.drawable.ic_tv else R.drawable.ic_movie),
			contentDescription = null,
			tint = JellyfinTheme.colorScheme.listCaption,
			modifier = Modifier.size(20.dp),
		)
	}
}

/** One torrent: state chip, what it is, a progress bar with speed/time left, and download-race's note. */
@Composable
private fun DownloadRow(download: PopBoardDownload, text: BoardText) = Column(
	verticalArrangement = Arrangement.spacedBy(3.dp),
	modifier = Modifier.padding(top = 2.dp),
) {
	val progress = download.progress.toFloat().coerceIn(0f, 1f)
	val progressText = text.downloadProgress(download)

	Row(
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Chip(text.downloadState(download))
		Text(
			text = text.downloadMeta(download).ifEmpty { download.release },
			fontSize = 12.sp,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f),
		)
		Text(
			text = listOf("${(progress * PERCENT).toInt()}%", progressText).filter { it.isNotEmpty() }.joinToString(" · "),
			color = JellyfinTheme.colorScheme.listCaption,
			fontSize = 12.sp,
			maxLines = 1,
		)
	}

	Box(
		modifier = Modifier
			.fillMaxWidth()
			.height(4.dp)
			.clip(CircleShape)
			.background(Color.White.copy(alpha = 0.12f))
	) {
		Box(
			modifier = Modifier
				.fillMaxHeight()
				.fillMaxWidth(progress)
				.background(PopColors.accent)
		)
	}

	text.raceNote(download.race)?.let { (note, tone) ->
		Text(
			text = note,
			color = tone.color(),
			fontSize = 11.sp,
		)
	}
}

@Composable
private fun Chip(chip: BoardChip) = Text(
	text = chip.label,
	color = chip.tone.color(),
	fontSize = 11.sp,
	fontWeight = FontWeight.W700,
	maxLines = 1,
	modifier = Modifier
		.clip(CircleShape)
		.background(chip.tone.color().copy(alpha = 0.16f))
		.padding(horizontal = 8.dp, vertical = 2.dp),
)

private const val PERCENT = 100

@Composable
private fun BoardTone.color(): Color = when (this) {
	BoardTone.GOOD -> Color(0xFF66BB6A)
	BoardTone.INFO -> PopColors.accent
	BoardTone.WARN -> Color(0xFFFFB74D)
	BoardTone.BAD -> Color(0xFFE57373)
	BoardTone.MUTED -> JellyfinTheme.colorScheme.listCaption
}
