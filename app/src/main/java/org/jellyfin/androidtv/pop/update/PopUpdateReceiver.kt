package org.jellyfin.androidtv.pop.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber

/**
 * Receives the package installer's answer for the session [PopUpdateRepository] committed:
 * opens the system's confirmation screen when it asks for the user, reports a failure back.
 * On success the app is replaced and restarted by the system, so there is nothing to do.
 */
class PopUpdateReceiver : BroadcastReceiver(), KoinComponent {
	companion object {
		const val ACTION_INSTALL_STATUS = "org.jellyfin.androidtv.pop.update.INSTALL_STATUS"
	}

	private val repository by inject<PopUpdateRepository>()

	override fun onReceive(context: Context, intent: Intent) {
		if (intent.action != ACTION_INSTALL_STATUS) return

		when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
			PackageInstaller.STATUS_PENDING_USER_ACTION -> {
				val confirmation = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
				if (confirmation == null) {
					repository.onInstallFailed("no confirmation intent")
					return
				}

				try {
					context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
				} catch (@Suppress("TooGenericExceptionCaught") err: Exception) {
					Timber.e(err, "Update: could not open the install confirmation")
					repository.onInstallFailed(err.message)
				}
			}

			PackageInstaller.STATUS_SUCCESS -> Timber.i("Update: installed")

			else -> repository.onInstallFailed(
				"status $status: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}"
			)
		}
	}
}
