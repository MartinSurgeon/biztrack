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
   - In cPanel **File Manager**, open `public_html/` (or a subdirectory like `public_html/farm/` or `public_html/api/`).
   - Upload `index.php`, `sync.php`, and import `schema.sql` into your phpMyAdmin database.

2. **Configure Database Connection in `index.php`**:
   Open `index.php` on your server and make sure lines 18–24 match your MySQL database credentials:
   ```php
   define('DB_HOST', 'localhost');
   define('DB_NAME', 'your_cpanel_db_name');
   define('DB_USER', 'your_cpanel_db_user');
   define('DB_PASS', 'your_cpanel_db_password');
   define('SECRET_KEY', 'Naadriel@Farm2026!SecKey');
   ```

3. **Access from Your Laptop**:
   - Open your laptop browser and go to: `https://yourdomain.com/farm/index.php`
   - Enter your **Cloud Secret Key** to log in.

---

## 💻 Available CRUD Features on the Web Dashboard

- **Dashboard Overview**: Live sales totals, cash collected, total outstanding debts, and low stock warnings.
- **New Sale (Web POS)**: Create orders, select or type customers, calculate discounts, apply cash/MoMo/credit, automatically deduct product inventory stock, and log inventory movements.
- **Products & Stock Inventory**: Add new products (live broilers, crates of eggs, feed, layers, processed meat), edit prices, delete items, and perform quick restocks.
- **Customers & Debtors Ledger**: Track customer purchase histories, view debtors owing money, edit contacts, and view WhatsApp links.
- **Debt Recovery & Payments**: Record payments from debtors, which automatically reduces their balance in MySQL and marks their credit invoices as paid.
- **Inventory Audit History**: Complete log of every stock deduction and addition.
- **Farm Profile & Settings**: Update business name, phone, tagline, MoMo details, and currency symbol.

---

## 🔄 How it Syncs with Mobile in Real Time

1. When you add/update/delete products, sales, or debts on your laptop, `index.php` writes directly into the MySQL database.
2. On your Android app, whenever auto-sync triggers or you tap **"Restore / Pull from Cloud"**, the mobile app immediately downloads the updated state from MySQL.
3. Neither your mobile code nor your database schema needs to change!
