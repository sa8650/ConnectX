package com.connectx.gateway.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SmsStatusTest {
    @Test fun userFacingSmsStatusDistinguishesAcceptanceFromDelivery() {
        assertEquals("Sent · unconfirmed", displaySmsStatus("sent"))
        assertEquals("Carrier confirmed", displaySmsStatus("delivered"))
        assertEquals("Unknown", displaySmsStatus("unknown"))
        assertEquals("Processing", displaySmsStatus("processing"))
        assertEquals("Pending", displaySmsStatus("pending"))
        assertEquals("Failed", displaySmsStatus("failed"))
        assertEquals("Cancelled", displaySmsStatus("cancelled"))
    }
}
