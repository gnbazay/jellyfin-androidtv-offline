package org.jellyfin.androidtv.offline.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.offline.data.DownloadRepository
import org.jellyfin.androidtv.offline.data.OfflineStorage
import org.jellyfin.androidtv.offline.data.StorageOption
import org.jellyfin.androidtv.offline.model.DownloadStatus
import org.jellyfin.androidtv.offline.model.OfflineItem
import org.jellyfin.androidtv.offline.model.OfflineItemType
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.koin.compose.koinInject
import java.io.File

private data class Section(val title: String, val items: List<OfflineItem>, val landscape: Boolean)

@Composable
fun DownloadsScreen(
	repository: DownloadRepository,
	offlineMode: Boolean,
	onItemClick: (OfflineItem) -> Unit,
	onConnect: () -> Unit,
	onCheckUpdates: () -> Unit,
	onChooseStorage: () -> Unit,
	formatBytes: (Long) -> String,
) {
	val storage = koinInject<OfflineStorage>()
	val items by repository.items.collectAsState()
	val location by storage.location.collectAsState()
	var freeBytes by remember { mutableLongStateOf(0L) }
	var usedBytes by remember { mutableLongStateOf(0L) }
	var target by remember { mutableStateOf<StorageOption?>(null) }

	LaunchedEffect(items.size, items.count { it.isCompleted }, location) {
		withContext(Dispatchers.IO) {
			target = storage.targetOption()
			freeBytes = repository.freeBytes()
			usedBytes = repository.usedBytes()
		}
	}

	val sectionInProgress = stringResource(R.string.offline_in_progress)
	val sectionContinue = stringResource(R.string.lbl_continue_watching)
	val sectionMovies = stringResource(R.string.lbl_movies)
	val sections = remember(items) {
		buildList {
			val pending = items.filter { !it.isCompleted }.sortedBy { it.addedAt }
			if (pending.isNotEmpty()) add(Section(sectionInProgress, pending, landscape = false))

			val completed = items.filter { it.isCompleted }
			val resume = completed
				.filter { it.positionMs > 0 && !it.played }
				.sortedByDescending { it.lastPlayedAt ?: 0 }
			if (resume.isNotEmpty()) add(Section(sectionContinue, resume, landscape = false))

			val movies = completed.filter { it.type != OfflineItemType.EPISODE }.sortedBy { it.name }
			if (movies.isNotEmpty()) add(Section(sectionMovies, movies, landscape = false))

			completed
				.filter { it.type == OfflineItemType.EPISODE }
				.groupBy { it.seriesName ?: "" }
				.toSortedMap()
				.forEach { (series, episodes) ->
					add(
						Section(
							title = series,
							items = episodes.sortedWith(compareBy({ it.parentIndexNumber ?: 0 }, { it.indexNumber ?: 0 })),
							landscape = true,
						)
					)
				}
		}
	}

	val firstFocus = remember { FocusRequester() }
	LaunchedEffect(sections.isNotEmpty()) {
		delay(100)
		runCatching { firstFocus.requestFocus() }
	}

	Box(
		modifier = Modifier
			.fillMaxSize()
			.background(JellyfinTheme.colorScheme.background)
	) {
		LazyColumn(
			contentPadding = PaddingValues(vertical = 27.dp),
			verticalArrangement = Arrangement.spacedBy(20.dp),
			modifier = Modifier.fillMaxSize(),
		) {
			item {
				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(12.dp),
					modifier = Modifier
						.fillMaxWidth()
						.padding(horizontal = 48.dp),
				) {
					Column(modifier = Modifier.weight(1f)) {
						Text(
							text = stringResource(R.string.offline_downloads),
							fontSize = 28.sp,
							fontWeight = FontWeight.Bold,
							color = JellyfinTheme.colorScheme.onBackground,
						)
						Text(
							text = stringResource(R.string.offline_storage_summary, formatBytes(usedBytes), formatBytes(freeBytes)),
							fontSize = 14.sp,
							color = JellyfinTheme.colorScheme.listCaption,
						)
					}
					if (offlineMode) {
						Button(onClick = onConnect) { Text(stringResource(R.string.offline_connect_server)) }
					}
					Button(onClick = onChooseStorage) {
						Text(
							target?.let { stringResource(R.string.offline_storage_button, it.label, formatBytes(it.freeBytes)) }
								?: stringResource(R.string.offline_storage_button_unavailable)
						)
					}
					Button(
						onClick = onCheckUpdates,
						modifier = if (sections.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier,
					) { Text(stringResource(R.string.offline_check_updates)) }
				}
			}

			if (sections.isEmpty()) {
				item {
					Text(
						text = stringResource(R.string.offline_empty),
						fontSize = 18.sp,
						color = JellyfinTheme.colorScheme.onBackground,
						modifier = Modifier.padding(horizontal = 48.dp, vertical = 32.dp),
					)
				}
			}

			sections.forEachIndexed { sectionIndex, section ->
				item(key = "section-${section.title}-$sectionIndex") {
					Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
						Text(
							text = section.title,
							fontSize = 20.sp,
							fontWeight = FontWeight.SemiBold,
							color = JellyfinTheme.colorScheme.onBackground,
							modifier = Modifier.padding(horizontal = 48.dp),
						)
						LazyRow(
							contentPadding = PaddingValues(horizontal = 48.dp),
							horizontalArrangement = Arrangement.spacedBy(16.dp),
						) {
							items(section.items, key = { it.id }) { item ->
								val isFirst = sectionIndex == 0 && item == section.items.first()
								OfflineCard(
									item = item,
									landscape = section.landscape || item.type == OfflineItemType.EPISODE,
									onClick = { onItemClick(item) },
									modifier = if (isFirst) Modifier.focusRequester(firstFocus) else Modifier,
								)
							}
						}
					}
				}
			}
		}
	}
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OfflineCard(
	item: OfflineItem,
	landscape: Boolean,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val storage = koinInject<OfflineStorage>()
	val interactionSource = remember { MutableInteractionSource() }
	val focused by interactionSource.collectIsFocusedAsState()
	val shape = RoundedCornerShape(8.dp)
	val width = if (landscape) 260.dp else 150.dp

	val image: File? = remember(item.id, item.hasPoster, item.hasBackdrop, landscape) {
		val backdrop = storage.findItemDir(item.id)?.let { File(it, OfflineStorage.BACKDROP_FILE) }
		val poster = storage.findItemDir(item.id)?.let { File(it, OfflineStorage.POSTER_FILE) }
		if (landscape) backdrop?.takeIf { it.exists() } ?: poster?.takeIf { it.exists() }
		else poster?.takeIf { it.exists() } ?: backdrop?.takeIf { it.exists() }
	}

	Column(
		modifier = modifier
			.width(width)
			.combinedClickable(
				interactionSource = interactionSource,
				indication = null,
				onClick = onClick,
				onLongClick = onClick,
			),
	) {
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.aspectRatio(if (landscape) 16f / 9f else 2f / 3f)
				.clip(shape)
				.background(Color(0xFF202020))
				.border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, shape),
		) {
			if (image != null) {
				AsyncImage(
					model = image,
					contentDescription = item.name,
					contentScale = ContentScale.Crop,
					modifier = Modifier.fillMaxSize(),
				)
			} else {
				Text(
					text = item.name,
					color = Color.White,
					modifier = Modifier
						.align(Alignment.Center)
						.padding(8.dp),
				)
			}

			// Download progress or watch progress
			val progress = when {
				!item.isCompleted -> item.progress
				item.positionMs > 0 && !item.played && (item.runTimeTicks ?: 0) > 0 ->
					(item.positionMs * 10_000f / item.runTimeTicks!!).coerceIn(0f, 1f)

				else -> null
			}
			if (progress != null) {
				Box(
					modifier = Modifier
						.align(Alignment.BottomStart)
						.fillMaxWidth()
						.height(6.dp)
						.background(Color(0x99000000))
				) {
					Box(
						modifier = Modifier
							.fillMaxWidth(progress)
							.height(6.dp)
							.background(if (item.isCompleted) Color(0xFFAA5CC3) else Color(0xFF00A4DC))
					)
				}
			}

			if (item.played) {
				Text(
					text = "✓",
					color = Color.White,
					fontWeight = FontWeight.Bold,
					modifier = Modifier
						.align(Alignment.TopEnd)
						.padding(6.dp)
						.background(Color(0xFF00A4DC), RoundedCornerShape(50))
						.padding(horizontal = 8.dp, vertical = 2.dp),
				)
			}
		}

		Spacer(Modifier.height(6.dp))
		Text(
			text = item.displayTitle,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			fontWeight = if (focused) FontWeight.Bold else FontWeight.Normal,
			color = JellyfinTheme.colorScheme.onBackground,
		)
		Text(
			text = statusText(item),
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			fontSize = 12.sp,
			color = if (item.status == DownloadStatus.FAILED) Color(0xFFFF6B6B) else JellyfinTheme.colorScheme.listCaption,
		)
	}
}

@Composable
private fun statusText(item: OfflineItem): String = when (item.status) {
	DownloadStatus.QUEUED -> stringResource(R.string.offline_status_queued)
	DownloadStatus.DOWNLOADING -> stringResource(R.string.offline_status_downloading, (item.progress * 100).toInt())
	DownloadStatus.FAILED -> stringResource(R.string.offline_status_failed)
	DownloadStatus.COMPLETED -> listOfNotNull(
		item.seriesName.takeIf { item.type == OfflineItemType.EPISODE },
		item.productionYear?.toString().takeIf { item.type != OfflineItemType.EPISODE },
		stringResource(qualityLabel(item.quality)),
	).joinToString(" · ")
}

fun qualityLabel(quality: org.jellyfin.androidtv.offline.model.DownloadQuality) = when (quality) {
	org.jellyfin.androidtv.offline.model.DownloadQuality.ORIGINAL -> R.string.offline_quality_original
	org.jellyfin.androidtv.offline.model.DownloadQuality.HD_1080 -> R.string.offline_quality_1080
	org.jellyfin.androidtv.offline.model.DownloadQuality.HD_720 -> R.string.offline_quality_720
	org.jellyfin.androidtv.offline.model.DownloadQuality.SD_480 -> R.string.offline_quality_480
}
