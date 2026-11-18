package org.jellyfin.androidtv.offline.player

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.offline.data.DownloadRepository
import org.jellyfin.androidtv.offline.data.OfflineStorage
import org.jellyfin.androidtv.offline.model.OfflineItem
import org.jellyfin.androidtv.offline.model.OfflineItemType
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.util.Locale

/**
 * Minimal, server independent video player for downloaded items.
 */
@OptIn(UnstableApi::class)
class OfflinePlayerActivity : FragmentActivity() {
	companion object {
		private const val EXTRA_ITEM_ID = "item_id"
		private const val EXTRA_START_MS = "start_ms"
		private const val SAVE_INTERVAL_MS = 10_000L

		fun intent(context: Context, itemId: String, startPositionMs: Long?) =
			Intent(context, OfflinePlayerActivity::class.java).apply {
				putExtra(EXTRA_ITEM_ID, itemId)
				putExtra(EXTRA_START_MS, startPositionMs ?: -1L)
			}
	}

	private val repository by inject<DownloadRepository>()
	private val storage by inject<OfflineStorage>()

	private lateinit var playerView: PlayerView
	private var player: ExoPlayer? = null
	private var item: OfflineItem? = null
	private var saveJob: Job? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

		playerView = PlayerView(this).apply {
			setBackgroundColor(Color.BLACK)
			setShowSubtitleButton(true)
			setShowNextButton(false)
			setShowPreviousButton(false)
			setShowShuffleButton(false)
			controllerAutoShow = true
			controllerShowTimeoutMs = 4_000
			keepScreenOn = true
		}
		setContentView(playerView)

		val itemId = intent.getStringExtra(EXTRA_ITEM_ID)
		val item = itemId?.let { repository.get(it) } ?: run {
			repository.reload()
			itemId?.let { repository.get(it) }
		}
		val file = item?.let { storage.mediaFile(it) }
		if (item == null || file == null || !file.exists()) {
			Toast.makeText(this, R.string.offline_error_missing_file, Toast.LENGTH_LONG).show()
			finish()
			return
		}
		this.item = item

		val startMs = intent.getLongExtra(EXTRA_START_MS, -1L).takeIf { it >= 0 } ?: item.positionMs
		initializePlayer(item, Uri.fromFile(file), startMs)
	}

	private fun initializePlayer(item: OfflineItem, uri: Uri, startMs: Long) {
		val renderersFactory = DefaultRenderersFactory(this).apply {
			setEnableDecoderFallback(true)
			// Use FFmpeg for audio formats the device can't decode (DTS, TrueHD, ...)
			setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
		}

		val trackSelector = DefaultTrackSelector(this).apply {
			val language = Locale.getDefault().language
			parameters = buildUponParameters()
				.setPreferredAudioLanguage(language)
				.setPreferredTextLanguage(language)
				.build()
		}

		val subtitleConfigurations = item.subtitles.mapNotNull { subtitle ->
			val file = storage.subtitleFile(item.id, subtitle.fileName)
			if (!file.exists()) return@mapNotNull null
			val mimeType = when (subtitle.codec) {
				"ass", "ssa" -> MimeTypes.TEXT_SSA
				"vtt" -> MimeTypes.TEXT_VTT
				else -> MimeTypes.APPLICATION_SUBRIP
			}
			MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file))
				.setMimeType(mimeType)
				.setLanguage(subtitle.language)
				.setLabel(subtitle.title ?: subtitle.language)
				.setSelectionFlags(
					(if (subtitle.isDefault) C.SELECTION_FLAG_DEFAULT else 0) or
						(if (subtitle.isForced) C.SELECTION_FLAG_FORCED else 0)
				)
				.build()
		}

		val mediaItem = MediaItem.Builder()
			.setUri(uri)
			.setMediaId(item.id)
			.setSubtitleConfigurations(subtitleConfigurations)
			.setMediaMetadata(
				MediaMetadata.Builder()
					.setTitle(item.displayTitle)
					.setArtist(item.seriesName)
					.build()
			)
			.build()

		val player = ExoPlayer.Builder(this, renderersFactory)
			.setTrackSelector(trackSelector)
			.setSeekBackIncrementMs(10_000)
			.setSeekForwardIncrementMs(30_000)
			.build()
		this.player = player
		playerView.player = player

		player.addListener(object : Player.Listener {
			override fun onPlaybackStateChanged(playbackState: Int) {
				if (playbackState == Player.STATE_ENDED) onPlaybackEnded()
			}

			override fun onIsPlayingChanged(isPlaying: Boolean) {
				if (!isPlaying) savePosition()
			}

			override fun onPlayerError(error: PlaybackException) {
				Timber.e(error, "Offline playback failed")
				Toast.makeText(
					this@OfflinePlayerActivity,
					getString(R.string.offline_error_playback, error.errorCodeName),
					Toast.LENGTH_LONG
				).show()
			}
		})

		player.setMediaItem(mediaItem, startMs.coerceAtLeast(0))
		player.prepare()
		player.playWhenReady = true

		saveJob = lifecycleScope.launch {
			while (isActive) {
				delay(SAVE_INTERVAL_MS)
				if (player.isPlaying) savePosition()
			}
		}
	}

	private fun savePosition() {
		val player = player ?: return
		val item = item ?: return
		val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
		repository.savePlaybackPosition(item.id, player.currentPosition, duration)
	}

	private fun onPlaybackEnded() {
		val current = item ?: return
		repository.savePlaybackPosition(current.id, Long.MAX_VALUE / 2, 1)

		// Continue with the next downloaded episode of the same series
		val next = if (current.type == OfflineItemType.EPISODE) {
			repository.items.value
				.filter { it.isCompleted && it.seriesId == current.seriesId }
				.sortedWith(compareBy({ it.parentIndexNumber ?: 0 }, { it.indexNumber ?: 0 }))
				.firstOrNull {
					val season = it.parentIndexNumber ?: 0
					val episode = it.indexNumber ?: 0
					val currentSeason = current.parentIndexNumber ?: 0
					val currentEpisode = current.indexNumber ?: 0
					season > currentSeason || (season == currentSeason && episode > currentEpisode)
				}
		} else null

		if (next != null) {
			startActivity(intent(this, next.id, 0))
		}
		finish()
	}

	override fun dispatchKeyEvent(event: KeyEvent): Boolean {
		if (event.keyCode == KeyEvent.KEYCODE_BACK && playerView.isControllerFullyVisible) {
			if (event.action == KeyEvent.ACTION_UP) playerView.hideController()
			return true
		}
		if (event.keyCode != KeyEvent.KEYCODE_BACK && !playerView.isControllerFullyVisible) {
			// Quick seek without opening the controls
			if (event.action == KeyEvent.ACTION_DOWN) when (event.keyCode) {
				KeyEvent.KEYCODE_DPAD_LEFT -> { player?.seekBack(); playerView.showController(); return true }
				KeyEvent.KEYCODE_DPAD_RIGHT -> { player?.seekForward(); playerView.showController(); return true }
			}
		}
		return playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
	}

	override fun onPause() {
		super.onPause()
		savePosition()
		player?.pause()
	}

	override fun onDestroy() {
		saveJob?.cancel()
		savePosition()
		player?.release()
		player = null
		super.onDestroy()
	}
}
