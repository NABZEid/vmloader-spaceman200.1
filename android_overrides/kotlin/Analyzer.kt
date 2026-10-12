package com.spaceman.vmloader

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Satu tile utuh (tidak tertutup tile lain) yang terdeteksi di layar. */
class Tile(
    val rect: Rect,
    val bright: Float,
    val sig: FloatArray
) {
    var free: Boolean = false
    val cx: Int get() = rect.centerX()
    val cy: Int get() = rect.centerY()
}

class Analysis(val tiles: List<Tile>, val refW: Int, val refH: Int)

object Analyzer {
    private const val S = 4          // langkah sampling piksel
    private const val SW = 10        // lebar tanda pengenal
    private const val SH = 12        // tinggi tanda pengenal

    fun analyze(bmp: Bitmap, topFrac: Float, botFrac: Float, blocked: Rect?): Analysis {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)

        val y0 = (h * topFrac).toInt()
        val y1 = (h * botFrac).toInt()
        val gw = w / S
        val gh = (y1 - y0) / S
        if (gw < 10 || gh < 10) return Analysis(emptyList(), 0, 0)

        // 1. topeng warna muka tile (krem / putih gading)
        val raw = BooleanArray(gw * gh)
        for (gy in 0 until gh) {
            val row = (y0 + gy * S) * w
            for (gx in 0 until gw) {
                raw[gy * gw + gx] = isFace(px[row + gx * S])
            }
        }

        // 2. erosi 1 sampel supaya tile yang menempel terpisah
        val m = BooleanArray(gw * gh)
        for (gy in 1 until gh - 1) {
            for (gx in 1 until gw - 1) {
                val i = gy * gw + gx
                m[i] = raw[i] && raw[i - 1] && raw[i + 1] && raw[i - gw] && raw[i + gw]
            }
        }

        // 3. komponen terhubung -> kotak tile
        val seen = BooleanArray(gw * gh)
        val queue = IntArray(gw * gh)
        val rects = ArrayList<Rect>()
        for (start in m.indices) {
            if (!m[start] || seen[start]) continue
            var qh = 0
            var qt = 0
            queue[qt++] = start
            seen[start] = true
            var minX = gw
            var maxX = 0
            var minY = gh
            var maxY = 0
            var cnt = 0
            while (qh < qt) {
                val c = queue[qh++]
                val cx = c % gw
                val cy = c / gw
                cnt++
                if (cx < minX) minX = cx
                if (cx > maxX) maxX = cx
                if (cy < minY) minY = cy
                if (cy > maxY) maxY = cy
                val ns = intArrayOf(c - 1, c + 1, c - gw, c + gw)
                for (n in ns) {
                    if (m[n] && !seen[n]) {
                        seen[n] = true
                        queue[qt++] = n
                    }
                }
            }
            if (cnt < 8) continue
            val r = Rect(
                max(0, (minX - 1) * S),
                max(0, y0 + (minY - 1) * S),
                min(w - 1, (maxX + 2) * S),
                min(h - 1, y0 + (maxY + 2) * S)
            )
            rects.add(r)
        }

        // 4. tentukan ukuran tile utuh (yang paling umum di antara kotak besar)
        val shaped = rects.filter {
            val ratio = it.width().toFloat() / it.height().toFloat()
            ratio in 0.55f..1.1f && it.width() > 20
        }
        if (shaped.isEmpty()) return Analysis(emptyList(), 0, 0)
        val areas = shaped.map { it.width() * it.height() }.sorted()
        val p95 = areas[((areas.size - 1) * 0.95f).toInt()]
        val cands = shaped.filter { it.width() * it.height() >= 0.75f * p95 }
        val refW = cands.map { it.width() }.sorted()[cands.size / 2]
        val refH = cands.map { it.height() }.sorted()[cands.size / 2]

        // 5. simpan hanya tile utuh (tile yang sebagian tertutup dibuang)
        val tiles = ArrayList<Tile>()
        for (r in shaped) {
            val okW = abs(r.width() - refW) <= 0.15f * refW
            val okH = abs(r.height() - refH) <= 0.15f * refH
            if (!okW || !okH) continue
            val bright = faceBright(px, w, r)
            if (bright < 1f) continue
            tiles.add(Tile(r, bright, signature(px, w, h, r, bright)))
        }
        if (tiles.isEmpty()) return Analysis(emptyList(), refW, refH)

        // 6. tile bebas: sisi kiri ATAU kanan terbuka, dan tidak diredupkan game
        val sortedB = tiles.map { it.bright }.sorted()
        val refB = sortedB[((sortedB.size - 1) * 0.9f).toInt()]
        for (t in tiles) {
            var left = false
            var right = false
            for (o in tiles) {
                if (o === t) continue
                if (abs(o.cy - t.cy) >= 0.9f * refH) continue
                val dx = o.cx - t.cx
                if (dx <= -0.65f * refW && dx >= -1.35f * refW) left = true
                if (dx >= 0.65f * refW && dx <= 1.35f * refW) right = true
            }
            val bright = t.bright >= 0.94f * refB
            val hidden = blocked != null && Rect.intersects(blocked, t.rect)
            t.free = !(left && right) && bright && !hidden
        }
        return Analysis(tiles, refW, refH)
    }

    /** Jarak rata-rata antara dua tanda pengenal tile. Makin kecil makin mirip. */
    fun dist(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += abs(a[i] - b[i])
        return s / a.size
    }

    private fun isFace(c: Int): Boolean {
        val r = (c shr 16) and 255
        val g = (c shr 8) and 255
        val b = c and 255
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        return mx >= 170 && mx - mn <= 55
    }

    private fun faceBright(px: IntArray, w: Int, r: Rect): Float {
        var sum = 0L
        var n = 0
        var y = r.top
        while (y < r.bottom) {
            var x = r.left
            while (x < r.right) {
                val c = px[y * w + x]
                if (isFace(c)) {
                    sum += max((c shr 16) and 255, max((c shr 8) and 255, c and 255))
                    n++
                }
                x += 3
            }
            y += 3
        }
        return if (n == 0) 0f else sum.toFloat() / n
    }

    private fun signature(px: IntArray, w: Int, h: Int, r: Rect, bright: Float): FloatArray {
        val ix = (r.width() * 0.18f).toInt()
        val iy = (r.height() * 0.15f).toInt()
        val l = r.left + ix
        val t = r.top + iy
        val rw = max(1, r.width() - 2 * ix)
        val rh = max(1, r.height() - 2 * iy)
        val out = FloatArray(SW * SH * 3)
        val norm = max(bright, 1f)
        for (j in 0 until SH) {
            for (i in 0 until SW) {
                val cx = l + (i * rw + rw / 2) / SW
                val cy = t + (j * rh + rh / 2) / SH
                var rs = 0
                var gs = 0
                var bs = 0
                var n = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val x = (cx + dx).coerceIn(0, w - 1)
                        val y = (cy + dy).coerceIn(0, h - 1)
                        val c = px[y * w + x]
                        rs += (c shr 16) and 255
                        gs += (c shr 8) and 255
                        bs += c and 255
                        n++
                    }
                }
                val k = (j * SW + i) * 3
                out[k] = min(rs.toFloat() / (n * norm), 1.5f)
                out[k + 1] = min(gs.toFloat() / (n * norm), 1.5f)
                out[k + 2] = min(bs.toFloat() / (n * norm), 1.5f)
            }
        }
        return out
    }
}
