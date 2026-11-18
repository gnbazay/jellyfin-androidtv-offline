@file:JvmName("OfflineItemMenu")

package org.jellyfin.androidtv.offline.ui

import android.view.Menu
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.offline.data.DownloadRepository
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.java.KoinJavaComponent.get

// Show the download entries after the regular entries of the menu
private const val ORDER_START = 100

/**
 * Adds the download entries to the long press menu of an item card (KeyProcessor.createItemMenu).
 */
fun addOfflineMenuItems(menu: Menu, item: BaseItemDto, activity: FragmentActivity) {
	val repository = get<DownloadRepository>(DownloadRepository::class.java)
	val anchor = activity.currentFocus ?: activity.window.decorView
	val menus = { OfflineDownloadMenus(activity, activity.lifecycleScope, anchor) }

	var order = ORDER_START
	fun item(title: String, onClick: () -> Unit) {
		menu.add(Menu.NONE, Menu.NONE, order++, title).setOnMenuItemClickListener {
			onClick()
			true
		}
	}

	run {
		when (item.type) {
			BaseItemKind.EPISODE -> {
				if (repository.get(item.id) == null) {
					item(activity.getString(R.string.offline_download_episode)) { menus().showQualityMenu(item) }
				}
				item(activity.getString(R.string.offline_download_until_season_end)) { menus().showUntilSeasonEndMenu(item) }
			}

			BaseItemKind.MOVIE, BaseItemKind.VIDEO -> if (repository.get(item.id) == null) {
				item(activity.getString(R.string.offline_download)) { menus().showQualityMenu(item) }
			}

			BaseItemKind.SEASON -> item(activity.getString(R.string.offline_download_season)) { menus().showEpisodeScopeMenu(item) }
			BaseItemKind.SERIES -> item(activity.getString(R.string.offline_download_season)) { menus().showSeasonMenu(item) }
			else -> Unit
		}
	}
}
