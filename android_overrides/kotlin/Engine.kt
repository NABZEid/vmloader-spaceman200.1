package com.spaceman.vmloader

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.min

/**
 * Otak auto play:
 * ambil screenshot -> cari tile bebas -> pilih pasangan paling mirip -> ketuk dua tile ->
 * ambil screenshot lagi untuk memastikan pasangan benar-benar hilang. Pasangan yang gagal
 * dicatat supaya tidak diulang terus-menerus.
 */
class Engine(private val ctx: Context, private val ui: OverlayService) {

    companion object {
        const val TOP = 0.10f          // bagian atas layar yang diabaikan (bar level, menu)
        const val BOT = 0.88f          // bagian bawah layar yang diabaikan (tombol, iklan)
        const val THR = 0.045f         // batas kemiripan dua tile dianggap pasangan
    }

    @Volatile
    private var running = false
    val isRunning: Boolean get() = running

    fun start(target: Int) {
        if (running) return
        running = true
        Thread {
            try {
                loop(target)
            } catch (e: Exception) {
                ui.status("Error: ${e.message}")
            } finally {
                running = false
                ui.onStopped()
            }
        }.also {
            it.isDaemon = true
            it.start()
        }
    }

    fun stop() {
        running = false
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    private fun grab(svc: BotService): Bitmap? {
        ui.hide(true)
        sleep(120)
        var bmp = svc.screenshot()
        if (bmp == null) {
            sleep(450)
            bmp = svc.screenshot()
        }
        ui.hide(false)
        return bmp
    }

    private fun scan(svc: BotService, debug: Boolean): Analysis? {
        val bmp = grab(svc) ?: return null
        val an = Analyzer.analyze(bmp, TOP, BOT, ui.occupied())
        if (debug) saveDebug(bmp, an)
        bmp.recycle()
        return an
    }

    private fun key(a: Tile, b: Tile): String {
        val k1 = "${a.cx / 8},${a.cy / 8}"
        val k2 = "${b.cx / 8},${b.cy / 8}"
        return if (k1 < k2) "$k1|$k2" else "$k2|$k1"
    }

    private fun present(an: Analysis, t: Tile): Boolean {
        val lim = 0.35f * t.rect.width()
        return an.tiles.any {
            abs(it.cx - t.cx) < lim && abs(it.cy - t.cy) < lim && Analyzer.dist(it.sig, t.sig) < 0.05f
        }
    }

    private fun loop(target: Int) {
        val prefs = ctx.getSharedPreferences("vm", Context.MODE_PRIVATE)
        var levels = 0
        var moves = 0
        var seenBoard = false
        var empty = 0
        var noPair = 0
        var fails = 0
        var nullShots = 0
        val bad = HashSet<String>()
        var carry: Analysis? = null

        ui.progress(0, 0)
        ui.status("Auto play berjalan…")

        while (running) {
            val svc = BotService.instance
            if (svc == null) {
                ui.status("Layanan aksesibilitas mati. Aktifkan lagi di pengaturan.")
                return
            }
            val debug = prefs.getBoolean("debug", false)
            val an = carry ?: scan(svc, debug)
            carry = null
            if (an == null) {
                nullShots++
                if (nullShots > 8) {
                    ui.status("Gagal mengambil layar. Butuh Android 11 ke atas.")
                    return
                }
                sleep(500)
                continue
            }
            nullShots = 0

            // Papan kosong: level selesai, atau layar transisi / menu
            if (an.tiles.size < 2) {
                empty++
                if (seenBoard && empty >= 2) {
                    levels++
                    seenBoard = false
                    bad.clear()
                    ui.progress(levels, moves)
                    if (target > 0 && levels >= target) {
                        ui.status("Selesai. Target $target level tercapai.")
                        return
                    }
                    ui.status("Level selesai. Lanjut…")
                }
                if (empty > 30) {
                    ui.status("Papan game tidak terdeteksi. Cek posisi layar game.")
                    return
                }
                if (empty >= 3) {
                    tapNext(svc, prefs, empty)
                    sleep(1600)
                } else {
                    sleep(700)
                }
                continue
            }
            empty = 0
            if (an.tiles.size >= 4) seenBoard = true

            // Cari pasangan tile bebas yang paling mirip
            val free = an.tiles.filter { it.free }
            var bestA: Tile? = null
            var bestB: Tile? = null
            var bestD = 9f
            for (i in free.indices) {
                for (j in i + 1 until free.size) {
                    if (key(free[i], free[j]) in bad) continue
                    val d = Analyzer.dist(free[i].sig, free[j].sig)
                    if (d < bestD) {
                        bestD = d
                        bestA = free[i]
                        bestB = free[j]
                    }
                }
            }
            val thr = THR * (1f + 0.5f * min(noPair, 3))
            val a = bestA
            val b = bestB
            if (a == null || b == null || bestD > thr) {
                noPair++
                if (noPair >= 3) bad.clear()
                if (noPair > 10) {
                    ui.status("Tidak ada pasangan cocok. Mungkin buntu atau perlu kalibrasi.")
                    return
                }
                ui.status("Mencari pasangan… (${free.size} tile bebas)")
                sleep(500)
                continue
            }
            noPair = 0

            // Ketuk dua tile
            svc.tap(a.cx.toFloat(), a.cy.toFloat())
            sleep(160)
            svc.tap(b.cx.toFloat(), b.cy.toFloat())
            sleep(800)

            // Verifikasi: kedua tile harus sudah hilang
            val an2 = scan(svc, debug)
            if (an2 != null) {
                carry = an2
                if (!present(an2, a) && !present(an2, b)) {
                    moves++
                    fails = 0
                    ui.progress(levels, moves)
                    ui.status("Bermain… ${an2.tiles.size} tile di layar")
                } else {
                    bad.add(key(a, b))
                    fails++
                    if (fails >= 15) {
                        bad.clear()
                        fails = 0
                        sleep(1200)
                    }
                }
            }
        }
        ui.status("Auto play dihentikan.")
    }

    private fun tapNext(svc: BotService, prefs: SharedPreferences, n: Int) {
        val size = ui.screenSize()
        val w = size.first
        val h = size.second
        val fx = prefs.getFloat("nx", -1f)
        val fy = prefs.getFloat("ny", -1f)
        if (fx >= 0f && fy >= 0f) {
            svc.tap(fx * w, fy * h)
        } else {
            val ys = floatArrayOf(0.62f, 0.72f, 0.82f, 0.50f)
            svc.tap(0.5f * w, ys[(n - 3) % ys.size] * h)
        }
    }

    private fun saveDebug(bmp: Bitmap, an: Analysis) {
        try {
            val copy = bmp.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(copy)
            val paint = Paint().apply {
                style = Paint.Style.STROKE
                strokeWidth = 4f
            }
            for (t in an.tiles) {
                paint.color = if (t.free) Color.GREEN else Color.RED
                canvas.drawRect(t.rect, paint)
            }
            val f = File(ctx.getExternalFilesDir(null), "debug.png")
            FileOutputStream(f).use { copy.compress(Bitmap.CompressFormat.PNG, 90, it) }
            copy.recycle()
        } catch (_: Exception) {
        }
    }
}
