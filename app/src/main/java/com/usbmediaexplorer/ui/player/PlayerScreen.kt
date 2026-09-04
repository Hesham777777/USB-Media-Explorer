package com.usbmediaexplorer.ui.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.usbmediaexplorer.R
import com.usbmediaexplorer.data.settings.AspectMode
import com.usbmediaexplorer.ui.common.LocalAppContainer
import com.usbmediaexplorer.ui.nav.LocalNavigator
import com.usbmediaexplorer.ui.common.LocalSettings
import com.usbmediaexplorer.ui.common.OptionDialog
import com.usbmediaexplorer.ui.common.ResumeDialog
import com.usbmediaexplorer.ui.common.viewModelFactory
import com.usbmediaexplorer.util.Formatters
import kotlinx.coroutines.delay

/**
 * Video player screen (spec §9–§11).
 *
 * Media3 renders into a [PlayerView] while every control is Compose/Material 3, which keeps the
 * overlay consistent with the rest of the app and lets us add the drive-specific bits the stock
 * controller does not have: next/previous inside the same USB folder, external subtitle loading,
 * screen lock and aspect-ratio cycling.
 */
@Composable
fun PlayerScreen(uri: String, folderUri: String) {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val settings = LocalSettings.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val viewModel: PlayerViewModel = viewModel(
        key = "player-$uri",
        factory = viewModelFactory { PlayerViewModel(container, uri, folderUri) },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    var showSpeedDialog by remember { mutableStateOf(false) }
    var showAspectDialog by remember { mutableStateOf(false) }
    var showAudioDialog by remember { mutableStateOf(false) }
    var showSubtitleDialog by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableFloatStateOf(0f) }
    var controlsTimer by remember { mutableStateOf(0) }

    // Keep the screen on while playing (settings toggle) and hide system bars.
    DisposableEffect(settings.keepScreenOn, state.isPlaying) {
        val activity = context as? Activity
        if (activity != null && settings.keepScreenOn) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        activity?.window?.decorView?.let { hideSystemBars(it) }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity?.window?.decorView?.let { showSystemBars(it) }
        }
    }

    // Pause when the app goes to the background, resume is left to the user.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    viewModel.player.pause()
                }

                Lifecycle.Event.ON_STOP -> {
                    viewModel.player.pause()
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Auto-hide controls after a few seconds of playback.
    LaunchedEffect(state.controlsVisible, state.isPlaying, controlsTimer) {
        if (state.controlsVisible && state.isPlaying) {
            delay(4_000)
            viewModel.setControlsVisible(false)
        }
    }

    BackHandler {
        when {
            state.locked -> viewModel.toggleLock()
            state.controlsVisible -> viewModel.setControlsVisible(false)
            else -> navigator.back()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(state.locked) {
                detectTapGestures(
                    onTap = {
                        if (state.locked) {
                            viewModel.setControlsVisible(true)
                        } else {
                            viewModel.setControlsVisible(!state.controlsVisible)
                            controlsTimer++
                        }
                    },
                    onDoubleTap = { offset ->
                        if (state.locked) return@detectTapGestures
                        if (offset.x < size.width / 2f) {
                            viewModel.seekBy(-SEEK_STEP_MS)
                        } else {
                            viewModel.seekBy(SEEK_STEP_MS)
                        }
                        viewModel.setControlsVisible(true)
                        controlsTimer++
                    },
                )
            }
            .pointerInput(state.locked, state.durationMs) {
                if (state.locked) return@pointerInput
                detectVerticalDragGestures { change, dragAmount ->
                    change.consume()
                    val rightSide = change.position.x > size.width / 2f
                    if (rightSide) adjustVolume(context, -dragAmount / 24f)
                }
            }
            .pointerInput(state.locked, state.durationMs) {
                if (state.locked) return@pointerInput
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (scrubbing) {
                            viewModel.seekTo(scrubPosition.toLong())
                            scrubbing = false
                        }
                    },
                ) { change, dragAmount ->
                    change.consume()
                    if (state.durationMs <= 0) return@detectHorizontalDragGestures
                    if (!scrubbing) {
                        scrubbing = true
                        scrubPosition = state.positionMs.toFloat()
                        viewModel.setControlsVisible(true)
                    }
                    val delta = dragAmount * SCRUB_MS_PER_PX * (state.durationMs / 60_000f)
                    scrubPosition = (scrubPosition + delta).coerceIn(0f, state.durationMs.toFloat())
                    controlsTimer++
                }
            },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { view ->
                view.player = viewModel.player
                view.resizeMode = resizeModeFor(state.aspectMode)
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (state.loading) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }

        state.error?.let { error ->
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                shape = RoundedCornerShape(16.dp),
                color = Color.Black.copy(alpha = 0.8f),
            ) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.player_unsupported),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = error,
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = state.controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            PlayerControls(
                state = state,
                scrubbing = scrubbing,
                scrubPosition = scrubPosition,
                onScrubChange = { scrubPosition = it; scrubbing = true },
                onScrubEnd = {
                    viewModel.seekTo(it.toLong())
                    scrubbing = false
                },
                onPlayPause = { viewModel.playPause(); controlsTimer++ },
                onSeekBack = { viewModel.seekBy(-SEEK_STEP_MS); controlsTimer++ },
                onSeekForward = { viewModel.seekBy(SEEK_STEP_MS); controlsTimer++ },
                onNext = { viewModel.next(); controlsTimer++ },
                onPrevious = { viewModel.previous(); controlsTimer++ },
                onToggleLock = { viewModel.toggleLock() },
                onSpeed = { showSpeedDialog = true },
                onAspect = { showAspectDialog = true },
                onAudio = { showAudioDialog = true },
                onSubtitle = { showSubtitleDialog = true },
                onBack = { navigator.back() },
            )
        }

        if (state.locked && !state.controlsVisible) {
            IconButton(
                onClick = { viewModel.setControlsVisible(true) },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(16.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f)),
            ) {
                Icon(Icons.Outlined.Lock, contentDescription = stringResource(R.string.player_locked), tint = Color.White)
            }
        }
    }

    // ---- dialogs ----------------------------------------------------------

    state.resumePrompt?.let { prompt ->
        ResumeDialog(
            position = prompt,
            onResume = { viewModel.resolveResume(true) },
            onStartOver = { viewModel.resolveResume(false) },
            onDismiss = { viewModel.resolveResume(false) },
        )
    }

    if (showSpeedDialog) {
        val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
        OptionDialog(
            title = stringResource(R.string.player_speed),
            options = speeds.map { "${it}×" },
            selectedIndex = speeds.indexOf(state.speed).coerceAtLeast(0),
            onSelect = { viewModel.setSpeed(speeds[it]) },
            onDismiss = { showSpeedDialog = false },
        )
    }

    if (showAspectDialog) {
        val modes = AspectMode.entries.toList()
        OptionDialog(
            title = stringResource(R.string.player_aspect),
            options = modes.map { aspectLabel(it) },
            selectedIndex = modes.indexOf(state.aspectMode),
            onSelect = { viewModel.setAspectMode(modes[it]) },
            onDismiss = { showAspectDialog = false },
        )
    }

    if (showAudioDialog) {
        val options = state.audioTracks
        OptionDialog(
            title = stringResource(R.string.player_audio_track),
            options = options.map { it.label },
            selectedIndex = options.indexOfFirst { it.selected },
            onSelect = { viewModel.selectAudio(options.getOrNull(it)) },
            onDismiss = { showAudioDialog = false },
        )
    }

    if (showSubtitleDialog) {
        val options = state.subtitleTracks
        val external = state.externalSubtitles
        OptionDialog(
            title = stringResource(R.string.player_subtitle_track),
            options = listOf(stringResource(R.string.player_track_off)) + options.map { it.label } +
                external.map { it.name },
            selectedIndex = options.indexOfFirst { it.selected }.let { if (it < 0) 0 else it + 1 },
            onSelect = { index ->
                when {
                    index == 0 -> viewModel.selectSubtitle(null)
                    index <= options.size -> viewModel.selectSubtitle(options[index - 1])
                    else -> {
                        val node = external.getOrNull(index - options.size - 1)
                        node?.let { viewModel.addExternalSubtitle(it.uri) }
                    }
                }
            },
            onDismiss = { showSubtitleDialog = false },
        )
    }
}

@Composable
private fun aspectLabel(mode: AspectMode): String = stringResource(
    when (mode) {
        AspectMode.FIT -> R.string.aspect_fit
        AspectMode.FILL -> R.string.aspect_fill
        AspectMode.STRETCH -> R.string.aspect_stretch
        AspectMode.ZOOM -> R.string.aspect_zoom
    },
)

private fun resizeModeFor(mode: AspectMode): Int = when (mode) {
    AspectMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    AspectMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    AspectMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_STRETCH
    AspectMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
}

private fun hideSystemBars(view: View) {
    @Suppress("DEPRECATION")
    view.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        or View.SYSTEM_UI_FLAG_FULLSCREEN)
}

private fun showSystemBars(view: View) {
    @Suppress("DEPRECATION")
    view.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
}

private fun adjustVolume(context: Context, deltaSteps: Float) {
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    val target = (current + deltaSteps).toInt().coerceIn(0, max)
    if (target != current) audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
}

private const val SEEK_STEP_MS = 10_000L
private const val SCRUB_MS_PER_PX = 220f

/** Compose control overlay: transport, timeline and drive-specific pickers. */
@Composable
private fun PlayerControls(
    state: PlayerUiState,
    scrubbing: Boolean,
    scrubPosition: Float,
    onScrubChange: (Float) -> Unit,
    onScrubEnd: (Float) -> Unit,
    onPlayPause: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleLock: () -> Unit,
    onSpeed: () -> Unit,
    onAspect: () -> Unit,
    onAudio: () -> Unit,
    onSubtitle: () -> Unit,
    onBack: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .systemBarsPadding(),
    ) {
        // Top bar
        Row(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                    tint = Color.White,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = state.node?.nameWithoutExtension.orEmpty(),
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.playlist.size > 1) {
                    Text(
                        text = "${state.index + 1} / ${state.playlist.size}",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            IconButton(onClick = onToggleLock) {
                Icon(
                    imageVector = if (state.locked) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                    contentDescription = stringResource(R.string.player_locked),
                    tint = Color.White,
                )
            }
        }

        // Centre transport
        Row(
            Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IconButton(onClick = onPrevious, enabled = state.hasPrevious) {
                Icon(
                    Icons.Outlined.SkipPrevious,
                    contentDescription = stringResource(R.string.player_previous),
                    tint = Color.White,
                    modifier = Modifier.size(40.dp),
                )
            }
            IconButton(onClick = onSeekBack) {
                Icon(
                    Icons.Outlined.Replay10,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(34.dp),
                )
            }
            IconButton(
                onClick = onPlayPause,
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.18f)),
            ) {
                Icon(
                    imageVector = if (state.isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(40.dp),
                )
            }
            IconButton(onClick = onSeekForward) {
                Icon(
                    Icons.Outlined.Forward10,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(34.dp),
                )
            }
            IconButton(onClick = onNext, enabled = state.hasNext) {
                Icon(
                    Icons.Outlined.SkipNext,
                    contentDescription = stringResource(R.string.player_next),
                    tint = Color.White,
                    modifier = Modifier.size(40.dp),
                )
            }
        }

        // Bottom bar
        Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = Formatters.duration(if (scrubbing) scrubPosition.toLong() else state.positionMs),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
                Slider(
                    value = if (scrubbing) scrubPosition else state.positionMs.toFloat(),
                    onValueChange = onScrubChange,
                    onValueChangeFinished = { onScrubEnd(if (scrubbing) scrubPosition else state.positionMs.toFloat()) },
                    valueRange = 0f..state.durationMs.coerceAtLeast(1L).toFloat(),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp),
                )
                Text(
                    text = Formatters.duration(state.durationMs),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OverlayAction(Icons.Outlined.Subtitles, R.string.player_subtitle_track, onSubtitle)
                OverlayAction(Icons.Outlined.Audiotrack, R.string.player_audio_track, onAudio)
                OverlayAction(Icons.Outlined.Speed, R.string.player_speed, onSpeed)
                OverlayAction(Icons.Outlined.AspectRatio, R.string.player_aspect, onAspect)
                Spacer(Modifier.width(4.dp))
            }
        }
    }
}

@Composable
private fun OverlayAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    labelRes: Int,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(labelRes),
            tint = Color.White,
            modifier = Modifier.size(22.dp),
        )
    }
}
