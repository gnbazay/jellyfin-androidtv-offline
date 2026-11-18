@file:JvmName("OfflineDetailsButton")

package org.jellyfin.androidtv.offline.ui

import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.offline.data.DownloadRepository
import org.jellyfin.androidtv.offline.model.DownloadStatus
import org.jellyfin.androidtv.ui.TextUnderButton
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.LocationType
import org.koin.java.KoinJavaComponent.get

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
		val menus = OfflineDownloadMenus(context, fragment.lifecycleScope, view)
		val offlineItem = repository.get(id)
		when {
			item.type == BaseItemKind.SERIES -> menus.showSeasonMenu(item)
			item.type == BaseItemKind.SEASON -> menus.showEpisodeScopeMenu(item)
			offlineItem == null -> menus.showQualityMenu(item)
			else -> menus.showExistingMenu(offlineItem, item.takeIf { it.type == BaseItemKind.EPISODE })
		}
	}

	// Long press on an episode: download it and the rest of its season
	if (item.type == BaseItemKind.EPISODE) {
		button.setOnLongClickListener { view ->
			OfflineDownloadMenus(context, fragment.lifecycleScope, view).showUntilSeasonEndMenu(item)
			true
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

private val repository get() = get<DownloadRepository>(DownloadRepository::class.java)
