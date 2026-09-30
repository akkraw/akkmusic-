package dev.local.player

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private enum class Screen { TRACKS, PLAYLISTS, PLAYLIST }

    // ---------- Вьюхи ----------
    private lateinit var backBtn: ImageButton
    private lateinit var screenTitle: TextView
    private lateinit var countText: TextView
    private lateinit var tabs: MaterialButtonToggleGroup
    private lateinit var actionsRow: View
    private lateinit var newPlaylistBtn: Button
    private lateinit var playAllBtn: Button
    private lateinit var shuffleAllBtn: Button
    private lateinit var list: RecyclerView
    private lateinit var emptyView: View
    private lateinit var emptyText: TextView
    private lateinit var grantButton: Button
    private lateinit var playerBar: View
    private lateinit var nowTitle: TextView
    private lateinit var nowArtist: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var posText: TextView
    private lateinit var durText: TextView
    private lateinit var playBtn: ImageButton
    private lateinit var shuffleBtn: ImageButton
    private lateinit var repeatBtn: ImageButton

    // ---------- Данные и состояние экрана ----------
    private lateinit var playlists: PlaylistStore
    private var library: List<Track> = emptyList()
    private var trackById: Map<Long, Track> = emptyMap()
    private var libraryLoaded = false

    private var screen = Screen.TRACKS
    private var openPlaylistId: Long = -1

    private val trackAdapter: TrackAdapter = TrackAdapter(
        onClick = { pos -> playQueue(trackAdapter.tracks, pos) },
        onLongClick = { pos -> onTrackLongClick(trackAdapter.tracks[pos]) },
        onStartDrag = { holder -> touchHelper.startDrag(holder) },
    )
    private val playlistAdapter = PlaylistAdapter(
        onClick = { openPlaylist(it.id) },
        onLongClick = { onPlaylistLongClick(it) },
    )

    // Перетаскивание треков внутри плейлиста за «ручку» справа
    private val touchHelper: ItemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
        private var moved = false

        override fun isLongPressDragEnabled() = false
        override fun isItemViewSwipeEnabled() = false

        override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int =
            if (screen == Screen.PLAYLIST) {
                makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
            } else 0

        override fun onMove(
            rv: RecyclerView,
            from: RecyclerView.ViewHolder,
            to: RecyclerView.ViewHolder,
        ): Boolean {
            val a = from.bindingAdapterPosition
            val b = to.bindingAdapterPosition
            if (a == RecyclerView.NO_POSITION || b == RecyclerView.NO_POSITION) return false
            trackAdapter.move(a, b)
            moved = true
            return true
        }

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            if (moved) {
                moved = false
                saveDraggedOrder()
            }
        }
    })

    // ---------- Плеер ----------
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val controller: MediaController?
        get() = controllerFuture?.takeIf { it.isDone && !it.isCancelled }?.get()

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var userSeeking = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasAudioPermission()) loadLibrary() else render()
        }

    private val progressTick = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            updatePlayerUi()
        }
    }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = showScreen(Screen.PLAYLISTS)
    }

    // =====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        playlists = PlaylistStore(applicationContext)
        bindViews()
        onBackPressedDispatcher.addCallback(this, backCallback)

        savedInstanceState?.let {
            screen = Screen.entries[it.getInt(KEY_SCREEN, 0)]
            openPlaylistId = it.getLong(KEY_PLAYLIST, -1)
            if (screen == Screen.PLAYLIST && playlists.get(openPlaylistId) == null) {
                screen = Screen.PLAYLISTS
            }
        }
        tabs.check(if (screen == Screen.TRACKS) R.id.tabTracks else R.id.tabPlaylists)

        if (hasAudioPermission()) loadLibrary() else requestPermissions()
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_SCREEN, screen.ordinal)
        outState.putLong(KEY_PLAYLIST, openPlaylistId)
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            controller?.let {
                it.addListener(playerListener)
                updatePlayerUi()
            }
        }, MoreExecutors.directExecutor())
        handler.post(progressTick)
    }

    override fun onStop() {
        handler.removeCallbacks(progressTick)
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    override fun onDestroy() {
        ioExecutor.shutdown()
        super.onDestroy()
    }

    // ---------- Разрешения и библиотека ----------

    private fun audioPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, audioPermission()) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        val perms = mutableListOf(audioPermission())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Без него на Android 13+ не будет видно уведомления с кнопками
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun loadLibrary() {
        ioExecutor.execute {
            val loaded = MusicLibrary.load(applicationContext)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                library = loaded
                trackById = loaded.associateBy { it.id }
                libraryLoaded = true
                render()
                updatePlayerUi()
            }
        }
    }

    // ---------- Навигация и отрисовка экрана ----------

    private fun showScreen(s: Screen) {
        screen = s
        if (s != Screen.PLAYLIST) {
            tabs.check(if (s == Screen.TRACKS) R.id.tabTracks else R.id.tabPlaylists)
        }
        render()
        list.scrollToPosition(0)
    }

    private fun openPlaylist(id: Long) {
        openPlaylistId = id
        showScreen(Screen.PLAYLIST)
    }

    /** Треки открытого плейлиста, которые реально есть на телефоне. */
    private fun openPlaylistTracks(): List<Track> =
        playlists.get(openPlaylistId)?.trackIds?.mapNotNull { trackById[it] } ?: emptyList()

    private fun render() {
        backCallback.isEnabled = screen == Screen.PLAYLIST
        backBtn.visibility = if (screen == Screen.PLAYLIST) View.VISIBLE else View.GONE
        tabs.visibility = if (screen == Screen.PLAYLIST) View.GONE else View.VISIBLE

        var emptyMessage: Int? = null
        grantButton.visibility = View.GONE

        when (screen) {
            Screen.TRACKS -> {
                screenTitle.setText(R.string.app_name)
                countText.text = if (libraryLoaded) {
                    resources.getQuantityString(R.plurals.track_count, library.size, library.size)
                } else ""
                actionsRow.visibility = View.GONE
                list.adapter = trackAdapter
                trackAdapter.submit(library)
                when {
                    !hasAudioPermission() -> {
                        emptyMessage = R.string.need_permission
                        grantButton.visibility = View.VISIBLE
                    }
                    libraryLoaded && library.isEmpty() -> emptyMessage = R.string.no_tracks
                }
            }

            Screen.PLAYLISTS -> {
                val all = playlists.all()
                screenTitle.setText(R.string.app_name)
                countText.text =
                    resources.getQuantityString(R.plurals.playlist_count, all.size, all.size)
                showActions(newPlaylist = true)
                list.adapter = playlistAdapter
                playlistAdapter.submit(all)
                if (all.isEmpty()) emptyMessage = R.string.no_playlists
            }

            Screen.PLAYLIST -> {
                val p = playlists.get(openPlaylistId)
                if (p == null) {
                    showScreen(Screen.PLAYLISTS)
                    return
                }
                val tracks = openPlaylistTracks()
                screenTitle.text = p.name
                countText.text =
                    resources.getQuantityString(R.plurals.track_count, tracks.size, tracks.size)
                showActions(newPlaylist = false)
                playAllBtn.isEnabled = tracks.isNotEmpty()
                shuffleAllBtn.isEnabled = tracks.isNotEmpty()
                list.adapter = trackAdapter
                trackAdapter.submit(tracks, dragHandles = true)
                if (libraryLoaded && tracks.isEmpty()) emptyMessage = R.string.playlist_empty
            }
        }

        if (emptyMessage != null) {
            emptyText.setText(emptyMessage)
            emptyView.visibility = View.VISIBLE
            list.visibility = View.GONE
        } else {
            emptyView.visibility = View.GONE
            list.visibility = View.VISIBLE
        }
    }

    private fun showActions(newPlaylist: Boolean) {
        actionsRow.visibility = View.VISIBLE
        newPlaylistBtn.visibility = if (newPlaylist) View.VISIBLE else View.GONE
        playAllBtn.visibility = if (newPlaylist) View.GONE else View.VISIBLE
        shuffleAllBtn.visibility = if (newPlaylist) View.GONE else View.VISIBLE
    }

    // ---------- Плейлисты: действия ----------

    private fun onTrackLongClick(track: Track) {
        if (screen != Screen.PLAYLIST) {
            chooseTargetPlaylist(track)
            return
        }
        val options = arrayOf(
            getString(R.string.remove_from_playlist),
            getString(R.string.add_to_playlist),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(track.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        playlists.removeTrack(openPlaylistId, track.id)
                        render()
                    }
                    1 -> chooseTargetPlaylist(track)
                }
            }
            .show()
    }

    /** Диалог «Добавить в плейлист»: список плейлистов + создание нового. */
    private fun chooseTargetPlaylist(track: Track) {
        val all = playlists.all()
        val names = all.map { it.name } + getString(R.string.new_playlist_ellipsis)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_to_playlist)
            .setItems(names.toTypedArray()) { _, which ->
                if (which < all.size) {
                    addToPlaylist(all[which], track)
                } else {
                    askName(R.string.new_playlist, "", R.string.create) { name ->
                        addToPlaylist(playlists.create(name), track)
                    }
                }
            }
            .show()
    }

    private fun addToPlaylist(p: Playlist, track: Track) {
        val added = playlists.addTrack(p.id, track.id)
        val msg = if (added) R.string.added_to else R.string.already_in
        Toast.makeText(this, getString(msg, p.name), Toast.LENGTH_SHORT).show()
        if (screen != Screen.TRACKS) render()
    }

    private fun onPlaylistLongClick(p: Playlist) {
        val options = arrayOf(getString(R.string.rename), getString(R.string.delete))
        MaterialAlertDialogBuilder(this)
            .setTitle(p.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> askName(R.string.rename, p.name, R.string.save) { name ->
                        playlists.rename(p.id, name)
                        render()
                    }
                    1 -> MaterialAlertDialogBuilder(this)
                        .setMessage(getString(R.string.delete_playlist_q, p.name))
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.delete) { _, _ ->
                            playlists.delete(p.id)
                            render()
                        }
                        .show()
                }
            }
            .show()
    }

    /** Диалог с полем ввода названия. Пустое название не принимается. */
    private fun askName(title: Int, initial: String, positive: Int, onOk: (String) -> Unit) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            setText(initial)
            setSelection(initial.length)
            setHint(R.string.playlist_name_hint)
            setSingleLine(true)
        }
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(positive) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) onOk(name)
            }
            .show()
        input.requestFocus()
    }

    private fun saveDraggedOrder() {
        val p = playlists.get(openPlaylistId) ?: return
        val visible = trackAdapter.tracks.map { it.id }
        // Треки, которых сейчас нет на телефоне, не теряем — оставляем в конце
        val missing = p.trackIds.filter { it !in trackById }
        playlists.setTracks(p.id, visible + missing)
    }

    // ---------- Воспроизведение ----------

    /** Ставит в очередь переданный список и начинает с выбранного трека. */
    private fun playQueue(tracks: List<Track>, startIndex: Int, shuffle: Boolean = false) {
        val c = controller ?: return
        if (tracks.isEmpty()) return
        c.shuffleModeEnabled = shuffle
        c.setMediaItems(tracks.map { it.toMediaItem() }, startIndex, 0L)
        c.prepare()
        c.play()
    }

    private fun bindViews() {
        backBtn = findViewById(R.id.backBtn)
        screenTitle = findViewById(R.id.screenTitle)
        countText = findViewById(R.id.countText)
        tabs = findViewById(R.id.tabs)
        actionsRow = findViewById(R.id.actionsRow)
        newPlaylistBtn = findViewById(R.id.newPlaylistBtn)
        playAllBtn = findViewById(R.id.playAllBtn)
        shuffleAllBtn = findViewById(R.id.shuffleAllBtn)
        list = findViewById(R.id.trackList)
        emptyView = findViewById(R.id.emptyView)
        emptyText = findViewById(R.id.emptyText)
        grantButton = findViewById(R.id.grantButton)
        playerBar = findViewById(R.id.playerBar)
        nowTitle = findViewById(R.id.nowTitle)
        nowArtist = findViewById(R.id.nowArtist)
        seekBar = findViewById(R.id.seekBar)
        posText = findViewById(R.id.posText)
        durText = findViewById(R.id.durText)
        playBtn = findViewById(R.id.playBtn)
        shuffleBtn = findViewById(R.id.shuffleBtn)
        repeatBtn = findViewById(R.id.repeatBtn)

        list.layoutManager = LinearLayoutManager(this)
        list.adapter = trackAdapter
        touchHelper.attachToRecyclerView(list)

        tabs.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || screen == Screen.PLAYLIST) return@addOnButtonCheckedListener
            val target = if (checkedId == R.id.tabTracks) Screen.TRACKS else Screen.PLAYLISTS
            if (target != screen) showScreen(target)
        }
        backBtn.setOnClickListener { showScreen(Screen.PLAYLISTS) }
        grantButton.setOnClickListener { requestPermissions() }
        newPlaylistBtn.setOnClickListener {
            askName(R.string.new_playlist, "", R.string.create) { name ->
                playlists.create(name)
                render()
            }
        }
        playAllBtn.setOnClickListener { playQueue(openPlaylistTracks(), 0) }
        shuffleAllBtn.setOnClickListener {
            val tracks = openPlaylistTracks()
            if (tracks.isNotEmpty()) playQueue(tracks, tracks.indices.random(), shuffle = true)
        }

        playBtn.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            if (c.isPlaying) c.pause() else {
                if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition(0)
                c.play()
            }
        }
        findViewById<ImageButton>(R.id.prevBtn).setOnClickListener { controller?.seekToPrevious() }
        findViewById<ImageButton>(R.id.nextBtn).setOnClickListener { controller?.seekToNext() }
        shuffleBtn.setOnClickListener {
            controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
        }
        repeatBtn.setOnClickListener {
            controller?.let {
                it.repeatMode = when (it.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
            }
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) posText.text = formatTime(progress.toLong())
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                userSeeking = false
                controller?.seekTo(sb.progress.toLong())
            }
        })
    }

    // ---------- UI плеера ----------

    private fun updatePlayerUi() {
        val c = controller
        val item = c?.currentMediaItem
        if (c == null || item == null) {
            playerBar.visibility = View.GONE
            trackAdapter.setCurrent(null)
            return
        }
        playerBar.visibility = View.VISIBLE
        nowTitle.text = item.mediaMetadata.title ?: ""
        nowArtist.text = item.mediaMetadata.artist ?: ""
        trackAdapter.setCurrent(item.mediaId)

        playBtn.setImageResource(if (c.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        playBtn.contentDescription = getString(if (c.isPlaying) R.string.pause else R.string.play)

        shuffleBtn.alpha = if (c.shuffleModeEnabled) 1f else 0.35f
        when (c.repeatMode) {
            Player.REPEAT_MODE_ONE -> {
                repeatBtn.setImageResource(R.drawable.ic_repeat_one); repeatBtn.alpha = 1f
            }
            Player.REPEAT_MODE_ALL -> {
                repeatBtn.setImageResource(R.drawable.ic_repeat); repeatBtn.alpha = 1f
            }
            else -> {
                repeatBtn.setImageResource(R.drawable.ic_repeat); repeatBtn.alpha = 0.35f
            }
        }
        updateProgress()
    }

    private fun updateProgress() {
        val c = controller ?: return
        if (c.currentMediaItem == null) return
        val duration = c.duration.takeIf { it > 0 } ?: 0L
        seekBar.max = duration.toInt()
        durText.text = formatTime(duration)
        if (!userSeeking) {
            seekBar.progress = c.currentPosition.toInt()
            posText.text = formatTime(c.currentPosition)
        }
    }

    companion object {
        private const val KEY_SCREEN = "screen"
        private const val KEY_PLAYLIST = "playlist"
    }
}
