package com.dogukan.bigmatch

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class Card(val x: Int, val y: Int, val left: Int, val top: Int, val right: Int, val bottom: Int)
data class Match(val first: Card, val second: Card)

/** Detects the red card interiors, compares only the non-red icon pixels. */
object CardVision {
    private fun isRed(pixel: Int): Boolean {
        val r = pixel shr 16 and 255
        val g = pixel shr 8 and 255
        val b = pixel and 255
        return r > 155 && r > g * 2.0 && r > b * 1.7 && g < 100
    }

    fun findCards(bitmap: Bitmap): List<Card> {
        val step = max(2, bitmap.width / 240)
        val w = bitmap.width / step
        val h = bitmap.height / step
        val mask = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            mask[y * w + x] = isRed(bitmap.getPixel(x * step, y * step))
        }
        val seen = BooleanArray(mask.size)
        val queue = IntArray(mask.size)
        val found = mutableListOf<Card>()
        for (start in mask.indices) {
            if (!mask[start] || seen[start]) continue
            var head = 0; var tail = 0
            queue[tail++] = start; seen[start] = true
            var minX = w; var minY = h; var maxX = 0; var maxY = 0
            while (head < tail) {
                val p = queue[head++]
                val x = p % w; val y = p / w
                minX = min(minX, x); maxX = max(maxX, x)
                minY = min(minY, y); maxY = max(maxY, y)
                if (x > 0 && mask[p-1] && !seen[p-1]) { seen[p-1] = true; queue[tail++] = p-1 }
                if (x+1 < w && mask[p+1] && !seen[p+1]) { seen[p+1] = true; queue[tail++] = p+1 }
                if (y > 0 && mask[p-w] && !seen[p-w]) { seen[p-w] = true; queue[tail++] = p-w }
                if (y+1 < h && mask[p+w] && !seen[p+w]) { seen[p+w] = true; queue[tail++] = p+w }
            }
            val bw = maxX - minX + 1; val bh = maxY - minY + 1
            if (tail < 80 || bw < w / 13 || bw > w / 3 || bh < w / 9 || bh > h / 3) continue
            if (bh.toDouble() / bw !in 0.75..2.8) continue
            val l = minX * step; val t = minY * step
            val r = min(bitmap.width - 1, (maxX+1)*step)
            val b = min(bitmap.height - 1, (maxY+1)*step)
            found.add(Card((l+r)/2, (t+b)/2, l, t, r, b))
        }
        return if (found.size == 16) found.sortedWith(compareBy<Card> { it.y / max(1, bitmap.height / 10) }.thenBy { it.x }) else emptyList()
    }

    /** Tiny centered color descriptor; normalization makes screenshot resolution irrelevant. */
    private fun descriptor(bitmap: Bitmap, card: Card): FloatArray {
        val side = 32
        val result = FloatArray(side * side * 4)
        val width = card.right - card.left
        val height = card.bottom - card.top
        for (y in 0 until side) for (x in 0 until side) {
            val px = (card.x + (x - 15.5) * width * 0.62 / side).toInt().coerceIn(0, bitmap.width - 1)
            val py = (card.y + (y - 15.5) * height * 0.66 / side).toInt().coerceIn(0, bitmap.height - 1)
            val c = bitmap.getPixel(px, py)
            val r = (c shr 16 and 255).toFloat()
            val g = (c shr 8 and 255).toFloat()
            val b = (c and 255).toFloat()
            val i = (y * side + x) * 4
            val fg = if (isRed(c)) 0f else 1f
            result[i] = fg; result[i+1] = r * fg / 255f
            result[i+2] = g * fg / 255f; result[i+3] = b * fg / 255f
        }
        return result
    }

    private fun distance(a: FloatArray, b: FloatArray): Double {
        var sum = 0.0
        for (i in a.indices) { val d = (a[i] - b[i]).toDouble(); sum += d * d }
        return sqrt(sum / a.size)
    }

    fun solve(bitmap: Bitmap, cards: List<Card>): List<Match>? {
        if (cards.size != 16) return null
        val images = cards.map { descriptor(bitmap, it) }
        val choices = mutableListOf<Triple<Int, Int, Double>>()
        for (i in 0 until 16) for (j in i+1 until 16) choices.add(Triple(i, j, distance(images[i], images[j])))
        choices.sortBy { it.third }
        val used = BooleanArray(16)
        val pairs = mutableListOf<Match>()
        for ((a, b, d) in choices) {
            if (used[a] || used[b]) continue
            // The identical icon artwork should be much closer than different icons.
            if (d > 0.20) return null
            used[a] = true; used[b] = true
            pairs.add(Match(cards[a], cards[b]))
        }
        return pairs.takeIf { it.size == 8 }
    }

    fun looksTurnedOver(current: Bitmap, cards: List<Card>, original: Bitmap): Boolean {
        var change = 0.0
        for (card in cards) change += distance(descriptor(current, card), descriptor(original, card))
        return change / cards.size > 0.075
    }
}
