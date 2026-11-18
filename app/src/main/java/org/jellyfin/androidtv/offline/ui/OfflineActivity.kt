package org.jellyfin.androidtv.offline.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.offline.data.DownloadRepository
import org.jellyfin.androidtv.offline.data.OfflineStorage
import org.jellyfin.androidtv.offline.data.StorageSelector
import org.jellyfin.androidtv.offline.model.DownloadStatus
import org.jellyfin.androidtv.offline.model.OfflineItem
import org.jellyfin.androidtv.offline.player.OfflinePlayerActivity
import org.jellyfin.androidtv.offline.update.checkForOfflineUpdates
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.navigation.ActivityDestinations
import org.jellyfin.androidtv.util.TimeUtils
import org.koin.android.ext.android.inject

/**
 * Lists all downloads and works completely without a server connection.
 */
class OfflineActivity : FragmentActivity() {
	companion object {
		private const val EXTRA_OFFLINE_MODE = "offline_mode"

		/**
		 * @param offlineMode true when opened because the server is not reachable; shows a button
		 * to go back to the regular (online) startup flow.
		 */
		fun intent(context: Context, offlineMode: Boolean = false) = Intent(context, OfflineActivity::class.java).apply {
			putExtra(EXTRA_OFFLINE_MODE, offlineMode)
		}
	}

	private val repository by inject<DownloadRepository>()
	private val storage by inject<OfflineStorage>()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val offlineMode = intent.getBooleanExtra(EXTRA_OFFLINE_MODE, false)

		setContent {
			JellyfinTheme {
				DownloadsScreen(
					repository = repository,
					offlineMode = offlineMode,
					onItemClick = ::showItemOptions,
					onConnect = {
						startActivity(ActivityDestinations.startup(this, hideSplash = false).apply {
							putExtra(OFFLINE_SKIP_EXTRA, true)
						})
						finish()
					},
					onCheckUpdates = { checkForOfflineUpdates(manual = true) },
					onChooseStorage = ::showStorageOptions,
					formatBytes = { Formatter.formatShortFileSize(this, it) },
				)
			}
		}
	}

	override fun onResume() {
		super.onResume()
		repository.reload()
	}

	private fun showStorageOptions() {
		val options = storage.storageOptions()
		val format = { bytes: Long -> Formatter.formatShortFileSize(this, bytes) }
		val values = listOf(StorageSelector.AUTO) + options.map { it.path }
		val labels = listOf(getString(R.string.offline_storage_auto)) + options.map {
			getString(R.string.offline_storage_option, it.label, format(it.freeBytes), format(it.totalBytes))
		}
		val current = values.indexOf(storage.location.value)

		// AlertDialog can't show a message together with a list, so the warning goes in the title
		val title = getString(R.string.offline_storage_title)
		AlertDialog.Builder(this)
			.setTitle(if (current == -1) "$title\n${getString(R.string.offline_storage_missing)}" else title)
			.setSingleChoiceItems(labels.toTypedArray(), current) { dialog, which ->
				storage.setLocation(values[which])
				dialog.dismiss()
			}
			.setNegativeButton(R.string.lbl_cancel, null)
			.show()
	}

	private fun play(item: OfflineItem, fromStart: Boolean) {
		startActivity(OfflinePlayerActivity.intent(this, item.id, if (fromStart) 0 else item.positionMs))
	}

	private fun confirmDelete(item: OfflineItem) {
		AlertDialog.Builder(this)
			.setTitle(item.displayTitle)
			.setMessage(R.string.offline_delete_confirm)
			.setPositiveButton(R.string.lbl_delete) { _, _ -> repository.delete(item.id) }
			.setNegativeButton(R.string.lbl_cancel, null)
			.show()
	}

	private fun showItemOptions(item: OfflineItem) {
		val options = mutableListOf<Pair<String, () -> Unit>>()
		var message: String? = null

		when (item.status) {
			DownloadStatus.COMPLETED -> {
				if (item.positionMs > 0 && !item.played) {
					options += getString(R.string.lbl_resume_from, TimeUtils.formatMillis(item.positionMs)) to { play(item, false) }
					options += getString(R.string.lbl_from_beginning) to { play(item, true) }
				} else {
					options += getString(R.string.lbl_play) to { play(item, true) }
				}
				options += getString(R.string.offline_delete_download) to { confirmDelete(item) }
			}

			DownloadStatus.FAILED -> {
				message = item.error
				options += getString(R.string.offline_retry) to { repository.retry(item.id) }
				options += getString(R.string.offline_delete_download) to { repository.delete(item.id) }
			}

			DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING -> {
				message = item.error
				options += getString(R.string.offline_cancel_download) to { repository.delete(item.id) }
			}
		}

		val builder = AlertDialog.Builder(this).setTitle(item.displayTitle)
		if (message != null) {
			// AlertDialog can't show both a message and items, so prefix the title
			builder.setTitle("${item.displayTitle}\n$message")
		}
		builder
			.setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
			.setNegativeButton(R.string.lbl_cancel, null)
			.show()
	}
}

/** Extra for [org.jellyfin.androidtv.ui.startup.StartupActivity] to skip the automatic offline redirect. */
const val OFFLINE_SKIP_EXTRA = "offline_skip"
