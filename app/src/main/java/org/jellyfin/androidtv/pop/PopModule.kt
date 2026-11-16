package org.jellyfin.androidtv.pop

import org.jellyfin.androidtv.pop.data.PopLibraryPreferences
import org.jellyfin.androidtv.pop.data.PopRequestsApi
import org.jellyfin.androidtv.pop.library.MyLibraryViewModel
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.navigation.NavigationRepositoryImpl
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Everything the fork adds to dependency injection. Loaded last (see `KoinInitializer`), so a
 * definition here replaces upstream's definition of the same type.
 */
val popModule = module {
	// The app opens on My library; upstream's Home stays one toolbar button away
	single<NavigationRepository> { NavigationRepositoryImpl(PopDestinations.myLibrary) }

	single { PopRequestsApi(get()) }
	single { PopLibraryPreferences(androidContext()) }

	viewModel { MyLibraryViewModel(get(), get(), get(), get()) }
}
