package com.procam.s23fe.gpu

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Minimal yet compliant **.cube** LUT parser (Adobe Cube LUT spec).
 *
 * Supported directives:
 *  - `TITLE`        (ignored, stored on the result for display)
 *  - `LUT_3D_SIZE`  (required for 3D LUTs)
 *  - `DOMAIN_MIN`   (optional, defaults to 0 0 0)
 *  - `DOMAIN_MAX`   (optional, defaults to 1 1 1)
 *  - Any `R G B` triplet line
 *  - `#` comments and blank lines
 *
 * Not supported (rare in camera grading workflows, intentionally out of scope):
 *  - `LUT_1D_SIZE`
 *  - `LUT_3D_INPUT_RANGE` legacy directive
 *  - Non-0..1 domains with per-channel asymmetry beyond what `DOMAIN_*` encodes
 *
 * The returned float array is ordered **R fastest, then G, then B**, which matches
 * the OpenGL GL_TEXTURE_3D upload convention we use in [LutEngine.createLut3D].
 */
object CubeLutParser {

    data class Parsed(
        val title: String?,
        val size: Int,
        /** size*size*size*3 floats, R-fastest ordering, values in 0..1. */
        val data: FloatArray,
    )

    class CubeParseException(msg: String) : RuntimeException(msg)

    fun parse(input: InputStream): Parsed =
        BufferedReader(InputStreamReader(input, Charsets.US_ASCII)).use { parse(it) }

    fun parse(reader: BufferedReader): Parsed {
        var title: String? = null
        var size = -1
        var domainMin = floatArrayOf(0f, 0f, 0f)
        var domainMax = floatArrayOf(1f, 1f, 1f)
        val triplets = ArrayList<FloatArray>(33 * 33 * 33)

        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val raw = line!!.trim()
            if (raw.isEmpty() || raw.startsWith("#")) continue

            val upper = raw.uppercase()
            when {
                upper.startsWith("TITLE") -> {
                    title = raw.substringAfter(' ').trim().trim('"').ifBlank { null }
                }
                upper.startsWith("LUT_3D_SIZE") -> {
                    size = raw.split(Regex("\\s+")).getOrNull(1)?.toIntOrNull()
                        ?: throw CubeParseException("Invalid LUT_3D_SIZE: $raw")
                }
                upper.startsWith("LUT_1D_SIZE") -> {
                    throw CubeParseException("1D LUTs are not supported")
                }
                upper.startsWith("DOMAIN_MIN") -> {
                    domainMin = parseTriplet(raw) ?: domainMin
                }
                upper.startsWith("DOMAIN_MAX") -> {
                    domainMax = parseTriplet(raw) ?: domainMax
                }
                else -> {
                    parseTriplet(raw)?.let { triplets += it }
                }
            }
        }

        if (size <= 1) throw CubeParseException("Missing or invalid LUT_3D_SIZE")
        val expected = size * size * size
        if (triplets.size != expected) {
            throw CubeParseException("Triplet count ${triplets.size} != expected $expected (size=$size)")
        }

        // Normalize to 0..1 domain
        val rangeR = (domainMax[0] - domainMin[0]).takeIf { it > 0f } ?: 1f
        val rangeG = (domainMax[1] - domainMin[1]).takeIf { it > 0f } ?: 1f
        val rangeB = (domainMax[2] - domainMin[2]).takeIf { it > 0f } ?: 1f
        val out = FloatArray(expected * 3)
        for (i in 0 until expected) {
            val t = triplets[i]
            out[i * 3]     = ((t[0] - domainMin[0]) / rangeR).coerceIn(0f, 1f)
            out[i * 3 + 1] = ((t[1] - domainMin[1]) / rangeG).coerceIn(0f, 1f)
            out[i * 3 + 2] = ((t[2] - domainMin[2]) / rangeB).coerceIn(0f, 1f)
        }
        return Parsed(title = title, size = size, data = out)
    }

    private fun parseTriplet(raw: String): FloatArray? {
        // Split on whitespace, take the last 3 tokens that parse as floats.
        val tokens = raw.split(Regex("\\s+"))
            .filter { it.isNotBlank() && !it.startsWith("#") }
        val floats = tokens.mapNotNull { it.toFloatOrNull() }
        if (floats.size < 3) return null
        val take = floats.takeLast(3)
        return floatArrayOf(take[0], take[1], take[2])
    }
}
