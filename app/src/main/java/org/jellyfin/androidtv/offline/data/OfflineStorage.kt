package org.jellyfin.androidtv.offline.data

import android.content.Context
import android.os.StatFs
import kotlinx.serialization.json.Json
import org.jellyfin.androidtv.offline.model.OfflineItem
import timber.log.Timber
import java.io.File

/**
 * File based storage for offline items. Every item lives in its own folder:
 * `<volume>/downloads/<itemId>/{meta.json, media.<ext>, media.part, poster.jpg, backdrop.jpg, subs/}`
 *
 * App specific external storage is used so no storage permissions are required. The data is
 * removed when the app is uninstalled (but kept on updates).
 */
class OfflineStorage(private val context: Context) {
	companion object {
		private const val DOWNLOADS_DIR = "downloads"
		private const val META_FILE = "meta.json"
		const val PART_FILE = "media.part"
		const val POSTER_FILE = "poster.jpg"
		const val BACKDROP_FILE = "backdrop.jpg"
		const val SUBTITLES_DIR = "subs"

		/** Keep some space free so the device keeps working. */
		const val RESERVED_BYTES = 500L * 1024 * 1024
	}

	private val json = Json {
		ignoreUnknownKeys = true
		encodeDefaults = true
	}

	private val roots: List<File>
		get() = buildList {
			context.getExternalFilesDirs(null).filterNotNull().forEach { add(File(it, DOWNLOADS_DIR)) }
			add(File(context.filesDir, DOWNLOADS_DIR))
		}.distinct()

	/** The volume with the most free space is used for new downloads. */
	private fun bestRoot(): File = roots
		.onEach { it.mkdirs() }
		.filter { it.canWrite() }
		.maxByOrNull { freeBytes(it) }
		?: File(context.filesDir, DOWNLOADS_DIR).apply { mkdirs() }

	fun freeBytes(dir: File = bestRoot()): Long = runCatching {
		StatFs(dir.absolutePath).availableBytes
	}.getOrDefault(0L)

	fun totalBytes(dir: File = bestRoot()): Long = runCatching {
		StatFs(dir.absolutePath).totalBytes
	}.getOrDefault(0L)

	fun usableBytes(): Long = (freeBytes() - RESERVED_BYTES).coerceAtLeast(0)

	fun findItemDir(id: String): File? = roots
		.map { File(it, id) }
		.firstOrNull { File(it, META_FILE).exists() }

	fun itemDir(id: String): File = (findItemDir(id) ?: File(bestRoot(), id)).apply { mkdirs() }

	fun subtitlesDir(id: String): File = File(itemDir(id), SUBTITLES_DIR).apply { mkdirs() }

	fun mediaFile(item: OfflineItem): File? = item.mediaFileName?.let { File(itemDir(item.id), it) }

	fun partFile(id: String): File = File(itemDir(id), PART_FILE)

	fun posterFile(id: String): File = File(itemDir(id), POSTER_FILE)

	fun backdropFile(id: String): File = File(itemDir(id), BACKDROP_FILE)

	fun subtitleFile(id: String, fileName: String): File = File(subtitlesDir(id), fileName)

	@Synchronized
	fun write(item: OfflineItem) {
		val dir = itemDir(item.id)
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
