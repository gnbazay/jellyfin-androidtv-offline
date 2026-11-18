package org.jellyfin.androidtv.offline.data

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.androidtv.offline.download.DownloadWorker
import org.jellyfin.androidtv.offline.model.DownloadQuality
import org.jellyfin.androidtv.offline.model.DownloadStatus
import org.jellyfin.androidtv.offline.model.OfflineItem
import org.jellyfin.androidtv.offline.model.OfflineItemType
import org.jellyfin.androidtv.offline.model.OfflineSubtitle
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.LocationType
import org.jellyfin.sdk.model.api.MediaStreamType
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.TimeUnit

class InsufficientSpaceException(val requiredBytes: Long, val availableBytes: Long) : Exception()
class DownloadNotAllowedException : Exception()

/**
 * Central place for everything related to offline items: enqueueing downloads, progress updates,
 * playback state and deletion. Backed by [OfflineStorage] (one meta.json per item).
 */
class DownloadRepository(
	private val storage: OfflineStorage,
	private val workManager: WorkManager,
	private val api: ApiClient,
	private val authStore: OfflineAuthStore,
	private val userRepository: UserRepository,
) {
	companion object {
		const val WORK_TAG = "offline-download"
		const val QUEUE_WORK_NAME = "offline-download-queue"

		private val subtitleFormats = mapOf(
			"subrip" to "srt",
			"srt" to "srt",
			"ass" to "ass",
			"ssa" to "ssa",
			"webvtt" to "vtt",
			"vtt" to "vtt",
			"mov_text" to "srt",
			"text" to "srt",
		)

		fun subtitleExtension(codec: String?) = subtitleFormats[codec?.lowercase()]
	}

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val _items = MutableStateFlow<List<OfflineItem>>(emptyList())
	val items: StateFlow<List<OfflineItem>> = _items.asStateFlow()

	init {
		scope.launch { reload() }
	}

	fun reload() {
		_items.value = storage.readAll().sortedByDescending { it.addedAt }
	}

	fun get(id: String): OfflineItem? = _items.value.firstOrNull { it.id == id }
	fun get(id: UUID): OfflineItem? = get(id.toString())

	fun observe(id: String): Flow<OfflineItem?> = items.map { list -> list.firstOrNull { it.id == id } }

	/** Update an item in memory and on disk. */
	fun update(id: String, persist: Boolean = true, transform: (OfflineItem) -> OfflineItem): OfflineItem? {
		var updated: OfflineItem? = null
		_items.update { list ->
			list.map { item ->
				if (item.id == id) transform(item).also { updated = it } else item
			}
		}
		if (persist) updated?.let(storage::write)
		return updated
	}

	/**
	 * Download a movie/episode/video, or every (unplayed) episode of a series or season.
	 * @return number of items queued
	 */
	suspend fun enqueue(
		item: BaseItemDto,
		quality: DownloadQuality,
		onlyUnplayed: Boolean = false,
	): Int = withContext(Dispatchers.IO) {
		when (item.type) {
			BaseItemKind.SERIES, BaseItemKind.SEASON -> {
				val seriesId = if (item.type == BaseItemKind.SERIES) item.id else requireNotNull(item.seriesId)
				val episodes = api.tvShowsApi.getEpisodes(
					seriesId = seriesId,
					seasonId = if (item.type == BaseItemKind.SEASON) item.id else null,
					fields = ItemRepository.itemFields,
				).content.items
					.filter { it.locationType != LocationType.VIRTUAL }
					.filter { !onlyUnplayed || it.userData?.played != true }
					.filter { get(it.id) == null }

				val required = episodes.sumOf { estimateBytes(it, quality) }
				ensureSpace(required)
				episodes.forEach { enqueueSingle(it, quality, checkSpace = false) }
				episodes.size
			}

			else -> {
				val full = api.userLibraryApi.getItem(itemId = item.id).content
				enqueueSingle(full, quality, checkSpace = true)
				1
			}
		}
	}

	private fun estimateBytes(item: BaseItemDto, quality: DownloadQuality): Long {
		val source = item.mediaSources?.firstOrNull()
		return if (quality.isTranscoded) quality.estimateBytes(item.runTimeTicks) ?: 0
		else source?.size ?: 0
	}

	private fun ensureSpace(required: Long) {
		// Space still needed by downloads that are already queued
		val pending = _items.value
			.filter { !it.isCompleted && it.status != DownloadStatus.FAILED }
			.sumOf { (it.totalBytes - it.downloadedBytes).coerceAtLeast(0) }
		val available = (storage.usableBytes() - pending).coerceAtLeast(0)
		if (required > available) throw InsufficientSpaceException(required, available)
	}

	private fun enqueueSingle(item: BaseItemDto, quality: DownloadQuality, checkSpace: Boolean) {
		val id = item.id.toString()
		val existing = get(id)
		if (existing?.isCompleted == true) return
		// Start over when an unfinished download is queued again (quality may have changed)
		if (existing != null) storage.delete(id)

		val source = item.mediaSources?.firstOrNull()
		if (checkSpace) ensureSpace(estimateBytes(item, quality))

		val streams = source?.mediaStreams.orEmpty()
		val subtitles = streams
			.filter { it.type == MediaStreamType.SUBTITLE }
			// Original files already contain embedded subtitles, only fetch the external ones
			.filter { quality.isTranscoded || it.isExternal }
			.mapNotNull { stream ->
				val ext = subtitleExtension(stream.codec) ?: return@mapNotNull null
				OfflineSubtitle(
					index = stream.index,
					language = stream.language,
					title = stream.displayTitle ?: stream.title,
					codec = ext,
					fileName = "${stream.index}.${stream.language ?: "und"}.$ext",
					isDefault = stream.isDefault,
					isForced = stream.isForced,
				)
			}

		val type = when (item.type) {
			BaseItemKind.MOVIE -> OfflineItemType.MOVIE
			BaseItemKind.EPISODE -> OfflineItemType.EPISODE
			else -> OfflineItemType.VIDEO
		}

		val container = if (quality.isTranscoded) "mkv"
		else source?.container?.substringBefore(',')?.ifBlank { null } ?: "mkv"

		val offlineItem = OfflineItem(
			id = id,
			serverId = item.serverId,
			serverUrl = requireNotNull(api.baseUrl).trimEnd('/'),
			userId = userRepository.currentUser.value?.id?.toString() ?: "",
			type = type,
			name = item.name ?: id,
			overview = item.overview,
			productionYear = item.productionYear,
			officialRating = item.officialRating,
			communityRating = item.communityRating,
			runTimeTicks = item.runTimeTicks,
			seriesId = item.seriesId?.toString(),
			seriesName = item.seriesName,
			seasonId = item.seasonId?.toString(),
			seasonName = item.seasonName,
			parentIndexNumber = item.parentIndexNumber,
			indexNumber = item.indexNumber,
			mediaSourceId = source?.id,
			quality = quality,
			container = container,
			sourceSizeBytes = source?.size,
			mediaFileName = "media.$container",
			totalBytes = estimateBytes(item, quality),
			status = DownloadStatus.QUEUED,
			subtitles = subtitles,
			positionMs = (item.userData?.playbackPositionTicks ?: 0) / 10_000,
			played = item.userData?.played == true,
			audioStreamIndex = source?.defaultAudioStreamIndex,
		)

		storage.write(offlineItem)
		_items.update { list -> listOf(offlineItem) + list.filterNot { it.id == id } }
		Timber.i("Queued offline download for $id (${item.name}) in quality $quality")
		startQueue()
	}

	/** Retry a failed download. */
	fun retry(id: String) {
		update(id) { it.copy(status = DownloadStatus.QUEUED, error = null) } ?: return
		startQueue()
	}

	/**
	 * Start (or keep running) the single worker that processes all queued downloads one by one.
	 * The authorization of the current session is stored so the worker can run without the UI.
	 */
	fun startQueue() {
		if (api.accessToken != null && api.baseUrl != null) {
			authStore.save(
				serverUrl = requireNotNull(api.baseUrl).trimEnd('/'),
				authorization = AuthorizationHeaderBuilder.buildHeader(
					api.clientInfo.name,
					api.clientInfo.version,
					api.deviceInfo.id,
					api.deviceInfo.name,
					api.accessToken,
				),
				deviceId = api.deviceInfo.id,
			)
		}

		val request = OneTimeWorkRequestBuilder<DownloadWorker>()
			.addTag(WORK_TAG)
			.setConstraints(
				Constraints.Builder()
					.setRequiredNetworkType(NetworkType.CONNECTED)
					.build()
			)
			.setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
			.build()

		workManager.enqueueUniqueWork(QUEUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
	}

	/** Next item the download worker should process. */
	fun nextQueued(): OfflineItem? {
		reload()
		return _items.value
			.filter { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING }
			.minByOrNull { it.addedAt }
	}

	fun delete(id: String) {
		storage.delete(id)
		_items.update { list -> list.filterNot { it.id == id } }
	}

	fun savePlaybackPosition(id: String, positionMs: Long, durationMs: Long) {
		val played = durationMs > 0 && positionMs >= durationMs * 0.9
		update(id) {
			it.copy(
				positionMs = if (played) 0 else positionMs,
				played = played || it.played,
				pendingSync = true,
				lastPlayedAt = System.currentTimeMillis(),
			)
		}
	}

	fun markSynced(id: String) {
		update(id) { it.copy(pendingSync = false) }
	}

	fun freeBytes() = storage.freeBytes()
	fun usedBytes() = storage.usedBytes()
}
