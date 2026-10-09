package com.example.waiptv

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * مشغّل IPTV يعمل بواتساب فقط (بدون صلاحية إنترنت):
 *  1) يرسل أمر .playm3u إلى البوت عبر واتساب (رابط wa.me جاهز، تضغط إرسال).
 *  2) البوت يسجّل البث ويرسل أجزاء قصيرة كمستندات mp4.
 *  3) التطبيق يراقب مجلد "WhatsApp Documents" ويشغّل الأجزاء الجديدة بالتتابع.
 */
class MainActivity : AppCompatActivity() {

    private data class Seg(val uri: Uri, val name: String, val modified: Long, val size: Long)

    private lateinit var player: ExoPlayer
    private lateinit var etBot: EditText
    private lateinit var etUrl: EditText
    private lateinit var etMin: EditText
    private lateinit var etSeg: EditText
    private lateinit var cbDelete: CheckBox
    private lateinit var tvStatus: TextView
    private lateinit var tvFolder: TextView

    private val prefs by lazy { getSharedPreferences("waiptv", Context.MODE_PRIVATE) }
    private val ui = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadScheduledExecutor()
    private var watcher: ScheduledFuture<*>? = null

    private var treeUri: Uri? = null

    // تُستخدم من خيط exec فقط
    private val known = HashSet<String>()
    private val lastSize = HashMap<String, Long>()

    // تُستخدم من الخيط الرئيسي فقط
    private var arrived = 0
    private var played = 0

    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: Exception) {
                    try {
                        contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: Exception) {
                    }
                }
                treeUri = uri
                prefs.edit().putString("tree", uri.toString()).apply()
                showFolder()
            }
        }

    // ───────────────────────── دورة الحياة ─────────────────────────

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
        setContentView(buildUi())
        showFolder()
        updateStatus()
    }

    override fun onDestroy() {
        watcher?.cancel(false)
        exec.shutdownNow()
        player.release()
        super.onDestroy()
    }

    // ───────────────────────── الواجهة ─────────────────────────

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun field(hint: String, type: Int, text: String = ""): EditText =
        EditText(this).apply {
            this.hint = hint
            inputType = type
            setText(text)
            setSingleLine()
        }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }

    private fun buildUi(): View {
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val pv = PlayerView(this).apply {
            this.player = this@MainActivity.player
            setBackgroundColor(Color.BLACK)
        }
        root.addView(pv, LinearLayout.LayoutParams(match, dp(220)))

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }

        etBot = field(
            "رقم البوت مع رمز الدولة بدون + (فارغ = اختيار جهة اتصال)",
            InputType.TYPE_CLASS_PHONE,
            prefs.getString("bot", "") ?: ""
        )
        etUrl = field(
            "رابط البث (m3u8)",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        )
        etMin = field("المدة الكلية بالدقائق", InputType.TYPE_CLASS_NUMBER, "60")
        etSeg = field("مدة كل جزء بالدقائق (1 = أقل تأخير)", InputType.TYPE_CLASS_NUMBER, "1")

        cbDelete = CheckBox(this).apply {
            text = "حذف الأجزاء بعد تشغيلها (يوفر المساحة)"
            isChecked = true
        }

        tvFolder = TextView(this).apply { setPadding(0, dp(8), 0, dp(4)) }
        tvStatus = TextView(this).apply { setPadding(0, dp(8), 0, 0) }

        form.addView(etBot)
        form.addView(etUrl)
        form.addView(etMin)
        form.addView(etSeg)
        form.addView(cbDelete)
        form.addView(button("1) اختيار مجلد WhatsApp Documents") { pickFolder.launch(null) })
        form.addView(tvFolder)
        form.addView(button("2) ابدأ — يفتح واتساب، اضغط إرسال") { start() })
        form.addView(button("إيقاف") { stop() })
        form.addView(tvStatus)

        root.addView(
            ScrollView(this).apply { addView(form, LinearLayout.LayoutParams(match, wrap)) },
            LinearLayout.LayoutParams(match, 0, 1f)
        )
        return root
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun showFolder() {
        val t = treeUri
        tvFolder.text = if (t == null) {
            "لم يُختر مجلد بعد"
        } else {
            "المجلد: " + DocumentsContract.getTreeDocumentId(t).substringAfter(':')
        }
    }

    private fun updateStatus() {
        tvStatus.text = "في الانتظار: ${player.mediaItemCount}  |  شُغّل: $played  |  وصل: $arrived"
    }

    // ───────────────────────── الأوامر ─────────────────────────

    private fun start() {
        val url = etUrl.text.toString().trim()
        val min = etMin.text.toString().toIntOrNull() ?: 0
        val seg = etSeg.text.toString().toIntOrNull() ?: 0

        if (!url.startsWith("http")) return toast("أدخل رابط البث أولاً")
        if (min !in 1..360 || seg < 1 || seg > min) {
            return toast("المدة بين 1 و360، ومدة الجزء بين 1 والمدة الكلية")
        }
        if (treeUri == null) return toast("اختر مجلد WhatsApp Documents أولاً")

        startWatching()
        sendViaWhatsApp(".playm3u $url $min $seg")
    }

    private fun stop() {
        sendViaWhatsApp(".stopplaym3u")
        // نترك المراقبة دقيقة أخرى لاستلام الجزء الأخير الذي سيرسله البوت
        exec.schedule({ watcher?.cancel(false) }, 60, TimeUnit.SECONDS)
    }

    private fun sendViaWhatsApp(text: String) {
        val num = etBot.text.toString().filter { it.isDigit() }
        prefs.edit().putString("bot", num).apply()

        val uri = Uri.parse("https://wa.me/$num?text=" + Uri.encode(text))
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b", null)) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
                    if (pkg != null) setPackage(pkg)
                })
                return
            } catch (_: ActivityNotFoundException) {
            }
        }
        toast("واتساب غير مثبّت")
    }

    // ───────────────────────── المراقبة والتشغيل ─────────────────────────

    private fun startWatching() {
        watcher?.cancel(false)
        player.clearMediaItems()
        arrived = 0
        played = 0
        updateStatus()

        val tree = treeUri ?: return

        // لقطة بالملفات الموجودة الآن: أي ملف يظهر بعدها يُعدّ جزءاً جديداً
        exec.execute {
            known.clear()
            lastSize.clear()
            try {
                listSegments(tree).forEach { known.add(it.uri.toString()) }
            } catch (e: Exception) {
                ui.post { tvStatus.text = "تعذّر قراءة المجلد: ${e.message}" }
            }
        }
        watcher = exec.scheduleWithFixedDelay({ poll() }, 3, 3, TimeUnit.SECONDS)
    }

    private fun poll() {
        try {
            val tree = treeUri ?: return
            val fresh = listSegments(tree).filter { it.uri.toString() !in known }

            // الجزء جاهز عندما يثبت حجمه بين فحصين متتاليين (انتهى تنزيله)
            val ready = fresh.filter { it.size > 0 && lastSize[it.uri.toString()] == it.size }
            fresh.forEach { lastSize[it.uri.toString()] = it.size }

            ready.sortedWith(compareBy({ it.modified }, { it.name })).forEach { s ->
                known.add(s.uri.toString())
                lastSize.remove(s.uri.toString())
                ui.post { enqueue(s) }
            }
        } catch (e: Exception) {
            ui.post { tvStatus.text = "خطأ في قراءة المجلد: ${e.message}" }
        }
    }

    private fun enqueue(s: Seg) {
        val wasEnded = player.playbackState == Player.STATE_ENDED
        player.addMediaItem(
            MediaItem.Builder().setUri(s.uri).setMediaId(s.uri.toString()).build()
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

    // استعلام واحد يجلب كل الملفات بدل استعلام لكل ملف (أسرع بكثير)
    private fun listSegments(tree: Uri): List<Seg> {
        val parentId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val out = ArrayList<Seg>()
        val cols = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_SIZE
        )
        contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (!name.endsWith(".mp4", ignoreCase = true)) continue
                out.add(
                    Seg(
                        DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)),
                        name,
                        c.getLong(2),
                        c.getLong(3)
                    )
                )
            }
        }
        return out
    }
}
