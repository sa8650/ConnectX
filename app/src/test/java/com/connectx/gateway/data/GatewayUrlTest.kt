package com.connectx.gateway.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class GatewayUrlTest {
    @Test fun acceptsOnlySiteOrigin() {
        assertEquals("https://connectx-control.pages.dev", GatewayUrl.normalize(" https://connectx-control.pages.dev/ "))
        assertEquals("https://connectx-control.pages.dev", GatewayUrl.normalize("connectx-control.pages.dev"))
        assertEquals("https://connectx-control.pages.dev", GatewayUrl.normalize("https://connectx-control.pages.dev/api"))
        assertEquals("https://connectx-control.pages.dev", GatewayUrl.normalize("https://connectx-control.pages.dev/?page=app-store"))
        assertEquals("https://connectx-control.pages.dev", GatewayUrl.normalize("https://connectx-control.pages.dev/#login"))
    }

    @Test fun rejectsSchemesWithoutHostsAndNonGatewayAddresses() {
        listOf("", "https:/", "https://", "https", "https://https:/", "https://https",
            "https://github.com/sa8650/EMS", "http://localhost:8788",
            "https://connectx-control.pages.dev/anything-else", "https://user:password@connectx-control.pages.dev"
        ).forEach { input ->
            val error = assertThrows(IllegalArgumentException::class.java) { GatewayUrl.normalize(input) }
            assertTrue("Missing guidance for '$input': ${error.message}", error.message.orEmpty().isNotBlank())
        }
    }
}
