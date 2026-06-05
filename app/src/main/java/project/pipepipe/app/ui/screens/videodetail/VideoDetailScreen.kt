package project.pipepipe.app.ui.screens.videodetail

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.info_list.CommonItem
import org.schabi.newpipe.info_list.buildInfoItemState
import org.schabi.newpipe.util.Localization
import project.pipepipe.app.SharedContext
import project.pipepipe.app.mediasource.MediaItemFactory
import project.pipepipe.app.uistate.VideoDetailPageState
import project.pipepipe.app.viewmodel.VideoDetailViewModel

@UnstableApi
@Composable
fun VideoDetailScreen(viewModel: VideoDetailViewModel) {
    val state by viewModel.uiState.collectAsState()
    val controller by SharedContext.mediaController.collectAsState()
    val density = LocalDensity.current
    val context = LocalContext.current
    var dragDistance by remember { mutableFloatStateOf(0f) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val offset by animateDpAsState(
            if (state.pageState == VideoDetailPageState.BOTTOM_PLAYER) maxHeight - 64.dp else 0.dp,
            label = "detailOffset"
        )
        when {
            state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            state.error != null -> Text(
                state.error?.message ?: state.error.toString(),
                Modifier.align(Alignment.Center).padding(24.dp)
            )
            state.currentStreamInfo != null -> {
                val streamInfo = state.currentStreamInfo!!
                Column(
                    Modifier
                        .fillMaxSize()
                        .offset(y = offset)
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .background(androidx.compose.ui.graphics.Color.Black)
                            .pointerInput(Unit) {
                                detectVerticalDragGestures(
                                    onDragEnd = {
                                        when {
                                            dragDistance > with(density) { 100.dp.toPx() } ->
                                                viewModel.showBottomPlayer()
                                            dragDistance < -with(density) { 50.dp.toPx() } ->
                                                viewModel.showFullscreen()
                                        }
                                        dragDistance = 0f
                                    },
                                    onDragCancel = { dragDistance = 0f }
                                ) { change, amount ->
                                    change.consume()
                                    dragDistance += amount
                                }
                            }
                    ) {
                        controller?.let {
                            AndroidView(
                                factory = { context ->
                                    PlayerView(context).apply {
                                        useController = false
                                        player = it.nativePlayer as Player
                                    }
                                },
                                update = { view -> view.player = it.nativePlayer as Player },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        Row(
                            Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            IconButton(onClick = {
                                val playbackController = controller ?: return@IconButton
                                if (playbackController.currentMediaItem.value?.mediaId != streamInfo.url) {
                                    val item = MediaItemFactory.fromStreamInfo(streamInfo)
                                    SharedContext.queueManager.setQueue(listOf(item))
                                    playbackController.prepare()
                                }
                                if (playbackController.isPlaying.value) {
                                    playbackController.pause()
                                } else {
                                    playbackController.play()
                                }
                            }) {
                                Icon(
                                    if (controller?.isPlaying?.collectAsState()?.value == true) {
                                        Icons.Default.Pause
                                    } else {
                                        Icons.Default.PlayArrow
                                    },
                                    null
                                )
                            }
                            IconButton(onClick = viewModel::showFullscreen) {
                                Icon(Icons.Default.Fullscreen, null)
                            }
                            IconButton(onClick = viewModel::showBottomPlayer) {
                                Icon(Icons.Default.KeyboardArrowDown, null)
                            }
                        }
                    }
                    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        item {
                            Text(
                                streamInfo.name,
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.padding(16.dp)
                            )
                            Text(
                                streamInfo.uploaderName ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                            if (streamInfo.viewCount >= 0) {
                                Text(
                                    Localization.shortViewCount(context, streamInfo.viewCount),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            }
                            streamInfo.description?.content?.takeIf(String::isNotBlank)?.let {
                                Text(
                                    stringResource(R.string.description_tab_description),
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(16.dp)
                                )
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                            }
                        }
                        val relatedItems = streamInfo.relatedItems.filterIsInstance<StreamInfoItem>()
                        if (relatedItems.isNotEmpty()) {
                            item {
                                Text(
                                    stringResource(R.string.related_items_tab_description),
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                            items(relatedItems) { item ->
                                buildInfoItemState(context, item, null)?.let { itemState ->
                                    CommonItem(
                                        state = itemState,
                                        isGridLayout = false,
                                        isCardLayout = false,
                                        showDragHandle = false,
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = { viewModel.open(item.serviceId, item.url) }
                                    )
                                }
                            }
                        }
                    }
                }
                if (state.pageState == VideoDetailPageState.BOTTOM_PLAYER) {
                    Row(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(64.dp)
                            .background(MaterialTheme.colorScheme.surface)
                            .clickable(onClick = viewModel::showDetail),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(streamInfo.name, Modifier.weight(1f).padding(16.dp), maxLines = 1)
                        IconButton(onClick = viewModel::hide) {
                            Icon(Icons.Default.Close, null)
                        }
                    }
                }
            }
        }
    }
}
