package org.jellyfin.androidtv.offline

import androidx.fragment.app.FragmentActivity
import androidx.work.WorkManager
import org.jellyfin.androidtv.offline.data.DownloadRepository
import org.jellyfin.androidtv.offline.download.OfflineSyncWorker
import org.jellyfin.androidtv.offline.model.DownloadStatus
import org.jellyfin.androidtv.offline.update.checkForOfflineUpdates
import org.koin.android.ext.android.get

/**
 * Called when the main (online) activity resumes: syncs offline watch progress, continues
 * interrupted downloads and checks for app updates.
 */
fun FragmentActivity.onOfflineMainResume() {
	val repository = get<DownloadRepository>()
	val items = repository.items.value

	if (items.any { it.pendingSync }) OfflineSyncWorker.enqueue(get<WorkManager>())
	if (items.any { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING }) {
		repository.startQueue()
	}

	checkForOfflineUpdates(manual = false)
}
