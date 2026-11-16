package org.jellyfin.androidtv.pop.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text

private const val POSTER_ASPECT_RATIO = 2f / 3f
private const val FOCUSED_SCALE = 1.06f
private val PosterShape = RoundedCornerShape(6.dp)

/**
 * A poster with a title and one line under it, as on the web client's library pages. The poster
 * grows and gets a white ring while focused.
 *
 * @param progress Played part between 0 and 1, drawn as a bar along the bottom of the poster.
 * @param badge Drawn in the poster's top end corner (unwatched count, watched mark).
 */
@Composable
fun PosterCard(
	title: String,
	subtitle: String,
	imageUrl: String?,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	isSeries: Boolean = false,
	progress: Float? = null,
	onFocused: () -> Unit = {},
	badge: (@Composable BoxScope.() -> Unit)? = null,
) {
	// Not from an interaction source: that one keeps saying "focused" when the page is left with the card focused
	var focused by remember { mutableStateOf(false) }
	val scale by animateFloatAsState(if (focused) FOCUSED_SCALE else 1f)

	Column(
		modifier = modifier
			.onFocusChanged {
				focused = it.isFocused
				if (it.isFocused) onFocused()
			}
			.graphicsLayer {
				scaleX = scale
				scaleY = scale
			}
			.clickable(
				interactionSource = null,
				indication = null,
				onClick = onClick,
			)
	) {
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.aspectRatio(POSTER_ASPECT_RATIO)
				.clip(PosterShape)
				.background(JellyfinTheme.colorScheme.surface)
				.then(if (focused) Modifier.border(2.dp, Color.White, PosterShape) else Modifier)
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
					imageVector = ImageVector.vectorResource(if (isSeries) R.drawable.ic_tv else R.drawable.ic_movie),
					contentDescription = null,
					tint = JellyfinTheme.colorScheme.listCaption,
					modifier = Modifier
						.align(Alignment.Center)
						.size(32.dp),
				)
			}

			if (badge != null) {
				Box(
					modifier = Modifier
						.align(Alignment.TopEnd)
						.padding(5.dp),
					content = badge,
				)
			}

			if (progress != null && progress > 0f) {
				Box(
					modifier = Modifier
						.align(Alignment.BottomStart)
						.fillMaxWidth()
						.height(4.dp)
						.background(Color.Black.copy(alpha = 0.6f))
				) {
					Box(
						modifier = Modifier
							.fillMaxHeight()
							.fillMaxWidth(progress.coerceIn(0f, 1f))
							.background(PopColors.accent)
					)
				}
			}
		}

		Text(
			text = title,
			color = JellyfinTheme.colorScheme.onBackground,
			fontSize = 12.sp,
			fontWeight = FontWeight.W600,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.padding(top = 6.dp),
		)
		Text(
			text = subtitle,
			color = JellyfinTheme.colorScheme.listCaption,
			fontSize = 11.sp,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
	}
}
