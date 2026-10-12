package com.spaceman.vmloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

class OverlayService : Service() {

    companion object {
        @Volatile
        var instance: OverlayService? = null
        private const val CHANNEL = "vm_overlay"
        private val BLUE = Color.parseColor("#1E6BFF")
        private val CYAN = Color.parseColor("#00C8FF")
        private val SILVER = Color.parseColor("#C9D3E6")
        private val INK = Color.parseColor("#05080F")
        private val FIELD = Color.parseColor("#0C1630")
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var root: FrameLayout
    private lateinit var bubble: ImageView
    private lateinit var panel: LinearLayout
    private lateinit var lp: WindowManager.LayoutParams
    private lateinit var etTarget: EditText
    private lateinit var btnPlay: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvLevel: TextView
    private lateinit var engine: Engine
    private val prefs by lazy { getSharedPreferences("vm", MODE_PRIVATE) }

    @Volatile
    private var occ = Rect()

    private val baseFlags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startAsForeground()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        engine = Engine(this, this)
        buildUi()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        engine.stop()
        try {
            wm.removeView(root)
        } catch (_: Exception) {
        }
        instance = null
        super.onDestroy()
    }

    // ---------- dipanggil dari Engine (thread mana pun) ----------

    fun status(s: String) {
        main.post { tvStatus.text = s }
    }

    fun progress(levels: Int, moves: Int) {
        main.post { tvLevel.text = "Level selesai: $levels  |  Langkah: $moves" }
    }

    fun onStopped() {
        main.post { btnPlay.text = "▶  Auto Play" }
    }

    fun hide(h: Boolean) {
        main.post { root.visibility = if (h) View.INVISIBLE else View.VISIBLE }
    }

    fun occupied(): Rect = Rect(occ)

    fun screenSize(): Pair<Int, Int> {
        val b = wm.currentWindowMetrics.bounds
        return Pair(b.width(), b.height())
    }

    // ---------- UI ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(fill: Int, radius: Int, stroke: Int = 0, sw: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius.toFloat()
            if (sw > 0) setStroke(sw, stroke)
        }

    private fun oval(fill: Int, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            setStroke(dp(2), stroke)
        }

    private fun roundLogo(size: Int): ImageView = ImageView(this).apply {
        setImageResource(R.drawable.vm_logo)
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = oval(Color.BLACK, BLUE)
        clipToOutline = true
        outlineProvider = ViewOutlineProvider.BACKGROUND
        layoutParams = LinearLayout.LayoutParams(size, size)
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun button(text: String, fill: Int, textColor: Int = Color.WHITE): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(textColor)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(11), dp(12), dp(11))
            background = rounded(fill, dp(12))
        }

    private fun buildUi() {
        root = FrameLayout(this)

        bubble = ImageView(this).apply {
            setImageResource(R.drawable.vm_logo)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = oval(Color.BLACK, BLUE)
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
        }
        root.addView(bubble, FrameLayout.LayoutParams(dp(54), dp(54)))

        panel = buildPanel()
        panel.visibility = View.GONE
        root.addView(panel, FrameLayout.LayoutParams(dp(264), ViewGroup.LayoutParams.WRAP_CONTENT))

        lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            baseFlags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(220)
        }

        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateOcc() }
        dragAndClick(bubble) { setExpanded(true) }
        wm.addView(root, lp)
    }

    private fun buildPanel(): LinearLayout {
        val p = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(14))
            background = rounded(INK, dp(18), BLUE, dp(2))
        }

        // kepala: logo, judul, tombol kecilkan dan tutup
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(roundLogo(dp(34)))
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(6), 0)
        }
        titles.addView(label("VMloader-spaceman", 15f, Color.WHITE, true))
        titles.addView(label("Create by SpacemanBuilder", 11f, CYAN))
        head.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val minimize = label("–", 22f, SILVER, true).apply {
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener { setExpanded(false) }
        }
        val close = label("✕", 17f, SILVER, true).apply {
            setPadding(dp(8), 0, dp(4), 0)
            setOnClickListener { stopSelf() }
        }
        head.addView(minimize)
        head.addView(close)
        dragAndClick(head) { }
        p.addView(head)

        // target level
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        row.addView(label("Target level", 14f, SILVER), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        etTarget = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt("target", 0).toString())
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            hint = "0"
            textSize = 16f
            gravity = Gravity.CENTER
            background = rounded(FIELD, dp(10), BLUE, dp(1))
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        row.addView(etTarget, LinearLayout.LayoutParams(dp(84), ViewGroup.LayoutParams.WRAP_CONTENT))
        p.addView(row)
        p.addView(label("Isi 0 untuk tanpa batas (unlimited)", 11f, Color.parseColor("#7F8CA8")).apply {
            setPadding(0, dp(4), 0, dp(12))
        })

        btnPlay = button("▶  Auto Play", BLUE).apply { setOnClickListener { toggle() } }
        p.addView(btnPlay, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val btnNext = button("Atur tombol Next", FIELD, CYAN).apply {
            textSize = 13f
            setOnClickListener { calibrateNext() }
        }
        p.addView(btnNext, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        val cbDebug = CheckBox(this).apply {
            text = "Simpan debug.png"
            setTextColor(SILVER)
            textSize = 12f
            isChecked = prefs.getBoolean("debug", false)
            setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("debug", c).apply() }
        }
        p.addView(cbDebug)

        tvLevel = label("Level selesai: 0  |  Langkah: 0", 12f, Color.WHITE, true).apply {
            setPadding(0, dp(6), 0, 0)
        }
        p.addView(tvLevel)
        tvStatus = label("Siap. Buka game lalu tekan Auto Play.", 12f, SILVER).apply {
            setPadding(0, dp(4), 0, 0)
        }
        p.addView(tvStatus)
        return p
    }

    private fun dragAndClick(v: View, onClick: () -> Unit) {
        var sx = 0f
        var sy = 0f
        var ox = 0
        var oy = 0
        var moved = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX
                    sy = e.rawY
                    ox = lp.x
                    oy = lp.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx
                    val dy = e.rawY - sy
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    if (moved) {
                        lp.x = ox + dx.toInt()
                        lp.y = oy + dy.toInt()
                        wm.updateViewLayout(root, lp)
                        updateOcc()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onClick()
                    true
                }
                else -> false
            }
        }
    }

    private fun updateOcc() {
        occ = Rect(lp.x, lp.y, lp.x + root.width, lp.y + root.height)
    }

    private fun setExpanded(e: Boolean) {
        panel.visibility = if (e) View.VISIBLE else View.GONE
        bubble.visibility = if (e) View.GONE else View.VISIBLE
        // panel butuh fokus agar keyboard muncul; bubble tidak boleh mencuri fokus dari game
        lp.flags = if (e) baseFlags else baseFlags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        wm.updateViewLayout(root, lp)
    }

    private fun toggle() {
        if (engine.isRunning) {
            engine.stop()
            status("Menghentikan…")
            return
        }
        if (Build.VERSION.SDK_INT < 30) {
            status("Butuh Android 11 ke atas.")
            return
        }
        if (BotService.instance == null) {
            status("Aktifkan layanan aksesibilitas VMloader-spaceman dulu.")
            return
        }
        val t = etTarget.text.toString().toIntOrNull() ?: 0
        prefs.edit().putInt("target", t).apply()
        btnPlay.text = "■  Stop"
        setExpanded(false)
        engine.start(t)
    }

    private fun calibrateNext() {
        val v = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#88000000")) }
        val tv = TextView(this).apply {
            text = "Ketuk posisi tombol Next / Lanjut di layar game"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(48), dp(24), dp(24))
        }
        v.addView(tv, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        ))
        val p = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        v.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_UP) {
                val size = screenSize()
                prefs.edit()
                    .putFloat("nx", e.rawX / size.first)
                    .putFloat("ny", e.rawY / size.second)
                    .apply()
                try {
                    wm.removeView(v)
                } catch (_: Exception) {
                }
                status("Posisi tombol Next tersimpan.")
            }
            true
        }
        wm.addView(v, p)
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "VMloader-spaceman", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("VMloader-spaceman")
            .setContentText("Jendela mengambang aktif")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }
}
