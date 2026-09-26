package com.connectx.gateway.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SimBalanceTest {
    @Test fun readsOnlyLabeledBalances() {
        assertEquals("185.50", BalanceReplyParser.balance("Available balance: Tk 185.50"))
        assertEquals("185.50", BalanceReplyParser.balance("Balance: ৳185.50"))
        assertEquals("185.50", BalanceReplyParser.balance("Balance is 185,50 taka"))
        assertEquals("0.00", BalanceReplyParser.balance("Your Balance: 0"))
        assertNull(BalanceReplyParser.balance("Dial 01712345678 for more info"))
        assertNull(BalanceReplyParser.balance("SMS Remaining: 73")) // No quota parser/guessing.
        assertNull(BalanceReplyParser.balance("SMS Balance: Tk 73")) // Not a cash balance.
        assertNull(BalanceReplyParser.balance(null))
    }

    @Test fun groupedCurrencyMustBeCompleteAndUnambiguous() {
        assertEquals("1234.50", BalanceReplyParser.balance("Balance: ৳1,234.50"))
        assertEquals("1234.50", BalanceReplyParser.balance("Balance: Tk 1.234,50"))
        assertEquals("1234.50", BalanceReplyParser.balance("Balance: 1234.50."))
        assertNull(BalanceReplyParser.balance("Balance: 1,234")) // Could be 1.234 or 1234.
        assertNull(BalanceReplyParser.balance("Balance: -৳20.00"))
        assertNull(BalanceReplyParser.balance("Balance: 100.00; bonus balance: 20.00"))
    }

    @Test fun ownerPatternsCannotCapturePartOfAnAmountOrHangOnNestedQuantifiers() {
        assertEquals("185.50", BalanceReplyParser.balance("Credit=185.5; bonus=50", "Credit=([0-9.]+)"))
        assertNull(BalanceReplyParser.balance("Credit: 1,234.50", "Credit: ([0-9.]+)"))
        assertNull(BalanceReplyParser.balance("Promo: 185.5", "Promo: [0-9.]+"))
        assertNull(BalanceReplyParser.balance("aaaaaaa!", "(a+)+"))
    }

    @Test fun rejectsUntrustedDialStrings() {
        assertTrue(CarrierBalanceConfig.isSafeCode("*123#"))
        assertTrue(CarrierBalanceConfig.isSafeCode("*1#"))
        assertTrue(CarrierBalanceConfig.isSafeCode("*123*45#"))
        assertFalse(CarrierBalanceConfig.isSafeCode("**#"))
        assertFalse(CarrierBalanceConfig.isSafeCode("https://carrier.example"))
        assertFalse(CarrierBalanceConfig.isSafeCode("123456789"))
        assertTrue(CarrierBalanceConfig("Carrier", "*123#").hasSafeCode())
    }
}
