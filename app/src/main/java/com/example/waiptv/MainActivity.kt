package com.example.waiptv

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

@UnstableApi
class MainActivity : AppCompatActivity() {

    private data class Seg(val uri: Uri, val name: String, val modified: Long, val size: Long)

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var cbDelete: CheckBox
    private lateinit var tvStatus: TextView
    private lateinit var tvFolder: TextView
    private lateinit var btnResize: Button

    private val prefs by lazy { getSharedPreferences("waiptv", Context.MODE_PRIVATE) }
    private val ui = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadScheduledExecutor()
    private var watcher: ScheduledFuture<*>? = null

    private var treeUri: Uri? = null
    private val known = HashSet<String>()
    private val lastSize = HashMap<String, Long>()

    private var arrived = 0
    private var played = 0

    // أنماط ملاءمة أبعاد الشاشة المتوافقة تماماً مع Media3
    private val resizeModes = arrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT to "ملاءمة (Fit)",
        AspectRatioFrameLayout.RESIZE_MODE_FILL to "تعبئة الشاشة (Fill)",
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "تكبير/قص (Zoom)",
        AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH to "العرض ثابت (Fixed Width)",
        AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT to "الارتفاع ثابت (Fixed Height)"
    )
    private var currentResizeIdx = 0

    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: Exception) {
                    try {
                        contentResolver.takePersistableUriPermission(
                            uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: Exception) {
                    }
                }
                treeUri = uri
                prefs.edit().putString("tree", uri.toString()).apply()
                showFolder()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        player = ExoPlayer.Builder(this).build()
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                ui.post { dropPlayed() }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                updateStatus()
            }

            override fun onPlayerError(error: PlaybackException) {
                tvStatus.text = "خطأ تشغيل: ${error.errorCodeName}"
                if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.prepare()
                }
            }
        })

        prefs.getString("tree", null)?.let { treeUri = Uri.parse(it) }
        setContentView(buildModernUi())
        showFolder()
        updateStatus()
    }

    override fun onDestroy() {
        watcher?.cancel(false)
        exec.shutdownNow()
        player.release()
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildModernUi(): View {
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
        }

        playerView = PlayerView(this).apply {
            this.player = this@MainActivity.player
            setBackgroundColor(Color.BLACK)
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        root.addView(playerView, LinearLayout.LayoutParams(match, dp(250)))

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(20))
        }

        tvFolder = TextView(this).apply {
            setTextColor(Color.parseColor("#BBBBBB"))
            textSize = 13f
            setPadding(0, 0, 0, dp(12))
        }

        val btnPick = createStyledButton("📁 اختيار المجلد المراقَب", "#2196F3") {
            pickFolder.launch(null)
        }

        btnResize = createStyledButton("📺 الأبعاد: ملاءمة (Fit)", "#424242") {
            toggleResizeMode()
        }

        val btnStart = createStyledButton("▶ بدء المراقبة والتشغيل", "#4CAF50") {
            start()
        }

        val btnStop = createStyledButton("⏹ إيقاف المراقبة", "#F44336") {
            stop()
        }

        cbDelete = CheckBox(this).apply {
            text = "حذف الملفات تلقائياً بعد التشغيل"
            setTextColor(Color.WHITE)
            isChecked = true
            setPadding(dp(4), dp(8), 0, dp(12))
        }

        tvStatus = TextView(this).apply {
            setTextColor(Color.parseColor("#00E676"))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        }

        form.addView(btnPick)
        form.addView(tvFolder)
        form.addView(btnResize)
        form.addView(cbDelete)
        form.addView(btnStart)
        form.addView(btnStop)
        form.addView(tvStatus)

        root.addView(
            ScrollView(this).apply { addView(form, LinearLayout.LayoutParams(match, wrap)) },
            LinearLayout.LayoutParams(match, 0, 1f)
        )
        return root
    }

    private fun createStyledButton(label: String, colorHex: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 15f
            setBackgroundColor(Color.parseColor(colorHex))
            setOnClickListener { onClick() }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, 0, 0, dp(10))
            layoutParams = lp
        }
    }

    private fun toggleResizeMode() {
        currentResizeIdx = (currentResizeIdx + 1) % resizeModes.size
        val (mode, modeName) = resizeModes[currentResizeIdx]
        playerView.resizeMode = mode
        btnResize.text = "📺 الأبعاد: $modeName"
        toast("الوضع الحالي: $modeName")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun showFolder() {
        val t = treeUri
        tvFolder.text = if (t == null) {
            "لم يتم اختيار مجلد حتى الآن"
        } else {
            "المجلد الحالي: " + DocumentsContract.getTreeDocumentId(t).substringAfter(':')
        }
    }

    private fun updateStatus() {
        tvStatus.text = "الانتظار: ${player.mediaItemCount}  |  شُغّل: $played  |  وصل: $arrived"
    }

    private fun start() {
        if (treeUri == null) return toast("اختر مجلد التنزيل أولاً")
        startWatching()
        toast("بدأت مراقبة المجلد بنجاح")
    }

    private fun stop() {
        watcher?.cancel(false)
        toast("تم إيقاف المراقبة")
    }

    private fun startWatching() {
        watcher?.cancel(false)
        player.clearMediaItems()
        arrived = 0
        played = 0
        updateStatus()

        known.clear()
        lastSize.clear()

        watcher = exec.scheduleWithFixedDelay({ poll() }, 2, 2, TimeUnit.SECONDS)
    }

    private fun poll() {
        try {
            val tree = treeUri ?: return
            val fresh = listSegmentsRecursive(tree).filter { it.uri.toString() !in known }

            val ready = fresh.filter { it.size > 0 && lastSize[it.uri.toString()] == it.size }
            fresh.forEach { lastSize[it.uri.toString()] = it.size }

            ready.sortedWith(compareBy({ it.modified }, { it.name })).forEach { s ->
                known.add(s.uri.toString())
                lastSize.remove(s.uri.toString())
                ui.post { enqueue(s) }
            }
        } catch (e: Exception) {
            ui.post { tvStatus.text = "خطأ قراءة المجلد: ${e.message}" }
        }
    }

    private fun enqueue(s: Seg) {
        val wasEnded = player.playbackState == Player.STATE_ENDED
        player.addMediaItem(
            MediaItem.Builder()
                .setUri(s.uri)
                .setMediaId(s.uri.toString())
                .build()
        )
        arrived++
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        if (wasEnded) player.seekToDefaultPosition(player.mediaItemCount - 1)
        player.playWhenReady = true
        updateStatus()
    }

    private fun dropPlayed() {
        val idx = player.currentMediaItemIndex
        if (idx <= 0) return
        val ids = (0 until idx).map { player.getMediaItemAt(it).mediaId }
        player.removeMediaItems(0, idx)
        played += ids.size
        if (cbDelete.isChecked) exec.execute { ids.forEach { deleteDoc(it) } }
        updateStatus()
    }

    private fun deleteDoc(uriStr: String) {
        try {
            DocumentsContract.deleteDocument(contentResolver, Uri.parse(uriStr))
        } catch (_: Exception) {
        }
    }

    private fun listSegmentsRecursive(tree: Uri): List<Seg> {
        val out = ArrayList<Seg>()
        val parentId = DocumentsContract.getTreeDocumentId(tree)
        scanFolder(tree, parentId, out)
        return out
    }

    private fun scanFolder(tree: Uri, parentId: String, out: ArrayList<Seg>) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val cols = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_SIZE,
            Document.COLUMN_MIME_TYPE
        )
        contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val docId = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(4) ?: ""

                if (mime == Document.MIME_TYPE_DIR) {
                    scanFolder(tree, docId, out)
                } else if (!name.startsWith(".")) {
                    out.add(
                        Seg(
                            DocumentsContract.buildDocumentUriUsingTree(tree, docId),
                            name,
                            c.getLong(2),
                            c.getLong(3)
                        )
                    )
                }
            }
        }
    }
}
