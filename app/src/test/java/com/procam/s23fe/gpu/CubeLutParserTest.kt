package com.procam.s23fe.gpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CubeLutParserTest {

    @Test fun parses_identity_2x2x2() {
        val cube = """
            TITLE "Id"
            LUT_3D_SIZE 2
            DOMAIN_MIN 0 0 0
            DOMAIN_MAX 1 1 1
            # comment ignored
            0.0 0.0 0.0
            1.0 0.0 0.0
            0.0 1.0 0.0
            1.0 1.0 0.0
            0.0 0.0 1.0
            1.0 0.0 1.0
            0.0 1.0 1.0
            1.0 1.0 1.0
        """.trimIndent()
        val p = CubeLutParser.parse(cube.byteInputStream())
        assertEquals("Id", p.title)
        assertEquals(2, p.size)
        assertEquals(2 * 2 * 2 * 3, p.data.size)
        // last sample should be (1,1,1)
        assertEquals(1.0f, p.data[p.data.size - 3], 1e-6f)
        assertEquals(1.0f, p.data[p.data.size - 2], 1e-6f)
        assertEquals(1.0f, p.data[p.data.size - 1], 1e-6f)
    }

    @Test fun rescales_custom_domain() {
        val cube = """
            LUT_3D_SIZE 2
            DOMAIN_MIN 0.0 0.0 0.0
            DOMAIN_MAX 2.0 2.0 2.0
            0.0 0.0 0.0
            2.0 0.0 0.0
            0.0 2.0 0.0
            2.0 2.0 0.0
            0.0 0.0 2.0
            2.0 0.0 2.0
            0.0 2.0 2.0
            2.0 2.0 2.0
        """.trimIndent()
        val p = CubeLutParser.parse(cube.byteInputStream())
        // 2.0 in a 0..2 domain → 1.0 after normalization
        assertEquals(1f, p.data[p.data.size - 1], 1e-6f)
    }

    @Test(expected = CubeLutParser.CubeParseException::class)
    fun missing_size_throws() {
        CubeLutParser.parse("0 0 0\n1 1 1\n".byteInputStream())
    }

    @Test(expected = CubeLutParser.CubeParseException::class)
    fun wrong_triplet_count_throws() {
        val cube = """
            LUT_3D_SIZE 2
            0 0 0
            1 0 0
        """.trimIndent()
        CubeLutParser.parse(cube.byteInputStream())
    }
}
