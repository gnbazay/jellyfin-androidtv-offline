package org.jellyfin.androidtv.offline.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.offline.data.DownloadRepository
import org.jellyfin.androidtv.offline.data.OfflineAuthStore
import org.jellyfin.androidtv.offline.data.OfflineStorage
import org.jellyfin.androidtv.offline.model.DownloadStatus
import org.jellyfin.androidtv.offline.model.OfflineItem
import org.jellyfin.androidtv.offline.model.OfflineItemType
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Processes the offline download queue one item at a time. Original files are resumed with HTTP
 * range requests after connection loss; transcoded files are restarted (the server can't resume).
 */
class DownloadWorker(
	context: Context,
	params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {
	companion object {
		private const val CHANNEL_ID = "offline_downloads"
		private const val NOTIFICATION_ID = 0x0FF1
		private const val BUFFER_SIZE = 256 * 1024
		private const val PROGRESS_INTERVAL_MS = 1_000L
		private const val PERSIST_INTERVAL_MS = 10_000L
		private const val MAX_ATTEMPTS = 30
	}

	private sealed interface ItemResult {
		data object Success : ItemResult
		data object Removed : ItemResult
		data class Fatal(val message: String) : ItemResult
		data class Retry(val cause: Throwable) : ItemResult
	}

	private val repository by inject<DownloadRepository>()
	private val storage by inject<OfflineStorage>()
	private val authStore by inject<OfflineAuthStore>()

	private val client = OkHttpClient.Builder()
		.connectTimeout(30, TimeUnit.SECONDS)
		// Transcoded downloads may take a while before the first bytes arrive
		.readTimeout(3, TimeUnit.MINUTES)
		.retryOnConnectionFailure(true)
		.build()

	override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
		createChannel()

		while (true) {
			ensureActive()
			val item = repository.nextQueued() ?: break

			runCatching { setForeground(createForegroundInfo(item, 0)) }
				.onFailure { Timber.w(it, "Unable to promote download worker to foreground") }

			when (val result = download(item)) {
				ItemResult.Success -> Timber.i("Download of ${item.id} completed")
				ItemResult.Removed -> Timber.i("Download of ${item.id} removed while downloading")
				is ItemResult.Fatal -> {
					Timber.w("Download of ${item.id} failed: ${result.message}")
					repository.update(item.id) { it.copy(status = DownloadStatus.FAILED, error = result.message) }
				}

				is ItemResult.Retry -> {
					Timber.w(result.cause, "Download of ${item.id} interrupted (attempt $runAttemptCount)")
					return@withContext if (runAttemptCount < MAX_ATTEMPTS) {
						repository.update(item.id) { it.copy(error = result.cause.message) }
						Result.retry()
					} else {
						repository.update(item.id) {
							it.copy(status = DownloadStatus.FAILED, error = result.cause.message)
						}
						Result.failure()
					}
				}
			}
		}

		Result.success()
	}

	private suspend fun download(item: OfflineItem): ItemResult {
		val authorization = authStore.authorization(item.serverUrl)
			?: return ItemResult.Fatal(applicationContext.getString(R.string.offline_error_no_session))

		repository.update(item.id) { it.copy(status = DownloadStatus.DOWNLOADING, error = null) }

		return try {
			downloadImages(item, authorization)
			downloadSubtitles(item, authorization)
			downloadMedia(item, authorization)
		} catch (err: HttpException) {
			when (err.code) {
				401, 403 -> ItemResult.Fatal(applicationContext.getString(R.string.offline_error_forbidden))
				404 -> ItemResult.Fatal(applicationContext.getString(R.string.offline_error_not_found))
				in 500..599 -> ItemResult.Retry(err)
				else -> ItemResult.Fatal("HTTP ${err.code}")
			}
		} catch (err: IOException) {
			if (repository.get(item.id) == null) ItemResult.Removed else ItemResult.Retry(err)
		}
	}

	private class HttpException(val code: Int) : IOException("HTTP $code")

	private fun request(url: String, authorization: String, rangeStart: Long = 0): Response {
		val request = Request.Builder()
			.url(url)
			.header("Authorization", authorization)
			.apply { if (rangeStart > 0) header("Range", "bytes=$rangeStart-") }
			.build()
		val response = client.newCall(request).execute()
		if (!response.isSuccessful) {
			response.close()
			throw HttpException(response.code)
		}
		return response
	}

	private fun downloadFile(url: String, authorization: String, target: File) {
		if (target.exists() && target.length() > 0) return
		val tmp = File(target.parentFile, "${target.name}.tmp")
		request(url, authorization).use { response ->
			requireNotNull(response.body).byteStream().use { input ->
				FileOutputStream(tmp).use { output -> input.copyTo(output) }
			}
		}
		tmp.renameTo(target)
	}

	private fun downloadImages(item: OfflineItem, authorization: String) {
		val posterId = if (item.type == OfflineItemType.EPISODE) item.seriesId ?: item.id else item.id
		val (backdropId, backdropType) = when (item.type) {
			OfflineItemType.EPISODE -> item.id to "Primary"
			else -> item.id to "Backdrop"
		}

		if (!item.hasPoster) runCatching {
			downloadFile("${item.serverUrl}/Items/$posterId/Images/Primary?maxHeight=600&quality=90", authorization, storage.posterFile(item.id))
			repository.update(item.id) { it.copy(hasPoster = true) }
		}.onFailure { Timber.w(it, "Unable to download poster for ${item.id}") }

		if (!item.hasBackdrop) runCatching {
			downloadFile("${item.serverUrl}/Items/$backdropId/Images/$backdropType?maxWidth=1280&quality=85", authorization, storage.backdropFile(item.id))
			repository.update(item.id) { it.copy(hasBackdrop = true) }
		}.onFailure { Timber.w(it, "Unable to download backdrop for ${item.id}") }
	}

	private fun downloadSubtitles(item: OfflineItem, authorization: String) {
		val mediaSourceId = item.mediaSourceId ?: return
		for (subtitle in item.subtitles) {
			val target = storage.subtitleFile(item.id, subtitle.fileName)
			runCatching {
				downloadFile(
					"${item.serverUrl}/Videos/${item.id}/$mediaSourceId/Subtitles/${subtitle.index}/0/Stream.${subtitle.codec}",
					authorization,
					target,
				)
			}.onFailure { Timber.w(it, "Unable to download subtitle ${subtitle.index} for ${item.id}") }
		}
	}

	private fun mediaUrl(item: OfflineItem): String {
		if (!item.quality.isTranscoded) return "${item.serverUrl}/Items/${item.id}/Download"

		val deviceId = authStore.deviceId(item.serverUrl) ?: "jellyfin-offline"
		val params = buildList {
			add("static=false")
			item.mediaSourceId?.let { add("mediaSourceId=$it") }
			add("deviceId=$deviceId")
			add("playSessionId=${UUID.randomUUID().toString().replace("-", "")}")
			add("container=mkv")
			add("videoCodec=h264")
			add("audioCodec=aac")
			add("audioChannels=2")
			add("maxAudioChannels=2")
			item.quality.maxWidth?.let { add("maxWidth=$it") }
			item.quality.videoBitrate?.let { add("videoBitRate=$it") }
			item.quality.audioBitrate?.let { add("audioBitRate=$it") }
			item.audioStreamIndex?.let { add("audioStreamIndex=$it") }
			add("subtitleMethod=Drop")
			add("context=Static")
		}
		return "${item.serverUrl}/Videos/${item.id}/stream.mkv?${params.joinToString("&")}"
	}

	private suspend fun downloadMedia(item: OfflineItem, authorization: String): ItemResult {
		val target = storage.mediaFile(item) ?: return ItemResult.Fatal("No media file name")
		if (target.exists()) return complete(item, target)

		val part = storage.partFile(item.id)
		// Transcoded streams can't be resumed
		if (item.quality.isTranscoded && part.exists()) part.delete()

		val resumeFrom = if (part.exists()) part.length() else 0L
		val response = request(mediaUrl(item), authorization, resumeFrom)

		response.use {
			val append = resumeFrom > 0 && response.code == 206
			val body = requireNotNull(response.body)
			val contentLength = body.contentLength()
			val total = when {
				contentLength > 0 && append -> resumeFrom + contentLength
				contentLength > 0 -> contentLength
				else -> item.totalBytes
			}

			var downloaded = if (append) resumeFrom else 0L
			repository.update(item.id) { it.copy(totalBytes = total, downloadedBytes = downloaded) }

			var lastProgress = 0L
			var lastPersist = System.currentTimeMillis()
			body.byteStream().use { input ->
				FileOutputStream(part, append).use { output ->
					val buffer = ByteArray(BUFFER_SIZE)
					while (true) {
						if (isStopped) throw IOException("Worker stopped")
						val read = input.read(buffer)
						if (read == -1) break
						output.write(buffer, 0, read)
						downloaded += read

						val now = System.currentTimeMillis()
						if (now - lastProgress >= PROGRESS_INTERVAL_MS) {
							lastProgress = now
							// Deleted or queued again while downloading
							if (repository.get(item.id)?.addedAt != item.addedAt) return ItemResult.Removed
							val persist = now - lastPersist >= PERSIST_INTERVAL_MS
							if (persist) lastPersist = now
							val updated = repository.update(item.id, persist) {
								it.copy(downloadedBytes = downloaded, totalBytes = maxOf(total, downloaded))
							} ?: return ItemResult.Removed
							val percent = (updated.progress * 100).toInt()
							runCatching { setForeground(createForegroundInfo(updated, percent)) }
						}
					}
				}
			}

			if (contentLength > 0 && downloaded < total) throw IOException("Incomplete download ($downloaded/$total)")
		}

		if (repository.get(item.id)?.addedAt != item.addedAt) return ItemResult.Removed
		if (!part.renameTo(target)) return ItemResult.Fatal("Unable to move downloaded file")
		return complete(item, target)
	}

	private fun complete(item: OfflineItem, target: File): ItemResult {
		repository.update(item.id) {
			it.copy(
				status = DownloadStatus.COMPLETED,
				downloadedBytes = target.length(),
				totalBytes = target.length(),
				error = null,
			)
		}
		return ItemResult.Success
	}

	private fun createChannel() {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
		val channel = NotificationChannel(
			CHANNEL_ID,
			applicationContext.getString(R.string.offline_downloads),
			NotificationManager.IMPORTANCE_LOW,
		)
		NotificationManagerCompat.from(applicationContext).createNotificationChannel(channel)
	}

	private fun createForegroundInfo(item: OfflineItem, percent: Int): ForegroundInfo {
		val title = if (item.seriesName != null) "${item.seriesName} · ${item.displayTitle}" else item.displayTitle
		val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
			.setSmallIcon(R.drawable.app_icon_foreground)
			.setContentTitle(applicationContext.getString(R.string.offline_downloading))
			.setContentText(title)
			.setProgress(100, percent, percent == 0 && item.quality.isTranscoded)
			.setOngoing(true)
			.setOnlyAlertOnce(true)
			.build()

		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
		} else {
			ForegroundInfo(NOTIFICATION_ID, notification)
		}
	}
}
