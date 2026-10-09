package com.labteto.dshmobile.ui.components

import org.junit.Assert.*
import org.junit.Test

class ReadImagePathTest {
    @Test fun `only read_image file_path arguments become previews`() {
        assertEquals("/tmp/example.png", readImagePath("read_image", """{"file_path":"/tmp/example.png"}"""))
        assertNull(readImagePath("read", """{"file_path":"/tmp/example.png"}"""))
        assertNull(readImagePath("read_image", "{}"))
        assertNull(readImagePath("read_image", "invalid"))
        assertNull(readImagePath("read_image", """{"file_path":" "}"""))
    }
}
