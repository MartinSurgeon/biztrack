<?php
/**
 * =========================================================================
 * BIZTRACK POS & BUSINESS MANAGER - WEB DASHBOARD
 * Complete CRUD Web Interface for Laptop & Desktop Browsers
 * 
 * Works directly with your Namecheap / cPanel MySQL database.
 * Changes made here immediately reflect in your Android app upon sync!
 * =========================================================================
 */

// Enable full error reporting to diagnose issues rather than showing a blank 500 error
ini_set('display_errors', '1');
ini_set('display_startup_errors', '1');
error_reporting(E_ALL);

// Safe session startup
if (session_status() === PHP_SESSION_NONE) {
    @session_start();
}

// ----------------------------------------------------
// 1. DYNAMIC CONFIGURATION (Auto-detected from sync.php / config.php)
// ----------------------------------------------------
$configFile = __DIR__ . '/config.php';
$syncFile   = __DIR__ . '/sync.php';

$DB_HOST = 'localhost';
$DB_NAME = '';
$DB_USER = '';
$DB_PASS = '';
$SECRET_KEY = 'naadriel_farm_secret_2026';

// 1. Try loading from custom config.php if saved
if (file_exists($configFile)) {
    @include $configFile;
}

// 2. If credentials are blank, auto-extract from existing sync.php
if (empty($DB_NAME) && file_exists($syncFile)) {
    $syncCode = @file_get_contents($syncFile);
    if ($syncCode) {
        if (preg_match('/\$DB_HOST\s*=\s*[\'"](.*?)[\'"];/', $syncCode, $m)) $DB_HOST = $m[1];
        if (preg_match('/\$DB_NAME\s*=\s*[\'"](.*?)[\'"];/', $syncCode, $m)) $DB_NAME = $m[1];
        if (preg_match('/\$DB_USER\s*=\s*[\'"](.*?)[\'"];/', $syncCode, $m)) $DB_USER = $m[1];
        if (preg_match('/\$DB_PASS\s*=\s*[\'"](.*?)[\'"];/', $syncCode, $m)) $DB_PASS = $m[1];
        if (preg_match('/\$API_SECRET_KEY\s*=\s*[\'"](.*?)[\'"];/', $syncCode, $m)) $SECRET_KEY = $m[1];
        if (preg_match('/\$SECRET_KEY\s*=\s*[\'"](.*?)[\'"];/', $syncCode, $m)) $SECRET_KEY = $m[1];
    }
}

// Ensure $SECRET_KEY is defined safely
if (empty($SECRET_KEY)) {
    if (isset($API_SECRET_KEY)) {
        $SECRET_KEY = $API_SECRET_KEY;
    } elseif (defined('SECRET_KEY')) {
        $SECRET_KEY = constant('SECRET_KEY');
    } else {
        $SECRET_KEY = 'naadriel_farm_secret_2026';
    }
}

// Handle Configuration Save from Web UI
if ($_SERVER['REQUEST_METHOD'] === 'POST' && isset($_POST['save_db_config'])) {
    $newHost = trim($_POST['db_host'] ?? 'localhost');
    $newName = trim($_POST['db_name'] ?? '');
    $newUser = trim($_POST['db_user'] ?? '');
    $newPass = trim($_POST['db_pass'] ?? '');
    $newKey  = trim($_POST['secret_key'] ?? 'naadriel_farm_secret_2026');

    $configContent = "<?php\n"
        . "\$DB_HOST = " . var_export($newHost, true) . ";\n"
        . "\$DB_NAME = " . var_export($newName, true) . ";\n"
        . "\$DB_USER = " . var_export($newUser, true) . ";\n"
        . "\$DB_PASS = " . var_export($newPass, true) . ";\n"
        . "\$SECRET_KEY = " . var_export($newKey, true) . ";\n"
        . "\$API_SECRET_KEY = " . var_export($newKey, true) . ";\n";

    @file_put_contents($configFile, $configContent);
    header("Location: index.php?msg=" . urlencode("Database configuration saved successfully!"));
    exit;
}

// ----------------------------------------------------
// 2. DATABASE CONNECTION & AUTO-SCHEMA REPAIR / MIGRATION
// ----------------------------------------------------
$db = null;
$dbError = null;

if (!empty($DB_NAME) && !empty($DB_USER)) {
    try {
        $dsn = "mysql:host={$DB_HOST};dbname={$DB_NAME};charset=utf8mb4";
        $db = new PDO($dsn, $DB_USER, $DB_PASS, [
            PDO::ATTR_ERRMODE            => PDO::ERRMODE_EXCEPTION,
            PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
            PDO::ATTR_EMULATE_PREPARES   => false,
        ]);

        // Auto-verify and create essential schema tables if missing
        $db->exec("
            CREATE TABLE IF NOT EXISTS products (
                id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
                name VARCHAR(255) NOT NULL,
                category VARCHAR(100) DEFAULT 'OTHER',
                price DECIMAL(12,2) DEFAULT 0.00,
                unitPrice DECIMAL(12,2) DEFAULT 0.00,
                costPrice DECIMAL(12,2) DEFAULT 0.00,
                stockQuantity DECIMAL(12,2) DEFAULT 0.00,
                unit VARCHAR(50) DEFAULT 'unit',
                minStockThreshold DECIMAL(12,2) DEFAULT 5.00,
                description TEXT NULL,
                imageUri VARCHAR(512) DEFAULT '',
                lastRestockedAt BIGINT DEFAULT 0
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
                createdAt BIGINT DEFAULT 0,
                lastTransactionDate BIGINT DEFAULT 0
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

            CREATE TABLE IF NOT EXISTS sales_orders (
                id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
                invoiceNumber VARCHAR(100) NOT NULL UNIQUE,
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
                lastSmsTimestamp BIGINT NULL
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
                balanceAfterPayment DECIMAL(12,2) DEFAULT 0.00
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

            CREATE TABLE IF NOT EXISTS inventory_logs (
                id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
                productId BIGINT UNSIGNED NOT NULL,
                productName VARCHAR(255) NOT NULL,
                changeType VARCHAR(50) NOT NULL,
                quantityChanged DECIMAL(12,2) NOT NULL DEFAULT 0.00,
                quantityAfter DECIMAL(12,2) NOT NULL DEFAULT 0.00,
                unit VARCHAR(50) DEFAULT 'unit',
                notes TEXT NULL,
                timestamp BIGINT NOT NULL DEFAULT 0
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

            CREATE TABLE IF NOT EXISTS app_settings (
                id INT PRIMARY KEY DEFAULT 1,
                businessName VARCHAR(255) DEFAULT 'Naadriel Enterprise',
                businessTagline VARCHAR(255) DEFAULT 'Chicken at its best',
                businessPhone VARCHAR(64) DEFAULT '024 000 0000',
                businessLocation VARCHAR(255) DEFAULT 'Ghana',
                momoPaymentDetails VARCHAR(255) DEFAULT 'MTN MoMo: 0244XXXXXX (Naadriel Enterprise)',
                currencySymbol VARCHAR(20) DEFAULT 'GH₵'
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
        ");

        // Helper to dynamically add any missing columns to pre-existing tables
        $addColumnIfMissing = function($pdo, $table, $column, $def) {
            try {
                $check = $pdo->query("SHOW COLUMNS FROM `{$table}` LIKE '{$column}'");
                if ($check && $check->rowCount() === 0) {
                    $pdo->exec("ALTER TABLE `{$table}` ADD COLUMN `{$column}` {$def}");
                }
            } catch (Exception $ex) {
                // Silently continue if column already exists
            }
        };

        // Guarantee customer columns exist even on legacy tables
        $addColumnIfMissing($db, 'customers', 'currentBalance', "DECIMAL(12,2) NOT NULL DEFAULT 0.00");
        $addColumnIfMissing($db, 'customers', 'totalDebt', "DECIMAL(12,2) NOT NULL DEFAULT 0.00");
        $addColumnIfMissing($db, 'customers', 'totalPurchases', "DECIMAL(12,2) NOT NULL DEFAULT 0.00");
        $addColumnIfMissing($db, 'customers', 'totalPurchased', "DECIMAL(12,2) NOT NULL DEFAULT 0.00");
        $addColumnIfMissing($db, 'customers', 'totalPaid', "DECIMAL(12,2) NOT NULL DEFAULT 0.00");
        $addColumnIfMissing($db, 'customers', 'whatsapp', "VARCHAR(64) NOT NULL DEFAULT ''");
        $addColumnIfMissing($db, 'customers', 'phone', "VARCHAR(64) NOT NULL DEFAULT ''");
        $addColumnIfMissing($db, 'customers', 'address', "VARCHAR(255) NOT NULL DEFAULT ''");
        $addColumnIfMissing($db, 'customers', 'notes', "TEXT NULL");
        $addColumnIfMissing($db, 'customers', 'lastTransactionDate', "BIGINT NOT NULL DEFAULT 0");
        $addColumnIfMissing($db, 'customers', 'createdAt', "BIGINT NOT NULL DEFAULT 0");

        // Guarantee product columns
        $addColumnIfMissing($db, 'products', 'unitPrice', "DECIMAL(12,2) NOT NULL DEFAULT 0.00");
        $addColumnIfMissing($db, 'products', 'price', "DECIMAL(12,2) NOT NULL DEFAULT 0.00");
        $addColumnIfMissing($db, 'products', 'costPrice', "DECIMAL(12,2) NOT NULL DEFAULT 0.00");
        $addColumnIfMissing($db, 'products', 'minStockThreshold', "DECIMAL(12,2) NOT NULL DEFAULT 5.00");
        $addColumnIfMissing($db, 'products', 'unit', "VARCHAR(32) NOT NULL DEFAULT 'unit'");
        $addColumnIfMissing($db, 'products', 'lastRestockedAt', "BIGINT NOT NULL DEFAULT 0");

        // Guarantee sales order columns
        $addColumnIfMissing($db, 'sales_orders', 'balanceDue', "DECIMAL(12,2) NOT NULL DEFAULT 0.00");
        $addColumnIfMissing($db, 'sales_orders', 'debtAmount', "DECIMAL(12,2) NOT NULL DEFAULT 0.00");
        $addColumnIfMissing($db, 'sales_orders', 'discountAmount', "DECIMAL(12,2) NOT NULL DEFAULT 0.00");
        $addColumnIfMissing($db, 'sales_orders', 'paymentStatus', "VARCHAR(32) NOT NULL DEFAULT 'PAID'");
        $addColumnIfMissing($db, 'sales_orders', 'paymentMethod', "VARCHAR(32) NOT NULL DEFAULT 'CASH'");
        $addColumnIfMissing($db, 'sales_orders', 'customerWhatsapp', "VARCHAR(64) NOT NULL DEFAULT ''");

        // Auto-reconcile any legacy rows
        try {
            $db->exec("UPDATE customers SET currentBalance = totalDebt WHERE (currentBalance IS NULL OR currentBalance = 0) AND totalDebt > 0");
            $db->exec("UPDATE customers SET totalDebt = currentBalance WHERE (totalDebt IS NULL OR totalDebt = 0) AND currentBalance > 0");
            $db->exec("UPDATE products SET unitPrice = price WHERE (unitPrice IS NULL OR unitPrice = 0) AND price > 0");
            $db->exec("UPDATE products SET price = unitPrice WHERE (price IS NULL OR price = 0) AND unitPrice > 0");
        } catch (Exception $reconcileEx) {
            // Ignore
        }

    } catch (PDOException $e) {
        $dbError = $e->getMessage();
    }
} else {
    $dbError = "Database credentials are not yet configured.";
}

// ----------------------------------------------------
// 3. SHOW SETUP SCREEN IF DATABASE NOT CONNECTED
// ----------------------------------------------------
if (!$db) {
    ?>
    <!DOCTYPE html>
    <html lang="en">
    <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>Database Setup - BizTrack Web POS</title>
        <script src="https://cdn.tailwindcss.com"></script>
        <link href="https://fonts.googleapis.com/css2?family=Plus+Jakarta+Sans:wght@400;600;700;800&display=swap" rel="stylesheet">
        <style>body { font-family: 'Plus Jakarta Sans', sans-serif; }</style>
    </head>
    <body class="bg-slate-950 text-slate-100 min-h-screen flex items-center justify-center p-4">
        <div class="max-w-xl w-full bg-slate-900 border border-slate-800 rounded-3xl shadow-2xl p-8 space-y-6">
            <div class="text-center">
                <div class="w-16 h-16 bg-amber-500/20 text-amber-400 rounded-2xl mx-auto flex items-center justify-center text-3xl mb-3">
                    ⚙️
                </div>
                <h1 class="text-2xl font-black text-white">BizTrack Web Setup</h1>
                <p class="text-slate-400 text-sm mt-1">Configure your MySQL database connection to get started</p>
            </div>

            <?php if (!empty($dbError)): ?>
                <div class="p-4 bg-rose-500/10 border border-rose-500/30 rounded-2xl text-rose-300 text-xs leading-relaxed">
                    <strong class="font-bold text-rose-400 block mb-1">Database Connection Status:</strong>
                    <?= htmlspecialchars($dbError) ?>
                </div>
            <?php endif; ?>

            <form method="POST" action="index.php" class="space-y-4">
                <input type="hidden" name="save_db_config" value="1">
                <div>
                    <label class="block text-xs font-semibold text-slate-300 uppercase mb-1">Database Host</label>
                    <input type="text" name="db_host" value="<?= htmlspecialchars($DB_HOST ?: 'localhost') ?>" required class="w-full px-4 py-3 bg-slate-950 border border-slate-800 rounded-xl text-white">
                </div>
                <div>
                    <label class="block text-xs font-semibold text-slate-300 uppercase mb-1">Database Name</label>
                    <input type="text" name="db_name" value="<?= htmlspecialchars($DB_NAME) ?>" required placeholder="e.g. rmgroups_farm_db" class="w-full px-4 py-3 bg-slate-950 border border-slate-800 rounded-xl text-white">
                </div>
                <div>
                    <label class="block text-xs font-semibold text-slate-300 uppercase mb-1">Database User</label>
                    <input type="text" name="db_user" value="<?= htmlspecialchars($DB_USER) ?>" required placeholder="e.g. rmgroups_farm_user" class="w-full px-4 py-3 bg-slate-950 border border-slate-800 rounded-xl text-white">
                </div>
                <div>
                    <label class="block text-xs font-semibold text-slate-300 uppercase mb-1">Database Password</label>
                    <input type="password" name="db_pass" value="<?= htmlspecialchars($DB_PASS) ?>" placeholder="Enter MySQL Password" class="w-full px-4 py-3 bg-slate-950 border border-slate-800 rounded-xl text-white">
                </div>
                <div>
                    <label class="block text-xs font-semibold text-slate-300 uppercase mb-1">Secret Key (Matches Mobile App Settings)</label>
                    <input type="text" name="secret_key" value="<?= htmlspecialchars($SECRET_KEY ?: 'naadriel_farm_secret_2026') ?>" required class="w-full px-4 py-3 bg-slate-950 border border-slate-800 rounded-xl text-white">
                </div>
                <button type="submit" class="w-full py-4 bg-emerald-600 hover:bg-emerald-500 text-white font-bold rounded-xl shadow-lg shadow-emerald-900/40 transition-all">
                    Connect & Open Dashboard
                </button>
            </form>
        </div>
    </body>
    </html>
    <?php
    exit;
}

// ----------------------------------------------------
// 4. AUTHENTICATION (LOGIN / LOGOUT)
// ----------------------------------------------------
if (isset($_GET['logout'])) {
    unset($_SESSION['naadriel_auth']);
    @session_destroy();
    header("Location: index.php");
    exit;
}

$loginError = '';
if ($_SERVER['REQUEST_METHOD'] === 'POST' && isset($_POST['auth_action']) && $_POST['auth_action'] === 'login') {
    $enteredKey = trim($_POST['secret_key'] ?? '');
    if ($enteredKey === $SECRET_KEY || empty($SECRET_KEY)) {
        $_SESSION['naadriel_auth'] = true;
        header("Location: index.php");
        exit;
    } else {
        $loginError = "Invalid Secret Key. Please check the key in your Android App Settings.";
    }
}

$isLoggedIn = !empty($_SESSION['naadriel_auth']);

if (!$isLoggedIn) {
    // Show Login Screen
    ?>
    <!DOCTYPE html>
    <html lang="en">
    <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>Login - BizTrack Web Dashboard</title>
        <script src="https://cdn.tailwindcss.com"></script>
        <link href="https://fonts.googleapis.com/css2?family=Plus+Jakarta+Sans:wght@400;600;700;800&display=swap" rel="stylesheet">
        <style>body { font-family: 'Plus Jakarta Sans', sans-serif; }</style>
    </head>
    <body class="bg-slate-950 min-h-screen flex items-center justify-center p-4">
        <div class="max-w-md w-full bg-slate-900 border border-slate-800 rounded-3xl shadow-2xl p-8">
            <div class="text-center mb-8">
                <div class="inline-flex items-center justify-center w-16 h-16 bg-emerald-600/20 text-emerald-400 rounded-2xl mb-4 text-3xl shadow-lg shadow-emerald-900/30">
                    🐔
                </div>
                <h1 class="text-2xl font-extrabold text-white tracking-tight">BizTrack Business POS</h1>
                <p class="text-slate-400 text-sm mt-1">Web Management & Real-time Cloud POS</p>
            </div>

            <?php if (!empty($loginError)): ?>
                <div class="mb-6 p-4 bg-rose-500/10 border border-rose-500/30 rounded-2xl text-rose-400 text-xs flex items-center gap-3">
                    <svg class="w-5 h-5 flex-shrink-0" fill="currentColor" viewBox="0 0 20 20"><path fill-rule="evenodd" d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7 4a1 1 0 11-2 0 1 1 0 012 0zm-1-9a1 1 0 00-1 1v4a1 1 0 102 0V6a1 1 0 00-1-1z" clip-rule="evenodd"/></svg>
                    <span><?= htmlspecialchars($loginError) ?></span>
                </div>
            <?php endif; ?>

            <form method="POST" action="index.php" class="space-y-5">
                <input type="hidden" name="auth_action" value="login">
                <div>
                    <label class="block text-xs font-semibold text-slate-300 uppercase tracking-wider mb-2">Cloud Secret Key</label>
                    <input type="password" name="secret_key" required placeholder="Enter your Secret Key" class="w-full px-4 py-3.5 bg-slate-950 border border-slate-800 rounded-xl text-white placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-emerald-500 transition-all">
                </div>
                <button type="submit" class="w-full py-4 bg-emerald-600 hover:bg-emerald-500 text-white font-bold rounded-xl shadow-lg shadow-emerald-900/40 transition-all">
                    Access Web Dashboard
                </button>
            </form>
            <div class="mt-8 pt-6 border-t border-slate-800/80 flex items-center justify-between text-xs text-slate-500">
                <span>Database: <?= htmlspecialchars($DB_NAME) ?></span>
                <span class="text-emerald-400 font-medium">● Connected</span>
            </div>
        </div>
    </body>
    </html>
    <?php
    exit;
}

// ----------------------------------------------------
// 5. DATA ACTIONS & CRUD CONTROLLER
// ----------------------------------------------------
$msg = $_GET['msg'] ?? '';
$error = $_GET['err'] ?? '';

// Load App Settings
$stmt = $db->query("SELECT * FROM app_settings WHERE id = 1 LIMIT 1");
$settings = $stmt->fetch() ?: [
    'businessName' => 'BizTrack Business',
    'businessTagline' => 'Quality products & reliable service',
    'businessPhone' => '024 000 0000',
    'businessLocation' => 'Ghana',
    'momoPaymentDetails' => 'MTN MoMo: 0244XXXXXX (BizTrack)',
    'currencySymbol' => 'GH₵'
];
$currency = $settings['currencySymbol'] ?: 'GH₵';

// Handle POST Form Actions
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $action = $_POST['action'] ?? '';

    // A. PRODUCT CRUD
    if ($action === 'create_product') {
        $name = trim($_POST['name'] ?? '');
        $category = $_POST['category'] ?? 'OTHER';
        $unitPrice = (float)($_POST['unitPrice'] ?? 0.0);
        $costPrice = (float)($_POST['costPrice'] ?? 0.0);
        $stockQuantity = (float)($_POST['stockQuantity'] ?? 0.0);
        $unit = trim($_POST['unit'] ?? 'unit');
        $minThreshold = (float)($_POST['minStockThreshold'] ?? 5.0);
        $description = trim($_POST['description'] ?? '');

        if (!empty($name)) {
            $now = round(microtime(true) * 1000);
            $stmt = $db->prepare("INSERT INTO products (name, category, price, unitPrice, costPrice, stockQuantity, unit, minStockThreshold, description, lastRestockedAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
            $stmt->execute([$name, $category, $unitPrice, $unitPrice, $costPrice, $stockQuantity, $unit, $minThreshold, $description, $now]);
            $prodId = $db->lastInsertId();

            if ($stockQuantity > 0) {
                $logStmt = $db->prepare("INSERT INTO inventory_logs (productId, productName, changeType, quantityChanged, quantityAfter, unit, notes, timestamp) VALUES (?, ?, 'RESTOCK', ?, ?, ?, 'Initial stock added via Web', ?)");
                $logStmt->execute([$prodId, $name, $stockQuantity, $stockQuantity, $unit, $now]);
            }
            header("Location: index.php?tab=products&msg=" . urlencode("Product '$name' added successfully!"));
            exit;
        }
    }

    if ($action === 'update_product') {
        $id = (int)$_POST['id'];
        $name = trim($_POST['name'] ?? '');
        $category = $_POST['category'] ?? 'OTHER';
        $unitPrice = (float)($_POST['unitPrice'] ?? 0.0);
        $costPrice = (float)($_POST['costPrice'] ?? 0.0);
        $stockQuantity = (float)($_POST['stockQuantity'] ?? 0.0);
        $unit = trim($_POST['unit'] ?? 'unit');
        $minThreshold = (float)($_POST['minStockThreshold'] ?? 5.0);
        $description = trim($_POST['description'] ?? '');

        $stmt = $db->prepare("UPDATE products SET name = ?, category = ?, price = ?, unitPrice = ?, costPrice = ?, stockQuantity = ?, unit = ?, minStockThreshold = ?, description = ? WHERE id = ?");
        $stmt->execute([$name, $category, $unitPrice, $unitPrice, $costPrice, $stockQuantity, $unit, $minThreshold, $description, $id]);
        header("Location: index.php?tab=products&msg=" . urlencode("Product updated successfully!"));
        exit;
    }

    if ($action === 'restock_product') {
        $id = (int)$_POST['id'];
        $addQty = (float)$_POST['add_quantity'];
        $notes = trim($_POST['notes'] ?? 'Restocked via Web Dashboard');

        $pStmt = $db->prepare("SELECT name, stockQuantity, unit FROM products WHERE id = ?");
        $pStmt->execute([$id]);
        $prod = $pStmt->fetch();

        if ($prod && $addQty > 0) {
            $newQty = (float)$prod['stockQuantity'] + $addQty;
            $now = round(microtime(true) * 1000);

            $db->prepare("UPDATE products SET stockQuantity = ?, lastRestockedAt = ? WHERE id = ?")->execute([$newQty, $now, $id]);
            $db->prepare("INSERT INTO inventory_logs (productId, productName, changeType, quantityChanged, quantityAfter, unit, notes, timestamp) VALUES (?, ?, 'RESTOCK', ?, ?, ?, ?, ?)")
               ->execute([$id, $prod['name'], $addQty, $newQty, $prod['unit'], $notes, $now]);

            header("Location: index.php?tab=products&msg=" . urlencode("Restocked {$addQty} {$prod['unit']} of {$prod['name']}!"));
            exit;
        }
    }

    if ($action === 'delete_product') {
        $id = (int)$_POST['id'];
        $db->prepare("DELETE FROM products WHERE id = ?")->execute([$id]);
        header("Location: index.php?tab=products&msg=" . urlencode("Product removed!"));
        exit;
    }

    // B. CUSTOMER CRUD
    if ($action === 'create_customer') {
        $name = trim($_POST['name'] ?? '');
        $phone = trim($_POST['phone'] ?? '');
        $whatsapp = trim($_POST['whatsapp'] ?? '');
        $address = trim($_POST['address'] ?? '');
        $notes = trim($_POST['notes'] ?? '');
        $initialDebt = (float)($_POST['initial_debt'] ?? 0.0);

        if (!empty($name)) {
            $now = round(microtime(true) * 1000);
            $stmt = $db->prepare("INSERT INTO customers (name, phone, whatsapp, address, notes, totalDebt, currentBalance, totalPurchases, totalPaid, createdAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
            $stmt->execute([$name, $phone, $whatsapp, $address, $notes, $initialDebt, $initialDebt, $initialDebt, 0.0, $now]);
            header("Location: index.php?tab=customers&msg=" . urlencode("Customer '$name' added successfully!"));
            exit;
        }
    }

    if ($action === 'update_customer') {
        $id = (int)$_POST['id'];
        $name = trim($_POST['name'] ?? '');
        $phone = trim($_POST['phone'] ?? '');
        $whatsapp = trim($_POST['whatsapp'] ?? '');
        $address = trim($_POST['address'] ?? '');
        $notes = trim($_POST['notes'] ?? '');

        $stmt = $db->prepare("UPDATE customers SET name = ?, phone = ?, whatsapp = ?, address = ?, notes = ? WHERE id = ?");
        $stmt->execute([$name, $phone, $whatsapp, $address, $notes, $id]);
        header("Location: index.php?tab=customers&msg=" . urlencode("Customer updated successfully!"));
        exit;
    }

    if ($action === 'delete_customer') {
        $id = (int)$_POST['id'];
        $db->prepare("DELETE FROM customers WHERE id = ?")->execute([$id]);
        header("Location: index.php?tab=customers&msg=" . urlencode("Customer removed!"));
        exit;
    }

    // C. SALE / POS TRANSACTION
    if ($action === 'create_sale') {
        $customerId = (int)($_POST['customer_id'] ?? 0);
        $customerName = trim($_POST['customer_name'] ?? 'Walk-in Customer');
        $customerPhone = trim($_POST['customer_phone'] ?? '');
        $customerWhatsapp = trim($_POST['customer_whatsapp'] ?? '');
        $paymentMethod = $_POST['payment_method'] ?? 'CASH';
        $discountAmount = (float)($_POST['discount_amount'] ?? 0.0);
        $amountPaid = (float)($_POST['amount_paid'] ?? 0.0);
        $notes = trim($_POST['notes'] ?? '');

        if ($customerId > 0) {
            $cStmt = $db->prepare("SELECT * FROM customers WHERE id = ?");
            $cStmt->execute([$customerId]);
            $cust = $cStmt->fetch();
            if ($cust) {
                $customerName = $cust['name'];
                if (empty($customerPhone)) $customerPhone = $cust['phone'];
                if (empty($customerWhatsapp)) $customerWhatsapp = $cust['whatsapp'];
            }
        }

        $itemIds = $_POST['item_product_id'] ?? [];
        $itemQtys = $_POST['item_quantity'] ?? [];
        $itemPrices = $_POST['item_price'] ?? [];

        $cartItems = [];
        $grossTotal = 0.0;
        $now = round(microtime(true) * 1000);

        for ($i = 0; $i < count($itemIds); $i++) {
            $pId = (int)$itemIds[$i];
            $qty = (float)$itemQtys[$i];
            $price = (float)$itemPrices[$i];

            if ($pId > 0 && $qty > 0) {
                $pStmt = $db->prepare("SELECT name, unit, costPrice, stockQuantity FROM products WHERE id = ?");
                $pStmt->execute([$pId]);
                $pRow = $pStmt->fetch();

                if ($pRow) {
                    $lineTotal = $qty * $price;
                    $grossTotal += $lineTotal;
                    $cartItems[] = [
                        'productId' => $pId,
                        'productName' => $pRow['name'],
                        'unitPrice' => $price,
                        'costPrice' => (float)$pRow['costPrice'],
                        'quantity' => $qty,
                        'unit' => $pRow['unit'],
                        'lineTotal' => $lineTotal
                    ];

                    // Deduct stock and log
                    $newStock = max(0.0, (float)$pRow['stockQuantity'] - $qty);
                    $db->prepare("UPDATE products SET stockQuantity = ? WHERE id = ?")->execute([$newStock, $pId]);
                    $db->prepare("INSERT INTO inventory_logs (productId, productName, changeType, quantityChanged, quantityAfter, unit, notes, timestamp) VALUES (?, ?, 'SALE', ?, ?, ?, 'Sold via Web POS', ?)")
                       ->execute([$pId, $pRow['name'], -$qty, $newStock, $pRow['unit'], $now]);
                }
            }
        }

        if (!empty($cartItems)) {
            $netTotal = max(0.0, $grossTotal - $discountAmount);
            $balanceDue = max(0.0, $netTotal - $amountPaid);
            $paymentStatus = ($balanceDue <= 0.001) ? 'PAID' : (($amountPaid > 0.001) ? 'PARTIAL' : 'CREDIT');
            $invoiceNum = 'INV-' . date('ymd') . '-' . rand(1000, 9999);
            $itemsJson = json_encode($cartItems);

            // Insert Sales Order
            $soStmt = $db->prepare("INSERT INTO sales_orders (invoiceNumber, customerId, customerName, customerPhone, customerWhatsapp, itemsJson, totalAmount, amountPaid, debtAmount, balanceDue, discountAmount, paymentStatus, paymentMethod, notes, timestamp) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
            $soStmt->execute([$invoiceNum, ($customerId > 0 ? $customerId : null), $customerName, $customerPhone, $customerWhatsapp, $itemsJson, $netTotal, $amountPaid, $balanceDue, $balanceDue, $discountAmount, $paymentStatus, $paymentMethod, $notes, $now]);
            $orderId = $db->lastInsertId();

            // Update customer debt ledger
            if ($customerId > 0) {
                $db->prepare("UPDATE customers SET totalPurchases = totalPurchases + ?, totalPaid = totalPaid + ?, totalDebt = totalDebt + ?, currentBalance = currentBalance + ?, lastTransactionDate = ? WHERE id = ?")
                   ->execute([$netTotal, $amountPaid, $balanceDue, $balanceDue, $now, $customerId]);

                if ($amountPaid > 0) {
                    $cBalStmt = $db->prepare("SELECT currentBalance FROM customers WHERE id = ?");
                    $cBalStmt->execute([$customerId]);
                    $balNow = (float)($cBalStmt->fetchColumn() ?: 0.0);

                    $db->prepare("INSERT INTO payments (customerId, customerName, saleOrderId, amount, paymentMethod, notes, timestamp, balanceAfterPayment) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                       ->execute([$customerId, $customerName, $orderId, $amountPaid, $paymentMethod, "Initial payment on #$invoiceNum", $now, $balNow]);
                }
            }

            header("Location: index.php?tab=sales&msg=" . urlencode("Invoice #$invoiceNum created successfully!"));
            exit;
        } else {
            header("Location: index.php?tab=new_sale&err=" . urlencode("Please add at least one product with quantity > 0."));
            exit;
        }
    }

    // D. RECORD PAYMENT / DEBT RECOVERY
    if ($action === 'record_payment') {
        $customerId = (int)$_POST['customer_id'];
        $amount = (float)($_POST['amount'] ?? 0.0);
        $method = $_POST['payment_method'] ?? 'CASH';
        $notes = trim($_POST['notes'] ?? 'Payment recorded via Web Dashboard');
        $now = round(microtime(true) * 1000);

        if ($customerId > 0 && $amount > 0) {
            $cStmt = $db->prepare("SELECT * FROM customers WHERE id = ?");
            $cStmt->execute([$customerId]);
            $cust = $cStmt->fetch();

            if ($cust) {
                $curBal = (float)($cust['currentBalance'] ?: $cust['totalDebt'] ?: 0.0);
                $newBal = max(0.0, $curBal - $amount);

                // Update customer balance
                $db->prepare("UPDATE customers SET totalPaid = totalPaid + ?, totalDebt = ?, currentBalance = ?, lastTransactionDate = ? WHERE id = ?")
                   ->execute([$amount, $newBal, $newBal, $now, $customerId]);

                // Insert payment receipt
                $db->prepare("INSERT INTO payments (customerId, customerName, saleOrderId, amount, paymentMethod, notes, timestamp, balanceAfterPayment) VALUES (?, ?, NULL, ?, ?, ?, ?, ?)")
                   ->execute([$customerId, $cust['name'], $amount, $method, $notes, $now, $newBal]);

                // Reconcile unpaid orders
                $ordersStmt = $db->prepare("SELECT id, balanceDue, amountPaid FROM sales_orders WHERE customerId = ? AND balanceDue > 0 ORDER BY timestamp ASC");
                $ordersStmt->execute([$customerId]);
                $unpaidOrders = $ordersStmt->fetchAll();

                $remainingPayment = $amount;
                foreach ($unpaidOrders as $ord) {
                    if ($remainingPayment <= 0) break;
                    $ordBal = (float)$ord['balanceDue'];
                    $paidOnThis = min($remainingPayment, $ordBal);
                    $newOrdBal = max(0.0, $ordBal - $paidOnThis);
                    $newOrdPaid = (float)$ord['amountPaid'] + $paidOnThis;
                    $newStatus = ($newOrdBal <= 0.001) ? 'PAID' : 'PARTIAL';

                    $db->prepare("UPDATE sales_orders SET balanceDue = ?, debtAmount = ?, amountPaid = ?, paymentStatus = ? WHERE id = ?")
                       ->execute([$newOrdBal, $newOrdBal, $newOrdPaid, $newStatus, $ord['id']]);

                    $remainingPayment -= $paidOnThis;
                }

                header("Location: index.php?tab=debtors&msg=" . urlencode("Payment of $currency " . number_format($amount, 2) . " recorded for {$cust['name']}!"));
                exit;
            }
        }
    }

    // E. SETTINGS UPDATE
    if ($action === 'update_settings') {
        $bName = trim($_POST['businessName'] ?? '');
        $bTagline = trim($_POST['businessTagline'] ?? '');
        $bPhone = trim($_POST['businessPhone'] ?? '');
        $bLocation = trim($_POST['businessLocation'] ?? '');
        $mMomo = trim($_POST['momoPaymentDetails'] ?? '');
        $cSymbol = trim($_POST['currencySymbol'] ?? 'GH₵');

        $stmt = $db->prepare("REPLACE INTO app_settings (id, businessName, businessTagline, businessPhone, businessLocation, momoPaymentDetails, currencySymbol) VALUES (1, ?, ?, ?, ?, ?, ?)");
        $stmt->execute([$bName, $bTagline, $bPhone, $bLocation, $mMomo, $cSymbol]);

        header("Location: index.php?tab=settings&msg=" . urlencode("Farm settings updated successfully!"));
        exit;
    }
}

// ----------------------------------------------------
// 6. DASHBOARD & TAB DATA PREPARATION
// ----------------------------------------------------
$tab = $_GET['tab'] ?? 'dashboard';

$totalSalesSum = (float)($db->query("SELECT SUM(totalAmount) FROM sales_orders")->fetchColumn() ?: 0.0);
$totalCollectedSum = (float)($db->query("SELECT SUM(amountPaid) FROM sales_orders")->fetchColumn() ?: 0.0);
$totalDebtSum = (float)($db->query("SELECT SUM(COALESCE(currentBalance, totalDebt, 0)) FROM customers WHERE COALESCE(currentBalance, totalDebt, 0) > 0")->fetchColumn() ?: 0.0);
$totalProductsCount = (int)($db->query("SELECT COUNT(*) FROM products")->fetchColumn() ?: 0);
$lowStockCount = (int)($db->query("SELECT COUNT(*) FROM products WHERE stockQuantity <= minStockThreshold")->fetchColumn() ?: 0);
$debtorsCount = (int)($db->query("SELECT COUNT(*) FROM customers WHERE COALESCE(currentBalance, totalDebt, 0) > 0.009")->fetchColumn() ?: 0);

$allProducts = $db->query("SELECT * FROM products ORDER BY name ASC")->fetchAll();
$allCustomers = $db->query("SELECT * FROM customers ORDER BY name ASC")->fetchAll();
?>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title><?= htmlspecialchars($settings['businessName']) ?> - Web Dashboard</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <link href="https://fonts.googleapis.com/css2?family=Plus+Jakarta+Sans:wght@400;500;600;700;800&display=swap" rel="stylesheet">
    <style>
        body { font-family: 'Plus Jakarta Sans', sans-serif; }
        .glass-card { background: rgba(30, 41, 59, 0.7); backdrop-filter: blur(12px); border: 1px solid rgba(255, 255, 255, 0.07); }
    </style>
</head>
<body class="bg-slate-950 text-slate-100 min-h-screen flex flex-col md:flex-row">

    <!-- SIDEBAR NAVIGATION -->
    <aside class="w-full md:w-64 bg-slate-900 border-r border-slate-800 p-5 flex flex-col justify-between flex-shrink-0">
        <div>
            <!-- Farm Branding -->
            <div class="flex items-center gap-3 mb-8">
                <div class="w-10 h-10 rounded-xl bg-gradient-to-tr from-emerald-600 to-teal-400 flex items-center justify-center text-white font-black text-xl shadow-lg shadow-emerald-900/40">
                    🐔
                </div>
                <div>
                    <h2 class="font-bold text-base text-white leading-tight"><?= htmlspecialchars($settings['businessName']) ?></h2>
                    <span class="text-xs text-emerald-400 font-medium">Cloud Web POS</span>
                </div>
            </div>

            <!-- Navigation Links -->
            <nav class="space-y-1.5">
                <a href="index.php?tab=dashboard" class="flex items-center gap-3 px-4 py-3 rounded-xl font-semibold text-sm transition-all <?= $tab === 'dashboard' ? 'bg-emerald-600 text-white shadow-lg shadow-emerald-900/30' : 'text-slate-400 hover:bg-slate-800 hover:text-white' ?>">
                    <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M3 12l2-2m0 0l7-7 7 7M5 10v10a1 1 0 001 1h3m10-11l2 2m-2-2v10a1 1 0 01-1 1h-3m-6 0a1 1 0 001-1v-4a1 1 0 011-1h2a1 1 0 011 1v4a1 1 0 001 1m-6 0h6"/></svg>
                    Dashboard
                </a>
                <a href="index.php?tab=new_sale" class="flex items-center gap-3 px-4 py-3 rounded-xl font-semibold text-sm transition-all <?= $tab === 'new_sale' ? 'bg-emerald-600 text-white shadow-lg shadow-emerald-900/30' : 'text-slate-400 hover:bg-slate-800 hover:text-white' ?>">
                    <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4"/></svg>
                    New Sale (POS)
                </a>
                <a href="index.php?tab=sales" class="flex items-center gap-3 px-4 py-3 rounded-xl font-semibold text-sm transition-all <?= $tab === 'sales' ? 'bg-emerald-600 text-white shadow-lg shadow-emerald-900/30' : 'text-slate-400 hover:bg-slate-800 hover:text-white' ?>">
                    <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"/></svg>
                    Sales History
                </a>
                <a href="index.php?tab=products" class="flex items-center gap-3 px-4 py-3 rounded-xl font-semibold text-sm transition-all <?= $tab === 'products' ? 'bg-emerald-600 text-white shadow-lg shadow-emerald-900/30' : 'text-slate-400 hover:bg-slate-800 hover:text-white' ?>">
                    <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M20 7l-8-4-8 4m16 0l-8 4m8-4v10l-8 4m0-10L4 7m8 4v10M4 7v10l8 4"/></svg>
                    Products & Stock
                    <?php if ($lowStockCount > 0): ?>
                        <span class="ml-auto bg-amber-500/20 text-amber-400 text-xs px-2 py-0.5 rounded-full font-bold"><?= $lowStockCount ?></span>
                    <?php endif; ?>
                </a>
                <a href="index.php?tab=customers" class="flex items-center gap-3 px-4 py-3 rounded-xl font-semibold text-sm transition-all <?= $tab === 'customers' ? 'bg-emerald-600 text-white shadow-lg shadow-emerald-900/30' : 'text-slate-400 hover:bg-slate-800 hover:text-white' ?>">
                    <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4.354a4 4 0 110 5.292M15 21H3v-1a6 6 0 0112 0v1zm0 0h6v-1a6 6 0 00-9-5.197M13 7a4 4 0 11-8 0 4 4 0 018 0z"/></svg>
                    Customers
                </a>
                <a href="index.php?tab=debtors" class="flex items-center gap-3 px-4 py-3 rounded-xl font-semibold text-sm transition-all <?= $tab === 'debtors' ? 'bg-rose-600 text-white shadow-lg shadow-rose-900/30' : 'text-slate-400 hover:bg-slate-800 hover:text-white' ?>">
                    <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17 9V7a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2m2 4h10a2 2 0 002-2v-6a2 2 0 00-2-2H9a2 2 0 00-2 2v6a2 2 0 002 2zm7-5a2 2 0 11-4 0 2 2 0 014 0z"/></svg>
                    Debtors Ledger
                    <?php if ($debtorsCount > 0): ?>
                        <span class="ml-auto bg-rose-500/20 text-rose-400 text-xs px-2 py-0.5 rounded-full font-bold"><?= $debtorsCount ?></span>
                    <?php endif; ?>
                </a>
                <a href="index.php?tab=payments" class="flex items-center gap-3 px-4 py-3 rounded-xl font-semibold text-sm transition-all <?= $tab === 'payments' ? 'bg-emerald-600 text-white shadow-lg shadow-emerald-900/30' : 'text-slate-400 hover:bg-slate-800 hover:text-white' ?>">
                    <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 8h6m-5 0a3 3 0 110 6H9l3 3m-3-6h6m6 1a9 9 0 11-18 0 9 9 0 0118 0z"/></svg>
                    Payment Logs
                </a>
                <a href="index.php?tab=inventory_logs" class="flex items-center gap-3 px-4 py-3 rounded-xl font-semibold text-sm transition-all <?= $tab === 'inventory_logs' ? 'bg-emerald-600 text-white shadow-lg shadow-emerald-900/30' : 'text-slate-400 hover:bg-slate-800 hover:text-white' ?>">
                    <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2"/></svg>
                    Inventory History
                </a>
                <a href="index.php?tab=settings" class="flex items-center gap-3 px-4 py-3 rounded-xl font-semibold text-sm transition-all <?= $tab === 'settings' ? 'bg-emerald-600 text-white shadow-lg shadow-emerald-900/30' : 'text-slate-400 hover:bg-slate-800 hover:text-white' ?>">
                    <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z"/><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"/></svg>
                    Settings & Profile
                </a>
            </nav>
        </div>

        <div class="mt-8 pt-6 border-t border-slate-800">
            <div class="flex items-center justify-between text-xs text-slate-500 mb-3">
                <span class="flex items-center gap-1.5"><span class="w-2 h-2 rounded-full bg-emerald-400"></span> MySQL Live</span>
                <span class="truncate max-w-[100px]"><?= htmlspecialchars($DB_NAME) ?></span>
            </div>
            <a href="index.php?logout=1" class="flex items-center justify-center gap-2 w-full py-2.5 bg-slate-800 hover:bg-rose-900/30 hover:text-rose-400 text-slate-400 text-sm font-medium rounded-xl transition-all">
                <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17 16l4-4m0 0l-4-4m4 4H7m6 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h4a3 3 0 013 3v1"/></svg>
                Sign Out
            </a>
        </div>
    </aside>

    <!-- MAIN CONTENT AREA -->
    <main class="flex-1 p-6 lg:p-10 overflow-y-auto">
        <!-- Toast Alerts -->
        <?php if (!empty($msg)): ?>
            <div class="mb-6 p-4 bg-emerald-500/10 border border-emerald-500/30 rounded-2xl text-emerald-400 text-sm flex items-center justify-between shadow-lg">
                <div class="flex items-center gap-3">
                    <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7"/></svg>
                    <span><?= htmlspecialchars($msg) ?></span>
                </div>
                <a href="index.php?tab=<?= htmlspecialchars($tab) ?>" class="text-emerald-400 hover:text-white">&times;</a>
            </div>
        <?php endif; ?>
        <?php if (!empty($error)): ?>
            <div class="mb-6 p-4 bg-rose-500/10 border border-rose-500/30 rounded-2xl text-rose-400 text-sm flex items-center justify-between shadow-lg">
                <div class="flex items-center gap-3">
                    <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"/></svg>
                    <span><?= htmlspecialchars($error) ?></span>
                </div>
                <a href="index.php?tab=<?= htmlspecialchars($tab) ?>" class="text-rose-400 hover:text-white">&times;</a>
            </div>
        <?php endif; ?>

        <?php
        // ---------------------------------------------------------------------------------
        // TAB: DASHBOARD
        // ---------------------------------------------------------------------------------
        if ($tab === 'dashboard'): 
            $recentSales = $db->query("SELECT * FROM sales_orders ORDER BY timestamp DESC LIMIT 5")->fetchAll();
            $topDebtors = $db->query("SELECT * FROM customers WHERE COALESCE(currentBalance, totalDebt, 0) > 0.009 ORDER BY COALESCE(currentBalance, totalDebt, 0) DESC LIMIT 5")->fetchAll();
        ?>
            <div class="space-y-8">
                <div>
                    <h1 class="text-2xl lg:text-3xl font-extrabold text-white tracking-tight">Overview & Performance</h1>
                    <p class="text-slate-400 text-sm mt-1">Real-time live metrics synchronized with your mobile farm POS</p>
                </div>

                <!-- Stats Grid -->
                <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-5">
                    <div class="glass-card p-5 rounded-2xl">
                        <span class="text-xs font-semibold text-slate-400 uppercase tracking-wider">Total Sales Volume</span>
                        <div class="text-2xl font-black text-emerald-400 mt-2"><?= $currency ?> <?= number_format($totalSalesSum, 2) ?></div>
                        <span class="text-xs text-slate-500 mt-1 block">All recorded farm orders</span>
                    </div>
                    <div class="glass-card p-5 rounded-2xl">
                        <span class="text-xs font-semibold text-slate-400 uppercase tracking-wider">Cash / MoMo Collected</span>
                        <div class="text-2xl font-black text-cyan-400 mt-2"><?= $currency ?> <?= number_format($totalCollectedSum, 2) ?></div>
                        <span class="text-xs text-slate-500 mt-1 block">Received payments</span>
                    </div>
                    <div class="glass-card p-5 rounded-2xl">
                        <span class="text-xs font-semibold text-slate-400 uppercase tracking-wider">Total Debtors (Owing)</span>
                        <div class="text-2xl font-black text-rose-400 mt-2"><?= $currency ?> <?= number_format($totalDebtSum, 2) ?></div>
                        <span class="text-xs text-slate-500 mt-1 block"><?= $debtorsCount ?> customers currently owe</span>
                    </div>
                    <div class="glass-card p-5 rounded-2xl">
                        <span class="text-xs font-semibold text-slate-400 uppercase tracking-wider">Inventory Items</span>
                        <div class="text-2xl font-black text-amber-400 mt-2"><?= $totalProductsCount ?> Products</div>
                        <span class="text-xs text-slate-500 mt-1 block"><?= $lowStockCount ?> items in low stock</span>
                    </div>
                </div>

                <!-- Recent Sales & Debtors -->
                <div class="grid grid-cols-1 md:grid-cols-2 gap-6">
                    <!-- Recent Sales -->
                    <div class="glass-card p-6 rounded-2xl">
                        <div class="flex items-center justify-between mb-4">
                            <h3 class="font-bold text-lg text-white">Recent Sales</h3>
                            <a href="index.php?tab=sales" class="text-xs font-semibold text-emerald-400 hover:underline">View All &rarr;</a>
                        </div>
                        <div class="space-y-3">
                            <?php if (empty($recentSales)): ?>
                                <p class="text-sm text-slate-500 py-4 text-center">No sales recorded yet.</p>
                            <?php else: foreach ($recentSales as $s): ?>
                                <div class="flex items-center justify-between p-3 bg-slate-900/60 rounded-xl border border-slate-800">
                                    <div>
                                        <div class="font-semibold text-sm text-white"><?= htmlspecialchars((string)($s['customerName'] ?? 'Walk-in Customer')) ?></div>
                                        <div class="text-xs text-slate-500">#<?= htmlspecialchars((string)($s['invoiceNumber'] ?? ('INV-' . ($s['id'] ?? '')))) ?> • <?= date('d M Y, h:i A', (int)round((float)($s['timestamp'] ?? 0) / 1000)) ?></div>
                                    </div>
                                    <div class="text-right">
                                        <div class="font-bold text-sm text-emerald-400"><?= $currency ?> <?= number_format($s['totalAmount'], 2) ?></div>
                                        <span class="text-[10px] px-2 py-0.5 rounded-full font-bold <?= $s['balanceDue'] > 0 ? 'bg-rose-500/20 text-rose-400' : 'bg-emerald-500/20 text-emerald-400' ?>">
                                            <?= $s['paymentStatus'] ?>
                                        </span>
                                    </div>
                                </div>
                            <?php endforeach; endif; ?>
                        </div>
                    </div>

                    <!-- Top Debtors -->
                    <div class="glass-card p-6 rounded-2xl">
                        <div class="flex items-center justify-between mb-4">
                            <h3 class="font-bold text-lg text-white">Top Outstanding Debtors</h3>
                            <a href="index.php?tab=debtors" class="text-xs font-semibold text-rose-400 hover:underline">Manage Debtors &rarr;</a>
                        </div>
                        <div class="space-y-3">
                            <?php if (empty($topDebtors)): ?>
                                <p class="text-sm text-slate-500 py-4 text-center">🎉 No outstanding debtors! All accounts cleared.</p>
                            <?php else: foreach ($topDebtors as $d): ?>
                                <div class="flex items-center justify-between p-3 bg-slate-900/60 rounded-xl border border-slate-800">
                                    <div>
                                        <div class="font-semibold text-sm text-white"><?= htmlspecialchars((string)($d['name'] ?? '')) ?></div>
                                        <div class="text-xs text-slate-500"><?= htmlspecialchars((string)(($d['phone'] ?? '') ?: 'No phone')) ?></div>
                                    </div>
                                    <div class="text-right">
                                        <div class="font-bold text-sm text-rose-400"><?= $currency ?> <?= number_format($d['currentBalance'], 2) ?></div>
                                        <button onclick="openPaymentModal(<?= $d['id'] ?>, '<?= htmlspecialchars(addslashes($d['name'])) ?>', <?= $d['currentBalance'] ?>)" class="text-[11px] text-emerald-400 font-semibold hover:underline">
                                            + Record Payment
                                        </button>
                                    </div>
                                </div>
                            <?php endforeach; endif; ?>
                        </div>
                    </div>
                </div>
            </div>

        <?php
        // ---------------------------------------------------------------------------------
        // TAB: NEW SALE (POS)
        // ---------------------------------------------------------------------------------
        elseif ($tab === 'new_sale'): ?>
            <div class="max-w-4xl mx-auto space-y-6">
                <div>
                    <h1 class="text-2xl font-extrabold text-white tracking-tight">Record New Sale</h1>
                    <p class="text-slate-400 text-sm mt-1">Create an invoice, update inventory stock, and track customer debts</p>
                </div>

                <form method="POST" action="index.php" id="saleForm" class="space-y-6">
                    <input type="hidden" name="action" value="create_sale">

                    <!-- Customer Selection -->
                    <div class="glass-card p-6 rounded-2xl space-y-4">
                        <h3 class="font-bold text-base text-emerald-400">1. Customer Information</h3>
                        <div class="grid grid-cols-1 md:grid-cols-3 gap-4">
                            <div>
                                <label class="block text-xs font-semibold text-slate-300 uppercase mb-2">Select Customer</label>
                                <select name="customer_id" id="pos_customer_select" onchange="handleCustomerSelect(this)" class="w-full px-4 py-3 bg-slate-900 border border-slate-700 rounded-xl text-white focus:ring-2 focus:ring-emerald-500">
                                    <option value="0" data-phone="" data-whatsapp="">Walk-in / Cash Customer</option>
                                    <?php foreach ($allCustomers as $c): ?>
                                        <option value="<?= $c['id'] ?>" data-name="<?= htmlspecialchars($c['name']) ?>" data-phone="<?= htmlspecialchars($c['phone']) ?>" data-whatsapp="<?= htmlspecialchars($c['whatsapp']) ?>" data-balance="<?= $c['currentBalance'] ?>">
                                            <?= htmlspecialchars($c['name']) ?> <?= $c['currentBalance'] > 0 ? '(Owes ' . $currency . ' ' . number_format($c['currentBalance'], 2) . ')' : '' ?>
                                        </option>
                                    <?php endforeach; ?>
                                </select>
                            </div>
                            <div>
                                <label class="block text-xs font-semibold text-slate-300 uppercase mb-2">Customer Name</label>
                                <input type="text" name="customer_name" id="pos_cust_name" value="Walk-in Customer" required class="w-full px-4 py-3 bg-slate-900 border border-slate-700 rounded-xl text-white focus:ring-2 focus:ring-emerald-500">
                            </div>
                            <div>
                                <label class="block text-xs font-semibold text-slate-300 uppercase mb-2">Phone Number</label>
                                <input type="text" name="customer_phone" id="pos_cust_phone" placeholder="024XXXXXXX" class="w-full px-4 py-3 bg-slate-900 border border-slate-700 rounded-xl text-white focus:ring-2 focus:ring-emerald-500">
                            </div>
                        </div>
                    </div>

                    <!-- Cart Items Selection -->
                    <div class="glass-card p-6 rounded-2xl space-y-4">
                        <div class="flex items-center justify-between">
                            <h3 class="font-bold text-base text-emerald-400">2. Farm Products Cart</h3>
                            <button type="button" onclick="addSaleRow()" class="px-3 py-1.5 bg-emerald-600/20 hover:bg-emerald-600/30 text-emerald-400 text-xs font-bold rounded-lg border border-emerald-500/30">
                                + Add Product Line
                            </button>
                        </div>
                        <div id="cart_rows_container" class="space-y-3">
                            <!-- Dynamic Item Rows -->
                        </div>
                    </div>

                    <!-- Payment & Totals -->
                    <div class="glass-card p-6 rounded-2xl space-y-4">
                        <h3 class="font-bold text-base text-emerald-400">3. Payment & Settlement</h3>
                        <div class="grid grid-cols-1 md:grid-cols-4 gap-4">
                            <div>
                                <label class="block text-xs font-semibold text-slate-300 uppercase mb-2">Discount (<?= $currency ?>)</label>
                                <input type="number" step="0.01" name="discount_amount" id="pos_discount" value="0.00" oninput="calculateTotals()" class="w-full px-4 py-3 bg-slate-900 border border-slate-700 rounded-xl text-white focus:ring-2 focus:ring-emerald-500">
                            </div>
                            <div>
                                <label class="block text-xs font-semibold text-slate-300 uppercase mb-2">Amount Paid (<?= $currency ?>)</label>
                                <input type="number" step="0.01" name="amount_paid" id="pos_amount_paid" value="0.00" oninput="calculateTotals()" class="w-full px-4 py-3 bg-slate-900 border border-slate-700 rounded-xl text-white focus:ring-2 focus:ring-emerald-500">
                            </div>
                            <div>
                                <label class="block text-xs font-semibold text-slate-300 uppercase mb-2">Payment Method</label>
                                <select name="payment_method" class="w-full px-4 py-3 bg-slate-900 border border-slate-700 rounded-xl text-white focus:ring-2 focus:ring-emerald-500">
                                    <option value="CASH">💵 Cash</option>
                                    <option value="MOMO">📱 MTN MoMo</option>
                                    <option value="BANK">🏦 Bank Transfer</option>
                                    <option value="CREDIT">⏳ Credit / Unpaid</option>
                                </select>
                            </div>
                            <div>
                                <label class="block text-xs font-semibold text-slate-300 uppercase mb-2">Notes / Memo</label>
                                <input type="text" name="notes" placeholder="Optional notes" class="w-full px-4 py-3 bg-slate-900 border border-slate-700 rounded-xl text-white focus:ring-2 focus:ring-emerald-500">
                            </div>
                        </div>

                        <!-- Live Summary Box -->
                        <div class="p-4 bg-slate-900/80 rounded-xl border border-slate-800 flex flex-wrap items-center justify-between gap-4 mt-4">
                            <div>
                                <span class="text-xs text-slate-400">Total Bill:</span>
                                <div class="text-xl font-bold text-white"><?= $currency ?> <span id="pos_total_display">0.00</span></div>
                            </div>
                            <div>
                                <span class="text-xs text-slate-400">Amount Paid:</span>
                                <div class="text-xl font-bold text-emerald-400"><?= $currency ?> <span id="pos_paid_display">0.00</span></div>
                            </div>
                            <div>
                                <span class="text-xs text-slate-400">Balance Due (Credit):</span>
                                <div class="text-xl font-black text-rose-400"><?= $currency ?> <span id="pos_balance_display">0.00</span></div>
                            </div>
                        </div>
                    </div>

                    <button type="submit" class="w-full py-4 bg-emerald-600 hover:bg-emerald-500 text-white font-bold text-lg rounded-2xl shadow-xl shadow-emerald-900/40 transition-all">
                        Complete & Issue Sale
                    </button>
                </form>
            </div>

            <script>
                const productsData = <?= json_encode($allProducts) ?>;

                function handleCustomerSelect(el) {
                    const opt = el.options[el.selectedIndex];
                    if (el.value !== "0") {
                        document.getElementById('pos_cust_name').value = opt.getAttribute('data-name') || '';
                        document.getElementById('pos_cust_phone').value = opt.getAttribute('data-phone') || '';
                    } else {
                        document.getElementById('pos_cust_name').value = 'Walk-in Customer';
                        document.getElementById('pos_cust_phone').value = '';
                    }
                }

                function addSaleRow() {
                    const container = document.getElementById('cart_rows_container');
                    const row = document.createElement('div');
                    row.className = 'grid grid-cols-12 gap-3 items-center p-3 bg-slate-900/60 rounded-xl border border-slate-800 cart-row';
                    
                    let optionsHtml = '<option value="">-- Choose Product --</option>';
                    productsData.forEach(p => {
                        optionsHtml += `<option value="${p.id}" data-price="${p.unitPrice}" data-unit="${p.unit}" data-stock="${p.stockQuantity}">${p.name} (${p.unitPrice} / ${p.unit}) [Stock: ${p.stockQuantity}]</option>`;
                    });

                    row.innerHTML = `
                        <div class="col-span-5">
                            <select name="item_product_id[]" onchange="handleProductChange(this)" required class="w-full px-3 py-2 bg-slate-900 border border-slate-700 rounded-lg text-sm text-white focus:ring-2 focus:ring-emerald-500">
                                ${optionsHtml}
                            </select>
                        </div>
                        <div class="col-span-2">
                            <input type="number" step="0.1" name="item_quantity[]" value="1" min="0.1" oninput="calculateTotals()" placeholder="Qty" required class="w-full px-3 py-2 bg-slate-900 border border-slate-700 rounded-lg text-sm text-white text-center">
                        </div>
                        <div class="col-span-2">
                            <input type="number" step="0.01" name="item_price[]" value="0.00" oninput="calculateTotals()" placeholder="Price" required class="w-full px-3 py-2 bg-slate-900 border border-slate-700 rounded-lg text-sm text-white text-right">
                        </div>
                        <div class="col-span-2 text-right font-bold text-sm text-emerald-400 line-total">
                            <?= $currency ?> 0.00
                        </div>
                        <div class="col-span-1 text-center">
                            <button type="button" onclick="this.closest('.cart-row').remove(); calculateTotals();" class="text-rose-400 hover:text-rose-300 font-bold text-lg">&times;</button>
                        </div>
                    `;
                    container.appendChild(row);
                }

                function handleProductChange(sel) {
                    const row = sel.closest('.cart-row');
                    const opt = sel.options[sel.selectedIndex];
                    const price = parseFloat(opt.getAttribute('data-price') || '0');
                    row.querySelector('input[name="item_price[]"]').value = price.toFixed(2);
                    calculateTotals();
                }

                function calculateTotals() {
                    let subtotal = 0;
                    document.querySelectorAll('.cart-row').forEach(row => {
                        const qty = parseFloat(row.querySelector('input[name="item_quantity[]"]').value) || 0;
                        const price = parseFloat(row.querySelector('input[name="item_price[]"]').value) || 0;
                        const total = qty * price;
                        subtotal += total;
                        row.querySelector('.line-total').innerText = '<?= $currency ?> ' + total.toFixed(2);
                    });

                    const discount = parseFloat(document.getElementById('pos_discount').value) || 0;
                    const finalTotal = Math.max(0, subtotal - discount);
                    
                    const paidInput = document.getElementById('pos_amount_paid');
                    if (paidInput.dataset.touched !== "true") {
                        paidInput.value = finalTotal.toFixed(2);
                    }
                    const amountPaid = parseFloat(paidInput.value) || 0;
                    const balanceDue = Math.max(0, finalTotal - amountPaid);

                    document.getElementById('pos_total_display').innerText = finalTotal.toFixed(2);
                    document.getElementById('pos_paid_display').innerText = amountPaid.toFixed(2);
                    document.getElementById('pos_balance_display').innerText = balanceDue.toFixed(2);
                }

                document.getElementById('pos_amount_paid').addEventListener('input', () => {
                    document.getElementById('pos_amount_paid').dataset.touched = "true";
                });

                window.addEventListener('DOMContentLoaded', () => {
                    addSaleRow();
                });
            </script>

        <?php
        // ---------------------------------------------------------------------------------
        // TAB: PRODUCTS & INVENTORY CRUD
        // ---------------------------------------------------------------------------------
        elseif ($tab === 'products'): ?>
            <div class="space-y-6">
                <div class="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-4">
                    <div>
                        <h1 class="text-2xl font-extrabold text-white tracking-tight">Products & Stock Inventory</h1>
                        <p class="text-slate-400 text-sm mt-1">Manage poultry, eggs, feed, prices, and stock levels</p>
                    </div>
                    <button onclick="document.getElementById('newProductModal').classList.remove('hidden')" class="px-4 py-2.5 bg-emerald-600 hover:bg-emerald-500 text-white font-bold text-sm rounded-xl shadow-lg shadow-emerald-900/30">
                        + Add New Product
                    </button>
                </div>

                <!-- Products Table -->
                <div class="glass-card rounded-2xl overflow-hidden">
                    <div class="overflow-x-auto">
                        <table class="w-full text-left text-sm text-slate-300">
                            <thead class="bg-slate-900/80 text-xs uppercase font-bold text-slate-400 border-b border-slate-800">
                                <tr>
                                    <th class="px-6 py-4">Product Name</th>
                                    <th class="px-6 py-4">Category</th>
                                    <th class="px-6 py-4">Selling Price</th>
                                    <th class="px-6 py-4">Cost Price</th>
                                    <th class="px-6 py-4">Stock Level</th>
                                    <th class="px-6 py-4 text-right">Actions</th>
                                </tr>
                            </thead>
                            <tbody class="divide-y divide-slate-800/60">
                                <?php if (empty($allProducts)): ?>
                                    <tr><td colspan="6" class="px-6 py-8 text-center text-slate-500">No products found. Click "+ Add New Product" to create one.</td></tr>
                                <?php else: foreach ($allProducts as $p): 
                                    $isLow = (float)$p['stockQuantity'] <= (float)$p['minStockThreshold'];
                                ?>
                                    <tr class="hover:bg-slate-900/40 transition-colors">
                                        <td class="px-6 py-4 font-semibold text-white">
                                            <?= htmlspecialchars($p['name']) ?>
                                            <?php if (!empty($p['description'])): ?>
                                                <div class="text-xs text-slate-500"><?= htmlspecialchars($p['description']) ?></div>
                                            <?php endif; ?>
                                        </td>
                                        <td class="px-6 py-4">
                                            <span class="px-2.5 py-1 rounded-full text-xs font-semibold bg-slate-800 text-slate-300 border border-slate-700">
                                                <?= htmlspecialchars($p['category']) ?>
                                            </span>
                                        </td>
                                        <td class="px-6 py-4 font-bold text-emerald-400"><?= $currency ?> <?= number_format($p['unitPrice'], 2) ?> / <?= htmlspecialchars($p['unit']) ?></td>
                                        <td class="px-6 py-4 text-slate-400"><?= $currency ?> <?= number_format($p['costPrice'], 2) ?></td>
                                        <td class="px-6 py-4">
                                            <span class="font-black <?= $isLow ? 'text-rose-400' : 'text-slate-200' ?>">
                                                <?= $p['stockQuantity'] ?> <?= htmlspecialchars($p['unit']) ?>
                                            </span>
                                            <?php if ($isLow): ?>
                                                <span class="ml-2 text-[10px] bg-rose-500/20 text-rose-400 font-bold px-2 py-0.5 rounded-full">Low Stock</span>
                                            <?php endif; ?>
                                        </td>
                                        <td class="px-6 py-4 text-right space-x-2">
                                            <button onclick="openRestockModal(<?= $p['id'] ?>, '<?= htmlspecialchars(addslashes($p['name'])) ?>', '<?= htmlspecialchars($p['unit']) ?>')" class="px-2.5 py-1 bg-emerald-600/20 hover:bg-emerald-600/30 text-emerald-400 rounded-lg text-xs font-bold border border-emerald-500/30">
                                                + Restock
                                            </button>
                                            <button onclick="openEditProductModal(<?= htmlspecialchars(json_encode($p)) ?>)" class="px-2.5 py-1 bg-slate-800 hover:bg-slate-700 text-slate-300 rounded-lg text-xs font-medium">
                                                Edit
                                            </button>
                                            <form method="POST" action="index.php" class="inline" onsubmit="return confirm('Delete <?= htmlspecialchars($p['name']) ?>?')">
                                                <input type="hidden" name="action" value="delete_product">
                                                <input type="hidden" name="id" value="<?= $p['id'] ?>">
                                                <button type="submit" class="px-2.5 py-1 bg-rose-950/40 hover:bg-rose-900/60 text-rose-400 rounded-lg text-xs font-medium">
                                                    Delete
                                                </button>
                                            </form>
                                        </td>
                                    </tr>
                                <?php endforeach; endif; ?>
                            </tbody>
                        </table>
                    </div>
                </div>
            </div>

            <!-- MODAL: ADD PRODUCT -->
            <div id="newProductModal" class="hidden fixed inset-0 z-50 bg-black/70 backdrop-blur-sm flex items-center justify-center p-4">
                <div class="bg-slate-900 border border-slate-700 rounded-2xl max-w-lg w-full p-6 shadow-2xl space-y-4">
                    <div class="flex items-center justify-between">
                        <h3 class="font-bold text-lg text-white">Create New Product</h3>
                        <button onclick="document.getElementById('newProductModal').classList.add('hidden')" class="text-slate-400 hover:text-white font-bold text-xl">&times;</button>
                    </div>
                    <form method="POST" action="index.php" class="space-y-4">
                        <input type="hidden" name="action" value="create_product">
                        <div>
                            <label class="block text-xs font-semibold text-slate-300 uppercase mb-1">Product Name</label>
                            <input type="text" name="name" required placeholder="e.g. Broiler Chicken (Live), Crate of Eggs" class="w-full px-4 py-2.5 bg-slate-950 border border-slate-700 rounded-xl text-white">
                        </div>
                        <div class="grid grid-cols-2 gap-3">
                            <div>
                                <label class="block text-xs font-semibold text-slate-300 uppercase mb-1">Category</label>
                                <select name="category" class="w-full px-4 py-2.5 bg-slate-950 border border-slate-700 rounded-xl text-white">
                                    <option value="BROILER">Broiler</option>
                                    <option value="LAYER">Layer</option>
                                    <option value="EGGS">Eggs</option>
                                    <option value="FEED">Feed</option>
                                    <option value="PROCESSED">Processed</option>
                                    <option value="OTHER">Other</option>
                                </select>
                            </div>
                            <div>
                                <label class="block text-xs font-semibold text-slate-300 uppercase mb-1">Unit of Measure</label>
                                <input type="text" name="unit" value="unit" placeholder="crate, kg, pcs, bag" class="w-full px-4 py-2.5 bg-slate-950 border border-slate-700 rounded-xl text-white">
                            </div>
                        </div>
                        <div class="grid grid-cols-2 gap-3">
                            <div>
                                <label class="block text-xs font-semibold text-slate-300 uppercase mb-1">Selling Price (<?= $currency ?>)</label>
                                <input type="number" step="0.01" name="unitPrice" required placeholder="0.00" class="w-full px-4 py-2.5 bg-slate-950 border border-slate-700 rounded-xl text-white">
                            </div>
                            <div>
                                <label class="block text-xs font-semibold text-slate-300 uppercase mb-1">Cost Price (<?= $currency ?>)</label>
                                <input type="number" step="0.01" name="costPrice" value="0.00" class="w-full px-4 py-2.5 bg-slate-950 border border-slate-700 rounded-xl text-white">
                            </div>
                        </div>
                        <div class="grid grid-cols-2 gap-3">
                            <div>
                                <label class="block text-xs font-semibold text-slate-300 uppercase mb-1">Initial Stock</label>
                                <input type="number" step="0.1" name="stockQuantity" value="0" class="w-full px-4 py-2.5 bg-slate-950 border border-slate-700 rounded-xl text-white">
                            </div>
                            <div>
                                <label class="block text-xs font-semibold text-slate-300 uppercase mb-1">Low Stock Alert Level</label>
                                <input type="number" step="0.1" name="minStockThreshold" value="5" class="w-full px-4 py-2.5 bg-slate-950 border border-slate-700 rounded-xl text-white">
                            </div>
                        </div>
                        <div>
                            <label class="block text-xs font-semibold text-slate-300 uppercase mb-1">Description (Optional)</label>
                            <input type="text" name="description" placeholder="Notes or batch details" class="w-full px-4 py-2.5 bg-slate-950 border border-slate-700 rounded-xl text-white">
                        </div>
                        <button type="submit" class="w-full py-3 bg-emerald-600 hover:bg-emerald-500 text-white font-bold rounded-xl shadow-lg">
                            Save Product
                        </button>
                    </form>
                </div>
            </div>

            <!-- MODAL: EDIT PRODUCT -->
            <div id="editProductModal" class="hidden fixed inset-0 z-50 bg-black/70 backdrop-blur-sm flex items-center justify-center p-4">
                <div class="bg-slate-900 border border-slate-700 rounded-2xl max-w-lg w-full p-6 shadow-2xl space-y-4">
                    <div class="flex items-center justify-between">
                        <h3 class="font-bold text-lg text-white">Edit Product</h3>
                        <button onclick="document.getElementById('editProductModal').classList.add('hidden')" class="text-slate-400 hover:text-white font-bold text-xl">&times;</button>
                    </div>
                    <form method="POST" action="index.php" class="space-y-4">
                        <input type="hidden" name="action" value="update_product">
                        <input type="hidden" name="id" id="edit_p_id">
                        <div>
                            <label class="block text-xs font-semibold text-slate-300 uppercase mb-1">Product Name</label>
                            <input type="text" name="name" id="edit_p_name" required class="w-full px-4 py-2.5 bg-slate-950 border border-slate-700 rounded-xl text-white">
                        </div>
                        <div class="grid grid-cols-2 gap-3">
                            <div>
                                <label class="block text-xs font-semibold text-slate-300 uppercase mb-1">Category</label>
                                <select name="category" id="edit_p_cat" class="w-full px-4 py-2.5 bg-slate-950 border border-slate-700 rounded-xl text-white">
                                    <option value="BROILER">Broiler</option>
                                    <option value="LAYER">Layer</option>
                                    <option value="EGGS">Eggs</option>
                                    <option value="FEED">Feed</option>
                                    <option value="PROCESSED">Processed</option>
                                    <option value="OTHER">Other</option>
                                </select>
                            </div>
                            <div>
                                <label class="block text-xs font-semibold text-slate-300 uppercase mb-1">Unit</label>
                                <input type="text" name="unit" id="edit_p_unit" class="w-full px-4 py-2.5 bg-slate-950 border border-slate-700 rounded-xl text-white">
                            </div>
                        </div>
                        <div class="grid grid-cols-2 gap-3">
                            <div>
                                <label class="block text-xs font-semibold text-slate-300 uppercase mb-1">Selling Price (<?= $currency ?>)</label>
                                <input type="number" step="0.01" name="unitPrice" id="edit_p_price" required class="w-full px-4 py-2.5 bg-slate-950 border border-slate-700 rounded-xl text-white">
                            </div>
                            <div>
                                <label class="block text-xs font-semibold text-slate-300 uppercase mb-1">Cost Price (<?= $currency ?>)</label>
                                <input type="number" step="0.01" name="costPrice" id="edit_p_cost" class="w-full px-4 py-2.5 bg-slate-950 border border-slate-700 rounded-xl text-white">
                            </div>
                        </div>
                        <div class="grid grid-cols-2 gap-3">
                            <div>
                                <label class="block text-xs font-semibold text-slate-300 uppercase mb-1">Current Stock</label>
                                <input type="number" step="0.1" name="stockQuantity" id="edit_p_stock" class="w-full px-4 py-2.5 bg-slate-950 border border-slate-700 rounded-xl text-white">
                            </div>
                            <div>
                                <label class="block text-xs font-semibold text-slate-300 uppercase mb-1">Low Stock Level</label>
                                <input type="number" step="0.1" name="minStockThreshold" id="edit_p_min" class="w-full px-4 py-2.5 bg-slate-950 border border-slate-700 rounded-xl text-white">
                            </div>
                        </div>
                        <div>
                            <label class="block text-xs font-semibold text-slate-300 uppercase mb-1">Description</label>
                            <input type="text" name="description" id="edit_p_desc" class="w-full px-4 py-2.5 bg-slate-950 border border-slate-700 rounded-xl text-white">
                        </div>
                        <button type="submit" class="w-full py-3 bg-emerald-600 hover:bg-emerald-500 text-white font-bold rounded-xl shadow-lg">
                            Update Product
                        </button>
                    </form>
                </div>
            </div>

            <!-- MODAL: RESTOCK -->
            <div id="restockModal" class="hidden fixed inset-0 z-50 bg-black/70 backdrop-blur-sm flex items-center justify-center p-4">
                <div class="bg-slate-900 border border-slate-700 rounded-2xl max-w-md w-full p-6 shadow-2xl space-y-4">
                    <div class="flex items-center justify-between">
                        <h3 class="font-bold text-lg text-white">Restock Product</h3>
                        <button onclick="document.getElementById('restockModal').classList.add('hidden')" class="text-slate-400 hover:text-white font-bold text-xl">&times;</button>
                    </div>
                    <form method="POST" action="index.php" class="space-y-4">
                        <input type="hidden" name="action" value="restock_product">
                        <input type="hidden" name="id" id="restock_p_id">
                        <div class="p-3 bg-slate-950 rounded-xl border border-slate-800">
                            <div class="text-xs text-slate-400">Product</div>
                            <div class="font-bold text-white text-base" id="restock_p_name">--</div>
                        </div>
                        <div>
                            <label class="block text-xs font-semibold text-slate-300 uppercase mb-1">Quantity to Add (<span id="restock_p_unit"></span>)</label>
                            <input type="number" step="0.1" name="add_quantity" required min="0.1" placeholder="e.g. 50" class="w-full px-4 py-3 bg-slate-950 border border-slate-700 rounded-xl text-white">
                        </div>
                        <div>
                            <label class="block text-xs font-semibold text-slate-300 uppercase mb-1">Reason / Batch Notes</label>
                            <input type="text" name="notes" placeholder="e.g. New harvest batch arrival" class="w-full px-4 py-3 bg-slate-950 border border-slate-700 rounded-xl text-white">
                        </div>
                        <button type="submit" class="w-full py-3 bg-emerald-600 hover:bg-emerald-500 text-white font-bold rounded-xl shadow-lg">
                            Confirm Restock
                        </button>
                    </form>
                </div>
            </div>

            <script>
                function openEditProductModal(p) {
                    document.getElementById('edit_p_id').value = p.id;
                    document.getElementById('edit_p_name').value = p.name;
                    document.getElementById('edit_p_cat').value = p.category;
                    document.getElementById('edit_p_unit').value = p.unit;
                    document.getElementById('edit_p_price').value = p.unitPrice;
                    document.getElementById('edit_p_cost').value = p.costPrice;
                    document.getElementById('edit_p_stock').value = p.stockQuantity;
                    document.getElementById('edit_p_min').value = p.minStockThreshold;
                    document.getElementById('edit_p_desc').value = p.description || '';
                    document.getElementById('editProductModal').classList.remove('hidden');
                }

                function openRestockModal(id, name, unit) {
                    document.getElementById('restock_p_id').value = id;
                    document.getElementById('restock_p_name').innerText = name;
                    document.getElementById('restock_p_unit').innerText = unit;
                    document.getElementById('restockModal').classList.remove('hidden');
                }
            </script>

        <?php
        // ---------------------------------------------------------------------------------
        // TAB: SALES HISTORY
        // ---------------------------------------------------------------------------------
        elseif ($tab === 'sales'): 
            $allSales = $db->query("SELECT * FROM sales_orders ORDER BY timestamp DESC")->fetchAll();
        ?>
            <div class="space-y-6">
                <div class="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-4">
                    <div>
                        <h1 class="text-2xl font-extrabold text-white tracking-tight">Sales Orders & Invoices</h1>
                        <p class="text-slate-400 text-sm mt-1">Full transaction register of all counter sales and deliveries</p>
                    </div>
                    <a href="index.php?tab=new_sale" class="px-4 py-2.5 bg-emerald-600 hover:bg-emerald-500 text-white font-bold text-sm rounded-xl shadow-lg shadow-emerald-900/30">
                        + New Sale
                    </a>
                </div>

                <div class="glass-card rounded-2xl overflow-hidden">
                    <div class="overflow-x-auto">
                        <table class="w-full text-left text-sm text-slate-300">
                            <thead class="bg-slate-900/80 text-xs uppercase font-bold text-slate-400 border-b border-slate-800">
                                <tr>
                                    <th class="px-6 py-4">Invoice #</th>
                                    <th class="px-6 py-4">Customer</th>
                                    <th class="px-6 py-4">Date</th>
                                    <th class="px-6 py-4">Total Amount</th>
                                    <th class="px-6 py-4">Paid</th>
                                    <th class="px-6 py-4">Balance Due</th>
                                    <th class="px-6 py-4">Status</th>
                                </tr>
                            </thead>
                            <tbody class="divide-y divide-slate-800/60">
                                <?php if (empty($allSales)): ?>
                                    <tr><td colspan="7" class="px-6 py-8 text-center text-slate-500">No sales recorded yet.</td></tr>
                                <?php else: foreach ($allSales as $s): ?>
                                    <tr class="hover:bg-slate-900/40 transition-colors">
                                        <td class="px-6 py-4 font-bold text-white">#<?= htmlspecialchars((string)($s['invoiceNumber'] ?? ('INV-' . ($s['id'] ?? '')))) ?></td>
                                        <td class="px-6 py-4 font-semibold text-slate-200">
                                            <?= htmlspecialchars((string)($s['customerName'] ?? 'Walk-in Customer')) ?>
                                            <?php if (!empty($s['customerPhone'])): ?>
                                                <div class="text-xs text-slate-500"><?= htmlspecialchars((string)$s['customerPhone']) ?></div>
                                            <?php endif; ?>
                                        </td>
                                        <td class="px-6 py-4 text-slate-400 text-xs"><?= date('d M Y, h:i A', (int)round((float)($s['timestamp'] ?? 0) / 1000)) ?></td>
                                        <td class="px-6 py-4 font-bold text-white"><?= $currency ?> <?= number_format($s['totalAmount'], 2) ?></td>
                                        <td class="px-6 py-4 text-emerald-400 font-semibold"><?= $currency ?> <?= number_format($s['amountPaid'], 2) ?></td>
                                        <td class="px-6 py-4 font-bold <?= (float)$s['balanceDue'] > 0 ? 'text-rose-400' : 'text-slate-400' ?>">
                                            <?= $currency ?> <?= number_format($s['balanceDue'], 2) ?>
                                        </td>
                                        <td class="px-6 py-4">
                                            <span class="px-2.5 py-1 rounded-full text-xs font-bold <?= (float)$s['balanceDue'] > 0 ? 'bg-rose-500/20 text-rose-400' : 'bg-emerald-500/20 text-emerald-400' ?>">
                                                <?= $s['paymentStatus'] ?>
                                            </span>
                                        </td>
                                    </tr>
                                <?php endforeach; endif; ?>
                            </tbody>
                        </table>
                    </div>
                </div>
            </div>

        <?php
        // ---------------------------------------------------------------------------------
        // TAB: CUSTOMERS & DEBTORS
        // ---------------------------------------------------------------------------------
        elseif ($tab === 'customers' || $tab === 'debtors'): 
            $isDebtorsOnly = ($tab === 'debtors');
            $customerList = $isDebtorsOnly 
                ? $db->query("SELECT * FROM customers WHERE COALESCE(currentBalance, totalDebt, 0) > 0.009 ORDER BY COALESCE(currentBalance, totalDebt, 0) DESC")->fetchAll()
                : $db->query("SELECT * FROM customers ORDER BY name ASC")->fetchAll();
        ?>
            <div class="space-y-6">
                <div class="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-4">
                    <div>
                        <h1 class="text-2xl font-extrabold text-white tracking-tight"><?= $isDebtorsOnly ? 'Debtors Ledger (Owing Money)' : 'Customer Directory' ?></h1>
                        <p class="text-slate-400 text-sm mt-1">Track customer balances, WhatsApp links, and debt repayments</p>
                    </div>
                    <div class="flex gap-3">
                        <button onclick="document.getElementById('newCustomerModal').classList.remove('hidden')" class="px-4 py-2.5 bg-emerald-600 hover:bg-emerald-500 text-white font-bold text-sm rounded-xl shadow-lg shadow-emerald-900/30">
                            + Add New Customer
                        </button>
                    </div>
                </div>

                <div class="glass-card rounded-2xl overflow-hidden">
                    <div class="overflow-x-auto">
                        <table class="w-full text-left text-sm text-slate-300">
                            <thead class="bg-slate-900/80 text-xs uppercase font-bold text-slate-400 border-b border-slate-800">
                                <tr>
                                    <th class="px-6 py-4">Customer Name</th>
                                    <th class="px-6 py-4">Contact</th>
                                    <th class="px-6 py-4">Total Purchases</th>
                                    <th class="px-6 py-4">Total Paid</th>
                                    <th class="px-6 py-4">Current Debt</th>
                                    <th class="px-6 py-4 text-right">Actions</th>
                                </tr>
                            </thead>
                            <tbody class="divide-y divide-slate-800/60">
                                <?php if (empty($customerList)): ?>
                                    <tr><td colspan="6" class="px-6 py-8 text-center text-slate-500">No customers found.</td></tr>
                                <?php else: foreach ($customerList as $c): 
                                    $bal = (float)($c['currentBalance'] ?: $c['totalDebt'] ?: 0.0);
                                ?>
                                    <tr class="hover:bg-slate-900/40 transition-colors">
                                        <td class="px-6 py-4 font-semibold text-white">
                                            <?= htmlspecialchars($c['name']) ?>
                                            <?php if (!empty($c['address'])): ?>
                                                <div class="text-xs text-slate-500"><?= htmlspecialchars($c['address']) ?></div>
                                            <?php endif; ?>
                                        </td>
                                        <td class="px-6 py-4">
                                            <div><?= htmlspecialchars($c['phone'] ?: 'No phone') ?></div>
                                            <?php if (!empty($c['whatsapp'])): ?>
                                                <a href="https://wa.me/<?= preg_replace('/[^0-9]/', '', $c['whatsapp']) ?>" target="_blank" class="text-xs text-emerald-400 hover:underline">WhatsApp 💬</a>
                                            <?php endif; ?>
                                        </td>
                                        <td class="px-6 py-4 text-slate-300 font-semibold"><?= $currency ?> <?= number_format($c['totalPurchases'], 2) ?></td>
                                        <td class="px-6 py-4 text-emerald-400 font-semibold"><?= $currency ?> <?= number_format($c['totalPaid'], 2) ?></td>
                                        <td class="px-6 py-4 font-black <?= $bal > 0 ? 'text-rose-400' : 'text-emerald-400' ?>">
                                            <?= $currency ?> <?= number_format($bal, 2) ?>
                                        </td>
                                        <td class="px-6 py-4 text-right space-x-2">
                                            <?php if ($bal > 0): ?>
                                                <button onclick="openPaymentModal(<?= $c['id'] ?>, '<?= htmlspecialchars(addslashes($c['name'])) ?>', <?= $bal ?>)" class="px-3 py-1 bg-emerald-600/20 hover:bg-emerald-600/30 text-emerald-400 rounded-lg text-xs font-bold border border-emerald-500/30">
                                                    + Record Payment
                                                </button>
                                            <?php endif; ?>
                                            <button onclick="openEditCustomerModal(<?= htmlspecialchars(json_encode($c)) ?>)" class="px-2.5 py-1 bg-slate-800 hover:bg-slate-700 text-slate-300 rounded-lg text-xs font-medium">
                                                Edit
                                            </button>
                                            <form method="POST" action="index.php" class="inline" onsubmit="return confirm('Delete <?= htmlspecialchars($c['name']) ?>?')">
                                                <input type="hidden" name="action" value="delete_customer">
                                                <input type="hidden" name="id" value="<?= $c['id'] ?>">
                                                <button type="submit" class="px-2.5 py-1 bg-rose-950/40 hover:bg-rose-900/60 text-rose-400 rounded-lg text-xs font-medium">
                                                    Delete
                                                </button>
                                            </form>
                                        </td>
                                    </tr>
                                <?php endforeach; endif; ?>
                            </tbody>
                        </table>
                    </div>
                </div>
            </div>

            <!-- MODAL: ADD CUSTOMER -->
            <div id="newCustomerModal" class="hidden fixed inset-0 z-50 bg-black/70 backdrop-blur-sm flex items-center justify-center p-4">
                <div class="bg-slate-900 border border-slate-700 rounded-2xl max-w-md w-full p-6 shadow-2xl space-y-4">
                    <div class="flex items-center justify-between">
                        <h3 class="font-bold text-lg text-white">Add New Customer</h3>
                        <button onclick="document.getElementById('newCustomerModal').classList.add('hidden')" class="text-slate-400 hover:text-white font-bold text-xl">&times;</button>
                    </div>
                    <form method="POST" action="index.php" class="space-y-4">
                        <input type="hidden" name="action" value="create_customer">
                        <div>
                            <label class="block text-xs font-semibold text-slate-300 uppercase mb-1">Customer Full Name</label>
                            <input type="text" name="name" required placeholder="e.g. Mama Akua, Kofi Mensah" class="w-full px-4 py-2.5 bg-slate-950 border border-slate-700 rounded-xl text-white">
                        </div>
                        <div class="grid grid-cols-2 gap-3">
                            <div>
                                <label class="block text-xs font-semibold text-slate-300 uppercase mb-1">Phone Number</label>
                                <input type="text" name="phone" placeholder="024XXXXXXX" class="w-full px-4 py-2.5 bg-slate-950 border border-slate-700 rounded-xl text-white">
                            </div>
                            <div>
                                <label class="block text-xs font-semibold text-slate-300 uppercase mb-1">WhatsApp</label>
                                <input type="text" name="whatsapp" placeholder="024XXXXXXX" class="w-full px-4 py-2.5 bg-slate-950 border border-slate-700 rounded-xl text-white">
                            </div>
                        </div>
                        <div>
                            <label class="block text-xs font-semibold text-slate-300 uppercase mb-1">Location / Address</label>
                            <input type="text" name="address" placeholder="e.g. Madina Market, Accra" class="w-full px-4 py-2.5 bg-slate-950 border border-slate-700 rounded-xl text-white">
                        </div>
                        <div>
                            <label class="block text-xs font-semibold text-slate-300 uppercase mb-1">Initial Outstanding Debt (<?= $currency ?>)</label>
                            <input type="number" step="0.01" name="initial_debt" value="0.00" class="w-full px-4 py-2.5 bg-slate-950 border border-slate-700 rounded-xl text-white">
                        </div>
                        <div>
                            <label class="block text-xs font-semibold text-slate-300 uppercase mb-1">Notes</label>
                            <input type="text" name="notes" placeholder="Optional notes" class="w-full px-4 py-2.5 bg-slate-950 border border-slate-700 rounded-xl text-white">
                        </div>
                        <button type="submit" class="w-full py-3 bg-emerald-600 hover:bg-emerald-500 text-white font-bold rounded-xl shadow-lg">
                            Save Customer
                        </button>
                    </form>
                </div>
            </div>

            <!-- MODAL: EDIT CUSTOMER -->
            <div id="editCustomerModal" class="hidden fixed inset-0 z-50 bg-black/70 backdrop-blur-sm flex items-center justify-center p-4">
                <div class="bg-slate-900 border border-slate-700 rounded-2xl max-w-md w-full p-6 shadow-2xl space-y-4">
                    <div class="flex items-center justify-between">
                        <h3 class="font-bold text-lg text-white">Edit Customer</h3>
                        <button onclick="document.getElementById('editCustomerModal').classList.add('hidden')" class="text-slate-400 hover:text-white font-bold text-xl">&times;</button>
                    </div>
                    <form method="POST" action="index.php" class="space-y-4">
                        <input type="hidden" name="action" value="update_customer">
                        <input type="hidden" name="id" id="edit_c_id">
                        <div>
                            <label class="block text-xs font-semibold text-slate-300 uppercase mb-1">Customer Name</label>
                            <input type="text" name="name" id="edit_c_name" required class="w-full px-4 py-2.5 bg-slate-950 border border-slate-700 rounded-xl text-white">
                        </div>
                        <div class="grid grid-cols-2 gap-3">
                            <div>
                                <label class="block text-xs font-semibold text-slate-300 uppercase mb-1">Phone</label>
                                <input type="text" name="phone" id="edit_c_phone" class="w-full px-4 py-2.5 bg-slate-950 border border-slate-700 rounded-xl text-white">
                            </div>
                            <div>
                                <label class="block text-xs font-semibold text-slate-300 uppercase mb-1">WhatsApp</label>
                                <input type="text" name="whatsapp" id="edit_c_whatsapp" class="w-full px-4 py-2.5 bg-slate-950 border border-slate-700 rounded-xl text-white">
                            </div>
                        </div>
                        <div>
                            <label class="block text-xs font-semibold text-slate-300 uppercase mb-1">Address</label>
                            <input type="text" name="address" id="edit_c_address" class="w-full px-4 py-2.5 bg-slate-950 border border-slate-700 rounded-xl text-white">
                        </div>
                        <div>
                            <label class="block text-xs font-semibold text-slate-300 uppercase mb-1">Notes</label>
                            <input type="text" name="notes" id="edit_c_notes" class="w-full px-4 py-2.5 bg-slate-950 border border-slate-700 rounded-xl text-white">
                        </div>
                        <button type="submit" class="w-full py-3 bg-emerald-600 hover:bg-emerald-500 text-white font-bold rounded-xl shadow-lg">
                            Update Customer
                        </button>
                    </form>
                </div>
            </div>

            <script>
                function openEditCustomerModal(c) {
                    document.getElementById('edit_c_id').value = c.id;
                    document.getElementById('edit_c_name').value = c.name;
                    document.getElementById('edit_c_phone').value = c.phone || '';
                    document.getElementById('edit_c_whatsapp').value = c.whatsapp || '';
                    document.getElementById('edit_c_address').value = c.address || '';
                    document.getElementById('edit_c_notes').value = c.notes || '';
                    document.getElementById('editCustomerModal').classList.remove('hidden');
                }
            </script>

        <?php
        // ---------------------------------------------------------------------------------
        // TAB: PAYMENT LOGS
        // ---------------------------------------------------------------------------------
        elseif ($tab === 'payments'): 
            $allPayments = $db->query("SELECT * FROM payments ORDER BY timestamp DESC")->fetchAll();
        ?>
            <div class="space-y-6">
                <div>
                    <h1 class="text-2xl font-extrabold text-white tracking-tight">Payments & Debt Recoveries</h1>
                    <p class="text-slate-400 text-sm mt-1">Audit trail of all cash, MoMo, and installment receipts</p>
                </div>

                <div class="glass-card rounded-2xl overflow-hidden">
                    <div class="overflow-x-auto">
                        <table class="w-full text-left text-sm text-slate-300">
                            <thead class="bg-slate-900/80 text-xs uppercase font-bold text-slate-400 border-b border-slate-800">
                                <tr>
                                    <th class="px-6 py-4">Receipt #</th>
                                    <th class="px-6 py-4">Customer</th>
                                    <th class="px-6 py-4">Date</th>
                                    <th class="px-6 py-4">Amount Paid</th>
                                    <th class="px-6 py-4">Method</th>
                                    <th class="px-6 py-4">Balance After</th>
                                    <th class="px-6 py-4">Notes</th>
                                </tr>
                            </thead>
                            <tbody class="divide-y divide-slate-800/60">
                                <?php if (empty($allPayments)): ?>
                                    <tr><td colspan="7" class="px-6 py-8 text-center text-slate-500">No payment records found.</td></tr>
                                <?php else: foreach ($allPayments as $p): ?>
                                    <tr class="hover:bg-slate-900/40">
                                        <td class="px-6 py-4 font-bold text-white">#PAY-<?= $p['id'] ?></td>
                                        <td class="px-6 py-4 font-semibold text-slate-200"><?= htmlspecialchars($p['customerName']) ?></td>
                                        <td class="px-6 py-4 text-slate-400 text-xs"><?= date('d M Y, h:i A', (int)round((float)($p['timestamp'] ?? 0) / 1000)) ?></td>
                                        <td class="px-6 py-4 font-bold text-emerald-400"><?= $currency ?> <?= number_format($p['amount'], 2) ?></td>
                                        <td class="px-6 py-4">
                                            <span class="px-2.5 py-0.5 rounded-full text-xs font-semibold bg-slate-800 text-slate-300">
                                                <?= htmlspecialchars($p['paymentMethod']) ?>
                                            </span>
                                        </td>
                                        <td class="px-6 py-4 font-bold <?= (float)$p['balanceAfterPayment'] > 0 ? 'text-rose-400' : 'text-slate-400' ?>">
                                            <?= $currency ?> <?= number_format($p['balanceAfterPayment'], 2) ?>
                                        </td>
                                        <td class="px-6 py-4 text-xs text-slate-400"><?= htmlspecialchars($p['notes'] ?: '—') ?></td>
                                    </tr>
                                <?php endforeach; endif; ?>
                            </tbody>
                        </table>
                    </div>
                </div>
            </div>

        <?php
        // ---------------------------------------------------------------------------------
        // TAB: INVENTORY LOGS
        // ---------------------------------------------------------------------------------
        elseif ($tab === 'inventory_logs'): 
            $allLogs = $db->query("SELECT * FROM inventory_logs ORDER BY timestamp DESC LIMIT 100")->fetchAll();
        ?>
            <div class="space-y-6">
                <div>
                    <h1 class="text-2xl font-extrabold text-white tracking-tight">Inventory Movement History</h1>
                    <p class="text-slate-400 text-sm mt-1">Detailed log of all stock restocks, sales deductions, and adjustments</p>
                </div>

                <div class="glass-card rounded-2xl overflow-hidden">
                    <div class="overflow-x-auto">
                        <table class="w-full text-left text-sm text-slate-300">
                            <thead class="bg-slate-900/80 text-xs uppercase font-bold text-slate-400 border-b border-slate-800">
                                <tr>
                                    <th class="px-6 py-4">Timestamp</th>
                                    <th class="px-6 py-4">Product</th>
                                    <th class="px-6 py-4">Action Type</th>
                                    <th class="px-6 py-4">Quantity Changed</th>
                                    <th class="px-6 py-4">Stock After</th>
                                    <th class="px-6 py-4">Reason / Notes</th>
                                </tr>
                            </thead>
                            <tbody class="divide-y divide-slate-800/60">
                                <?php if (empty($allLogs)): ?>
                                    <tr><td colspan="6" class="px-6 py-8 text-center text-slate-500">No inventory movements recorded yet.</td></tr>
                                <?php else: foreach ($allLogs as $l): 
                                    $isAdd = (float)$l['quantityChanged'] > 0;
                                ?>
                                    <tr class="hover:bg-slate-900/40">
                                        <td class="px-6 py-4 text-xs text-slate-400"><?= date('d M Y, h:i A', (int)round((float)($l['timestamp'] ?? 0) / 1000)) ?></td>
                                        <td class="px-6 py-4 font-bold text-white"><?= htmlspecialchars($l['productName']) ?></td>
                                        <td class="px-6 py-4">
                                            <span class="px-2.5 py-0.5 rounded-full text-xs font-bold <?= $l['changeType'] === 'RESTOCK' ? 'bg-emerald-500/20 text-emerald-400' : 'bg-cyan-500/20 text-cyan-400' ?>">
                                                <?= htmlspecialchars($l['changeType']) ?>
                                            </span>
                                        </td>
                                        <td class="px-6 py-4 font-black <?= $isAdd ? 'text-emerald-400' : 'text-rose-400' ?>">
                                            <?= $isAdd ? '+' : '' ?><?= $l['quantityChanged'] ?> <?= htmlspecialchars($l['unit']) ?>
                                        </td>
                                        <td class="px-6 py-4 font-semibold text-slate-300"><?= $l['quantityAfter'] ?> <?= htmlspecialchars($l['unit']) ?></td>
                                        <td class="px-6 py-4 text-xs text-slate-400"><?= htmlspecialchars($l['notes'] ?: '—') ?></td>
                                    </tr>
                                <?php endforeach; endif; ?>
                            </tbody>
                        </table>
                    </div>
                </div>
            </div>

        <?php
        // ---------------------------------------------------------------------------------
        // TAB: SETTINGS
        // ---------------------------------------------------------------------------------
        elseif ($tab === 'settings'): ?>
            <div class="max-w-3xl space-y-6">
                <div>
                    <h1 class="text-2xl font-extrabold text-white tracking-tight">Farm Settings & Receipt Info</h1>
                    <p class="text-slate-400 text-sm mt-1">Configure business profile, currency symbol, and payment details</p>
                </div>

                <div class="glass-card p-6 lg:p-8 rounded-2xl">
                    <form method="POST" action="index.php" class="space-y-5">
                        <input type="hidden" name="action" value="update_settings">
                        <div>
                            <label class="block text-xs font-semibold text-slate-300 uppercase mb-2">Farm / Business Name</label>
                            <input type="text" name="businessName" value="<?= htmlspecialchars((string)($settings['businessName'] ?? '')) ?>" required class="w-full px-4 py-3 bg-slate-900 border border-slate-700 rounded-xl text-white">
                        </div>
                        <div>
                            <label class="block text-xs font-semibold text-slate-300 uppercase mb-2">Tagline / Slogan</label>
                            <input type="text" name="businessTagline" value="<?= htmlspecialchars((string)($settings['businessTagline'] ?? '')) ?>" class="w-full px-4 py-3 bg-slate-900 border border-slate-700 rounded-xl text-white">
                        </div>
                        <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
                            <div>
                                <label class="block text-xs font-semibold text-slate-300 uppercase mb-2">Contact Phone</label>
                                <input type="text" name="businessPhone" value="<?= htmlspecialchars((string)($settings['businessPhone'] ?? '')) ?>" class="w-full px-4 py-3 bg-slate-900 border border-slate-700 rounded-xl text-white">
                            </div>
                            <div>
                                <label class="block text-xs font-semibold text-slate-300 uppercase mb-2">Currency Symbol</label>
                                <input type="text" name="currencySymbol" value="<?= htmlspecialchars((string)(($settings['currencySymbol'] ?? '') ?: 'GH₵')) ?>" class="w-full px-4 py-3 bg-slate-900 border border-slate-700 rounded-xl text-white">
                            </div>
                        </div>
                        <div>
                            <label class="block text-xs font-semibold text-slate-300 uppercase mb-2">Location / Address</label>
                            <input type="text" name="businessLocation" value="<?= htmlspecialchars((string)($settings['businessLocation'] ?? '')) ?>" class="w-full px-4 py-3 bg-slate-900 border border-slate-700 rounded-xl text-white">
                        </div>
                        <div>
                            <label class="block text-xs font-semibold text-slate-300 uppercase mb-2">MTN MoMo Payment Instructions</label>
                            <input type="text" name="momoPaymentDetails" value="<?= htmlspecialchars((string)($settings['momoPaymentDetails'] ?? '')) ?>" class="w-full px-4 py-3 bg-slate-900 border border-slate-700 rounded-xl text-white">
                        </div>
                        <button type="submit" class="w-full py-3.5 bg-emerald-600 hover:bg-emerald-500 text-white font-bold rounded-xl shadow-lg transition-all">
                            Save Farm Settings
                        </button>
                    </form>
                </div>
            </div>
        <?php endif; ?>
    </main>

    <!-- GLOBAL MODAL: RECORD PAYMENT -->
    <div id="paymentModal" class="hidden fixed inset-0 z-50 bg-black/70 backdrop-blur-sm flex items-center justify-center p-4">
        <div class="bg-slate-900 border border-slate-700 rounded-2xl max-w-md w-full p-6 shadow-2xl space-y-4">
            <div class="flex items-center justify-between">
                <h3 class="font-bold text-lg text-white">Record Debt Payment</h3>
                <button onclick="document.getElementById('paymentModal').classList.add('hidden')" class="text-slate-400 hover:text-white font-bold text-xl">&times;</button>
            </div>
            <form method="POST" action="index.php" class="space-y-4">
                <input type="hidden" name="action" value="record_payment">
                <input type="hidden" name="customer_id" id="pay_cust_id">
                <div class="p-3 bg-slate-950 rounded-xl border border-slate-800 space-y-1">
                    <div class="text-xs text-slate-400">Debtor</div>
                    <div class="font-bold text-white text-base" id="pay_cust_name">--</div>
                    <div class="text-xs text-rose-400 font-semibold">Current Balance: <?= $currency ?> <span id="pay_cust_debt">0.00</span></div>
                </div>
                <div>
                    <label class="block text-xs font-semibold text-slate-300 uppercase mb-1">Amount to Pay (<?= $currency ?>)</label>
                    <input type="number" step="0.01" name="amount" id="pay_amount" required min="0.01" class="w-full px-4 py-3 bg-slate-950 border border-slate-700 rounded-xl text-white font-bold text-lg text-emerald-400">
                </div>
                <div>
                    <label class="block text-xs font-semibold text-slate-300 uppercase mb-1">Payment Method</label>
                    <select name="payment_method" class="w-full px-4 py-3 bg-slate-950 border border-slate-700 rounded-xl text-white">
                        <option value="CASH">💵 Cash</option>
                        <option value="MOMO">📱 MTN MoMo</option>
                        <option value="BANK">🏦 Bank Transfer</option>
                    </select>
                </div>
                <div>
                    <label class="block text-xs font-semibold text-slate-300 uppercase mb-1">Payment Memo / Receipt Note</label>
                    <input type="text" name="notes" placeholder="e.g. Received via MoMo reference #1234" class="w-full px-4 py-3 bg-slate-950 border border-slate-700 rounded-xl text-white">
                </div>
                <button type="submit" class="w-full py-3.5 bg-emerald-600 hover:bg-emerald-500 text-white font-bold rounded-xl shadow-lg">
                    Confirm & Apply Payment
                </button>
            </form>
        </div>
    </div>

    <script>
        function openPaymentModal(customerId, customerName, debtAmount) {
            document.getElementById('pay_cust_id').value = customerId;
            document.getElementById('pay_cust_name').innerText = customerName;
            document.getElementById('pay_cust_debt').innerText = parseFloat(debtAmount).toFixed(2);
            document.getElementById('pay_amount').value = parseFloat(debtAmount).toFixed(2);
            document.getElementById('paymentModal').classList.remove('hidden');
        }
    </script>
</body>
</html>
