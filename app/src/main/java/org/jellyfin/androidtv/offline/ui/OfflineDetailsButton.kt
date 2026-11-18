@file:JvmName("OfflineDetailsButton")

package org.jellyfin.androidtv.offline.ui

import android.content.Context
import android.text.format.Formatter
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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
import org.jellyfin.androidtv.ui.TextUnderButton
import org.jellyfin.androidtv.util.popupMenu
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.LocationType
import org.koin.java.KoinJavaComponent.get
import timber.log.Timber

private val singleTypes = setOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE, BaseItemKind.VIDEO, BaseItemKind.MUSIC_VIDEO)
private val groupTypes = setOf(BaseItemKind.SERIES, BaseItemKind.SEASON)

/**
 * Creates the "Download" button for the item details screen, or null when the item can't be
 * downloaded. Called from FullDetailsFragment.
 */
fun createOfflineDownloadButton(fragment: Fragment, item: BaseItemDto, buttonSize: Int): TextUnderButton? {
	if (item.type !in singleTypes && item.type !in groupTypes) return null
	if (item.type in singleTypes && item.mediaSources.isNullOrEmpty() && item.locationType == LocationType.VIRTUAL) return null

	val context = fragment.requireContext()
	val id = item.id.toString()

	lateinit var button: TextUnderButton
	button = TextUnderButton.create(
		context,
		R.drawable.ic_download,
		buttonSize,
		2,
		context.getString(if (item.type in groupTypes) R.string.offline_download_season else R.string.offline_download),
	) { view ->
		val offlineItem = repository.get(id)
		when {
			item.type == BaseItemKind.SERIES -> showSeasonMenu(fragment, view, item)
			item.type == BaseItemKind.SEASON -> showEpisodeScopeMenu(fragment, view, item)
			offlineItem == null -> showQualityMenu(fragment, view, item)
			else -> showExistingMenu(fragment, view, offlineItem)
		}
	}

	if (item.type in singleTypes) {
		fragment.lifecycleScope.launch {
			fragment.repeatOnLifecycle(Lifecycle.State.STARTED) {
				repository.observe(id).collect { offlineItem ->
					when {
						offlineItem == null -> {
							button.setIcon(R.drawable.ic_download, buttonSize)
							button.setLabel(context.getString(R.string.offline_download))
							button.isActivated = false
						}

						offlineItem.isCompleted -> {
							button.setIcon(R.drawable.ic_download_done, buttonSize)
							button.setLabel(context.getString(R.string.offline_downloaded))
							button.isActivated = true
						}

						offlineItem.status == DownloadStatus.FAILED -> {
							button.setIcon(R.drawable.ic_download, buttonSize)
							button.setLabel(context.getString(R.string.offline_status_failed))
							button.isActivated = false
						}

						else -> {
							button.setIcon(R.drawable.ic_download, buttonSize)
							button.setLabel(context.getString(R.string.offline_status_downloading, (offlineItem.progress * 100).toInt()))
							button.isActivated = true
						}
					}
				}
			}
		}
	}

	return button
}

private fun canDownload(fragment: Fragment): Boolean {
	val user = get<UserRepository>(UserRepository::class.java).currentUser.value
	val allowed = user?.policy?.enableContentDownloading != false
	if (!allowed) Toast.makeText(fragment.requireContext(), R.string.offline_error_forbidden, Toast.LENGTH_LONG).show()
	return allowed
}

private val repository get() = get<DownloadRepository>(DownloadRepository::class.java)

/** Series: pick one of the seasons, downloading a complete series at once is not offered. */
private fun showSeasonMenu(fragment: Fragment, anchor: View, series: BaseItemDto) {
	if (!canDownload(fragment)) return
	val context = fragment.requireContext()

	fragment.lifecycleScope.launch {
		val seasons = runCatching { repository.getSeasons(series.id) }
			.onFailure { showError(context, it) }
			.getOrNull() ?: return@launch

		popupMenu(context, anchor) {
			seasons.forEach { season ->
				item(seasonLabel(context, season)) { showEpisodeScopeMenu(fragment, anchor, season) }
			}
			item(context.getString(R.string.offline_open_downloads)) {
				context.startActivity(OfflineActivity.intent(context))
			}
		}.show()
	}
}

private fun seasonLabel(context: Context, season: BaseItemDto): String {
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
private fun showEpisodeScopeMenu(fragment: Fragment, anchor: View, season: BaseItemDto) {
	if (!canDownload(fragment)) return
	val context = fragment.requireContext()
	val seriesId = season.seriesId ?: return

	fun load(onlyUnplayed: Boolean) = fragment.lifecycleScope.launch {
		val episodes = runCatching { repository.getDownloadableEpisodes(seriesId, season.id, onlyUnplayed) }
			.onFailure { showError(context, it) }
			.getOrNull() ?: return@launch

		if (episodes.isEmpty()) {
			Toast.makeText(context, R.string.offline_nothing_to_download, Toast.LENGTH_LONG).show()
		} else {
			showEpisodesQualityMenu(fragment, anchor, episodes)
		}
	}

	popupMenu(context, anchor) {
		item(context.getString(R.string.offline_download_all_episodes)) { load(onlyUnplayed = false) }
		item(context.getString(R.string.offline_download_unplayed)) { load(onlyUnplayed = true) }
		item(context.getString(R.string.offline_open_downloads)) {
			context.startActivity(OfflineActivity.intent(context))
		}
	}.show()
}

private fun showEpisodesQualityMenu(fragment: Fragment, anchor: View, episodes: List<BaseItemDto>) {
	val context = fragment.requireContext()
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
				fragment.lifecycleScope.launch {
					runCatching { repository.enqueueEpisodes(episodes, quality) }
						.onSuccess { count -> showQueued(context, count) }
						.onFailure { showError(context, it) }
				}
			}
		}
	}.show()
}

private fun showQualityMenu(fragment: Fragment, anchor: View, item: BaseItemDto) {
	if (!canDownload(fragment)) return
	val context = fragment.requireContext()
	val sourceSize = item.mediaSources?.firstOrNull()?.size

	popupMenu(context, anchor) {
		DownloadQuality.entries.forEach { quality ->
			val size = if (quality == DownloadQuality.ORIGINAL) sourceSize else quality.estimateBytes(item.runTimeTicks)
			val label = buildString {
				append(context.getString(qualityLabel(quality)))
				if (size != null && size > 0) append(" (~").append(Formatter.formatShortFileSize(context, size)).append(")")
			}
			item(label) {
				fragment.lifecycleScope.launch {
					runCatching { repository.enqueue(item, quality) }
						.onSuccess { showQueued(context, 1) }
						.onFailure { showError(context, it) }
				}
			}
		}
	}.show()
}

private fun showQueued(context: Context, count: Int) {
	val message = if (count == 0) context.getString(R.string.offline_nothing_to_download)
	else context.resources.getQuantityString(R.plurals.offline_queued, count, count)
	Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
}

private fun showError(context: Context, error: Throwable) {
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

private fun showExistingMenu(fragment: Fragment, anchor: View, offlineItem: OfflineItem) {
	val context = fragment.requireContext()
	popupMenu(context, anchor) {
		if (offlineItem.isCompleted) {
			item(context.getString(R.string.offline_play_download)) {
				context.startActivity(OfflinePlayerActivity.intent(context, offlineItem.id, null))
			}
		}
		if (offlineItem.status == DownloadStatus.FAILED) {
			item(context.getString(R.string.offline_retry)) { repository.retry(offlineItem.id) }
		}
		item(context.getString(R.string.offline_open_downloads)) {
			context.startActivity(OfflineActivity.intent(context))
		}
		item(
			context.getString(
				if (offlineItem.isCompleted) R.string.offline_delete_download else R.string.offline_cancel_download
			)
		) { repository.delete(offlineItem.id) }
	}.show()
}
