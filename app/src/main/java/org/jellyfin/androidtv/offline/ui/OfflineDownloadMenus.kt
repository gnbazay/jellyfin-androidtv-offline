package org.jellyfin.androidtv.offline.ui

import android.content.Context
import android.text.format.Formatter
import android.view.View
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.offline.data.DownloadRepository
import org.jellyfin.androidtv.offline.data.InsufficientSpaceException
import org.jellyfin.androidtv.offline.data.StorageUnavailableException
import org.jellyfin.androidtv.offline.model.DownloadQuality
import org.jellyfin.androidtv.offline.model.DownloadStatus
import org.jellyfin.androidtv.offline.model.OfflineItem
import org.jellyfin.androidtv.offline.player.OfflinePlayerActivity
import org.jellyfin.androidtv.util.popupMenu
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.java.KoinJavaComponent.get
import timber.log.Timber

/**
 * The popup menus used to start downloads, shared by the details screen button and the long
 * press menu of item cards.
 *
 * @param scope a lifecycle bound scope (fragment or activity) for the server requests
 * @param anchor the view the popups are shown next to
 */
class OfflineDownloadMenus(
	private val context: Context,
	private val scope: CoroutineScope,
	private val anchor: View,
) {
	private val repository get() = get<DownloadRepository>(DownloadRepository::class.java)

	private fun canDownload(): Boolean {
		val user = get<UserRepository>(UserRepository::class.java).currentUser.value
		val allowed = user?.policy?.enableContentDownloading != false
		if (!allowed) Toast.makeText(context, R.string.offline_error_forbidden, Toast.LENGTH_LONG).show()
		return allowed
	}

	/** Series: pick one of the seasons, downloading a complete series at once is not offered. */
	fun showSeasonMenu(series: BaseItemDto) {
		if (!canDownload()) return

		scope.launch {
			val seasons = runCatching { repository.getSeasons(series.id) }
				.onFailure(::showError)
				.getOrNull() ?: return@launch

			popupMenu(context, anchor) {
				seasons.forEach { season ->
					item(seasonLabel(season)) { showEpisodeScopeMenu(season) }
				}
				item(context.getString(R.string.offline_open_downloads)) { openDownloads() }
			}.show()
		}
	}

	private fun seasonLabel(season: BaseItemDto): String {
		val name = season.name ?: context.getString(R.string.offline_season_number, season.indexNumber ?: 0)
		val total = season.childCount ?: 0
		val downloaded = repository.downloadedCount(season.id)
		val status = when {
			total > 0 && downloaded >= total -> context.getString(R.string.offline_season_downloaded)
			downloaded > 0 -> context.getString(R.string.offline_season_partial, downloaded, total)
			total > 0 -> context.resources.getQuantityString(R.plurals.offline_episode_count, total, total)
			else -> null
		}
		return listOfNotNull(name, status).joinToString(" · ")
	}

	/** Season: all episodes or only the unwatched ones. */
	fun showEpisodeScopeMenu(season: BaseItemDto) {
		if (!canDownload()) return
		val seriesId = season.seriesId ?: return

		popupMenu(context, anchor) {
			item(context.getString(R.string.offline_download_all_episodes)) {
				loadEpisodes { repository.getDownloadableEpisodes(seriesId, season.id, onlyUnplayed = false) }
			}
			item(context.getString(R.string.offline_download_unplayed)) {
				loadEpisodes { repository.getDownloadableEpisodes(seriesId, season.id, onlyUnplayed = true) }
			}
			item(context.getString(R.string.offline_open_downloads)) { openDownloads() }
		}.show()
	}

	/** Episode: download it and every following episode of its season. */
	fun showUntilSeasonEndMenu(episode: BaseItemDto) {
		if (!canDownload()) return
		val seriesId = episode.seriesId
		val seasonId = episode.seasonId
		if (seriesId == null || seasonId == null) {
			showError(IllegalStateException("Episode without season"))
			return
		}

		loadEpisodes {
			repository.getDownloadableEpisodes(seriesId, seasonId, onlyUnplayed = false, fromEpisodeId = episode.id)
		}
	}

	private fun loadEpisodes(load: suspend () -> List<BaseItemDto>) = scope.launch {
		val episodes = runCatching { load() }
			.onFailure(::showError)
			.getOrNull() ?: return@launch

		if (episodes.isEmpty()) {
			Toast.makeText(context, R.string.offline_nothing_to_download, Toast.LENGTH_LONG).show()
		} else {
			showEpisodesQualityMenu(episodes)
		}
	}

	private fun showEpisodesQualityMenu(episodes: List<BaseItemDto>) {
		popupMenu(context, anchor) {
			DownloadQuality.entries.forEach { quality ->
				val size = repository.estimateBytes(episodes, quality)
				val label = buildString {
					append(context.getString(qualityLabel(quality)))
					append(" (")
					append(context.resources.getQuantityString(R.plurals.offline_episode_count, episodes.size, episodes.size))
					if (size > 0) append(" · ~").append(Formatter.formatShortFileSize(context, size))
					append(")")
				}
				item(label) {
					scope.launch {
						runCatching { repository.enqueueEpisodes(episodes, quality) }
							.onSuccess(::showQueued)
							.onFailure(::showError)
					}
				}
			}
		}.show()
	}

	/** Movie, episode or video: pick the quality. */
	fun showQualityMenu(item: BaseItemDto) {
		if (!canDownload()) return
		val sourceSize = item.mediaSources?.firstOrNull()?.size

		popupMenu(context, anchor) {
			DownloadQuality.entries.forEach { quality ->
				val size = if (quality == DownloadQuality.ORIGINAL) sourceSize else quality.estimateBytes(item.runTimeTicks)
				val label = buildString {
					append(context.getString(qualityLabel(quality)))
					if (size != null && size > 0) append(" (~").append(Formatter.formatShortFileSize(context, size)).append(")")
				}
				item(label) {
					scope.launch {
						runCatching { repository.enqueue(item, quality) }
							.onSuccess { showQueued(1) }
							.onFailure(::showError)
					}
				}
			}
		}.show()
	}

	/** Item that is already downloaded or downloading. */
	fun showExistingMenu(offlineItem: OfflineItem, episode: BaseItemDto?) {
		popupMenu(context, anchor) {
			if (offlineItem.isCompleted) {
				item(context.getString(R.string.offline_play_download)) {
					context.startActivity(OfflinePlayerActivity.intent(context, offlineItem.id, null))
				}
			}
			if (offlineItem.status == DownloadStatus.FAILED) {
				item(context.getString(R.string.offline_retry)) { repository.retry(offlineItem.id) }
			}
			if (episode != null) {
				item(context.getString(R.string.offline_download_until_season_end)) { showUntilSeasonEndMenu(episode) }
			}
			item(context.getString(R.string.offline_open_downloads)) { openDownloads() }
			item(
				context.getString(
					if (offlineItem.isCompleted) R.string.offline_delete_download else R.string.offline_cancel_download
				)
			) { repository.delete(offlineItem.id) }
		}.show()
	}

	private fun openDownloads() = context.startActivity(OfflineActivity.intent(context))

	private fun showQueued(count: Int) {
		val message = if (count == 0) context.getString(R.string.offline_nothing_to_download)
		else context.resources.getQuantityString(R.plurals.offline_queued, count, count)
		Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
	}

	private fun showError(error: Throwable) {
		Timber.e(error, "Offline download action failed")
		val app = context.applicationContext
		val message = when (error) {
			is InsufficientSpaceException -> app.getString(
				R.string.offline_error_space,
				Formatter.formatShortFileSize(app, error.requiredBytes),
				Formatter.formatShortFileSize(app, error.availableBytes),
			)

			is StorageUnavailableException -> app.getString(R.string.offline_error_storage_unavailable)
			else -> app.getString(R.string.offline_error_generic, error.message ?: error.javaClass.simpleName)
		}
		Toast.makeText(app, message, Toast.LENGTH_LONG).show()
	}
}
