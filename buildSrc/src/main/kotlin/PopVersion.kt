/**
 * Fork (pop) releases are tagged `v<upstream version>-pop.<N>`, for example `v0.20.0-pop.7`. `N` counts the
 * fork's releases and never resets, so it is the version code: it keeps growing when the fork moves to
 * a newer upstream version. Returns null for any other version name (local builds, upstream tags).
 */
fun getPopVersionCode(versionName: String): Int? =
	Regex("""-pop\.(\d+)$""").find(versionName)?.groupValues?.get(1)?.toIntOrNull()
