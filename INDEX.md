# BizTrack – Project Index

Android POS / sales / inventory / customer-debt app (Kotlin, Jetpack Compose, Room) with SMS/WhatsApp
notifications and cloud sync to a PHP/MySQL (cPanel) backend that also serves a web dashboard.
Generic for any business (shops, services, food, farms). Legacy poultry categories are kept for old data. ~18k lines.

- Package: `com.example` · applicationId `com.aistudio.naadriel.farm` · minSdk 24 / targetSdk 36
- Stack: Compose + Material3, Room (KSP, DB version 6), WorkManager, Retrofit/OkHttp/Moshi, Coil, Firebase (AI, AppCheck), Roborazzi/Robolectric tests
- Secrets: `.env` (gitignored; template `.env.example`), release keystore via env `KEYSTORE_PATH`/`STORE_PASSWORD`/`KEY_PASSWORD`

## Root
| File | Purpose |
|---|---|
| `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties` | Gradle config (single module `:app`) |
| `gradle/libs.versions.toml` | Version catalog |
| `metadata.json` | AI Studio app metadata |
| `.gradle/` | Generated Gradle cache (gitignored, safe to delete) |

## Android app (`app/src/main/java/com/example/`)
| Path | Role |
|---|---|
| `MainActivity.kt` | Entry point; `Screen` enum (Home, Customers, Sell, Products, Settings — enum names are deep-link targets) + custom `AppTabBar` with raised center Sell button; handles widget/notification intents |
| `data/model/Models.kt` | Room entities: `ProductEntity`, `InventoryLogEntity`, `CustomerEntity`, `SaleOrderEntity`, `PaymentEntity`, `AppSettingsEntity`; enums `ProductCategory` (generic categories + `legacy` farm ones, `labelFor`), `CommonUnits`, `PaymentStatus`, `PaymentMethod`; `CartItem` |
| `data/model/BackupModels.kt` | JSON backup/restore shapes |
| `data/local/AppDatabase.kt` | Room DB (v6) + migrations |
| `data/local/Daos.kt` | `ProductDao`, `CustomerDao`, `SaleOrderDao`, `PaymentDao`, `AppSettingsDao`, `InventoryLogDao` |
| `data/repository/FarmRepository.kt` | Single repository over DAOs, sale/payment/stock logic |
| `data/remote/CloudSyncService.kt` | `pushToCloud`, `pullFromCloud`, `testConnection` against `sync.php` |
| `data/remote/CloudSyncModels.kt` | Sync payload models |
| `data/remote/SmsOnlineGhService.kt` | SMSOnlineGH API (Ghana phone formatting, send, balance), native SMS and WhatsApp share helpers |
| `ui/viewmodel/FarmViewModel.kt` | Main ViewModel (1076 lines) – app state and actions |
| `ui/screens/` | `SalesDashboardScreen`, `NewSaleScreen`, `CustomersDebtScreen`, `ProductsCatalogScreen`, `SettingsScreen` (largest, 1610 lines) |
| `ui/components/AppUi.kt` | **Design system**: `AppCard`, `ScreenHeader`, `SectionHeader`, `AppButton`, `CircleIconButton`, `AppTextField`, `SearchField`, `SegmentedControl`, `PillChip`/`ChipRow`, `StatusBadge`, `Avatar`, `IconBadge`, `Banner`, `EmptyState`, `ListRow`, `Tone`, `pressScale` |
| `ui/components/` | Dialogs/widgets: `CustomerDialog`, `EditInvoiceDialog`, `ReceiptDialog`, `RecordPaymentDialog`, `SmsPreviewDialog`, `ConfirmationDialog`, `CustomerPaymentHistoryComponent`, `ProductImageView` |
| `ui/theme/` | `Color` (zinc + indigo `Palette`, theme-aware `StatusColors` via `AppTheme.status`; legacy names like `FarmGreen`/`DebtRed` resolve per theme), `Theme` (full light/dark schemes, AA contrast), `Type` (system sans) |
| `util/DatabaseBackupHelper.kt` | Local DB export/import |
| `util/DigitalReceiptGenerator.kt` | Receipt rendering/sharing |
| `util/Formatters.kt` | Currency/date formatting |
| `util/NotificationHelper.kt` | Notifications (custom collapsed/expanded layouts) |
| `util/work/` | `BackupWorkScheduler` + `DatabaseBackupWorker` (scheduled backups); `SmsWorkScheduler` + `SmsOnlineGhWorker` (queued SMS) |
| `widget/FarmWidgetProvider.kt` | Home-screen summary widget (`ACTION_REFRESH_WIDGET`) |

Resources: `app/src/main/res/` – vector drawables for products (eggs, broiler, sasso, dressed chicken), widget drawables/layout, notification layouts, fonts, launcher icons, xml configs (`farm_widget_info`, `file_paths`, backup rules).
Manifest: INTERNET, ACCESS_NETWORK_STATE, POST_NOTIFICATIONS; `MainActivity`, `FarmWidgetProvider` receiver, FileProvider.

## Backend (`app/src/main/assets/cpanel_backend/`) – PHP + MySQL
| File | Purpose |
|---|---|
| `sync.php` (482 lines) | Endpoint the mobile app pushes/pulls to |
| `index.php` (1793 lines) | Web dashboard: POS, products CRUD, debtors ledger, payments, settings |
| `schema.sql` | MySQL tables: products, customers, sales_orders, payments, inventory_logs, app_settings (mirrors Room) |
| `README_WEB_DASHBOARD.md` | cPanel/Namecheap setup instructions |

## Tests
`app/src/test/` – `ExampleUnitTest`, `ExampleRobolectricTest`, `GreetingScreenshotTest` (+ `screenshots/greeting.png`); `app/src/androidTest/` – `ExampleInstrumentedTest`. These look like template stubs.

## Data flow
UI screens → `FarmViewModel` → `FarmRepository` → Room DAOs (`AppDatabase`). Sync: `CloudSyncService` ⇄ `sync.php` ⇄ MySQL ⇄ `index.php` web dashboard. SMS: `SmsOnlineGhService` / `SmsOnlineGhWorker`.

## Notes
- Git: `main`, two commits (initial + Android project structure).
- Schema changes must be kept in sync in three places: Room entities/migrations, `schema.sql`, and `sync.php`/`index.php`.
- The release signing config reads env vars; the debug config expects `debug.keystore` in the root (gitignored).

## UI conventions
- Build screens from `ui/components/AppUi.kt`; never hardcode `Color(0x…)` or `Color.White` in UI — use `MaterialTheme.colorScheme` or `AppTheme.status` (both switch for dark mode).
- Text on a solid fill uses its `on*` pair (`onPrimary`, `status.onSuccess`, …).
- Screenshot tests: `ScreensScreenshotTest` renders Home/Products/Customers in light + dark into `app/src/test/screenshots/` (`gradle :app:testDebugUnitTest -Proborazzi.test.record=true`; runs on SDK 35 because SDK 36 Robolectric needs Java 21).
