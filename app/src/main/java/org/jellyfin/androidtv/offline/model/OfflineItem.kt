package org.jellyfin.androidtv.offline.model

import kotlinx.serialization.Serializable

@Serializable
enum class OfflineItemType {
	MOVIE,
	EPISODE,
	VIDEO,
}

@Serializable
enum class DownloadStatus {
	QUEUED,
	DOWNLOADING,
	COMPLETED,
	FAILED,
}

/**
 * Download quality presets. [maxWidth] and [videoBitrate] are only used for transcoded downloads.
 */
@Serializable
enum class DownloadQuality(
	val maxWidth: Int?,
	val videoBitrate: Int?,
	val audioBitrate: Int?,
) {
	ORIGINAL(null, null, null),
	HD_1080(1920, 8_000_000, 192_000),
	HD_720(1280, 4_000_000, 160_000),
	SD_480(854, 1_500_000, 128_000);

	val isTranscoded get() = this != ORIGINAL

	/** Estimated size in bytes for a transcoded download of the given runtime. */
	fun estimateBytes(runTimeTicks: Long?): Long? {
		if (videoBitrate == null || audioBitrate == null || runTimeTicks == null) return null
		val seconds = runTimeTicks / 10_000_000L
		return seconds * (videoBitrate + audioBitrate) / 8
	}
}

@Serializable
data class OfflineSubtitle(
	val index: Int,
	val language: String? = null,
	val title: String? = null,
	val codec: String,
	val fileName: String,
	val isDefault: Boolean = false,
	val isForced: Boolean = false,
)

/**
 * Metadata for a downloaded (or downloading) item. Stored as meta.json next to the media file so
 * downloads can be browsed and played without any server connection.
 */
@Serializable
data class OfflineItem(
	val id: String,
	val serverId: String? = null,
	val serverUrl: String,
	val userId: String,
	val type: OfflineItemType,
	val name: String,
	val overview: String? = null,
	val productionYear: Int? = null,
	val officialRating: String? = null,
	val communityRating: Float? = null,
	val runTimeTicks: Long? = null,
	val seriesId: String? = null,
	val seriesName: String? = null,
	val seasonId: String? = null,
	val seasonName: String? = null,
	val parentIndexNumber: Int? = null,
	val indexNumber: Int? = null,
	val mediaSourceId: String? = null,
	val audioStreamIndex: Int? = null,
	val quality: DownloadQuality = DownloadQuality.ORIGINAL,
	val container: String? = null,
	val sourceSizeBytes: Long? = null,
	val mediaFileName: String? = null,
	val totalBytes: Long = 0,
	val downloadedBytes: Long = 0,
	val status: DownloadStatus = DownloadStatus.QUEUED,
	val error: String? = null,
	val subtitles: List<OfflineSubtitle> = emptyList(),
	val hasPoster: Boolean = false,
	val hasBackdrop: Boolean = false,
	val positionMs: Long = 0,
	val played: Boolean = false,
	val pendingSync: Boolean = false,
	val addedAt: Long = System.currentTimeMillis(),
	val lastPlayedAt: Long? = null,
) {
	val progress: Float
		get() = when {
			status == DownloadStatus.COMPLETED -> 1f
			totalBytes <= 0 -> 0f
			else -> (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 0.99f)
		}

	val isCompleted get() = status == DownloadStatus.COMPLETED

	val displayTitle: String
		get() = when (type) {
			OfflineItemType.EPISODE -> buildString {
				if (parentIndexNumber != null) append("T").append(parentIndexNumber)
				if (indexNumber != null) append("E").append(indexNumber)
				if (isNotEmpty()) append(" · ")
				append(name)
			}

			else -> name
		}
}
