package com.procam.s23fe.gpu

import kotlin.math.pow

/**
 * The four built-in looks the spec calls out:
 *   - Cinema Warm   (lifted blacks, warm highlights)
 *   - Cinema Cool   (teal shadows, crisp highlights)
 *   - Teal & Orange (classic blockbuster grade)
 *   - Soft Film     (lower contrast, slight film curve)
 *
 * Each LUT is generated procedurally so we don't ship binary .cube files in this
 * skeleton — the real product can swap in artist-tuned LUTs loaded from assets.
 */
object LutCatalog {

    data class Entry(val id: String, val label: String, val size: Int, val data: FloatArray)

    val ALL: List<Entry> by lazy {
        listOf(
            Entry("identity",     "None",           33, identity(33)),
            Entry("cinema_warm",  "Cinema Warm",    33, cinemaWarm(33)),
            Entry("cinema_cool",  "Cinema Cool",    33, cinemaCool(33)),
            Entry("teal_orange",  "Teal & Orange",  33, tealOrange(33)),
            Entry("soft_film",    "Soft Film",      33, softFilm(33)),
        )
    }

    fun identity(size: Int): FloatArray = build(size) { r, g, b -> floatArrayOf(r, g, b) }

    fun cinemaWarm(size: Int): FloatArray = build(size) { r, g, b ->
        val rr = (r + 0.05f).coerceIn(0f, 1f)
        val gg = g
        val bb = (b - 0.05f).coerceIn(0f, 1f)
        curve(rr, gg, bb, lift = 0.03f, gamma = 0.95f)
    }

    fun cinemaCool(size: Int): FloatArray = build(size) { r, g, b ->
        val rr = (r - 0.04f).coerceIn(0f, 1f)
        val bb = (b + 0.06f).coerceIn(0f, 1f)
        curve(rr, g, bb, lift = 0.0f, gamma = 1.05f)
    }

    fun tealOrange(size: Int): FloatArray = build(size) { r, g, b ->
        // Push warm tones toward orange, cool tones toward teal
        val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val warmMix = (lum - 0.5f).coerceAtLeast(0f) * 2f
        val coolMix = (0.5f - lum).coerceAtLeast(0f) * 2f
        val rr = (r + 0.08f * warmMix).coerceIn(0f, 1f)
        val gg = (g + 0.02f * warmMix - 0.02f * coolMix).coerceIn(0f, 1f)
        val bb = (b - 0.05f * warmMix + 0.08f * coolMix).coerceIn(0f, 1f)
        floatArrayOf(rr, gg, bb)
    }

    fun softFilm(size: Int): FloatArray = build(size) { r, g, b ->
        curve(r, g, b, lift = 0.04f, gamma = 1.1f, contrastDrop = 0.06f)
    }

    // region helpers

    private inline fun build(size: Int, body: (Float, Float, Float) -> FloatArray): FloatArray {
        val out = FloatArray(size * size * size * 3)
        var i = 0
        for (bi in 0 until size) {
            val b = bi.toFloat() / (size - 1)
            for (gi in 0 until size) {
                val g = gi.toFloat() / (size - 1)
                for (ri in 0 until size) {
                    val r = ri.toFloat() / (size - 1)
                    val rgb = body(r, g, b)
                    out[i++] = rgb[0]; out[i++] = rgb[1]; out[i++] = rgb[2]
                }
            }
        }
        return out
    }

    private fun curve(
        r: Float, g: Float, b: Float,
        lift: Float = 0f,
        gamma: Float = 1f,
        contrastDrop: Float = 0f,
    ): FloatArray {
        fun c(v: Float): Float {
            var x = v.coerceIn(0f, 1f)
            x = x * (1f - contrastDrop) + 0.5f * contrastDrop
            x = (x + lift * (1f - x))
            x = x.toDouble().pow(gamma.toDouble()).toFloat()
            return x.coerceIn(0f, 1f)
        }
        return floatArrayOf(c(r), c(g), c(b))
    }

    // endregion
}
