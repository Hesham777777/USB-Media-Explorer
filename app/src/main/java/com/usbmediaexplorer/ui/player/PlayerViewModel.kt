package com.usbmediaexplorer.ui.player

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import com.usbmediaexplorer.data.doc.DocNode
import com.usbmediaexplorer.data.doc.DocRepository
import com.usbmediaexplorer.data.doc.DocSorter
import com.usbmediaexplorer.data.doc.MediaKind
import com.usbmediaexplorer.data.doc.isSubtitle
import com.usbmediaexplorer.data.doc.isVideo
import com.usbmediaexplorer.data.settings.AspectMode
import com.usbmediaexplorer.data.store.PlaybackPosition
import com.usbmediaexplorer.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TrackOption(
    val id: String,
    val label: String,
    val groupIndex: Int,
    val trackIndex: Int,
    val type: Int,
    val selected: Boolean,
)

data class PlayerUiState(
    val node: DocNode? = null,
    val playlist: List<DocNode> = emptyList(),
    val index: Int = 0,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val speed: Float = 1f,
    val aspectMode: AspectMode = AspectMode.FIT,
    val audioTracks: List<TrackOption> = emptyList(),
    val subtitleTracks: List<TrackOption> = emptyList(),
    val externalSubtitles: List<DocNode> = emptyList(),
    val controlsVisible: Boolean = true,
    val locked: Boolean = false,
    val error: String? = null,
    val resumePrompt: PlaybackPosition? = null,
    val loading: Boolean = true,
) {
    val hasNext: Boolean get() = index < playlist.lastIndex
    val hasPrevious: Boolean get() = index > 0
    val progress: Float
        get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

/**
 * Built-in player (spec §9, §10, §11, §19).
 *
 * Plays straight from the USB drive through Media3's default data source (content:// and file://
 * are both handled) — the file is never copied locally. The playlist is the folder's video list
 * in the browser's current sort order, so "next episode" works without leaving the player.
 */
class PlayerViewModel(
    private val container: AppContainer,
    private val uriString: String,
    private val folderUriString: String,
) : ViewModel() {

    private val context: Context = container.appContext
    private val docRepository: DocRepository = container.docRepository

    /** Subtitle configurations applied per playlist index (Media3 does not hand them back). */
    private val subtitleConfigs = HashMap<Int, MutableList<MediaItem.SubtitleConfiguration>>()

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setSeekBackIncrementMs(SEEK_STEP_MS)
        .setSeekForwardIncrementMs(SEEK_STEP_MS)
        .setHandleAudioBecomingNoisy(true)
        .build()
        .apply {
            setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ true)
            playWhenReady = true
        }

    init {
        player.addListener(
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _state.value = _state.value.copy(isPlaying = isPlaying)
                    if (!isPlaying) persistPosition()
                }

                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                    publishTrackOptions()
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val uri = mediaItem?.localConfiguration?.uri ?: mediaItem?.mediaId?.let { Uri.parse(it) }
                    val node = _state.value.playlist.firstOrNull { it.uri.toString() == uri?.toString() }
                    _state.value = _state.value.copy(
                        node = node ?: _state.value.node,
                        index = _state.value.playlist.indexOfFirst { it.uri.toString() == uri?.toString() }
                            .coerceAtLeast(0),
                    )
                    node?.let { applyResumeBehaviour(it) }
                }

                override fun onPlayerError(error: PlaybackException) {
                    _state.value = _state.value.copy(
                        error = error.errorCodeName + ": " + (error.message ?: ""),
                    )
                }
            },
        )

        viewModelScope.launch {
            val settings = container.settingsRepository.settings.first()
            _state.value = _state.value.copy(
                speed = settings.defaultPlaybackSpeed,
                aspectMode = settings.defaultAspectMode,
            )
            player.setPlaybackSpeed(settings.defaultPlaybackSpeed)
            prepare()
            startPositionTicker()
        }
    }

    // ------------------------------------------------------------------

    private suspend fun prepare() {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull()
        if (uri == null) {
            _state.value = _state.value.copy(loading = false, error = "invalid-uri")
            return
        }
        val node = withContext(Dispatchers.IO) { docRepository.node(uri) }
        if (node == null) {
            _state.value = _state.value.copy(loading = false, error = "missing-file")
            return
        }

        val folderUri = folderUriString.takeIf { it.isNotBlank() }?.let {
            runCatching { Uri.parse(it) }.getOrNull()
        }
        val siblings: List<DocNode> = withContext(Dispatchers.IO) {
            val parent = folderUri?.let { docRepository.node(it) }
            val children = parent?.let { docRepository.children(it) } ?: emptyList()
            val sortMode = container.folderPrefsStore.prefsFor(parent?.stableKey.orEmpty()).sortMode
                ?: container.settingsRepository.settings.first().defaultSortMode
            DocSorter.sort(children.filter { it.isVideo }, sortMode, foldersFirst = false)
        }
        val playlist = siblings.ifEmpty { listOf(node) }
        val startIndex = playlist.indexOfFirst { it.key == node.key }.coerceAtLeast(0)

        val settings = container.settingsRepository.settings.first()
        val subtitleNodes = if (settings.autoDetectSubtitles) {
            findExternalSubtitles(node, folderUri)
        } else {
            emptyList()
        }

        _state.value = _state.value.copy(
            node = playlist[startIndex],
            playlist = playlist,
            index = startIndex,
            externalSubtitles = subtitleNodes,
            loading = false,
        )

        val initialSubs = subtitleNodes.map { sub -> subtitleConfig(sub) }
        subtitleConfigs[startIndex] = initialSubs.toMutableList()
        val mediaItems = playlist.map { item ->
            MediaItem.Builder()
                .setUri(item.uri)
                .setMediaId(item.uri.toString())
                .setSubtitleConfigurations(
                    if (item.key == node.key) initialSubs else emptyList(),
                )
                .build()
        }
        val resumeMs = resumePositionFor(playlist[startIndex])
        player.setMediaItems(mediaItems, startIndex, resumeMs ?: 0L)
        player.prepare()

        val resume = resumeMs?.let {
            PlaybackPosition(
                key = playlist[startIndex].stableKey,
                positionMs = it,
                durationMs = 0L,
                updatedAt = System.currentTimeMillis(),
            )
        }
        if (resume != null && resume.positionMs > RESUME_PROMPT_THRESHOLD_MS && settings.resumePromptEnabled) {
            player.pause()
            _state.value = _state.value.copy(resumePrompt = resume, isPlaying = false)
        }
    }

    private suspend fun findExternalSubtitles(node: DocNode, folderUri: Uri?): List<DocNode> =
        withContext(Dispatchers.IO) {
            val parentUri = folderUri ?: runCatching { docRepository.parentOf(node)?.uri }.getOrNull()
                ?: return@withContext emptyList()
            val parent = docRepository.node(parentUri) ?: return@withContext emptyList()
            val base = node.nameWithoutExtension.lowercase()
            docRepository.children(parent)
                .filter { it.isSubtitle }
                .sortedWith(
                    compareByDescending<DocNode> { it.nameWithoutExtension.lowercase() == base }
                        .thenBy { it.name.length },
                )
                .take(MAX_EXTERNAL_SUBTITLES)
        }

    private fun subtitleConfig(sub: DocNode): MediaItem.SubtitleConfiguration {
        val mime = MediaKind.subtitleMime(sub.extension) ?: "application/x-subrip"
        val language = guessLanguage(sub.name)
        return MediaItem.SubtitleConfiguration.Builder(sub.uri)
            .setMimeType(mime)
            .setLabel(sub.name)
            .setLanguage(language)
            .setSelectionFlags(if (language != null) C.SELECTION_FLAG_DEFAULT else 0)
            .build()
    }

    private fun guessLanguage(name: String): String? {
        val lower = name.lowercase()
        return when {
            lower.contains(".ar") || lower.contains("arabic") || name.contains("عرب") -> "ar"
            lower.contains(".en") || lower.contains("english") -> "en"
            lower.contains(".fr") || lower.contains("french") -> "fr"
            lower.contains(".es") || lower.contains("spanish") -> "es"
            lower.contains(".de") || lower.contains("german") -> "de"
            else -> null
        }
    }

    private fun resumePositionFor(node: DocNode): Long? =
        container.playbackPositionStore.positionOf(node.stableKey)?.positionMs?.takeIf {
            it > RESUME_PROMPT_THRESHOLD_MS / 2
        }

    /**
     * When the playlist advances to the next episode, jump to that file's own bookmark
     * (spec §10 + §19) without interrupting playback.
     */
    private fun applyResumeBehaviour(node: DocNode) {
        val position = container.playbackPositionStore.positionOf(node.stableKey) ?: return
        if (position.isFinished) return
        if (position.positionMs > RESUME_PROMPT_THRESHOLD_MS && player.currentPosition < 1000) {
            player.seekTo(position.positionMs)
        }
    }

    private fun startPositionTicker() {
        viewModelScope.launch {
            while (true) {
                delay(500)
                val current = player.currentPosition.coerceAtLeast(0)
                val duration = player.duration.let { if (it == C.TIME_UNSET) 0L else it }
                val buffered = player.bufferedPosition.coerceAtLeast(0)
                _state.value = _state.value.copy(
                    positionMs = current,
                    durationMs = duration,
                    bufferedMs = buffered,
                )
                if (_state.value.isPlaying && current % SAVE_INTERVAL_MS < 500) persistPosition()
            }
        }
    }

    private fun publishTrackOptions() {
        val tracks = player.currentTracks
        val audio = ArrayList<TrackOption>()
        val text = ArrayList<TrackOption>()
        tracks.groups.forEachIndexed { groupIndex, group ->
            val type = group.type
            if (type != C.TRACK_TYPE_AUDIO && type != C.TRACK_TYPE_TEXT) return@forEachIndexed
            for (trackIndex in 0 until group.length) {
                if (!group.isTrackSupported(trackIndex)) continue
                val format = group.getTrackFormat(trackIndex)
                val label = buildString {
                    format.language?.takeIf { it.isNotBlank() && it != "und" }?.let { append(it.uppercase()) }
                    format.label?.let {
                        if (isNotEmpty()) append(" • ")
                        append(it)
                    }
                    if (isEmpty()) append(format.sampleMimeType?.substringAfterLast('/') ?: "track")
                    if (type == C.TRACK_TYPE_AUDIO && format.channelCount > 0) {
                        append(" • ${format.channelCount}ch")
                    }
                }
                val option = TrackOption(
                    id = "$groupIndex:$trackIndex",
                    label = label,
                    groupIndex = groupIndex,
                    trackIndex = trackIndex,
                    type = type,
                    selected = group.isTrackSelected(trackIndex),
                )
                if (type == C.TRACK_TYPE_AUDIO) audio += option else text += option
            }
        }
        _state.value = _state.value.copy(audioTracks = audio, subtitleTracks = text)
    }

    // ---- controls --------------------------------------------------------

    fun playPause() {
        if (player.isPlaying) {
            player.pause()
            persistPosition()
        } else {
            player.play()
        }
        _state.value = _state.value.copy(isPlaying = player.isPlaying)
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs.coerceIn(0, player.duration.coerceAtLeast(0)))
        _state.value = _state.value.copy(positionMs = positionMs)
    }

    fun seekBy(deltaMs: Long) = seekTo(player.currentPosition + deltaMs)

    fun next() {
        if (player.hasNextMediaItem()) player.seekToNextMediaItem()
    }

    fun previous() {
        if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem()
    }

    fun playIndex(index: Int) {
        if (index in _state.value.playlist.indices) {
            persistPosition()
            player.seekToDefaultPosition(index)
            _state.value = _state.value.copy(index = index, node = _state.value.playlist[index])
        }
    }

    fun setSpeed(speed: Float) {
        player.playbackParameters = PlaybackParameters(speed)
        _state.value = _state.value.copy(speed = speed)
        viewModelScope.launch { container.settingsRepository.setPlaybackSpeed(speed) }
    }

    fun cycleAspectMode() {
        val next = AspectMode.entries[(_state.value.aspectMode.ordinal + 1) % AspectMode.entries.size]
        _state.value = _state.value.copy(aspectMode = next)
        viewModelScope.launch { container.settingsRepository.setAspectMode(next) }
    }

    fun setAspectMode(mode: AspectMode) {
        _state.value = _state.value.copy(aspectMode = mode)
    }

    fun toggleLock() {
        _state.value = _state.value.copy(
            locked = !_state.value.locked,
            controlsVisible = false,
        )
    }

    fun setControlsVisible(visible: Boolean) {
        if (_state.value.locked && visible) {
            _state.value = _state.value.copy(controlsVisible = true)
            return
        }
        _state.value = _state.value.copy(controlsVisible = visible)
    }

    fun selectAudio(option: TrackOption?) {
        applyTrackOverride(C.TRACK_TYPE_AUDIO, option)
    }

    fun selectSubtitle(option: TrackOption?) {
        applyTrackOverride(C.TRACK_TYPE_TEXT, option)
    }

    private fun applyTrackOverride(type: Int, option: TrackOption?) {
        val parameters = player.trackSelectionParameters.buildUpon()
        if (option == null) {
            parameters.setTrackTypeDisabled(type, true)
            parameters.clearOverridesOfType(type)
        } else {
            parameters.setTrackTypeDisabled(type, false)
            val groups = player.currentTracks.groups
            val group = groups.getOrNull(option.groupIndex)
            if (group != null) {
                parameters.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
            }
        }
        player.trackSelectionParameters = parameters.build()
        viewModelScope.launch {
            delay(150)
            publishTrackOptions()
        }
    }

    /** Adds a user-picked subtitle file to the currently playing item (spec §11). */
    fun addExternalSubtitle(uri: Uri) {
        val index = player.currentMediaItemIndex
        val current = runCatching { player.getMediaItemAt(index) }.getOrNull() ?: return
        val mime = MediaKind.subtitleMime(uri.toString().substringAfterLast('.', "srt"))
            ?: "application/x-subrip"
        val config = MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(mime)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        val configs = subtitleConfigs.getOrPut(index) { mutableListOf() }
        configs += config
        val updated = current.buildUpon()
            .setSubtitleConfigurations(configs)
            .build()
        player.replaceMediaItem(index, updated)
        viewModelScope.launch {
            delay(400)
            publishTrackOptions()
        }
    }

    fun resolveResume(resume: Boolean) {
        val prompt = _state.value.resumePrompt
        _state.value = _state.value.copy(resumePrompt = null)
        if (resume && prompt != null) {
            player.seekTo(prompt.positionMs)
        } else {
            player.seekTo(0)
        }
        player.play()
        _state.value = _state.value.copy(isPlaying = true)
    }

    fun retry() {
        _state.value = _state.value.copy(error = null)
        player.prepare()
        player.play()
    }

    private fun persistPosition() {
        val node = _state.value.node ?: return
        val position = player.currentPosition
        val duration = player.duration.let { if (it == C.TIME_UNSET) 0L else it }
        if (position <= 0 || duration <= 0) return
        viewModelScope.launch {
            container.playbackPositionStore.save(
                PlaybackPosition(
                    key = node.stableKey,
                    positionMs = position,
                    durationMs = duration,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    override fun onCleared() {
        persistPosition()
        player.release()
        super.onCleared()
    }

    private companion object {
        const val SEEK_STEP_MS = 10_000L
        const val SAVE_INTERVAL_MS = 10_000L
        const val RESUME_PROMPT_THRESHOLD_MS = 30_000L
        const val MAX_EXTERNAL_SUBTITLES = 6
    }
}
