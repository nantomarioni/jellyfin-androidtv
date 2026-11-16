package org.jellyfin.androidtv.pop.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jellyfin.androidtv.BuildConfig
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A fork release newer than the installed build. */
data class PopUpdateRelease(
	/** Version name, for example `0.20.0-pop.7`. */
	val version: String,
	/** The `N` of `-pop.<N>`: the Android version code. */
	val versionCode: Int,
	val apkName: String,
	val apkUrl: String,
) {
	/** Short label for the toolbar, for example `pop.7`. */
	val label get() = "pop.$versionCode"
}

sealed interface PopUpdateState {
	/** No newer release (or not checked yet): nothing to show. */
	data object Idle : PopUpdateState
	data class Available(val release: PopUpdateRelease) : PopUpdateState

	/** @param progress Downloaded part between 0 and 1. */
	data class Downloading(val release: PopUpdateRelease, val progress: Float) : PopUpdateState

	/** Handed to the package installer; the system asks the user to confirm. */
	data class ReadyToInstall(val release: PopUpdateRelease) : PopUpdateState

	/** Download or install failed; pressing the button tries again. */
	data class Failed(val release: PopUpdateRelease) : PopUpdateState
}

/**
 * The fork's update channel: the latest GitHub Release of nantomarioni/jellyfin-androidtv. Tags are
 * `v<upstream version>-pop.<N>` and carry a signed `*-release.apk`; a release is newer when its `N`
 * is above this build's version code. The APK is installed through the system package installer
 * (the user confirms; a Fire TV asks once to allow "install unknown apps" for this app).
 */
class PopUpdateRepository(
	private val context: Context,
	okHttpFactory: OkHttpFactory,
	httpClientOptions: HttpClientOptions,
) {
	companion object {
		private const val LATEST_RELEASE_URL = "https://api.github.com/repos/nantomarioni/jellyfin-androidtv/releases/latest"
		private const val APK_SUFFIX = "-release.apk"
		private const val DOWNLOAD_DIRECTORY = "updates"
		private const val READ_TIMEOUT_SECONDS = 60L
		private const val BUFFER_SIZE = 64 * 1024
		private val popVersionRegex = Regex("""-pop\.(\d+)$""")

		/** The `N` of a `...-pop.<N>` version name or tag, null for anything else (local and upstream builds). */
		fun popVersionCode(version: String): Int? = popVersionRegex.find(version)?.groupValues?.get(1)?.toIntOrNull()
	}

	@Serializable
	private data class GithubRelease(
		@SerialName("tag_name") val tagName: String = "",
		val draft: Boolean = false,
		val prerelease: Boolean = false,
		val assets: List<GithubAsset> = emptyList(),
	)

	@Serializable
	private data class GithubAsset(
		val name: String = "",
		@SerialName("browser_download_url") val browserDownloadUrl: String = "",
	)

	private val json = Json {
		ignoreUnknownKeys = true
		coerceInputValues = true
	}

	// The shared client's call timeout would cut a slow APK download short
	private val client: OkHttpClient by lazy {
		okHttpFactory.createClient(httpClientOptions).newBuilder()
			.callTimeout(0, TimeUnit.MILLISECONDS)
			.readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
			.build()
	}

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val checked = AtomicBoolean(false)
	private var installJob: Job? = null

	private val _state = MutableStateFlow<PopUpdateState>(PopUpdateState.Idle)
	val state = _state.asStateFlow()

	/** Only builds made from a fork release tag update themselves (not debug or local builds). */
	private val installedVersionCode: Int? = popVersionCode(BuildConfig.VERSION_NAME)?.let { BuildConfig.VERSION_CODE }

	/** Look for a newer release, once per app process. */
	fun check() {
		if (installedVersionCode == null) return
		if (!checked.compareAndSet(false, true)) return

		scope.launch {
			try {
				val release = fetchLatestRelease()
				if (release != null && release.versionCode > installedVersionCode) {
					Timber.i("Update: %s is available (installed code %d)", release.version, installedVersionCode)
					_state.value = PopUpdateState.Available(release)
				}
			} catch (err: CancellationException) {
				throw err
			} catch (@Suppress("TooGenericExceptionCaught") err: Exception) {
				// Not shown: no update button is the right look when GitHub can't be reached. Try again next check.
				Timber.w(err, "Update: checking the latest release failed")
				checked.set(false)
			}
		}
	}

	/** Download the available release and hand it to the package installer. */
	fun downloadAndInstall() {
		val release = when (val current = _state.value) {
			is PopUpdateState.Available -> current.release
			is PopUpdateState.Failed -> current.release
			// The system dialog was dismissed or is slow to show: handing it over again is harmless
			is PopUpdateState.ReadyToInstall -> current.release
			else -> return
		}
		if (installJob?.isActive == true) return

		installJob = scope.launch {
			try {
				_state.value = PopUpdateState.Downloading(release, 0f)
				val apk = download(release)
				install(apk)
				_state.value = PopUpdateState.ReadyToInstall(release)
			} catch (err: CancellationException) {
				throw err
			} catch (@Suppress("TooGenericExceptionCaught") err: Exception) {
				Timber.e(err, "Update: installing %s failed", release.version)
				_state.value = PopUpdateState.Failed(release)
			}
		}
	}

	/** Called by [PopUpdateReceiver] when the package installer reports a failure (or the user declined). */
	internal fun onInstallFailed(message: String?) {
		Timber.w("Update: the package installer refused the update: %s", message)
		val release = when (val current = _state.value) {
			is PopUpdateState.ReadyToInstall -> current.release
			is PopUpdateState.Downloading -> current.release
			else -> return
		}
		_state.value = PopUpdateState.Failed(release)
	}

	private fun fetchLatestRelease(): PopUpdateRelease? {
		val request = Request.Builder()
			.url(LATEST_RELEASE_URL)
			.header("Accept", "application/vnd.github+json")
			.build()

		val release = client.newCall(request).execute().use { response ->
			if (!response.isSuccessful) throw IOException("GitHub answered ${response.code}")
			json.decodeFromString<GithubRelease>(response.body?.string() ?: throw IOException("GitHub sent no body"))
		}
		if (release.draft || release.prerelease) return null

		val version = release.tagName.removePrefix("v")
		val versionCode = popVersionCode(version) ?: return null
		val asset = release.assets.firstOrNull { it.name.endsWith(APK_SUFFIX) && it.browserDownloadUrl.startsWith("https://") }
			?: return null

		return PopUpdateRelease(
			version = version,
			versionCode = versionCode,
			apkName = asset.name,
			apkUrl = asset.browserDownloadUrl,
		)
	}

	private fun download(release: PopUpdateRelease): File {
		val directory = File(context.cacheDir, DOWNLOAD_DIRECTORY)
		// Only ever one APK on disk
		directory.deleteRecursively()
		if (!directory.mkdirs()) throw IOException("Could not create $directory")
		val target = File(directory, "update-${release.versionCode}.apk")

		val request = Request.Builder().url(release.apkUrl).build()
		client.newCall(request).execute().use { response ->
			if (!response.isSuccessful) throw IOException("Download answered ${response.code}")
			val body = response.body ?: throw IOException("Download has no body")
			val total = body.contentLength()

			body.byteStream().use { input ->
				target.outputStream().use { output ->
					val buffer = ByteArray(BUFFER_SIZE)
					var done = 0L
					while (true) {
						val read = input.read(buffer)
						if (read < 0) break
						output.write(buffer, 0, read)
						done += read
						if (total > 0) _state.value = PopUpdateState.Downloading(release, (done.toFloat() / total).coerceIn(0f, 1f))
					}
				}
			}
		}

		return target
	}

	private fun install(apk: File) {
		val installer = context.packageManager.packageInstaller
		val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
			setAppPackageName(context.packageName)
			setSize(apk.length())
			// Where the system allows it (Android 12+, this app installed the previous version), skip the confirmation
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
				setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
			}
		}

		val sessionId = installer.createSession(params)
		try {
			installer.openSession(sessionId).use { session ->
				session.openWrite("base.apk", 0, apk.length()).use { output ->
					apk.inputStream().use { input -> input.copyTo(output, BUFFER_SIZE) }
					session.fsync(output)
				}

				// The system fills in the status extras, so the PendingIntent must be mutable (and explicit)
				val statusIntent = Intent(context, PopUpdateReceiver::class.java)
					.setAction(PopUpdateReceiver.ACTION_INSTALL_STATUS)
					.setPackage(context.packageName)
				val flags = PendingIntent.FLAG_UPDATE_CURRENT or
					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
				val statusReceiver = PendingIntent.getBroadcast(context, sessionId, statusIntent, flags)

				session.commit(statusReceiver.intentSender)
			}
		} catch (@Suppress("TooGenericExceptionCaught") err: Exception) {
			runCatching { installer.abandonSession(sessionId) }
			throw err
		}
	}
}
