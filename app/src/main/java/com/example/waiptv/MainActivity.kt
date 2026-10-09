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

class MainActivity : AppCompatActivity() {

    private data class Seg(val uri: Uri, val name: String, val modified: Long, val size: Long)

    private lateinit var player: ExoPlayer
    private lateinit var etBot: EditText
    private lateinit var etCmd: EditText
    private lateinit var etStop: EditText
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

    private val known = HashSet<String>()
    private val lastSize = HashMap<String, Long>()

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
            "رقم المستلِم/البوت (فارغ = اختيار جهة اتصال)",
            InputType.TYPE_CLASS_PHONE,
            prefs.getString("bot", "") ?: ""
        )
        etCmd = field(
            "قالب النص/الأمر: استخدم {url} {min} {seg}",
            InputType.TYPE_CLASS_TEXT,
            prefs.getString("cmd", ".stream {url} {min} {seg}") ?: ""
        )
        etStop = field(
            "أمر الإيقاف (اتركه فارغاً إن لم يوجد)",
            InputType.TYPE_CLASS_TEXT,
            prefs.getString("stop", ".stopstream") ?: ""
        )
        etUrl = field(
            "رابط البث أو اسم المحتوى المطلوب",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        )
        etMin = field("المدة الكلية بالدقائق ({min})", InputType.TYPE_CLASS_NUMBER, "60")
        etSeg = field("مدة كل جزء بالدقائق ({seg})", InputType.TYPE_CLASS_NUMBER, "1")

        cbDelete = CheckBox(this).apply {
            text = "حذف أي ملف فور الانتهاء من تشغيله"
            isChecked = true
        }

        tvFolder = TextView(this).apply { setPadding(0, dp(8), 0, dp(4)) }
        tvStatus = TextView(this).apply { setPadding(0, dp(8), 0, 0) }

        form.addView(etBot)
        form.addView(etCmd)
        form.addView(etStop)
        form.addView(etUrl)
        form.addView(etMin)
        form.addView(etSeg)
        form.addView(cbDelete)
        form.addView(button("1) اختيار المجلد (Sent أو WhatsApp Documents)") { pickFolder.launch(null) })
        form.addView(tvFolder)
        form.addView(button("2) بدء مراقبة المجلد وتشغيل البث") { start() })
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
            "المجلد المراقَب: " + DocumentsContract.getTreeDocumentId(t).substringAfter(':')
        }
    }

    private fun updateStatus() {
        tvStatus.text = "في الانتظار: ${player.mediaItemCount}  |  شُغّل: $played  |  وصل: $arrived"
    }

    private fun start() {
        val tpl = etCmd.text.toString().trim()
        val url = etUrl.text.toString().trim()
        val min = etMin.text.toString().toIntOrNull() ?: 0
        val seg = etSeg.text.toString().toIntOrNull() ?: 0

        if (treeUri == null) return toast("اختر مجلد الاستقبال أولاً")

        prefs.edit()
            .putString("cmd", tpl)
            .putString("stop", etStop.text.toString().trim())
            .apply()

        val cmd = tpl
            .replace("{url}", url)
            .replace("{min}", min.toString())
            .replace("{seg}", seg.toString())

        startWatching()
        if (cmd.isNotEmpty()) {
            sendViaWhatsApp(cmd)
        }
    }

    private fun stop() {
        val stopCmd = etStop.text.toString().trim()
        if (stopCmd.isNotEmpty()) {
            sendViaWhatsApp(stopCmd)
            exec.schedule({ watcher?.cancel(false) }, 60, TimeUnit.SECONDS)
        } else {
            watcher?.cancel(false)
        }
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
        toast("تطبيق واتساب غير مثبّت")
    }

    private fun startWatching() {
        watcher?.cancel(false)
        player.clearMediaItems()
        arrived = 0
        played = 0
        updateStatus()

        // تفريغ السجلات للبدء بقراءة أي ملفات متواجدة داخل المجلد فوراً
        known.clear()
        lastSize.clear()

        watcher = exec.scheduleWithFixedDelay({ poll() }, 2, 2, TimeUnit.SECONDS)
    }

    private fun poll() {
        try {
            val tree = treeUri ?: return
            val fresh = listSegmentsRecursive(tree).filter { it.uri.toString() !in known }

            // التثبت من اكتمال التنزيل عبر ثبات الحجم بين فحصين
            val ready = fresh.filter { it.size > 0 && lastSize[it.uri.toString()] == it.size }
            fresh.forEach { lastSize[it.uri.toString()] = it.size }

            ready.sortedWith(compareBy({ it.modified }, { it.name })).forEach { s ->
                known.add(s.uri.toString())
                lastSize.remove(s.uri.toString())
                ui.post { enqueue(s) }
            }
        } catch (e: Exception) {
            ui.post { tvStatus.text = "خطأ أثناء قراءة المجلد: ${e.message}" }
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

    /**
     * استعلام يجلب كافة الملفات بما في ذلك الملفات الموجودة داخل مجلدات فرعية مثل Sent
     */
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
                    // الانتقال ودخول المجلدات الفرعية (مثل مجلد Sent)
                    scanFolder(tree, docId, out)
                } else if (!name.startsWith(".")) {
                    // حفظ كل الملفات بغض النظر عن النوع أو الامتداد
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
