<?php
/**
 * =========================================================================
 * BIZTRACK - WEB DASHBOARD
 * Sales, stock, customers and payments for any kind of business.
 *
 * Works directly with your cPanel MySQL database. Changes made here show up
 * in the Android app on its next sync.
 * =========================================================================
 */

// Never show errors to visitors (they leak paths and SQL); log them server-side instead.
ini_set('display_errors', '0');
ini_set('display_startup_errors', '0');
ini_set('log_errors', '1');
error_reporting(E_ALL);

header('X-Frame-Options: DENY');
header('X-Content-Type-Options: nosniff');
header('Referrer-Policy: no-referrer');

// Safe session startup with hardened cookie
if (session_status() === PHP_SESSION_NONE) {
    session_set_cookie_params([
        'lifetime' => 0,
        'path'     => '/',
        'secure'   => !empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off',
        'httponly' => true,
        'samesite' => 'Strict',
    ]);
    @session_start();
}

// CSRF token: every POST form must carry it.
if (empty($_SESSION['csrf'])) {
    $_SESSION['csrf'] = bin2hex(random_bytes(32));
}
if ($_SERVER['REQUEST_METHOD'] === 'POST'
    && !hash_equals($_SESSION['csrf'], (string)($_POST['csrf'] ?? ''))) {
    http_response_code(403);
    exit('Invalid or expired form token. Go back, reload the page and try again.');
}

// ----------------------------------------------------
// 0. SHARED VOCABULARY (kept in sync with the Android app)
// ----------------------------------------------------

// Product categories: same keys as ProductCategory in the app. Legacy farm keys
// are only shown when a product already uses them.
$CATEGORIES = [
    'GENERAL'       => 'General',
    'FOOD_DRINKS'   => 'Food & Drinks',
    'GROCERIES'     => 'Groceries',
    'FASHION'       => 'Fashion & Apparel',
    'ELECTRONICS'   => 'Electronics',
    'HEALTH_BEAUTY' => 'Health & Beauty',
    'HOME_HARDWARE' => 'Home & Hardware',
    'AGRICULTURE'   => 'Agriculture & Farm',
    'SERVICES'      => 'Services',
    'OTHER'         => 'Other',
];
$LEGACY_CATEGORIES = [
    'EGGS' => 'Eggs', 'BROILER_LIVE' => 'Live Broiler Chicken', 'BROILER_DRESSED' => 'Dressed Broiler Chicken',
    'SASSO_LIVE' => 'Live Sasso Chicken', 'SASSO_DRESSED' => 'Dressed Sasso Chicken',
    'BROILER' => 'Broiler', 'LAYER' => 'Layer', 'FEED' => 'Feed', 'PROCESSED' => 'Processed',
];
// Same values as PaymentMethod in the app.
$PAYMENT_METHODS = ['CASH' => 'Cash', 'MOMO' => 'Mobile money', 'BANK_TRANSFER' => 'Bank transfer'];
$COMMON_UNITS = ['Piece', 'Kg', 'Box', 'Pack', 'Bottle', 'Litre', 'Dozen', 'Bag', 'Hour', 'Service'];

function e($v): string { return htmlspecialchars((string)$v, ENT_QUOTES, 'UTF-8'); }

function money($amount): string {
    global $currency;
    return e($currency) . ' ' . number_format((float)$amount, 2);
}

function fmtDate($ms, string $format = 'd M Y, H:i'): string {
    $sec = (int)round((float)$ms / 1000);
    return $sec > 0 ? date($format, $sec) : '—';
}

function fmtQty($q): string {
    $q = (float)$q;
    return floor($q) == $q ? number_format($q, 0) : rtrim(rtrim(number_format($q, 2), '0'), '.');
}

function categoryLabel(string $key): string {
    global $CATEGORIES, $LEGACY_CATEGORIES;
    if (isset($CATEGORIES[$key])) return $CATEGORIES[$key];
    if (isset($LEGACY_CATEGORIES[$key])) return $LEGACY_CATEGORIES[$key];
    $key = trim($key);
    if ($key === '') return 'General';
    // Old SHOUTY_KEYS are prettified; names a business typed ("Pastries") are shown as-is.
    return preg_match('/^[A-Z0-9_]+$/', $key) ? ucfirst(strtolower(str_replace('_', ' ', $key))) : $key;
}

function methodLabel(string $m): string {
    global $PAYMENT_METHODS;
    if ($m === 'BANK') return 'Bank transfer'; // older web records
    return $PAYMENT_METHODS[$m] ?? ucfirst(strtolower(str_replace('_', ' ', $m ?: 'Cash')));
}

/** [label, tone] for a sale's payment state. Handles legacy "CREDIT" rows. */
function saleStatus(array $s): array {
    $status = strtoupper((string)($s['paymentStatus'] ?? ''));
    $balance = (float)($s['balanceDue'] ?? 0);
    if ($balance <= 0.009 || $status === 'PAID') return ['Paid', 'success'];
    if ($status === 'PARTIAL' || (float)($s['amountPaid'] ?? 0) > 0.009) return ['Partial', 'warning'];
    return ['Unpaid', 'danger'];
}

/**
 * Records created on the web get IDs from 10^15 upward. Phones use their own lower ranges
 * (RecordIds.kt in the app), so sync - which matches records by ID - can never overwrite a
 * phone's record with a web one or the other way round. Keep WEB_ID_BASE equal in both.
 */
const WEB_ID_BASE = 1000000000000000; // 1e15, below 2^53 so JavaScript keeps it exact

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

/** Invoice number for display; older rows without one fall back to INV-<id>. */
function invoiceNo(array $s): string {
    $n = trim((string)($s['invoiceNumber'] ?? ''));
    return $n !== '' ? $n : 'INV-' . (int)($s['id'] ?? 0);
}

function initials(string $name): string {
    $parts = preg_split('/\s+/', trim($name)) ?: [];
    $out = '';
    foreach (array_slice(array_filter($parts), 0, 2) as $p) $out .= mb_strtoupper(mb_substr($p, 0, 1));
    return $out ?: '?';
}

function avatarTone(string $name): string {
    $tones = ['primary', 'success', 'warning', 'info', 'danger'];
    return $tones[abs(crc32($name)) % count($tones)];
}

function csrfField(): string {
    return '<input type="hidden" name="csrf" value="' . e($_SESSION['csrf'] ?? '') . '">';
}

function jsonAttr($data): string {
    return e(json_encode($data, JSON_HEX_TAG | JSON_HEX_AMP | JSON_HEX_APOS | JSON_HEX_QUOT));
}

/**
 * Product image with a coloured ring (same look as the app): the uploaded photo, or the
 * first letter on a tinted tile. If the photo fails to load, the letter underneath shows.
 */
function productThumb(array $p, int $size = 44): string {
    $tone = avatarTone((string)($p['category'] ?? ''));
    $letter = e(mb_strtoupper(mb_substr(trim((string)($p['name'] ?? '?')), 0, 1)) ?: '?');
    $url = (string)($p['imageUrl'] ?? '');
    $img = preg_match('#^https?://#i', $url)
        ? '<img src="' . e($url) . '" alt="" loading="lazy" onerror="this.remove()">'
        : '';
    return '<span class="thumb ring-' . $tone . '" style="width:' . $size . 'px;height:' . $size . 'px">'
        . '<span class="thumb-in bg-tone-' . $tone . '">' . $letter . $img . '</span></span>';
}

/** Inline Lucide-style icons (stroke based, inherit currentColor). */
function icon(string $name, string $class = 'ic'): string {
    static $paths = [
        'home' => '<path d="m3 9 9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><path d="M9 22V12h6v10"/>',
        'plus' => '<path d="M12 5v14M5 12h14"/>',
        'minus' => '<path d="M5 12h14"/>',
        'receipt' => '<path d="M4 2v20l2-1 2 1 2-1 2 1 2-1 2 1 2-1 2 1V2l-2 1-2-1-2 1-2-1-2 1-2-1-2 1Z"/><path d="M8 7h8M8 11h8M8 15h5"/>',
        'box' => '<path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/><path d="M3.27 6.96 12 12.01l8.73-5.05M12 22.08V12"/>',
        'users' => '<path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75"/>',
        'wallet' => '<path d="M19 7V4a1 1 0 0 0-1-1H5a2 2 0 0 0 0 4h15a1 1 0 0 1 1 1v4h-3a2 2 0 0 0 0 4h3a1 1 0 0 0 1-1v-2a1 1 0 0 0-1-1"/><path d="M3 5v14a2 2 0 0 0 2 2h15a1 1 0 0 0 1-1v-4"/>',
        'history' => '<path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/><path d="M3 3v5h5M12 7v5l4 2"/>',
        'settings' => '<path d="M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z"/><circle cx="12" cy="12" r="3"/>',
        'logout' => '<path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9"/>',
        'search' => '<circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/>',
        'trend' => '<path d="m22 7-8.5 8.5-5-5L2 17"/><path d="M16 7h6v6"/>',
        'trendDown' => '<path d="m22 17-8.5-8.5-5 5L2 7"/><path d="M16 17h6v-6"/>',
        'check' => '<path d="M20 6 9 17l-5-5"/>',
        'checkCircle' => '<circle cx="12" cy="12" r="10"/><path d="m9 12 2 2 4-4"/>',
        'clock' => '<circle cx="12" cy="12" r="10"/><path d="M12 6v6l4 2"/>',
        'alert' => '<path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3"/><path d="M12 9v4M12 17h.01"/>',
        'x' => '<path d="M18 6 6 18M6 6l12 12"/>',
        'edit' => '<path d="M12 20h9"/><path d="M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4Z"/>',
        'trash' => '<path d="M3 6h18M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/>',
        'menu' => '<path d="M4 6h16M4 12h16M4 18h16"/>',
        'sun' => '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M6.34 17.66l-1.41 1.41M19.07 4.93l-1.41 1.41"/>',
        'moon' => '<path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z"/>',
        'printer' => '<path d="M6 9V2h12v7M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2"/><path d="M6 14h12v8H6z"/>',
        'chat' => '<path d="M7.9 20A9 9 0 1 0 4 16.1L2 22Z"/>',
        'phone' => '<path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72c.13.96.36 1.9.7 2.81a2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45c.91.34 1.85.57 2.81.7A2 2 0 0 1 22 16.92z"/>',
        'store' => '<path d="m2 7 4.41-4.41A2 2 0 0 1 7.83 2h8.34a2 2 0 0 1 1.42.59L22 7"/><path d="M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8M15 22v-4a2 2 0 0 0-2-2h-2a2 2 0 0 0-2 2v4M2 7h20M22 7v3a2 2 0 0 1-2 2 2.7 2.7 0 0 1-2-1 2.7 2.7 0 0 1-4 0 2.7 2.7 0 0 1-4 0 2.7 2.7 0 0 1-4 0 2.7 2.7 0 0 1-2 1 2 2 0 0 1-2-2V7"/>',
        'cart' => '<circle cx="8" cy="21" r="1"/><circle cx="19" cy="21" r="1"/><path d="M2.05 2.05h2l2.66 12.42a2 2 0 0 0 2 1.58h9.78a2 2 0 0 0 1.95-1.57l1.65-7.43H5.12"/>',
        'arrowRight' => '<path d="M5 12h14M12 5l7 7-7 7"/>',
        'arrowUp' => '<path d="m5 12 7-7 7 7M12 19V5"/>',
        'arrowDown' => '<path d="M12 5v14M19 12l-7 7-7-7"/>',
        'database' => '<ellipse cx="12" cy="5" rx="9" ry="3"/><path d="M3 5v14a9 3 0 0 0 18 0V5M3 12a9 3 0 0 0 18 0"/>',
        'lock' => '<rect width="18" height="11" x="3" y="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/>',
        'tag' => '<path d="M12.59 2.59A2 2 0 0 0 11.17 2H4a2 2 0 0 0-2 2v7.17a2 2 0 0 0 .59 1.41l8.7 8.7a2.43 2.43 0 0 0 3.42 0l6.58-6.58a2.43 2.43 0 0 0 0-3.42z"/><circle cx="7.5" cy="7.5" r=".5"/>',
        'filterOff' => '<path d="M13.01 4H2l8 9.46V19l4 2v-8.54"/><path d="m22 3-5 5M17 3l5 5"/>',
    ];
    $d = $paths[$name] ?? $paths['box'];
    return '<svg class="' . e($class) . '" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' . $d . '</svg>';
}

/** <head> shared by setup, login and the dashboard. */
function renderHead(string $title): void { ?>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<meta name="color-scheme" content="light dark">
<title><?= e($title) ?></title>
<script>
  // Apply the saved/system theme before paint to avoid a flash.
  (function () {
    var t = null;
    try { t = localStorage.getItem('bt-theme'); } catch (e) {}
    if (!t) t = window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
    document.documentElement.setAttribute('data-theme', t);
  })();
</script>
<script src="https://cdn.tailwindcss.com/3.4.16"></script>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&display=swap" rel="stylesheet">
<style>
  /* ---------- Design tokens (WCAG AA for every text/background pair) ---------- */
  :root, [data-theme="light"] {
    --bg: #f4f4f5; --surface: #ffffff; --surface-2: #f4f4f5; --surface-3: #e4e4e7;
    --border: #e4e4e7; --border-strong: #d4d4d8;
    --text: #09090b; --muted: #52525b; --subtle: #71717a;
    --primary: #4f46e5; --primary-hover: #4338ca; --on-primary: #ffffff; --primary-soft: #eef2ff; --on-primary-soft: #312e81;
    --success: #15803d; --success-soft: #dcfce7; --on-success-soft: #14532d;
    --warning: #b45309; --warning-soft: #fef3c7; --on-warning-soft: #78350f;
    --danger: #dc2626; --danger-hover: #b91c1c; --danger-soft: #fee2e2; --on-danger-soft: #7f1d1d;
    --info: #1d4ed8; --info-soft: #dbeafe; --on-info-soft: #1e3a8a;
    --ring: rgba(79, 70, 229, .35); --bar: #818cf8;
    --shadow: 0 1px 2px rgba(0,0,0,.04), 0 4px 16px rgba(0,0,0,.05);
    --shadow-lg: 0 10px 40px rgba(0,0,0,.14);
  }
  [data-theme="dark"] {
    --bg: #09090b; --surface: #18181b; --surface-2: #27272a; --surface-3: #3f3f46;
    --border: #27272a; --border-strong: #3f3f46;
    --text: #fafafa; --muted: #a1a1aa; --subtle: #a1a1aa;
    --primary: #818cf8; --primary-hover: #a5b4fc; --on-primary: #09090b; --primary-soft: #1e1b4b; --on-primary-soft: #e0e7ff;
    --success: #4ade80; --success-soft: #0b2e1a; --on-success-soft: #bbf7d0;
    --warning: #fbbf24; --warning-soft: #3a2306; --on-warning-soft: #fde68a;
    --danger: #f87171; --danger-hover: #fca5a5; --danger-soft: #3b1212; --on-danger-soft: #fecaca;
    --info: #60a5fa; --info-soft: #172554; --on-info-soft: #bfdbfe;
    --ring: rgba(129, 140, 248, .45); --bar: #6366f1;
    --shadow: 0 0 0 1px var(--border);
    --shadow-lg: 0 0 0 1px var(--border-strong), 0 20px 50px rgba(0,0,0,.6);
  }
  html { background: var(--bg); }
  body { font-family: 'Inter', system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif; background: var(--bg); color: var(--text);
         -webkit-font-smoothing: antialiased; font-feature-settings: 'cv11', 'ss01'; }
  .tnum { font-variant-numeric: tabular-nums; }
  .ic { width: 20px; height: 20px; flex-shrink: 0; }
  .ic-sm { width: 16px; height: 16px; flex-shrink: 0; }
  .ic-lg { width: 24px; height: 24px; flex-shrink: 0; }
  .muted { color: var(--muted); }
  .subtle { color: var(--subtle); }
  :focus-visible { outline: 2px solid var(--primary); outline-offset: 2px; border-radius: 8px; }

  /* ---------- Surfaces ---------- */
  .card { background: var(--surface); border-radius: 20px; box-shadow: var(--shadow); }
  .card-pad { padding: 20px; }
  @media (min-width: 1024px) { .card-pad { padding: 24px; } }
  .divider { border-top: 1px solid var(--border); }
  .soft { background: var(--surface-2); border-radius: 14px; }

  /* ---------- Typography ---------- */
  .page-title { font-size: 28px; line-height: 1.15; font-weight: 800; letter-spacing: -.025em; }
  @media (min-width: 1024px) { .page-title { font-size: 32px; } }
  .section-title { font-size: 17px; font-weight: 700; letter-spacing: -.01em; }
  .label { display: block; font-size: 13px; font-weight: 600; margin: 0 0 6px 2px; color: var(--text); }
  .hint { font-size: 12px; color: var(--muted); margin: 6px 0 0 2px; }
  .caption { font-size: 12px; font-weight: 600; letter-spacing: .04em; text-transform: uppercase; color: var(--muted); }

  /* ---------- Buttons ---------- */
  .btn { display: inline-flex; align-items: center; justify-content: center; gap: 8px; height: 44px; padding: 0 18px;
         border-radius: 14px; font-weight: 600; font-size: 14px; transition: background .15s, transform .08s, opacity .15s;
         white-space: nowrap; cursor: pointer; user-select: none; border: 0; }
  .btn:active { transform: scale(.97); }
  .btn:disabled { opacity: .45; cursor: not-allowed; transform: none; }
  .btn-sm { height: 36px; padding: 0 12px; border-radius: 12px; font-size: 13px; }
  .btn-lg { height: 52px; padding: 0 22px; border-radius: 16px; font-size: 15px; }
  .btn-primary { background: var(--primary); color: var(--on-primary); }
  .btn-primary:hover:not(:disabled) { background: var(--primary-hover); }
  .btn-secondary { background: var(--surface-2); color: var(--text); box-shadow: inset 0 0 0 1px var(--border-strong); }
  .btn-secondary:hover:not(:disabled) { background: var(--surface-3); }
  .btn-danger { background: var(--danger); color: #fff; }
  [data-theme="dark"] .btn-danger { color: #450a0a; }
  .btn-danger:hover:not(:disabled) { background: var(--danger-hover); }
  .btn-success { background: var(--success); color: #fff; }
  [data-theme="dark"] .btn-success { color: #052e16; }
  .btn-ghost { background: transparent; color: var(--muted); }
  .btn-ghost:hover { background: var(--surface-2); color: var(--text); }
  .icon-btn { width: 36px; height: 36px; padding: 0; border-radius: 999px; display: inline-flex; align-items: center; justify-content: center;
              background: var(--surface-2); color: var(--text); transition: background .15s, transform .08s; flex-shrink: 0; }
  .icon-btn:hover { background: var(--surface-3); }
  .icon-btn:active { transform: scale(.92); }
  .icon-btn.tone-danger { background: var(--danger-soft); color: var(--on-danger-soft); }
  .icon-btn.tone-success { background: var(--success-soft); color: var(--on-success-soft); }
  .icon-btn.tone-primary { background: var(--primary-soft); color: var(--on-primary-soft); }
  .kbd { font-size: 11px; font-weight: 600; padding: 1px 6px; border-radius: 6px; border: 1px solid var(--border-strong); color: var(--muted); }

  /* ---------- Inputs ---------- */
  .input { width: 100%; height: 46px; padding: 0 14px; border-radius: 14px; background: var(--surface-2); color: var(--text);
           border: 1.5px solid transparent; font-size: 15px; transition: border-color .15s, background .15s, box-shadow .15s; }
  textarea.input { height: auto; padding: 12px 14px; min-height: 84px; }
  .input::placeholder { color: var(--subtle); }
  .input:focus { outline: none; background: var(--surface); border-color: var(--primary); box-shadow: 0 0 0 4px var(--ring); }
  select.input { appearance: none; background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='16' height='16' viewBox='0 0 24 24' fill='none' stroke='%2371717a' stroke-width='2'%3E%3Cpath d='m6 9 6 6 6-6'/%3E%3C/svg%3E");
                 background-repeat: no-repeat; background-position: right 14px center; padding-right: 40px; }
  .input-icon { position: relative; }
  .input-icon > svg { position: absolute; left: 14px; top: 50%; transform: translateY(-50%); color: var(--subtle); pointer-events: none; }
  .input-icon > .input { padding-left: 42px; }
  .search .input { background: var(--surface); box-shadow: var(--shadow); }
  .input-lg { height: 56px; font-size: 22px; font-weight: 700; }

  /* ---------- Segmented control & chips ---------- */
  .seg { display: inline-flex; padding: 3px; gap: 3px; border-radius: 14px; background: var(--surface-3); }
  [data-theme="dark"] .seg { background: var(--surface); box-shadow: 0 0 0 1px var(--border); }
  .seg > button, .seg > label { height: 34px; padding: 0 14px; border-radius: 11px; font-size: 13px; font-weight: 500; color: var(--muted);
                 display: inline-flex; align-items: center; justify-content: center; gap: 6px; cursor: pointer; transition: background .15s, color .15s; flex: 1; white-space: nowrap; }
  .seg > button[aria-pressed="true"], .seg > label:has(input:checked) { background: var(--surface); color: var(--text); font-weight: 600; box-shadow: 0 1px 3px rgba(0,0,0,.1); }
  [data-theme="dark"] .seg > button[aria-pressed="true"], [data-theme="dark"] .seg > label:has(input:checked) { background: var(--surface-2); }
  .seg input { position: absolute; opacity: 0; pointer-events: none; }
  .chip { height: 36px; padding: 0 14px; border-radius: 999px; font-size: 13px; font-weight: 600; display: inline-flex; align-items: center; gap: 6px;
          background: var(--surface); color: var(--text); border: 1px solid var(--border); white-space: nowrap; cursor: pointer; transition: all .15s; }
  .chip:hover { border-color: var(--border-strong); }
  .chip[aria-pressed="true"] { background: var(--text); color: var(--surface); border-color: var(--text); }
  .chip .count { font-weight: 500; opacity: .7; }
  .scroll-x { overflow-x: auto; scrollbar-width: none; }
  .scroll-x::-webkit-scrollbar { display: none; }

  /* ---------- Badges, avatars, tiles ---------- */
  .badge { display: inline-flex; align-items: center; gap: 4px; height: 24px; padding: 0 10px; border-radius: 999px; font-size: 12px; font-weight: 600; white-space: nowrap; }
  .tone-success.badge, .bg-tone-success { background: var(--success-soft); color: var(--on-success-soft); }
  .tone-warning.badge, .bg-tone-warning { background: var(--warning-soft); color: var(--on-warning-soft); }
  .tone-danger.badge, .bg-tone-danger { background: var(--danger-soft); color: var(--on-danger-soft); }
  .tone-info.badge, .bg-tone-info { background: var(--info-soft); color: var(--on-info-soft); }
  .tone-primary.badge, .bg-tone-primary { background: var(--primary-soft); color: var(--on-primary-soft); }
  .tone-neutral.badge, .bg-tone-neutral { background: var(--surface-2); color: var(--muted); }
  .text-success { color: var(--success); } .text-danger { color: var(--danger); } .text-warning { color: var(--warning); } .text-primary { color: var(--primary); }
  .avatar { width: 40px; height: 40px; border-radius: 999px; display: inline-flex; align-items: center; justify-content: center; font-weight: 700; font-size: 14px; flex-shrink: 0; }
  .tile { width: 40px; height: 40px; border-radius: 12px; display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0; }
  /* Product image with ring (matches the app): accent colour at partial opacity, small gap. */
  .thumb { display: inline-flex; flex-shrink: 0; padding: 2px; border-radius: 14px; border: 2px solid color-mix(in srgb, var(--tc) 45%, transparent); }
  .thumb-in { position: relative; flex: 1; border-radius: 9px; overflow: hidden; display: flex; align-items: center; justify-content: center; font-weight: 700; }
  .thumb-in img { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: cover; }
  .ring-primary { --tc: var(--primary); } .ring-success { --tc: var(--success); } .ring-warning { --tc: var(--warning); }
  .ring-danger { --tc: var(--danger); } .ring-info { --tc: var(--info); } .ring-neutral { --tc: var(--muted); }

  /* ---------- Tables ---------- */
  .table { width: 100%; font-size: 14px; border-collapse: separate; border-spacing: 0; }
  .table th { text-align: left; font-size: 12px; font-weight: 600; color: var(--muted); padding: 12px 16px; border-bottom: 1px solid var(--border); white-space: nowrap; }
  .table td { padding: 14px 16px; border-bottom: 1px solid var(--border); vertical-align: middle; }
  .table tr:last-child td { border-bottom: 0; }
  .table tbody tr { transition: background .1s; }
  .table tbody tr:hover { background: var(--surface-2); }
  .table tbody tr.clickable { cursor: pointer; }
  .num, .table th.num { text-align: right; font-variant-numeric: tabular-nums; white-space: nowrap; }
  .table th.text-right { text-align: right; }
  /* Phones: rows reflow into stacked cards so key figures and actions never scroll off-screen. */
  @media (max-width: 767px) {
    .table .opt, .table thead { display: none; }
    .table tbody tr:not(.no-match):not(.empty-row):not(.hidden) { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 12px; padding: 14px 16px; border-bottom: 1px solid var(--border); }
    .table tbody tr:last-child { border-bottom: 0; }
    .table tbody td { padding: 0; border: 0; }
    .table tbody td:first-child { flex: 1 1 55%; min-width: 0; }
    .table tbody td.num { margin-left: auto; }
    .table tbody td.actions { flex-basis: 100%; }
  }

  /* ---------- Navigation ---------- */
  .sidebar { background: var(--surface); border-right: 1px solid var(--border); }
  .nav-group { font-size: 11px; font-weight: 600; letter-spacing: .06em; text-transform: uppercase; color: var(--subtle); padding: 18px 12px 6px; }
  .nav-link { display: flex; align-items: center; gap: 12px; height: 42px; padding: 0 12px; border-radius: 12px; font-size: 14px; font-weight: 500; color: var(--muted); transition: background .12s, color .12s; }
  .nav-link:hover { background: var(--surface-2); color: var(--text); }
  .nav-link[aria-current="page"] { background: var(--primary-soft); color: var(--on-primary-soft); font-weight: 600; }
  .nav-link .pill { margin-left: auto; font-size: 11px; font-weight: 700; min-width: 22px; height: 20px; padding: 0 6px; border-radius: 999px; display: inline-flex; align-items: center; justify-content: center; }

  /* ---------- Overlays ---------- */
  .modal { position: fixed; inset: 0; z-index: 60; display: none; align-items: flex-end; justify-content: center; padding: 0; background: rgba(9, 9, 11, .55); backdrop-filter: blur(4px); }
  .modal.open { display: flex; animation: fade .15s ease-out; }
  .modal-panel { background: var(--surface); width: 100%; max-height: 92vh; overflow-y: auto; border-radius: 24px 24px 0 0; box-shadow: var(--shadow-lg); animation: rise .2s cubic-bezier(.2,.9,.3,1.1); }
  @media (min-width: 640px) { .modal { align-items: center; padding: 24px; } .modal-panel { border-radius: 24px; max-width: 520px; } }
  .modal-panel.wide { max-width: 640px; }
  @keyframes fade { from { opacity: 0; } to { opacity: 1; } }
  @keyframes rise { from { transform: translateY(24px); opacity: .6; } to { transform: none; opacity: 1; } }
  .toast { position: fixed; top: 20px; right: 20px; left: 20px; z-index: 80; display: flex; justify-content: flex-end; pointer-events: none; }
  .toast > div { pointer-events: auto; max-width: 420px; animation: rise .25s ease-out; }

  /* ---------- Chart ---------- */
  .bars { display: flex; align-items: flex-end; gap: 6px; height: 160px; }
  .bar { width: 100%; border-radius: 8px 8px 4px 4px; background: var(--bar); min-height: 4px; position: relative; transition: background .15s; }
  .bar.today { background: var(--primary); }
  .bar:hover { background: var(--primary); }
  .kpi { font-size: clamp(18px, calc(1.9vw + 8px), 30px); line-height: 1.15; font-weight: 800; letter-spacing: -.02em; white-space: nowrap; font-variant-numeric: tabular-nums; margin-top: 12px; }
  .bar-wrap { flex: 1; display: flex; flex-direction: column; align-items: stretch; justify-content: flex-end; height: 100%; }

  /* ---------- POS ---------- */
  .product-tile { text-align: left; padding: 14px; border-radius: 18px; background: var(--surface); box-shadow: var(--shadow); transition: transform .08s, box-shadow .15s; position: relative; }
  .product-tile:hover { box-shadow: 0 0 0 2px var(--primary); }
  .product-tile:active { transform: scale(.97); }
  .product-tile.in-cart { box-shadow: 0 0 0 2px var(--primary); }
  .product-tile[disabled] { opacity: .5; }
  .stepper { display: inline-flex; align-items: center; background: var(--surface-2); border-radius: 12px; padding: 3px; }
  .stepper button { width: 30px; height: 30px; border-radius: 9px; display: inline-flex; align-items: center; justify-content: center; }
  .stepper button:hover { background: var(--surface); }
  .stepper input { width: 46px; text-align: center; background: transparent; font-weight: 600; font-variant-numeric: tabular-nums; border: 0; }
  .stepper input:focus { outline: none; }

  @media print {
    body * { visibility: hidden; }
    #receiptModal, #receiptModal * { visibility: visible; }
    #receiptModal { position: absolute; inset: 0; background: #fff; display: block !important; backdrop-filter: none; }
    #receiptModal .modal-panel { box-shadow: none; max-height: none; border-radius: 0; color: #000; background: #fff; }
    .no-print { display: none !important; }
  }
</style>
<?php }

// ----------------------------------------------------
// 1. DYNAMIC CONFIGURATION (Auto-detected from sync.php / config.php)
// ----------------------------------------------------
$configFile = __DIR__ . '/config.php';
$syncFile   = __DIR__ . '/sync.php';

$DB_HOST = 'localhost';
$DB_NAME = '';
$DB_USER = '';
$DB_PASS = '';
$SECRET_KEY = '';

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
        $SECRET_KEY = '';
    }
}

// A missing, default or short secret means the server is not safely configured.
$secretIsWeak = strlen((string)$SECRET_KEY) < 24 || strpos((string)$SECRET_KEY, 'CHANGE_ME') !== false;

// Handle Configuration Save from Web UI.
// Only allowed during first-time setup (no config.php yet) or by a logged-in admin;
// otherwise any visitor could point the dashboard at their own database and key.
if ($_SERVER['REQUEST_METHOD'] === 'POST' && isset($_POST['save_db_config'])) {
    if (file_exists($configFile) && empty($_SESSION['naadriel_auth'])) {
        http_response_code(403);
        exit('Configuration is locked. Delete config.php on the server to run setup again.');
    }
    $newHost = trim($_POST['db_host'] ?? 'localhost');
    $newName = trim($_POST['db_name'] ?? '');
    $newUser = trim($_POST['db_user'] ?? '');
    $newPass = trim($_POST['db_pass'] ?? '');
    $newKey  = trim($_POST['secret_key'] ?? '');
    if (strlen($newKey) < 24 || strpos($newKey, 'CHANGE_ME') !== false) {
        header("Location: index.php?err=" . urlencode("Secret key must be at least 24 characters."));
        exit;
    }

    $configContent = "<?php\n"
        . "\$DB_HOST = " . var_export($newHost, true) . ";\n"
        . "\$DB_NAME = " . var_export($newName, true) . ";\n"
        . "\$DB_USER = " . var_export($newUser, true) . ";\n"
        . "\$DB_PASS = " . var_export($newPass, true) . ";\n"
        . "\$SECRET_KEY = " . var_export($newKey, true) . ";\n"
        . "\$API_SECRET_KEY = " . var_export($newKey, true) . ";\n";

    @file_put_contents($configFile, $configContent, LOCK_EX);
    @chmod($configFile, 0600);
    header("Location: index.php?msg=" . urlencode("Database connected. Sign in with your secret key."));
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
        $addColumnIfMissing($db, 'products', 'unit', "VARCHAR(32) NOT NULL DEFAULT 'Piece'");
        $addColumnIfMissing($db, 'products', 'lastRestockedAt', "BIGINT NOT NULL DEFAULT 0");
        $addColumnIfMissing($db, 'products', 'description', "TEXT NULL");
        $addColumnIfMissing($db, 'products', 'imageUrl', "VARCHAR(512) NOT NULL DEFAULT ''");
        $addColumnIfMissing($db, 'products', 'imageUri', "VARCHAR(512) NOT NULL DEFAULT ''"); // read when recording a sale

        // Guarantee sales order columns
        $addColumnIfMissing($db, 'sales_orders', 'balanceDue', "DECIMAL(12,2) NOT NULL DEFAULT 0.00");
        $addColumnIfMissing($db, 'sales_orders', 'debtAmount', "DECIMAL(12,2) NOT NULL DEFAULT 0.00");
        $addColumnIfMissing($db, 'sales_orders', 'discountAmount', "DECIMAL(12,2) NOT NULL DEFAULT 0.00");
        $addColumnIfMissing($db, 'sales_orders', 'paymentStatus', "VARCHAR(32) NOT NULL DEFAULT 'PAID'");
        $addColumnIfMissing($db, 'sales_orders', 'paymentMethod', "VARCHAR(32) NOT NULL DEFAULT 'CASH'");
        $addColumnIfMissing($db, 'sales_orders', 'customerWhatsapp', "VARCHAR(64) NOT NULL DEFAULT ''");
        // Tables created by older versions lack these; without them the dashboard logged
        // "Undefined array key invoiceNumber" and recording a web sale crashed.
        $addColumnIfMissing($db, 'sales_orders', 'invoiceNumber', "VARCHAR(100) NOT NULL DEFAULT ''");
        $addColumnIfMissing($db, 'sales_orders', 'customerId', "BIGINT UNSIGNED NULL");
        $addColumnIfMissing($db, 'sales_orders', 'customerName', "VARCHAR(255) NOT NULL DEFAULT ''");
        $addColumnIfMissing($db, 'sales_orders', 'customerPhone', "VARCHAR(64) NOT NULL DEFAULT ''");
        $addColumnIfMissing($db, 'sales_orders', 'itemsJson', "LONGTEXT NULL");
        $addColumnIfMissing($db, 'sales_orders', 'totalAmount', "DECIMAL(12,2) NOT NULL DEFAULT 0.00");
        $addColumnIfMissing($db, 'sales_orders', 'amountPaid', "DECIMAL(12,2) NOT NULL DEFAULT 0.00");
        $addColumnIfMissing($db, 'sales_orders', 'notes', "TEXT NULL");
        $addColumnIfMissing($db, 'sales_orders', 'timestamp', "BIGINT NOT NULL DEFAULT 0");

        // Auto-reconcile any legacy rows
        try {
            $db->exec("UPDATE customers SET currentBalance = totalDebt WHERE (currentBalance IS NULL OR currentBalance = 0) AND totalDebt > 0");
            $db->exec("UPDATE customers SET totalDebt = currentBalance WHERE (totalDebt IS NULL OR totalDebt = 0) AND currentBalance > 0");
            $db->exec("UPDATE products SET unitPrice = price WHERE (unitPrice IS NULL OR unitPrice = 0) AND price > 0");
            $db->exec("UPDATE products SET price = unitPrice WHERE (price IS NULL OR price = 0) AND unitPrice > 0");
            $db->exec("UPDATE sales_orders SET invoiceNumber = CONCAT('INV-', id) WHERE invoiceNumber IS NULL OR invoiceNumber = ''");
            $db->exec("UPDATE sales_orders SET balanceDue = debtAmount WHERE (balanceDue IS NULL OR balanceDue = 0) AND debtAmount > 0");
            $db->exec("UPDATE customers SET totalPurchases = totalPurchased WHERE (totalPurchases IS NULL OR totalPurchases = 0) AND totalPurchased > 0");
        } catch (Exception $reconcileEx) {
            // Ignore
        }

    } catch (PDOException $e) {
        error_log("BizTrack dashboard DB error: " . $e->getMessage());
        $dbError = "Could not connect to the database. Check the host, name, user and password.";
    }
} else {
    $dbError = null; // first run: nothing configured yet, not an error
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

// ----------------------------------------------------
// 3. SHOW SETUP SCREEN IF DATABASE NOT CONNECTED
// ----------------------------------------------------
if (!$db) {
    $setupErr = $_GET['err'] ?? '';
    ?>
<!DOCTYPE html>
<html lang="en">
<head><?php renderHead('Set up · BizTrack'); ?></head>
<body class="min-h-screen flex items-center justify-center p-4">
    <main class="w-full max-w-lg">
        <div class="text-center mb-8">
            <div class="tile bg-tone-primary mx-auto mb-4" style="width:56px;height:56px;border-radius:18px"><?= icon('database', 'ic-lg') ?></div>
            <h1 class="page-title">Connect your database</h1>
            <p class="muted mt-2">One-time setup. Use the MySQL details from your hosting control panel.</p>
        </div>

        <div class="card card-pad">
            <?php if (!empty($dbError) || $setupErr !== ''): ?>
                <div class="soft bg-tone-danger p-3 mb-5 flex gap-3 text-sm" role="alert">
                    <?= icon('alert', 'ic-sm mt-0.5') ?>
                    <span><?= e($setupErr !== '' ? $setupErr : $dbError) ?></span>
                </div>
            <?php endif; ?>

            <form method="POST" action="index.php" class="space-y-4">
                <?= csrfField() ?>
                <input type="hidden" name="save_db_config" value="1">
                <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
                    <div>
                        <label class="label" for="db_host">Host</label>
                        <input class="input" type="text" id="db_host" name="db_host" value="<?= e($DB_HOST ?: 'localhost') ?>" required>
                    </div>
                    <div>
                        <label class="label" for="db_name">Database name</label>
                        <input class="input" type="text" id="db_name" name="db_name" value="<?= e($DB_NAME) ?>" required placeholder="cpaneluser_biztrack">
                    </div>
                    <div>
                        <label class="label" for="db_user">Database user</label>
                        <input class="input" type="text" id="db_user" name="db_user" value="<?= e($DB_USER) ?>" required placeholder="cpaneluser_admin" autocomplete="username">
                    </div>
                    <div>
                        <label class="label" for="db_pass">Password</label>
                        <input class="input" type="password" id="db_pass" name="db_pass" value="" autocomplete="current-password">
                    </div>
                </div>
                <div>
                    <label class="label" for="secret_key">Secret key</label>
                    <input class="input" type="text" id="secret_key" name="secret_key" value="" minlength="24" required placeholder="At least 24 random characters" autocomplete="off">
                    <p class="hint">Use the same key in the app (Settings → Backup → Cloud sync). You will also sign in with it.</p>
                </div>
                <button type="submit" class="btn btn-primary btn-lg w-full">Connect &amp; continue <?= icon('arrowRight', 'ic-sm') ?></button>
            </form>
        </div>
        <p class="text-center text-xs subtle mt-6 flex items-center justify-center gap-1.5"><?= icon('lock', 'ic-sm') ?> Details are stored in config.php on your server only.</p>
    </main>
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
    $lockedUntil = (int)($_SESSION['login_locked_until'] ?? 0);
    if ($lockedUntil > time()) {
        $loginError = "Too many failed attempts. Try again in " . ($lockedUntil - time()) . " seconds.";
    } elseif ($secretIsWeak) {
        $loginError = "The server's secret key is missing or too short. Set a key of 24+ characters in config.php.";
    } elseif (hash_equals((string)$SECRET_KEY, $enteredKey)) {
        session_regenerate_id(true);
        $_SESSION['csrf'] = bin2hex(random_bytes(32));
        $_SESSION['naadriel_auth'] = true;
        unset($_SESSION['login_fails'], $_SESSION['login_locked_until']);
        header("Location: index.php");
        exit;
    } else {
        $_SESSION['login_fails'] = (int)($_SESSION['login_fails'] ?? 0) + 1;
        if ($_SESSION['login_fails'] >= 5) {
            $_SESSION['login_locked_until'] = time() + 300;
            $_SESSION['login_fails'] = 0;
        }
        sleep(1);
        $loginError = "That key didn't match. Copy it from the app: Settings → Backup → Cloud sync.";
    }
}

$isLoggedIn = !empty($_SESSION['naadriel_auth']);

if (!$isLoggedIn) {
    $bizName = 'BizTrack';
    try {
        $bizName = (string)($db->query("SELECT businessName FROM app_settings WHERE id = 1")->fetchColumn() ?: 'BizTrack');
    } catch (Exception $ex) { /* settings table may be empty */ }
    $loginMsg = $_GET['msg'] ?? '';
    ?>
<!DOCTYPE html>
<html lang="en">
<head><?php renderHead('Sign in · ' . $bizName); ?></head>
<body class="min-h-screen flex items-center justify-center p-4">
    <main class="w-full max-w-sm">
        <div class="text-center mb-8">
            <div class="avatar bg-tone-<?= avatarTone($bizName) ?> mx-auto mb-4" style="width:64px;height:64px;font-size:22px"><?= e(initials($bizName)) ?></div>
            <h1 class="page-title"><?= e($bizName) ?></h1>
            <p class="muted mt-2">Sign in to your business dashboard</p>
        </div>

        <div class="card card-pad">
            <?php if ($loginMsg !== ''): ?>
                <div class="soft bg-tone-success p-3 mb-5 flex gap-3 text-sm" role="status"><?= icon('checkCircle', 'ic-sm mt-0.5') ?><span><?= e($loginMsg) ?></span></div>
            <?php endif; ?>
            <?php if ($loginError !== ''): ?>
                <div class="soft bg-tone-danger p-3 mb-5 flex gap-3 text-sm" role="alert"><?= icon('alert', 'ic-sm mt-0.5') ?><span><?= e($loginError) ?></span></div>
            <?php endif; ?>
            <form method="POST" action="index.php" class="space-y-4">
                <?= csrfField() ?>
                <input type="hidden" name="auth_action" value="login">
                <div>
                    <label class="label" for="login_key">Secret key</label>
                    <div class="input-icon">
                        <?= icon('lock', 'ic-sm') ?>
                        <input class="input" type="password" id="login_key" name="secret_key" required autofocus autocomplete="current-password" placeholder="Paste your secret key">
                    </div>
                </div>
                <button type="submit" class="btn btn-primary btn-lg w-full">Sign in</button>
            </form>
        </div>
        <p class="text-center text-xs subtle mt-6 flex items-center justify-center gap-1.5">
            <span class="inline-block w-2 h-2 rounded-full" style="background:var(--success)"></span>
            Connected to <?= e($DB_NAME) ?>
        </p>
    </main>
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
    'businessName' => 'My Business',
    'businessTagline' => 'Quality products & reliable service',
    'businessPhone' => '',
    'businessLocation' => '',
    'momoPaymentDetails' => '',
    'currencySymbol' => 'GH₵'
];
$currency = $settings['currencySymbol'] ?: 'GH₵';

// Built-in key, an existing category, or a new name the user typed (same rules as the app).
$validCategory = function (string $c) use ($CATEGORIES, $LEGACY_CATEGORIES): string {
    $c = trim(preg_replace('/\s+/u', ' ', strip_tags($c)));
    $c = function_exists('mb_substr') ? mb_substr($c, 0, 40) : substr($c, 0, 40);
    if ($c === '') return 'GENERAL';
    if (isset($CATEGORIES[$c]) || isset($LEGACY_CATEGORIES[$c])) return $c;
    foreach ($CATEGORIES as $k => $label) {
        if (strcasecmp($label, $c) === 0 || strcasecmp($k, $c) === 0) return $k;
    }
    return $c;
};
$validMethod = function (string $m) use ($PAYMENT_METHODS): string {
    return isset($PAYMENT_METHODS[$m]) ? $m : 'CASH';
};
$redirect = function (string $tab, string $message, bool $isError = false, array $extra = []) {
    $q = array_merge(['tab' => $tab, $isError ? 'err' : 'msg' => $message], $extra);
    header('Location: index.php?' . http_build_query($q));
    exit;
};

// Handle POST Form Actions
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $action = $_POST['action'] ?? '';

    // A. PRODUCT CRUD
    if ($action === 'create_product' || $action === 'update_product') {
        $id = (int)($_POST['id'] ?? 0);
        $name = trim($_POST['name'] ?? '');
        $choice = (string)($_POST['category'] ?? 'GENERAL');
        if ($choice === '__new__') $choice = (string)($_POST['category_new'] ?? '');
        $category = $validCategory($choice);
        // Typed an existing custom category with different capitals? Reuse it instead of duplicating.
        if (!isset($CATEGORIES[$category]) && !isset($LEGACY_CATEGORIES[$category])) {
            foreach ($db->query("SELECT DISTINCT category FROM products")->fetchAll(PDO::FETCH_COLUMN) as $existing) {
                if (strcasecmp((string)$existing, $category) === 0) { $category = (string)$existing; break; }
            }
        }
        $unitPrice = max(0.0, (float)($_POST['unitPrice'] ?? 0.0));
        $costPrice = max(0.0, (float)($_POST['costPrice'] ?? 0.0));
        $stockQuantity = max(0.0, (float)($_POST['stockQuantity'] ?? 0.0));
        $unit = trim($_POST['unit'] ?? '') ?: 'Piece';
        $minThreshold = max(0.0, (float)($_POST['minStockThreshold'] ?? 5.0));
        $description = trim($_POST['description'] ?? '');

        if ($name === '') $redirect('products', 'Give the product a name.', true);

        // Photo: a new upload replaces it, "remove" clears it, otherwise it is left as-is (null).
        $photoUrl = null;
        if (($_FILES['photo']['error'] ?? UPLOAD_ERR_NO_FILE) !== UPLOAD_ERR_NO_FILE) {
            [$photoUrl, $photoErr] = bt_store_product_image($_FILES['photo']);
            if ($photoErr !== null) $redirect('products', $photoErr, true);
        } elseif (!empty($_POST['remove_photo'])) {
            $photoUrl = '';
        }

        if ($action === 'create_product') {
            $now = round(microtime(true) * 1000);
            $stmt = $db->prepare("INSERT INTO products (id, name, category, price, unitPrice, costPrice, stockQuantity, unit, minStockThreshold, description, imageUrl, lastRestockedAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
            $stmt->execute([bt_next_id($db, 'products'), $name, $category, $unitPrice, $unitPrice, $costPrice, $stockQuantity, $unit, $minThreshold, $description, $photoUrl ?? '', $now]);
            $prodId = bt_last_id('products');

            if ($stockQuantity > 0) {
                $logStmt = $db->prepare("INSERT INTO inventory_logs (id, productId, productName, changeType, quantityChanged, quantityAfter, unit, notes, timestamp) VALUES (?, ?, ?, 'RESTOCK', ?, ?, ?, 'Opening stock (web)', ?)");
                $logStmt->execute([bt_next_id($db, 'inventory_logs'), $prodId, $name, $stockQuantity, $stockQuantity, $unit, $now]);
            }
            $redirect('products', "Added \"$name\".");
        }

        $stmt = $db->prepare("UPDATE products SET name = ?, category = ?, price = ?, unitPrice = ?, costPrice = ?, stockQuantity = ?, unit = ?, minStockThreshold = ?, description = ? WHERE id = ?");
        $stmt->execute([$name, $category, $unitPrice, $unitPrice, $costPrice, $stockQuantity, $unit, $minThreshold, $description, $id]);
        if ($photoUrl !== null) {
            $old = $db->prepare("SELECT imageUrl FROM products WHERE id = ?");
            $old->execute([$id]);
            $oldUrl = (string)($old->fetchColumn() ?: '');
            $db->prepare("UPDATE products SET imageUrl = ? WHERE id = ?")->execute([$photoUrl, $id]);
            if ($oldUrl !== $photoUrl) bt_forget_product_image($db, $oldUrl);
        }
        $redirect('products', "Saved changes to \"$name\".");
    }

    if ($action === 'restock_product') {
        $id = (int)$_POST['id'];
        $qty = (float)($_POST['add_quantity'] ?? 0);
        $direction = ($_POST['direction'] ?? 'add') === 'remove' ? -1 : 1;
        $notes = trim($_POST['notes'] ?? '');

        $pStmt = $db->prepare("SELECT name, stockQuantity, unit FROM products WHERE id = ?");
        $pStmt->execute([$id]);
        $prod = $pStmt->fetch();

        if ($prod && $qty > 0) {
            $change = $direction * $qty;
            $newQty = max(0.0, (float)$prod['stockQuantity'] + $change);
            $now = round(microtime(true) * 1000);
            $type = $direction > 0 ? 'RESTOCK' : 'CORRECTION';
            if ($notes === '') $notes = $direction > 0 ? 'Restocked (web)' : 'Stock removed (web)';

            if ($direction > 0) {
                $db->prepare("UPDATE products SET stockQuantity = ?, lastRestockedAt = ? WHERE id = ?")->execute([$newQty, $now, $id]);
            } else {
                $db->prepare("UPDATE products SET stockQuantity = ? WHERE id = ?")->execute([$newQty, $id]);
            }
            $db->prepare("INSERT INTO inventory_logs (id, productId, productName, changeType, quantityChanged, quantityAfter, unit, notes, timestamp) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")
               ->execute([bt_next_id($db, 'inventory_logs'), $id, $prod['name'], $type, $change, $newQty, $prod['unit'], $notes, $now]);

            $verb = $direction > 0 ? 'Added' : 'Removed';
            $redirect('products', "$verb " . fmtQty($qty) . " {$prod['unit']} · {$prod['name']} now has " . fmtQty($newQty) . ".");
        }
        $redirect('products', 'Enter a quantity greater than zero.', true);
    }

    if ($action === 'delete_product') {
        $id = (int)$_POST['id'];
        $old = $db->prepare("SELECT imageUrl FROM products WHERE id = ?");
        $old->execute([$id]);
        $oldUrl = (string)($old->fetchColumn() ?: '');
        $db->prepare("DELETE FROM products WHERE id = ?")->execute([$id]);
        bt_forget_product_image($db, $oldUrl);
        $redirect('products', 'Product deleted. Past sales keep their records.');
    }

    // B. CUSTOMER CRUD
    if ($action === 'create_customer') {
        $name = trim($_POST['name'] ?? '');
        $phone = trim($_POST['phone'] ?? '');
        $whatsapp = trim($_POST['whatsapp'] ?? '');
        $address = trim($_POST['address'] ?? '');
        $notes = trim($_POST['notes'] ?? '');
        $initialDebt = max(0.0, (float)($_POST['initial_debt'] ?? 0.0));

        if ($name === '') $redirect('customers', 'Give the customer a name.', true);
        $now = round(microtime(true) * 1000);
        $stmt = $db->prepare("INSERT INTO customers (id, name, phone, whatsapp, address, notes, totalDebt, currentBalance, totalPurchases, totalPaid, createdAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
        $stmt->execute([bt_next_id($db, 'customers'), $name, $phone, $whatsapp, $address, $notes, $initialDebt, $initialDebt, $initialDebt, 0.0, $now]);
        $redirect('customers', "Added $name.");
    }

    if ($action === 'update_customer') {
        $id = (int)$_POST['id'];
        $name = trim($_POST['name'] ?? '');
        $phone = trim($_POST['phone'] ?? '');
        $whatsapp = trim($_POST['whatsapp'] ?? '');
        $address = trim($_POST['address'] ?? '');
        $notes = trim($_POST['notes'] ?? '');

        if ($name === '') $redirect('customers', 'Give the customer a name.', true);
        $stmt = $db->prepare("UPDATE customers SET name = ?, phone = ?, whatsapp = ?, address = ?, notes = ? WHERE id = ?");
        $stmt->execute([$name, $phone, $whatsapp, $address, $notes, $id]);
        $redirect('customers', "Saved changes to $name.");
    }

    if ($action === 'delete_customer') {
        $id = (int)$_POST['id'];
        $db->prepare("DELETE FROM customers WHERE id = ?")->execute([$id]);
        $redirect('customers', 'Customer deleted. Their past invoices are kept.');
    }

    // C. SALE / POS TRANSACTION
    if ($action === 'create_sale') {
        $customerId = (int)($_POST['customer_id'] ?? 0);
        $customerName = trim($_POST['customer_name'] ?? '') ?: 'Walk-in customer';
        $customerPhone = trim($_POST['customer_phone'] ?? '');
        $customerWhatsapp = trim($_POST['customer_whatsapp'] ?? '');
        $paymentMethod = $validMethod((string)($_POST['payment_method'] ?? 'CASH'));
        $discountAmount = max(0.0, (float)($_POST['discount_amount'] ?? 0.0));
        $amountPaid = max(0.0, (float)($_POST['amount_paid'] ?? 0.0));
        $notes = trim($_POST['notes'] ?? '');

        if ($customerId > 0) {
            $cStmt = $db->prepare("SELECT * FROM customers WHERE id = ?");
            $cStmt->execute([$customerId]);
            $cust = $cStmt->fetch();
            if ($cust) {
                $customerName = $cust['name'];
                if (empty($customerPhone)) $customerPhone = $cust['phone'];
                if (empty($customerWhatsapp)) $customerWhatsapp = $cust['whatsapp'];
            } else {
                $customerId = 0;
            }
        }

        $itemIds = $_POST['item_product_id'] ?? [];
        $itemQtys = $_POST['item_quantity'] ?? [];
        $itemPrices = $_POST['item_price'] ?? [];

        $cartItems = [];
        $grossTotal = 0.0;
        $now = round(microtime(true) * 1000);

        $db->beginTransaction();
        try {
            for ($i = 0; $i < count($itemIds); $i++) {
                $pId = (int)$itemIds[$i];
                $qty = (float)($itemQtys[$i] ?? 0);
                $price = max(0.0, (float)($itemPrices[$i] ?? 0));

                if ($pId > 0 && $qty > 0) {
                    $pStmt = $db->prepare("SELECT name, category, unit, costPrice, stockQuantity, imageUri FROM products WHERE id = ?");
                    $pStmt->execute([$pId]);
                    $pRow = $pStmt->fetch();

                    if ($pRow) {
                        $lineTotal = $qty * $price;
                        $grossTotal += $lineTotal;
                        // Same shape as the app's CartItem (category is required there).
                        $cartItems[] = [
                            'productId' => $pId,
                            'productName' => $pRow['name'],
                            'category' => (string)($pRow['category'] ?: 'GENERAL'),
                            'unit' => $pRow['unit'],
                            'unitPrice' => $price,
                            'quantity' => $qty,
                            'lineTotal' => $lineTotal,
                            'imageUri' => (string)($pRow['imageUri'] ?? ''),
                            'costPrice' => (float)$pRow['costPrice'],
                        ];

                        $newStock = max(0.0, (float)$pRow['stockQuantity'] - $qty);
                        $db->prepare("UPDATE products SET stockQuantity = ? WHERE id = ?")->execute([$newStock, $pId]);
                        $db->prepare("INSERT INTO inventory_logs (id, productId, productName, changeType, quantityChanged, quantityAfter, unit, notes, timestamp) VALUES (?, ?, ?, 'SALE', ?, ?, ?, 'Sold (web)', ?)")
                           ->execute([bt_next_id($db, 'inventory_logs'), $pId, $pRow['name'], -$qty, $newStock, $pRow['unit'], $now]);
                    }
                }
            }

            if (empty($cartItems)) {
                $db->rollBack();
                $redirect('new_sale', 'Add at least one item to the sale.', true);
            }

            $netTotal = max(0.0, $grossTotal - $discountAmount);
            $amountPaid = min($amountPaid, $netTotal); // change is handed back, not recorded as payment
            $balanceDue = max(0.0, $netTotal - $amountPaid);
            $paymentStatus = ($balanceDue <= 0.001) ? 'PAID' : (($amountPaid > 0.001) ? 'PARTIAL' : 'UNPAID');
            $invoiceNum = 'INV-' . date('ymd') . '-' . random_int(1000, 9999);
            $itemsJson = json_encode($cartItems);

            $soStmt = $db->prepare("INSERT INTO sales_orders (id, invoiceNumber, customerId, customerName, customerPhone, customerWhatsapp, itemsJson, totalAmount, amountPaid, debtAmount, balanceDue, discountAmount, paymentStatus, paymentMethod, notes, timestamp) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
            $soStmt->execute([bt_next_id($db, 'sales_orders'), $invoiceNum, ($customerId > 0 ? $customerId : null), $customerName, $customerPhone, $customerWhatsapp, $itemsJson, $netTotal, $amountPaid, $balanceDue, $balanceDue, $discountAmount, $paymentStatus, $paymentMethod, $notes, $now]);
            $orderId = bt_last_id('sales_orders');

            if ($customerId > 0) {
                $db->prepare("UPDATE customers SET totalPurchases = totalPurchases + ?, totalPaid = totalPaid + ?, totalDebt = totalDebt + ?, currentBalance = currentBalance + ?, lastTransactionDate = ? WHERE id = ?")
                   ->execute([$netTotal, $amountPaid, $balanceDue, $balanceDue, $now, $customerId]);

                if ($amountPaid > 0) {
                    $cBalStmt = $db->prepare("SELECT currentBalance FROM customers WHERE id = ?");
                    $cBalStmt->execute([$customerId]);
                    $balNow = (float)($cBalStmt->fetchColumn() ?: 0.0);

                    $db->prepare("INSERT INTO payments (id, customerId, customerName, saleOrderId, amount, paymentMethod, notes, timestamp, balanceAfterPayment) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")
                       ->execute([bt_next_id($db, 'payments'), $customerId, $customerName, $orderId, $amountPaid, $paymentMethod, "Payment on #$invoiceNum", $now, $balNow]);
                }
            }
            $db->commit();
        } catch (Exception $ex) {
            if ($db->inTransaction()) $db->rollBack();
            error_log('BizTrack sale error: ' . $ex->getMessage());
            $redirect('new_sale', 'The sale could not be saved. Nothing was charged; please try again.', true);
        }

        $redirect('sales', "Sale #$invoiceNum recorded · " . $currency . ' ' . number_format($netTotal, 2), false, ['open' => $orderId]);
    }

    // D. RECORD PAYMENT / DEBT RECOVERY
    if ($action === 'record_payment') {
        $customerId = (int)$_POST['customer_id'];
        $amount = (float)($_POST['amount'] ?? 0.0);
        $method = $validMethod((string)($_POST['payment_method'] ?? 'CASH'));
        $notes = trim($_POST['notes'] ?? '') ?: 'Payment recorded (web)';
        $now = round(microtime(true) * 1000);
        $back = in_array($_POST['return_tab'] ?? '', ['dashboard', 'customers'], true) ? $_POST['return_tab'] : 'customers';

        if ($customerId > 0 && $amount > 0) {
            $cStmt = $db->prepare("SELECT * FROM customers WHERE id = ?");
            $cStmt->execute([$customerId]);
            $cust = $cStmt->fetch();

            if ($cust) {
                $curBal = (float)($cust['currentBalance'] ?: $cust['totalDebt'] ?: 0.0);
                $amount = min($amount, $curBal); // never record more than is owed
                if ($amount <= 0) $redirect($back, "{$cust['name']} doesn't owe anything.", true);
                $newBal = max(0.0, $curBal - $amount);

                $db->beginTransaction();
                $db->prepare("UPDATE customers SET totalPaid = totalPaid + ?, totalDebt = ?, currentBalance = ?, lastTransactionDate = ? WHERE id = ?")
                   ->execute([$amount, $newBal, $newBal, $now, $customerId]);

                $db->prepare("INSERT INTO payments (id, customerId, customerName, saleOrderId, amount, paymentMethod, notes, timestamp, balanceAfterPayment) VALUES (?, ?, ?, NULL, ?, ?, ?, ?, ?)")
                   ->execute([bt_next_id($db, 'payments'), $customerId, $cust['name'], $amount, $method, $notes, $now, $newBal]);

                // Apply the payment to the oldest unpaid invoices first.
                $ordersStmt = $db->prepare("SELECT id, balanceDue, amountPaid FROM sales_orders WHERE customerId = ? AND balanceDue > 0 ORDER BY timestamp ASC");
                $ordersStmt->execute([$customerId]);
                $remainingPayment = $amount;
                foreach ($ordersStmt->fetchAll() as $ord) {
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
                $db->commit();

                $left = $newBal > 0.009 ? ' · still owes ' . $currency . ' ' . number_format($newBal, 2) : ' · fully settled';
                $redirect($back, "Received $currency " . number_format($amount, 2) . " from {$cust['name']}$left");
            }
        }
        $redirect($back, 'Enter an amount greater than zero.', true);
    }

    // E. SETTINGS UPDATE
    if ($action === 'update_settings') {
        $bName = trim($_POST['businessName'] ?? '') ?: 'My Business';
        $bTagline = trim($_POST['businessTagline'] ?? '');
        $bPhone = trim($_POST['businessPhone'] ?? '');
        $bLocation = trim($_POST['businessLocation'] ?? '');
        $mMomo = trim($_POST['momoPaymentDetails'] ?? '');
        $cSymbol = trim($_POST['currencySymbol'] ?? '') ?: 'GH₵';

        $stmt = $db->prepare("REPLACE INTO app_settings (id, businessName, businessTagline, businessPhone, businessLocation, momoPaymentDetails, currencySymbol) VALUES (1, ?, ?, ?, ?, ?, ?)");
        $stmt->execute([$bName, $bTagline, $bPhone, $bLocation, $mMomo, $cSymbol]);
        $redirect('settings', 'Business details saved.');
    }
}

// ----------------------------------------------------
// 6. PAGE DATA
// ----------------------------------------------------
$tab = $_GET['tab'] ?? 'dashboard';
if ($tab === 'debtors') { $tab = 'customers'; $_GET['filter'] = 'owing'; } // old links
$validTabs = ['dashboard', 'new_sale', 'sales', 'products', 'customers', 'payments', 'inventory_logs', 'settings'];
if (!in_array($tab, $validTabs, true)) $tab = 'dashboard';

$totalSalesSum = (float)($db->query("SELECT SUM(totalAmount) FROM sales_orders")->fetchColumn() ?: 0.0);
$totalCollectedSum = (float)($db->query("SELECT SUM(amountPaid) FROM sales_orders")->fetchColumn() ?: 0.0);
$totalDebtSum = (float)($db->query("SELECT SUM(COALESCE(currentBalance, totalDebt, 0)) FROM customers WHERE COALESCE(currentBalance, totalDebt, 0) > 0")->fetchColumn() ?: 0.0);
$totalProductsCount = (int)($db->query("SELECT COUNT(*) FROM products")->fetchColumn() ?: 0);
$lowStockCount = (int)($db->query("SELECT COUNT(*) FROM products WHERE stockQuantity <= minStockThreshold")->fetchColumn() ?: 0);
$debtorsCount = (int)($db->query("SELECT COUNT(*) FROM customers WHERE COALESCE(currentBalance, totalDebt, 0) > 0.009")->fetchColumn() ?: 0);

$allProducts = $db->query("SELECT * FROM products ORDER BY name ASC")->fetchAll();
$allCustomers = $db->query("SELECT * FROM customers ORDER BY name ASC")->fetchAll();

$todayStartMs = strtotime('today') * 1000;
$nav = [
    'Overview' => [
        ['dashboard', 'Dashboard', 'home', null],
    ],
    'Sell' => [
        ['new_sale', 'New sale', 'plus', null],
        ['sales', 'Sales', 'receipt', null],
    ],
    'Manage' => [
        ['products', 'Products', 'box', $lowStockCount > 0 ? [$lowStockCount, 'warning', 'low on stock'] : null],
        ['customers', 'Customers', 'users', $debtorsCount > 0 ? [$debtorsCount, 'danger', 'owing'] : null],
    ],
    'Records' => [
        ['payments', 'Payments', 'wallet', null],
        ['inventory_logs', 'Stock history', 'history', null],
    ],
];
$pageTitles = [
    'dashboard' => 'Dashboard', 'new_sale' => 'New sale', 'sales' => 'Sales', 'products' => 'Products',
    'customers' => 'Customers', 'payments' => 'Payments', 'inventory_logs' => 'Stock history', 'settings' => 'Settings',
];

/** Small helpers for repeated view fragments. */
function pageHeader(string $title, string $subtitle = '', string $actions = ''): void { ?>
    <header class="flex flex-col sm:flex-row sm:items-end justify-between gap-4 mb-6 lg:mb-8">
        <div>
            <h1 class="page-title"><?= e($title) ?></h1>
            <?php if ($subtitle !== ''): ?><p class="muted mt-1.5"><?= e($subtitle) ?></p><?php endif; ?>
        </div>
        <?php if ($actions !== ''): ?><div class="flex flex-wrap gap-2"><?= $actions ?></div><?php endif; ?>
    </header>
<?php }

function emptyRow(int $cols, string $iconName, string $title, string $text): string {
    return '<tr class="empty-row"><td colspan="' . $cols . '"><div class="py-14 text-center">'
        . '<div class="tile bg-tone-neutral mx-auto mb-3" style="width:56px;height:56px;border-radius:999px">' . icon($iconName, 'ic-lg') . '</div>'
        . '<p class="font-semibold">' . e($title) . '</p><p class="muted text-sm mt-1">' . e($text) . '</p></div></td></tr>';
}

function noMatchRow(int $cols): string {
    return '<tr class="no-match hidden"><td colspan="' . $cols . '"><div class="py-12 text-center">'
        . '<div class="tile bg-tone-neutral mx-auto mb-3" style="width:56px;height:56px;border-radius:999px">' . icon('filterOff', 'ic-lg') . '</div>'
        . '<p class="font-semibold">Nothing matches</p><p class="muted text-sm mt-1">Try a different search or filter.</p></div></td></tr>';
}
?>
<!DOCTYPE html>
<html lang="en">
<head><?php renderHead($pageTitles[$tab] . ' · ' . $settings['businessName']); ?></head>
<body class="min-h-screen">
<a href="#main" class="sr-only focus:not-sr-only focus:fixed focus:top-3 focus:left-3 focus:z-50 btn btn-primary">Skip to content</a>

<!-- ===================== SIDEBAR ===================== -->
<div id="navScrim" class="fixed inset-0 z-40 hidden lg:hidden" style="background:rgba(9,9,11,.5)" onclick="toggleNav(false)"></div>
<aside id="sidebar" class="sidebar fixed inset-y-0 left-0 z-50 w-72 -translate-x-full lg:translate-x-0 transition-transform duration-200 flex flex-col" aria-label="Main navigation">
    <div class="flex items-center gap-3 px-5 h-20 flex-shrink-0">
        <div class="avatar bg-tone-<?= avatarTone($settings['businessName']) ?>" style="border-radius:14px"><?= e(initials($settings['businessName'])) ?></div>
        <div class="min-w-0">
            <div class="font-bold truncate"><?= e($settings['businessName']) ?></div>
            <div class="text-xs muted truncate"><?= e($settings['businessTagline'] ?: 'Business dashboard') ?></div>
        </div>
        <button class="icon-btn ml-auto lg:hidden" onclick="toggleNav(false)" aria-label="Close menu"><?= icon('x', 'ic-sm') ?></button>
    </div>

    <div class="px-4">
        <a href="index.php?tab=new_sale" class="btn btn-primary w-full"><?= icon('plus', 'ic-sm') ?> New sale <span class="kbd ml-auto" style="border-color:currentColor;color:inherit;opacity:.7">N</span></a>
    </div>

    <nav class="flex-1 overflow-y-auto px-3 pb-4">
        <?php foreach ($nav as $group => $links): ?>
            <div class="nav-group"><?= e($group) ?></div>
            <?php foreach ($links as [$key, $label, $ic, $pill]): ?>
                <a href="index.php?tab=<?= $key ?>" class="nav-link" <?= $tab === $key ? 'aria-current="page"' : '' ?>>
                    <?= icon($ic) ?> <?= e($label) ?>
                    <?php if ($pill): ?><span class="pill bg-tone-<?= $pill[1] ?>" title="<?= $pill[0] . ' ' . e($pill[2]) ?>"><?= $pill[0] ?></span><?php endif; ?>
                </a>
            <?php endforeach; ?>
        <?php endforeach; ?>
    </nav>

    <div class="p-3 divider space-y-1">
        <a href="index.php?tab=settings" class="nav-link" <?= $tab === 'settings' ? 'aria-current="page"' : '' ?>><?= icon('settings') ?> Settings</a>
        <button type="button" class="nav-link w-full" onclick="toggleTheme()">
            <span class="theme-dark-only"><?= icon('sun') ?></span><span class="theme-light-only"><?= icon('moon') ?></span>
            <span class="theme-light-only">Dark mode</span><span class="theme-dark-only">Light mode</span>
        </button>
        <a href="index.php?logout=1" class="nav-link"><?= icon('logout') ?> Sign out</a>
        <div class="flex items-center gap-2 px-3 pt-2 text-xs subtle">
            <span class="inline-block w-2 h-2 rounded-full" style="background:var(--success)"></span>
            <span class="truncate">Live · <?= e($DB_NAME) ?></span>
        </div>
    </div>
</aside>

<!-- Mobile top bar -->
<div class="lg:hidden sticky top-0 z-30 flex items-center gap-3 px-4 h-16" style="background:var(--surface);border-bottom:1px solid var(--border)">
    <button class="icon-btn" onclick="toggleNav(true)" aria-label="Open menu"><?= icon('menu', 'ic-sm') ?></button>
    <div class="font-bold truncate flex-1"><?= e($pageTitles[$tab]) ?></div>
    <?php if ($tab !== 'new_sale'): ?>
        <a href="index.php?tab=new_sale" class="btn btn-primary btn-sm"><?= icon('plus', 'ic-sm') ?> Sale</a>
    <?php endif; ?>
</div>

<!-- ===================== MAIN ===================== -->
<main id="main" class="lg:pl-72">
    <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-10 py-6 lg:py-10">

<?php
// =====================================================================
// DASHBOARD
// =====================================================================
if ($tab === 'dashboard'):
    $recentSales = $db->query("SELECT * FROM sales_orders ORDER BY timestamp DESC LIMIT 6")->fetchAll();
    $topDebtors = $db->query("SELECT * FROM customers WHERE COALESCE(currentBalance, totalDebt, 0) > 0.009 ORDER BY COALESCE(currentBalance, totalDebt, 0) DESC LIMIT 5")->fetchAll();
    $lowStock = $db->query("SELECT * FROM products WHERE stockQuantity <= minStockThreshold ORDER BY stockQuantity ASC LIMIT 5")->fetchAll();

    // Last 14 days of sales, bucketed per day.
    $days = 14;
    $chartStartMs = strtotime('today -' . ($days - 1) . ' days') * 1000;
    $q = $db->prepare("SELECT timestamp, totalAmount, itemsJson FROM sales_orders WHERE timestamp >= ?");
    $q->execute([min($chartStartMs, strtotime('first day of last month 00:00') * 1000)]);
    $periodRows = $q->fetchAll();

    $series = [];
    for ($i = 0; $i < $days; $i++) { $series[date('Y-m-d', strtotime("today -" . ($days - 1 - $i) . " days"))] = 0.0; }
    $todayTotal = 0.0; $todayCount = 0;
    $thisMonthStart = strtotime('first day of this month 00:00') * 1000;
    $lastMonthStart = strtotime('first day of last month 00:00') * 1000;
    $thisMonth = 0.0; $lastMonthToDate = 0.0;
    $dayOfMonth = (int)date('j');
    $productTotals = [];
    foreach ($periodRows as $r) {
        $ms = (float)$r['timestamp']; $amt = (float)$r['totalAmount'];
        $key = date('Y-m-d', (int)round($ms / 1000));
        if (isset($series[$key])) $series[$key] += $amt;
        if ($ms >= $todayStartMs) { $todayTotal += $amt; $todayCount++; }
        if ($ms >= $thisMonthStart) {
            $thisMonth += $amt;
            foreach ((json_decode((string)$r['itemsJson'], true) ?: []) as $it) {
                $n = (string)($it['productName'] ?? 'Item');
                $productTotals[$n] = ($productTotals[$n] ?? 0) + (float)($it['lineTotal'] ?? 0);
            }
        } elseif ($ms >= $lastMonthStart && (int)date('j', (int)round($ms / 1000)) <= $dayOfMonth) {
            $lastMonthToDate += $amt; // compare like-for-like: same days of the month
        }
    }
    arsort($productTotals);
    $topProducts = array_slice($productTotals, 0, 5, true);
    $maxDay = max(1.0, max($series));
    $chartTotal = array_sum($series);
    $monthDelta = $lastMonthToDate > 0 ? (($thisMonth - $lastMonthToDate) / $lastMonthToDate) * 100 : null;
    $hour = (int)date('G');
    $greeting = $hour < 12 ? 'Good morning' : ($hour < 17 ? 'Good afternoon' : 'Good evening');
?>
        <?php pageHeader($greeting, date('l, j F Y'),
            '<a href="index.php?tab=customers&filter=owing" class="btn btn-secondary">' . icon('wallet', 'ic-sm') . ' Collect payment</a>'
            . '<a href="index.php?tab=new_sale" class="btn btn-primary">' . icon('plus', 'ic-sm') . ' New sale</a>'); ?>

        <!-- KPIs -->
        <section class="grid grid-cols-2 xl:grid-cols-4 gap-3 lg:gap-5 mb-6" aria-label="Key figures">
            <div class="card card-pad">
                <div class="flex items-center gap-2 muted text-sm font-medium"><span class="tile bg-tone-primary" style="width:32px;height:32px;border-radius:10px"><?= icon('trend', 'ic-sm') ?></span> Today</div>
                <div class="kpi"><?= money($todayTotal) ?></div>
                <div class="text-sm muted mt-1"><?= $todayCount ?> <?= $todayCount === 1 ? 'sale' : 'sales' ?></div>
            </div>
            <div class="card card-pad">
                <div class="flex items-center gap-2 muted text-sm font-medium"><span class="tile bg-tone-info" style="width:32px;height:32px;border-radius:10px"><?= icon('receipt', 'ic-sm') ?></span> This month</div>
                <div class="kpi"><?= money($thisMonth) ?></div>
                <div class="text-sm mt-1 flex items-center gap-1">
                    <?php if ($monthDelta === null): ?>
                        <span class="muted">No sales last month to compare</span>
                    <?php else: $up = $monthDelta >= 0; ?>
                        <span class="badge tone-<?= $up ? 'success' : 'danger' ?>"><?= icon($up ? 'arrowUp' : 'arrowDown', 'ic-sm') ?><?= number_format(abs($monthDelta), 0) ?>%</span>
                        <span class="muted">vs same days last month</span>
                    <?php endif; ?>
                </div>
            </div>
            <div class="card card-pad">
                <div class="flex items-center gap-2 muted text-sm font-medium"><span class="tile bg-tone-success" style="width:32px;height:32px;border-radius:10px"><?= icon('checkCircle', 'ic-sm') ?></span> Collected</div>
                <div class="kpi"><?= money($totalCollectedSum) ?></div>
                <div class="text-sm muted mt-1">of <?= money($totalSalesSum) ?> sold</div>
            </div>
            <a href="index.php?tab=customers&filter=owing" class="card card-pad block transition hover:ring-2" style="--tw-ring-color:var(--danger)">
                <div class="flex items-center gap-2 muted text-sm font-medium"><span class="tile bg-tone-danger" style="width:32px;height:32px;border-radius:10px"><?= icon('clock', 'ic-sm') ?></span> Owed to you</div>
                <div class="kpi <?= $totalDebtSum > 0 ? 'text-danger' : '' ?>"><?= money($totalDebtSum) ?></div>
                <div class="text-sm muted mt-1"><?= $debtorsCount ?> <?= $debtorsCount === 1 ? 'customer' : 'customers' ?> owing</div>
            </a>
        </section>

        <div class="grid grid-cols-1 xl:grid-cols-3 gap-5">
            <!-- Chart -->
            <section class="card card-pad xl:col-span-2">
                <div class="flex items-start justify-between gap-4 mb-6">
                    <div>
                        <h2 class="section-title">Sales, last 14 days</h2>
                        <p class="muted text-sm mt-0.5"><?= money($chartTotal) ?> total · <?= money($chartTotal / $days) ?> per day on average</p>
                    </div>
                </div>
                <div class="bars" role="img" aria-label="Daily sales for the last 14 days">
                    <?php $i = 0; foreach ($series as $day => $amt): $isToday = $i === $days - 1; $i++; ?>
                        <div class="bar-wrap" title="<?= e(date('D j M', strtotime($day))) ?> · <?= e($currency) ?> <?= number_format($amt, 2) ?>">
                            <div class="bar <?= $isToday ? 'today' : '' ?>" style="height: <?= max(2, round($amt / $maxDay * 100)) ?>%"></div>
                        </div>
                    <?php endforeach; ?>
                </div>
                <div class="flex justify-between text-xs subtle mt-3 tnum">
                    <span><?= date('j M', strtotime('today -13 days')) ?></span><span><?= date('j M', strtotime('today -7 days')) ?></span><span>Today</span>
                </div>
            </section>

            <!-- Top products -->
            <section class="card card-pad">
                <h2 class="section-title">Best sellers this month</h2>
                <?php if (empty($topProducts)): ?>
                    <p class="muted text-sm mt-6">Sell something this month to see your best sellers here.</p>
                <?php else: $topMax = max($topProducts); ?>
                    <ul class="mt-5 space-y-4">
                        <?php foreach ($topProducts as $name => $amt): ?>
                            <li>
                                <div class="flex justify-between gap-3 text-sm"><span class="font-medium truncate"><?= e($name) ?></span><span class="tnum font-semibold"><?= money($amt) ?></span></div>
                                <div class="h-2 rounded-full mt-2" style="background:var(--surface-2)"><div class="h-2 rounded-full" style="width:<?= max(4, round($amt / $topMax * 100)) ?>%;background:var(--primary)"></div></div>
                            </li>
                        <?php endforeach; ?>
                    </ul>
                <?php endif; ?>
            </section>

            <!-- Recent sales -->
            <section class="card xl:col-span-2 overflow-hidden">
                <div class="flex items-center justify-between card-pad pb-3">
                    <h2 class="section-title">Recent sales</h2>
                    <a href="index.php?tab=sales" class="btn btn-ghost btn-sm">View all <?= icon('arrowRight', 'ic-sm') ?></a>
                </div>
                <?php if (empty($recentSales)): ?>
                    <div class="px-6 pb-10 pt-4 text-center">
                        <p class="font-semibold">No sales yet</p>
                        <p class="muted text-sm mt-1">Your first sale will show up here.</p>
                        <a href="index.php?tab=new_sale" class="btn btn-primary mt-4"><?= icon('plus', 'ic-sm') ?> Record a sale</a>
                    </div>
                <?php else: ?>
                    <ul>
                        <?php foreach ($recentSales as $s): [$stLabel, $stTone] = saleStatus($s); ?>
                            <li class="divider">
                                <a href="index.php?tab=sales&open=<?= (int)$s['id'] ?>" class="flex items-center gap-3 px-5 lg:px-6 py-3.5 transition hover:bg-[var(--surface-2)]">
                                    <span class="avatar bg-tone-<?= avatarTone((string)$s['customerName']) ?>"><?= e(initials((string)$s['customerName'])) ?></span>
                                    <span class="min-w-0 flex-1">
                                        <span class="block font-semibold truncate"><?= e($s['customerName'] ?: 'Walk-in customer') ?></span>
                                        <span class="block text-sm muted truncate">#<?= e(invoiceNo($s)) ?> · <?= fmtDate($s['timestamp'], 'j M, H:i') ?></span>
                                    </span>
                                    <span class="text-right">
                                        <span class="block font-semibold tnum"><?= money($s['totalAmount']) ?></span>
                                        <span class="badge tone-<?= $stTone ?> mt-1"><?= $stLabel ?></span>
                                    </span>
                                </a>
                            </li>
                        <?php endforeach; ?>
                    </ul>
                <?php endif; ?>
            </section>

            <div class="space-y-5">
                <!-- Who owes -->
                <section class="card overflow-hidden">
                    <div class="flex items-center justify-between card-pad pb-3">
                        <h2 class="section-title">Who owes you</h2>
                        <a href="index.php?tab=customers&filter=owing" class="btn btn-ghost btn-sm">All <?= icon('arrowRight', 'ic-sm') ?></a>
                    </div>
                    <?php if (empty($topDebtors)): ?>
                        <div class="px-6 pb-6 flex items-center gap-3"><span class="tile bg-tone-success"><?= icon('checkCircle', 'ic-sm') ?></span><p class="text-sm">Nobody owes you. Every account is settled.</p></div>
                    <?php else: ?>
                        <ul>
                            <?php foreach ($topDebtors as $d): $bal = (float)($d['currentBalance'] ?: $d['totalDebt']); ?>
                                <li class="divider flex items-center gap-3 px-5 lg:px-6 py-3">
                                    <span class="avatar bg-tone-<?= avatarTone($d['name']) ?>" style="width:36px;height:36px;font-size:13px"><?= e(initials($d['name'])) ?></span>
                                    <span class="min-w-0 flex-1">
                                        <span class="block font-medium truncate"><?= e($d['name']) ?></span>
                                        <span class="block text-sm text-danger font-semibold tnum"><?= money($bal) ?></span>
                                    </span>
                                    <button type="button" class="btn btn-secondary btn-sm" data-pay='<?= jsonAttr(['id' => (int)$d['id'], 'name' => $d['name'], 'balance' => $bal, 'return' => 'dashboard']) ?>'>Collect</button>
                                </li>
                            <?php endforeach; ?>
                        </ul>
                    <?php endif; ?>
                </section>

                <!-- Low stock -->
                <section class="card overflow-hidden">
                    <div class="flex items-center justify-between card-pad pb-3">
                        <h2 class="section-title">Running low</h2>
                        <a href="index.php?tab=products&stock=low" class="btn btn-ghost btn-sm">All <?= icon('arrowRight', 'ic-sm') ?></a>
                    </div>
                    <?php if (empty($lowStock)): ?>
                        <div class="px-6 pb-6 flex items-center gap-3"><span class="tile bg-tone-success"><?= icon('checkCircle', 'ic-sm') ?></span><p class="text-sm">Everything is well stocked.</p></div>
                    <?php else: ?>
                        <ul>
                            <?php foreach ($lowStock as $p): $out = (float)$p['stockQuantity'] <= 0; ?>
                                <li class="divider flex items-center gap-3 px-5 lg:px-6 py-3">
                                    <span class="min-w-0 flex-1 font-medium truncate"><?= e($p['name']) ?></span>
                                    <span class="badge tone-<?= $out ? 'danger' : 'warning' ?> tnum"><?= $out ? 'Out' : fmtQty($p['stockQuantity']) . ' ' . e($p['unit']) ?></span>
                                </li>
                            <?php endforeach; ?>
                        </ul>
                    <?php endif; ?>
                </section>
            </div>
        </div>

<?php
// =====================================================================
// NEW SALE (POS)
// =====================================================================
elseif ($tab === 'new_sale'):
    $posProducts = array_map(fn($p) => [
        'id' => (int)$p['id'], 'name' => (string)$p['name'], 'category' => (string)$p['category'],
        'categoryLabel' => categoryLabel((string)$p['category']), 'unit' => (string)$p['unit'], 'image' => (string)($p['imageUrl'] ?? ''),
        'price' => (float)$p['unitPrice'], 'stock' => (float)$p['stockQuantity'], 'min' => (float)$p['minStockThreshold'],
    ], $allProducts);
    $posCustomers = array_map(fn($c) => [
        'id' => (int)$c['id'], 'name' => (string)$c['name'], 'phone' => (string)$c['phone'],
        'balance' => (float)($c['currentBalance'] ?: $c['totalDebt']),
    ], $allCustomers);
    $posCategories = [];
    foreach ($allProducts as $p) $posCategories[$p['category']] = categoryLabel((string)$p['category']);
    asort($posCategories);
?>
        <?php pageHeader('New sale', 'Tap items to add them, then take payment.'); ?>

        <form method="POST" action="index.php" id="saleForm" class="grid grid-cols-1 lg:grid-cols-5 gap-6 items-start" novalidate>
            <?= csrfField() ?>
            <input type="hidden" name="action" value="create_sale">

            <!-- Catalog -->
            <section class="lg:col-span-3 space-y-4" aria-label="Products">
                <div class="search input-icon">
                    <?= icon('search', 'ic-sm') ?>
                    <input type="search" id="posSearch" class="input" placeholder="Search products   ( / )" aria-label="Search products" autocomplete="off">
                </div>
                <?php if (count($posCategories) > 1): ?>
                    <div class="scroll-x flex gap-2" role="group" aria-label="Filter by category" id="posCats">
                        <button type="button" class="chip" aria-pressed="true" data-cat="">All</button>
                        <?php foreach ($posCategories as $key => $label): ?>
                            <button type="button" class="chip" aria-pressed="false" data-cat="<?= e($key) ?>"><?= e($label) ?></button>
                        <?php endforeach; ?>
                    </div>
                <?php endif; ?>

                <?php if (empty($posProducts)): ?>
                    <div class="card card-pad text-center py-14">
                        <div class="tile bg-tone-primary mx-auto mb-3" style="width:56px;height:56px;border-radius:999px"><?= icon('box', 'ic-lg') ?></div>
                        <p class="font-semibold">No products yet</p>
                        <p class="muted text-sm mt-1">Add the goods or services you sell first.</p>
                        <a href="index.php?tab=products&add=1" class="btn btn-primary mt-4"><?= icon('plus', 'ic-sm') ?> Add a product</a>
                    </div>
                <?php else: ?>
                    <div id="posGrid" class="grid grid-cols-2 sm:grid-cols-3 gap-3"></div>
                    <p id="posNoMatch" class="hidden text-center muted py-10">No products match your search.</p>
                <?php endif; ?>
            </section>

            <!-- Cart & checkout -->
            <section class="lg:col-span-2 lg:sticky lg:top-6 space-y-4" aria-label="Checkout">
                <div class="card card-pad space-y-4">
                    <div class="flex items-center justify-between">
                        <h2 class="section-title">Customer</h2>
                        <span id="custDebtBadge" class="badge tone-danger hidden"></span>
                    </div>
                    <div>
                        <label class="label" for="custSelect">Saved customer</label>
                        <select id="custSelect" name="customer_id" class="input">
                            <option value="0">Walk-in / new customer</option>
                            <?php foreach ($posCustomers as $c): ?>
                                <option value="<?= $c['id'] ?>"><?= e($c['name']) ?><?= $c['balance'] > 0.009 ? ' · owes ' . e($currency) . ' ' . number_format($c['balance'], 2) : '' ?></option>
                            <?php endforeach; ?>
                        </select>
                    </div>
                    <div class="grid grid-cols-2 gap-3">
                        <div>
                            <label class="label" for="custName">Name</label>
                            <input id="custName" name="customer_name" class="input" value="Walk-in customer" required autocomplete="off">
                        </div>
                        <div>
                            <label class="label" for="custPhone">Phone</label>
                            <input id="custPhone" name="customer_phone" class="input" inputmode="tel" placeholder="Optional" autocomplete="off">
                        </div>
                    </div>
                </div>

                <div class="card card-pad">
                    <div class="flex items-center justify-between mb-3">
                        <h2 class="section-title">Cart <span id="cartCount" class="muted font-medium"></span></h2>
                        <button type="button" id="clearCart" class="btn btn-ghost btn-sm hidden">Clear</button>
                    </div>
                    <div id="cartEmpty" class="text-center py-8">
                        <div class="tile bg-tone-neutral mx-auto mb-3" style="width:48px;height:48px;border-radius:999px"><?= icon('cart', 'ic') ?></div>
                        <p class="muted text-sm">Nothing here yet. Tap a product to add it.</p>
                    </div>
                    <ul id="cartList" class="space-y-3"></ul>

                    <div id="checkout" class="hidden mt-5 pt-5 divider space-y-4">
                        <div class="grid grid-cols-2 gap-3">
                            <div>
                                <label class="label" for="discount">Discount</label>
                                <input id="discount" name="discount_amount" type="number" min="0" step="0.01" class="input tnum" placeholder="0.00" inputmode="decimal">
                            </div>
                            <div>
                                <label class="label" for="paid">Amount paid</label>
                                <input id="paid" name="amount_paid" type="number" min="0" step="0.01" class="input tnum" inputmode="decimal">
                            </div>
                        </div>
                        <div class="scroll-x flex gap-2" role="group" aria-label="Payment presets">
                            <button type="button" class="chip" data-paid="full">Paid in full</button>
                            <button type="button" class="chip" data-paid="half">50% deposit</button>
                            <button type="button" class="chip" data-paid="none">On credit</button>
                        </div>
                        <div>
                            <span class="label">Payment method</span>
                            <div class="seg w-full" role="radiogroup" aria-label="Payment method">
                                <?php $first = true; foreach ($PAYMENT_METHODS as $val => $label): ?>
                                    <label><input type="radio" name="payment_method" value="<?= $val ?>" <?= $first ? 'checked' : '' ?>><?= e($label) ?></label>
                                <?php $first = false; endforeach; ?>
                            </div>
                        </div>
                        <div>
                            <label class="label" for="saleNotes">Note</label>
                            <input id="saleNotes" name="notes" class="input" placeholder="Optional · delivery details, reference…">
                        </div>

                        <dl class="soft p-4 space-y-2 text-sm tnum">
                            <div class="flex justify-between"><dt class="muted">Subtotal</dt><dd id="sumSubtotal"></dd></div>
                            <div class="flex justify-between" id="rowDiscount"><dt class="muted">Discount</dt><dd id="sumDiscount" class="text-warning"></dd></div>
                            <div class="flex justify-between items-baseline pt-2 divider"><dt class="font-semibold">Total</dt><dd id="sumTotal" class="text-2xl font-extrabold"></dd></div>
                            <div id="sumStatus" class="pt-1"></div>
                        </dl>

                        <button type="submit" id="chargeBtn" class="btn btn-primary btn-lg w-full"></button>
                    </div>
                </div>
            </section>
        </form>

        <script>
        (function () {
            const products = <?= json_encode($posProducts, JSON_HEX_TAG | JSON_HEX_AMP | JSON_HEX_APOS | JSON_HEX_QUOT) ?>;
            const customers = <?= json_encode($posCustomers, JSON_HEX_TAG | JSON_HEX_AMP | JSON_HEX_APOS | JSON_HEX_QUOT) ?>;
            const cur = <?= json_encode($currency) ?>;
            const fmt = n => cur + ' ' + (Math.round(n * 100) / 100).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
            const qtyFmt = n => (Math.round(n * 100) / 100).toString();
            const cart = new Map(); // id -> { product, qty, price }
            let category = '', query = '', paidTouched = false;

            const grid = document.getElementById('posGrid');
            const el = id => document.getElementById(id);

            function tile(p) {
                const b = document.createElement('button');
                b.type = 'button';
                b.className = 'product-tile';
                b.dataset.id = p.id;
                const low = p.stock <= p.min, out = p.stock <= 0;
                b.innerHTML = '<div class="flex items-start justify-between gap-2 mb-3"><span class="thumb ring-primary" style="width:48px;height:48px"><span class="thumb-in bg-tone-primary"></span></span><span class="badge"></span></div>'
                    + '<div class="font-semibold leading-snug line-clamp-2" style="min-height:2.6em"></div>'
                    + '<div class="mt-1 text-sm"><span class="font-bold tnum price"></span><span class="muted unit"></span></div>'
                    + '<span class="qty-pill badge tone-primary hidden" style="position:absolute;bottom:12px;right:12px"></span>';
                const thumb = b.querySelector('.thumb-in');
                thumb.textContent = p.name.trim().charAt(0).toUpperCase() || '•';
                if (/^https?:\/\//i.test(p.image || '')) {
                    const img = document.createElement('img');
                    img.src = p.image; img.alt = ''; img.loading = 'lazy';
                    img.onerror = () => img.remove();
                    thumb.appendChild(img);
                }
                const badge = b.querySelector('.badge');
                badge.textContent = out ? 'Out' : qtyFmt(p.stock) + ' left';
                badge.classList.add(out ? 'tone-danger' : (low ? 'tone-warning' : 'tone-neutral'));
                b.children[1].textContent = p.name;
                b.querySelector('.price').textContent = fmt(p.price);
                b.querySelector('.unit').textContent = ' / ' + p.unit;
                b.setAttribute('aria-label', 'Add ' + p.name + ', ' + fmt(p.price) + ' per ' + p.unit);
                b.addEventListener('click', () => add(p));
                return b;
            }

            function renderGrid() {
                if (!grid) return;
                const q = query.toLowerCase();
                let shown = 0;
                grid.querySelectorAll('.product-tile').forEach(b => {
                    const p = products.find(x => x.id == b.dataset.id);
                    const ok = (!category || p.category === category) && (!q || p.name.toLowerCase().includes(q));
                    b.classList.toggle('hidden', !ok);
                    if (ok) shown++;
                    const line = cart.get(p.id);
                    b.classList.toggle('in-cart', !!line);
                    const pill = b.querySelector('.qty-pill');
                    pill.classList.toggle('hidden', !line);
                    if (line) pill.textContent = '× ' + qtyFmt(line.qty);
                });
                el('posNoMatch').classList.toggle('hidden', shown > 0);
            }

            function add(p, delta = 1) {
                const line = cart.get(p.id) || { product: p, qty: 0, price: p.price };
                line.qty = Math.max(0, Math.round((line.qty + delta) * 100) / 100);
                if (line.qty <= 0) cart.delete(p.id); else cart.set(p.id, line);
                render();
            }

            function render() {
                const list = el('cartList');
                list.innerHTML = '';
                let subtotal = 0, count = 0;
                cart.forEach(line => {
                    const p = line.product, total = line.qty * line.price;
                    subtotal += total; count += line.qty;
                    const li = document.createElement('li');
                    li.className = 'flex items-center gap-3';
                    li.innerHTML = '<div class="min-w-0 flex-1"><div class="font-medium truncate"></div>'
                        + '<div class="text-sm muted flex items-center gap-1 mt-0.5"><span>' + cur + '</span><input type="number" step="0.01" min="0" class="price-in tnum bg-transparent w-20 rounded px-1" aria-label="Unit price"><span class="unit"></span></div>'
                        + '<div class="stock-warn text-xs text-warning mt-0.5 hidden"></div></div>'
                        + '<div class="stepper"><button type="button" class="dec" aria-label="Decrease">' + <?= json_encode(icon('minus', 'ic-sm')) ?> + '</button>'
                        + '<input type="number" min="0" step="any" class="qty-in" aria-label="Quantity"><button type="button" class="inc" aria-label="Increase">' + <?= json_encode(icon('plus', 'ic-sm')) ?> + '</button></div>'
                        + '<div class="w-24 text-right font-semibold tnum line-total"></div>'
                        + '<input type="hidden" name="item_product_id[]"><input type="hidden" name="item_quantity[]"><input type="hidden" name="item_price[]">';
                    li.querySelector('.font-medium').textContent = p.name;
                    li.querySelector('.unit').textContent = '/ ' + p.unit;
                    const priceIn = li.querySelector('.price-in'); priceIn.value = line.price.toFixed(2);
                    const qtyIn = li.querySelector('.qty-in'); qtyIn.value = qtyFmt(line.qty);
                    li.querySelector('.line-total').textContent = fmt(total);
                    const hidden = li.querySelectorAll('input[type=hidden]');
                    hidden[0].value = p.id; hidden[1].value = line.qty; hidden[2].value = line.price;
                    if (line.qty > p.stock) {
                        const w = li.querySelector('.stock-warn');
                        w.textContent = 'Only ' + qtyFmt(Math.max(0, p.stock)) + ' in stock';
                        w.classList.remove('hidden');
                    }
                    li.querySelector('.dec').onclick = () => add(p, -1);
                    li.querySelector('.inc').onclick = () => add(p, 1);
                    qtyIn.onchange = () => { const v = parseFloat(qtyIn.value) || 0; add(p, v - line.qty); };
                    priceIn.onchange = () => { line.price = Math.max(0, parseFloat(priceIn.value) || 0); render(); };
                    list.appendChild(li);
                });

                const has = cart.size > 0;
                el('cartEmpty').classList.toggle('hidden', has);
                el('checkout').classList.toggle('hidden', !has);
                el('clearCart').classList.toggle('hidden', !has);
                el('cartCount').textContent = has ? '· ' + qtyFmt(count) + (count === 1 ? ' item' : ' items') : '';

                const discount = Math.min(subtotal, Math.max(0, parseFloat(el('discount').value) || 0));
                const total = Math.max(0, subtotal - discount);
                if (!paidTouched) el('paid').value = total.toFixed(2);
                const paid = Math.max(0, parseFloat(el('paid').value) || 0);
                const due = Math.max(0, total - paid), change = Math.max(0, paid - total);

                el('sumSubtotal').textContent = fmt(subtotal);
                el('rowDiscount').classList.toggle('hidden', discount <= 0);
                el('sumDiscount').textContent = '− ' + fmt(discount);
                el('sumTotal').textContent = fmt(total);
                const status = el('sumStatus');
                status.innerHTML = '';
                const b = document.createElement('span');
                b.className = 'badge';
                if (due > 0.009) {
                    const sel = customers.find(c => c.id == el('custSelect').value);
                    b.classList.add('tone-danger');
                    b.textContent = 'Balance due ' + fmt(due) + (sel ? '' : ' · pick a saved customer to track it');
                } else if (change > 0.009) {
                    b.classList.add('tone-warning'); b.textContent = 'Give change ' + fmt(change);
                } else {
                    b.classList.add('tone-success'); b.textContent = 'Paid in full';
                }
                status.appendChild(b);
                el('chargeBtn').innerHTML = <?= json_encode(icon('check', 'ic-sm')) ?> + ' <span></span>';
                el('chargeBtn').querySelector('span').textContent = (due > 0.009 ? 'Record sale · ' : 'Charge ') + fmt(total);
                document.querySelectorAll('[data-paid]').forEach(c => {
                    const v = c.dataset.paid;
                    const on = (v === 'full' && Math.abs(paid - total) < .005) || (v === 'half' && Math.abs(paid - total / 2) < .005 && total > 0) || (v === 'none' && paid === 0);
                    c.setAttribute('aria-pressed', on ? 'true' : 'false');
                });
                renderGrid();
            }

            if (grid) {
                products.forEach(p => grid.appendChild(tile(p)));
                el('posSearch').addEventListener('input', e => { query = e.target.value; renderGrid(); });
                document.querySelectorAll('#posCats .chip').forEach(c => c.addEventListener('click', () => {
                    category = c.dataset.cat;
                    document.querySelectorAll('#posCats .chip').forEach(x => x.setAttribute('aria-pressed', x === c ? 'true' : 'false'));
                    renderGrid();
                }));
            }
            el('discount').addEventListener('input', render);
            el('paid').addEventListener('input', () => { paidTouched = true; render(); });
            document.querySelectorAll('[data-paid]').forEach(c => c.addEventListener('click', () => {
                const sub = [...cart.values()].reduce((s, l) => s + l.qty * l.price, 0);
                const total = Math.max(0, sub - Math.min(sub, parseFloat(el('discount').value) || 0));
                el('paid').value = (c.dataset.paid === 'full' ? total : c.dataset.paid === 'half' ? total / 2 : 0).toFixed(2);
                paidTouched = true; render();
            }));
            el('clearCart').addEventListener('click', () => { cart.clear(); paidTouched = false; render(); });
            el('custSelect').addEventListener('change', e => {
                const c = customers.find(x => x.id == e.target.value);
                el('custName').value = c ? c.name : 'Walk-in customer';
                el('custPhone').value = c ? c.phone : '';
                el('custName').readOnly = !!c;
                const badge = el('custDebtBadge');
                badge.classList.toggle('hidden', !(c && c.balance > 0.009));
                if (c) badge.textContent = 'Owes ' + fmt(c.balance);
                render();
            });
            el('saleForm').addEventListener('submit', e => {
                if (cart.size === 0) { e.preventDefault(); showToast('Add at least one item first.', 'danger'); return; }
                if (!el('custName').value.trim()) { e.preventDefault(); el('custName').focus(); showToast('Enter the customer name.', 'danger'); return; }
                el('chargeBtn').disabled = true; // prevent double submit
                el('chargeBtn').textContent = 'Saving…';
            });
            render();
        })();
        </script>

<?php
// =====================================================================
// SALES
// =====================================================================
elseif ($tab === 'sales'):
    $allSales = $db->query("SELECT * FROM sales_orders ORDER BY timestamp DESC")->fetchAll();
    $openId = (int)($_GET['open'] ?? 0);
    $statusCounts = ['Paid' => 0, 'Partial' => 0, 'Unpaid' => 0];
    foreach ($allSales as $s) $statusCounts[saleStatus($s)[0]]++;
?>
        <?php pageHeader('Sales', count($allSales) . ' invoices · ' . money($totalSalesSum) . ' in total',
            '<a href="index.php?tab=new_sale" class="btn btn-primary">' . icon('plus', 'ic-sm') . ' New sale</a>'); ?>

        <div class="flex flex-col md:flex-row gap-3 mb-4" data-filters="salesTable">
            <div class="search input-icon flex-1">
                <?= icon('search', 'ic-sm') ?>
                <input type="search" class="input" data-filter-search placeholder="Search customer, invoice or item   ( / )" aria-label="Search sales">
            </div>
            <select class="input md:w-44" data-filter-key="period" aria-label="Date range">
                <option value="">All time</option>
                <option value="today">Today</option>
                <option value="7">Last 7 days</option>
                <option value="30">Last 30 days</option>
            </select>
            <div class="seg" role="group" aria-label="Payment status" data-filter-key="status">
                <button type="button" aria-pressed="true" data-value="">All</button>
                <?php foreach ($statusCounts as $label => $n): ?>
                    <button type="button" aria-pressed="false" data-value="<?= $label ?>"><?= $label ?> <span class="subtle tnum"><?= $n ?></span></button>
                <?php endforeach; ?>
            </div>
        </div>

        <div class="card overflow-hidden">
            <div class="overflow-x-auto">
                <table class="table" id="salesTable">
                    <thead><tr><th class="opt">Invoice</th><th>Customer</th><th class="opt">Date</th><th class="num">Total</th><th class="num opt">Balance</th><th>Status</th></tr></thead>
                    <tbody>
                    <?php if (empty($allSales)): ?>
                        <?= emptyRow(6, 'receipt', 'No sales yet', 'Record a sale and it will appear here.') ?>
                    <?php else: foreach ($allSales as $s):
                        [$stLabel, $stTone] = saleStatus($s);
                        $items = json_decode((string)$s['itemsJson'], true) ?: [];
                        $itemText = implode(' ', array_map(fn($i) => (string)($i['productName'] ?? ''), $items));
                        $order = [
                            'id' => (int)$s['id'], 'invoice' => invoiceNo($s), 'customer' => $s['customerName'], 'phone' => $s['customerPhone'],
                            'date' => fmtDate($s['timestamp'], 'j M Y, H:i'), 'items' => array_map(fn($i) => [
                                'name' => (string)($i['productName'] ?? 'Item'), 'qty' => (float)($i['quantity'] ?? 0),
                                'unit' => (string)($i['unit'] ?? ''), 'price' => (float)($i['unitPrice'] ?? 0), 'total' => (float)($i['lineTotal'] ?? 0)], $items),
                            'discount' => (float)$s['discountAmount'], 'total' => (float)$s['totalAmount'], 'paid' => (float)$s['amountPaid'],
                            'balance' => (float)$s['balanceDue'], 'status' => $stLabel, 'tone' => $stTone, 'method' => methodLabel((string)$s['paymentMethod']),
                            'notes' => (string)($s['notes'] ?? ''),
                        ];
                    ?>
                        <tr class="clickable" tabindex="0" data-order='<?= jsonAttr($order) ?>' data-status="<?= $stLabel ?>" data-ts="<?= (int)$s['timestamp'] ?>"
                            data-search="<?= e(strtolower(invoiceNo($s) . ' ' . $s['customerName'] . ' ' . $s['customerPhone'] . ' ' . $itemText)) ?>" <?= $openId === (int)$s['id'] ? 'data-autopen="1"' : '' ?>>
                            <td class="font-semibold whitespace-nowrap opt">#<?= e(invoiceNo($s)) ?></td>
                            <td>
                                <div class="flex items-center gap-3">
                                    <span class="avatar bg-tone-<?= avatarTone((string)$s['customerName']) ?>" style="width:34px;height:34px;font-size:12px"><?= e(initials((string)$s['customerName'])) ?></span>
                                    <div class="min-w-0">
                                        <div class="font-medium truncate max-w-[220px]"><?= e($s['customerName'] ?: 'Walk-in customer') ?></div>
                                        <div class="text-xs muted truncate max-w-[220px]"><?= e(count($items) . ' ' . (count($items) === 1 ? 'item' : 'items') . ($items ? ' · ' . ($items[0]['productName'] ?? '') . (count($items) > 1 ? ' +' . (count($items) - 1) : '') : '')) ?></div>
                                    </div>
                                </div>
                            </td>
                            <td class="muted whitespace-nowrap opt"><?= fmtDate($s['timestamp'], 'j M Y, H:i') ?></td>
                            <td class="num font-semibold"><?= money($s['totalAmount']) ?></td>
                            <td class="num opt <?= (float)$s['balanceDue'] > 0.009 ? 'text-danger font-semibold' : 'subtle' ?>"><?= (float)$s['balanceDue'] > 0.009 ? money($s['balanceDue']) : '—' ?></td>
                            <td><span class="badge tone-<?= $stTone ?>"><?= $stLabel ?></span></td>
                        </tr>
                    <?php endforeach; ?>
                        <?= noMatchRow(6) ?>
                    <?php endif; ?>
                    </tbody>
                </table>
            </div>
            <?php if (!empty($allSales)): ?>
                <div class="divider px-5 py-3 text-sm muted flex justify-between" data-summary="salesTable"><span></span><span class="tnum font-semibold" style="color:var(--text)"></span></div>
            <?php endif; ?>
        </div>

        <!-- Receipt -->
        <div class="modal" id="receiptModal" role="dialog" aria-modal="true" aria-labelledby="rcptTitle">
            <div class="modal-panel">
                <div class="p-6 sm:p-8">
                    <div class="flex items-start justify-between gap-4">
                        <div>
                            <div class="caption"><?= e($settings['businessName']) ?></div>
                            <h2 id="rcptTitle" class="text-2xl font-extrabold mt-1 tracking-tight"></h2>
                            <p id="rcptDate" class="muted text-sm mt-0.5"></p>
                        </div>
                        <button type="button" class="icon-btn no-print" data-close aria-label="Close"><?= icon('x', 'ic-sm') ?></button>
                    </div>
                    <div class="soft p-4 mt-5 flex items-center gap-3">
                        <span id="rcptAvatar" class="avatar bg-tone-primary"></span>
                        <div class="min-w-0"><div id="rcptCustomer" class="font-semibold truncate"></div><div id="rcptPhone" class="text-sm muted"></div></div>
                        <span id="rcptStatus" class="badge ml-auto"></span>
                    </div>
                    <table class="w-full text-sm mt-5 tnum">
                        <thead><tr class="muted text-xs"><th class="text-left font-semibold pb-2">Item</th><th class="text-right font-semibold pb-2">Qty</th><th class="text-right font-semibold pb-2">Amount</th></tr></thead>
                        <tbody id="rcptItems"></tbody>
                    </table>
                    <dl class="mt-4 pt-4 divider space-y-2 text-sm tnum">
                        <div class="flex justify-between" id="rcptDiscRow"><dt class="muted">Discount</dt><dd id="rcptDisc"></dd></div>
                        <div class="flex justify-between text-base"><dt class="font-semibold">Total</dt><dd id="rcptTotal" class="font-extrabold"></dd></div>
                        <div class="flex justify-between"><dt class="muted">Paid · <span id="rcptMethod"></span></dt><dd id="rcptPaid"></dd></div>
                        <div class="flex justify-between" id="rcptBalRow"><dt class="font-semibold text-danger">Balance due</dt><dd id="rcptBal" class="font-bold text-danger"></dd></div>
                    </dl>
                    <p id="rcptNotes" class="soft p-3 text-sm mt-4 hidden"></p>
                    <p class="text-center text-xs subtle mt-6">Thank you for your business<?= $settings['businessPhone'] ? ' · ' . e($settings['businessPhone']) : '' ?></p>
                    <div class="flex gap-2 mt-6 no-print">
                        <button type="button" class="btn btn-secondary flex-1" onclick="window.print()"><?= icon('printer', 'ic-sm') ?> Print</button>
                        <a id="rcptWhatsapp" class="btn btn-success flex-1" target="_blank" rel="noopener"><?= icon('chat', 'ic-sm') ?> WhatsApp</a>
                    </div>
                </div>
            </div>
        </div>

        <script>
        (function () {
            const cur = <?= json_encode($currency) ?>, biz = <?= json_encode($settings['businessName']) ?>;
            const fmt = n => cur + ' ' + Number(n).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
            const $ = id => document.getElementById(id);
            function open(o) {
                $('rcptTitle').textContent = 'Receipt #' + o.invoice;
                $('rcptDate').textContent = o.date;
                $('rcptCustomer').textContent = o.customer || 'Walk-in customer';
                $('rcptPhone').textContent = o.phone || '';
                $('rcptAvatar').textContent = (o.customer || '?').trim().split(/\s+/).slice(0, 2).map(w => w[0]).join('').toUpperCase();
                const st = $('rcptStatus'); st.className = 'badge ml-auto tone-' + o.tone; st.textContent = o.status;
                const body = $('rcptItems'); body.innerHTML = '';
                o.items.forEach(i => {
                    const tr = document.createElement('tr');
                    tr.innerHTML = '<td class="py-1.5 pr-2"><div class="font-medium"></div><div class="text-xs muted"></div></td><td class="py-1.5 text-right whitespace-nowrap"></td><td class="py-1.5 text-right whitespace-nowrap font-medium"></td>';
                    tr.querySelector('.font-medium').textContent = i.name;
                    tr.querySelector('.text-xs').textContent = fmt(i.price) + (i.unit ? ' / ' + i.unit : '');
                    tr.children[1].textContent = (Math.round(i.qty * 100) / 100);
                    tr.children[2].textContent = fmt(i.total);
                    body.appendChild(tr);
                });
                $('rcptDiscRow').classList.toggle('hidden', !(o.discount > 0));
                $('rcptDisc').textContent = '− ' + fmt(o.discount);
                $('rcptTotal').textContent = fmt(o.total);
                $('rcptPaid').textContent = fmt(o.paid);
                $('rcptMethod').textContent = o.method;
                $('rcptBalRow').classList.toggle('hidden', !(o.balance > 0.009));
                $('rcptBal').textContent = fmt(o.balance);
                $('rcptNotes').classList.toggle('hidden', !o.notes);
                $('rcptNotes').textContent = o.notes;
                const lines = ['*' + biz + '* · Receipt #' + o.invoice, o.date, ''].concat(o.items.map(i => (Math.round(i.qty * 100) / 100) + ' × ' + i.name + ' = ' + fmt(i.total)))
                    .concat(['', 'Total: ' + fmt(o.total), 'Paid: ' + fmt(o.paid)]).concat(o.balance > 0.009 ? ['Balance due: ' + fmt(o.balance)] : []).concat(['', 'Thank you for your business!']);
                const phone = (o.phone || '').replace(/\D/g, '').replace(/^0/, '233');
                $('rcptWhatsapp').href = 'https://wa.me/' + phone + '?text=' + encodeURIComponent(lines.join('\n'));
                openModal('receiptModal');
            }
            document.querySelectorAll('#salesTable tr[data-order]').forEach(tr => {
                const o = JSON.parse(tr.dataset.order);
                tr.addEventListener('click', () => open(o));
                tr.addEventListener('keydown', e => { if (e.key === 'Enter') open(o); });
                if (tr.dataset.autopen) setTimeout(() => open(o), 150);
            });
        })();
        </script>

<?php
// =====================================================================
// PRODUCTS
// =====================================================================
elseif ($tab === 'products'):
    $stockFilter = in_array($_GET['stock'] ?? '', ['low', 'out'], true) ? $_GET['stock'] : '';
    $catsInUse = [];
    foreach ($allProducts as $p) $catsInUse[$p['category']] = categoryLabel((string)$p['category']);
    asort($catsInUse);
    $stockValue = array_sum(array_map(fn($p) => max(0, (float)$p['stockQuantity']) * (float)$p['unitPrice'], $allProducts));
    $outCount = count(array_filter($allProducts, fn($p) => (float)$p['stockQuantity'] <= 0));
    $lowOnly = $lowStockCount - $outCount;
?>
        <?php pageHeader('Products', $totalProductsCount . ' items · stock worth ' . money($stockValue) . ' at selling price',
            '<button type="button" class="btn btn-primary" data-product-new>' . icon('plus', 'ic-sm') . ' Add product</button>'); ?>

        <div class="flex flex-col md:flex-row gap-3 mb-4" data-filters="productsTable">
            <div class="search input-icon flex-1">
                <?= icon('search', 'ic-sm') ?>
                <input type="search" class="input" data-filter-search placeholder="Search products   ( / )" aria-label="Search products">
            </div>
            <?php if (count($catsInUse) > 1): ?>
                <select class="input md:w-56" data-filter-key="category" aria-label="Category">
                    <option value="">All categories</option>
                    <?php foreach ($catsInUse as $k => $l): ?><option value="<?= e($k) ?>"><?= e($l) ?></option><?php endforeach; ?>
                </select>
            <?php endif; ?>
            <div class="seg" role="group" aria-label="Stock level" data-filter-key="stock">
                <button type="button" aria-pressed="<?= $stockFilter === '' ? 'true' : 'false' ?>" data-value="">All</button>
                <button type="button" aria-pressed="<?= $stockFilter === 'low' ? 'true' : 'false' ?>" data-value="low">Low <span class="subtle tnum"><?= max(0, $lowOnly) ?></span></button>
                <button type="button" aria-pressed="<?= $stockFilter === 'out' ? 'true' : 'false' ?>" data-value="out">Out <span class="subtle tnum"><?= $outCount ?></span></button>
            </div>
        </div>

        <div class="card overflow-hidden">
            <div class="overflow-x-auto">
                <table class="table" id="productsTable">
                    <thead><tr><th>Product</th><th class="opt">Category</th><th class="num">Price</th><th class="num opt">Margin</th><th>Stock</th><th class="text-right"><span class="sr-only">Actions</span></th></tr></thead>
                    <tbody>
                    <?php if (empty($allProducts)): ?>
                        <?= emptyRow(6, 'box', 'Add your first product', 'Goods or services: set a price, unit and opening stock.') ?>
                    <?php else: foreach ($allProducts as $p):
                        $stock = (float)$p['stockQuantity']; $min = (float)$p['minStockThreshold'];
                        $state = $stock <= 0 ? 'out' : ($stock <= $min ? 'low' : 'ok');
                        $price = (float)$p['unitPrice']; $cost = (float)$p['costPrice'];
                        $margin = ($cost > 0 && $price > 0) ? (($price - $cost) / $price) * 100 : null;
                        $pData = ['id' => (int)$p['id'], 'name' => $p['name'], 'category' => $p['category'], 'unit' => $p['unit'], 'unitPrice' => $price,
                                  'costPrice' => $cost, 'stockQuantity' => $stock, 'minStockThreshold' => $min, 'description' => (string)($p['description'] ?? ''),
                                  'imageUrl' => (string)($p['imageUrl'] ?? '')];
                    ?>
                        <tr data-search="<?= e(strtolower($p['name'] . ' ' . ($p['description'] ?? '') . ' ' . categoryLabel((string)$p['category']))) ?>" data-category="<?= e($p['category']) ?>" data-stock="<?= $state ?>">
                            <td>
                                <div class="flex items-center gap-3">
                                    <?= productThumb($p, 44) ?>
                                    <div class="min-w-0">
                                        <div class="font-semibold truncate max-w-[260px]"><?= e($p['name']) ?></div>
                                        <?php if (!empty($p['description'])): ?><div class="text-xs muted truncate max-w-[260px]"><?= e($p['description']) ?></div><?php endif; ?>
                                    </div>
                                </div>
                            </td>
                            <td class="muted whitespace-nowrap opt"><?= e(categoryLabel((string)$p['category'])) ?></td>
                            <td class="num"><span class="font-semibold"><?= money($price) ?></span><div class="text-xs muted">per <?= e($p['unit']) ?></div></td>
                            <td class="num opt"><?= $margin === null ? '<span class="subtle">—</span>' : '<span class="' . ($margin < 0 ? 'text-danger' : '') . '">' . number_format($margin, 0) . '%</span><div class="text-xs muted">cost ' . money($cost) . '</div>' ?></td>
                            <td>
                                <span class="badge tone-<?= $state === 'out' ? 'danger' : ($state === 'low' ? 'warning' : 'success') ?> tnum">
                                    <?= $state === 'out' ? 'Out of stock' : fmtQty($stock) . ' ' . e($p['unit']) ?>
                                </span>
                                <?php if ($state === 'low'): ?><div class="text-xs muted mt-1">alert at <?= fmtQty($min) ?></div><?php endif; ?>
                            </td>
                            <td class="actions">
                                <div class="flex justify-end gap-1.5">
                                    <button type="button" class="icon-btn tone-primary" data-stock-adjust='<?= jsonAttr($pData) ?>' aria-label="Adjust stock for <?= e($p['name']) ?>" title="Adjust stock"><?= icon('box', 'ic-sm') ?></button>
                                    <button type="button" class="icon-btn" data-product-edit='<?= jsonAttr($pData) ?>' aria-label="Edit <?= e($p['name']) ?>" title="Edit"><?= icon('edit', 'ic-sm') ?></button>
                                    <form method="POST" action="index.php" data-confirm="Delete “<?= e($p['name']) ?>”? Past sales keep their records, but this can't be undone." data-confirm-action="Delete product">
                                        <?= csrfField() ?>
                                        <input type="hidden" name="action" value="delete_product">
                                        <input type="hidden" name="id" value="<?= (int)$p['id'] ?>">
                                        <button type="submit" class="icon-btn tone-danger" aria-label="Delete <?= e($p['name']) ?>" title="Delete"><?= icon('trash', 'ic-sm') ?></button>
                                    </form>
                                </div>
                            </td>
                        </tr>
                    <?php endforeach; ?>
                        <?= noMatchRow(6) ?>
                    <?php endif; ?>
                    </tbody>
                </table>
            </div>
        </div>

        <!-- Product form -->
        <div class="modal" id="productModal" role="dialog" aria-modal="true" aria-labelledby="productTitle">
            <div class="modal-panel wide">
                <form method="POST" action="index.php" enctype="multipart/form-data" class="p-6 sm:p-8 space-y-5">
                    <?= csrfField() ?>
                    <input type="hidden" name="action" value="create_product">
                    <input type="hidden" name="id" value="">
                    <input type="hidden" name="remove_photo" value="">
                    <div class="flex items-center justify-between">
                        <h2 id="productTitle" class="text-xl font-bold">Add product</h2>
                        <button type="button" class="icon-btn" data-close aria-label="Close"><?= icon('x', 'ic-sm') ?></button>
                    </div>
                    <div class="flex items-center gap-4">
                        <span class="thumb ring-primary" style="width:72px;height:72px"><span class="thumb-in bg-tone-primary" id="p_photo_preview"></span></span>
                        <div class="flex-1 min-w-0">
                            <div class="font-semibold">Photo</div>
                            <p class="text-sm muted" id="p_photo_hint">Optional · JPEG, PNG or WebP up to 6 MB. Also shown in the app.</p>
                        </div>
                        <label class="btn btn-secondary btn-sm cursor-pointer">
                            <?= icon('plus', 'ic-sm') ?> <span id="p_photo_btn">Choose</span>
                            <input type="file" name="photo" id="p_photo" accept="image/jpeg,image/png,image/webp" class="sr-only">
                        </label>
                        <button type="button" class="icon-btn tone-danger hidden" id="p_photo_remove" aria-label="Remove photo" title="Remove photo"><?= icon('trash', 'ic-sm') ?></button>
                    </div>
                    <div>
                        <label class="label" for="p_name">Name</label>
                        <input class="input" id="p_name" name="name" required placeholder="e.g. Bottled water 500ml, Haircut, Phone repair">
                    </div>
                    <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
                        <div>
                            <label class="label" for="p_category">Category</label>
                            <select class="input" id="p_category" name="category">
                                <?php $customCats = array_values(array_filter(array_keys($catsInUse), fn($k) => !isset($CATEGORIES[$k]) && !isset($LEGACY_CATEGORIES[$k]))); ?>
                                <?php if ($customCats): ?>
                                    <optgroup label="Your categories">
                                        <?php foreach ($customCats as $k): ?><option value="<?= e($k) ?>"><?= e(categoryLabel((string)$k)) ?></option><?php endforeach; ?>
                                    </optgroup>
                                <?php endif; ?>
                                <optgroup label="Suggested">
                                    <?php foreach ($CATEGORIES as $k => $l): ?><option value="<?= $k ?>"><?= e($l) ?></option><?php endforeach; ?>
                                </optgroup>
                                <optgroup label="Older categories" id="p_legacy" hidden>
                                    <?php foreach ($LEGACY_CATEGORIES as $k => $l): ?><option value="<?= $k ?>" hidden><?= e($l) ?></option><?php endforeach; ?>
                                </optgroup>
                                <option value="__new__">+ New category…</option>
                            </select>
                            <input class="input mt-2 hidden" id="p_category_new" name="category_new" maxlength="40" placeholder="Name, e.g. Pastries" autocomplete="off">
                        </div>
                        <div>
                            <label class="label" for="p_unit">Unit</label>
                            <input class="input" id="p_unit" name="unit" list="unitList" value="Piece" placeholder="Piece, Kg, Box, Hour…">
                            <datalist id="unitList"><?php foreach ($COMMON_UNITS as $u): ?><option value="<?= $u ?>"><?php endforeach; ?></datalist>
                        </div>
                        <div>
                            <label class="label" for="p_price">Selling price (<?= e($currency) ?>)</label>
                            <input class="input tnum" id="p_price" name="unitPrice" type="number" step="0.01" min="0" required placeholder="0.00" inputmode="decimal">
                        </div>
                        <div>
                            <label class="label" for="p_cost">Cost price <span class="subtle font-normal">optional</span></label>
                            <input class="input tnum" id="p_cost" name="costPrice" type="number" step="0.01" min="0" placeholder="0.00" inputmode="decimal">
                            <p class="hint" id="p_margin"></p>
                        </div>
                        <div>
                            <label class="label" for="p_stock">Stock on hand</label>
                            <input class="input tnum" id="p_stock" name="stockQuantity" type="number" step="any" min="0" value="0" inputmode="decimal">
                        </div>
                        <div>
                            <label class="label" for="p_min">Low-stock alert at</label>
                            <input class="input tnum" id="p_min" name="minStockThreshold" type="number" step="any" min="0" value="5" inputmode="decimal">
                        </div>
                    </div>
                    <div>
                        <label class="label" for="p_desc">Description <span class="subtle font-normal">optional</span></label>
                        <input class="input" id="p_desc" name="description" placeholder="Size, brand, details…">
                    </div>
                    <div class="flex gap-3 pt-2">
                        <button type="button" class="btn btn-secondary flex-1" data-close>Cancel</button>
                        <button type="submit" class="btn btn-primary flex-[2]" id="productSubmit">Add product</button>
                    </div>
                </form>
            </div>
        </div>

        <!-- Stock adjustment -->
        <div class="modal" id="stockModal" role="dialog" aria-modal="true" aria-labelledby="stockTitle">
            <div class="modal-panel">
                <form method="POST" action="index.php" class="p-6 sm:p-8 space-y-5">
                    <?= csrfField() ?>
                    <input type="hidden" name="action" value="restock_product">
                    <input type="hidden" name="id" value="">
                    <div class="flex items-start justify-between gap-4">
                        <div>
                            <h2 id="stockTitle" class="text-xl font-bold">Adjust stock</h2>
                            <p class="muted text-sm mt-0.5" id="stockSub"></p>
                        </div>
                        <button type="button" class="icon-btn" data-close aria-label="Close"><?= icon('x', 'ic-sm') ?></button>
                    </div>
                    <div class="seg w-full" role="radiogroup" aria-label="Direction">
                        <label><input type="radio" name="direction" value="add" checked><?= icon('arrowUp', 'ic-sm') ?> Add stock</label>
                        <label><input type="radio" name="direction" value="remove"><?= icon('arrowDown', 'ic-sm') ?> Remove</label>
                    </div>
                    <div>
                        <label class="label" for="s_qty">Quantity</label>
                        <input class="input input-lg tnum" id="s_qty" name="add_quantity" type="number" step="any" min="0.01" required inputmode="decimal" placeholder="0">
                    </div>
                    <div>
                        <label class="label" for="s_notes">Reason <span class="subtle font-normal">optional</span></label>
                        <input class="input" id="s_notes" name="notes" placeholder="New delivery, damaged, count correction…">
                    </div>
                    <div class="soft p-3 text-sm flex justify-between" id="stockPreview"><span class="muted">New stock</span><span class="font-semibold tnum"></span></div>
                    <button type="submit" class="btn btn-primary btn-lg w-full" id="stockSubmit">Update stock</button>
                </form>
            </div>
        </div>

        <script>
        (function () {
            const cur = <?= json_encode($currency) ?>;
            const pm = document.getElementById('productModal'), pf = pm.querySelector('form');
            function fillProduct(p) {
                const isNew = !p;
                pf.action.value = isNew ? 'create_product' : 'update_product';
                pf.id.value = isNew ? '' : p.id;
                document.getElementById('productTitle').textContent = isNew ? 'Add product' : 'Edit product';
                document.getElementById('productSubmit').textContent = isNew ? 'Add product' : 'Save changes';
                pf.name.value = isNew ? '' : p.name;
                // Older farm categories only appear when the product already uses one.
                const legacy = document.getElementById('p_legacy');
                const isLegacy = !isNew && [...legacy.querySelectorAll('option')].some(o => o.value === p.category);
                legacy.hidden = !isLegacy;
                legacy.querySelectorAll('option').forEach(o => o.hidden = !(isLegacy && o.value === p.category));
                pf.category.value = isNew ? 'GENERAL' : p.category;
                if (pf.category.value !== (isNew ? 'GENERAL' : p.category)) {
                    // Category added elsewhere (e.g. the app) after this page loaded.
                    const o = new Option(p.category, p.category, true, true);
                    pf.category.insertBefore(o, pf.category.firstChild);
                }
                pf.category_new.value = '';
                toggleNewCategory();
                pf.unit.value = isNew ? 'Piece' : p.unit;
                pf.unitPrice.value = isNew ? '' : p.unitPrice;
                pf.costPrice.value = isNew || !p.costPrice ? '' : p.costPrice;
                pf.stockQuantity.value = isNew ? 0 : p.stockQuantity;
                pf.minStockThreshold.value = isNew ? 5 : p.minStockThreshold;
                pf.description.value = isNew ? '' : p.description;
                currentPhoto = isNew ? '' : (p.imageUrl || '');
                pf.photo.value = '';
                pf.remove_photo.value = '';
                photoHint.textContent = defaultHint;
                showPhoto(currentPhoto);
                margin();
                openModal('productModal');
            }
            function margin() {
                const price = parseFloat(pf.unitPrice.value) || 0, cost = parseFloat(pf.costPrice.value) || 0;
                const m = document.getElementById('p_margin');
                m.textContent = price > 0 && cost > 0 ? 'Profit ' + cur + ' ' + (price - cost).toFixed(2) + ' per unit · ' + Math.round((price - cost) / price * 100) + '% margin' : '';
                m.style.color = price > 0 && cost > price ? 'var(--danger)' : '';
            }
            // ---- Photo: preview, size check, remove ----
            const photoPreview = document.getElementById('p_photo_preview');
            const photoHint = document.getElementById('p_photo_hint'), defaultHint = photoHint.textContent;
            const photoRemove = document.getElementById('p_photo_remove');
            let currentPhoto = '';
            function showPhoto(src) {
                photoPreview.textContent = (pf.name.value.trim().charAt(0) || '?').toUpperCase();
                if (src) {
                    const img = document.createElement('img');
                    img.src = src; img.alt = ''; img.onerror = () => img.remove();
                    photoPreview.appendChild(img);
                }
                photoRemove.classList.toggle('hidden', !src);
                document.getElementById('p_photo_btn').textContent = src ? 'Change' : 'Choose';
            }
            pf.photo.addEventListener('change', () => {
                const f = pf.photo.files[0];
                if (!f) return;
                if (f.size > 6 * 1024 * 1024) { pf.photo.value = ''; showToast('Photo must be smaller than 6 MB.', 'danger'); return; }
                pf.remove_photo.value = '';
                photoHint.textContent = f.name + ' · uploads when you save';
                showPhoto(URL.createObjectURL(f));
            });
            photoRemove.addEventListener('click', () => {
                pf.photo.value = '';
                pf.remove_photo.value = currentPhoto ? '1' : '';
                photoHint.textContent = currentPhoto ? 'Photo will be removed when you save.' : defaultHint;
                showPhoto('');
            });
            pf.name.addEventListener('input', () => { if (!photoPreview.querySelector('img')) showPhoto(''); });

            function toggleNewCategory() {
                const isNewCat = pf.category.value === '__new__';
                pf.category_new.classList.toggle('hidden', !isNewCat);
                pf.category_new.required = isNewCat;
                if (isNewCat) pf.category_new.focus();
            }
            pf.category.addEventListener('change', toggleNewCategory);
            pf.unitPrice.addEventListener('input', margin);
            pf.costPrice.addEventListener('input', margin);
            document.querySelectorAll('[data-product-new]').forEach(b => b.addEventListener('click', () => fillProduct(null)));
            document.querySelectorAll('[data-product-edit]').forEach(b => b.addEventListener('click', () => fillProduct(JSON.parse(b.dataset.productEdit))));
            if (new URLSearchParams(location.search).get('add')) fillProduct(null);

            const sm = document.getElementById('stockModal'), sf = sm.querySelector('form');
            let current = null;
            function preview() {
                const q = parseFloat(sf.add_quantity.value) || 0, dir = sf.querySelector('[name=direction]:checked').value;
                const next = Math.max(0, current.stockQuantity + (dir === 'add' ? q : -q));
                document.querySelector('#stockPreview span:last-child').textContent = (Math.round(next * 100) / 100) + ' ' + current.unit;
                const btn = document.getElementById('stockSubmit');
                btn.textContent = q > 0 ? (dir === 'add' ? 'Add ' : 'Remove ') + q + ' ' + current.unit : 'Update stock';
                btn.className = 'btn btn-lg w-full ' + (dir === 'add' ? 'btn-primary' : 'btn-danger');
            }
            document.querySelectorAll('[data-stock-adjust]').forEach(b => b.addEventListener('click', () => {
                current = JSON.parse(b.dataset.stockAdjust);
                sf.id.value = current.id;
                sf.add_quantity.value = ''; sf.notes.value = '';
                sf.querySelector('[value=add]').checked = true;
                document.getElementById('stockTitle').textContent = current.name;
                document.getElementById('stockSub').textContent = (Math.round(current.stockQuantity * 100) / 100) + ' ' + current.unit + ' on hand';
                preview();
                openModal('stockModal');
            }));
            sf.addEventListener('input', preview);
        })();
        </script>

<?php
// =====================================================================
// CUSTOMERS
// =====================================================================
elseif ($tab === 'customers'):
    $owingOnly = ($_GET['filter'] ?? '') === 'owing';
?>
        <?php pageHeader('Customers', count($allCustomers) . ' saved · ' . $debtorsCount . ' owing ' . money($totalDebtSum),
            '<button type="button" class="btn btn-primary" data-customer-new>' . icon('plus', 'ic-sm') . ' Add customer</button>'); ?>

        <div class="flex flex-col md:flex-row gap-3 mb-4" data-filters="customersTable">
            <div class="search input-icon flex-1">
                <?= icon('search', 'ic-sm') ?>
                <input type="search" class="input" data-filter-search placeholder="Search name, phone or address   ( / )" aria-label="Search customers">
            </div>
            <div class="seg" role="group" aria-label="Show" data-filter-key="owing">
                <button type="button" aria-pressed="<?= $owingOnly ? 'false' : 'true' ?>" data-value="">All <span class="subtle tnum"><?= count($allCustomers) ?></span></button>
                <button type="button" aria-pressed="<?= $owingOnly ? 'true' : 'false' ?>" data-value="yes">Owing <span class="subtle tnum"><?= $debtorsCount ?></span></button>
            </div>
        </div>

        <div class="card overflow-hidden">
            <div class="overflow-x-auto">
                <table class="table" id="customersTable">
                    <thead><tr><th>Customer</th><th class="opt">Contact</th><th class="num opt">Bought</th><th class="num opt">Paid</th><th class="num">Balance</th><th class="text-right"><span class="sr-only">Actions</span></th></tr></thead>
                    <tbody>
                    <?php if (empty($allCustomers)): ?>
                        <?= emptyRow(6, 'users', 'No customers yet', 'Customers you add or sell to on credit show up here.') ?>
                    <?php else:
                        // Owing customers first (largest balance), then everyone else alphabetically.
                        usort($allCustomers, function ($a, $b) {
                            $ba = (float)($a['currentBalance'] ?: $a['totalDebt']); $bb = (float)($b['currentBalance'] ?: $b['totalDebt']);
                            if (($ba > 0.009) !== ($bb > 0.009)) return $ba > 0.009 ? -1 : 1;
                            return $ba > 0.009 ? $bb <=> $ba : strcasecmp($a['name'], $b['name']);
                        });
                        foreach ($allCustomers as $c):
                        $bal = (float)($c['currentBalance'] ?: $c['totalDebt'] ?: 0.0);
                        $owes = $bal > 0.009;
                        $wa = preg_replace('/\D/', '', (string)($c['whatsapp'] ?: $c['phone']));
                        if (strpos($wa, '0') === 0) $wa = '233' . substr($wa, 1); // local Ghana numbers
                        $reminder = "Hello {$c['name']}, a friendly reminder from {$settings['businessName']}: your balance is {$currency} " . number_format($bal, 2) . '.'
                            . ($settings['momoPaymentDetails'] ? " You can pay via {$settings['momoPaymentDetails']}." : '') . ' Thank you!';
                        $cData = ['id' => (int)$c['id'], 'name' => $c['name'], 'phone' => $c['phone'], 'whatsapp' => $c['whatsapp'], 'address' => $c['address'], 'notes' => (string)($c['notes'] ?? '')];
                    ?>
                        <tr data-search="<?= e(strtolower($c['name'] . ' ' . $c['phone'] . ' ' . $c['whatsapp'] . ' ' . $c['address'])) ?>" data-owing="<?= $owes ? 'yes' : 'no' ?>">
                            <td>
                                <div class="flex items-center gap-3">
                                    <span class="avatar bg-tone-<?= avatarTone($c['name']) ?>"><?= e(initials($c['name'])) ?></span>
                                    <div class="min-w-0">
                                        <div class="font-semibold truncate max-w-[220px]"><?= e($c['name']) ?></div>
                                        <div class="text-xs muted truncate max-w-[220px]"><?= e($c['address'] ?: ($c['notes'] ?? '') ?: 'Customer since ' . fmtDate($c['createdAt'], 'M Y')) ?></div>
                                    </div>
                                </div>
                            </td>
                            <td class="whitespace-nowrap opt">
                                <?php if (!empty($c['phone'])): ?>
                                    <a href="tel:<?= e($c['phone']) ?>" class="inline-flex items-center gap-1.5 hover:underline"><?= icon('phone', 'ic-sm subtle') ?><?= e($c['phone']) ?></a>
                                <?php else: ?><span class="subtle">No phone</span><?php endif; ?>
                            </td>
                            <td class="num opt"><?= money($c['totalPurchases']) ?></td>
                            <td class="num muted opt"><?= money($c['totalPaid']) ?></td>
                            <td class="num">
                                <?php if ($owes): ?><span class="font-bold text-danger"><?= money($bal) ?></span>
                                <?php else: ?><span class="badge tone-success">Settled</span><?php endif; ?>
                            </td>
                            <td class="actions">
                                <div class="flex justify-end gap-1.5">
                                    <?php if ($owes): ?>
                                        <button type="button" class="btn btn-success btn-sm" data-pay='<?= jsonAttr(['id' => (int)$c['id'], 'name' => $c['name'], 'balance' => $bal, 'return' => 'customers']) ?>'><?= icon('wallet', 'ic-sm') ?> Collect</button>
                                        <?php if ($wa !== ''): ?>
                                            <a class="icon-btn tone-success" href="https://wa.me/<?= e($wa) ?>?text=<?= rawurlencode($reminder) ?>" target="_blank" rel="noopener" aria-label="Send WhatsApp reminder to <?= e($c['name']) ?>" title="WhatsApp reminder"><?= icon('chat', 'ic-sm') ?></a>
                                        <?php endif; ?>
                                    <?php elseif ($wa !== ''): ?>
                                        <a class="icon-btn" href="https://wa.me/<?= e($wa) ?>" target="_blank" rel="noopener" aria-label="WhatsApp <?= e($c['name']) ?>" title="WhatsApp"><?= icon('chat', 'ic-sm') ?></a>
                                    <?php endif; ?>
                                    <button type="button" class="icon-btn" data-customer-edit='<?= jsonAttr($cData) ?>' aria-label="Edit <?= e($c['name']) ?>" title="Edit"><?= icon('edit', 'ic-sm') ?></button>
                                    <form method="POST" action="index.php" data-confirm="Delete <?= e($c['name']) ?>?<?= $owes ? ' They still owe ' . money($bal) . ', and that balance will be lost.' : '' ?> Their past invoices are kept." data-confirm-action="Delete customer">
                                        <?= csrfField() ?>
                                        <input type="hidden" name="action" value="delete_customer">
                                        <input type="hidden" name="id" value="<?= (int)$c['id'] ?>">
                                        <button type="submit" class="icon-btn tone-danger" aria-label="Delete <?= e($c['name']) ?>" title="Delete"><?= icon('trash', 'ic-sm') ?></button>
                                    </form>
                                </div>
                            </td>
                        </tr>
                    <?php endforeach; ?>
                        <?= noMatchRow(6) ?>
                    <?php endif; ?>
                    </tbody>
                </table>
            </div>
        </div>

        <!-- Customer form -->
        <div class="modal" id="customerModal" role="dialog" aria-modal="true" aria-labelledby="customerTitle">
            <div class="modal-panel">
                <form method="POST" action="index.php" class="p-6 sm:p-8 space-y-4">
                    <?= csrfField() ?>
                    <input type="hidden" name="action" value="create_customer">
                    <input type="hidden" name="id" value="">
                    <div class="flex items-center justify-between">
                        <h2 id="customerTitle" class="text-xl font-bold">Add customer</h2>
                        <button type="button" class="icon-btn" data-close aria-label="Close"><?= icon('x', 'ic-sm') ?></button>
                    </div>
                    <div>
                        <label class="label" for="c_name">Name</label>
                        <input class="input" id="c_name" name="name" required placeholder="Full name or business name">
                    </div>
                    <div class="grid grid-cols-2 gap-3">
                        <div>
                            <label class="label" for="c_phone">Phone</label>
                            <input class="input" id="c_phone" name="phone" inputmode="tel" placeholder="024 123 4567">
                        </div>
                        <div>
                            <label class="label" for="c_wa">WhatsApp <span class="subtle font-normal">if different</span></label>
                            <input class="input" id="c_wa" name="whatsapp" inputmode="tel">
                        </div>
                    </div>
                    <div>
                        <label class="label" for="c_addr">Address</label>
                        <input class="input" id="c_addr" name="address" placeholder="Optional">
                    </div>
                    <div id="c_debt_wrap">
                        <label class="label" for="c_debt">Opening balance owed (<?= e($currency) ?>)</label>
                        <input class="input tnum" id="c_debt" name="initial_debt" type="number" step="0.01" min="0" placeholder="0.00" inputmode="decimal">
                        <p class="hint">Only if they already owe you from before.</p>
                    </div>
                    <div>
                        <label class="label" for="c_notes">Notes</label>
                        <input class="input" id="c_notes" name="notes" placeholder="Optional">
                    </div>
                    <div class="flex gap-3 pt-2">
                        <button type="button" class="btn btn-secondary flex-1" data-close>Cancel</button>
                        <button type="submit" class="btn btn-primary flex-[2]" id="customerSubmit">Add customer</button>
                    </div>
                </form>
            </div>
        </div>

        <script>
        (function () {
            const f = document.querySelector('#customerModal form');
            function fill(c) {
                const isNew = !c;
                f.action.value = isNew ? 'create_customer' : 'update_customer';
                f.id.value = isNew ? '' : c.id;
                document.getElementById('customerTitle').textContent = isNew ? 'Add customer' : 'Edit customer';
                document.getElementById('customerSubmit').textContent = isNew ? 'Add customer' : 'Save changes';
                document.getElementById('c_debt_wrap').classList.toggle('hidden', !isNew);
                ['name', 'phone', 'whatsapp', 'address', 'notes'].forEach(k => f[k].value = isNew ? '' : (c[k] || ''));
                f.initial_debt.value = '';
                openModal('customerModal');
            }
            document.querySelectorAll('[data-customer-new]').forEach(b => b.addEventListener('click', () => fill(null)));
            document.querySelectorAll('[data-customer-edit]').forEach(b => b.addEventListener('click', () => fill(JSON.parse(b.dataset.customerEdit))));
        })();
        </script>

<?php
// =====================================================================
// PAYMENTS
// =====================================================================
elseif ($tab === 'payments'):
    $allPayments = $db->query("SELECT * FROM payments ORDER BY timestamp DESC")->fetchAll();
    $monthStartMs = strtotime('first day of this month 00:00') * 1000;
    $payMonth = array_sum(array_map(fn($p) => (float)$p['timestamp'] >= $monthStartMs ? (float)$p['amount'] : 0, $allPayments));
    $payTotal = array_sum(array_map(fn($p) => (float)$p['amount'], $allPayments));
?>
        <?php pageHeader('Payments', 'Money received from customers, including part-payments on credit sales.'); ?>

        <div class="grid grid-cols-2 gap-3 lg:gap-5 mb-6">
            <div class="card card-pad"><div class="muted text-sm font-medium">This month</div><div class="kpi"><?= money($payMonth) ?></div></div>
            <div class="card card-pad"><div class="muted text-sm font-medium">All time · <?= count($allPayments) ?> payments</div><div class="kpi"><?= money($payTotal) ?></div></div>
        </div>

        <div class="flex flex-col md:flex-row gap-3 mb-4" data-filters="paymentsTable">
            <div class="search input-icon flex-1">
                <?= icon('search', 'ic-sm') ?>
                <input type="search" class="input" data-filter-search placeholder="Search customer or note   ( / )" aria-label="Search payments">
            </div>
            <select class="input md:w-52" data-filter-key="method" aria-label="Payment method">
                <option value="">All methods</option>
                <?php foreach ($PAYMENT_METHODS as $k => $l): ?><option value="<?= e($l) ?>"><?= e($l) ?></option><?php endforeach; ?>
            </select>
        </div>

        <div class="card overflow-hidden">
            <div class="overflow-x-auto">
                <table class="table" id="paymentsTable">
                    <thead><tr><th>Customer</th><th class="opt">Date</th><th class="opt">Method</th><th class="num">Amount</th><th class="num opt">Balance after</th><th class="opt">Note</th></tr></thead>
                    <tbody>
                    <?php if (empty($allPayments)): ?>
                        <?= emptyRow(6, 'wallet', 'No payments yet', 'Payments you collect from customers show up here.') ?>
                    <?php else: foreach ($allPayments as $p): $ml = methodLabel((string)$p['paymentMethod']); ?>
                        <tr data-search="<?= e(strtolower($p['customerName'] . ' ' . ($p['notes'] ?? ''))) ?>" data-method="<?= e($ml) ?>">
                            <td>
                                <div class="flex items-center gap-3">
                                    <span class="avatar bg-tone-<?= avatarTone((string)$p['customerName']) ?>" style="width:34px;height:34px;font-size:12px"><?= e(initials((string)$p['customerName'])) ?></span>
                                    <span class="font-medium"><?= e($p['customerName']) ?></span>
                                </div>
                            </td>
                            <td class="muted whitespace-nowrap opt"><?= fmtDate($p['timestamp'], 'j M Y, H:i') ?></td>
                            <td class="opt"><span class="badge tone-neutral"><?= e($ml) ?></span></td>
                            <td class="num font-semibold text-success">+ <?= money($p['amount']) ?></td>
                            <td class="num opt <?= (float)$p['balanceAfterPayment'] > 0.009 ? '' : 'subtle' ?>"><?= (float)$p['balanceAfterPayment'] > 0.009 ? money($p['balanceAfterPayment']) : 'Settled' ?></td>
                            <td class="muted text-sm max-w-[260px] truncate opt"><?= e($p['notes'] ?: '—') ?></td>
                        </tr>
                    <?php endforeach; ?>
                        <?= noMatchRow(6) ?>
                    <?php endif; ?>
                    </tbody>
                </table>
            </div>
        </div>

<?php
// =====================================================================
// STOCK HISTORY
// =====================================================================
elseif ($tab === 'inventory_logs'):
    $allLogs = $db->query("SELECT * FROM inventory_logs ORDER BY timestamp DESC LIMIT 300")->fetchAll();
    $typeLabels = ['RESTOCK' => 'Restock', 'SALE' => 'Sale', 'CORRECTION' => 'Correction', 'ADJUSTMENT' => 'Adjustment', 'DAMAGE' => 'Damaged',
                   'EGG_COLLECTION' => 'Production', 'MORTALITY' => 'Loss', 'VOID' => 'Sale voided'];
    $typeTones = ['RESTOCK' => 'success', 'SALE' => 'info', 'CORRECTION' => 'warning', 'DAMAGE' => 'danger', 'MORTALITY' => 'danger', 'EGG_COLLECTION' => 'success'];
?>
        <?php pageHeader('Stock history', 'Every change to stock: restocks, sales and corrections (latest 300).'); ?>

        <div class="flex flex-col md:flex-row gap-3 mb-4" data-filters="logsTable">
            <div class="search input-icon flex-1">
                <?= icon('search', 'ic-sm') ?>
                <input type="search" class="input" data-filter-search placeholder="Search product or reason   ( / )" aria-label="Search stock history">
            </div>
            <div class="seg" role="group" aria-label="Movement" data-filter-key="dir">
                <button type="button" aria-pressed="true" data-value="">All</button>
                <button type="button" aria-pressed="false" data-value="in">Stock in</button>
                <button type="button" aria-pressed="false" data-value="out">Stock out</button>
            </div>
        </div>

        <div class="card overflow-hidden">
            <div class="overflow-x-auto">
                <table class="table" id="logsTable">
                    <thead><tr><th>Product</th><th>Type</th><th class="opt">Date</th><th class="num">Change</th><th class="num opt">Stock after</th><th class="opt">Reason</th></tr></thead>
                    <tbody>
                    <?php if (empty($allLogs)): ?>
                        <?= emptyRow(6, 'history', 'No stock changes yet', 'Restocks, sales and corrections are logged here automatically.') ?>
                    <?php else: foreach ($allLogs as $l):
                        $chg = (float)$l['quantityChanged']; $in = $chg > 0;
                        $type = strtoupper((string)$l['changeType']);
                    ?>
                        <tr data-search="<?= e(strtolower($l['productName'] . ' ' . ($l['notes'] ?? '') . ' ' . ($typeLabels[$type] ?? $type))) ?>" data-dir="<?= $in ? 'in' : 'out' ?>">
                            <td class="font-medium"><?= e($l['productName']) ?></td>
                            <td><span class="badge tone-<?= $typeTones[$type] ?? 'neutral' ?>"><?= e($typeLabels[$type] ?? ucfirst(strtolower($type))) ?></span></td>
                            <td class="muted whitespace-nowrap opt"><?= fmtDate($l['timestamp'], 'j M Y, H:i') ?></td>
                            <td class="num font-semibold <?= $in ? 'text-success' : 'text-danger' ?>"><?= $in ? '+' : '−' ?><?= fmtQty(abs($chg)) ?> <span class="muted font-normal"><?= e($l['unit']) ?></span></td>
                            <td class="num opt"><?= fmtQty($l['quantityAfter']) ?></td>
                            <td class="muted text-sm max-w-[280px] truncate opt"><?= e($l['notes'] ?: '—') ?></td>
                        </tr>
                    <?php endforeach; ?>
                        <?= noMatchRow(6) ?>
                    <?php endif; ?>
                    </tbody>
                </table>
            </div>
        </div>

<?php
// =====================================================================
// SETTINGS
// =====================================================================
elseif ($tab === 'settings'): ?>
        <?php pageHeader('Settings', 'Your business details appear on receipts, reminders and in the app.'); ?>

        <div class="grid grid-cols-1 lg:grid-cols-5 gap-6 items-start">
            <form method="POST" action="index.php" class="card card-pad lg:col-span-3 space-y-5" id="settingsForm">
                <?= csrfField() ?>
                <input type="hidden" name="action" value="update_settings">
                <h2 class="section-title">Business profile</h2>
                <div>
                    <label class="label" for="s_name">Business name</label>
                    <input class="input" id="s_name" name="businessName" value="<?= e($settings['businessName']) ?>" required data-preview="name">
                </div>
                <div>
                    <label class="label" for="s_tag">Tagline</label>
                    <input class="input" id="s_tag" name="businessTagline" value="<?= e($settings['businessTagline']) ?>" placeholder="e.g. Quality you can trust" data-preview="tagline">
                </div>
                <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
                    <div>
                        <label class="label" for="s_phone">Phone</label>
                        <input class="input" id="s_phone" name="businessPhone" value="<?= e($settings['businessPhone']) ?>" inputmode="tel" data-preview="phone">
                    </div>
                    <div>
                        <label class="label" for="s_cur">Currency symbol</label>
                        <input class="input" id="s_cur" name="currencySymbol" value="<?= e($currency) ?>" list="curList" data-preview="currency">
                        <datalist id="curList"><option value="GH₵"><option value="₦"><option value="KSh"><option value="$"><option value="€"><option value="£"><option value="R"><option value="CFA"></datalist>
                    </div>
                </div>
                <div>
                    <label class="label" for="s_loc">Location</label>
                    <input class="input" id="s_loc" name="businessLocation" value="<?= e($settings['businessLocation']) ?>" placeholder="Town, street or landmark" data-preview="location">
                </div>
                <div>
                    <label class="label" for="s_pay">Payment instructions</label>
                    <input class="input" id="s_pay" name="momoPaymentDetails" value="<?= e($settings['momoPaymentDetails']) ?>" placeholder="e.g. MoMo 024 123 4567 (Ama's Shop) · Bank: …" data-preview="pay">
                    <p class="hint">Added to payment reminders so customers know how to pay you.</p>
                </div>
                <div class="flex items-center gap-3 pt-2">
                    <button type="submit" class="btn btn-primary" id="settingsSave" disabled><?= icon('check', 'ic-sm') ?> Save changes</button>
                    <span class="text-sm muted" id="settingsState">No unsaved changes</span>
                </div>
            </form>

            <div class="lg:col-span-2 space-y-5">
                <section class="card card-pad">
                    <div class="caption mb-4">Receipt preview</div>
                    <div class="soft p-5 text-center">
                        <div class="avatar bg-tone-primary mx-auto mb-3" style="width:48px;height:48px" id="pv_init"><?= e(initials($settings['businessName'])) ?></div>
                        <div class="font-bold text-lg" id="pv_name"><?= e($settings['businessName']) ?></div>
                        <div class="text-sm muted" id="pv_tagline"><?= e($settings['businessTagline']) ?></div>
                        <div class="text-xs subtle mt-1"><span id="pv_location"><?= e($settings['businessLocation']) ?></span> <span id="pv_phone"><?= e($settings['businessPhone']) ?></span></div>
                        <div class="divider my-4"></div>
                        <div class="flex justify-between text-sm tnum"><span class="muted">2 × Sample item</span><span><span class="pv_cur"><?= e($currency) ?></span> 50.00</span></div>
                        <div class="flex justify-between font-bold mt-2 tnum"><span>Total</span><span><span class="pv_cur"><?= e($currency) ?></span> 50.00</span></div>
                        <div class="text-xs muted mt-4" id="pv_pay"><?= e($settings['momoPaymentDetails']) ?></div>
                    </div>
                </section>
                <section class="card card-pad space-y-3">
                    <h2 class="section-title">Connection</h2>
                    <div class="flex items-center gap-3"><span class="tile bg-tone-success"><?= icon('database', 'ic-sm') ?></span><div><div class="font-medium">MySQL connected</div><div class="text-sm muted"><?= e($DB_NAME) ?> on <?= e($DB_HOST) ?></div></div></div>
                    <p class="text-sm muted">The app syncs through <code class="text-xs px-1.5 py-0.5 rounded" style="background:var(--surface-2)">sync.php</code> using the same secret key you sign in with.</p>
                </section>
            </div>
        </div>

        <script>
        (function () {
            const f = document.getElementById('settingsForm'), save = document.getElementById('settingsSave'), state = document.getElementById('settingsState');
            const initial = new FormData(f);
            const map = { name: 'pv_name', tagline: 'pv_tagline', phone: 'pv_phone', location: 'pv_location', pay: 'pv_pay' };
            f.addEventListener('input', e => {
                const k = e.target.dataset.preview;
                if (k === 'currency') document.querySelectorAll('.pv_cur').forEach(n => n.textContent = e.target.value || 'GH₵');
                else if (map[k]) document.getElementById(map[k]).textContent = e.target.value;
                if (k === 'name') document.getElementById('pv_init').textContent = e.target.value.trim().split(/\s+/).slice(0, 2).map(w => w[0] || '').join('').toUpperCase() || '?';
                const dirty = [...new FormData(f).entries()].some(([key, v]) => initial.get(key) !== v);
                save.disabled = !dirty;
                state.textContent = dirty ? 'You have unsaved changes' : 'No unsaved changes';
                state.style.color = dirty ? 'var(--warning)' : '';
            });
            window.addEventListener('beforeunload', e => { if (!save.disabled && !f.dataset.submitting) { e.preventDefault(); e.returnValue = ''; } });
            f.addEventListener('submit', () => f.dataset.submitting = '1');
        })();
        </script>
<?php endif; ?>

    </div>
</main>

<!-- ===================== GLOBAL: COLLECT PAYMENT ===================== -->
<div class="modal" id="paymentModal" role="dialog" aria-modal="true" aria-labelledby="payTitle">
    <div class="modal-panel">
        <form method="POST" action="index.php" class="p-6 sm:p-8 space-y-5">
            <?= csrfField() ?>
            <input type="hidden" name="action" value="record_payment">
            <input type="hidden" name="customer_id" value="">
            <input type="hidden" name="return_tab" value="customers">
            <div class="flex items-start justify-between gap-4">
                <div class="flex items-center gap-3">
                    <span class="avatar bg-tone-success" id="payAvatar"></span>
                    <div>
                        <h2 id="payTitle" class="text-xl font-bold">Collect payment</h2>
                        <p class="text-sm muted"><span id="payName"></span> owes <span id="payOwed" class="font-semibold text-danger tnum"></span></p>
                    </div>
                </div>
                <button type="button" class="icon-btn" data-close aria-label="Close"><?= icon('x', 'ic-sm') ?></button>
            </div>
            <div>
                <label class="label" for="payAmount">Amount received (<?= e($currency) ?>)</label>
                <input class="input input-lg tnum" id="payAmount" name="amount" type="number" step="0.01" min="0.01" required inputmode="decimal">
                <div class="flex gap-2 mt-3">
                    <button type="button" class="chip" data-pay-preset="1">Full amount</button>
                    <button type="button" class="chip" data-pay-preset="0.5">Half</button>
                </div>
            </div>
            <div>
                <span class="label">Method</span>
                <div class="seg w-full" role="radiogroup" aria-label="Payment method">
                    <?php $first = true; foreach ($PAYMENT_METHODS as $val => $label): ?>
                        <label><input type="radio" name="payment_method" value="<?= $val ?>" <?= $first ? 'checked' : '' ?>><?= e($label) ?></label>
                    <?php $first = false; endforeach; ?>
                </div>
            </div>
            <div>
                <label class="label" for="payNotes">Note <span class="subtle font-normal">optional</span></label>
                <input class="input" id="payNotes" name="notes" placeholder="e.g. MoMo reference 12345">
            </div>
            <div class="soft p-3 text-sm flex justify-between"><span class="muted">Balance after payment</span><span id="payAfter" class="font-semibold tnum"></span></div>
            <button type="submit" class="btn btn-success btn-lg w-full" id="paySubmit">Record payment</button>
        </form>
    </div>
</div>

<!-- ===================== GLOBAL: CONFIRM ===================== -->
<div class="modal" id="confirmModal" role="alertdialog" aria-modal="true" aria-labelledby="confirmTitle" aria-describedby="confirmText">
    <div class="modal-panel" style="max-width:420px">
        <div class="p-6 sm:p-8 text-center">
            <div class="tile bg-tone-danger mx-auto mb-4" style="width:56px;height:56px;border-radius:999px"><?= icon('alert', 'ic-lg') ?></div>
            <h2 id="confirmTitle" class="text-xl font-bold">Are you sure?</h2>
            <p id="confirmText" class="muted mt-2"></p>
            <div class="flex flex-col gap-2 mt-6">
                <button type="button" class="btn btn-danger btn-lg w-full" id="confirmYes">Delete</button>
                <button type="button" class="btn btn-secondary btn-lg w-full" data-close>Cancel</button>
            </div>
        </div>
    </div>
</div>

<!-- ===================== TOAST ===================== -->
<div class="toast" aria-live="polite" id="toastHost">
    <?php if ($msg !== '' || $error !== ''): $isErr = $error !== ''; ?>
        <div class="card p-4 flex items-start gap-3" role="<?= $isErr ? 'alert' : 'status' ?>" data-toast>
            <span class="tile bg-tone-<?= $isErr ? 'danger' : 'success' ?>" style="width:32px;height:32px;border-radius:999px"><?= icon($isErr ? 'alert' : 'check', 'ic-sm') ?></span>
            <p class="text-sm font-medium flex-1 pt-1.5"><?= e($isErr ? $error : $msg) ?></p>
            <button type="button" class="icon-btn" style="width:28px;height:28px" onclick="this.closest('[data-toast]').remove()" aria-label="Dismiss"><?= icon('x', 'ic-sm') ?></button>
        </div>
    <?php endif; ?>
</div>

<style>
  [data-theme="light"] .theme-dark-only, [data-theme="dark"] .theme-light-only { display: none; }
  @media (max-width: 1023px) { #sidebar.open { transform: translateX(0); } }
</style>
<script>
    const ICON_CHECK = <?= json_encode(icon('check', 'ic-sm')) ?>, ICON_ALERT = <?= json_encode(icon('alert', 'ic-sm')) ?>;
    const CURRENCY = <?= json_encode($currency) ?>;

    function toggleTheme() {
        const next = document.documentElement.getAttribute('data-theme') === 'dark' ? 'light' : 'dark';
        document.documentElement.setAttribute('data-theme', next);
        try { localStorage.setItem('bt-theme', next); } catch (e) {}
    }
    function toggleNav(open) {
        document.getElementById('sidebar').classList.toggle('open', open);
        document.getElementById('navScrim').classList.toggle('hidden', !open);
    }

    // ---- Modals: focus management, Esc and backdrop close ----
    let lastFocus = null;
    function openModal(id) {
        lastFocus = document.activeElement;
        const m = document.getElementById(id);
        m.classList.add('open');
        document.body.style.overflow = 'hidden';
        setTimeout(() => { const f = m.querySelector('input:not([type=hidden]):not([readonly]), select, textarea, button:not([data-close])'); if (f) f.focus(); }, 60);
    }
    function closeModal(m) {
        m.classList.remove('open');
        if (!document.querySelector('.modal.open')) document.body.style.overflow = '';
        if (lastFocus) lastFocus.focus();
    }
    document.querySelectorAll('.modal').forEach(m => {
        m.addEventListener('click', e => { if (e.target === m || e.target.closest('[data-close]')) closeModal(m); });
    });

    // ---- Toasts ----
    function showToast(text, tone = 'success') {
        const host = document.getElementById('toastHost');
        host.innerHTML = '';
        const t = document.createElement('div');
        t.className = 'card p-4 flex items-start gap-3';
        t.setAttribute('role', tone === 'danger' ? 'alert' : 'status');
        t.innerHTML = '<span class="tile bg-tone-' + tone + '" style="width:32px;height:32px;border-radius:999px">' + (tone === 'danger' ? ICON_ALERT : ICON_CHECK) + '</span><p class="text-sm font-medium flex-1 pt-1.5"></p>';
        t.querySelector('p').textContent = text;
        host.appendChild(t);
        setTimeout(() => t.remove(), 5000);
    }
    document.querySelectorAll('[data-toast]').forEach(t => setTimeout(() => t.remove(), 6000));
    // Drop flash params so a refresh doesn't repeat the message.
    (function () {
        const u = new URL(location.href);
        if (u.searchParams.has('msg') || u.searchParams.has('err') || u.searchParams.has('open')) {
            ['msg', 'err', 'open'].forEach(k => u.searchParams.delete(k));
            history.replaceState(null, '', u.pathname + (u.search ? u.search : ''));
        }
    })();

    // ---- Confirm before destructive submits ----
    let pendingForm = null;
    document.querySelectorAll('form[data-confirm]').forEach(f => f.addEventListener('submit', e => {
        if (f.dataset.confirmed) return;
        e.preventDefault();
        pendingForm = f;
        document.getElementById('confirmText').textContent = f.dataset.confirm;
        document.getElementById('confirmYes').textContent = f.dataset.confirmAction || 'Delete';
        openModal('confirmModal');
        setTimeout(() => document.querySelector('#confirmModal [data-close]').focus(), 80); // safe default
    }));
    document.getElementById('confirmYes').addEventListener('click', () => {
        if (!pendingForm) return;
        pendingForm.dataset.confirmed = '1';
        pendingForm.submit();
    });

    // ---- Collect payment ----
    (function () {
        const m = document.getElementById('paymentModal'), f = m.querySelector('form');
        let owed = 0;
        const money = n => CURRENCY + ' ' + Number(n).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
        function update() {
            const a = parseFloat(f.amount.value) || 0;
            const after = Math.max(0, owed - a);
            document.getElementById('payAfter').textContent = after <= 0.009 ? 'Settled' : money(after);
            document.getElementById('payAfter').style.color = after <= 0.009 ? 'var(--success)' : '';
            const btn = document.getElementById('paySubmit');
            btn.disabled = a <= 0 || a > owed + 0.001;
            btn.textContent = a > owed + 0.001 ? 'More than they owe' : (a > 0 ? 'Record ' + money(a) : 'Record payment');
            m.querySelectorAll('[data-pay-preset]').forEach(c => c.setAttribute('aria-pressed', Math.abs(a - owed * parseFloat(c.dataset.payPreset)) < .005 ? 'true' : 'false'));
        }
        document.querySelectorAll('[data-pay]').forEach(b => b.addEventListener('click', () => {
            const d = JSON.parse(b.dataset.pay);
            owed = Number(d.balance);
            f.customer_id.value = d.id;
            f.return_tab.value = d.return || 'customers';
            f.amount.value = owed.toFixed(2);
            f.amount.max = owed.toFixed(2);
            f.notes.value = '';
            document.getElementById('payName').textContent = d.name;
            document.getElementById('payOwed').textContent = money(owed);
            document.getElementById('payAvatar').textContent = d.name.trim().split(/\s+/).slice(0, 2).map(w => w[0]).join('').toUpperCase();
            update();
            openModal('paymentModal');
        }));
        m.querySelectorAll('[data-pay-preset]').forEach(c => c.addEventListener('click', () => { f.amount.value = (owed * parseFloat(c.dataset.payPreset)).toFixed(2); update(); }));
        f.amount.addEventListener('input', update);
        f.addEventListener('submit', () => { const b = document.getElementById('paySubmit'); setTimeout(() => { b.disabled = true; b.textContent = 'Saving…'; }); });
    })();

    // ---- Client-side table filters (search + segmented/select filters) ----
    document.querySelectorAll('[data-filters]').forEach(bar => {
        const table = document.getElementById(bar.dataset.filters);
        if (!table) return;
        const rows = [...table.querySelectorAll('tbody tr:not(.no-match):not(.empty-row)')];
        const noMatch = table.querySelector('tr.no-match');
        const search = bar.querySelector('[data-filter-search]');
        const state = {};
        bar.querySelectorAll('[data-filter-key]').forEach(ctrl => {
            const key = ctrl.dataset.filterKey;
            if (ctrl.tagName === 'SELECT') {
                state[key] = ctrl.value;
                ctrl.addEventListener('change', () => { state[key] = ctrl.value; apply(); });
            } else {
                const on = ctrl.querySelector('[aria-pressed="true"]');
                state[key] = on ? on.dataset.value : '';
                ctrl.querySelectorAll('button').forEach(b => b.addEventListener('click', () => {
                    ctrl.querySelectorAll('button').forEach(x => x.setAttribute('aria-pressed', x === b ? 'true' : 'false'));
                    state[key] = b.dataset.value; apply();
                }));
            }
        });
        const dayMs = 86400000, todayStart = <?= (int)$todayStartMs ?>;
        function apply() {
            const q = (search ? search.value : '').trim().toLowerCase();
            let shown = 0, sum = 0;
            rows.forEach(r => {
                let ok = !q || (r.dataset.search || '').includes(q);
                for (const [k, v] of Object.entries(state)) {
                    if (!v || !ok) continue;
                    if (k === 'period') {
                        const ts = Number(r.dataset.ts);
                        ok = v === 'today' ? ts >= todayStart : ts >= Date.now() - Number(v) * dayMs;
                    } else ok = r.dataset[k] === v;
                }
                r.classList.toggle('hidden', !ok);
                if (ok) { shown++; if (r.dataset.order) sum += JSON.parse(r.dataset.order).total; }
            });
            if (noMatch) noMatch.classList.toggle('hidden', shown > 0 || rows.length === 0);
            const summary = document.querySelector('[data-summary="' + table.id + '"]');
            if (summary) {
                summary.children[0].textContent = 'Showing ' + shown + ' of ' + rows.length;
                summary.children[1].textContent = CURRENCY + ' ' + sum.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
            }
        }
        if (search) search.addEventListener('input', apply);
        apply();
    });

    // ---- Keyboard shortcuts: "/" search, "n" new sale, Esc close ----
    document.addEventListener('keydown', e => {
        const typing = /INPUT|TEXTAREA|SELECT/.test(document.activeElement.tagName) || document.activeElement.isContentEditable;
        if (e.key === 'Escape') { const m = document.querySelector('.modal.open'); if (m) closeModal(m); else toggleNav(false); return; }
        if (typing || e.ctrlKey || e.metaKey || e.altKey) return;
        if (e.key === '/') { const s = document.querySelector('[data-filter-search], #posSearch'); if (s) { e.preventDefault(); s.focus(); } }
        if (e.key === 'n' || e.key === 'N') { if (!location.search.includes('tab=new_sale')) location.href = 'index.php?tab=new_sale'; }
    });
</script>
</body>
</html>
