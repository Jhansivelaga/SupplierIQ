package com.supplieriq.generator;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/** Fixed-seed synthetic procurement dataset generator. */
public class DataGenerator {
    static final Path DIR=Path.of("data"); static final LocalDate BASE=LocalDate.of(2025,1,1); static Random random;
    public static void main(String[] a)throws Exception { generate(a.length>0?a[0]:"starter",a.length>1?Long.parseLong(a[1]):20261022L); }
    public static void generate(String scale,long seed)throws IOException {
        random=new Random(seed); Files.createDirectories(DIR); boolean full="full".equalsIgnoreCase(scale);
        int ns=full?1000:50,npo=full?10000:300,nsh=full?15000:450,nin=full?20000:600;
        List<String> sids=new ArrayList<>(),siteIds=new ArrayList<>(); Map<String,Integer> supplierIndex=new HashMap<>();
        for(int i=1;i<=ns;i++){String id="SUP%04d".formatted(i);sids.add(id);supplierIndex.put(id,i-1);}
        try(Csv c=new Csv("suppliers.csv","supplier_id,name,category,country,status,created_date")){for(int i=0;i<ns;i++)c.row(sids.get(i),"Supplier "+(i+1),pick("Raw Materials","Electronics","Packaging","Logistics","Maintenance"),pick("India","USA","Germany","Japan"),pick("ACTIVE","ACTIVE","ACTIVE","UNDER_REVIEW"),date(0,500));}
        try(Csv c=new Csv("sites.csv","site_id,supplier_id,site_name,city,country,status")){for(int i=1;i<=ns*2;i++){String id="SITE%05d".formatted(i);siteIds.add(id);c.row(id,sids.get((i-1)/2),"Site "+i,pick("Hyderabad","Pune","Chennai","Bengaluru","Delhi"),"India","ACTIVE");}}
        try(Csv c=new Csv("contracts.csv","contract_id,supplier_id,start_date,end_date,status,currency")){for(int i=0;i<ns;i++)c.row("CON%05d".formatted(i+1),sids.get(i),date(0,100),date(900,1200),"ACTIVE","INR");}
        try(Csv c=new Csv("purchase_orders.csv","po_id,supplier_id,site_id,contract_id,order_date,expected_date,status,total_amount,currency")){for(int i=1;i<=npo;i++){String sid=sids.get((i-1)%ns);int ix=supplierIndex.get(sid);String site=siteIds.get(ix*2+random.nextInt(2));LocalDate order=BASE.plusDays(120+random.nextInt(600));LocalDate expected=order.plusDays(20+random.nextInt(31));c.row("PO%06d".formatted(i),sid,site,"CON%05d".formatted(ix+1),order,expected,pick("OPEN","CLOSED","CLOSED","CANCELLED"),money(1000,100000),"INR");}}
        try(Csv c=new Csv("po_lines.csv","po_line_id,po_id,line_number,item_code,quantity,unit_price")){for(int i=1;i<=npo;i++)c.row("POL%07d".formatted(i),"PO%06d".formatted(i),1,"ITEM"+(1+random.nextInt(200)),1+random.nextInt(100),money(10,1000));}
        try(Csv c=new Csv("shipments.csv","shipment_id,po_id,supplier_id,site_id,dispatch_date,delivery_date,status")){for(int i=1;i<=nsh;i++){int pi=(i-1)%npo;String po="PO%06d".formatted(pi+1);String sid=sids.get(pi%ns);String site=siteIds.get((pi%ns)*2+random.nextInt(2));LocalDate dispatch=BASE.plusDays(random.nextInt(650));LocalDate delivery=dispatch.plusDays(1+random.nextInt(14));if(i==1)po="";if(i==2)delivery=dispatch.minusDays(1);if(i==3){String other=sids.get((supplierIndex.get(sid)+1)%ns);site=siteIds.get(supplierIndex.get(other)*2);}c.row("SHP%06d".formatted(i),po,sid,site,dispatch,delivery,pick("IN_TRANSIT","DELIVERED","DELIVERED","DELAYED"));}}
        try(Csv c=new Csv("delivery_events.csv","event_id,shipment_id,event_type,event_date,source")){for(int i=1;i<=nsh;i++){String type=i%3==1?"DISPATCHED":i%3==2?"ARRIVED":"DELIVERED"; LocalDate eventDate=BASE.plusDays(30+i); c.row("DEV%06d".formatted(i),"SHP%06d".formatted(i),type,eventDate,"TMS");}}
        try(Csv c=new Csv("inspections.csv","inspection_id,po_id,supplier_id,inspection_date,result,defect_rate")){for(int i=1;i<=nin;i++){int pi=(i-1)%npo;String sid=sids.get(pi%ns);double defect=random.nextDouble()*.08;String result=defect>.05?"FAIL":"PASS";c.row("INS%06d".formatted(i),"PO%06d".formatted(pi+1),sid,date(30,800),result,String.format(Locale.US,"%.3f",defect));}}
        try(Csv c=new Csv("invoices.csv","invoice_id,po_id,supplier_id,invoice_date,amount,currency,status")){for(int i=1;i<=npo;i++){String sid=sids.get((i-1)%ns);double amount=1000+random.nextDouble()*99000;c.row("INV%06d".formatted(i),"PO%06d".formatted(i),sid,date(30,800),fmt(amount),"INR",pick("PAID","PAID","PENDING","DISPUTED"));}}
        try(Csv c=new Csv("risk_signals.csv","risk_signal_id,supplier_id,signal_type,severity,observed_at,source,description")){for(int i=1;i<=ns*2;i++){String observed=date(0,800),source="NewsMonitor";if(i==1){observed="";source="";}c.row("RS%06d".formatted(i),sids.get((i-1)%ns),pick("FINANCIAL","WEATHER","COMPLIANCE","CAPACITY"),pick("LOW","MEDIUM","HIGH"),observed,source,"Synthetic signal "+i);}}
        try(Csv c=new Csv("supplier_scores.csv","score_id,supplier_id,as_of_date,delivery_score,quality_score,cost_score,capacity_score,compliance_score,disruption_score,reliability_score,risk_band,available_components")){for(int i=0;i<ns;i++){double d=between(45,100),q=between(45,100),co=between(45,100),ca=between(45,100),cp=between(45,100),di=between(45,100);double score=d*.25+q*.25+co*.15+ca*.15+cp*.10+di*.10;String band=score<60?"HIGH_RISK":score<80?"WATCH":"RELIABLE";c.row("SCR%05d".formatted(i+1),sids.get(i),BASE.plusDays(700),fmt(d),fmt(q),fmt(co),fmt(ca),fmt(cp),fmt(di),fmt(score),band,6);}}
        try(Csv c=new Csv("state_events.csv","state_event_id,entity_type,entity_id,from_state,to_state,event_date,actor")){for(int i=1;i<=npo;i++)c.row("STE%06d".formatted(i),"PurchaseOrder","PO%06d".formatted(i),"OPEN","CLOSED",date(0,800),"SYSTEM");}
        try(Csv c=new Csv("ground_truth.csv","truth_id,entity_type,entity_id,anomaly_type,expected_detection,rule_description")){
            c.row("GT0001","Shipment","SHP000001","SHIPMENT_WITHOUT_PO",true,"Shipment must reference an existing purchase order");
            c.row("GT0002","Shipment","SHP000002","DELIVERY_BEFORE_DISPATCH",true,"Delivery date must be on or after dispatch date");
            c.row("GT0003","RiskSignal","RS000001","MISSING_SOURCE_OR_TIME",true,"Risk signals require observation timestamp and source");
            c.row("GT0004","Shipment","SHP000003","SUPPLIER_SITE_MISMATCH",true,"Shipment supplier must own the selected site");
        }
        Files.writeString(DIR.resolve("manifest.txt"),"project=SupplierIQ\nscale="+scale+"\nseed="+seed+"\nbase_date="+BASE+"\nsuppliers="+ns+"\npurchase_orders="+npo+"\nshipments="+nsh+"\ninspections="+nin+"\n",StandardCharsets.UTF_8);
        Files.writeString(DIR.resolve("validation_report.txt"),"SupplierIQ validation summary\nIntentional examples are expected in this demo dataset.\nSHP000001: shipment without PO\nSHP000002: delivery before dispatch\nRS000001: missing risk source/time\nSHP000003: supplier/site mismatch\n",StandardCharsets.UTF_8);
        System.out.println("Generated "+scale+" dataset with seed "+seed+" in "+DIR.toAbsolutePath());
    }
    static String date(int min,int max){return BASE.plusDays(min+random.nextInt(max-min+1)).toString();} static String money(int min,int max){return String.format(Locale.US,"%.2f",min+random.nextDouble()*(max-min));} static String fmt(double n){return String.format(Locale.US,"%.2f",n);} static double between(double a,double b){return a+random.nextDouble()*(b-a);} static String pick(String...v){return v[random.nextInt(v.length)];}
    static class Csv implements AutoCloseable {BufferedWriter out;Csv(String n,String h)throws IOException{out=Files.newBufferedWriter(DIR.resolve(n),StandardCharsets.UTF_8);out.write(h);out.newLine();}void row(Object...v)throws IOException{for(int i=0;i<v.length;i++){if(i>0)out.write(",");String s=String.valueOf(v[i]);out.write("\""+s.replace("\"","\"\"")+"\"");}out.newLine();}public void close()throws IOException{out.close();}}
}
