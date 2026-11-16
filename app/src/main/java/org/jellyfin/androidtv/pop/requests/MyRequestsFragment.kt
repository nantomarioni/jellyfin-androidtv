package org.jellyfin.androidtv.pop.requests

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class MyRequestsFragment : Fragment() {
	private val viewModel by viewModel<MyRequestsViewModel>()
	private val navigationRepository by inject<NavigationRepository>()
	private val backgroundService by inject<BackgroundService>()

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	) = content {
		MyRequestsScreen(
			viewModel = viewModel,
			onOpenItem = { itemId -> navigationRepository.navigate(Destinations.itemDetails(itemId)) },
		)
	}

	override fun onResume() {
		super.onResume()

		backgroundService.clearBackgrounds()
		viewModel.onEnter()
	}
}
