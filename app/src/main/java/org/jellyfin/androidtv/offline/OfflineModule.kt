package org.jellyfin.androidtv.offline

import org.jellyfin.androidtv.offline.data.DownloadRepository
import org.jellyfin.androidtv.offline.data.OfflineAuthStore
import org.jellyfin.androidtv.offline.data.OfflineStorage
import org.jellyfin.androidtv.offline.update.UpdateChecker
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val offlineModule = module {
	single { OfflineStorage(androidContext()) }
	single { OfflineAuthStore(androidContext()) }
	single { DownloadRepository(get(), get(), get(), get(), get()) }
	single { UpdateChecker(androidContext()) }
}
