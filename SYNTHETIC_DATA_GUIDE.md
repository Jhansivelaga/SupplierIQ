# SupplierIQ synthetic data generation (granular specification)

## What is generated
The Python standard-library generator in `tools/generate_synthetic_data.py` is the reproducible source for this dataset. It generates baseline parent/master records first and dependent/event records after them. Baseline records are valid; deliberately invalid challenge records are isolated in `challenge_*.csv/json` and mapped by `ground_truth.csv/json`, so challenge labels are not included in baseline score features.

## Commands
Run from the `SupplierIQ_Java` project root (Python 3.10+):

```powershell
# Starter data: about 1% of full scale
python tools/generate_synthetic_data.py --out data/starter --seed 20261022 --scale 0.01

# Full baseline: 1,000 suppliers, 10,000 POs, 15,000 shipments, 20,000 inspections
python tools/generate_synthetic_data.py --out data/full --seed 20261022 --scale 1

# Same seed and scale into another directory to check reproducibility
python tools/generate_synthetic_data.py --out data/full_repeat --seed 20261022 --scale 1
```

Compare `source_manifest.json` file hashes in `data/full` and `data/full_repeat`. The manifest intentionally excludes its own hash and the validation report from the source file list to avoid self-reference. Files are deterministic for the same Python/runtime and seed.

## IDs and immutability
- Supplier: `SUP-000001`
- Site: `SITE-SUP-000001-01`
- PO: `PO-2026-000001`
- Shipment: `SHP-2026-000001`
- Risk signal: `RSK-SUP-000001-2026-01`
- Supplier score: `SCORE-2026-000001`

IDs are deterministic, zero-padded and never recycled within a generated dataset. Every baseline child row references the exact parent ID used by its parent file.

## Data dictionary
`data_dictionary.csv` documents every generated baseline field with type, required/optional, allowed values where applicable, units, uniqueness, and validation notes. Entity files are named in plural form. Key rules:
- `*_id` primary IDs are unique within their entity file.
- Foreign-key IDs must match an existing parent ID exactly.
- Dates use ISO `YYYY-MM-DD`; timestamps use ISO-8601 UTC offsets.
- Monetary values use USD; rates/score components are numeric 0–100 unless explicitly documented as a ratio such as `defect_rate` (0–1).
- Status/risk/result fields use the enum values in the generator's `ENUMS` mapping.
- Optional `valid_to` is blank for open-ended site validity; challenge records may intentionally omit required values.

## Generation order and business rules
1. Supplier master records.
2. Supplier-owned sites and effective-dated contracts.
3. Purchase orders and PO lines; PO expected date cannot precede order date, and line total is quantity × unit price.
4. Shipments; each baseline shipment references a valid PO and matches its supplier/site. Delays are derived from dispatch/delivery dates.
5. Delivery events; every event references a generated shipment and carries an event timestamp.
6. Incoming inspections; defect rate is between 0 and 1 and result is derived from defect thresholds.
7. Invoices; variance amount = invoice amount − PO amount and variance percentage is derived from those amounts.
8. Risk signals; observation and expiry timestamps are ordered. Some suppliers intentionally have no risk observation to represent missing performance data; score calculation uses an explicit neutral fallback for a missing component.
9. Supplier score snapshots; score components and the weighted reliability score are stored with an explanation.
10. State events and validation artifacts.

The score formula is `delivery*0.25 + quality*0.25 + cost*0.15 + capacity*0.15 + compliance*0.10 + disruption*0.10`. Bands: `<60 HIGH_RISK`, `60–<80 WATCH`, `>=80 RELIABLE`. Delivery is based on on-time delivery against PO expected dates among shipments with delivery dates on/before the score cutoff; quality is based on inspection defect rate; cost is based on mean absolute invoice variance percentage; capacity is the share of eligible shipments marked delivered, and compliance is a failed-inspection proxy; neither is audited production evidence. When a component has no observations, the generator uses a neutral fallback of 75 and records the component explanation.

## Deliberate anomaly challenge set
Baseline files are valid. `challenge_shipments.csv`, `challenge_risk_signals.csv`, and `challenge_supplier_scores.csv` hold the intentional anomalies. `ground_truth.csv/json` maps each challenge entity ID to `issue_type`, expected detection, and label:
- `SHIPMENT_WITHOUT_PO`
- `DELIVERY_BEFORE_DISPATCH`
- `FUTURE_DATA_LEAKAGE`
- `MISSING_RISK_SOURCE_OR_TIME`
- `SUPPLIER_SITE_MISMATCH`

Do not join ground truth into model input features. Use it only after validation/scoring to measure detection performance.

## Exports and validation
The generator exports CSV and JSON per baseline entity, `seed_data.sql` (portable JSON staging seed table), data dictionary, ground truth CSV/JSON, source manifest with SHA-256 hashes, and `validation_summary.csv`. Checks include primary-key uniqueness, parent FK integrity, supplier/site ownership, date ordering, inspection bounds, invoice/PO arithmetic reconciliation, PO line totals, and score-weight sum. A failed validation exits nonzero.

The SQL file inserts into `synthetic_seed_records`, a staging table, rather than guessing the names/columns of every production table. Map/import staging payloads into the relational schema only after reviewing the target schema. Existing application data is not dropped or truncated.

## Acceptance verification and provenance

Run the acceptance suite from the `SupplierIQ_Java` project root after generating the full dataset:

```powershell
python tools/verify_acceptance.py data/full
```

The script writes `data/full/acceptance_report.csv` and exits nonzero if any acceptance check fails. It checks target counts, distribution variation, IDs and joins, lifecycle/date consistency, derived invoice/PO/inspection/score values, isolated anomaly coverage, score explanation and cutoff, and source-manifest hashes. Generate a second dataset with the same seed and compare it using `tools/verify_reproducibility.py`.

**Provenance:** all names, identifiers, events, risk signals, and amounts in this dataset are artificially generated for testing. No real supplier data, real personal data, or confidential procurement records are used. The generator uses Python's standard library and a fixed seed; it makes no network calls and imports no third-party dataset. The SHA-256 manifest supports integrity checking, not proof of authorship by itself.

**Metric definitions:** delivery score is the percentage of eligible shipments delivered on or before the PO expected date among shipments with delivery dates on/before the score cutoff; its denominator is the number of those eligible shipments. Quality score is `100 × (1 − mean defect_rate)` across inspections dated on/before the cutoff; its denominator is the number of those eligible inspections. Cost uses the mean absolute invoice variance percentage, capacity uses delivered-status shipment share, compliance uses the failed-inspection proxy, and disruption subtracts severity penalties from risk signals observed on/before the cutoff. These are synthetic proxy metrics, not audited supplier KPIs. The score snapshot uses `2026-10-22` as its fixed `as_of_date`; later-dated source observations are excluded from its measured components. The score explanation includes component weights, cutoff, observation denominators, and the neutral fallback used when no observations are available.
