CREATE TABLE IF NOT EXISTS supplier_performance (
  supplier_id VARCHAR(20) PRIMARY KEY,
  name VARCHAR(120) NOT NULL,
  category VARCHAR(80) NOT NULL,
  country VARCHAR(80) NOT NULL,
  status VARCHAR(40) NOT NULL,
  created_date DATE NOT NULL,
  as_of_date DATE NOT NULL,
  delivery_score DECIMAL(5,2) NOT NULL,
  quality_score DECIMAL(5,2) NOT NULL,
  cost_score DECIMAL(5,2) NOT NULL,
  capacity_score DECIMAL(5,2) NOT NULL,
  compliance_score DECIMAL(5,2) NOT NULL,
  disruption_score DECIMAL(5,2) NOT NULL,
  reliability_score DECIMAL(5,2) NOT NULL,
  risk_band VARCHAR(20) NOT NULL,
  available_components INT NOT NULL,
  CONSTRAINT chk_delivery_score CHECK (delivery_score BETWEEN 0 AND 100),
  CONSTRAINT chk_quality_score CHECK (quality_score BETWEEN 0 AND 100),
  CONSTRAINT chk_reliability_score CHECK (reliability_score BETWEEN 0 AND 100),
  INDEX idx_supplier_risk_band (risk_band),
  INDEX idx_supplier_score (reliability_score)
);

-- Additive procurement model. Existing supplier_performance rows are never deleted.
CREATE TABLE IF NOT EXISTS supplier_master (
  supplier_id VARCHAR(20) PRIMARY KEY,
  supplier_name VARCHAR(120) NOT NULL,
  category VARCHAR(80) NOT NULL,
  country VARCHAR(80) NOT NULL,
  status VARCHAR(40) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT IGNORE INTO supplier_master (supplier_id,supplier_name,category,country,status)
SELECT supplier_id,name,category,country,status FROM supplier_performance;

CREATE TABLE IF NOT EXISTS sites (
  site_id VARCHAR(20) PRIMARY KEY, supplier_id VARCHAR(20) NOT NULL,
  site_name VARCHAR(120) NOT NULL, city VARCHAR(80) NOT NULL, country VARCHAR(80) NOT NULL,
  status VARCHAR(30) NOT NULL,
  CONSTRAINT fk_site_supplier FOREIGN KEY (supplier_id) REFERENCES supplier_master(supplier_id),
  UNIQUE KEY uq_site_owner (site_id,supplier_id)
);
CREATE TABLE IF NOT EXISTS contracts (
  contract_id VARCHAR(20) PRIMARY KEY, supplier_id VARCHAR(20) NOT NULL,
  start_date DATE NOT NULL, end_date DATE NOT NULL, status VARCHAR(30) NOT NULL, currency CHAR(3) NOT NULL,
  CONSTRAINT fk_contract_supplier FOREIGN KEY (supplier_id) REFERENCES supplier_master(supplier_id),
  CONSTRAINT chk_contract_dates CHECK (end_date >= start_date),
  UNIQUE KEY uq_contract_owner (contract_id,supplier_id)
);
CREATE TABLE IF NOT EXISTS purchase_orders (
  po_id VARCHAR(24) PRIMARY KEY, supplier_id VARCHAR(20) NOT NULL, site_id VARCHAR(20) NOT NULL,
  contract_id VARCHAR(20) NOT NULL, order_date DATE NOT NULL, expected_date DATE NOT NULL,
  status VARCHAR(30) NOT NULL, total_amount DECIMAL(14,2) NOT NULL, currency CHAR(3) NOT NULL,
  CONSTRAINT fk_po_supplier FOREIGN KEY (supplier_id) REFERENCES supplier_master(supplier_id),
  CONSTRAINT fk_po_site FOREIGN KEY (site_id) REFERENCES sites(site_id),
  CONSTRAINT fk_po_contract FOREIGN KEY (contract_id) REFERENCES contracts(contract_id),
  CONSTRAINT chk_po_dates CHECK (expected_date >= order_date),
  CONSTRAINT chk_po_amount CHECK (total_amount >= 0)
);
CREATE TABLE IF NOT EXISTS po_lines (
  po_line_id VARCHAR(24) PRIMARY KEY, po_id VARCHAR(24) NOT NULL, line_number INT NOT NULL,
  item_code VARCHAR(40) NOT NULL, quantity DECIMAL(12,3) NOT NULL, unit_price DECIMAL(14,2) NOT NULL,
  UNIQUE KEY uq_po_line (po_id,line_number),
  CONSTRAINT fk_line_po FOREIGN KEY (po_id) REFERENCES purchase_orders(po_id),
  CONSTRAINT chk_line_qty CHECK (quantity > 0), CONSTRAINT chk_line_price CHECK (unit_price >= 0)
);
CREATE TABLE IF NOT EXISTS shipments (
  shipment_id VARCHAR(24) PRIMARY KEY, po_id VARCHAR(24) NOT NULL, supplier_id VARCHAR(20) NOT NULL,
  site_id VARCHAR(20) NOT NULL, dispatch_date DATE NOT NULL, delivery_date DATE NULL, status VARCHAR(30) NOT NULL,
  CONSTRAINT fk_shipment_po FOREIGN KEY (po_id) REFERENCES purchase_orders(po_id),
  CONSTRAINT fk_shipment_supplier FOREIGN KEY (supplier_id) REFERENCES supplier_master(supplier_id),
  CONSTRAINT fk_shipment_site FOREIGN KEY (site_id) REFERENCES sites(site_id),
  INDEX idx_shipment_dates (dispatch_date,delivery_date)
);
CREATE TABLE IF NOT EXISTS delivery_events (
  event_id VARCHAR(24) PRIMARY KEY, shipment_id VARCHAR(24) NOT NULL, event_type VARCHAR(40) NOT NULL,
  event_at TIMESTAMP NOT NULL, source VARCHAR(80) NOT NULL,
  CONSTRAINT fk_delivery_shipment FOREIGN KEY (shipment_id) REFERENCES shipments(shipment_id),
  INDEX idx_delivery_event_time (event_at)
);
CREATE TABLE IF NOT EXISTS inspections (
  inspection_id VARCHAR(24) PRIMARY KEY, po_id VARCHAR(24) NOT NULL, supplier_id VARCHAR(20) NOT NULL,
  inspection_date DATE NOT NULL, result VARCHAR(20) NOT NULL, defect_rate DECIMAL(6,4) NOT NULL,
  CONSTRAINT fk_inspection_po FOREIGN KEY (po_id) REFERENCES purchase_orders(po_id),
  CONSTRAINT fk_inspection_supplier FOREIGN KEY (supplier_id) REFERENCES supplier_master(supplier_id),
  CONSTRAINT chk_defect_rate CHECK (defect_rate BETWEEN 0 AND 1)
);
CREATE TABLE IF NOT EXISTS invoices (
  invoice_id VARCHAR(24) PRIMARY KEY, po_id VARCHAR(24) NOT NULL, supplier_id VARCHAR(20) NOT NULL,
  invoice_date DATE NOT NULL, amount DECIMAL(14,2) NOT NULL, currency CHAR(3) NOT NULL,
  status VARCHAR(30) NOT NULL, variance_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
  CONSTRAINT fk_invoice_po FOREIGN KEY (po_id) REFERENCES purchase_orders(po_id),
  CONSTRAINT fk_invoice_supplier FOREIGN KEY (supplier_id) REFERENCES supplier_master(supplier_id),
  INDEX idx_invoice_variance (variance_amount)
);
CREATE TABLE IF NOT EXISTS risk_signals (
  risk_signal_id VARCHAR(24) PRIMARY KEY, supplier_id VARCHAR(20) NOT NULL, signal_type VARCHAR(40) NOT NULL,
  severity VARCHAR(20) NOT NULL, observed_at TIMESTAMP NOT NULL, source VARCHAR(100) NOT NULL, description VARCHAR(500),
  CONSTRAINT fk_risk_supplier FOREIGN KEY (supplier_id) REFERENCES supplier_master(supplier_id),
  INDEX idx_risk_observed_at (observed_at)
);
CREATE TABLE IF NOT EXISTS supplier_score_history (
  score_history_id BIGINT AUTO_INCREMENT PRIMARY KEY, supplier_id VARCHAR(20) NOT NULL, as_of_date DATE NOT NULL,
  delivery_score DECIMAL(5,2) NOT NULL, quality_score DECIMAL(5,2) NOT NULL, cost_score DECIMAL(5,2) NOT NULL,
  capacity_score DECIMAL(5,2) NOT NULL, compliance_score DECIMAL(5,2) NOT NULL, disruption_score DECIMAL(5,2) NOT NULL,
  reliability_score DECIMAL(5,2) NOT NULL, risk_band VARCHAR(20) NOT NULL, explanation JSON NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_score_history_supplier FOREIGN KEY (supplier_id) REFERENCES supplier_master(supplier_id),
  INDEX idx_score_history_supplier_date (supplier_id,as_of_date)
);
CREATE TABLE IF NOT EXISTS state_events (
  state_event_id VARCHAR(24) PRIMARY KEY, entity_type VARCHAR(40) NOT NULL, entity_id VARCHAR(24) NOT NULL,
  from_state VARCHAR(30), to_state VARCHAR(30) NOT NULL, event_at TIMESTAMP NOT NULL,
  actor VARCHAR(80) NOT NULL, reason VARCHAR(500), INDEX idx_state_entity (entity_type,entity_id,event_at)
);
CREATE TABLE IF NOT EXISTS ground_truth (
  truth_id VARCHAR(24) PRIMARY KEY, entity_type VARCHAR(40) NOT NULL, entity_id VARCHAR(24) NOT NULL,
  anomaly_type VARCHAR(60) NOT NULL, expected_detection BOOLEAN NOT NULL, rule_description VARCHAR(500) NOT NULL,
  generated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_truth_entity (entity_type,entity_id)
);

-- Relationship support, lifecycle/effective dating, anomaly tracking, and validated joins.
CREATE TABLE IF NOT EXISTS item_master (
  item_code VARCHAR(40) PRIMARY KEY, item_name VARCHAR(120) NOT NULL DEFAULT 'Synthetic item', active BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE TABLE IF NOT EXISTS supplier_site_assignments (
  supplier_id VARCHAR(20) NOT NULL, site_id VARCHAR(20) NOT NULL, effective_from DATE NOT NULL, effective_to DATE NULL,
  PRIMARY KEY (supplier_id,site_id,effective_from),
  CONSTRAINT fk_ssa_supplier FOREIGN KEY (supplier_id) REFERENCES supplier_master(supplier_id),
  CONSTRAINT fk_ssa_site FOREIGN KEY (site_id) REFERENCES sites(site_id),
  CONSTRAINT chk_ssa_dates CHECK (effective_to IS NULL OR effective_to >= effective_from)
);
CREATE TABLE IF NOT EXISTS contract_items (
  contract_id VARCHAR(20) NOT NULL, item_code VARCHAR(40) NOT NULL, effective_from DATE NOT NULL, effective_to DATE NULL,
  PRIMARY KEY (contract_id,item_code,effective_from),
  CONSTRAINT fk_ci_contract FOREIGN KEY (contract_id) REFERENCES contracts(contract_id),
  CONSTRAINT fk_ci_item FOREIGN KEY (item_code) REFERENCES item_master(item_code),
  CONSTRAINT chk_ci_dates CHECK (effective_to IS NULL OR effective_to >= effective_from)
);
CREATE TABLE IF NOT EXISTS data_quality_issues (
  issue_id BIGINT AUTO_INCREMENT PRIMARY KEY, issue_type VARCHAR(80) NOT NULL, entity_type VARCHAR(40) NOT NULL,
  entity_id VARCHAR(40) NOT NULL, severity VARCHAR(20) NOT NULL DEFAULT 'ERROR', issue_message VARCHAR(500) NOT NULL,
  ground_truth BOOLEAN NOT NULL DEFAULT FALSE, detected_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_issue_entity_type (issue_type,entity_type,entity_id)
);
CREATE TABLE IF NOT EXISTS entity_lifecycle (
  entity_type VARCHAR(40) NOT NULL, entity_id VARCHAR(40) NOT NULL, lifecycle_state VARCHAR(40) NOT NULL,
  effective_from TIMESTAMP NOT NULL, effective_to TIMESTAMP NULL,
  PRIMARY KEY(entity_type,entity_id,effective_from),
  CONSTRAINT chk_lifecycle_dates CHECK (effective_to IS NULL OR effective_to >= effective_from)
);
