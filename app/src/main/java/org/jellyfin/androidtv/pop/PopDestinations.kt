package org.jellyfin.androidtv.pop

import org.jellyfin.androidtv.pop.discover.DiscoverFragment
import org.jellyfin.androidtv.pop.library.MyLibraryFragment
import org.jellyfin.androidtv.pop.requests.MyRequestsFragment
import org.jellyfin.androidtv.pop.streams.ActiveStreamsFragment
import org.jellyfin.androidtv.pop.syncplay.SyncPlayFragment
import org.jellyfin.androidtv.ui.navigation.fragmentDestination

/** Screens added by the fork, next to upstream's `Destinations`. */
object PopDestinations {
	val myLibrary = fragmentDestination<MyLibraryFragment>()
	val myRequests = fragmentDestination<MyRequestsFragment>()
	val discover = fragmentDestination<DiscoverFragment>()
	val activeStreams = fragmentDestination<ActiveStreamsFragment>()
	val syncPlay = fragmentDestination<SyncPlayFragment>()
}
