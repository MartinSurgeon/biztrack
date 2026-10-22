<?php
/**
 * BizTrack POS & Business Manager - Namecheap cPanel MySQL Cloud Sync API
 * 
 * Instructions:
 * 1. Log into your Namecheap cPanel.
 * 2. Create a MySQL Database and Database User via "MySQL Databases".
 * 3. Import `schema.sql` via phpMyAdmin into your database.
 * 4. Configure $DB_HOST, $DB_NAME, $DB_USER, $DB_PASS, and $API_SECRET_KEY below.
 * 5. Upload this file to your `public_html/api/sync.php` (or similar public folder).
 */

header('Content-Type: application/json; charset=utf-8');
header('X-Content-Type-Options: nosniff');
header('Cache-Control: no-store');
// No CORS headers: the Android app is not a browser and needs none.

// ==========================================
// 1. CONFIGURATION (Edit with your cPanel info)
// ==========================================
$DB_HOST        = 'localhost';
$DB_NAME        = 'YOUR_CPANEL_USERNAME_biztrack'; // e.g., 'ama_biztrack'
$DB_USER        = 'YOUR_CPANEL_USERNAME_biztrack_user';
$DB_PASS        = 'YOUR_STRONG_DATABASE_PASSWORD';
$API_SECRET_KEY = 'CHANGE_ME_TO_A_LONG_RANDOM_SECRET'; // 32+ random chars. Match this in the Android App Settings!

// Optional: keep real credentials in config.php (never commit it) instead of editing this file.
if (file_exists(__DIR__ . '/config.php')) {
    require __DIR__ . '/config.php';
}

// Refuse to run with a missing, default or weak secret.
if (strlen($API_SECRET_KEY) < 24 || strpos($API_SECRET_KEY, 'CHANGE_ME') !== false) {
    http_response_code(503);
    echo json_encode(['status' => 'error', 'message' => 'Server not configured: set a strong API_SECRET_KEY.']);
    exit;
}

// ==========================================
// 2. CONNECT TO MYSQL (PDO)
// ==========================================
try {
    $pdo = new PDO(
        "mysql:host={$DB_HOST};dbname={$DB_NAME};charset=utf8mb4",
        $DB_USER,
        $DB_PASS,
        [
            PDO::ATTR_ERRMODE            => PDO::ERRMODE_EXCEPTION,
            PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
            PDO::ATTR_EMULATE_PREPARES   => false
        ]
    );
} catch (PDOException $e) {
    http_response_code(500);
    error_log('BizTrack sync DB connection failed: ' . $e->getMessage());
    echo json_encode(['status' => 'error', 'message' => 'Database connection failed.']);
    exit;
}

// Shared schema, sync merge and photo code (upload biztrack_core.php next to this file).
if (!file_exists(__DIR__ . '/biztrack_core.php')) {
    http_response_code(500);
    echo json_encode(['status' => 'error', 'message' => 'Server incomplete: upload biztrack_core.php next to sync.php.']);
    exit;
}
require_once __DIR__ . '/biztrack_core.php';
try {
    bt_ensure_schema($pdo);
} catch (Throwable $e) {
    http_response_code(500);
    error_log('BizTrack schema upgrade failed: ' . $e->getMessage());
    echo json_encode(['status' => 'error', 'message' => 'Server database upgrade failed: ' . $e->getMessage()]);
    exit;
}

// ==========================================
// 3. HEALTH CHECK (GET Request)
// ==========================================
if ($_SERVER['REQUEST_METHOD'] === 'GET') {
    $headerKey = (string)($_SERVER['HTTP_X_API_KEY'] ?? '');
    if (!hash_equals($API_SECRET_KEY, $headerKey)) {
        http_response_code(401);
        echo json_encode([
            'status'  => 'error',
            'message' => 'Invalid or missing API Secret Key.'
        ]);
        exit;
    }

    echo json_encode([
        'status'          => 'success',
        'message'         => 'BizTrack Sync API is online and healthy.',
        'serverTimestamp' => round(microtime(true) * 1000)
    ]);
    exit;
}

// ==========================================
// 4. AUTHENTICATION & ACTION DISPATCH (POST)
// ==========================================
$rawInput = file_get_contents('php://input');
$data     = json_decode($rawInput, true);

$providedKey = (string)($data['secretKey'] ?? $_SERVER['HTTP_X_API_KEY'] ?? '');
if (!hash_equals($API_SECRET_KEY, $providedKey)) {
    http_response_code(401);
    echo json_encode([
        'status'  => 'error',
        'message' => 'Unauthorized: Invalid API Secret Key.'
    ]);
    exit;
}

$action = $data['action'] ?? $_POST['action'] ?? $_SERVER['HTTP_X_SYNC_ACTION'] ?? (isset($data['backupData']) ? 'PUSH' : 'PULL');

// ----------------------------------------------------
// UPLOAD_IMAGE: store a product photo sent by the app (multipart: productId + image)
// ----------------------------------------------------
if (strtoupper((string)$action) === 'UPLOAD_IMAGE') {
    if (empty($_FILES['image'])) {
        http_response_code(400);
        echo json_encode(['status' => 'error', 'message' => 'No image received.']);
        exit;
    }
    [$url, $err] = bt_store_product_image($_FILES['image']);
    if ($err !== null) {
        http_response_code(422);
        echo json_encode(['status' => 'error', 'message' => $err]);
        exit;
    }
    $productId = (int)($_POST['productId'] ?? 0);
    if ($productId > 0) {
        $old = $pdo->prepare("SELECT imageUrl FROM products WHERE id = ?");
        $old->execute([$productId]);
        $oldUrl = (string)($old->fetchColumn() ?: '');
        $pdo->prepare("UPDATE products SET imageUrl = ? WHERE id = ?")->execute([$url, $productId]);
        if ($oldUrl !== $url) bt_forget_product_image($pdo, $oldUrl);
    }
    echo json_encode(['status' => 'success', 'message' => 'Photo uploaded.', 'url' => $url]);
    exit;
}

// ----------------------------------------------------
// PULL: Return all MySQL records to Android phone
// ----------------------------------------------------
if (strtoupper($action) === 'PULL') {
    try {
        $products      = $pdo->query("SELECT * FROM products ORDER BY id ASC")->fetchAll();
        $customers     = $pdo->query("SELECT * FROM customers ORDER BY id ASC")->fetchAll();
        $salesOrders   = $pdo->query("SELECT * FROM sales_orders ORDER BY id ASC")->fetchAll();
        $payments      = $pdo->query("SELECT * FROM payments ORDER BY id ASC")->fetchAll();
        $inventoryLogs = $pdo->query("SELECT * FROM inventory_logs ORDER BY id ASC")->fetchAll();
        $settingsRow   = $pdo->query("SELECT * FROM app_settings WHERE id = 1 LIMIT 1")->fetch();

        // Cast numeric fields and map to Android entity names
        $formattedProducts = [];
        foreach ($products as $p) {
            $formattedProducts[] = [
                'id'                => (int)$p['id'],
                'name'              => (string)($p['name'] ?? 'Product'),
                'category'          => (string)($p['category'] ?? 'OTHER'),
                'unit'              => (string)($p['unit'] ?? 'unit'),
                'unitPrice'         => (float)($p['unitPrice'] ?? $p['price'] ?? 0.0),
                'costPrice'         => (float)($p['costPrice'] ?? 0.0),
                'stockQuantity'     => (float)($p['stockQuantity'] ?? 0.0),
                'minStockThreshold' => (float)($p['minStockThreshold'] ?? 5.0),
                'inStock'           => (bool)(((float)($p['stockQuantity'] ?? 0.0)) > 0),
                'description'       => (string)($p['description'] ?? ''),
                // Web address of the photo. Older app versions read imageUri, newer ones remoteImageUrl.
                'imageUri'          => (string)($p['imageUrl'] ?? ''),
                'remoteImageUrl'    => (string)($p['imageUrl'] ?? ''),
                'lastRestockedAt'   => (int)($p['lastRestockedAt'] ?? 0),
                'updatedAt'         => (int)($p['updatedAt'] ?? 0)
            ];
        }

        $formattedCustomers = [];
        foreach ($customers as $c) {
            $formattedCustomers[] = [
                'id'             => (int)$c['id'],
                'name'           => (string)($c['name'] ?? 'Customer'),
                'phone'          => (string)($c['phone'] ?? ''),
                'whatsapp'       => (string)($c['whatsapp'] ?? ''),
                'address'        => (string)($c['address'] ?? ''),
                'notes'          => (string)($c['notes'] ?? ''),
                'totalPurchases' => (float)($c['totalPurchases'] ?? 0.0),
                'totalPaid'      => (float)($c['totalPaid'] ?? 0.0),
                'currentBalance' => (float)($c['currentBalance'] ?? 0.0),
                'openingBalance' => (float)($c['openingBalance'] ?? 0.0),
                'openingPurchases' => (float)($c['openingPurchases'] ?? 0.0),
                'createdAt'      => (int)($c['createdAt'] ?: ($c['lastTransactionDate'] ?? 0)),
                'updatedAt'      => (int)($c['updatedAt'] ?? 0)
            ];
        }

        $formattedSalesOrders = [];
        foreach ($salesOrders as $s) {
            $formattedSalesOrders[] = [
                'id'               => (int)$s['id'],
                'invoiceNumber'    => (string)($s['invoiceNumber'] ?? ('INV-' . $s['id'])),
                'customerId'       => (int)($s['customerId'] ?? 0),
                'customerName'     => (string)($s['customerName'] ?? 'Walk-in Customer'),
                'customerPhone'    => (string)($s['customerPhone'] ?? ''),
                'customerWhatsapp' => (string)($s['customerWhatsapp'] ?? ''),
                'itemsJson'        => (string)($s['itemsJson'] ?? '[]'),
                'totalAmount'      => (float)($s['totalAmount'] ?? 0.0),
                'discountAmount'   => (float)($s['discountAmount'] ?? 0.0),
                'amountPaid'       => (float)($s['amountPaid'] ?? 0.0),
                'balanceDue'       => (float)($s['balanceDue'] ?? $s['debtAmount'] ?? 0.0),
                'paymentStatus'    => (string)($s['paymentStatus'] ?? 'PAID'),
                'paymentMethod'    => (string)($s['paymentMethod'] ?? 'CASH'),
                'notes'            => (string)($s['notes'] ?? ''),
                'timestamp'        => (int)($s['timestamp'] ?? round(microtime(true) * 1000)),
                'smsSentCount'     => (int)($s['smsSentCount'] ?? 0),
                'lastSmsTimestamp' => ($s['lastSmsTimestamp'] !== null && $s['lastSmsTimestamp'] > 0) ? (int)$s['lastSmsTimestamp'] : null,
                'updatedAt'        => (int)($s['updatedAt'] ?? 0)
            ];
        }

        $formattedPayments = [];
        foreach ($payments as $pm) {
            $formattedPayments[] = [
                'id'                  => (int)$pm['id'],
                'customerId'          => (int)($pm['customerId'] ?? 0),
                'customerName'        => (string)($pm['customerName'] ?? ''),
                'saleOrderId'         => ($pm['saleOrderId'] !== null && $pm['saleOrderId'] > 0) ? (int)$pm['saleOrderId'] : null,
                'amount'              => (float)($pm['amount'] ?? 0.0),
                'paymentMethod'       => (string)($pm['paymentMethod'] ?? 'CASH'),
                'notes'               => (string)($pm['notes'] ?? ''),
                'timestamp'           => (int)($pm['timestamp'] ?? round(microtime(true) * 1000)),
                'balanceAfterPayment' => (float)($pm['balanceAfterPayment'] ?? 0.0),
                'updatedAt'           => (int)($pm['updatedAt'] ?? 0)
            ];
        }

        $formattedLogs = [];
        foreach ($inventoryLogs as $l) {
            $formattedLogs[] = [
                'id'              => (int)$l['id'],
                'productId'       => (int)($l['productId'] ?? 0),
                'productName'     => (string)($l['productName'] ?? ''),
                'changeType'      => (string)($l['changeType'] ?? 'ADJUSTMENT'),
                'quantityChanged' => (float)($l['quantityChanged'] ?? 0.0),
                'quantityAfter'   => (float)($l['quantityAfter'] ?? 0.0),
                'unit'            => (string)($l['unit'] ?? 'unit'),
                'notes'           => (string)($l['notes'] ?? ''),
                'timestamp'       => (int)($l['timestamp'] ?? round(microtime(true) * 1000))
            ];
        }

        $formattedDeleted = [];
        foreach ($pdo->query("SELECT tableName, recordId, deletedAt FROM deleted_records") as $d) {
            $formattedDeleted[] = ['tableName' => $d['tableName'], 'recordId' => (int)$d['recordId'], 'deletedAt' => (int)$d['deletedAt']];
        }

        $appSettings = [
            'id'                  => 1,
            'businessName'        => (string)($settingsRow['businessName'] ?? 'BizTrack Business'),
            'businessTagline'     => (string)($settingsRow['businessTagline'] ?? 'Quality products & reliable service'),
            'businessPhone'       => (string)($settingsRow['businessPhone'] ?? '024 000 0000'),
            'businessLocation'    => (string)($settingsRow['businessLocation'] ?? 'Ghana'),
            'momoPaymentDetails'  => (string)($settingsRow['momoPaymentDetails'] ?? 'MTN MoMo: 0244XXXXXX (BizTrack)'),
            'smsApiKey'           => '', // secrets are never sent back to devices
            'smsSenderId'         => '', // SMS options belong to the phone; blank keeps its own
            'smsAutoSendOnSale'   => isset($settingsRow['smsAutoSendOnSale']) ? (bool)$settingsRow['smsAutoSendOnSale'] : true,
            'smsAutoSendOnPayment'=> isset($settingsRow['smsAutoSendOnPayment']) ? (bool)$settingsRow['smsAutoSendOnPayment'] : true,
            'currencySymbol'      => (string)($settingsRow['currencySymbol'] ?? 'GH₵'),
            'cloudSyncUrl'        => (string)($settingsRow['cloudSyncUrl'] ?? ''),
            'cloudSyncSecretKey'  => '',
            'cloudAutoSyncOnSale' => isset($settingsRow['cloudAutoSyncOnSale']) ? (bool)$settingsRow['cloudAutoSyncOnSale'] : false,
            'lastCloudSyncTime'   => (int)($settingsRow['lastCloudSyncTime'] ?? 0),
            'lastCloudSyncStatus' => (string)($settingsRow['lastCloudSyncStatus'] ?? 'Synced'),
            'updatedAt'           => (int)($settingsRow['updatedAt'] ?? 0)
        ];

        echo json_encode([
            'status'          => 'success',
            'message'         => 'Cloud database records successfully fetched.',
            'serverTimestamp' => round(microtime(true) * 1000),
            'backupData'      => [
                'formatVersion' => 1,
                'appName'       => 'BizTrack POS',
                'exportedAt'    => round(microtime(true) * 1000),
                'exportedAtFormatted' => date('d M Y, h:i A'),
                'businessName'  => $appSettings['businessName'],
                'products'      => $formattedProducts,
                'customers'     => $formattedCustomers,
                'salesOrders'   => $formattedSalesOrders,
                'payments'      => $formattedPayments,
                'inventoryLogs' => $formattedLogs,
                'settings'      => $appSettings,
                'deletedRecords' => $formattedDeleted,
                'summary'       => [
                    'totalProducts'          => count($formattedProducts),
                    'totalCustomers'         => count($formattedCustomers),
                    'totalSalesOrders'       => count($formattedSalesOrders),
                    'totalPayments'          => count($formattedPayments),
                    'totalInventoryLogs'     => count($formattedLogs),
                    'totalOutstandingDebt'   => array_sum(array_column($formattedCustomers, 'currentBalance')),
                    'totalRevenue'           => array_sum(array_column($formattedSalesOrders, 'totalAmount'))
                ]
            ]
        ]);
        exit;
    } catch (Exception $e) {
        http_response_code(500);
        echo json_encode(['status' => 'error', 'message' => 'Pull failed: ' . $e->getMessage()]);
        exit;
    }
}

// ----------------------------------------------------
// PUSH: merge the phone's records (newer edits win, stock via logs, deletions kept)
// ----------------------------------------------------
$backup = $data['backupData'] ?? null;
if (!is_array($backup)) {
    http_response_code(400);
    echo json_encode(['status' => 'error', 'message' => 'Missing backupData payload in push request.']);
    exit;
}

try {
    $stats = bt_merge_backup($pdo, $backup);
    echo json_encode([
        'status'          => 'success',
        'message'         => 'Cloud database is up to date with your latest records.',
        'serverTimestamp' => bt_now_ms(),
        'merged'          => $stats
    ]);
} catch (Throwable $e) {
    http_response_code(500);
    error_log('BizTrack sync push failed: ' . $e->getMessage());
    echo json_encode(['status' => 'error', 'message' => 'Sync failed: ' . $e->getMessage()]);
}
