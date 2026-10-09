package com.example.waiptv

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

@UnstableApi
class MainActivity : AppCompatActivity() {

    // ───────────────────────── Models ─────────────────────────

    private class Raw(val uri: Uri, val name: String, val mod: Long, val size: Long)

    /** key = رقم الجزء (أو وقت التعديل إن لم يوجد رقم)، strict = ترقيم تسلسلي يصلح لكشف الفجوات */
    private class Seg(val uri: Uri, val name: String, val key: Long, val strict: Boolean) {
        var arrivedAt = 0L
    }

    private class Palette(
        val name: String,
        val bg: Int,
        val surface: Int,
        val surface2: Int,
        val text: Int,
        val sub: Int,
        val dark: Boolean
    )

    private fun c(s: String) = Color.parseColor(s)

    private val palettes by lazy {
        listOf(
            Palette("داكن", c("#0E1014"), c("#181B22"), c("#232733"), c("#F2F4F8"), c("#98A1B3"), true),
            Palette("أمولد", c("#000000"), c("#0D0D0F"), c("#1A1A1F"), c("#FFFFFF"), c("#8A8F9C"), true),
            Palette("فاتح", c("#F3F5F9"), c("#FFFFFF"), c("#E8ECF3"), c("#14171F"), c("#5B6475"), false),
            Palette("أزرق ليلي", c("#0A1224"), c("#111D38"), c("#1B2B4D"), c("#EAF1FF"), c("#8FA3C7"), true)
        )
    }
    private val accents by lazy {
        listOf("#3D8BFF", "#8B5CF6", "#10B981", "#F59E0B", "#EF4444", "#EC4899").map { c(it) }
    }

    private val resizeModes = arrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT to "ملاءمة",
        AspectRatioFrameLayout.RESIZE_MODE_FILL to "تعبئة",
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "تكبير/قص"
    )
    private val bufferOptions = listOf(3, 5, 8, 12)
    private val waitOptions = listOf(0, 15, 30, 60, 120) // ثواني، 0 = انتظار بلا نهاية

    // ───────────────────────── Views ─────────────────────────

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var playerContainer: FrameLayout
    private lateinit var controlsLayout: ScrollView
    private lateinit var tvStatus: TextView
    private lateinit var tvDetail: TextView
    private lateinit var tvFolder: TextView
    private lateinit var tvOverlay: TextView
    private lateinit var tvArrived: TextView
    private lateinit var tvPlayed: TextView
    private lateinit var tvQueue: TextView
    private lateinit var tvSkipped: TextView

    // ───────────────────────── Settings ─────────────────────────

    private val prefs by lazy { getSharedPreferences("waiptv", Context.MODE_PRIVATE) }
    private var themeIdx = 0
    private var accentIdx = 0
    private var deleteAfter = true
    private var bufferSize = 5
    private var maxWaitSec = 60
    private var resizeIdx = 1
    private lateinit var p: Palette
    private var accent = 0

    // ───────────────────────── State ─────────────────────────

    private val ui = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadScheduledExecutor()
    private var watcher: ScheduledFuture<*>? = null

    @Volatile private var treeUri: Uri? = null
    private val seen: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val lastSig = ConcurrentHashMap<String, Long>()

    /** الأجزاء الواصلة وغير المُمرَّرة للمشغّل بعد، مرتبة حسب الرقم */
    private val pending = TreeMap<Long, Seg>()

    @Volatile private var session = 0
    private var watching = false
    private var initialized = false
    private var strict = false
    private var nextSeq = 0L
    private var gapSince = 0L
    private var lastArrival = 0L
    private var neededBuffer = 5
    private var isBufferReady = false
    private var isFullScreen = false

    private var arrived = 0
    private var played = 0
    private var skipped = 0

    private val numRe = Regex("(\\d+)(?!.*\\d)")

    private companion object {
        const val IDLE_START_MS = 6000L   // لو توقف الوصول، ابدأ بما لديك
        const val STALE_DROP_MS = 8000L   // جزء متأخر جداً يُهمل
        const val STALE_RESTART = 5       // عدد أجزاء قديمة دفعة واحدة = إعادة ترقيم البث
        const val REBUFFER = 2
    }

    // ───────────────────────── Folder picker ─────────────────────────

    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
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

    // ───────────────────────── Lifecycle ─────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        themeIdx = prefs.getInt("theme", 0).coerceIn(0, palettes.size - 1)
        accentIdx = prefs.getInt("accent", 0).coerceIn(0, accents.size - 1)
        deleteAfter = prefs.getBoolean("del", true)
        bufferSize = prefs.getInt("buf", 5)
        maxWaitSec = prefs.getInt("wait", 60)
        resizeIdx = prefs.getInt("resize", 1).coerceIn(0, resizeModes.size - 1)
        treeUri = prefs.getString("tree", null)?.let { Uri.parse(it) }
        neededBuffer = bufferSize

        player = ExoPlayer.Builder(this).build().apply {
            playWhenReady = false
            repeatMode = Player.REPEAT_MODE_OFF
        }
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                ui.post { dropPlayed() }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && watching && initialized) {
                    ui.post { handleUnderrun() }
                }
                updateStatus()
            }

            override fun onPlayerError(error: PlaybackException) {
                ui.post { handleBrokenItem() }
            }
        })

        onBackPressedDispatcher.addCallback(this) {
            if (isFullScreen) toggleFullScreen() else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }

        applyTheme()
    }

    override fun onDestroy() {
        watching = false
        ui.removeCallbacksAndMessages(null)
        watcher?.cancel(false)
        exec.shutdownNow()
        player.release()
        super.onDestroy()
    }

    // ───────────────────────── Watching & ordering ─────────────────────────

    private fun start() {
        if (treeUri == null) return toast("اختر المجلد أولاً")
        session++
        val sess = session
        watcher?.cancel(false)
        ui.removeCallbacks(tick)

        player.stop()
        player.clearMediaItems()
        player.playWhenReady = false
        pending.clear()
        seen.clear()
        lastSig.clear()
        arrived = 0; played = 0; skipped = 0
        initialized = false
        isBufferReady = false
        gapSince = 0L
        nextSeq = 0L
        neededBuffer = bufferSize
        lastArrival = SystemClock.elapsedRealtime()
        watching = true

        watcher = exec.scheduleWithFixedDelay({ poll(sess) }, 0, 2, TimeUnit.SECONDS)
        ui.post(tick)
        updateStatus()
        toast("بدأت المراقبة")
    }

    private fun stop() {
        watching = false
        session++
        watcher?.cancel(false)
        ui.removeCallbacks(tick)
        player.stop()
        player.clearMediaItems()
        pending.clear()
        initialized = false
        isBufferReady = false
        gapSince = 0L
        updateStatus()
        toast("تم الإيقاف")
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!watching) return
            val now = SystemClock.elapsedRealtime()
            if (!initialized && pending.isNotEmpty() && now - lastArrival > IDLE_START_MS) initialize()
            pump()
            if (initialized && !isBufferReady && player.mediaItemCount > 0 &&
                now - lastArrival > IDLE_START_MS
            ) startPlayback()
            updateStatus()
            ui.postDelayed(this, 1000)
        }
    }

    // ---- خيط المسح (خلفية) ----
    private fun poll(sess: Int) {
        try {
            val tree = treeUri ?: return
            val raw = ArrayList<Raw>()
            scanFolder(tree, DocumentsContract.getTreeDocumentId(tree), raw)

            val ready = ArrayList<Seg>()
            for (r in raw) {
                val k = r.uri.toString()
                if (k in seen) continue
                val sig = r.size * 31 + r.mod
                // الملف مستقر = نفس الحجم/التعديل في مسحين متتاليين
                if (sig != 0L && lastSig[k] == sig) {
                    seen.add(k)
                    lastSig.remove(k)
                    ready.add(makeSeg(r))
                } else {
                    lastSig[k] = sig
                }
            }
            if (ready.isNotEmpty()) ui.post { onSegments(ready, sess) }
        } catch (e: Exception) {
            ui.post { tvDetail.text = "خطأ قراءة المجلد: ${e.message}" }
        }
    }

    private fun makeSeg(r: Raw): Seg {
        val base = r.name.substringBeforeLast('.', r.name)
        val n = numRe.find(base)?.value?.takeIf { it.length <= 18 }?.toLongOrNull()
        return if (n != null) Seg(r.uri, r.name, n, n < 1_000_000_000L)
        else Seg(r.uri, r.name, r.mod, false)
    }

    // ---- الخيط الرئيسي ----
    private fun onSegments(list: List<Seg>, sess: Int) {
        if (sess != session || !watching) return
        val now = SystemClock.elapsedRealtime()
        for (s in list) {
            s.arrivedAt = now
            var k = s.key
            if (s.strict) {
                if (pending.containsKey(k)) continue // نفس الرقم مرتين = تكرار
            } else {
                while (pending.containsKey(k)) k++
            }
            pending[k] = s
        }
        lastArrival = now
        if (!initialized && pending.size >= bufferSize) initialize()
        pump()
        updateStatus()
    }

    /** يحدد نقطة البداية بعد تجميع عدد كافٍ ليُرتَّب البدء صحيحاً (1 ثم 2 ثم ... 10) */
    private fun initialize() {
        initialized = true
        strict = pending.values.all { it.strict }
        nextSeq = if (strict && pending.isNotEmpty()) pending.firstKey() else 0L
        neededBuffer = bufferSize
        isBufferReady = false
        gapSince = 0L
    }

    /**
     * يمرّر للمشغّل فقط الجزء التالي بالترتيب.
     * إن غاب جزء: ينتظر وصوله (بدل القفز) حتى المهلة المحددة (أو للأبد).
     */
    private fun pump() {
        if (!initialized) return
        val now = SystemClock.elapsedRealtime()

        if (!strict) {
            while (pending.isNotEmpty()) feed(pending.pollFirstEntry()!!.value)
            return
        }

        // أجزاء قديمة (أصغر من المتوقع): إما متأخرة/مكررة فتُهمل، أو البث أعاد الترقيم
        val stale = pending.headMap(nextSeq)
        if (stale.size >= STALE_RESTART) {
            nextSeq = pending.firstKey()
            gapSince = 0L
        } else if (stale.isNotEmpty()) {
            val it = stale.entries.iterator()
            while (it.hasNext()) if (now - it.next().value.arrivedAt > STALE_DROP_MS) it.remove()
        }

        while (true) {
            val e = pending.ceilingEntry(nextSeq) ?: break
            if (e.key == nextSeq) {
                pending.remove(e.key)
                feed(e.value)
                nextSeq++
                gapSince = 0L
            } else {
                // فجوة: الجزء nextSeq لم يصل بعد
                if (gapSince == 0L) gapSince = now
                if (maxWaitSec > 0 && now - gapSince >= maxWaitSec * 1000L) {
                    skipped += (e.key - nextSeq).coerceAtMost(9999L).toInt()
                    nextSeq = e.key
                    gapSince = 0L
                } else break
            }
        }
        if (pending.ceilingEntry(nextSeq) == null) gapSince = 0L
    }

    private fun feed(s: Seg) {
        player.addMediaItem(
            MediaItem.Builder().setUri(s.uri).setMediaId(s.uri.toString()).build()
        )
        arrived++
        when (player.playbackState) {
            Player.STATE_IDLE -> player.prepare()
            Player.STATE_ENDED -> player.seekToDefaultPosition(player.mediaItemCount - 1)
            else -> {}
        }
        if (!isBufferReady && player.mediaItemCount >= neededBuffer) startPlayback()
    }

    private fun startPlayback() {
        isBufferReady = true
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.playWhenReady = true
    }

    /** نفدت الأجزاء: لا نقطع البث، نوقف مؤقتاً وننتظر وصول أجزاء جديدة ثم نكمل */
    private fun handleUnderrun() {
        if (!watching || !initialized) return
        val n = player.mediaItemCount
        if (n == 0) return
        val ids = (0 until n).map { player.getMediaItemAt(it).mediaId }
        player.clearMediaItems()
        played += n
        deleteAll(ids)
        player.playWhenReady = false
        isBufferReady = false
        neededBuffer = minOf(REBUFFER, bufferSize)
        pump()
        updateStatus()
    }

    private fun handleBrokenItem() {
        val idx = player.currentMediaItemIndex
        if (idx in 0 until player.mediaItemCount) {
            val id = player.getMediaItemAt(idx).mediaId
            player.removeMediaItem(idx)
            skipped++
            deleteAll(listOf(id))
            toast("تم تخطي جزء تالف")
        }
        if (player.mediaItemCount == 0) {
            isBufferReady = false
            neededBuffer = minOf(REBUFFER, bufferSize)
            player.playWhenReady = false
        } else {
            player.playWhenReady = isBufferReady
        }
        player.prepare()
        updateStatus()
    }

    private fun dropPlayed() {
        val idx = player.currentMediaItemIndex
        if (idx <= 0) return
        val ids = (0 until idx).map { player.getMediaItemAt(it).mediaId }
        player.removeMediaItems(0, idx)
        played += ids.size
        deleteAll(ids)
        updateStatus()
    }

    private fun deleteAll(ids: List<String>) {
        if (!deleteAfter) return
        exec.execute {
            ids.forEach { id ->
                try {
                    if (DocumentsContract.deleteDocument(contentResolver, Uri.parse(id))) {
                        seen.remove(id) // لو أُعيد إنشاء ملف بنفس الاسم لاحقاً يُقبل
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    // ───────────────────────── Folder scanning ─────────────────────────

    private fun scanFolder(tree: Uri, parentId: String, out: ArrayList<Raw>) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val cols = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_SIZE,
            Document.COLUMN_MIME_TYPE
        )
        contentResolver.query(children, cols, null, null, null)?.use { cur ->
            while (cur.moveToNext()) {
                val docId = cur.getString(0) ?: continue
                val name = cur.getString(1) ?: continue
                val mime = cur.getString(4) ?: ""
                if (mime == Document.MIME_TYPE_DIR) {
                    scanFolder(tree, docId, out)
                } else if (!name.startsWith(".") && !isTemp(name)) {
                    out.add(
                        Raw(
                            DocumentsContract.buildDocumentUriUsingTree(tree, docId),
                            name, cur.getLong(2), cur.getLong(3)
                        )
                    )
                }
            }
        }
    }

    private fun isTemp(n: String): Boolean {
        val l = n.lowercase()
        return l.endsWith(".tmp") || l.endsWith(".part") || l.endsWith(".crdownload") ||
            l.endsWith(".download") || l.endsWith(".temp")
    }

    // ───────────────────────── Status ─────────────────────────

    private fun updateStatus() {
        if (!::tvStatus.isInitialized) return
        val now = SystemClock.elapsedRealtime()
        val green = c("#22C55E")
        val warn = c("#F59E0B")

        tvArrived.text = arrived.toString()
        tvPlayed.text = played.toString()
        tvQueue.text = (player.mediaItemCount + pending.size).toString()
        tvSkipped.text = skipped.toString()

        var detail = ""
        val (text, color) = when {
            !watching -> "متوقف" to p.sub
            !initialized -> "⏳ تجميع ${pending.size}/$bufferSize" to warn
            gapSince != 0L -> {
                val waited = (now - gapSince) / 1000
                detail = if (maxWaitSec > 0) "ينتظر الجزء #$nextSeq منذ ${waited}ث (المهلة ${maxWaitSec}ث)"
                else "ينتظر الجزء #$nextSeq منذ ${waited}ث (بلا مهلة)"
                "⏳ بانتظار جزء" to warn
            }
            !isBufferReady -> "⏳ تخزين ${player.mediaItemCount}/$neededBuffer" to warn
            else -> "▶ يعمل" to green
        }
        if (detail.isEmpty() && watching && initialized && strict) detail = "التالي المتوقع: #$nextSeq"
        tvStatus.text = text
        tvStatus.setTextColor(color)
        tvStatus.background = rounded(ColorUtils.setAlphaComponent(color, 40), 20)
        tvDetail.text = detail

        val waiting = watching && initialized && (gapSince != 0L || !isBufferReady)
        tvOverlay.visibility = if (waiting) View.VISIBLE else View.GONE
        tvOverlay.text = if (gapSince != 0L) "⏳ بانتظار الجزء #$nextSeq…" else "⏳ جاري التخزين المؤقت…"
    }

    // ───────────────────────── Theme ─────────────────────────

    private fun applyTheme() {
        p = palettes[themeIdx]
        accent = accents[accentIdx]
        window.statusBarColor = p.bg
        window.navigationBarColor = p.bg
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !p.dark
            isAppearanceLightNavigationBars = !p.dark
        }
        if (::playerView.isInitialized) playerView.player = null
        setContentView(buildUi())
        showFolder()
        updateStatus()
    }

    // ───────────────────────── UI building ─────────────────────────

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, r: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat()
    }

    private fun lpx(w: Int, h: Int, t: Int = 0, b: Int = 0, s: Int = 0, e: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply {
            setMargins(0, dp(t), 0, dp(b)); marginStart = dp(s); marginEnd = dp(e)
        }

    private fun playerHeight(): Int {
        val m = resources.displayMetrics
        return minOf(m.widthPixels * 9 / 16, (m.heightPixels * 0.5f).toInt())
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
            fitsSystemWindows = true
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        // ── Player ──
        playerContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        playerView = PlayerView(this).apply {
            this.player = this@MainActivity.player
            setBackgroundColor(Color.BLACK)
            resizeMode = resizeModes[resizeIdx].first
            setKeepContentOnPlayerReset(true)
            setShowNextButton(false)
            setShowPreviousButton(false)
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setFullscreenButtonClickListener { toggleFullScreen() }
        }
        tvOverlay = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = rounded(0xAA000000.toInt(), 20)
            visibility = View.GONE
        }
        playerContainer.addView(playerView, FrameLayout.LayoutParams(MATCH, MATCH))
        playerContainer.addView(
            tvOverlay,
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                .apply { topMargin = dp(12) }
        )
        val ph = if (isFullScreen) MATCH else playerHeight()
        root.addView(playerContainer, LinearLayout.LayoutParams(MATCH, ph))

        // ── Controls ──
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }

        // Header
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("WAI TV", 22f, p.text, true))
            addView(label("مشغّل أجزاء البث المباشر", 12f, p.sub, false))
        }
        tvStatus = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(7), dp(14), dp(7))
        }
        col.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(titleCol, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(tvStatus, LinearLayout.LayoutParams(WRAP, WRAP))
        }, lpx(MATCH, WRAP, b = 14))

        // Stats
        val (t1, v1) = tile("وصل"); tvArrived = v1
        val (t2, v2) = tile("شُغّل"); tvPlayed = v2
        val (t3, v3) = tile("بالطابور"); tvQueue = v3
        val (t4, v4) = tile("تخطّي"); tvSkipped = v4
        tvDetail = label("", 12f, p.sub, false).apply { gravity = Gravity.CENTER }
        col.addView(card().apply {
            addView(rowOf(t1, t2, t3, t4))
            addView(tvDetail, lpx(MATCH, WRAP, t = 10))
        })

        // Start / Stop
        col.addView(
            rowOf(
                btn("▶  بدء", accent, Color.WHITE) { start() },
                btn("⏹  إيقاف", p.surface2, p.text) { stop() }
            ),
            lpx(MATCH, WRAP, b = 12)
        )

        // Folder
        tvFolder = label("", 13f, p.sub, false)
        col.addView(card().apply {
            addView(title("المصدر"))
            addView(tvFolder, lpx(MATCH, WRAP, b = 10))
            addView(btn("📁  اختيار المجلد المراقَب", p.surface2, p.text) { pickFolder.launch(null) })
        })

        // Playback
        col.addView(card().apply {
            addView(title("التشغيل"))

            val sw = SwitchCompat(this@MainActivity).apply {
                isChecked = deleteAfter
                val st = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
                thumbTintList = ColorStateList(st, intArrayOf(accent, p.sub))
                trackTintList = ColorStateList(
                    st, intArrayOf(ColorUtils.setAlphaComponent(accent, 110), p.surface2)
                )
                setOnCheckedChangeListener { _, v ->
                    deleteAfter = v
                    prefs.edit().putBoolean("del", v).apply()
                }
            }
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(label("حذف الجزء تلقائياً بعد تشغيله", 14f, p.text, false),
                    LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(sw)
            }, lpx(MATCH, WRAP, b = 14))

            addView(label("حجم التخزين المؤقت قبل البدء (أجزاء)", 13f, p.sub, false), lpx(MATCH, WRAP, b = 6))
            addView(
                chips(bufferOptions.map { "$it" }, bufferOptions.indexOf(bufferSize).coerceAtLeast(0)) {
                    bufferSize = bufferOptions[it]
                    prefs.edit().putInt("buf", bufferSize).apply()
                }, lpx(MATCH, WRAP, b = 14)
            )

            addView(label("مهلة انتظار الجزء الغائب", 13f, p.sub, false), lpx(MATCH, WRAP, b = 2))
            addView(
                label("المشغّل يتوقف وينتظر وصوله بدل القفز. بعد المهلة يتخطّاه.", 11f, p.sub, false),
                lpx(MATCH, WRAP, b = 6)
            )
            addView(
                chips(
                    waitOptions.map { if (it == 0) "∞ دائماً" else "${it}ث" },
                    waitOptions.indexOf(maxWaitSec).coerceAtLeast(0)
                ) {
                    maxWaitSec = waitOptions[it]
                    prefs.edit().putInt("wait", maxWaitSec).apply()
                }, lpx(MATCH, WRAP, b = 14)
            )

            addView(label("أبعاد الفيديو", 13f, p.sub, false), lpx(MATCH, WRAP, b = 6))
            addView(
                chips(resizeModes.map { it.second }, resizeIdx) {
                    resizeIdx = it
                    playerView.resizeMode = resizeModes[it].first
                    prefs.edit().putInt("resize", it).apply()
                }, lpx(MATCH, WRAP, b = 14)
            )

            addView(btn("⛶  ملء الشاشة", p.surface2, p.text) { toggleFullScreen() })
        })

        // Theme
        col.addView(card().apply {
            addView(title("المظهر"))
            addView(
                chips(palettes.map { it.name }, themeIdx) {
                    themeIdx = it
                    prefs.edit().putInt("theme", it).apply()
                    ui.post { applyTheme() }
                }, lpx(MATCH, WRAP, b = 14)
            )
            addView(label("لون التمييز", 13f, p.sub, false), lpx(MATCH, WRAP, b = 8))
            addView(swatches())
        })

        controlsLayout = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(col, LinearLayout.LayoutParams(MATCH, WRAP))
            visibility = if (isFullScreen) View.GONE else View.VISIBLE
        }
        root.addView(controlsLayout, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return root
    }

    // ---- UI helpers ----

    private fun label(t: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun title(t: String) = label(t, 15f, p.text, true).apply {
        layoutParams = lpx(MATCH, WRAP, b = 12)
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(p.surface, 20)
        setPadding(dp(16), dp(14), dp(16), dp(16))
        layoutParams = lpx(MATCH, WRAP, b = 12)
    }

    private fun tile(l: String): Pair<View, TextView> {
        val v = label("0", 20f, p.text, true).apply { gravity = Gravity.CENTER }
        val lab = label(l, 11f, p.sub, false).apply { gravity = Gravity.CENTER }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(12), dp(4), dp(12))
            background = rounded(p.surface2, 14)
            addView(v); addView(lab)
        }
        return box to v
    }

    private fun rowOf(vararg views: View) = LinearLayout(this).apply {
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                if (i > 0) marginStart = dp(8)
            })
        }
    }

    private fun btn(text: String, bg: Int, fg: Int, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        setTextColor(fg)
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), rounded(bg, 14), null)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun chips(options: List<String>, selected: Int, onSelect: (Int) -> Unit): View {
        val box = LinearLayout(this)
        val views = ArrayList<TextView>()
        fun style(sel: Int) = views.forEachIndexed { i, t ->
            val on = i == sel
            t.setTextColor(if (on) Color.WHITE else p.sub)
            t.background = rounded(if (on) accent else p.surface2, 20)
        }
        options.forEachIndexed { i, l ->
            val t = TextView(this).apply {
                text = l; textSize = 13f
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setOnClickListener { style(i); onSelect(i) }
            }
            views.add(t)
            box.addView(t, lpx(WRAP, WRAP, e = 8))
        }
        style(selected)
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(box)
        }
    }

    private fun swatches(): View {
        val box = LinearLayout(this)
        val views = ArrayList<TextView>()
        fun style(sel: Int) = views.forEachIndexed { i, t ->
            t.text = if (i == sel) "✓" else ""
        }
        accents.forEachIndexed { i, col ->
            val t = TextView(this).apply {
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(col) }
                setOnClickListener {
                    accentIdx = i
                    prefs.edit().putInt("accent", i).apply()
                    ui.post { applyTheme() }
                }
            }
            views.add(t)
            box.addView(t, lpx(dp(38), dp(38), e = 10).apply { width = dp(38); height = dp(38) })
        }
        style(accentIdx)
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(box)
        }
    }

    // ───────────────────────── Fullscreen & misc ─────────────────────────

    private fun toggleFullScreen() {
        isFullScreen = !isFullScreen
        val params = playerContainer.layoutParams as LinearLayout.LayoutParams
        val ctrl = WindowInsetsControllerCompat(window, window.decorView)
        if (isFullScreen) {
            controlsLayout.visibility = View.GONE
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            params.height = ViewGroup.LayoutParams.MATCH_PARENT
            ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctrl.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controlsLayout.visibility = View.VISIBLE
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            params.height = playerHeight()
            ctrl.show(WindowInsetsCompat.Type.systemBars())
        }
        playerContainer.layoutParams = params
    }

    private fun showFolder() {
        if (!::tvFolder.isInitialized) return
        val t = treeUri
        tvFolder.text = if (t == null) "لم يتم اختيار مجلد بعد"
        else "المجلد: " + DocumentsContract.getTreeDocumentId(t).substringAfter(':')
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
