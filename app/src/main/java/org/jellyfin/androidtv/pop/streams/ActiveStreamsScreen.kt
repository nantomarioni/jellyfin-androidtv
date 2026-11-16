package org.jellyfin.androidtv.pop.streams

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.pop.ui.LocalPopPage
import org.jellyfin.androidtv.pop.ui.PopColors
import org.jellyfin.androidtv.pop.ui.PopPage
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
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.SessionInfoDto
import org.koin.compose.koinInject
import java.util.UUID

private const val COLUMNS = 2
private const val POSTER_ASPECT_RATIO = 2f / 3f
private const val POSTER_MAX_HEIGHT = 360
private const val POSTER_QUALITY = 90
private const val FOCUSED_SCALE = 1.03f
private const val TICKS_PER_SECOND = 10_000_000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3600L
private const val KEY_EMPTY = "empty"
private val CardSpacing = 16.dp
private val CardHeight = 132.dp
private val CardShape = RoundedCornerShape(8.dp)
private val OverscanHorizontal = 48.dp
private val OverscanBottom = 27.dp

/**
 * The Active streams page: one card per session that is playing something (who, what, where,
 * how far, and whether the server has to transcode). Refreshed by the view model while visible.
 */
@Composable
fun ActiveStreamsScreen(
	viewModel: ActiveStreamsViewModel,
	onOpenItem: (UUID) -> Unit,
) {
	val state by viewModel.state.collectAsState()
	val api = koinInject<ApiClient>()

	// The card to focus when the page is entered: the one left behind, else the first one,
	// else the "nobody is watching" message (something must always hold focus)
	val cardKeys = remember(state.streams) { state.streams.map { it.streamKey } }
	val focusKey = viewModel.focusedKey?.takeIf { it in cardKeys } ?: cardKeys.firstOrNull() ?: KEY_EMPTY
	val contentFocusRequester = remember { FocusRequester() }
	val loadingFocusRequester = remember { FocusRequester() }
	val ready by rememberUpdatedState(state.ready)
	var hasFocus by remember { mutableStateOf(false) }
	var loadingHasFocus by remember { mutableStateOf(false) }

	// Something on the page must hold focus at all times: upstream's activity crashes on a key
	// press while the previous screen's (removed) view is still the focused one.
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
	// A refresh removed the focused card (the stream ended): the next target takes the focus
	LaunchedEffect(cardKeys) {
		if (!state.ready) return@LaunchedEffect
		withFrameNanos { }
		if (!hasFocus) runCatching { contentFocusRequester.requestFocus() }
	}

	fun Modifier.focusTarget(key: String) = if (key == focusKey) focusRequester(contentFocusRequester) else this

	CompositionLocalProvider(LocalPopPage provides PopPage.ACTIVE_STREAMS) {
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
					item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
						Header(count = state.streams.size, ownOnly = state.ownOnly)
					}

					if (state.failed) item(key = "failed", span = { GridItemSpan(maxLineSpan) }) {
						Message(stringResource(R.string.pop_streams_failed))
					}

					if (state.streams.isEmpty()) item(key = KEY_EMPTY, span = { GridItemSpan(maxLineSpan) }) {
						// Focusable so the page holds focus while there is nothing to select
						Box(
							modifier = Modifier
								.fillMaxWidth()
								.focusTarget(KEY_EMPTY)
								.focusable()
						) {
							if (!state.failed) Message(stringResource(R.string.pop_streams_empty))
						}
					}

					items(state.streams, key = { it.streamKey }) { session ->
						val key = session.streamKey
						val item = session.nowPlayingItem
						StreamCard(
							session = session,
							imageUrl = item?.let { api.posterUrl(it) },
							onClick = { item?.id?.let(onOpenItem) },
							onFocused = { viewModel.focusedKey = key },
							modifier = Modifier.focusTarget(key),
						)
					}
				}
			}
		}
	}
}

@Composable
private fun Header(count: Int, ownOnly: Boolean) = Row(
	verticalAlignment = Alignment.CenterVertically,
	horizontalArrangement = Arrangement.spacedBy(12.dp),
	modifier = Modifier
		.fillMaxWidth()
		.padding(vertical = 8.dp),
) {
	Text(
		text = stringResource(R.string.pop_streams_title),
		fontSize = 16.sp,
		fontWeight = FontWeight.W700,
	)

	if (ownOnly) {
		Text(
			text = stringResource(R.string.pop_streams_own_only),
			color = JellyfinTheme.colorScheme.listCaption,
			fontSize = 12.sp,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f),
		)
	} else {
		Spacer(Modifier.weight(1f))
	}

	Text(
		text = pluralStringResource(R.plurals.pop_streams_count, count, count),
		color = JellyfinTheme.colorScheme.listCaption,
		fontSize = 12.sp,
	)
}

@Composable
private fun Message(text: String) = Text(
	text = text,
	color = JellyfinTheme.colorScheme.listCaption,
	fontSize = 14.sp,
	modifier = Modifier.padding(vertical = 12.dp),
)

/**
 * One stream: poster, what is playing, who and where, the play state and method, and the progress.
 */
@Composable
private fun StreamCard(
	session: SessionInfoDto,
	imageUrl: String?,
	onClick: () -> Unit,
	onFocused: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val item = session.nowPlayingItem
	val isEpisode = item?.type == BaseItemKind.EPISODE

	// Not from an interaction source: that one keeps saying "focused" when the page is left with the card focused
	var focused by remember { mutableStateOf(false) }
	val scale by animateFloatAsState(if (focused) FOCUSED_SCALE else 1f)

	Row(
		modifier = modifier
			.onFocusChanged {
				focused = it.isFocused
				if (it.isFocused) onFocused()
			}
			.graphicsLayer {
				scaleX = scale
				scaleY = scale
			}
			.fillMaxWidth()
			.height(CardHeight)
			.clip(CardShape)
			.background(JellyfinTheme.colorScheme.surface)
			.then(if (focused) Modifier.border(2.dp, Color.White, CardShape) else Modifier)
			.clickable(
				interactionSource = null,
				indication = null,
				onClick = onClick,
			)
	) {
		Box(
			modifier = Modifier
				.fillMaxHeight()
				.aspectRatio(POSTER_ASPECT_RATIO)
				.background(Color.Black.copy(alpha = 0.3f))
		) {
			if (imageUrl != null) {
				AsyncImage(
					model = imageUrl,
					contentDescription = null,
					contentScale = ContentScale.Crop,
					modifier = Modifier.fillMaxSize(),
				)
			} else {
				Icon(
					imageVector = ImageVector.vectorResource(if (isEpisode) R.drawable.ic_tv else R.drawable.ic_movie),
					contentDescription = null,
					tint = JellyfinTheme.colorScheme.listCaption,
					modifier = Modifier
						.align(Alignment.Center)
						.size(28.dp),
				)
			}
		}

		Column(
			modifier = Modifier
				.weight(1f)
				.fillMaxHeight()
				.padding(horizontal = 12.dp, vertical = 10.dp),
		) {
			Text(
				text = (if (isEpisode) item?.seriesName else item?.name).orEmpty(),
				fontSize = 14.sp,
				fontWeight = FontWeight.W700,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			Line(itemSubtitle(item))
			Line(
				text = listOfNotNull(session.userName, session.deviceName, session.client)
					.filter { it.isNotBlank() }
					.joinToString(" · "),
				color = JellyfinTheme.colorScheme.onBackground,
			)

			Spacer(Modifier.weight(1f))

			Line(playSummary(session))
			StreamProgress(
				positionTicks = session.playState?.positionTicks,
				runTimeTicks = item?.runTimeTicks,
			)
		}
	}
}

@Composable
private fun Line(
	text: String,
	color: Color = JellyfinTheme.colorScheme.listCaption,
) = Text(
	text = text,
	color = color,
	fontSize = 12.sp,
	maxLines = 1,
	overflow = TextOverflow.Ellipsis,
)

@Composable
private fun itemSubtitle(item: BaseItemDto?): String {
	if (item == null) return ""
	if (item.type != BaseItemKind.EPISODE) return item.productionYear?.toString().orEmpty()

	val season = item.parentIndexNumber
	val episode = item.indexNumber
	return if (season != null && episode != null) {
		stringResource(R.string.pop_streams_episode, season, episode) + " · " + item.name.orEmpty()
	} else {
		item.name.orEmpty()
	}
}

/** "Playing · Direct play", or "Paused · Transcoding (H264 / AAC): Video codec not supported". */
@Composable
private fun playSummary(session: SessionInfoDto): String {
	val playState = session.playState
	val transcoding = session.transcodingInfo

	val state = stringResource(if (playState?.isPaused == true) R.string.pop_streams_paused else R.string.pop_streams_playing)
	val method = when {
		playState?.playMethod == PlayMethod.TRANSCODE -> R.string.pop_streams_transcode
		playState?.playMethod == PlayMethod.DIRECT_STREAM -> R.string.pop_streams_direct_stream
		playState?.playMethod == PlayMethod.DIRECT_PLAY -> R.string.pop_streams_direct_play
		// Some clients don't report a method: the server transcoding says enough
		transcoding != null -> R.string.pop_streams_transcode
		else -> null
	}

	return buildString {
		append(state)
		if (method != null) append(" · ").append(stringResource(method))

		if (method == R.string.pop_streams_transcode && transcoding != null) {
			val codecs = listOfNotNull(transcoding.videoCodec, transcoding.audioCodec)
				.filter { it.isNotBlank() }
				.joinToString(" / ") { it.uppercase() }
			if (codecs.isNotEmpty()) append(" (").append(codecs).append(")")

			// The server's reason names are CamelCase ("VideoCodecNotSupported"): spell them out
			val reasons = transcoding.transcodeReasons.orEmpty()
				.joinToString(", ") { reason -> reason.serialName.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ").lowercase() }
			if (reasons.isNotEmpty()) append(": ").append(reasons)
		}
	}
}

@Composable
private fun StreamProgress(positionTicks: Long?, runTimeTicks: Long?) {
	val position = positionTicks ?: 0L
	val fraction = if (runTimeTicks != null && runTimeTicks > 0) (position.toFloat() / runTimeTicks).coerceIn(0f, 1f) else 0f

	Row(
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		modifier = Modifier.padding(top = 4.dp),
	) {
		Box(
			modifier = Modifier
				.weight(1f)
				.height(4.dp)
				.clip(RoundedCornerShape(2.dp))
				.background(Color.Black.copy(alpha = 0.6f))
		) {
			Box(
				modifier = Modifier
					.fillMaxHeight()
					.fillMaxWidth(fraction)
					.background(PopColors.accent)
			)
		}

		Text(
			text = if (runTimeTicks != null && runTimeTicks > 0) {
				stringResource(R.string.pop_streams_position, formatTicks(position), formatTicks(runTimeTicks))
			} else {
				formatTicks(position)
			},
			color = JellyfinTheme.colorScheme.listCaption,
			fontSize = 11.sp,
		)
	}
}

/** Jellyfin ticks (100 ns) as `m:ss`, or `h:mm:ss` from an hour on. */
private fun formatTicks(ticks: Long): String {
	val totalSeconds = ticks / TICKS_PER_SECOND
	val hours = totalSeconds / SECONDS_PER_HOUR
	val minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
	val seconds = totalSeconds % SECONDS_PER_MINUTE

	return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
	else "%d:%02d".format(minutes, seconds)
}

/** The series poster for an episode, else the item's own poster. */
private fun ApiClient.posterUrl(item: BaseItemDto): String? {
	val seriesId = item.seriesId
	val seriesTag = item.seriesPrimaryImageTag
	val (id, tag) = when {
		item.type == BaseItemKind.EPISODE && seriesId != null && seriesTag != null -> seriesId to seriesTag
		else -> item.id to item.imageTags?.get(ImageType.PRIMARY)
	}

	return tag?.let {
		imageApi.getItemImageUrl(
			itemId = id,
			imageType = ImageType.PRIMARY,
			tag = it,
			maxHeight = POSTER_MAX_HEIGHT,
			quality = POSTER_QUALITY,
		)
	}
}
