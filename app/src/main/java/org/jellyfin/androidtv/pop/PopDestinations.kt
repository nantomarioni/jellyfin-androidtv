package org.jellyfin.androidtv.pop

import org.jellyfin.androidtv.pop.library.MyLibraryFragment
import org.jellyfin.androidtv.ui.navigation.fragmentDestination

/** Screens added by the fork, next to upstream's `Destinations`. */
object PopDestinations {
	val myLibrary = fragmentDestination<MyLibraryFragment>()
}
