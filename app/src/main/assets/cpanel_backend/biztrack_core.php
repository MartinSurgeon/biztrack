<?php
/**
 * BizTrack shared server code, used by index.php (web dashboard) and sync.php (app sync).
 * Upload this file next to them.
 *
 * Two-way sync rules (same as DatabaseBackupHelper.restoreDatabase in the app):
 *  - products, customers, sales, payments and settings carry updatedAt (ms); the newer
 *    version wins, so neither side blindly overwrites the other;
 *  - stock is never overwritten by sync: every stock change is an inventory log, and each
 *    log not seen before is applied once as a difference;
 *  - customer totals are derived: balance = openingBalance + unpaid sales,
 *    purchases = openingPurchases + sales totals, paid = purchases - balance;
 *  - deletions are kept in deleted_records so a record deleted on one side is deleted on
 *    the other instead of being sent back.
 */

if (defined('BIZTRACK_CORE')) return;
define('BIZTRACK_CORE', 1);

/** Bump when the schema changes; upgrades then run once instead of on every request. */
const BT_SCHEMA_VERSION = 4;

/** Web-created records use IDs from 1e15 upward; phones use lower ranges (RecordIds.kt). */
const WEB_ID_BASE = 1000000000000000;

const BT_SYNC_TABLES = ['products', 'customers', 'sales_orders', 'payments'];

function bt_now_ms(): int { return (int)round(microtime(true) * 1000); }

// ---------------------------------------------------------------------------
// Schema
// ---------------------------------------------------------------------------

function bt_ensure_schema(PDO $db): void {
    $db->exec("CREATE TABLE IF NOT EXISTS sync_meta (k VARCHAR(64) PRIMARY KEY, v VARCHAR(255) NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    $version = (int)($db->query("SELECT v FROM sync_meta WHERE k = 'schema_version'")->fetchColumn() ?: 0);
    if ($version >= BT_SCHEMA_VERSION) return;

    $tables = "
        CREATE TABLE IF NOT EXISTS products (
            id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
            name VARCHAR(255) NOT NULL,
            category VARCHAR(100) DEFAULT 'GENERAL',
            price DECIMAL(12,2) DEFAULT 0.00,
            unitPrice DECIMAL(12,2) DEFAULT 0.00,
            costPrice DECIMAL(12,2) DEFAULT 0.00,
            stockQuantity DECIMAL(12,2) DEFAULT 0.00,
            unit VARCHAR(50) DEFAULT 'Piece',
            minStockThreshold DECIMAL(12,2) DEFAULT 5.00,
            description TEXT NULL,
            imageUri VARCHAR(512) DEFAULT '',
            imageUrl VARCHAR(512) NOT NULL DEFAULT '',
            lastRestockedAt BIGINT DEFAULT 0,
            updatedAt BIGINT NOT NULL DEFAULT 0
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

        CREATE TABLE IF NOT EXISTS customers (
            id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
            name VARCHAR(255) NOT NULL,
            phone VARCHAR(64) DEFAULT '',
            whatsapp VARCHAR(64) DEFAULT '',
            address VARCHAR(255) DEFAULT '',
            notes TEXT NULL,
            totalDebt DECIMAL(12,2) DEFAULT 0.00,
            currentBalance DECIMAL(12,2) DEFAULT 0.00,
            totalPurchases DECIMAL(12,2) DEFAULT 0.00,
            totalPurchased DECIMAL(12,2) DEFAULT 0.00,
            totalPaid DECIMAL(12,2) DEFAULT 0.00,
            openingBalance DECIMAL(12,2) NOT NULL DEFAULT 0.00,
            openingPurchases DECIMAL(12,2) NOT NULL DEFAULT 0.00,
            createdAt BIGINT DEFAULT 0,
            lastTransactionDate BIGINT DEFAULT 0,
            updatedAt BIGINT NOT NULL DEFAULT 0
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

        CREATE TABLE IF NOT EXISTS sales_orders (
            id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
            invoiceNumber VARCHAR(100) NOT NULL,
            customerId BIGINT UNSIGNED NULL,
            customerName VARCHAR(255) NOT NULL,
            customerPhone VARCHAR(64) DEFAULT '',
            customerWhatsapp VARCHAR(64) DEFAULT '',
            itemsJson LONGTEXT NULL,
            totalAmount DECIMAL(12,2) DEFAULT 0.00,
            amountPaid DECIMAL(12,2) DEFAULT 0.00,
            debtAmount DECIMAL(12,2) DEFAULT 0.00,
            balanceDue DECIMAL(12,2) DEFAULT 0.00,
            discountAmount DECIMAL(12,2) DEFAULT 0.00,
            paymentStatus VARCHAR(50) DEFAULT 'PAID',
            paymentMethod VARCHAR(50) DEFAULT 'CASH',
            notes TEXT NULL,
            timestamp BIGINT NOT NULL DEFAULT 0,
            smsSentCount INT DEFAULT 0,
            lastSmsTimestamp BIGINT NULL,
            updatedAt BIGINT NOT NULL DEFAULT 0
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

        CREATE TABLE IF NOT EXISTS payments (
            id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
            customerId BIGINT UNSIGNED NOT NULL,
            customerName VARCHAR(255) NOT NULL,
            saleOrderId BIGINT UNSIGNED NULL,
            amount DECIMAL(12,2) DEFAULT 0.00,
            paymentMethod VARCHAR(50) DEFAULT 'CASH',
            notes TEXT NULL,
            timestamp BIGINT NOT NULL DEFAULT 0,
            balanceAfterPayment DECIMAL(12,2) DEFAULT 0.00,
            updatedAt BIGINT NOT NULL DEFAULT 0
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

        CREATE TABLE IF NOT EXISTS inventory_logs (
            id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
            productId BIGINT UNSIGNED NOT NULL,
            productName VARCHAR(255) NOT NULL,
            changeType VARCHAR(50) NOT NULL,
            quantityChanged DECIMAL(12,2) NOT NULL DEFAULT 0.00,
            quantityAfter DECIMAL(12,2) NOT NULL DEFAULT 0.00,
            unit VARCHAR(50) DEFAULT 'Piece',
            notes TEXT NULL,
            timestamp BIGINT NOT NULL DEFAULT 0
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

        CREATE TABLE IF NOT EXISTS app_settings (
            id INT PRIMARY KEY DEFAULT 1,
            businessName VARCHAR(255) DEFAULT 'My Business',
            businessTagline VARCHAR(255) DEFAULT 'Quality products & reliable service',
            businessPhone VARCHAR(64) DEFAULT '',
            businessLocation VARCHAR(255) DEFAULT '',
            momoPaymentDetails VARCHAR(255) DEFAULT '',
            currencySymbol VARCHAR(20) DEFAULT 'GH₵',
            updatedAt BIGINT NOT NULL DEFAULT 0
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

        CREATE TABLE IF NOT EXISTS deleted_records (
            tableName VARCHAR(32) NOT NULL,
            recordId BIGINT UNSIGNED NOT NULL,
            deletedAt BIGINT NOT NULL,
            PRIMARY KEY (tableName, recordId)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    ";
    // One statement per call: some hosts' MySQL drivers leave a multi-statement call
    // half-read, and every later query on the connection then fails.
    foreach (array_filter(array_map('trim', explode(';', $tables))) as $sql) $db->exec($sql);

    // Columns missing from tables created by older versions.
    $columns = [
        'products' => [
            'unitPrice' => "DECIMAL(12,2) NOT NULL DEFAULT 0.00", 'price' => "DECIMAL(12,2) NOT NULL DEFAULT 0.00",
            'costPrice' => "DECIMAL(12,2) NOT NULL DEFAULT 0.00", 'minStockThreshold' => "DECIMAL(12,2) NOT NULL DEFAULT 5.00",
            'unit' => "VARCHAR(32) NOT NULL DEFAULT 'Piece'", 'lastRestockedAt' => "BIGINT NOT NULL DEFAULT 0",
            'description' => "TEXT NULL", 'imageUrl' => "VARCHAR(512) NOT NULL DEFAULT ''",
            'imageUri' => "VARCHAR(512) NOT NULL DEFAULT ''", 'updatedAt' => "BIGINT NOT NULL DEFAULT 0",
        ],
        'customers' => [
            'currentBalance' => "DECIMAL(12,2) NOT NULL DEFAULT 0.00", 'totalDebt' => "DECIMAL(12,2) NOT NULL DEFAULT 0.00",
            'totalPurchases' => "DECIMAL(12,2) NOT NULL DEFAULT 0.00", 'totalPurchased' => "DECIMAL(12,2) NOT NULL DEFAULT 0.00",
            'totalPaid' => "DECIMAL(12,2) NOT NULL DEFAULT 0.00", 'whatsapp' => "VARCHAR(64) NOT NULL DEFAULT ''",
            'phone' => "VARCHAR(64) NOT NULL DEFAULT ''", 'address' => "VARCHAR(255) NOT NULL DEFAULT ''",
            'notes' => "TEXT NULL", 'lastTransactionDate' => "BIGINT NOT NULL DEFAULT 0", 'createdAt' => "BIGINT NOT NULL DEFAULT 0",
            'openingBalance' => "DECIMAL(12,2) NOT NULL DEFAULT 0.00", 'openingPurchases' => "DECIMAL(12,2) NOT NULL DEFAULT 0.00",
            'updatedAt' => "BIGINT NOT NULL DEFAULT 0",
        ],
        'sales_orders' => [
            'invoiceNumber' => "VARCHAR(100) NOT NULL DEFAULT ''", 'customerId' => "BIGINT UNSIGNED NULL",
            'customerName' => "VARCHAR(255) NOT NULL DEFAULT ''", 'customerPhone' => "VARCHAR(64) NOT NULL DEFAULT ''",
            'customerWhatsapp' => "VARCHAR(64) NOT NULL DEFAULT ''", 'itemsJson' => "LONGTEXT NULL",
            'totalAmount' => "DECIMAL(12,2) NOT NULL DEFAULT 0.00", 'amountPaid' => "DECIMAL(12,2) NOT NULL DEFAULT 0.00",
            'debtAmount' => "DECIMAL(12,2) NOT NULL DEFAULT 0.00", 'balanceDue' => "DECIMAL(12,2) NOT NULL DEFAULT 0.00",
            'discountAmount' => "DECIMAL(12,2) NOT NULL DEFAULT 0.00", 'paymentStatus' => "VARCHAR(32) NOT NULL DEFAULT 'PAID'",
            'paymentMethod' => "VARCHAR(32) NOT NULL DEFAULT 'CASH'", 'notes' => "TEXT NULL",
            'timestamp' => "BIGINT NOT NULL DEFAULT 0", 'smsSentCount' => "INT NOT NULL DEFAULT 0",
            'lastSmsTimestamp' => "BIGINT NULL", 'updatedAt' => "BIGINT NOT NULL DEFAULT 0",
        ],
        'payments' => ['updatedAt' => "BIGINT NOT NULL DEFAULT 0"],
        'app_settings' => ['updatedAt' => "BIGINT NOT NULL DEFAULT 0"],
    ];
    foreach ($columns as $table => $cols) {
        foreach ($cols as $col => $def) {
            try {
                if ($db->query("SHOW COLUMNS FROM `$table` LIKE '$col'")->rowCount() === 0) {
                    $db->exec("ALTER TABLE `$table` ADD COLUMN `$col` $def");
                }
            } catch (Exception $e) { error_log("BizTrack schema: $table.$col: " . $e->getMessage()); }
        }
    }

    // One-time data fixes for rows written by older versions.
    $fixes = [
        "UPDATE customers SET currentBalance = totalDebt WHERE (currentBalance IS NULL OR currentBalance = 0) AND totalDebt > 0",
        "UPDATE customers SET totalPurchases = totalPurchased WHERE (totalPurchases IS NULL OR totalPurchases = 0) AND totalPurchased > 0",
        "UPDATE products SET unitPrice = price WHERE (unitPrice IS NULL OR unitPrice = 0) AND price > 0",
        "UPDATE products SET price = unitPrice WHERE (price IS NULL OR price = 0) AND unitPrice > 0",
        "UPDATE sales_orders SET invoiceNumber = CONCAT('INV-', id) WHERE invoiceNumber IS NULL OR invoiceNumber = ''",
        "UPDATE sales_orders SET balanceDue = debtAmount WHERE (balanceDue IS NULL OR balanceDue = 0) AND debtAmount > 0",
    ];
    foreach ($fixes as $sql) {
        try { $db->exec($sql); } catch (Exception $e) { error_log('BizTrack schema fix: ' . $e->getMessage()); }
    }

    // Everything below needs these columns. If one could not be added (for example the
    // database user lacks ALTER permission), stop here: nothing is marked as done, so the
    // upgrade runs again on the next request once the problem is fixed.
    $missing = [];
    foreach (['products' => ['updatedAt'], 'customers' => ['updatedAt', 'openingBalance', 'openingPurchases', 'currentBalance', 'totalPurchases'],
              'sales_orders' => ['updatedAt', 'balanceDue', 'totalAmount'], 'payments' => ['updatedAt'], 'app_settings' => ['updatedAt']] as $table => $cols) {
        $have = array_column($db->query("SHOW COLUMNS FROM `$table`")->fetchAll(PDO::FETCH_ASSOC), 'Field');
        foreach ($cols as $col) if (!in_array($col, $have, true)) $missing[] = "$table.$col";
    }
    if ($missing) {
        throw new RuntimeException('Could not add the columns ' . implode(', ', $missing)
            . '. Make sure the database user has ALTER and CREATE permission (cPanel > MySQL Databases > user privileges: ALL PRIVILEGES).');
    }

    // Two-way sync (v4): whatever a balance doesn't get from sales becomes the opening
    // amount, so every customer's balance is unchanged after the upgrade.
    if ($version < 4) {
        $db->exec("
            UPDATE customers c
            LEFT JOIN (SELECT customerId, SUM(balanceDue) AS due, SUM(totalAmount) AS total FROM sales_orders GROUP BY customerId) s
                ON s.customerId = c.id
            SET c.openingBalance = c.currentBalance - COALESCE(s.due, 0),
                c.openingPurchases = c.totalPurchases - COALESCE(s.total, 0)
        ");
    }

    $db->prepare("REPLACE INTO sync_meta (k, v) VALUES ('schema_version', ?)")->execute([(string)BT_SCHEMA_VERSION]);
}

// ---------------------------------------------------------------------------
// IDs, derived totals, stock, deletions
// ---------------------------------------------------------------------------

$GLOBALS['bt_issued_ids'] = [];

function bt_next_id(PDO $db, string $table): int {
    if (!in_array($table, ['products', 'customers', 'sales_orders', 'payments', 'inventory_logs'], true)) {
        throw new InvalidArgumentException("Unknown table $table");
    }
    $max = (int)$db->query("SELECT COALESCE(MAX(id), 0) FROM `$table` WHERE id >= " . WEB_ID_BASE)->fetchColumn();
    $id = max($max, WEB_ID_BASE, $GLOBALS['bt_issued_ids'][$table] ?? 0) + 1;
    $GLOBALS['bt_issued_ids'][$table] = $id;
    return $id;
}

/** ID issued by the last bt_next_id() for $table (lastInsertId() is 0 for explicit IDs). */
function bt_last_id(string $table): int {
    return (int)($GLOBALS['bt_issued_ids'][$table] ?? 0);
}

/** Re-derives customer totals from their sales (both old and new column names). */
function bt_recalc_customers(PDO $db): void {
    $db->exec("
        UPDATE customers c
        LEFT JOIN (SELECT customerId, SUM(balanceDue) AS due, SUM(totalAmount) AS total FROM sales_orders GROUP BY customerId) s
            ON s.customerId = c.id
        SET c.currentBalance = GREATEST(0, c.openingBalance + COALESCE(s.due, 0)),
            c.totalDebt      = GREATEST(0, c.openingBalance + COALESCE(s.due, 0)),
            c.totalPurchases = c.openingPurchases + COALESCE(s.total, 0),
            c.totalPurchased = c.openingPurchases + COALESCE(s.total, 0)
    ");
    $db->exec("UPDATE customers SET totalPaid = GREATEST(0, totalPurchases - currentBalance)");
}

/** Changes stock by $delta and logs it (the log is what syncs). Returns the new stock. */
function bt_adjust_stock(PDO $db, int $productId, float $delta, string $type, string $notes): ?float {
    $q = $db->prepare("SELECT name, unit, stockQuantity FROM products WHERE id = ?");
    $q->execute([$productId]);
    $p = $q->fetch(PDO::FETCH_ASSOC);
    if (!$p) return null;
    $now = bt_now_ms();
    $after = (float)$p['stockQuantity'] + $delta;
    $db->prepare("UPDATE products SET stockQuantity = ?" . ($delta > 0 ? ", lastRestockedAt = ?" : "") . " WHERE id = ?")
       ->execute($delta > 0 ? [$after, $now, $productId] : [$after, $productId]);
    $db->prepare("INSERT INTO inventory_logs (id, productId, productName, changeType, quantityChanged, quantityAfter, unit, notes, timestamp) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")
       ->execute([bt_next_id($db, 'inventory_logs'), $productId, $p['name'], $type, $delta, $after, $p['unit'], $notes, $now]);
    return $after;
}

function bt_record_deletion(PDO $db, string $table, int $id, ?int $at = null): void {
    if (!in_array($table, BT_SYNC_TABLES, true)) return;
    $db->prepare("INSERT INTO deleted_records (tableName, recordId, deletedAt) VALUES (?, ?, ?)
                  ON DUPLICATE KEY UPDATE deletedAt = GREATEST(deletedAt, VALUES(deletedAt))")
       ->execute([$table, $id, $at ?? bt_now_ms()]);
}

/** Whether an incoming change should replace the stored one (newer wins). */
function bt_is_newer(int $incoming, int $stored): bool {
    // Both 0: data from apps that predate change tracking; accept it like before.
    return $incoming > $stored || ($incoming === 0 && $stored === 0);
}

// ---------------------------------------------------------------------------
// Merge a push from the app (mirrors DatabaseBackupHelper.restoreDatabase)
// ---------------------------------------------------------------------------

function bt_merge_backup(PDO $db, array $b): array {
    $stats = ['products' => 0, 'customers' => 0, 'sales' => 0, 'payments' => 0, 'logs' => 0, 'deleted' => 0, 'skipped' => 0];
    $removedPhotos = [];
    $db->beginTransaction();
    try {
        // 1. Deletions from the phone: remember them; delete here unless edited here later.
        foreach (($b['deletedRecords'] ?? []) as $d) {
            $t = (string)($d['tableName'] ?? ''); $id = (int)($d['recordId'] ?? 0); $at = (int)($d['deletedAt'] ?? 0);
            if (!in_array($t, BT_SYNC_TABLES, true) || $id === 0) continue;
            bt_record_deletion($db, $t, $id, $at);
            if ($t === 'products') {
                $img = $db->prepare("SELECT imageUrl FROM products WHERE id = ? AND updatedAt <= ?");
                $img->execute([$id, $at]);
                if ($u = $img->fetchColumn()) $removedPhotos[] = (string)$u;
            }
            $del = $db->prepare("DELETE FROM `$t` WHERE id = ? AND updatedAt <= ?");
            $del->execute([$id, $at]);
            $stats['deleted'] += $del->rowCount();
        }
        $deleted = [];
        foreach ($db->query("SELECT tableName, recordId, deletedAt FROM deleted_records") as $r) {
            $deleted[$r['tableName'] . ':' . $r['recordId']] = (int)$r['deletedAt'];
        }
        $isDeleted = fn(string $t, int $id, int $upd) => isset($deleted["$t:$id"]) && $deleted["$t:$id"] >= $upd;
        $stored = function (string $t) use ($db): array {
            $m = [];
            foreach ($db->query("SELECT id, updatedAt FROM `$t`") as $r) $m[(int)$r['id']] = (int)$r['updatedAt'];
            return $m;
        };

        // 2. Products: newer wins; stock is left to the logs below.
        $have = $stored('products');
        $newProducts = [];
        $imgQ = $db->prepare("SELECT imageUrl FROM products WHERE id = ?");
        foreach (($b['products'] ?? []) as $p) {
            $id = (int)($p['id'] ?? 0); $upd = (int)($p['updatedAt'] ?? 0);
            if ($id === 0) continue;
            if ($isDeleted('products', $id, $upd)) { $stats['skipped']++; continue; }
            $remote = trim((string)($p['remoteImageUrl'] ?? ''));
            $price = (float)($p['unitPrice'] ?? $p['price'] ?? 0);
            $fields = [
                'name' => (string)($p['name'] ?? 'Product'), 'category' => (string)($p['category'] ?? 'GENERAL'),
                'price' => $price, 'unitPrice' => $price, 'costPrice' => (float)($p['costPrice'] ?? 0),
                'unit' => (string)($p['unit'] ?? 'Piece'), 'minStockThreshold' => (float)($p['minStockThreshold'] ?? 5),
                'description' => (string)($p['description'] ?? ''), 'lastRestockedAt' => (int)($p['lastRestockedAt'] ?? 0),
                'updatedAt' => $upd,
            ];
            if (!array_key_exists($id, $have)) {
                $fields['id'] = $id;
                $fields['stockQuantity'] = (float)($p['stockQuantity'] ?? 0);
                $fields['imageUrl'] = preg_match('#^https?://#i', $remote) ? substr($remote, 0, 512) : '';
                bt_insert($db, 'products', $fields);
                $newProducts[$id] = true;
                $stats['products']++;
            } elseif (bt_is_newer($upd, $have[$id])) {
                if ($remote === 'none' || preg_match('#^https?://#i', $remote)) {
                    $imgQ->execute([$id]);
                    $prev = (string)$imgQ->fetchColumn();
                    $fields['imageUrl'] = $remote === 'none' ? '' : substr($remote, 0, 512);
                    if ($prev !== '' && $prev !== $fields['imageUrl']) $removedPhotos[] = $prev;
                }
                bt_update($db, 'products', $id, $fields);
                $stats['products']++;
            }
        }

        // 3. Stock changes: apply each log not seen before, except for products that just
        //    arrived (their stock number already includes them).
        $knownLogs = array_flip($db->query("SELECT id FROM inventory_logs")->fetchAll(PDO::FETCH_COLUMN));
        $productExists = $db->prepare("SELECT 1 FROM products WHERE id = ?");
        foreach (($b['inventoryLogs'] ?? []) as $l) {
            $id = (int)($l['id'] ?? 0); $pid = (int)($l['productId'] ?? 0);
            if ($id === 0 || isset($knownLogs[$id])) continue;
            $qty = (float)($l['quantityChanged'] ?? 0);
            bt_insert($db, 'inventory_logs', [
                'id' => $id, 'productId' => $pid, 'productName' => (string)($l['productName'] ?? ''),
                'changeType' => (string)($l['changeType'] ?? 'ADJUSTMENT'), 'quantityChanged' => $qty,
                'quantityAfter' => (float)($l['quantityAfter'] ?? 0), 'unit' => (string)($l['unit'] ?? ''),
                'notes' => (string)($l['notes'] ?? ''), 'timestamp' => (int)($l['timestamp'] ?? bt_now_ms()),
            ]);
            $knownLogs[$id] = true;
            $stats['logs']++;
            $productExists->execute([$pid]);
            if (!isset($newProducts[$pid]) && $productExists->fetchColumn()) {
                $db->prepare("UPDATE products SET stockQuantity = stockQuantity + ? WHERE id = ?")->execute([$qty, $pid]);
            }
        }

        // 4. Customers: newer wins. Their totals are re-derived at the end.
        $have = $stored('customers');
        $legacyCustomers = []; // id => [balance, purchases] from apps without opening amounts
        foreach (($b['customers'] ?? []) as $c) {
            $id = (int)($c['id'] ?? 0); $upd = (int)($c['updatedAt'] ?? 0);
            if ($id === 0) continue;
            if ($isDeleted('customers', $id, $upd)) { $stats['skipped']++; continue; }
            $fields = [
                'name' => (string)($c['name'] ?? 'Customer'), 'phone' => (string)($c['phone'] ?? ''),
                'whatsapp' => (string)($c['whatsapp'] ?? ''), 'address' => (string)($c['address'] ?? ''),
                'notes' => (string)($c['notes'] ?? ''), 'createdAt' => (int)($c['createdAt'] ?? 0),
                'openingBalance' => (float)($c['openingBalance'] ?? 0), 'openingPurchases' => (float)($c['openingPurchases'] ?? 0),
                'updatedAt' => $upd,
            ];
            $accepted = false;
            if (!array_key_exists($id, $have)) { $fields['id'] = $id; bt_insert($db, 'customers', $fields); $accepted = true; }
            elseif (bt_is_newer($upd, $have[$id])) { bt_update($db, 'customers', $id, $fields); $accepted = true; }
            if ($accepted) {
                $stats['customers']++;
                if (!array_key_exists('openingBalance', $c)) {
                    $legacyCustomers[$id] = [(float)($c['currentBalance'] ?? $c['totalDebt'] ?? 0), (float)($c['totalPurchases'] ?? $c['totalPurchased'] ?? 0)];
                }
            }
        }

        // 5. Sales and payments: newer wins.
        $have = $stored('sales_orders');
        foreach (($b['salesOrders'] ?? []) as $s) {
            $id = (int)($s['id'] ?? 0); $upd = (int)($s['updatedAt'] ?? 0);
            if ($id === 0) continue;
            if ($isDeleted('sales_orders', $id, $upd)) { $stats['skipped']++; continue; }
            $due = (float)($s['balanceDue'] ?? $s['debtAmount'] ?? 0);
            $fields = [
                'invoiceNumber' => trim((string)($s['invoiceNumber'] ?? '')) ?: ('INV-' . $id),
                'customerId' => !empty($s['customerId']) ? (int)$s['customerId'] : null,
                'customerName' => (string)($s['customerName'] ?? ''), 'customerPhone' => (string)($s['customerPhone'] ?? ''),
                'customerWhatsapp' => (string)($s['customerWhatsapp'] ?? ''), 'itemsJson' => (string)($s['itemsJson'] ?? '[]'),
                'totalAmount' => (float)($s['totalAmount'] ?? 0), 'amountPaid' => (float)($s['amountPaid'] ?? 0),
                'balanceDue' => $due, 'debtAmount' => $due, 'discountAmount' => (float)($s['discountAmount'] ?? 0),
                'paymentStatus' => (string)($s['paymentStatus'] ?? 'PAID'), 'paymentMethod' => (string)($s['paymentMethod'] ?? 'CASH'),
                'notes' => (string)($s['notes'] ?? ''), 'timestamp' => (int)($s['timestamp'] ?? bt_now_ms()),
                'smsSentCount' => (int)($s['smsSentCount'] ?? 0),
                'lastSmsTimestamp' => isset($s['lastSmsTimestamp']) ? (int)$s['lastSmsTimestamp'] : null,
                'updatedAt' => $upd,
            ];
            if (!array_key_exists($id, $have)) { $fields['id'] = $id; bt_insert($db, 'sales_orders', $fields); $stats['sales']++; }
            elseif (bt_is_newer($upd, $have[$id])) { bt_update($db, 'sales_orders', $id, $fields); $stats['sales']++; }
        }
        $have = $stored('payments');
        foreach (($b['payments'] ?? []) as $pm) {
            $id = (int)($pm['id'] ?? 0); $upd = (int)($pm['updatedAt'] ?? 0);
            if ($id === 0) continue;
            if ($isDeleted('payments', $id, $upd)) { $stats['skipped']++; continue; }
            $fields = [
                'customerId' => (int)($pm['customerId'] ?? 0), 'customerName' => (string)($pm['customerName'] ?? ''),
                'saleOrderId' => !empty($pm['saleOrderId']) ? (int)$pm['saleOrderId'] : null,
                'amount' => (float)($pm['amount'] ?? 0), 'paymentMethod' => (string)($pm['paymentMethod'] ?? 'CASH'),
                'notes' => (string)($pm['notes'] ?? ''), 'timestamp' => (int)($pm['timestamp'] ?? bt_now_ms()),
                'balanceAfterPayment' => (float)($pm['balanceAfterPayment'] ?? 0), 'updatedAt' => $upd,
            ];
            if (!array_key_exists($id, $have)) { $fields['id'] = $id; bt_insert($db, 'payments', $fields); $stats['payments']++; }
            elseif (bt_is_newer($upd, $have[$id])) { bt_update($db, 'payments', $id, $fields); $stats['payments']++; }
        }

        // 6. Customers from older apps carry totals, not opening amounts: work those out
        //    from their sales so their balance comes out unchanged.
        $sums = $db->prepare("SELECT COALESCE(SUM(balanceDue), 0), COALESCE(SUM(totalAmount), 0) FROM sales_orders WHERE customerId = ?");
        foreach ($legacyCustomers as $id => [$balance, $purchases]) {
            $sums->execute([$id]);
            [$due, $total] = $sums->fetch(PDO::FETCH_NUM);
            $db->prepare("UPDATE customers SET openingBalance = ?, openingPurchases = ? WHERE id = ?")
               ->execute([$balance - (float)$due, $purchases - (float)$total, $id]);
        }

        // 7. Settings: the side edited last wins (secrets are never synced).
        if (!empty($b['settings'])) {
            $st = $b['settings'];
            $upd = (int)($st['updatedAt'] ?? 0);
            $cur = (int)($db->query("SELECT COALESCE(MAX(updatedAt), 0) FROM app_settings WHERE id = 1")->fetchColumn());
            if (bt_is_newer($upd, $cur)) {
                $db->prepare("INSERT INTO app_settings (id, businessName, businessTagline, businessPhone, businessLocation, momoPaymentDetails, currencySymbol, updatedAt)
                              VALUES (1, ?, ?, ?, ?, ?, ?, ?)
                              ON DUPLICATE KEY UPDATE businessName = VALUES(businessName), businessTagline = VALUES(businessTagline),
                                businessPhone = VALUES(businessPhone), businessLocation = VALUES(businessLocation),
                                momoPaymentDetails = VALUES(momoPaymentDetails), currencySymbol = VALUES(currencySymbol), updatedAt = VALUES(updatedAt)")
                   ->execute([
                       (string)($st['businessName'] ?? 'My Business'), (string)($st['businessTagline'] ?? ''),
                       (string)($st['businessPhone'] ?? ''), (string)($st['businessLocation'] ?? ''),
                       (string)($st['momoPaymentDetails'] ?? ''), (string)($st['currencySymbol'] ?? 'GH₵'), $upd,
                   ]);
            }
        }

        bt_recalc_customers($db);
        $db->commit();
    } catch (Throwable $e) {
        if ($db->inTransaction()) $db->rollBack();
        throw $e;
    }
    foreach (array_unique($removedPhotos) as $u) bt_forget_product_image($db, $u);
    return $stats;
}

/** INSERT a row from a column => value map (names are code-defined, never user input). */
function bt_insert(PDO $db, string $table, array $fields): void {
    $cols = array_keys($fields);
    $db->prepare("INSERT INTO `$table` (`" . implode('`, `', $cols) . "`) VALUES (" . implode(', ', array_fill(0, count($cols), '?')) . ")")
       ->execute(array_values($fields));
}

function bt_update(PDO $db, string $table, int $id, array $fields): void {
    $set = implode(', ', array_map(fn($c) => "`$c` = ?", array_keys($fields)));
    $db->prepare("UPDATE `$table` SET $set WHERE id = ?")->execute([...array_values($fields), $id]);
}

// ---------------------------------------------------------------------------
// Product photos (stored in ./uploads/products/ next to this file, named by content hash)
// ---------------------------------------------------------------------------

function bt_uploads_dir(): string {
    $dir = __DIR__ . '/uploads/products';
    if (!is_dir($dir)) @mkdir($dir, 0755, true);
    // Never serve listings or run scripts from the uploads folder. (php_flag is avoided on
    // purpose: it breaks the folder with a 500 error on PHP-FPM hosts.)
    $ht = __DIR__ . '/uploads/.htaccess';
    if (!file_exists($ht)) {
        @file_put_contents($ht, "Options -Indexes\nRemoveHandler .php .phtml .php3 .php4 .php5 .php7 .php8 .phar\n"
            . "<FilesMatch \"\\.(php[0-9]?|phtml|phar|pl|py|cgi|sh)$\">\n"
            . "  <IfModule mod_authz_core.c>\n    Require all denied\n  </IfModule>\n"
            . "  <IfModule !mod_authz_core.c>\n    Deny from all\n  </IfModule>\n</FilesMatch>\n");
    }
    if (!file_exists(__DIR__ . '/uploads/index.html')) @file_put_contents(__DIR__ . '/uploads/index.html', '');
    if (!file_exists($dir . '/index.html')) @file_put_contents($dir . '/index.html', '');
    return $dir;
}

/** Public URL of the uploads folder, e.g. https://example.com/biztrack/uploads/products */
function bt_uploads_base_url(): string {
    $https = (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off')
        || strtolower((string)($_SERVER['HTTP_X_FORWARDED_PROTO'] ?? '')) === 'https';
    $host = preg_replace('/[^A-Za-z0-9.\-:\[\]]/', '', (string)($_SERVER['HTTP_HOST'] ?? 'localhost'));
    $dir = rtrim(str_replace('\\', '/', dirname((string)($_SERVER['SCRIPT_NAME'] ?? '/'))), '/');
    return ($https ? 'https' : 'http') . '://' . $host . $dir . '/uploads/products';
}

/**
 * Validates and stores an uploaded image ($_FILES entry). Returns [url, null] or [null, error].
 * Accepts JPEG, PNG and WebP up to 6 MB. With GD available the image is resized to at most
 * 1024px and re-encoded as JPEG, which also strips anything embedded in the file.
 */
function bt_store_product_image(array $file): array {
    if (($file['error'] ?? UPLOAD_ERR_NO_FILE) !== UPLOAD_ERR_OK) return [null, 'Upload failed (code ' . (int)($file['error'] ?? -1) . ').'];
    if (!is_uploaded_file($file['tmp_name'] ?? '')) return [null, 'Invalid upload.'];
    if ((int)$file['size'] <= 0 || (int)$file['size'] > 6 * 1024 * 1024) return [null, 'Photo must be smaller than 6 MB.'];
    $info = @getimagesize($file['tmp_name']);
    $types = [IMAGETYPE_JPEG => 'jpg', IMAGETYPE_PNG => 'png'];
    if (defined('IMAGETYPE_WEBP')) $types[IMAGETYPE_WEBP] = 'webp';
    if (!$info || !isset($types[$info[2]])) return [null, 'Only JPEG, PNG or WebP photos are allowed.'];

    $bytes = null;
    $ext = $types[$info[2]];
    if (function_exists('imagecreatefromstring') && function_exists('imagejpeg')) {
        $src = @imagecreatefromstring((string)file_get_contents($file['tmp_name']));
        if ($src) {
            $w = imagesx($src); $h = imagesy($src);
            $scale = min(1, 1024 / max($w, $h));
            $nw = max(1, (int)round($w * $scale)); $nh = max(1, (int)round($h * $scale));
            $dst = imagecreatetruecolor($nw, $nh);
            imagefill($dst, 0, 0, imagecolorallocate($dst, 255, 255, 255)); // flatten transparency onto white
            imagecopyresampled($dst, $src, 0, 0, 0, 0, $nw, $nh, $w, $h);
            ob_start(); imagejpeg($dst, null, 85); $bytes = ob_get_clean();
            $src = $dst = null; // GD images are freed automatically (imagedestroy is deprecated)
            $ext = 'jpg';
        }
    }
    if ($bytes === null) $bytes = (string)file_get_contents($file['tmp_name']);

    $name = sha1($bytes) . '.' . $ext;
    $path = bt_uploads_dir() . '/' . $name;
    if (!file_exists($path) && @file_put_contents($path, $bytes, LOCK_EX) === false) return [null, 'Could not save the photo on the server.'];
    return [bt_uploads_base_url() . '/' . $name, null];
}

/** Deletes a stored photo if no product uses it any more. */
function bt_forget_product_image(PDO $db, string $url): void {
    if ($url === '' || !preg_match('#/uploads/products/([a-f0-9]{40}\.(?:jpg|png|webp))$#', $url, $m)) return;
    $q = $db->prepare("SELECT COUNT(*) FROM products WHERE imageUrl = ?");
    $q->execute([$url]);
    if ((int)$q->fetchColumn() === 0) @unlink(bt_uploads_dir() . '/' . $m[1]);
}
