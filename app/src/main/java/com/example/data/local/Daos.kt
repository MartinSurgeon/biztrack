package com.example.data.local

import androidx.room.*
import com.example.data.model.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ProductDao {
    @Query("SELECT * FROM products ORDER BY category, name ASC")
    fun getAllProducts(): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products ORDER BY category, name ASC")
    suspend fun getAllProductsDirect(): List<ProductEntity>

    @Query("SELECT * FROM products WHERE inStock = 1 ORDER BY category, name ASC")
    fun getInStockProducts(): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products WHERE id = :id")
    suspend fun getProductById(id: Long): ProductEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProduct(product: ProductEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(products: List<ProductEntity>)

    @Update
    suspend fun updateProduct(product: ProductEntity)

    @Delete
    suspend fun deleteProduct(product: ProductEntity)

    @Query("SELECT COUNT(*) FROM products")
    suspend fun getProductCount(): Int

    @Query("SELECT * FROM products WHERE stockQuantity <= minStockThreshold")
    fun getLowStockProducts(): Flow<List<ProductEntity>>

    @Query("UPDATE products SET stockQuantity = stockQuantity + :quantityChange, lastRestockedAt = :timestamp WHERE id = :productId")
    suspend fun adjustStock(productId: Long, quantityChange: Double, timestamp: Long = System.currentTimeMillis())
}

@Dao
interface CustomerDao {
    @Query("SELECT * FROM customers ORDER BY name ASC")
    fun getAllCustomers(): Flow<List<CustomerEntity>>

    @Query("SELECT * FROM customers ORDER BY name ASC")
    suspend fun getAllCustomersDirect(): List<CustomerEntity>

    @Query("""
        SELECT DISTINCT c.* FROM customers c
        WHERE c.currentBalance > 0.009 
           OR c.id IN (SELECT DISTINCT customerId FROM sales_orders WHERE balanceDue > 0.009)
        ORDER BY c.currentBalance DESC, c.name ASC
    """)
    fun getDebtors(): Flow<List<CustomerEntity>>

    @Query("""
        SELECT DISTINCT c.* FROM customers c
        WHERE c.currentBalance > 0.009 
           OR c.id IN (SELECT DISTINCT customerId FROM sales_orders WHERE balanceDue > 0.009)
        ORDER BY c.currentBalance DESC, c.name ASC
    """)
    suspend fun getDebtorsDirect(): List<CustomerEntity>

    @Query("SELECT * FROM customers WHERE id = :id")
    suspend fun getCustomerById(id: Long): CustomerEntity?

    @Query("SELECT * FROM customers WHERE phone = :phone LIMIT 1")
    suspend fun getCustomerByPhone(phone: String): CustomerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCustomer(customer: CustomerEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(customers: List<CustomerEntity>): List<Long>

    @Update
    suspend fun updateCustomer(customer: CustomerEntity)

    @Delete
    suspend fun deleteCustomer(customer: CustomerEntity)

    @Query("SELECT COUNT(*) FROM customers")
    suspend fun getCustomerCount(): Int

    @Query("UPDATE customers SET currentBalance = currentBalance + :amountDiff, totalPurchases = totalPurchases + :purchasesDiff, totalPaid = totalPaid + :paidDiff WHERE id = :customerId")
    suspend fun updateCustomerBalances(customerId: Long, amountDiff: Double, purchasesDiff: Double, paidDiff: Double)

    @Query("SELECT SUM(currentBalance) FROM customers WHERE currentBalance > 0")
    fun getTotalOutstandingDebt(): Flow<Double?>
}

@Dao
interface SaleOrderDao {
    @Query("SELECT * FROM sales_orders ORDER BY timestamp DESC")
    fun getAllOrders(): Flow<List<SaleOrderEntity>>

    @Query("SELECT * FROM sales_orders ORDER BY timestamp DESC")
    suspend fun getAllOrdersDirect(): List<SaleOrderEntity>

    @Query("SELECT * FROM sales_orders WHERE customerId = :customerId ORDER BY timestamp DESC")
    fun getOrdersForCustomer(customerId: Long): Flow<List<SaleOrderEntity>>

    @Query("SELECT * FROM sales_orders WHERE customerId = :customerId AND balanceDue > 0 ORDER BY timestamp ASC")
    suspend fun getUnpaidOrdersForCustomerDirect(customerId: Long): List<SaleOrderEntity>

    @Query("SELECT * FROM sales_orders WHERE customerId = :customerId ORDER BY timestamp ASC")
    suspend fun getAllOrdersForCustomerDirect(customerId: Long): List<SaleOrderEntity>

    @Query("SELECT * FROM sales_orders WHERE balanceDue > 0 ORDER BY timestamp DESC")
    fun getUnpaidOrders(): Flow<List<SaleOrderEntity>>

    @Query("SELECT * FROM sales_orders WHERE timestamp >= :sinceTimestamp")
    suspend fun getOrdersSinceDirect(sinceTimestamp: Long): List<SaleOrderEntity>

    @Query("SELECT * FROM sales_orders WHERE id = :id")
    suspend fun getOrderById(id: Long): SaleOrderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrder(order: SaleOrderEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(orders: List<SaleOrderEntity>): List<Long>

    @Update
    suspend fun updateOrder(order: SaleOrderEntity)

    @Delete
    suspend fun deleteOrder(order: SaleOrderEntity)

    @Query("SELECT SUM(totalAmount) FROM sales_orders")
    fun getTotalRevenue(): Flow<Double?>

    @Query("SELECT SUM(amountPaid) FROM sales_orders")
    fun getTotalCollected(): Flow<Double?>

    @Query("SELECT COUNT(*) FROM sales_orders")
    fun getTotalSalesCount(): Flow<Int>
}

@Dao
interface PaymentDao {
    @Query("SELECT * FROM payments ORDER BY timestamp DESC")
    fun getAllPayments(): Flow<List<PaymentEntity>>

    @Query("SELECT * FROM payments ORDER BY timestamp DESC")
    suspend fun getAllPaymentsDirect(): List<PaymentEntity>

    @Query("SELECT * FROM payments WHERE customerId = :customerId ORDER BY timestamp DESC")
    fun getPaymentsForCustomer(customerId: Long): Flow<List<PaymentEntity>>

    @Query("SELECT * FROM payments WHERE customerId = :customerId ORDER BY timestamp DESC")
    suspend fun getPaymentsForCustomerDirect(customerId: Long): List<PaymentEntity>

    @Query("SELECT * FROM payments WHERE saleOrderId = :saleOrderId ORDER BY timestamp DESC")
    fun getPaymentsForOrder(saleOrderId: Long): Flow<List<PaymentEntity>>

    @Query("SELECT * FROM payments WHERE id = :id")
    suspend fun getPaymentById(id: Long): PaymentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPayment(payment: PaymentEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(payments: List<PaymentEntity>): List<Long>

    @Delete
    suspend fun deletePayment(payment: PaymentEntity)
}

@Dao
interface AppSettingsDao {
    @Query("SELECT * FROM app_settings WHERE id = 1 LIMIT 1")
    fun getSettings(): Flow<AppSettingsEntity?>

    @Query("SELECT * FROM app_settings WHERE id = 1 LIMIT 1")
    suspend fun getSettingsDirect(): AppSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSettings(settings: AppSettingsEntity)
}

@Dao
interface InventoryLogDao {
    @Query("SELECT * FROM inventory_logs ORDER BY timestamp DESC")
    fun getAllLogs(): Flow<List<InventoryLogEntity>>

    @Query("SELECT * FROM inventory_logs ORDER BY timestamp DESC")
    suspend fun getAllLogsDirect(): List<InventoryLogEntity>

    @Query("SELECT * FROM inventory_logs WHERE productId = :productId ORDER BY timestamp DESC")
    fun getLogsForProduct(productId: Long): Flow<List<InventoryLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: InventoryLogEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(logs: List<InventoryLogEntity>): List<Long>
}
