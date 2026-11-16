# Pop fork roadmap

Agreed with the owner on 2026-11-15. One page at a time, in this order; each
ends with a tagged release that reaches the Fire TV.

Kept from upstream (verified, not rebuilt): Media3 hardware playback, saved
users that switch without credentials, the user picker on open (the fork makes
it the default).

| # | Page | Data | State |
|---|---|---|---|
| 1 | **My library** (landing page) | plugin `GET /PopRequests/library`; Continue watching from Jellyfin `UserItems/Resume` + `Shows/NextUp` | done, tested on an emulator against the live server |
| 2 | **Auto update** | latest GitHub Release of this repo → download the signed APK → `PackageInstaller` | written; never run end to end (needs a release newer than the installed one, and a Fire TV) |
| 3 | **Requests** + "Requested by" on item details | plugin `GET /PopRequests/board`, `GET /PopRequests/requesters/{itemId}` | done, tested read-only against the live server |
| 4 | **Streams** | Jellyfin sessions API | written; only the empty state was seen live |
| 5 | **Discover** (Seerr suggestions and requesting) | plugin `GET /PopRequests/discover`, `POST /PopRequests/request` | app and plugin code written; the plugin change is not deployed, so the page shows "needs the plugin update" |
| 6 | **SyncPlay** | Jellyfin SyncPlay API + websocket | group page written; player sync is behind an experimental toggle, never run against a second client |

## Notes per page

- **Auto update.** Compares the release tag's `-pop.<N>` with the installed
  version code; a toolbar pill appears when a newer release exists. To test:
  install release N, publish N+1, open the app. Still missing: a settings entry.
- **Requested by.** Appended to the genre line of upstream's Leanback details
  screen and re-applied on a 1 s check while it is shown.
- **Streams.** Jellyfin shows a non-admin only their own sessions; if everyone
  should see all streams, add a plugin endpoint.
- **Seerr.** Most users sign in through Google SSO and have no Seerr password,
  so the app never talks to Seerr itself. The plugin calls Seerr with the API
  key plus `X-API-User: <seerr user id>`; Seerr 3.5.1 honours it
  (`server/middleware/auth.ts`, `checkUser`). Plugin changes restart Jellyfin.
  Unchecked against the live Seerr: the response fields the plugin reads, and
  "recommended" (Seerr recommendations for the viewer's three latest requests,
  else trending).
- **SyncPlay.** Wired to upstream's legacy `PlaybackController` (the default
  player) by polling it every 250 ms. Missing: buffering reports, multi-item
  queues, the rewritten player, leaving the group on user switch. Needs a
  two-client test before the toggle can lose "experimental".

## Open polish (My library)

- The picker menus open over their pill instead of under it.
- A Continue watching card opens item details (as upstream's Home does); direct
  resume on click is a possible change.
- Home → Back: upstream's Home button replaces the root page, so the first
  Back press does nothing and the second leaves the app.
- Look: restyle further toward the web client (avatars of requesters on
  posters, focused pill contrast).
