package com.ems.connectx.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class EmsSiteUrlTest {
    @Test fun acceptsOnlySiteOrigin() {
        assertEquals("https://real-ems.pages.dev", EmsSiteUrl.normalize(" https://real-ems.pages.dev/ "))
        assertEquals("https://real-ems.pages.dev", EmsSiteUrl.normalize("real-ems.pages.dev"))
        assertEquals("https://real-ems.pages.dev", EmsSiteUrl.normalize("https://real-ems.pages.dev/api"))
        assertEquals("https://real-ems.pages.dev", EmsSiteUrl.normalize("https://real-ems.pages.dev/?page=app-store"))
        assertEquals("https://real-ems.pages.dev", EmsSiteUrl.normalize("https://real-ems.pages.dev/#login"))
    }

    @Test fun rejectsSchemesWithoutHostsAndNonEmsAddresses() {
        listOf("", "https:/", "https://", "https", "https://https:/", "https://https",
            "https://github.com/sa8650/EMS", "http://localhost:8788",
            "https://real-ems.pages.dev/anything-else", "https://user:password@real-ems.pages.dev"
        ).forEach { input ->
            val error = assertThrows(IllegalArgumentException::class.java) { EmsSiteUrl.normalize(input) }
            assertTrue("Missing guidance for '$input': ${error.message}", error.message.orEmpty().isNotBlank())
        }
    }
}
