package dev.local.player

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.slider.Slider
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
import com.google.android.material.color.MaterialColors
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
    private lateinit var sortBtn: ImageButton
    private lateinit var bgBtn: ImageButton

    // Фон
    private lateinit var appRoot: View
    private lateinit var bgImage: ImageView
    private lateinit var bgScrim: View
    private lateinit var headerPanel: View
    private lateinit var listContainer: View
    private lateinit var bottomBar: View
    private var bgSharp: Bitmap? = null
    private var bgBlur: Bitmap? = null
    private var panelDrawables: List<BackdropDrawable> = emptyList()
    private lateinit var actionsRow: View

    // Нижнее меню
    private lateinit var navTracks: View
    private lateinit var navPlaylists: View
    private lateinit var navTracksPill: View
    private lateinit var navPlaylistsPill: View
    private lateinit var navTracksIcon: ImageView
    private lateinit var navPlaylistsIcon: ImageView
    private lateinit var navTracksLabel: TextView
    private lateinit var navPlaylistsLabel: TextView
    private lateinit var addSlot: View
    private lateinit var addPlaylistBtn: ImageButton
    private var addShown: Boolean? = null
    private var slotAnimator: ValueAnimator? = null
    private val easing = PathInterpolator(0.2f, 0f, 0f, 1f)
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
    private lateinit var playCounts: PlayCounts
    private lateinit var uiPrefs: SharedPreferences
    private var trackSort = TrackSort.ARTIST

    // Сервис засчитал прослушивание — обновляем цифры в списке (порядок не трогаем,
    // чтобы список не прыгал во время прослушивания)
    private val countsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            trackAdapter.setPlayCounts(playCounts.all())
        }
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

    // Системный выбор фото: разрешения на доступ к галерее не нужны
    private val pickImage =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri == null) return@registerForActivityResult
            ioExecutor.execute {
                val ok = Backdrop.import(applicationContext, uri)
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    // Пересоздаём экран: с фоном включается тёмный стиль
                    if (ok) recreate()
                    else Toast.makeText(this, R.string.bg_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }

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
        // С картинкой на фоне текст всегда светлый — включаем тёмную тему для этого экрана
        val hasBg = Backdrop.has(this)
        delegate.localNightMode =
            if (hasBg) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        super.onCreate(savedInstanceState)
        if (hasBg) {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            )
        } else {
            enableEdgeToEdge()
        }
        setContentView(R.layout.activity_main)
        playlists = PlaylistStore(applicationContext)
        playCounts = PlayCounts(applicationContext)
        uiPrefs = getSharedPreferences("ui", Context.MODE_PRIVATE)
        trackSort = TrackSort.fromName(uiPrefs.getString(KEY_SORT, null))
        bindViews()
        setupInsets()
        Backdrop.load(this)?.let { (sharp, blur) ->
            bgSharp = sharp
            bgBlur = blur
        }
        applyBackground()
        onBackPressedDispatcher.addCallback(this, backCallback)

        savedInstanceState?.let {
            screen = Screen.entries[it.getInt(KEY_SCREEN, 0)]
            openPlaylistId = it.getLong(KEY_PLAYLIST, -1)
            if (screen == Screen.PLAYLIST && playlists.get(openPlaylistId) == null) {
                screen = Screen.PLAYLISTS
            }
        }
        syncBottomNav(animate = false)

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
        playCounts.prefs.registerOnSharedPreferenceChangeListener(countsListener)
        trackAdapter.setPlayCounts(playCounts.all())
    }

    override fun onStop() {
        handler.removeCallbacks(progressTick)
        playCounts.prefs.unregisterOnSharedPreferenceChangeListener(countsListener)
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
        syncBottomNav()
        render()
        list.scrollToPosition(0)
        // Лёгкое появление содержимого при смене экрана
        for (v in listOf(list, emptyView)) {
            v.alpha = 0f
            v.animate().alpha(1f).setDuration(180).setInterpolator(easing).start()
        }
    }

    // ---------- Нижнее меню ----------

    /**
     * Подсвечивает нужный пункт (экран плейлиста относится к «Плейлистам»)
     * и показывает «+» между пунктами только на списке плейлистов.
     */
    private fun syncBottomNav(animate: Boolean = true) {
        val onTracks = screen == Screen.TRACKS
        setNavSelected(navTracksPill, navTracksIcon, navTracksLabel, onTracks, animate)
        setNavSelected(navPlaylistsPill, navPlaylistsIcon, navPlaylistsLabel, !onTracks, animate)
        setAddButtonVisible(screen == Screen.PLAYLISTS, animate)
    }

    private fun setNavSelected(
        pill: View, icon: ImageView, label: TextView, selected: Boolean, animate: Boolean,
    ) {
        val color = MaterialColors.getColor(
            label,
            if (selected) com.google.android.material.R.attr.colorOnSurface
            else com.google.android.material.R.attr.colorOnSurfaceVariant
        )
        icon.imageTintList = ColorStateList.valueOf(color)
        label.setTextColor(color)

        val alpha = if (selected) 1f else 0f
        val scale = if (selected) 1f else 0.5f
        pill.animate().cancel()
        if (animate) {
            pill.animate().alpha(alpha).scaleX(scale)
                .setDuration(220).setInterpolator(easing).start()
        } else {
            pill.alpha = alpha
            pill.scaleX = scale
        }
    }

    /** «+» выезжает между пунктами: слот расширяется, кнопка вырастает с поворотом. */
    private fun setAddButtonVisible(show: Boolean, animate: Boolean) {
        if (addShown == show) return
        addShown = show
        addPlaylistBtn.isEnabled = show

        val targetWidth = if (show) (72 * resources.displayMetrics.density).toInt() else 0
        slotAnimator?.cancel()
        addPlaylistBtn.animate().cancel()

        if (!animate) {
            addSlot.layoutParams.width = targetWidth
            addSlot.requestLayout()
            val v = if (show) 1f else 0f
            addPlaylistBtn.scaleX = v
            addPlaylistBtn.scaleY = v
            addPlaylistBtn.alpha = v
            addPlaylistBtn.rotation = 0f
            return
        }

        slotAnimator = ValueAnimator.ofInt(addSlot.layoutParams.width, targetWidth).apply {
            duration = if (show) 280 else 220
            interpolator = easing
            addUpdateListener {
                addSlot.layoutParams.width = it.animatedValue as Int
                addSlot.requestLayout()
            }
            start()
        }

        if (show) {
            if (addPlaylistBtn.scaleX < 0.05f) addPlaylistBtn.rotation = -90f
            addPlaylistBtn.animate()
                .scaleX(1f).scaleY(1f).alpha(1f).rotation(0f)
                .setStartDelay(60).setDuration(280)
                .setInterpolator(OvershootInterpolator(1.5f))
                .start()
        } else {
            addPlaylistBtn.animate()
                .scaleX(0f).scaleY(0f).alpha(0f).rotation(-90f)
                .setStartDelay(0).setDuration(180)
                .setInterpolator(easing)
                .start()
        }
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
        sortBtn.visibility = if (screen == Screen.TRACKS) View.VISIBLE else View.GONE
        bgBtn.visibility = if (screen != Screen.PLAYLIST) View.VISIBLE else View.GONE
        val counts = playCounts.all()
        trackAdapter.setPlayCounts(counts)

        var emptyMessage: Int? = null
        grantButton.visibility = View.GONE

        when (screen) {
            Screen.TRACKS -> {
                screenTitle.setText(R.string.app_name)
                countText.text = if (libraryLoaded) {
                    resources.getQuantityString(R.plurals.track_count, library.size, library.size) +
                        " · " + getString(sortLabel(trackSort)).replaceFirstChar { it.lowercase() }
                } else ""
                actionsRow.visibility = View.GONE
                list.adapter = trackAdapter
                trackAdapter.submit(TrackSorter.sort(library, trackSort, counts))
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
                actionsRow.visibility = View.GONE
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
                actionsRow.visibility = View.VISIBLE
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

    // ---------- Отступы под системные панели ----------

    /** Шапка заходит под статус-бар, нижнее меню — под панель навигации. */
    private fun setupInsets() {
        val d = resources.displayMetrics.density
        val headerTop = headerPanel.paddingTop
        val headerBottom = headerPanel.paddingBottom
        val headerSide = headerPanel.paddingLeft
        val barHeight = (72 * d).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(appRoot) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            headerPanel.setPadding(
                headerSide + bars.left, headerTop + bars.top,
                headerSide + bars.right, headerBottom,
            )
            bottomBar.setPadding(bars.left, 0, bars.right, bars.bottom)
            bottomBar.layoutParams.height = barHeight + bars.bottom
            listContainer.setPadding(bars.left, 0, bars.right, 0)
            bottomBar.requestLayout()
            insets
        }
    }

    // ---------- Фон ----------

    private fun bgDim(): Int = (uiPrefs.getInt(KEY_BG_DIM, 50) / 5 * 5).coerceIn(0, 90)

    private fun applyBackground() {
        val sharp = bgSharp
        val blur = bgBlur
        val panels = listOf(headerPanel, playerBar, bottomBar)
        if (sharp == null || blur == null) {
            bgImage.visibility = View.GONE
            bgScrim.visibility = View.GONE
            panelDrawables = emptyList()
            val panel = ContextCompat.getColor(this, R.color.panel)
            panels.forEach { it.setBackgroundColor(panel) }
            listContainer.setBackgroundColor(
                MaterialColors.getColor(listContainer, com.google.android.material.R.attr.colorSurface)
            )
            return
        }
        bgImage.setImageBitmap(sharp)
        bgImage.visibility = View.VISIBLE
        bgScrim.visibility = View.VISIBLE
        listContainer.background = null
        panelDrawables = panels.map { v ->
            BackdropDrawable(blur, appRoot, v).also { v.background = it }
        }
        // Панели могут сдвинуться (появился плеер) — перерисовываем кусок фона под ними
        appRoot.viewTreeObserver.addOnGlobalLayoutListener {
            panelDrawables.forEach { it.invalidateSelf() }
        }
        updateDim(bgDim())
    }

    /** Список затемняется на [percent]%, панели — заметно сильнее. */
    private fun updateDim(percent: Int) {
        val d = percent / 100f
        bgScrim.alpha = d
        val panel = d + (1f - d) * 0.6f
        val overlay = Color.argb((panel * 255).toInt(), 6, 11, 15)
        panelDrawables.forEach { it.setOverlay(overlay) }
    }

    private fun showBackgroundDialog() {
        val v = layoutInflater.inflate(R.layout.dialog_background, null)
        val pickBtn = v.findViewById<Button>(R.id.bgPickBtn)
        val removeBtn = v.findViewById<Button>(R.id.bgRemoveBtn)
        val dimBlock = v.findViewById<View>(R.id.bgDimBlock)
        val slider = v.findViewById<Slider>(R.id.bgDimSlider)
        val hasBg = bgSharp != null

        pickBtn.setText(if (hasBg) R.string.bg_change else R.string.bg_pick)
        dimBlock.visibility = if (hasBg) View.VISIBLE else View.GONE
        removeBtn.visibility = if (hasBg) View.VISIBLE else View.GONE
        slider.value = bgDim().toFloat()
        slider.setLabelFormatter { "${it.toInt()}%" }
        // Затемнение меняется вживую, пока двигаешь ползунок
        slider.addOnChangeListener { _, value, _ -> updateDim(value.toInt()) }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.background)
            .setView(v)
            .setPositiveButton(R.string.done, null)
            .setOnDismissListener {
                if (hasBg) uiPrefs.edit().putInt(KEY_BG_DIM, slider.value.toInt()).apply()
            }
            .show()
        // Почти не затемняем экран за диалогом, чтобы было видно результат
        dialog.window?.setDimAmount(0.1f)

        pickBtn.setOnClickListener {
            dialog.dismiss()
            pickImage.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
        removeBtn.setOnClickListener {
            dialog.dismiss()
            ioExecutor.execute {
                Backdrop.remove(applicationContext)
                runOnUiThread { if (!isDestroyed) recreate() }
            }
        }
    }

    // ---------- Сортировка ----------

    private fun sortLabel(sort: TrackSort): Int = when (sort) {
        TrackSort.ARTIST -> R.string.sort_artist
        TrackSort.TITLE_EN_FIRST -> R.string.sort_title_en
        TrackSort.TITLE_RU_FIRST -> R.string.sort_title_ru
        TrackSort.MOST_PLAYED -> R.string.sort_most_played
    }

    private fun chooseSort() {
        val options = TrackSort.entries
        val labels = options.map { getString(sortLabel(it)) }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sort)
            .setSingleChoiceItems(labels, options.indexOf(trackSort)) { dialog, which ->
                trackSort = options[which]
                uiPrefs.edit().putString(KEY_SORT, trackSort.name).apply()
                dialog.dismiss()
                render()
                list.scrollToPosition(0)
            }
            .show()
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
        sortBtn = findViewById(R.id.sortBtn)
        bgBtn = findViewById(R.id.bgBtn)
        appRoot = findViewById(R.id.appRoot)
        bgImage = findViewById(R.id.bgImage)
        bgScrim = findViewById(R.id.bgScrim)
        headerPanel = findViewById(R.id.headerPanel)
        listContainer = findViewById(R.id.listContainer)
        bottomBar = findViewById(R.id.bottomBar)
        actionsRow = findViewById(R.id.actionsRow)
        navTracks = findViewById(R.id.navTracks)
        navPlaylists = findViewById(R.id.navPlaylists)
        navTracksPill = findViewById(R.id.navTracksPill)
        navPlaylistsPill = findViewById(R.id.navPlaylistsPill)
        navTracksIcon = findViewById(R.id.navTracksIcon)
        navPlaylistsIcon = findViewById(R.id.navPlaylistsIcon)
        navTracksLabel = findViewById(R.id.navTracksLabel)
        navPlaylistsLabel = findViewById(R.id.navPlaylistsLabel)
        addSlot = findViewById(R.id.addSlot)
        addPlaylistBtn = findViewById(R.id.addPlaylistBtn)
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

        navTracks.setOnClickListener {
            if (screen != Screen.TRACKS) showScreen(Screen.TRACKS)
        }
        // Из открытого плейлиста тап по «Плейлистам» возвращает к списку
        navPlaylists.setOnClickListener {
            if (screen != Screen.PLAYLISTS) showScreen(Screen.PLAYLISTS)
        }
        backBtn.setOnClickListener { showScreen(Screen.PLAYLISTS) }
        sortBtn.setOnClickListener { chooseSort() }
        bgBtn.setOnClickListener { showBackgroundDialog() }
        grantButton.setOnClickListener { requestPermissions() }
        addPlaylistBtn.setOnClickListener {
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
        private const val KEY_SORT = "track_sort"
        private const val KEY_BG_DIM = "bg_dim"
    }
}
