# SupplierIQ relational model and business rules

## Entity keys and cardinality
- `supplier_master(supplier_id PK)` 1:N `sites`, `contracts`, `purchase_orders`, `risk_signals`, `supplier_score_history`, `inspections`, `invoices`.
- `sites(site_id PK, supplier_id FK)` belongs to exactly one supplier; a supplier may have zero or many sites.
- `contracts(contract_id PK, supplier_id FK)` belongs to exactly one supplier; a supplier may have zero or many contracts. `end_date >= start_date`.
- `purchase_orders(po_id PK)` references exactly one supplier, site, and contract. Each parent can be referenced by many POs. `expected_date >= order_date`, amount cannot be negative. Application import checks site ownership and contract/supplier relationship before loading.
- `po_lines(po_line_id PK, po_id FK)` is 1:N from PO to lines. `(po_id,line_number)` is unique. Each line references one item code; `item_master` catalogs item codes and `contract_items` bridges Contract N:M Item with effective dates.
- `shipments(shipment_id PK)` reference a PO, supplier and site. A PO can have zero or many shipments. Supplier must own the site; delivery cannot precede dispatch. Invalid deliberate cases are recorded in `data_quality_issues` and are not inserted into constrained operational tables.
- `delivery_events(event_id PK, shipment_id FK)` is 1:N from shipment to events; event timestamp/source are required.
- `inspections(inspection_id PK)` and `invoices(invoice_id PK)` each reference one PO and supplier; a PO can have many inspections/invoices. Invoice variance is calculated as invoice amount minus PO amount.
- `risk_signals(risk_signal_id PK)` references one supplier; a supplier can have many timestamped signals. Missing timestamp/source is recorded as an issue and not inserted as an operational risk signal.
- `supplier_score_history(score_history_id PK)` stores append-only score snapshots for suppliers. One supplier has many dated score snapshots; each includes component values, final score, risk band and JSON explanation.
- `state_events(state_event_id PK)` is append-only lifecycle history. `entity_lifecycle` records effective state intervals. Generic entity references are intentionally not a polymorphic FK; importer validates known PurchaseOrder event IDs before storing lifecycle intervals.
- `ground_truth(truth_id PK)` labels known challenge anomalies; `data_quality_issues` stores `issue_type`, entity, message, detected time and whether the issue is expected ground truth.
- `supplier_site_assignments` bridges Supplier N:M Site over effective dates. `contract_items` bridges Contract N:M Item over effective dates. In the present business rules, a site has one owning supplier, so supplier-site assignments capture dated assignment history but must agree with the site's owner.

## Optional relationships and lifecycle
- Supplier may have no sites, contracts, orders, risk signals or score history yet.
- PO may have no lines, shipments, inspections or invoices while newly created.
- Shipment delivery date is nullable while in transit. Delivery events and risk-signal observations are timestamped.
- Effective end dates are nullable for currently active assignments/lifecycle intervals. Where present, end date must not precede start date.
- Typical states: Supplier `ACTIVE/UNDER_REVIEW/INACTIVE`; Site `ACTIVE/INACTIVE`; Contract `DRAFT/ACTIVE/EXPIRED/TERMINATED`; PO `OPEN/CLOSED/CANCELLED`; Shipment `IN_TRANSIT/DELIVERED/DELAYED`; Invoice `PENDING/PAID/DISPUTED`.

## Score rule
`reliability = delivery*0.25 + quality*0.25 + cost*0.15 + capacity*0.15 + compliance*0.10 + disruption*0.10`.
Risk band: `<60 HIGH_RISK`, `60.. <80 WATCH`, `>=80 RELIABLE`. Score snapshots are retained with weights in their JSON explanation. The current generator creates synthetic component inputs; the score is explainable but the components are not yet inferred from raw operational outcomes.

## Import and anomaly handling
On startup, CSVs are imported in parent-first order. `INSERT IGNORE` makes imports restart-safe and does not delete existing supplier rows. Invalid intended anomaly rows are not inserted into tables with required FKs; they are represented in `data_quality_issues` and `ground_truth`. Run `GET /api/exceptions` for the CSV challenge list, and query `data_quality_issues` to see which were blocked during relational import.
