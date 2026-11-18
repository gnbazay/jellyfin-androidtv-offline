package org.jellyfin.androidtv.offline.data

/**
 * Pure selection logic for the download location so it can be unit tested.
 */
object StorageSelector {
	const val AUTO = "auto"

	data class Volume(
		val path: String,
		val removable: Boolean,
		val freeBytes: Long,
	)

	/**
	 * @param preference [AUTO] or the path of a volume chosen by the user
	 * @param mounted the volumes that are currently available
	 * @return the volume to save new downloads to, or null when the chosen volume isn't available
	 */
	fun select(preference: String, mounted: List<Volume>): Volume? = when (preference) {
		// Prefer removable storage (SD card / USB) and only fall back to internal storage without one
		AUTO -> mounted.filter { it.removable }.maxByOrNull { it.freeBytes }
			?: mounted.firstOrNull { !it.removable }
			?: mounted.firstOrNull()

		// Never silently fall back to another volume when the user picked one
		else -> mounted.firstOrNull { it.path == preference }
	}
}
