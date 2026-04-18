package com.procam.s23fe.gpu

import android.content.Context
import android.util.Log
import java.io.IOException

/**
 * Runtime LUT library: returns **procedural looks + every `.cube` shipped in `assets/luts/`**,
 * so a designer can drop a new `MyLook.cube` into `app/src/main/assets/luts/` and it will
 * show up in the LUT cycle button on next app launch.
 */
object LutLibrary {

    private const val TAG = "LutLibrary"
    private const val ASSETS_DIR = "luts"

    private var cache: List<LutCatalog.Entry>? = null

    fun all(context: Context): List<LutCatalog.Entry> {
        cache?.let { return it }
        val list = ArrayList<LutCatalog.Entry>()
        list += LutCatalog.ALL                     // procedural
        list += loadFromAssets(context)            // external .cube
        cache = list
        return list
    }

    private fun loadFromAssets(context: Context): List<LutCatalog.Entry> {
        val am = context.assets
        val files = try { am.list(ASSETS_DIR) ?: emptyArray() } catch (e: IOException) { emptyArray() }
        val out = ArrayList<LutCatalog.Entry>(files.size)
        for (f in files) {
            if (!f.endsWith(".cube", ignoreCase = true)) continue
            try {
                am.open("$ASSETS_DIR/$f").use { stream ->
                    val parsed = CubeLutParser.parse(stream)
                    val label = parsed.title ?: f.removeSuffix(".cube").replace('_', ' ')
                    out += LutCatalog.Entry(
                        id = "cube_$f",
                        label = label,
                        size = parsed.size,
                        data = parsed.data
                    )
                    Log.i(TAG, "Loaded LUT '$label' (${parsed.size}^3) from $f")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load $f", e)
            }
        }
        return out
    }

    /** For unit tests / debug screen. */
    fun invalidate() { cache = null }
}
