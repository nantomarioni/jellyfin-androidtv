package org.jellyfin.androidtv.pop.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.base.button.ButtonDefaults
import org.jellyfin.androidtv.ui.base.popover.Popover
import org.jellyfin.androidtv.ui.base.popover.PopoverMenu
import org.jellyfin.androidtv.ui.base.popover.PopoverMenuCheckboxItem

data class PillOption<T>(
	val value: T,
	val label: String,
)

/** A pill showing the current choice; pressing it opens a menu with the options under it. */
@Composable
fun <T> PickerPill(
	@DrawableRes icon: Int,
	label: String,
	options: List<PillOption<T>>,
	selected: T,
	onSelect: (T) -> Unit,
	modifier: Modifier = Modifier,
) = Box {
	var expanded by remember { mutableStateOf(false) }

	Button(
		onClick = { expanded = true },
		modifier = modifier,
	) {
		Icon(
			imageVector = ImageVector.vectorResource(icon),
			contentDescription = null,
			modifier = Modifier.size(16.dp),
		)
		Spacer(Modifier.width(8.dp))
		Text(label)
	}

	Popover(
		expanded = expanded,
		onDismissRequest = { expanded = false },
		alignment = Alignment.BottomCenter,
		offset = DpOffset(0.dp, 4.dp),
	) {
		PopoverMenu(
			modifier = Modifier
				.heightIn(max = 300.dp)
				.verticalScroll(rememberScrollState())
		) {
			options.forEach { option ->
				PopoverMenuCheckboxItem(
					selected = option.value == selected,
					onClick = {
						expanded = false
						onSelect(option.value)
					},
				) {
					Text(option.label)
				}
			}
		}
	}
}

/** An on/off pill: lit with a check mark while on. */
@Composable
fun TogglePill(
	label: String,
	checked: Boolean,
	onCheckedChange: (Boolean) -> Unit,
	modifier: Modifier = Modifier,
) = Button(
	onClick = { onCheckedChange(!checked) },
	modifier = modifier,
	colors = if (checked) {
		ButtonDefaults.colors(
			containerColor = JellyfinTheme.colorScheme.buttonActive,
			contentColor = JellyfinTheme.colorScheme.onButtonActive,
		)
	} else {
		ButtonDefaults.colors()
	},
) {
	Icon(
		imageVector = ImageVector.vectorResource(R.drawable.ic_check),
		contentDescription = null,
		modifier = Modifier
			.size(16.dp)
			.alpha(if (checked) 1f else 0.25f),
	)
	Spacer(Modifier.width(8.dp))
	Text(label)
}
