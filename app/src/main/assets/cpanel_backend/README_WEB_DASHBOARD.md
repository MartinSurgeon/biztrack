# BizTrack - Web POS & Business Management Dashboard

This web interface allows you to manage your business inventory, sales, customers, debts, and payments from any laptop or desktop web browser. It connects directly to your MySQL database on cPanel/Namecheap, which means **any changes made on your laptop will instantly sync with your Android mobile app without modifying any mobile app code!**

---

## 📁 Files Included in `cpanel_backend`

1. **`index.php`** — The complete Web Management Dashboard (POS, Products CRUD, Debtors Ledger, Payments, and Settings).
2. **`sync.php`** — The backend sync endpoint that your Android mobile app communicates with.
3. **`schema.sql`** — The MySQL database schema table definitions.

---

## 🚀 How to Set Up on Your Web Server (cPanel / Namecheap)

1. **Upload Files to cPanel**:
   - In cPanel **File Manager**, open `public_html/` (or a subdirectory like `public_html/biztrack/` or `public_html/api/`).
   - Upload `index.php`, `sync.php`, and import `schema.sql` into your phpMyAdmin database.

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

- **Dashboard**: Today's and this month's sales (with change vs. the same days last month), money collected, money owed, a 14-day sales chart, best sellers, who owes you, and what's running low.
- **New sale (POS)**: Tap products to add them (search and category filters), adjust quantities and prices, pick or type a customer, apply a discount, choose full / deposit / credit payment and a method. Stock is deducted automatically and a receipt opens when you're done.
- **Sales**: Search and filter by date or payment status, see running totals, and open any invoice as a printable receipt or share it on WhatsApp.
- **Products**: Goods or services in any category, selling and cost price (margin shown automatically), add or remove stock, and low-stock alerts.
- **Customers**: Everyone you sell to, who owes what, one-click "Collect" payments, and WhatsApp payment reminders pre-filled with the balance.
- **Payments** and **Stock history**: A searchable record of every payment received and every stock change.
- **Settings**: Business name, tagline, phone, location, payment instructions and currency, with a live receipt preview.

Keyboard shortcuts: `N` new sale · `/` search · `Esc` close dialogs.

---

## 🔄 How it Syncs with Mobile in Real Time

1. When you add/update/delete products, sales, or debts on your laptop, `index.php` writes directly into the MySQL database.
2. On your Android app, whenever auto-sync triggers or you tap **"Restore / Pull from Cloud"**, the mobile app immediately downloads the updated state from MySQL.
3. Neither your mobile code nor your database schema needs to change!

---

## 🖼️ Product photos

Photos added in the app or on the web dashboard are shared between them:

- **App → web:** when the app syncs to the cloud, it first uploads new product photos to `sync.php`, then the product data. The web dashboard shows them in Products and New sale.
- **Web → app:** photos uploaded in the web product form appear in the app after "Restore / Pull from Cloud". A restore never removes a photo that is already saved on the phone.
- Photos are stored in an `uploads/products/` folder next to `sync.php` / `index.php` (created automatically). Make sure that folder is **writable** by PHP (permission 755 is normal on cPanel).
- Accepted: JPEG, PNG or WebP up to 6 MB. With PHP's GD extension (enabled on most hosts) photos are resized to 1024px and saved as JPEG.
- The folder gets its own `.htaccess` that disables directory listings and blocks scripts.

**Upgrading?** Upload the new `index.php` **and** `sync.php` together. The new `imageUrl` column is added to your database automatically on first use.
