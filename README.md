# SupplierIQ — Java + MySQL Supplier Reliability Dashboard

This version stores supplier and reliability-score records in MySQL and reads them through Spring Boot JDBC. Related procurement event files (purchase orders, shipments, inspections, risk signals and exception examples) remain generated CSV files for this demo. All supplier records are fictional synthetic data.

## Requirements
- JDK 21
- Maven 3.9+
- MySQL Server 8.x (MySQL Workbench is optional)

## Step 1 — Create the database
Open MySQL Workbench or the MySQL command line and run:

```sql
CREATE DATABASE IF NOT EXISTS supplieriq;
```

The application initializes the relational schema automatically on startup. Existing `supplier_performance` records are preserved.

## Step 2 — Configure the MySQL password
The defaults in `src/main/resources/application.properties` are username `root` and an empty password. If your MySQL root account has a password, set environment variables before running the app in PowerShell. Passwords are not stored in the project source:

```powershell
$env:DB_USERNAME = "root"
$env:DB_PASSWORD = "YOUR_MYSQL_PASSWORD"
```

Do not commit real passwords to GitHub. You can also set `DB_URL` if MySQL runs on another host or port.

## Step 3 — Start the application
Open the `SupplierIQ_Java` project folder in VS Code, open Terminal, and run:

```powershell
mvn spring-boot:run
```

On first startup the application generates a fixed-seed starter dataset in `data/`, then loads the supplier and score rows into MySQL table `supplier_performance`. Open http://localhost:8080. Keep the terminal open while demonstrating.

## Step 4 — Verify the data in MySQL
Run these queries in MySQL Workbench:

```sql
USE supplieriq;
SELECT COUNT(*) AS supplier_count FROM supplier_performance;
SELECT supplier_id, name, reliability_score, risk_band
FROM supplier_performance
ORDER BY reliability_score DESC
LIMIT 10;
SELECT risk_band, COUNT(*) AS suppliers
FROM supplier_performance
GROUP BY risk_band;
```

The starter dataset has 50 suppliers. For the full synthetic dataset, stop the app and run:

```powershell
mvn -q -DskipTests package
java -cp target/classes com.supplieriq.generator.DataGenerator full 20261022
```

Restart the app with `mvn spring-boot:run`. Existing supplier rows are preserved. New supplier IDs are added without deleting existing records; score values are recomputed from imported operational facts when available.

## Demo score formula
Reliability score = delivery × 25% + quality × 25% + cost × 15% + capacity × 15% + compliance × 10% + disruption × 10%. Scores below 60 are `HIGH_RISK`, 60–79.99 are `WATCH`, and 80+ are `RELIABLE`. This is an illustrative demo model, not a production procurement policy.

## API endpoints
- `GET /api/summary` — overview metrics (supplier metrics from MySQL; PO/shipment/inspection counts from generated CSV files)
- `GET /api/suppliers?q=&risk=ALL` — supplier performance rows from MySQL
- `GET /api/suppliers/{supplierId}` — supplier details from MySQL, with related synthetic risk signals from CSV
- `GET /api/exceptions` — injected data-quality examples from generated CSV files
- `GET /api/validation` — basic validation summary

## Dataset and limitations
The synthetic supplier names and performance scores are generated using seed `20261022` for reproducibility. The application imports event-level procurement CSV records into MySQL relational tables. Supplier scores use documented operational proxy rules and are intended for a synthetic demo, not a validated predictive model.

## Business-rule implementation (additive update)

This version adds an additive relational procurement schema in `src/main/resources/schema.sql` while keeping `supplier_performance` and the existing dashboard endpoints intact. Startup copies existing supplier rows into `supplier_master` with `INSERT IGNORE`; it does not delete or replace existing suppliers. The CSV generator now aligns purchase orders, supplier-owned sites/contracts, shipments, inspections, and invoices to their parent supplier/PO. Five deliberate anomaly examples are kept in the separate challenge dataset and are explicitly documented in `ground_truth.csv` so validation can test detection rules.

### Rules and definitions
- **Master before child records:** supplier master, sites, contracts, purchase orders and PO lines have separate tables; child tables use foreign keys.
- **Parent IDs:** shipments, delivery events, inspections, invoices and risk signals include foreign keys to their parent records.
- **Business logic:** contract/PO dates and quantities/prices have checks; supplier-site ownership is generated consistently except the labelled test anomaly; the reliability score uses delivery 25%, quality 25%, cost 15%, capacity 15%, compliance 10%, disruption 10%; risk bands are derived from the resulting score (`<60 HIGH_RISK`, `<80 WATCH`, otherwise `RELIABLE`).
- **History:** `supplier_score_history` stores dated score snapshots and a JSON explanation; `state_events` stores state transitions as append-only events.
- **Ground truth:** `ground_truth.csv` describes the four intentionally inserted anomalies and the rule that should detect each one. They are not randomly labelled.

### Important scope note
The existing UI still reads procurement detail tables from generated CSV files, while supplier summary scores are read from MySQL. The new relational tables are created automatically, but this update does not automatically import every procurement CSV row into those MySQL tables. The generated CSV dataset is the rule-aligned source for those records; database import is a separate step if you want every procurement screen to query MySQL.

### Run
1. Back up your project folder and database first.
2. Extract this ZIP into a **new folder** (do not overwrite your current project yet).
3. Set `DB_USERNAME` and `DB_PASSWORD` in your terminal, then run `mvn spring-boot:run` from the project folder.
4. The schema uses `CREATE TABLE IF NOT EXISTS` and `INSERT IGNORE`; it does not drop or truncate existing data.

## Relational model completion
The startup importer now loads procurement CSV records into the relational tables in parent-first order, retaining pre-existing supplier rows. It rejects intentional invalid shipment/risk rows from operational tables and records them in `data_quality_issues`, with matching challenge labels in `ground_truth`. Score snapshots are stored in `supplier_score_history`; PO lifecycle history is stored in `state_events` and `entity_lifecycle`. See `RELATIONAL_MODEL.md` for entity keys, cardinalities, optional relationships, lifecycle rules and scoring formula.

New endpoints:
- `GET /api/data-quality/summary` — row counts by relational entity and issue table.
- `GET /api/data-quality/issues` — detected data-quality issues; add `?groundTruth=true` to show intentional challenge cases only.
- `GET /api/data-quality/validation` — validates cross-entity joins and date rules in the loaded relational data.

Import is restart-safe and additive; it does not delete supplier data. Generated challenge anomalies are preserved as labeled issue/ground-truth records instead of being inserted as invalid operational rows.

## Five implemented data/business rules

1. **Master-data-first import:** suppliers are loaded before sites and contracts, then purchase orders, PO lines, shipments, delivery events, inspections, invoices, risk signals, state events, and score snapshots. Imports are idempotent (`INSERT IGNORE`) and preserve existing supplier records.
2. **Referential and effective-date validation:** child rows are inserted only when parent rows exist. PO supplier must own its site and contract, and the contract must cover the PO order date. Invalid relationships are recorded in `data_quality_issues`; they are not loaded as valid operational rows.
3. **Operational business logic:** shipment delivery cannot predate dispatch; inspections and invoices must match the PO's supplier; invoices store variance against PO total; risk signals require source and observed timestamp. Supplier scores are recomputed from imported shipment, inspection, invoice, and risk facts using weights 25/25/15/15/10/10 for delivery/quality/cost/capacity/compliance/disruption. Score history stores a JSON explanation.
4. **Append-only state history:** state transitions are stored in `state_events`. `entity_lifecycle` stores effective-dated state intervals; earlier intervals are closed when a later state begins. Unknown entity references are reported as data-quality issues.
5. **Ground-truth anomaly labels:** documented anomaly rows are loaded before validation. A detected issue is marked as ground truth only when its entity, ID, issue type, and expected-detection label match `ground_truth`. Unrelated invalid data is recorded as a normal issue rather than being mislabeled as an intentional anomaly.

### Score interpretation

- Reliability = delivery × 0.25 + quality × 0.25 + cost × 0.15 + capacity × 0.15 + compliance × 0.10 + disruption × 0.10.
- `< 60`: `HIGH_RISK`; `60–<80`: `WATCH`; `>=80`: `RELIABLE`.
- For suppliers with no observations in a component, the scoring rule uses a documented neutral fallback (delivery/capacity 75; quality/cost 90; other empty aggregates default to 75 before clamping). Treat these as synthetic-demo defaults, not measured evidence.

## Granular synthetic-data specification (added)

For the full 10-step generation specification, field-level data dictionary, deterministic IDs, scalable fixed-seed generation, labelled anomaly set, SQL/JSON/CSV exports, validation checks and reproducibility verification, see `SYNTHETIC_DATA_GUIDE.md`.

The reproducible generator is `tools/generate_synthetic_data.py` (Python 3.10+, standard library only). The ZIP includes generated starter files under `data/starter/` and full-scale files under `data/full/`. Regenerate them with:

```powershell
python tools/generate_synthetic_data.py --out data/full_repeat --seed 20261022 --scale 1
python tools/verify_reproducibility.py data/full data/full_repeat
```

The generator's `seed_data.sql` writes to a dedicated `synthetic_seed_records` staging table and does not drop or truncate application tables. These synthetic data exports are a separate, reproducible dataset pipeline; the Spring Boot application's existing endpoints do not automatically switch their dashboard reads to these new `data/full/` files.

## Synthetic dataset acceptance checks

After generating `data/full`, run `python tools/verify_acceptance.py data/full`. The script creates `data/full/acceptance_report.csv` and exits with a failure code if any required check fails. See `SYNTHETIC_DATA_GUIDE.md` for metric definitions, fixed-seed reproducibility, challenge-set handling, and provenance.

All generated supplier names, IDs, operational records, risk events, and amounts are artificial test data; no real supplier/person records or confidential procurement data are used. The generator is local-only and uses Python's standard library.


## Final acceptance status (updated)

Run these from `SupplierIQ_Java` after regenerating the full dataset:

```powershell
python tools/generate_synthetic_data.py --out data/full --seed 20261022 --scale 1
python tools/verify_acceptance.py data/full
python tools/generate_synthetic_data.py --out data/full_repeat --seed 20261022 --scale 1
python tools/verify_reproducibility.py data/full data/full_repeat
```

The acceptance verifier checks target counts, joins, ownership, date/lifecycle rules, score weighting and fixed cutoff, score explanations and denominators, explicit missing-data handling, boundary inspection values, all five labeled negative scenarios, separate ground truth, manifest hashes, and synthetic-data provenance. The Spring Boot score snapshot uses the documented fixed cutoff `2026-10-22`; records after that cutoff are excluded from the relevant score components. `GET /api/exceptions` also evaluates the separately labeled challenge cases without importing those invalid records into baseline business tables.
