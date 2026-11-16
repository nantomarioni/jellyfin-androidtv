# Pop fork roadmap

Agreed with the owner on 2026-11-15. One page at a time, in this order; each
ends with a tagged release that reaches the Fire TV.

Kept from upstream (verified, not rebuilt): Media3 hardware playback, saved
users that switch without credentials, the user picker on open (the fork makes
it the default).

| # | Page | Data | State |
|---|---|---|---|
| 1 | **My library** (landing page) | plugin `GET /PopRequests/library`; Continue watching from Jellyfin `UserItems/Resume` + `Shows/NextUp` | done |
| 2 | **Auto update** | latest GitHub Release of this repo → download the signed APK → package installer (Fire TV asks once for "install unknown apps") | next |
| 3 | **My requests** + "Requested by" on item details | plugin `GET /PopRequests/board` (`Services/BoardModel.cs`), `GET /PopRequests/requesters/{itemId}` | |
| 4 | **Active streams** | Jellyfin sessions API | |
| 5 | **Seerr recommendations and requesting** | new plugin endpoints calling Seerr with its API key | |
| 6 | **SyncPlay** | Jellyfin SyncPlay API + websocket; absent upstream | |

## Notes per page

- **Auto update.** Needs `REQUEST_INSTALL_PACKAGES` and a `FileProvider` in the
  manifest (new hook). Compare the release tag's `-pop.<N>` with
  `BuildConfig.VERSION_CODE`. Check on start and from a settings entry.
- **Requested by.** Upstream's details screen (`ui/itemdetail/FullDetailsFragment`)
  is still Leanback/Java: expect the hook there to be the least tidy one.
- **Active streams.** Jellyfin shows a non-admin only their own sessions; if
  everyone should see all streams, add a plugin endpoint.
- **Seerr.** Most users sign in through Google SSO and have no Seerr password,
  so the app never talks to Seerr itself. The plugin calls Seerr with the API
  key plus `X-API-User: <seerr user id>`; Seerr 3.5.1 honours it
  (`server/middleware/auth.ts`, `checkUser`). Plugin changes restart Jellyfin.
- **SyncPlay.** Hardest: time sync, group state over the websocket, wiring into
  the player. Decide then whether to target upstream's legacy player or the
  rewrite (`ui/player/video`).

## Open polish (My library)

- The picker menus open over their pill instead of under it.
- A Continue watching card opens item details (as upstream's Home does); direct
  resume on click is a possible change.
- Home → Back: upstream's Home button replaces the root page, so the first
  Back press does nothing and the second leaves the app.
- Look: restyle further toward the web client (avatars of requesters on
  posters, focused pill contrast).
