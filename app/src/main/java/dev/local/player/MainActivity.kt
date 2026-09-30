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
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var countText: TextView
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

    private val adapter = TrackAdapter { position -> playFrom(position) }
    private var tracks: List<Track> = emptyList()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val controller: MediaController?
        get() = controllerFuture?.takeIf { it.isDone && !it.isCancelled }?.get()

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var userSeeking = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasAudioPermission()) loadLibrary() else showNoPermission()
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()

        if (hasAudioPermission()) loadLibrary() else requestPermissions()
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

    private fun showNoPermission() {
        list.visibility = View.GONE
        emptyView.visibility = View.VISIBLE
        emptyText.setText(R.string.need_permission)
        grantButton.visibility = View.VISIBLE
    }

    private fun loadLibrary() {
        emptyView.visibility = View.GONE
        ioExecutor.execute {
            val loaded = MusicLibrary.load(applicationContext)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                tracks = loaded
                adapter.submit(loaded)
                countText.text = resources.getQuantityString(
                    R.plurals.track_count, loaded.size, loaded.size
                )
                if (loaded.isEmpty()) {
                    list.visibility = View.GONE
                    emptyView.visibility = View.VISIBLE
                    emptyText.setText(R.string.no_tracks)
                    grantButton.visibility = View.GONE
                } else {
                    list.visibility = View.VISIBLE
                }
                updatePlayerUi()
            }
        }
    }

    // ---------- Воспроизведение ----------

    /** Ставим в очередь всю библиотеку и начинаем с выбранного трека. */
    private fun playFrom(position: Int) {
        val c = controller ?: return
        c.setMediaItems(tracks.map { it.toMediaItem() }, position, 0L)
        c.prepare()
        c.play()
    }

    private fun bindViews() {
        countText = findViewById(R.id.countText)
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
        list.adapter = adapter

        grantButton.setOnClickListener { requestPermissions() }

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
            adapter.setCurrent(null)
            return
        }
        playerBar.visibility = View.VISIBLE
        nowTitle.text = item.mediaMetadata.title ?: ""
        nowArtist.text = item.mediaMetadata.artist ?: ""
        adapter.setCurrent(item.mediaId)

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
}
