package org.jellyfin.androidtv.offline.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService
import org.jellyfin.androidtv.offline.data.DownloadRepository
import org.koin.java.KoinJavaComponent.get

fun hasOfflineDownloads(): Boolean {
	val repository = get<DownloadRepository>(DownloadRepository::class.java)
	if (repository.items.value.isEmpty()) repository.reload()
	return repository.items.value.any { it.isCompleted }
}

/** True when there is no usable network but there are downloads to watch. */
fun shouldOpenOfflineMode(context: Context): Boolean {
	val connectivityManager = context.getSystemService<ConnectivityManager>() ?: return false
	val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
	val online = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
	return !online && hasOfflineDownloads()
}
