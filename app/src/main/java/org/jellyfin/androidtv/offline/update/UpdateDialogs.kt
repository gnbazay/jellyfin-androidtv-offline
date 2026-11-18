package org.jellyfin.androidtv.offline.update

import android.app.AlertDialog
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.BuildConfig
import org.jellyfin.androidtv.R
import org.koin.android.ext.android.inject
import timber.log.Timber

/**
 * Check for a new release and offer to install it.
 * @param manual true when started by the user (shows feedback when there is no update)
 */
fun FragmentActivity.checkForOfflineUpdates(manual: Boolean) {
	val checker by inject<UpdateChecker>()
	// Debug builds are a different app, never offer them the release automatically
	if (!manual && (BuildConfig.DEBUG || !checker.shouldAutoCheck())) return

	lifecycleScope.launch {
		val update = runCatching { checker.check() }
			.onFailure { Timber.w(it, "Update check failed") }
			.getOrElse {
				if (manual) Toast.makeText(this@checkForOfflineUpdates, R.string.offline_update_check_failed, Toast.LENGTH_LONG).show()
				return@launch
			}

		if (update == null) {
			if (manual) Toast.makeText(
				this@checkForOfflineUpdates,
				getString(R.string.offline_update_none, BuildConfig.VERSION_NAME),
				Toast.LENGTH_LONG
			).show()
			return@launch
		}

		if (isFinishing || isDestroyed) return@launch
		AlertDialog.Builder(this@checkForOfflineUpdates)
			.setTitle(getString(R.string.offline_update_available, update.tag))
			.setMessage(update.notes?.take(1_000).orEmpty().ifBlank { getString(R.string.offline_update_message) })
			.setPositiveButton(R.string.offline_update_install) { _, _ -> downloadAndInstall(checker, update) }
			.setNegativeButton(R.string.offline_later, null)
			.show()
	}
}

private fun FragmentActivity.downloadAndInstall(checker: UpdateChecker, update: AvailableUpdate) {
	if (checker.needsInstallPermission()) {
		Toast.makeText(this, R.string.offline_update_permission, Toast.LENGTH_LONG).show()
		checker.openInstallPermissionSettings(this)
		return
	}

	val dialog = AlertDialog.Builder(this)
		.setTitle(R.string.offline_update_downloading)
		.setMessage("0 %")
		.setCancelable(false)
		.show()

	lifecycleScope.launch {
		val apk = runCatching {
			checker.download(update) { percent ->
				lifecycleScope.launch(Dispatchers.Main) { dialog.setMessage("$percent %") }
			}
		}.onFailure { Timber.e(it, "Update download failed") }.getOrNull()

		withContext(Dispatchers.Main) {
			dialog.dismiss()
			if (apk == null) {
				Toast.makeText(this@downloadAndInstall, R.string.offline_update_check_failed, Toast.LENGTH_LONG).show()
			} else {
				checker.install(this@downloadAndInstall, apk)
			}
		}
	}
}
