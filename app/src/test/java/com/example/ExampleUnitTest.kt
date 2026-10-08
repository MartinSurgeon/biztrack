package com.example

import com.example.data.model.CartItem
import com.example.util.Formatters
import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun `test exact user scenario - multi order debt calculation`() {
        // Customer buys eggs 3 Crates 50 cedis (150) and Chicken 180, totaling 330 cedis
        val item1 = CartItem(productId = 1, productName = "Crate of Eggs", category = "EGGS", unit = "Crate", unitPrice = 50.0, quantity = 3.0, lineTotal = 150.0)
        val item2 = CartItem(productId = 2, productName = "Live Broiler Chicken", category = "BROILER_LIVE", unit = "Bird", unitPrice = 180.0, quantity = 1.0, lineTotal = 180.0)
        val order1Total = item1.lineTotal + item2.lineTotal
        assertEquals(330.0, order1Total, 0.001)

        // Customer pays only 240
        val order1Paid = 240.0
        val order1BalanceDue = (order1Total - order1Paid).coerceAtLeast(0.0)
        assertEquals(90.0, order1BalanceDue, 0.001)

        // Customer buys another set of products: 2 Live Sasso Birds at 100 cedis each = 200 cedis
        val order2Total = 200.0
        val order2Paid = 80.0 // Pays partially
        val order2BalanceDue = (order2Total - order2Paid).coerceAtLeast(0.0)
        assertEquals(120.0, order2BalanceDue, 0.001)

        // Cumulative debt across both orders
        val totalCustomerDebt = order1BalanceDue + order2BalanceDue
        assertEquals(210.0, totalCustomerDebt, 0.001)

        // Now test FIFO payment allocation when customer brings 150 cedis part payment:
        var paymentRemaining = 150.0
        var newOrder1Bal = order1BalanceDue
        var newOrder2Bal = order2BalanceDue

        // Order 1 takes 90
        val alloc1 = minOf(newOrder1Bal, paymentRemaining)
        newOrder1Bal -= alloc1
        paymentRemaining -= alloc1
        assertEquals(90.0, alloc1, 0.001)
        assertEquals(0.0, newOrder1Bal, 0.001)
        assertEquals(60.0, paymentRemaining, 0.001)

        // Order 2 takes remaining 60
        val alloc2 = minOf(newOrder2Bal, paymentRemaining)
        newOrder2Bal -= alloc2
        paymentRemaining -= alloc2
        assertEquals(60.0, alloc2, 0.001)
        assertEquals(60.0, newOrder2Bal, 0.001)
        assertEquals(0.0, paymentRemaining, 0.001)

        // New total debt is 60.0
        val remainingTotalDebt = newOrder1Bal + newOrder2Bal
        assertEquals(60.0, remainingTotalDebt, 0.001)
    }

    @Test
    fun `test currency formatting`() {
        assertEquals("GH₵ 330.00", Formatters.formatCurrency(330.0, "GH₵"))
        assertEquals("GH₵ 90.00", Formatters.formatCurrency(90.0, "GH₵"))
    }
}
