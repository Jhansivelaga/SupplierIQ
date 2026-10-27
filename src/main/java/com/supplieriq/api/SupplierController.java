package com.supplieriq.api;

import org.springframework.web.bind.annotation.*;
import com.supplieriq.repository.SupplierRepository;
import java.io.*;import java.nio.charset.StandardCharsets;import java.nio.file.*;import java.time.LocalDate;import java.util.*;import java.util.stream.*;

@RestController @RequestMapping("/api") @CrossOrigin
public class SupplierController {
 private final SupplierRepository supplierRepository;
 public SupplierController(SupplierRepository supplierRepository) { this.supplierRepository = supplierRepository; }
 private List<Map<String,String>> csv(String name){return csvAt(Path.of("data",name));}
 private List<Map<String,String>> csvAt(Path p){if(!Files.exists(p))return new ArrayList<>();try{List<String> lines=Files.readAllLines(p,StandardCharsets.UTF_8);if(lines.isEmpty())return new ArrayList<>();String[] h=parse(lines.get(0));List<Map<String,String>> out=new ArrayList<>();for(int i=1;i<lines.size();i++){if(lines.get(i).isBlank())continue;String[] v=parse(lines.get(i));Map<String,String> row=new LinkedHashMap<>();for(int j=0;j<h.length;j++)row.put(h[j],j<v.length?v[j]:"");out.add(row);}return out;}catch(IOException e){throw new RuntimeException("Could not read "+p,e);}}
 private String[] parse(String line){List<String>a=new ArrayList<>();StringBuilder b=new StringBuilder();boolean q=false;for(int i=0;i<line.length();i++){char c=line.charAt(i);if(c=='"'){if(q&&i+1<line.length()&&line.charAt(i+1)=='"'){b.append('"');i++;}else q=!q;}else if(c==','&&!q){a.add(b.toString());b.setLength(0);}else b.append(c);}a.add(b.toString());return a.toArray(String[]::new);}
 @GetMapping("/summary") public Map<String,Object> summary(){List<Map<String,String>> p=csv("purchase_orders.csv"),sh=csv("shipments.csv"),in=csv("inspections.csv");Map<String,Object>m=new LinkedHashMap<>(supplierRepository.summary());m.put("purchaseOrderCount",p.size());m.put("shipmentCount",sh.size());m.put("inspectionCount",in.size());return m;}
 @GetMapping("/suppliers") public List<Map<String,Object>> suppliers(@RequestParam(defaultValue="")String q,@RequestParam(defaultValue="ALL")String risk){return supplierRepository.findSuppliers(q,risk);}
 @GetMapping("/suppliers/{id}") public Map<String,Object> supplier(@PathVariable String id){Map<String,Object>s=supplierRepository.findById(id).orElseThrow(()->new NoSuchElementException("Supplier not found: "+id));Map<String,Object>m=new LinkedHashMap<>(s);m.put("score",s);m.put("signals",csv("risk_signals.csv").stream().filter(x->id.equals(x.get("supplier_id"))).toList());return m;}
 @GetMapping("/exceptions") public List<Map<String,String>> exceptions(){
  List<Map<String,String>> out=new ArrayList<>();
  Map<String,String> siteOwners=csv("sites.csv").stream().collect(Collectors.toMap(x->x.get("site_id"),x->x.get("supplier_id"),(a,b)->a));
  Set<String> poIds=csv("purchase_orders.csv").stream().map(x->x.get("po_id")).collect(Collectors.toSet());
  for(var x:csv("shipments.csv")){
   if(x.getOrDefault("po_id","").isBlank()||!poIds.contains(x.get("po_id")))out.add(ex("Shipment",x.get("shipment_id"),"SHIPMENT_WITHOUT_PO","Shipment has a blank or unknown purchase-order reference."));
   try{if(LocalDate.parse(x.get("delivery_date")).isBefore(LocalDate.parse(x.get("dispatch_date"))))out.add(ex("Shipment",x.get("shipment_id"),"DELIVERY_BEFORE_DISPATCH","Delivery date precedes dispatch date."));}catch(Exception ignored){}
   if(!x.get("supplier_id").equals(siteOwners.getOrDefault(x.get("site_id"),"")))out.add(ex("Shipment",x.get("shipment_id"),"SUPPLIER_SITE_MISMATCH","Shipment supplier differs from site's supplier."));
  }
  for(var x:csv("risk_signals.csv"))if(x.getOrDefault("source","").isBlank()||x.getOrDefault("observed_at","").isBlank())out.add(ex("RiskSignal",x.get("risk_signal_id"),"MISSING_SOURCE_OR_TIME","Risk signal lacks source or observed time."));
  // Evaluate deliberately invalid challenge records separately; never import them into baseline business tables.
  Path full=Path.of("data","full");
  List<Map<String,String>> challengeShipments=csvAt(full.resolve("challenge_shipments.csv"));
  Map<String,String> fullSiteOwners=csvAt(full.resolve("sites.csv")).stream().collect(Collectors.toMap(x->x.get("site_id"),x->x.get("supplier_id"),(a,b)->a));
  Set<String> fullPoIds=csvAt(full.resolve("purchase_orders.csv")).stream().map(x->x.get("po_id")).collect(Collectors.toSet());
  for(var x:challengeShipments){
   if(x.getOrDefault("po_id","").isBlank()||!fullPoIds.contains(x.get("po_id")))out.add(ex("Shipment",x.get("shipment_id"),"SHIPMENT_WITHOUT_PO","Challenge: shipment references a missing PO."));
   try{if(LocalDate.parse(x.get("delivery_date")).isBefore(LocalDate.parse(x.get("dispatch_date"))))out.add(ex("Shipment",x.get("shipment_id"),"DELIVERY_BEFORE_DISPATCH","Challenge: delivery date precedes dispatch date."));}catch(Exception ignored){}
   if(!x.get("supplier_id").equals(fullSiteOwners.getOrDefault(x.get("site_id"),"")))out.add(ex("Shipment",x.get("shipment_id"),"SUPPLIER_SITE_MISMATCH","Challenge: supplier and site ownership do not match."));
  }
  for(var x:csvAt(full.resolve("challenge_risk_signals.csv")))if(x.getOrDefault("source","").isBlank()||x.getOrDefault("observed_at","").isBlank())out.add(ex("RiskSignal",x.get("risk_signal_id"),"MISSING_SOURCE_OR_TIME","Challenge: risk signal lacks source or observed time."));
  for(var x:csvAt(full.resolve("challenge_supplier_scores.csv")))try{if(LocalDate.parse(x.get("as_of_date")).isBefore(LocalDate.parse(x.get("source_observation_time"))))out.add(ex("SupplierScore",x.get("score_id"),"FUTURE_DATA_LEAKAGE","Challenge: score cutoff precedes a source observation used in scoring."));}catch(Exception ignored){}
  return out;
 }
 private Map<String,String> ex(String entity,String id,String type,String message){Map<String,String>m=new LinkedHashMap<>();m.put("entityType",entity);m.put("entityId",id);m.put("type",type);m.put("message",message);return m;}
 @GetMapping("/validation") public Map<String,Object> validation(){List<Map<String,String>>s=csv("suppliers.csv"),po=csv("purchase_orders.csv"),sc=csv("supplier_scores.csv"),ex=exceptions();Set<String>ids=s.stream().map(x->x.get("supplier_id")).collect(Collectors.toSet());Map<String,Object>m=new LinkedHashMap<>();m.put("supplierIdsUnique",ids.size()==s.size());m.put("purchaseOrdersReferenceKnownSupplier",po.stream().allMatch(x->ids.contains(x.get("supplier_id"))));m.put("scoreRows",sc.size());m.put("detectedExceptions",ex.size());Set<String> types=ex.stream().map(x->x.get("type")).collect(Collectors.toSet());m.put("expectedAnomaliesPresent",types.containsAll(Set.of("SHIPMENT_WITHOUT_PO","DELIVERY_BEFORE_DISPATCH","FUTURE_DATA_LEAKAGE","MISSING_SOURCE_OR_TIME","SUPPLIER_SITE_MISMATCH")));m.put("detectedAnomalyTypes",types);m.put("note","Some validation failures are intentionally injected as challenge cases; see Exceptions.");return m;}
 private double num(String x){try{return Double.parseDouble(x);}catch(Exception e){return 0;}}
}
