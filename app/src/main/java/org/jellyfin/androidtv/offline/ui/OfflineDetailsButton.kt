@file:JvmName("OfflineDetailsButton")

package org.jellyfin.androidtv.offline.ui

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

	val repository = get<DownloadRepository>(DownloadRepository::class.java)
	val context = fragment.requireContext()
	val id = item.id.toString()

	lateinit var button: TextUnderButton
	button = TextUnderButton.create(
		context,
		R.drawable.ic_download,
		buttonSize,
		2,
		context.getString(if (item.type in groupTypes) R.string.offline_download_all else R.string.offline_download),
	) { view ->
		val offlineItem = repository.get(id)
		when {
			item.type in groupTypes -> showGroupMenu(fragment, view, item)
			offlineItem == null -> showQualityMenu(fragment, view, item, onlyUnplayed = false)
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

private fun showGroupMenu(fragment: Fragment, anchor: View, item: BaseItemDto) {
	if (!canDownload(fragment)) return
	val context = fragment.requireContext()
	popupMenu(context, anchor) {
		item(context.getString(R.string.offline_download_unplayed)) {
			showQualityMenu(fragment, anchor, item, onlyUnplayed = true)
		}
		item(context.getString(R.string.offline_download_all_episodes)) {
			showQualityMenu(fragment, anchor, item, onlyUnplayed = false)
		}
		item(context.getString(R.string.offline_open_downloads)) {
			context.startActivity(OfflineActivity.intent(context))
		}
	}.show()
}

private fun showQualityMenu(fragment: Fragment, anchor: View, item: BaseItemDto, onlyUnplayed: Boolean) {
	if (!canDownload(fragment)) return
	val context = fragment.requireContext()
	val sourceSize = item.mediaSources?.firstOrNull()?.size

	popupMenu(context, anchor) {
		DownloadQuality.entries.forEach { quality ->
			val size = if (quality == DownloadQuality.ORIGINAL) sourceSize else quality.estimateBytes(item.runTimeTicks)
			val label = buildString {
				append(context.getString(qualityLabel(quality)))
				if (size != null && size > 0 && item.type in singleTypes) {
					append(" (~").append(Formatter.formatShortFileSize(context, size)).append(")")
				}
			}
			item(label) { startDownload(fragment, item, quality, onlyUnplayed) }
		}
	}.show()
}

private fun startDownload(fragment: Fragment, item: BaseItemDto, quality: DownloadQuality, onlyUnplayed: Boolean) {
	val repository = get<DownloadRepository>(DownloadRepository::class.java)
	val context = fragment.requireContext().applicationContext

	fragment.lifecycleScope.launch {
		runCatching { repository.enqueue(item, quality, onlyUnplayed) }
			.onSuccess { count ->
				val message = if (count == 0) context.getString(R.string.offline_nothing_to_download)
				else context.resources.getQuantityString(R.plurals.offline_queued, count, count)
				Toast.makeText(context, message, Toast.LENGTH_LONG).show()
			}
			.onFailure { error ->
				Timber.e(error, "Unable to queue download")
				val message = when (error) {
					is InsufficientSpaceException -> context.getString(
						R.string.offline_error_space,
						Formatter.formatShortFileSize(context, error.requiredBytes),
						Formatter.formatShortFileSize(context, error.availableBytes),
					)

					else -> context.getString(R.string.offline_error_generic, error.message ?: error.javaClass.simpleName)
				}
				Toast.makeText(context, message, Toast.LENGTH_LONG).show()
			}
	}
}

private fun showExistingMenu(fragment: Fragment, anchor: View, offlineItem: OfflineItem) {
	val repository = get<DownloadRepository>(DownloadRepository::class.java)
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
