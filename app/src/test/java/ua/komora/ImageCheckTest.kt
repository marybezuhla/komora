package ua.komora

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.komora.ui.looksLikeImage

class ImageCheckTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test fun onlyPngJpegAndWebpReachTheDecoder() {
        assertTrue(looksLikeImage(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0)))
        assertTrue(looksLikeImage(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertTrue(looksLikeImage("RIFF____WEBPVP8 ".toByteArray()))
        assertFalse(looksLikeImage("GIF89a".toByteArray()))
        assertFalse(looksLikeImage("<svg>".toByteArray()))
        assertFalse(looksLikeImage("RIFF____WAVE".toByteArray()))
        assertFalse(looksLikeImage(ByteArray(0)))
    }
}
