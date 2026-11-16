package org.jellyfin.androidtv.pop.syncplay

import androidx.lifecycle.ProcessLifecycleOwner
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val syncPlayModule = module {
	single { SyncPlayPlayerSync(androidContext(), get(), get(), get()) }
	single { SyncPlayRepository(androidContext(), get(), get(), ProcessLifecycleOwner.get().lifecycle) }

	viewModel { SyncPlayViewModel(get(), get()) }
}
