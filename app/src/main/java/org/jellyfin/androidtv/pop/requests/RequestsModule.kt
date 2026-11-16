package org.jellyfin.androidtv.pop.requests

import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val requestsModule = module {
	single { PopBoardApi(get()) }
	single { RequestsPreferences(androidContext()) }

	viewModel { MyRequestsViewModel(get(), get(), get()) }
}
