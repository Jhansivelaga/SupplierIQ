package com.supplieriq.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
public class SupplierRepository {
    private final JdbcTemplate jdbc;
    public SupplierRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Map<String, Object>> findSuppliers(String query, String risk) {
        String q = query == null ? "" : query.trim();
        String r = risk == null || risk.isBlank() ? "ALL" : risk.trim().toUpperCase(Locale.ROOT);
        String sql = "SELECT supplier_id, name, category, country, status, created_date, as_of_date, " +
                "delivery_score, quality_score, cost_score, capacity_score, compliance_score, disruption_score, " +
                "reliability_score, CASE WHEN reliability_score < 60 THEN 'HIGH_RISK' " +
                "WHEN reliability_score < 80 THEN 'WATCH' ELSE 'RELIABLE' END AS risk_band, available_components " +
                "FROM supplier_performance " +
                "WHERE (? = '' OR LOWER(name) LIKE LOWER(?) OR LOWER(supplier_id) LIKE LOWER(?)) " +
                "AND (? = 'ALL' OR (CASE WHEN reliability_score < 60 THEN 'HIGH_RISK' " +
                "WHEN reliability_score < 80 THEN 'WATCH' ELSE 'RELIABLE' END) = ?) " +
                "ORDER BY reliability_score DESC LIMIT 500";
        String like = "%" + q + "%";
        return jdbc.queryForList(sql, q, like, like, r, r);
    }

    public Optional<Map<String, Object>> findById(String id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM supplier_performance WHERE supplier_id = ?", id);
        return rows.stream().findFirst();
    }

    public Map<String, Object> summary() {
        return jdbc.queryForMap("SELECT COUNT(*) AS supplierCount, " +
                "COALESCE(SUM(risk_band='HIGH_RISK'),0) AS highRiskCount, " +
                "COALESCE(SUM(risk_band='WATCH'),0) AS watchCount, " +
                "COALESCE(SUM(risk_band='RELIABLE'),0) AS reliableCount, " +
                "COALESCE(AVG(reliability_score),0) AS averageScore FROM supplier_performance");
    }

    public int count() { return jdbc.queryForObject("SELECT COUNT(*) FROM supplier_performance", Integer.class); }
}
