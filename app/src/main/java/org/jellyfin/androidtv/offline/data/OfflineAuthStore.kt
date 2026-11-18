package org.jellyfin.androidtv.offline.data

import android.content.Context
import androidx.core.content.edit

/**
 * Stores the authorization header per server so queued downloads can continue in the background
 * (or after a reboot) without an active UI session. Kept in private app storage.
 */
class OfflineAuthStore(context: Context) {
	private val preferences = context.getSharedPreferences("offline_auth", Context.MODE_PRIVATE)

	fun save(serverUrl: String, authorization: String, deviceId: String) = preferences.edit {
		putString("auth:$serverUrl", authorization)
		putString("device:$serverUrl", deviceId)
	}

	fun authorization(serverUrl: String): String? = preferences.getString("auth:$serverUrl", null)
	fun deviceId(serverUrl: String): String? = preferences.getString("device:$serverUrl", null)
}
