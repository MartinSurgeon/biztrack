# BizTrack - Web POS & Business Management Dashboard

This web interface allows you to manage your business inventory, sales, customers, debts, and payments from any laptop or desktop web browser. It connects directly to your MySQL database on cPanel/Namecheap, which means **any changes made on your laptop will instantly sync with your Android mobile app without modifying any mobile app code!**

---

## 📁 Files Included in `cpanel_backend`

1. **`index.php`** — The complete Web Management Dashboard (POS, Products CRUD, Debtors Ledger, Payments, and Settings).
2. **`sync.php`** — The backend sync endpoint that your Android mobile app communicates with.
3. **`biztrack_core.php`** — Shared code used by both files above (database upgrades, two-way sync, photos). Both stop with a clear message if it is missing.
4. **`schema.sql`** — The MySQL database schema table definitions.

---

## 🚀 How to Set Up on Your Web Server (cPanel / Namecheap)

1. **Upload Files to cPanel**:
   - In cPanel **File Manager**, open `public_html/` (or a subdirectory like `public_html/biztrack/` or `public_html/api/`).
   - Upload `index.php`, `sync.php` and `biztrack_core.php` to the same folder, and import `schema.sql` into your phpMyAdmin database.

2. **Create `config.php`** next to `sync.php` (never commit it):
   ```php
   <?php
   $DB_HOST = 'localhost';
   $DB_NAME = 'your_cpanel_db_name';
   $DB_USER = 'your_cpanel_db_user';
   $DB_PASS = 'your_cpanel_db_password';
   $API_SECRET_KEY = 'a-long-random-string-of-32-or-more-characters';
   $SECRET_KEY = $API_SECRET_KEY;
   ```
   Or open `index.php` once and use the setup form (it only works while `config.php` does not exist). `sync.php` refuses to run with a missing, default or short (<24 chars) key. Use the same key in the Android app (Settings > Cloud Sync) and for web login. Serve over **HTTPS only**.


3. **Access from Your Laptop**:
   - Open your laptop browser and go to: `https://yourdomain.com/biztrack/index.php`
   - Enter your **Cloud Secret Key** to log in.

---

## 💻 Available CRUD Features on the Web Dashboard

Works for any kind of business (shop, salon, restaurant, repairs, farm…), in light or dark mode, on desktop and phone.

- **Dashboard**: Total sales (all time), today's and this month's sales (with change vs. the same days last month), money collected, money owed, a 14-day sales chart with a 7-day average line (hover or tap a day for its figures), best sellers, who owes you, and what's running low. The figure cards rearrange to fit any screen, from a small phone to a wide monitor.
- **New sale (POS)**: Tap products to add them (search and category filters), adjust quantities and prices, pick or type a customer, apply a discount, choose full / deposit / credit payment and a method. Stock is deducted automatically and a receipt opens when you're done.
- **Sales**: Search and filter by date or payment status, see running totals, and open any invoice as a printable receipt or share it on WhatsApp. From the receipt you can **edit the sale** (items, quantities, prices, discount, amount paid, method, notes) or **delete it**. Stock and the customer's balance are corrected automatically, and the change reaches every phone on its next sync.
- **Products**: Goods or services in any category, selling and cost price (margin shown automatically), add or remove stock, and low-stock alerts.
- **Customers**: Everyone you sell to, who owes what, one-click "Collect" payments, and WhatsApp payment reminders pre-filled with the balance.
- **Payments** and **Stock history**: A searchable record of every payment received and every stock change.
- **Settings**: Business name, tagline, phone, location, payment instructions and currency, with a live receipt preview.

Keyboard shortcuts: `N` new sale · `/` search · `Esc` close dialogs.

---

## 🔄 How it Syncs with Mobile (two-way)

When the app syncs, it first downloads what changed on the web and merges it, then uploads its own changes. Changes made on the web and on any number of phones are combined, not overwritten:

1. **The latest edit wins.** Every product, customer, sale, payment and the business details remember when they were last changed. An older copy never replaces a newer one.
2. **Stock adds up.** Stock is never copied as a number. Each sale, restock or correction is a stock-history entry, and each entry is counted once everywhere. A sale on a phone and a restock on the web both count.
3. **Balances add up.** What a customer owes = what they owed before BizTrack + their unpaid sales. It is recalculated after every change, so payments and edits made in different places all count.
4. **Deletions stick.** A sale, product, customer or payment deleted in one place is deleted everywhere, instead of being sent back by a device that still had it.

All devices should run the latest app version. Older app versions can still sync, but their changes are treated as "no edit time" and lose to newer edits.

---

## 🖼️ Product photos

Photos added in the app or on the web dashboard are shared between them:

- **App → web:** when the app syncs to the cloud, it first uploads new product photos to `sync.php`, then the product data. The web dashboard shows them in Products and New sale.
- **Web → app:** photos uploaded in the web product form appear in the app after "Restore / Pull from Cloud". A restore never removes a photo that is already saved on the phone.
- Photos are stored in an `uploads/products/` folder next to `sync.php` / `index.php` (created automatically). Make sure that folder is **writable** by PHP (permission 755 is normal on cPanel).
- Accepted: JPEG, PNG or WebP up to 6 MB. With PHP's GD extension (enabled on most hosts) photos are resized to 1024px and saved as JPEG.
- The folder gets its own `.htaccess` that disables directory listings and blocks scripts.

**Upgrading?** Upload the new `index.php`, `sync.php` **and** `biztrack_core.php` together, then update the app on every phone. New columns and tables are added to your database automatically the first time either page is opened. Existing balances are kept exactly as they are. Back up your database in phpMyAdmin first, as with any upgrade.
