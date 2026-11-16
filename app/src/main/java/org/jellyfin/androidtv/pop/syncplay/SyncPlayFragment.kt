package org.jellyfin.androidtv.pop.syncplay

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import org.jellyfin.androidtv.data.service.BackgroundService
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class SyncPlayFragment : Fragment() {
	private val viewModel by viewModel<SyncPlayViewModel>()
	private val backgroundService by inject<BackgroundService>()

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	) = content {
		SyncPlayScreen(viewModel)
	}

	override fun onResume() {
		super.onResume()

		backgroundService.clearBackgrounds()
		viewModel.onEnter()
	}

	override fun onPause() {
		super.onPause()

		viewModel.onLeave()
	}
}
