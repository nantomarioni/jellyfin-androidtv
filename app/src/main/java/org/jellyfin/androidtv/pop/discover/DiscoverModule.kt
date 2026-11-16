package org.jellyfin.androidtv.pop.discover

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** Dependency injection of the Discover page; included by `popModule`. */
val discoverModule = module {
	single { PopDiscoverApi(get()) }

	viewModel { DiscoverViewModel(get()) }
}
