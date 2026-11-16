package org.jellyfin.androidtv.pop.streams

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** Dependency injection of the Active streams page; included by `popModule`. */
val streamsModule = module {
	viewModel { ActiveStreamsViewModel(get(), get()) }
}
