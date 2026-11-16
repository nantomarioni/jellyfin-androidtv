package org.jellyfin.androidtv.pop.requests

import android.content.Context
import androidx.core.content.edit
import java.util.UUID

/** Whose requests the board shows (mine or everyone's), remembered on this device per user. */
class RequestsPreferences(context: Context) {
	private val preferences = context.getSharedPreferences("pop_requests", Context.MODE_PRIVATE)

	fun getEveryone(userId: UUID): Boolean = preferences.getBoolean("$userId.everyone", false)

	fun setEveryone(userId: UUID, everyone: Boolean) = preferences.edit {
		putBoolean("$userId.everyone", everyone)
	}
}
