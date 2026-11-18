package org.jellyfin.androidtv.offline.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.offline.data.DownloadRepository
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.UpdateUserItemDataDto
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Pushes the playback progress of offline viewing back to the server.
 */
class OfflineSyncWorker(
	context: Context,
	params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {
	companion object {
		private const val WORK_NAME = "offline-sync"

		fun enqueue(workManager: WorkManager) {
			val request = OneTimeWorkRequestBuilder<OfflineSyncWorker>()
				.setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
				.build()
			workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
		}
	}

	private val repository by inject<DownloadRepository>()
	private val userRepository by inject<UserRepository>()
	private val api by inject<ApiClient>()

	override suspend fun doWork(): Result {
		val userId = userRepository.currentUser.value?.id ?: return Result.success()
		val baseUrl = api.baseUrl?.trimEnd('/') ?: return Result.success()

		val pending = repository.items.value.filter {
			it.pendingSync && it.userId == userId.toString() && it.serverUrl == baseUrl
		}

		for (item in pending) {
			val itemId = item.id.toUUIDOrNull() ?: continue
			runCatching {
				api.itemsApi.updateItemUserData(
					itemId = itemId,
					userId = userId,
					data = UpdateUserItemDataDto(
						playbackPositionTicks = item.positionMs * 10_000,
						played = item.played,
						lastPlayedDate = item.lastPlayedAt?.let {
							LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneOffset.UTC)
						},
					),
				)
			}.onSuccess {
				repository.markSynced(item.id)
				Timber.i("Synced offline progress of ${item.id}")
			}.onFailure {
				Timber.w(it, "Unable to sync offline progress of ${item.id}")
				return Result.retry()
			}
		}

		return Result.success()
	}
}
