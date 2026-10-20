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

// Keep older databases compatible with photo sync (adds missing columns once).
foreach (['imageUrl' => "VARCHAR(512) NOT NULL DEFAULT ''", 'unitPrice' => "DECIMAL(12,2) NOT NULL DEFAULT 0.00", 'description' => "TEXT NULL"] as $col => $def) {
    try {
        if ($pdo->query("SHOW COLUMNS FROM products LIKE '$col'")->rowCount() === 0) {
            $pdo->exec("ALTER TABLE products ADD COLUMN `$col` $def");
        }
    } catch (Exception $e) { /* table may not exist yet */ }
}

// ----------------------------------------------------
// PRODUCT PHOTOS (shared by sync.php and index.php; keep both copies in sync)
// Photos live in ./uploads/products/ next to this file, named by content hash.
// ----------------------------------------------------
if (!function_exists('bt_store_product_image')) {
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
                'lastRestockedAt'   => (int)($p['lastRestockedAt'] ?? round(microtime(true) * 1000))
            ];
        }

        $formattedCustomers = [];
        foreach ($customers as $c) {
            $debt = 0.0;
            if (isset($c['currentBalance']) && (float)$c['currentBalance'] > 0.001) {
                $debt = (float)$c['currentBalance'];
            } elseif (isset($c['totalDebt']) && (float)$c['totalDebt'] > 0.001) {
                $debt = (float)$c['totalDebt'];
            } elseif (isset($c['currentBalance'])) {
                $debt = (float)$c['currentBalance'];
            } else {
                $debt = (float)($c['totalDebt'] ?? 0.0);
            }

            $purchases = 0.0;
            if (isset($c['totalPurchases']) && (float)$c['totalPurchases'] > 0.001) {
                $purchases = (float)$c['totalPurchases'];
            } elseif (isset($c['totalPurchased']) && (float)$c['totalPurchased'] > 0.001) {
                $purchases = (float)$c['totalPurchased'];
            } elseif (isset($c['totalPurchases'])) {
                $purchases = (float)$c['totalPurchases'];
            } else {
                $purchases = (float)($c['totalPurchased'] ?? 0.0);
            }

            $formattedCustomers[] = [
                'id'             => (int)$c['id'],
                'name'           => (string)($c['name'] ?? 'Customer'),
                'phone'          => (string)($c['phone'] ?? ''),
                'whatsapp'       => (string)($c['whatsapp'] ?? ''),
                'address'        => (string)($c['address'] ?? ''),
                'notes'          => (string)($c['notes'] ?? ''),
                'totalPurchases' => $purchases,
                'totalPaid'      => (float)($c['totalPaid'] ?? 0.0),
                'currentBalance' => $debt,
                'createdAt'      => (int)($c['createdAt'] ?? $c['lastTransactionDate'] ?? round(microtime(true) * 1000))
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
                'lastSmsTimestamp' => ($s['lastSmsTimestamp'] !== null && $s['lastSmsTimestamp'] > 0) ? (int)$s['lastSmsTimestamp'] : null
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
                'balanceAfterPayment' => (float)($pm['balanceAfterPayment'] ?? 0.0)
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

        $appSettings = [
            'id'                  => 1,
            'businessName'        => (string)($settingsRow['businessName'] ?? 'BizTrack Business'),
            'businessTagline'     => (string)($settingsRow['businessTagline'] ?? 'Quality products & reliable service'),
            'businessPhone'       => (string)($settingsRow['businessPhone'] ?? '024 000 0000'),
            'businessLocation'    => (string)($settingsRow['businessLocation'] ?? 'Ghana'),
            'momoPaymentDetails'  => (string)($settingsRow['momoPaymentDetails'] ?? 'MTN MoMo: 0244XXXXXX (BizTrack)'),
            'smsApiKey'           => '', // secrets are never sent back to devices
            'smsSenderId'         => (string)($settingsRow['smsSenderId'] ?? 'BizTrack'),
            'smsAutoSendOnSale'   => isset($settingsRow['smsAutoSendOnSale']) ? (bool)$settingsRow['smsAutoSendOnSale'] : true,
            'smsAutoSendOnPayment'=> isset($settingsRow['smsAutoSendOnPayment']) ? (bool)$settingsRow['smsAutoSendOnPayment'] : true,
            'currencySymbol'      => (string)($settingsRow['currencySymbol'] ?? 'GH₵'),
            'cloudSyncUrl'        => (string)($settingsRow['cloudSyncUrl'] ?? ''),
            'cloudSyncSecretKey'  => '',
            'cloudAutoSyncOnSale' => isset($settingsRow['cloudAutoSyncOnSale']) ? (bool)$settingsRow['cloudAutoSyncOnSale'] : false,
            'lastCloudSyncTime'   => (int)($settingsRow['lastCloudSyncTime'] ?? 0),
            'lastCloudSyncStatus' => (string)($settingsRow['lastCloudSyncStatus'] ?? 'Synced')
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
// PUSH: Sync Android Phone Data -> MySQL (Upsert)
// ----------------------------------------------------
$backup = $data['backupData'] ?? null;
if (!$backup) {
    http_response_code(400);
    echo json_encode(['status' => 'error', 'message' => 'Missing backupData payload in push request.']);
    exit;
}

$removedPhotos = [];
try {
    $pdo->beginTransaction();

    // 1. Sync Products
    if (!empty($backup['products'])) {
        // price and unitPrice are both kept (the web dashboard reads unitPrice).
        // imageUrl: a web address replaces the server's photo, "none" means the user removed
        // it, and blank (not uploaded yet / older app) leaves the server's photo untouched.
        $stmt = $pdo->prepare("
            INSERT INTO products (id, name, category, price, unitPrice, costPrice, stockQuantity, unit, minStockThreshold, description, imageUrl, lastRestockedAt)
            VALUES (:id, :name, :category, :price, :unitPrice, :costPrice, :stockQuantity, :unit, :minStockThreshold, :description, :imageUrl, :lastRestockedAt)
            ON DUPLICATE KEY UPDATE
                name = VALUES(name),
                category = VALUES(category),
                price = VALUES(price),
                unitPrice = VALUES(unitPrice),
                costPrice = VALUES(costPrice),
                stockQuantity = VALUES(stockQuantity),
                unit = VALUES(unit),
                minStockThreshold = VALUES(minStockThreshold),
                description = VALUES(description),
                imageUrl = IF(:imageAction = 'keep', imageUrl, VALUES(imageUrl)),
                lastRestockedAt = VALUES(lastRestockedAt)
        ");
        $oldUrls = [];
        foreach ($pdo->query("SELECT id, imageUrl FROM products")->fetchAll() as $row) $oldUrls[(int)$row['id']] = (string)$row['imageUrl'];
        foreach ($backup['products'] as $p) {
            $remote = trim((string)($p['remoteImageUrl'] ?? ''));
            if ($remote === 'none') { $imageAction = 'clear'; $imageUrl = ''; }
            elseif (preg_match('#^https?://#i', $remote)) { $imageAction = 'set'; $imageUrl = substr($remote, 0, 512); }
            else { $imageAction = 'keep'; $imageUrl = ''; }
            $price = $p['unitPrice'] ?? $p['price'] ?? 0.0;
            $stmt->execute([
                ':id'                => $p['id'],
                ':name'              => $p['name'],
                ':category'          => $p['category'] ?? 'GENERAL',
                ':price'             => $price,
                ':unitPrice'         => $price,
                ':costPrice'         => $p['costPrice'] ?? 0.0,
                ':stockQuantity'     => $p['stockQuantity'] ?? 0.0,
                ':unit'              => $p['unit'] ?? 'Piece',
                ':minStockThreshold' => $p['minStockThreshold'] ?? 5.0,
                ':description'       => (string)($p['description'] ?? ''),
                ':imageUrl'          => $imageUrl,
                ':imageAction'       => $imageAction,
                ':lastRestockedAt'   => $p['lastRestockedAt'] ?? round(microtime(true) * 1000)
            ]);
            $prev = $oldUrls[(int)$p['id']] ?? '';
            if ($imageAction !== 'keep' && $prev !== '' && $prev !== $imageUrl) $removedPhotos[] = $prev;
        }
    }

    // 2. Sync Customers
    if (!empty($backup['customers'])) {
        $stmt = $pdo->prepare("
            INSERT INTO customers (id, name, phone, whatsapp, address, notes, totalDebt, totalPurchased, totalPaid, lastTransactionDate)
            VALUES (:id, :name, :phone, :whatsapp, :address, :notes, :totalDebt, :totalPurchased, :totalPaid, :lastTransactionDate)
            ON DUPLICATE KEY UPDATE
                name = VALUES(name),
                phone = VALUES(phone),
                whatsapp = VALUES(whatsapp),
                address = VALUES(address),
                notes = VALUES(notes),
                totalDebt = VALUES(totalDebt),
                totalPurchased = VALUES(totalPurchased),
                totalPaid = VALUES(totalPaid),
                lastTransactionDate = VALUES(lastTransactionDate)
        ");
        foreach ($backup['customers'] as $c) {
            $stmt->execute([
                ':id'                  => $c['id'],
                ':name'                => $c['name'],
                ':phone'               => $c['phone'] ?? '',
                ':whatsapp'            => $c['whatsapp'] ?? '',
                ':address'             => $c['address'] ?? '',
                ':notes'               => $c['notes'] ?? '',
                ':totalDebt'           => $c['currentBalance'] ?? $c['totalDebt'] ?? 0.0,
                ':totalPurchased'      => $c['totalPurchases'] ?? $c['totalPurchased'] ?? 0.0,
                ':totalPaid'           => $c['totalPaid'] ?? 0.0,
                ':lastTransactionDate' => $c['createdAt'] ?? $c['lastTransactionDate'] ?? round(microtime(true) * 1000)
            ]);
        }
    }

    // 3. Sync Sales Orders
    if (!empty($backup['salesOrders'])) {
        $stmt = $pdo->prepare("
            INSERT INTO sales_orders (id, customerId, customerName, customerPhone, itemsJson, totalAmount, amountPaid, debtAmount, discountAmount, paymentStatus, paymentMethod, notes, timestamp, lastSmsTimestamp)
            VALUES (:id, :customerId, :customerName, :customerPhone, :itemsJson, :totalAmount, :amountPaid, :debtAmount, :discountAmount, :paymentStatus, :paymentMethod, :notes, :timestamp, :lastSmsTimestamp)
            ON DUPLICATE KEY UPDATE
                customerName = VALUES(customerName),
                customerPhone = VALUES(customerPhone),
                itemsJson = VALUES(itemsJson),
                totalAmount = VALUES(totalAmount),
                amountPaid = VALUES(amountPaid),
                debtAmount = VALUES(debtAmount),
                discountAmount = VALUES(discountAmount),
                paymentStatus = VALUES(paymentStatus),
                paymentMethod = VALUES(paymentMethod),
                notes = VALUES(notes),
                lastSmsTimestamp = VALUES(lastSmsTimestamp)
        ");
        foreach ($backup['salesOrders'] as $s) {
            $stmt->execute([
                ':id'               => $s['id'],
                ':customerId'       => $s['customerId'] ?? null,
                ':customerName'     => $s['customerName'],
                ':customerPhone'    => $s['customerPhone'] ?? '',
                ':itemsJson'        => $s['itemsJson'],
                ':totalAmount'      => (float)($s['totalAmount'] ?? 0.0),
                ':amountPaid'       => (float)($s['amountPaid'] ?? 0.0),
                ':debtAmount'       => (float)($s['balanceDue'] ?? $s['debtAmount'] ?? 0.0),
                ':discountAmount'   => (float)($s['discountAmount'] ?? 0.0),
                ':paymentStatus'    => $s['paymentStatus'] ?? 'PAID',
                ':paymentMethod'    => $s['paymentMethod'] ?? 'CASH',
                ':notes'            => $s['notes'] ?? '',
                ':timestamp'        => $s['timestamp'] ?? round(microtime(true) * 1000),
                ':lastSmsTimestamp' => $s['lastSmsTimestamp'] ?? null
            ]);
        }
    }

    // 4. Sync Payments
    if (!empty($backup['payments'])) {
        $stmt = $pdo->prepare("
            INSERT INTO payments (id, customerId, customerName, saleOrderId, amount, paymentMethod, notes, timestamp, balanceAfterPayment)
            VALUES (:id, :customerId, :customerName, :saleOrderId, :amount, :paymentMethod, :notes, :timestamp, :balanceAfterPayment)
            ON DUPLICATE KEY UPDATE
                amount = VALUES(amount),
                paymentMethod = VALUES(paymentMethod),
                notes = VALUES(notes),
                balanceAfterPayment = VALUES(balanceAfterPayment)
        ");
        foreach ($backup['payments'] as $pm) {
            $stmt->execute([
                ':id'                  => $pm['id'],
                ':customerId'          => $pm['customerId'],
                ':customerName'        => $pm['customerName'],
                ':saleOrderId'         => $pm['saleOrderId'] ?? null,
                ':amount'              => $pm['amount'] ?? 0.0,
                ':paymentMethod'       => $pm['paymentMethod'] ?? 'CASH',
                ':notes'               => $pm['notes'] ?? '',
                ':timestamp'           => $pm['timestamp'] ?? round(microtime(true) * 1000),
                ':balanceAfterPayment' => $pm['balanceAfterPayment'] ?? 0.0
            ]);
        }
    }

    // 5. Sync Inventory Logs
    if (!empty($backup['inventoryLogs'])) {
        $stmt = $pdo->prepare("
            INSERT INTO inventory_logs (id, productId, productName, changeType, quantityChanged, quantityAfter, unit, notes, timestamp)
            VALUES (:id, :productId, :productName, :changeType, :quantityChanged, :quantityAfter, :unit, :notes, :timestamp)
            ON DUPLICATE KEY UPDATE
                productName = VALUES(productName),
                changeType = VALUES(changeType),
                quantityChanged = VALUES(quantityChanged),
                quantityAfter = VALUES(quantityAfter),
                notes = VALUES(notes)
        ");
        foreach ($backup['inventoryLogs'] as $l) {
            $stmt->execute([
                ':id'              => $l['id'],
                ':productId'       => $l['productId'],
                ':productName'     => $l['productName'],
                ':changeType'      => $l['changeType'],
                ':quantityChanged' => $l['quantityChanged'] ?? 0.0,
                ':quantityAfter'   => $l['quantityAfter'] ?? 0.0,
                ':unit'            => $l['unit'] ?? 'unit',
                ':notes'           => $l['notes'] ?? '',
                ':timestamp'       => $l['timestamp'] ?? round(microtime(true) * 1000)
            ]);
        }
    }

    // 6. Sync Business Profile / Settings
    if (!empty($backup['settings'])) {
        $st = $backup['settings'];
        $stmt = $pdo->prepare("
            INSERT INTO app_settings (id, businessName, businessTagline, businessPhone, businessLocation, momoPaymentDetails, smsApiKey, smsSenderId, currencySymbol)
            VALUES (1, :businessName, :businessTagline, :businessPhone, :businessLocation, :momoPaymentDetails, :smsApiKey, :smsSenderId, :currencySymbol)
            ON DUPLICATE KEY UPDATE
                businessName = VALUES(businessName),
                businessTagline = VALUES(businessTagline),
                businessPhone = VALUES(businessPhone),
                businessLocation = VALUES(businessLocation),
                momoPaymentDetails = VALUES(momoPaymentDetails),
                smsApiKey = VALUES(smsApiKey),
                smsSenderId = VALUES(smsSenderId),
                currencySymbol = VALUES(currencySymbol)
        ");
        $stmt->execute([
            ':businessName'        => $st['businessName'] ?? 'BizTrack Business',
            ':businessTagline'     => $st['businessTagline'] ?? '',
            ':businessPhone'       => $st['businessPhone'] ?? '',
            ':businessLocation'    => $st['businessLocation'] ?? '',
            ':momoPaymentDetails'  => $st['momoPaymentDetails'] ?? '',
            ':smsApiKey'           => $st['smsApiKey'] ?? '',
            ':smsSenderId'         => $st['smsSenderId'] ?? 'BizTrack',
            ':currencySymbol'      => $st['currencySymbol'] ?? 'GH₵'
        ]);
    }

    $pdo->commit();

    // Delete photo files that no product uses any more.
    foreach (array_unique($removedPhotos) as $u) bt_forget_product_image($pdo, $u);

    echo json_encode([
        'status'          => 'success',
        'message'         => 'Cloud MySQL database successfully updated with your latest records.',
        'serverTimestamp' => round(microtime(true) * 1000)
    ]);
} catch (Exception $e) {
    if ($pdo->inTransaction()) {
        $pdo->rollBack();
    }
    http_response_code(500);
    echo json_encode([
        'status'  => 'error',
        'message' => 'Sync failed: ' . $e->getMessage()
    ]);
}
?>
