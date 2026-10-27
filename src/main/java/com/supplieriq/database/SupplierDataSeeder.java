package com.supplieriq.database;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Date;
import java.util.*;
import java.util.stream.Collectors;

@Component
@Order(2)
public class SupplierDataSeeder implements CommandLineRunner {
    private final JdbcTemplate jdbc;
    public SupplierDataSeeder(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public void run(String... args) throws Exception {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM supplier_performance", Integer.class);
        if (count != null && count > 0) {
            syncSupplierMaster();
            System.out.println("MySQL already contains " + count + " supplier records; keeping existing dataset.");
            return;
        }
        List<Map<String,String>> suppliers = readCsv("suppliers.csv");
        Map<String,Map<String,String>> scores = readCsv("supplier_scores.csv").stream()
                .collect(Collectors.toMap(r -> r.get("supplier_id"), r -> r, (a,b) -> a));
        String sql = "INSERT INTO supplier_performance (supplier_id,name,category,country,status,created_date,as_of_date," +
                "delivery_score,quality_score,cost_score,capacity_score,compliance_score,disruption_score,reliability_score,risk_band,available_components) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        int inserted = 0;
        for (Map<String,String> s : suppliers) {
            Map<String,String> sc = scores.get(s.get("supplier_id"));
            if (sc == null) continue;
            jdbc.update(sql, s.get("supplier_id"), s.get("name"), s.get("category"), s.get("country"), s.get("status"),
                    Date.valueOf(s.get("created_date")), Date.valueOf(sc.get("as_of_date")),
                    decimal(sc,"delivery_score"), decimal(sc,"quality_score"), decimal(sc,"cost_score"),
                    decimal(sc,"capacity_score"), decimal(sc,"compliance_score"), decimal(sc,"disruption_score"),
                    decimal(sc,"reliability_score"), sc.get("risk_band"), Integer.parseInt(sc.get("available_components")));
            inserted++;
        }
        syncSupplierMaster();
        System.out.println("Seeded " + inserted + " synthetic supplier records into MySQL table supplier_performance.");
    }

    private void syncSupplierMaster() {
        jdbc.update("INSERT IGNORE INTO supplier_master (supplier_id,supplier_name,category,country,status) SELECT supplier_id,name,category,country,status FROM supplier_performance");
    }

    private double decimal(Map<String,String> row, String key) { return Double.parseDouble(row.get(key)); }
    private List<Map<String,String>> readCsv(String name) throws IOException {
        Path path = Path.of("data", name);
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.isEmpty()) return List.of();
        String[] headers = parse(lines.get(0));
        List<Map<String,String>> rows = new ArrayList<>();
        for (int i=1;i<lines.size();i++) {
            if (lines.get(i).isBlank()) continue;
            String[] values = parse(lines.get(i));
            Map<String,String> row = new LinkedHashMap<>();
            for (int j=0;j<headers.length;j++) row.put(headers[j], j<values.length ? values[j] : "");
            rows.add(row);
        }
        return rows;
    }
    private String[] parse(String line) {
        List<String> values = new ArrayList<>(); StringBuilder value = new StringBuilder(); boolean quoted = false;
        for (int i=0;i<line.length();i++) { char c=line.charAt(i);
            if (c=='"') { if (quoted && i+1<line.length() && line.charAt(i+1)=='"') { value.append('"'); i++; } else quoted=!quoted; }
            else if (c==',' && !quoted) { values.add(value.toString()); value.setLength(0); } else value.append(c);
        }
        values.add(value.toString()); return values.toArray(String[]::new);
    }
}
