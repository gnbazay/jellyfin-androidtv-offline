package org.jellyfin.androidtv.offline.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jellyfin.androidtv.BuildConfig
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

data class AvailableUpdate(
	val tag: String,
	val versionCode: Int,
	val apkUrl: String,
	val notes: String?,
)

/** Very small Markdown cleanup so release notes read well in a plain dialog. */
internal fun markdownToPlainText(markdown: String): String = markdown
	.lines()
	// Join wrapped continuation lines ("  text") with the line before
	.fold(mutableListOf<String>()) { lines, line ->
		if (line.startsWith("  ") && line.isNotBlank() && !line.trimStart().startsWith("-") && lines.isNotEmpty()) {
			lines[lines.lastIndex] = lines.last() + " " + line.trim()
		} else {
			lines += line
		}
		lines
	}
	.joinToString("\n") { line ->
		line
			.replace(Regex("^#{1,6}\\s*"), "")
			.replace(Regex("^(\\s*)[-*]\\s+"), "$1• ")
			.replace("**", "")
			.replace(Regex("(?<!\\w)[*_]([^*_]+)[*_](?!\\w)"), "$1")
	}
	.replace(Regex("\n{3,}"), "\n\n")
	.trim()

/**
 * Checks the GitHub releases of this fork for a newer APK, downloads and installs it.
 */
class UpdateChecker(private val context: Context) {
	companion object {
		const val APK_ASSET_NAME = "jellyfin-offline.apk"
		private const val VERSION_ASSET_NAME = "version.txt"
		private const val AUTO_CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000
	}

	private val preferences = context.getSharedPreferences("offline_updates", Context.MODE_PRIVATE)
	private val client = OkHttpClient.Builder()
		.connectTimeout(15, TimeUnit.SECONDS)
		.readTimeout(60, TimeUnit.SECONDS)
		.build()
	private val json = Json { ignoreUnknownKeys = true }

	val repository: String get() = BuildConfig.UPDATE_REPO

	fun shouldAutoCheck(): Boolean {
		val last = preferences.getLong("last_check", 0)
		return System.currentTimeMillis() - last > AUTO_CHECK_INTERVAL_MS
	}

	/** Returns the newest release if it is newer than the installed version. */
	suspend fun check(): AvailableUpdate? = withContext(Dispatchers.IO) {
		preferences.edit { putLong("last_check", System.currentTimeMillis()) }

		val release = get("https://api.github.com/repos/$repository/releases/latest") { body ->
			json.parseToJsonElement(body).jsonObject
		}
		val tag = release["tag_name"]?.jsonPrimitive?.contentOrNull ?: return@withContext null
		val assets = release["assets"]?.jsonArray.orEmpty().map { it.jsonObject }
		fun assetUrl(name: String) = assets
			.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull == name }
			?.get("browser_download_url")?.jsonPrimitive?.contentOrNull

		val apkUrl = assetUrl(APK_ASSET_NAME) ?: return@withContext null
		// version.txt contains "v<versionName>=<versionCode>"
		val versionCode = assetUrl(VERSION_ASSET_NAME)
			?.let { url -> get(url) { it.trim().substringAfter('=').toIntOrNull() } }
			?: return@withContext null

		Timber.i("Latest release $tag ($versionCode), installed ${BuildConfig.VERSION_CODE}")
		if (versionCode <= BuildConfig.VERSION_CODE) return@withContext null

		AvailableUpdate(
			tag = tag,
			versionCode = versionCode,
			apkUrl = apkUrl,
			notes = release["body"]?.jsonPrimitive?.contentOrNull?.let(::markdownToPlainText),
		)
	}

	private fun <T> get(url: String, parse: (String) -> T): T {
		val request = Request.Builder()
			.url(url)
			.header("Accept", "application/vnd.github+json")
			.build()
		client.newCall(request).execute().use { response ->
			if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
			return parse(requireNotNull(response.body).string())
		}
	}

	suspend fun download(update: AvailableUpdate, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
		val dir = File(context.cacheDir, "updates").apply {
			deleteRecursively()
			mkdirs()
		}
		val target = File(dir, "update-${update.versionCode}.apk")
		client.newCall(Request.Builder().url(update.apkUrl).build()).execute().use { response ->
			if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
			val total = requireNotNull(response.body).contentLength()
			var read = 0L
			requireNotNull(response.body).byteStream().use { input ->
				FileOutputStream(target).use { output ->
					val buffer = ByteArray(64 * 1024)
					while (true) {
						val count = input.read(buffer)
						if (count == -1) break
						output.write(buffer, 0, count)
						read += count
						if (total > 0) onProgress((read * 100 / total).toInt())
					}
				}
			}
		}
		target
	}

	/** Whether the user still has to allow this app to install packages. */
	fun needsInstallPermission(): Boolean =
		Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()

	fun openInstallPermissionSettings(activity: Activity) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
		val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
		runCatching { activity.startActivity(intent) }
			.onFailure { Timber.w(it, "Unable to open unknown sources settings") }
	}

	fun install(activity: Activity, apk: File) {
		val uri = FileProvider.getUriForFile(activity, "${context.packageName}.offline.fileprovider", apk)
		val intent = Intent(Intent.ACTION_VIEW).apply {
			setDataAndType(uri, "application/vnd.android.package-archive")
			addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
		}
		activity.startActivity(intent)
	}
}
