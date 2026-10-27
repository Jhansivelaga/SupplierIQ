-- Run in MySQL Workbench after starting SupplierIQ.
USE supplieriq;

-- 1. Dataset size
SELECT COUNT(*) AS supplier_count FROM supplier_performance;

-- 2. Highest reliability scores
SELECT supplier_id, name, category, country, reliability_score, risk_band
FROM supplier_performance
ORDER BY reliability_score DESC
LIMIT 10;

-- 3. Risk distribution
SELECT risk_band, COUNT(*) AS supplier_count
FROM supplier_performance
GROUP BY risk_band
ORDER BY supplier_count DESC;

-- 4. Suppliers that need attention
SELECT supplier_id, name, reliability_score, delivery_score, quality_score, risk_band
FROM supplier_performance
WHERE risk_band IN ('HIGH_RISK', 'WATCH')
ORDER BY reliability_score ASC;
