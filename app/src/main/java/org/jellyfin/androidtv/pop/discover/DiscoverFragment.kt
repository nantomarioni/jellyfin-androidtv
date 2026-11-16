package org.jellyfin.androidtv.pop.discover

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import org.jellyfin.androidtv.data.service.BackgroundService
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class DiscoverFragment : Fragment() {
	private val viewModel by viewModel<DiscoverViewModel>()
	private val backgroundService by inject<BackgroundService>()

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	) = content {
		DiscoverScreen(viewModel)
	}

	override fun onResume() {
		super.onResume()

		// A plain dark page: no backdrop left over from the screen we came from
		backgroundService.clearBackgrounds()
		viewModel.onEnter()
	}
}
