package org.jellyfin.androidtv.offline

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.jellyfin.androidtv.offline.data.DownloadRepository
import org.jellyfin.androidtv.offline.model.DownloadQuality
import org.jellyfin.androidtv.offline.model.DownloadStatus
import org.jellyfin.androidtv.offline.model.OfflineItem
import org.jellyfin.androidtv.offline.model.OfflineItemType

class OfflineItemTests : FunSpec({
	val base = OfflineItem(
		id = "abc",
		serverUrl = "http://server:8096",
		userId = "user",
		type = OfflineItemType.EPISODE,
		name = "Pilot",
		parentIndexNumber = 1,
		indexNumber = 2,
	)

	test("transcoded size estimate uses runtime and bitrates") {
		// 1 hour at 4 Mbps video + 160 kbps audio
		DownloadQuality.HD_720.estimateBytes(36_000_000_000L) shouldBe 3600L * 4_160_000 / 8
		DownloadQuality.ORIGINAL.estimateBytes(36_000_000_000L) shouldBe null
	}

	test("episode title includes season and episode number") {
		base.displayTitle shouldBe "T1E2 · Pilot"
		base.copy(type = OfflineItemType.MOVIE).displayTitle shouldBe "Pilot"
	}

	test("progress is capped until completed") {
		base.copy(totalBytes = 100, downloadedBytes = 100, status = DownloadStatus.DOWNLOADING).progress shouldBe 0.99f
		base.copy(totalBytes = 100, downloadedBytes = 50, status = DownloadStatus.DOWNLOADING).progress shouldBe 0.5f
		base.copy(status = DownloadStatus.COMPLETED).progress shouldBe 1f
		base.copy(totalBytes = 0).progress shouldBe 0f
	}

	test("metadata survives a JSON round trip and ignores unknown keys") {
		val json = Json { ignoreUnknownKeys = true }
		val encoded = json.encodeToString(OfflineItem.serializer(), base)
		json.decodeFromString(OfflineItem.serializer(), encoded) shouldBe base
		val withExtra = encoded.dropLast(1) + ",\"futureField\":true}"
		json.decodeFromString(OfflineItem.serializer(), withExtra) shouldBe base
	}

	test("subtitle codecs map to file extensions") {
		DownloadRepository.subtitleExtension("subrip") shouldBe "srt"
		DownloadRepository.subtitleExtension("ASS") shouldBe "ass"
		DownloadRepository.subtitleExtension("webvtt") shouldBe "vtt"
		DownloadRepository.subtitleExtension("PGSSUB") shouldBe null
	}
})

class EpisodeRangeTests : FunSpec({
	fun episode(n: Int) = org.jellyfin.sdk.model.api.BaseItemDto(
		id = java.util.UUID(0, n.toLong()),
		type = org.jellyfin.sdk.model.api.BaseItemKind.EPISODE,
		indexNumber = n,
	)
	val season = (1..8).map(::episode)

	test("download from an episode until the end of the season") {
		DownloadRepository.episodesFrom(season, season[4].id).map { it.indexNumber } shouldBe listOf(5, 6, 7, 8)
		DownloadRepository.episodesFrom(season, season[7].id).map { it.indexNumber } shouldBe listOf(8)
	}

	test("without a start episode or with an unknown one the whole season is used") {
		DownloadRepository.episodesFrom(season, null) shouldBe season
		DownloadRepository.episodesFrom(season, java.util.UUID.randomUUID()) shouldBe season
	}
})
