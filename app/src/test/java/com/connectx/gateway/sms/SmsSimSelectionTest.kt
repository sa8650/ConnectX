package com.connectx.gateway.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmsSimSelectionTest {
    @Test fun usesTheShopConfiguredActiveSubscriptionOnly() {
        assertEquals(17, selectedActiveSubscriptionId(17, listOf(17, 22)))
    }

    @Test fun missingOrInvalidSelectionNeverFallsBackToAnotherSim() {
        assertNull(selectedActiveSubscriptionId(99, listOf(17, 22)))
        assertNull(selectedActiveSubscriptionId(0, listOf(17, 22)))
        assertNull(selectedActiveSubscriptionId(-1, listOf(17, 22)))
    }
}
