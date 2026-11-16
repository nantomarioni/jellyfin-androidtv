package org.jellyfin.androidtv.pop.update

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/** Auto update: included in `popModule`. */
val updateModule = module {
	single { PopUpdateRepository(androidContext(), get(), get()) }
}
