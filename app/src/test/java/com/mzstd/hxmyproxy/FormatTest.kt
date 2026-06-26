package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.ui.formatBytes
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    @Test fun formatsBytesAcrossUnits() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.5 MB", formatBytes(1024L * 1024 * 3 / 2))
        assertEquals("2.00 GB", formatBytes(2L * 1024 * 1024 * 1024))
    }
}
