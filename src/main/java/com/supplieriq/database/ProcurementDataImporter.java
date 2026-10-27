package com.supplieriq.database;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** Imports generated procurement CSVs in FK-safe order. Invalid challenge rows are recorded, never silently loaded. */
@Component @Order(3)
public class ProcurementDataImporter implements CommandLineRunner {
 private final JdbcTemplate db;
 public ProcurementDataImporter(JdbcTemplate db){this.db=db;}
 @Override public void run(String... args) throws Exception {
  List<Map<String,String>> suppliers=csv("suppliers.csv"), sites=csv("sites.csv"), contracts=csv("contracts.csv"), pos=csv("purchase_orders.csv");
  // Load documented expected anomalies first so every detected issue can be labeled against ground truth.
  for(var r:csv("ground_truth.csv")) db.update("INSERT IGNORE INTO ground_truth(truth_id,entity_type,entity_id,anomaly_type,expected_detection,rule_description) VALUES(?,?,?,?,?,?)",r.get("truth_id"),r.get("entity_type"),r.get("entity_id"),r.get("anomaly_type"),Boolean.parseBoolean(r.get("expected_detection")),r.get("rule_description"));
  for(var r:suppliers) db.update("INSERT IGNORE INTO supplier_master(supplier_id,supplier_name,category,country,status) VALUES(?,?,?,?,?)",r.get("supplier_id"),r.get("name"),r.get("category"),r.get("country"),r.get("status"));
  Set<String> supplierIds=set("SELECT supplier_id FROM supplier_master");
  for(var r:sites) {if(supplierIds.contains(r.get("supplier_id"))) {db.update("INSERT IGNORE INTO sites(site_id,supplier_id,site_name,city,country,status) VALUES(?,?,?,?,?,?)",r.get("site_id"),r.get("supplier_id"),r.get("site_name"),r.get("city"),r.get("country"),r.get("status")); db.update("INSERT IGNORE INTO supplier_site_assignments(supplier_id,site_id,effective_from) VALUES(?,?,?)",r.get("supplier_id"),r.get("site_id"),"2025-01-01");} else issue("SITE_WITHOUT_SUPPLIER","Site",r.get("site_id"),"Site references an unknown supplier",false);}
  for(var r:contracts) {if(supplierIds.contains(r.get("supplier_id")) && !LocalDate.parse(r.get("end_date")).isBefore(LocalDate.parse(r.get("start_date")))) db.update("INSERT IGNORE INTO contracts(contract_id,supplier_id,start_date,end_date,status,currency) VALUES(?,?,?,?,?,?)",r.get("contract_id"),r.get("supplier_id"),r.get("start_date"),r.get("end_date"),r.get("status"),r.get("currency")); else issue("CONTRACT_INVALID","Contract",r.get("contract_id"),"Contract must reference a valid supplier and end date must not precede start date",false);}
  Set<String> siteIds=set("SELECT site_id FROM sites"), contractIds=set("SELECT contract_id FROM contracts");
  Map<String,String> contractOwners=new HashMap<>();db.query("SELECT contract_id,supplier_id FROM contracts",rs->{contractOwners.put(rs.getString(1),rs.getString(2));});
  Map<String,String> siteOwnersForPo=new HashMap<>();db.query("SELECT site_id,supplier_id FROM sites",rs->{siteOwnersForPo.put(rs.getString(1),rs.getString(2));});
  for(var r:pos) {if(supplierIds.contains(r.get("supplier_id"))&&siteIds.contains(r.get("site_id"))&&contractIds.contains(r.get("contract_id"))&&Objects.equals(r.get("supplier_id"),siteOwnersForPo.get(r.get("site_id")))&&Objects.equals(r.get("supplier_id"),contractOwners.get(r.get("contract_id")))&&contractCovers(r.get("contract_id"),r.get("order_date"))){db.update("INSERT IGNORE INTO purchase_orders(po_id,supplier_id,site_id,contract_id,order_date,expected_date,status,total_amount,currency) VALUES(?,?,?,?,?,?,?,?,?)",r.get("po_id"),r.get("supplier_id"),r.get("site_id"),r.get("contract_id"),r.get("order_date"),r.get("expected_date"),r.get("status"),r.get("total_amount"),r.get("currency"));}else issue("INVALID_PO_RELATIONSHIP","PurchaseOrder",r.get("po_id"),"PO references an unknown/mismatched supplier, site, contract, or a contract not effective on order date",false);}
  for(var r:csv("po_lines.csv")){String item=r.get("item_code");if(exists("SELECT COUNT(*) FROM purchase_orders WHERE po_id=?",r.get("po_id"))){db.update("INSERT IGNORE INTO item_master(item_code) VALUES(?)",item);db.update("INSERT IGNORE INTO po_lines(po_line_id,po_id,line_number,item_code,quantity,unit_price) VALUES(?,?,?,?,?,?)",r.get("po_line_id"),r.get("po_id"),r.get("line_number"),r.get("item_code"),r.get("quantity"),r.get("unit_price"));String cid=db.queryForObject("SELECT contract_id FROM purchase_orders WHERE po_id=?",String.class,r.get("po_id"));db.update("INSERT IGNORE INTO contract_items(contract_id,item_code,effective_from) VALUES(?,?,?)",cid,item,"2025-01-01");}else issue("PO_LINE_WITHOUT_PO","POLine",r.get("po_line_id"),"PO line references an unknown purchase order",false);}
  Set<String> poIds=set("SELECT po_id FROM purchase_orders"); Map<String,String> owners=new HashMap<>(), poOwners=new HashMap<>();db.query("SELECT site_id,supplier_id FROM sites",rs->{owners.put(rs.getString(1),rs.getString(2));});db.query("SELECT po_id,supplier_id FROM purchase_orders",rs->{poOwners.put(rs.getString(1),rs.getString(2));});
  for(var r:csv("shipments.csv")){String id=r.get("shipment_id"),po=r.get("po_id"),sid=r.get("supplier_id"),site=r.get("site_id"); if(po==null||po.isBlank()){issue("SHIPMENT_WITHOUT_PO","Shipment",id,"Shipment must reference an existing purchase order",true);continue;} if(!poIds.contains(po)){issue("UNKNOWN_PURCHASE_ORDER","Shipment",id,"Shipment references unknown PO",false);continue;} if(!Objects.equals(sid,owners.get(site))||!Objects.equals(sid,poOwners.get(po))){issue("SUPPLIER_SITE_MISMATCH","Shipment",id,"Shipment supplier does not own its site",true);continue;} if(!r.get("delivery_date").isBlank()&&LocalDate.parse(r.get("delivery_date")).isBefore(LocalDate.parse(r.get("dispatch_date")))){issue("DELIVERY_BEFORE_DISPATCH","Shipment",id,"Delivery date is before dispatch date",true);continue;} db.update("INSERT IGNORE INTO shipments(shipment_id,po_id,supplier_id,site_id,dispatch_date,delivery_date,status) VALUES(?,?,?,?,?,?,?)",id,po,sid,site,r.get("dispatch_date"),nullableDate(r.get("delivery_date")),r.get("status")); }
  Set<String> shipmentIds=set("SELECT shipment_id FROM shipments");
  for(var r:csv("delivery_events.csv")) {if(shipmentIds.contains(r.get("shipment_id"))) {db.update("INSERT IGNORE INTO delivery_events(event_id,shipment_id,event_type,event_at,source) VALUES(?,?,?,?,?)",r.get("event_id"),r.get("shipment_id"),r.get("event_type"),Timestamp.valueOf(LocalDate.parse(r.get("event_date")).atStartOfDay()),r.get("source"));} else issue("DELIVERY_EVENT_WITHOUT_SHIPMENT","DeliveryEvent",r.get("event_id"),"Delivery event references a shipment that was not validly imported",false);}
  for(var r:csv("inspections.csv")){if(poIds.contains(r.get("po_id"))&&supplierIds.contains(r.get("supplier_id"))&&Objects.equals(poOwners.get(r.get("po_id")),r.get("supplier_id"))) db.update("INSERT IGNORE INTO inspections(inspection_id,po_id,supplier_id,inspection_date,result,defect_rate) VALUES(?,?,?,?,?,?)",r.get("inspection_id"),r.get("po_id"),r.get("supplier_id"),r.get("inspection_date"),r.get("result"),r.get("defect_rate")); else issue("INSPECTION_RELATIONSHIP_INVALID","Inspection",r.get("inspection_id"),"Inspection must reference a valid PO belonging to the same supplier",false);}
  for(var r:csv("invoices.csv")){if(poIds.contains(r.get("po_id"))&&supplierIds.contains(r.get("supplier_id"))&&Objects.equals(poOwners.get(r.get("po_id")),r.get("supplier_id"))){double total=db.queryForObject("SELECT total_amount FROM purchase_orders WHERE po_id=?",Double.class,r.get("po_id"));double amount=Double.parseDouble(r.get("amount"));db.update("INSERT IGNORE INTO invoices(invoice_id,po_id,supplier_id,invoice_date,amount,currency,status,variance_amount) VALUES(?,?,?,?,?,?,?,?)",r.get("invoice_id"),r.get("po_id"),r.get("supplier_id"),r.get("invoice_date"),amount,r.get("currency"),r.get("status"),amount-total);}else issue("INVOICE_RELATIONSHIP_INVALID","Invoice",r.get("invoice_id"),"Invoice must reference a valid PO belonging to the same supplier",false);}
  for(var r:csv("risk_signals.csv")){if(r.getOrDefault("source","").isBlank()||r.getOrDefault("observed_at","").isBlank()){issue("MISSING_SOURCE_OR_TIME","RiskSignal",r.get("risk_signal_id"),"Risk signal requires source and observed timestamp",true);continue;}if(supplierIds.contains(r.get("supplier_id"))) db.update("INSERT IGNORE INTO risk_signals(risk_signal_id,supplier_id,signal_type,severity,observed_at,source,description) VALUES(?,?,?,?,?,?,?)",r.get("risk_signal_id"),r.get("supplier_id"),r.get("signal_type"),r.get("severity"),Timestamp.valueOf(LocalDate.parse(r.get("observed_at")).atStartOfDay()),r.get("source"),r.get("description")); else issue("RISK_SIGNAL_UNKNOWN_SUPPLIER","RiskSignal",r.get("risk_signal_id"),"Risk signal references an unknown supplier",false);}
  for(var r:csv("state_events.csv")){String et=r.get("entity_type"),eid=r.get("entity_id"); boolean validEntity="PurchaseOrder".equals(et)&&poIds.contains(eid); if(!validEntity){issue("STATE_EVENT_UNKNOWN_ENTITY","StateEvent",r.get("state_event_id"),"State event references an unknown or invalid entity",false);continue;} Timestamp at=Timestamp.valueOf(LocalDate.parse(r.get("event_date")).atStartOfDay()); db.update("INSERT IGNORE INTO state_events(state_event_id,entity_type,entity_id,from_state,to_state,event_at,actor,reason) VALUES(?,?,?,?,?,?,?,?)",r.get("state_event_id"),et,eid,r.get("from_state"),r.get("to_state"),at,r.get("actor"),"Imported generated lifecycle history"); db.update("UPDATE entity_lifecycle SET effective_to=? WHERE entity_type=? AND entity_id=? AND effective_to IS NULL AND effective_from<?",at,et,eid,at); db.update("INSERT IGNORE INTO entity_lifecycle(entity_type,entity_id,lifecycle_state,effective_from) VALUES(?,?,?,?)",et,eid,r.get("to_state"),at);}
  // Rule 3: derive explainable supplier scores from imported operational facts, not random CSV component scores.
  recomputeSupplierScores();
  // Reconcile prior issue rows against the authoritative ground-truth table on every restart.
  db.update("UPDATE data_quality_issues q LEFT JOIN ground_truth g ON g.entity_type=q.entity_type AND g.entity_id=q.entity_id AND g.anomaly_type=q.issue_type AND g.expected_detection=TRUE SET q.ground_truth=IF(g.truth_id IS NULL,FALSE,TRUE)");
  // Rule 4: import immutable state-change events and maintain a current lifecycle projection.
  // Existing state_events are append-only via INSERT IGNORE; lifecycle projection is updated only when a newer event arrives.
  System.out.println("Procurement import complete. Valid relational rows loaded; intentional invalid rows recorded in data_quality_issues.");
 }
 private void issue(String type,String entity,String id,String message,boolean ignoredTruth){
  boolean truth=false;
  if(id!=null) { Integer n=db.queryForObject("SELECT COUNT(*) FROM ground_truth WHERE entity_type=? AND entity_id=? AND anomaly_type=? AND expected_detection=TRUE",Integer.class,entity,id,type); truth=n!=null&&n>0; }
  db.update("INSERT IGNORE INTO data_quality_issues(issue_type,entity_type,entity_id,issue_message,ground_truth) VALUES(?,?,?,?,?)",type,entity,id==null?"UNKNOWN":id,message,truth);
 }
 private boolean contractCovers(String contractId,String orderDate){
  if(contractId==null||orderDate==null||orderDate.isBlank())return false;
  Integer n=db.queryForObject("SELECT COUNT(*) FROM contracts WHERE contract_id=? AND start_date<=? AND end_date>=?",Integer.class,contractId,orderDate,orderDate);
  return n!=null&&n>0;
 }
 private void recomputeSupplierScores(){
  List<String> ids=db.queryForList("SELECT supplier_id FROM supplier_master",String.class);
  // Fixed, documented snapshot date prevents future-observation leakage and makes reruns comparable.
  LocalDate asOf=LocalDate.of(2026,10,22);
  String cutoff=asOf.toString();
  for(String sid:ids){
   String deliverySql="SELECT COALESCE(100.0*SUM(CASE WHEN s.delivery_date<=p.expected_date THEN 1 ELSE 0 END)/NULLIF(COUNT(s.shipment_id),0),75) FROM shipments s JOIN purchase_orders p ON p.po_id=s.po_id WHERE s.supplier_id=? AND s.delivery_date IS NOT NULL AND s.delivery_date<='"+cutoff+"'";
   String qualitySql="SELECT COALESCE(100.0*(1-AVG(i.defect_rate)),75) FROM inspections i WHERE i.supplier_id=? AND i.inspection_date<='"+cutoff+"'";
   String costSql="SELECT COALESCE(100.0-GREATEST(0,LEAST(100,100.0*AVG(ABS(i.variance_amount)/NULLIF(p.total_amount,0)))),75) FROM invoices i JOIN purchase_orders p ON p.po_id=i.po_id WHERE i.supplier_id=? AND i.invoice_date<='"+cutoff+"'";
   String capacitySql="SELECT COALESCE(100.0*SUM(CASE WHEN s.status='DELIVERED' THEN 1 ELSE 0 END)/NULLIF(COUNT(s.shipment_id),0),75) FROM shipments s WHERE s.supplier_id=? AND s.delivery_date IS NOT NULL AND s.delivery_date<='"+cutoff+"'";
   String complianceSql="SELECT COALESCE(GREATEST(0,100-20*SUM(CASE WHEN i.result='FAIL' THEN 1 ELSE 0 END)),75) FROM inspections i WHERE i.supplier_id=? AND i.inspection_date<='"+cutoff+"'";
   String disruptionSql="SELECT COALESCE(GREATEST(0,100-SUM(CASE WHEN r.severity='LOW' THEN 5 WHEN r.severity='MEDIUM' THEN 15 WHEN r.severity='HIGH' THEN 30 WHEN r.severity='CRITICAL' THEN 50 ELSE 0 END)),75) FROM risk_signals r WHERE r.supplier_id=? AND r.observed_at<='"+cutoff+" 23:59:59'";
   double delivery=clamp(metric(deliverySql,sid)), quality=clamp(metric(qualitySql,sid)), cost=clamp(metric(costSql,sid));
   double capacity=clamp(metric(capacitySql,sid)), compliance=clamp(metric(complianceSql,sid)), disruption=clamp(metric(disruptionSql,sid));
   int deliveryN=count("SELECT COUNT(*) FROM shipments WHERE supplier_id=? AND delivery_date IS NOT NULL AND delivery_date<=?",sid,java.sql.Date.valueOf(asOf));
   int qualityN=count("SELECT COUNT(*) FROM inspections WHERE supplier_id=? AND inspection_date<=?",sid,java.sql.Date.valueOf(asOf));
   int costN=count("SELECT COUNT(*) FROM invoices WHERE supplier_id=? AND invoice_date<=?",sid,java.sql.Date.valueOf(asOf));
   int capacityN=count("SELECT COUNT(*) FROM shipments WHERE supplier_id=? AND delivery_date IS NOT NULL AND delivery_date<=?",sid,java.sql.Date.valueOf(asOf));
   int complianceN=qualityN;
   int disruptionN=count("SELECT COUNT(*) FROM risk_signals WHERE supplier_id=? AND observed_at<=?",sid,Timestamp.valueOf(asOf.atTime(23,59,59)));
   double score=delivery*.25+quality*.25+cost*.15+capacity*.15+compliance*.10+disruption*.10;
   String band=score<60?"HIGH_RISK":score<80?"WATCH":"RELIABLE";
   String explanation=String.format(Locale.US,
    "{\"method\":\"operational rules v2\",\"as_of_date\":\"%s\",\"weights\":{\"delivery\":0.25,\"quality\":0.25,\"cost\":0.15,\"capacity\":0.15,\"compliance\":0.10,\"disruption\":0.10},\"components\":{\"delivery\":%.2f,\"quality\":%.2f,\"cost\":%.2f,\"capacity\":%.2f,\"compliance\":%.2f,\"disruption\":%.2f},\"observations\":{\"shipments\":%d,\"delivery_eligible_denominator\":%d,\"inspections\":%d,\"quality_eligible_denominator\":%d,\"invoices\":%d,\"risk_signals\":%d},\"missing_data\":{\"delivery\":%s,\"quality\":%s,\"cost\":%s,\"capacity\":%s,\"compliance\":%s,\"disruption\":%s},\"fallback\":75.0}",
    cutoff,delivery,quality,cost,capacity,compliance,disruption,deliveryN,deliveryN,qualityN,qualityN,costN,disruptionN,
    deliveryN==0,qualityN==0,costN==0,capacityN==0,complianceN==0,disruptionN==0);
   Integer exists=db.queryForObject("SELECT COUNT(*) FROM supplier_score_history WHERE supplier_id=? AND as_of_date=?",Integer.class,sid,java.sql.Date.valueOf(asOf));
   if(exists==null||exists==0) db.update("INSERT INTO supplier_score_history(supplier_id,as_of_date,delivery_score,quality_score,cost_score,capacity_score,compliance_score,disruption_score,reliability_score,risk_band,explanation) VALUES(?,?,?,?,?,?,?,?,?,?,CAST(? AS JSON))",sid,java.sql.Date.valueOf(asOf),delivery,quality,cost,capacity,compliance,disruption,score,band,explanation);
   db.update("UPDATE supplier_performance SET as_of_date=?,delivery_score=?,quality_score=?,cost_score=?,capacity_score=?,compliance_score=?,disruption_score=?,reliability_score=?,risk_band=? WHERE supplier_id=?",java.sql.Date.valueOf(asOf),delivery,quality,cost,capacity,compliance,disruption,score,band,sid);
  }
 }
 private int count(String sql,String sid,Object date){Integer n=db.queryForObject(sql,Integer.class,sid,date);return n==null?0:n;}

 private double metric(String sql,String sid){Double n=db.queryForObject(sql,Double.class,sid);return n==null?75:n;}
 private double clamp(double v){return Math.max(0,Math.min(100,v));}

 private boolean exists(String sql,Object... args){Integer n=db.queryForObject(sql,Integer.class,args);return n!=null&&n>0;}
 private Set<String> set(String sql){return new HashSet<>(db.queryForList(sql,String.class));}
 private Object nullableDate(String s){return s==null||s.isBlank()?null:java.sql.Date.valueOf(s);}
 private List<Map<String,String>> csv(String name)throws Exception{Path p=Path.of("data",name);if(!Files.exists(p))return List.of();List<String> lines=Files.readAllLines(p,StandardCharsets.UTF_8);if(lines.isEmpty())return List.of();String[] h=parse(lines.get(0));List<Map<String,String>> out=new ArrayList<>();for(int i=1;i<lines.size();i++){if(lines.get(i).isBlank())continue;String[] v=parse(lines.get(i));Map<String,String> m=new LinkedHashMap<>();for(int j=0;j<h.length;j++)m.put(h[j],j<v.length?v[j]:"");out.add(m);}return out;}
 private String[] parse(String line){List<String>a=new ArrayList<>();StringBuilder b=new StringBuilder();boolean q=false;for(int i=0;i<line.length();i++){char c=line.charAt(i);if(c=='"'){if(q&&i+1<line.length()&&line.charAt(i+1)=='"'){b.append('"');i++;}else q=!q;}else if(c==','&&!q){a.add(b.toString());b.setLength(0);}else b.append(c);}a.add(b.toString());return a.toArray(String[]::new);}
}
