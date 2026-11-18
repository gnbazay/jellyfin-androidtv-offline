package org.jellyfin.androidtv.offline.data

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import androidx.core.content.edit
import androidx.core.content.getSystemService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.offline.model.OfflineItem
import timber.log.Timber
import java.io.File
import java.io.IOException

data class StorageOption(
	/** Path of the app folder on the volume, used as preference value. */
	val path: String,
	val root: File,
	val label: String,
	val removable: Boolean,
	val freeBytes: Long,
	val totalBytes: Long,
)

/**
 * File based storage for offline items. Every item lives in its own folder:
 * `<volume>/downloads/<itemId>/{meta.json, media.<ext>, media.part, poster.jpg, backdrop.jpg, subs/}`
 *
 * App specific external storage is used so no storage permissions are required. The data is
 * removed when the app is uninstalled (but kept on updates). By default removable storage (SD
 * card / USB) is preferred, the user can choose a specific volume in the downloads screen.
 */
class OfflineStorage(private val context: Context) {
	companion object {
		private const val DOWNLOADS_DIR = "downloads"
		private const val META_FILE = "meta.json"
		const val PART_FILE = "media.part"
		const val POSTER_FILE = "poster.jpg"
		const val BACKDROP_FILE = "backdrop.jpg"
		const val SUBTITLES_DIR = "subs"

		/** Keep some space free on internal storage so the device keeps working. */
		const val RESERVED_BYTES = 500L * 1024 * 1024
		const val RESERVED_BYTES_REMOVABLE = 50L * 1024 * 1024

		private const val KEY_LOCATION = "storage_location"
	}

	private val json = Json {
		ignoreUnknownKeys = true
		encodeDefaults = true
	}

	private val preferences = context.getSharedPreferences("offline_settings", Context.MODE_PRIVATE)
	private val _location = MutableStateFlow(preferences.getString(KEY_LOCATION, null) ?: StorageSelector.AUTO)

	/** [StorageSelector.AUTO] or the path of the volume chosen by the user. */
	val location: StateFlow<String> = _location.asStateFlow()

	fun setLocation(value: String) {
		preferences.edit { putString(KEY_LOCATION, value) }
		_location.value = value
	}

	/** Every location that may contain downloads, mounted or not readable ones are skipped. */
	private val roots: List<File>
		get() = buildList {
			context.getExternalFilesDirs(null).filterNotNull().forEach { add(File(it, DOWNLOADS_DIR)) }
			// Legacy location, only used for reading
			add(File(context.filesDir, DOWNLOADS_DIR))
		}.distinct()

	/** Mounted external volumes that can store downloads. */
	fun storageOptions(): List<StorageOption> {
		val storageManager = context.getSystemService<StorageManager>()
		return context.getExternalFilesDirs(null)
			.filterNotNull()
			.filter { Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED }
			.mapIndexed { index, dir ->
				val volume = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) storageManager?.getStorageVolume(dir) else null
				val removable = volume?.isRemovable ?: runCatching { Environment.isExternalStorageRemovable(dir) }.getOrDefault(index > 0)
				val description = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) volume?.getDescription(context) else null
				StorageOption(
					path = dir.absolutePath,
					root = File(dir, DOWNLOADS_DIR),
					label = when {
						!removable -> context.getString(R.string.offline_storage_internal)
						!description.isNullOrBlank() -> description
						else -> context.getString(R.string.offline_storage_removable)
					},
					removable = removable,
					freeBytes = runCatching { StatFs(dir.absolutePath).availableBytes }.getOrDefault(0L),
					totalBytes = runCatching { StatFs(dir.absolutePath).totalBytes }.getOrDefault(0L),
				)
			}
	}

	/** The volume new downloads are saved to, or null when the chosen volume isn't available. */
	fun targetOption(): StorageOption? {
		val options = storageOptions()
		val selected = StorageSelector.select(
			preference = location.value,
			mounted = options.map { StorageSelector.Volume(it.path, it.removable, it.freeBytes) },
		) ?: return null
		return options.first { it.path == selected.path }
	}

	private fun targetRoot(): File? = targetOption()?.root?.apply { mkdirs() }

	fun freeBytes(): Long = targetOption()?.freeBytes ?: 0L

	fun usableBytes(): Long {
		val target = targetOption() ?: return 0
		val reserved = if (target.removable) RESERVED_BYTES_REMOVABLE else RESERVED_BYTES
		return (target.freeBytes - reserved).coerceAtLeast(0)
	}

	fun findItemDir(id: String): File? = roots
		.map { File(it, id) }
		.firstOrNull { File(it, META_FILE).exists() }

	/** Folder of an existing download. Throws when its volume (e.g. the SD card) isn't available. */
	fun itemDir(id: String): File = findItemDir(id) ?: throw IOException("Storage of offline item $id is not available")

	fun subtitlesDir(id: String): File = File(itemDir(id), SUBTITLES_DIR).apply { mkdirs() }

	fun mediaFile(item: OfflineItem): File? {
		val dir = findItemDir(item.id) ?: return null
		return item.mediaFileName?.let { File(dir, it) }
	}

	fun partFile(id: String): File = File(itemDir(id), PART_FILE)

	fun posterFile(id: String): File = File(itemDir(id), POSTER_FILE)

	fun backdropFile(id: String): File = File(itemDir(id), BACKDROP_FILE)

	fun subtitleFile(id: String, fileName: String): File = File(subtitlesDir(id), fileName)

	/**
	 * Write the metadata of an item. New items ([create]) are stored on the selected volume.
	 * @throws IOException when the volume is not available
	 */
	@Synchronized
	fun write(item: OfflineItem, create: Boolean = false) {
		val dir = findItemDir(item.id)
			?: if (create) File(targetRoot() ?: throw IOException("Download location not available"), item.id).apply { mkdirs() }
			else throw IOException("Storage of offline item ${item.id} is not available")
		val tmp = File(dir, "$META_FILE.tmp")
		tmp.writeText(json.encodeToString(OfflineItem.serializer(), item))
		if (!tmp.renameTo(File(dir, META_FILE))) {
			File(dir, META_FILE).writeText(tmp.readText())
			tmp.delete()
		}
	}

	@Synchronized
	fun readAll(): List<OfflineItem> = roots
		.flatMap { root -> root.listFiles()?.toList().orEmpty() }
		.mapNotNull { dir ->
			val meta = File(dir, META_FILE)
			if (!meta.exists()) return@mapNotNull null
			runCatching { json.decodeFromString(OfflineItem.serializer(), meta.readText()) }
				.onFailure { Timber.w(it, "Unable to read offline metadata in $dir") }
				.getOrNull()
		}

	@Synchronized
	fun delete(id: String) {
		roots.map { File(it, id) }.filter { it.exists() }.forEach { it.deleteRecursively() }
	}

	/** Bytes used on disk by all downloads. */
	fun usedBytes(): Long = roots.sumOf { root ->
		root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
	}
}
