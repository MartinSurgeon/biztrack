-- ==========================================================
-- BizTrack POS & Business Manager - MySQL Database Schema for Namecheap cPanel
-- Import this file into your MySQL database via phpMyAdmin
-- ==========================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- 1. Products Table
CREATE TABLE IF NOT EXISTS `products` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(255) NOT NULL,
  `category` VARCHAR(64) NOT NULL DEFAULT 'OTHER',
  `price` DECIMAL(12,2) NOT NULL DEFAULT 0.00,
  `unitPrice` DECIMAL(12,2) NOT NULL DEFAULT 0.00,
  `costPrice` DECIMAL(12,2) NOT NULL DEFAULT 0.00,
  `stockQuantity` DECIMAL(12,2) NOT NULL DEFAULT 0.00,
  `unit` VARCHAR(32) NOT NULL DEFAULT 'unit',
  `minStockThreshold` DECIMAL(12,2) NOT NULL DEFAULT 5.00,
  `description` TEXT,
  `imageUri` VARCHAR(512) NOT NULL DEFAULT '',
  `lastRestockedAt` BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 2. Customers Table (Debtors Ledger)
CREATE TABLE IF NOT EXISTS `customers` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(255) NOT NULL,
  `phone` VARCHAR(64) NOT NULL DEFAULT '',
  `whatsapp` VARCHAR(64) NOT NULL DEFAULT '',
  `address` VARCHAR(255) NOT NULL DEFAULT '',
  `notes` TEXT,
  `totalDebt` DECIMAL(12,2) NOT NULL DEFAULT 0.00,
  `currentBalance` DECIMAL(12,2) NOT NULL DEFAULT 0.00,
  `totalPurchased` DECIMAL(12,2) NOT NULL DEFAULT 0.00,
  `totalPurchases` DECIMAL(12,2) NOT NULL DEFAULT 0.00,
  `totalPaid` DECIMAL(12,2) NOT NULL DEFAULT 0.00,
  `lastTransactionDate` BIGINT NOT NULL DEFAULT 0,
  `createdAt` BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3. Sales Orders Table
CREATE TABLE IF NOT EXISTS `sales_orders` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `invoiceNumber` VARCHAR(64) NOT NULL DEFAULT '',
  `customerId` BIGINT UNSIGNED NULL,
  `customerName` VARCHAR(255) NOT NULL,
  `customerPhone` VARCHAR(64) NOT NULL DEFAULT '',
  `customerWhatsapp` VARCHAR(64) NOT NULL DEFAULT '',
  `itemsJson` MEDIUMTEXT NOT NULL,
  `totalAmount` DECIMAL(12,2) NOT NULL DEFAULT 0.00,
  `amountPaid` DECIMAL(12,2) NOT NULL DEFAULT 0.00,
  `debtAmount` DECIMAL(12,2) NOT NULL DEFAULT 0.00,
  `balanceDue` DECIMAL(12,2) NOT NULL DEFAULT 0.00,
  `discountAmount` DECIMAL(12,2) NOT NULL DEFAULT 0.00,
  `paymentStatus` VARCHAR(32) NOT NULL DEFAULT 'PAID',
  `paymentMethod` VARCHAR(32) NOT NULL DEFAULT 'CASH',
  `notes` TEXT,
  `timestamp` BIGINT NOT NULL DEFAULT 0,
  `smsSentCount` INT NOT NULL DEFAULT 0,
  `lastSmsTimestamp` BIGINT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_customer_id` (`customerId`),
  KEY `idx_timestamp` (`timestamp`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 4. Payments Table
CREATE TABLE IF NOT EXISTS `payments` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `customerId` BIGINT UNSIGNED NOT NULL,
  `customerName` VARCHAR(255) NOT NULL,
  `saleOrderId` BIGINT UNSIGNED NULL,
  `amount` DECIMAL(12,2) NOT NULL DEFAULT 0.00,
  `paymentMethod` VARCHAR(32) NOT NULL DEFAULT 'CASH',
  `notes` TEXT,
  `timestamp` BIGINT NOT NULL DEFAULT 0,
  `balanceAfterPayment` DECIMAL(12,2) NOT NULL DEFAULT 0.00,
  PRIMARY KEY (`id`),
  KEY `idx_payment_customer` (`customerId`),
  KEY `idx_payment_time` (`timestamp`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 5. Inventory Logs Table
CREATE TABLE IF NOT EXISTS `inventory_logs` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `productId` BIGINT UNSIGNED NOT NULL,
  `productName` VARCHAR(255) NOT NULL,
  `changeType` VARCHAR(64) NOT NULL,
  `quantityChanged` DECIMAL(12,2) NOT NULL DEFAULT 0.00,
  `quantityAfter` DECIMAL(12,2) NOT NULL DEFAULT 0.00,
  `unit` VARCHAR(32) NOT NULL DEFAULT 'unit',
  `notes` TEXT,
  `timestamp` BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_log_product` (`productId`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 6. Business Profile & App Settings Table
CREATE TABLE IF NOT EXISTS `app_settings` (
  `id` INT UNSIGNED NOT NULL DEFAULT 1,
  `businessName` VARCHAR(255) NOT NULL DEFAULT 'BizTrack Business',
  `businessTagline` VARCHAR(255) NOT NULL DEFAULT 'Quality products & reliable service',
  `businessPhone` VARCHAR(64) NOT NULL DEFAULT '024 000 0000',
  `businessLocation` VARCHAR(255) NOT NULL DEFAULT 'Ghana',
  `momoPaymentDetails` TEXT,
  `smsApiKey` VARCHAR(255) NOT NULL DEFAULT '',
  `smsSenderId` VARCHAR(64) NOT NULL DEFAULT 'BizTrack',
  `currencySymbol` VARCHAR(16) NOT NULL DEFAULT 'GH₵',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET FOREIGN_KEY_CHECKS = 1;
