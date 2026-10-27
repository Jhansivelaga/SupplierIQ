#!/usr/bin/env python3
"""Deterministic SupplierIQ dataset generator. Python 3.10+, standard library only."""
import argparse, csv, hashlib, json, random
from collections import Counter
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

SEED_DEFAULT = 20261022
WEIGHTS = {'delivery': .25, 'quality': .25, 'cost': .15, 'capacity': .15, 'compliance': .10, 'disruption': .10}
ENUMS = {
 'supplier_status':['ACTIVE','SUSPENDED','INACTIVE'], 'contract_status':['DRAFT','ACTIVE','EXPIRED','TERMINATED'],
 'po_status':['CREATED','APPROVED','RELEASED','PARTIALLY_RECEIVED','RECEIVED','CLOSED','CANCELLED'],
 'shipment_status':['PLANNED','DISPATCHED','IN_TRANSIT','DELIVERED','DELAYED','CANCELLED'],
 'delivery_event_type':['DISPATCHED','IN_TRANSIT','DELIVERED','DELAY_REPORTED'],
 'inspection_result':['PASS','FAIL','CONDITIONAL'], 'invoice_status':['MATCHED','VARIANCE','ON_HOLD','PAID'],
 'risk_type':['WEATHER','TRANSPORT','FINANCIAL','QUALITY','CAPACITY','GEOPOLITICAL'],
 'risk_severity':['LOW','MEDIUM','HIGH','CRITICAL'], 'risk_band':['HIGH_RISK','WATCH','RELIABLE']}

def iso(d): return d.isoformat()
def write_csv(path, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    rows=list(rows)
    if not rows: path.write_text('', encoding='utf-8'); return
    with path.open('w', newline='', encoding='utf-8') as f:
        w=csv.DictWriter(f, fieldnames=list(rows[0].keys())); w.writeheader(); w.writerows(rows)

def make_dataset(out: Path, seed: int, scale: float):
    rng=random.Random(seed); out.mkdir(parents=True, exist_ok=True)
    n_sup=max(1, round(1000*scale)); n_po=max(1, round(10000*scale)); n_ship=max(1, round(15000*scale)); n_insp=max(1, round(20000*scale))
    suppliers=[]; sites=[]; contracts=[]; pos=[]; lines=[]; shipments=[]; delivery=[]; inspections=[]; invoices=[]; risks=[]; scores=[]; state_events=[]
    start=date(2026,1,1)
    for i in range(1,n_sup+1):
        sid=f'SUP-{i:06d}'; tier='STRATEGIC' if i%20==0 else ('PREFERRED' if i%4==0 else 'STANDARD')
        suppliers.append({'supplier_id':sid,'supplier_name':f'Supplier {i:06d}','status':'ACTIVE' if i%37 else 'SUSPENDED','tier':tier,'country':['IN','US','DE','SG','JP'][i%5],'created_date':iso(start+timedelta(days=i%180)),'currency':'USD','source_system':'SYNTHETIC'})
        site_count=1+(i%3==0)+(i%11==0)
        for j in range(1,site_count+1):
            sites.append({'site_id':f'SITE-{sid}-{j:02d}','supplier_id':sid,'site_name':f'{sid} Site {j:02d}','country':['IN','US','DE','SG','JP'][i%5],'is_primary':str(j==1).lower(),'valid_from':iso(start),'valid_to':''})
        contracts.append({'contract_id':f'CON-{i:06d}-2026-01','supplier_id':sid,'contract_name':f'{sid} Annual Contract','start_date':'2026-01-01','end_date':'2026-12-31','status':'ACTIVE','currency':'USD','payment_terms_days':30+(i%3)*15,'max_value':round(50000+(i%25)*10000,2)})
    site_by_supplier={s['supplier_id']:[] for s in suppliers}
    for _site in sites: site_by_supplier[_site['supplier_id']].append(_site)
    po_by_id={}
    for i in range(1,n_po+1):
        sid=f'SUP-{((i-1)%n_sup)+1:06d}'; contract_id=f'CON-{int(sid[-6:]):06d}-2026-01'; site=rng.choice(site_by_supplier[sid]); order_date=start+timedelta(days=rng.randrange(0,250)); supplier_num=int(sid[-6:]); trend_days=(i%12 if supplier_num%10==0 else 0); lead=7+(supplier_num%15)+(i%9 if i%10==0 else 0)+trend_days; expected=order_date+timedelta(days=lead)
        poid=f'PO-2026-{i:06d}'; qty=10+(i%490); price=round(20+(i%90)*3.75,2); total=round(qty*price,2)
        po={'po_id':poid,'supplier_id':sid,'site_id':site['site_id'],'contract_id':contract_id,'order_date':iso(order_date),'expected_date':iso(expected),'currency':'USD','status':'RELEASED','total_quantity':qty,'total_amount':f'{total:.2f}'}
        pos.append(po); po_by_id[poid]=po
        lines.append({'po_line_id':f'{poid}-L01','po_id':poid,'line_number':1,'item_id':f'ITEM-{(i%300)+1:04d}','quantity_ordered':qty,'unit_price':f'{price:.2f}','line_total':f'{total:.2f}'})
    # shipments reference valid POs, round-robin with multiple shipments for some POs
    for i in range(1,n_ship+1):
        po=pos[(i-1)%len(pos)]; dispatch=date.fromisoformat(po['order_date'])+timedelta(days=max(1,(date.fromisoformat(po['expected_date'])-date.fromisoformat(po['order_date'])).days-2)); delay=(i%13==0)*rng.randint(2,12); delivered=dispatch+timedelta(days=2+delay)
        shid=f'SHP-2026-{i:06d}'; status='DELAYED' if delay else 'DELIVERED'
        shipments.append({'shipment_id':shid,'po_id':po['po_id'],'supplier_id':po['supplier_id'],'site_id':po['site_id'],'dispatch_date':iso(dispatch),'delivery_date':iso(delivered),'quantity_shipped':max(1,int(po['total_quantity'])//max(1, (n_ship//n_po))), 'status':status,'carrier':f'CARRIER-{(i%12)+1:02d}'})
        delivery.append({'delivery_event_id':f'DEV-{i:07d}-01','shipment_id':shid,'event_type':'DISPATCHED','event_time':datetime.combine(dispatch,datetime.min.time(),tzinfo=timezone.utc).isoformat(),'location':po['site_id'],'source':'GENERATOR'})
        delivery.append({'delivery_event_id':f'DEV-{i:07d}-02','shipment_id':shid,'event_type':'DELIVERED','event_time':datetime.combine(delivered,datetime.min.time(),tzinfo=timezone.utc).isoformat(),'location':po['site_id'],'source':'GENERATOR'})
    for i in range(1,n_insp+1):
        po=pos[(i-1)%len(pos)]; supplier_num=int(po['supplier_id'][-6:]); defect=round((i%17)/100,4) if (i%19==0 or supplier_num%13==0) else round((i%4)/1000,4); result='FAIL' if defect>=.10 else ('CONDITIONAL' if defect>=.03 else 'PASS')
        inspections.append({'inspection_id':f'INS-2026-{i:06d}','po_id':po['po_id'],'supplier_id':po['supplier_id'],'inspection_date':iso(date.fromisoformat(po['expected_date'])+timedelta(days=i%20)),'sample_size':min(int(po['total_quantity']),max(1,10+i%50)),'defect_rate':f'{defect:.4f}','result':result,'source':'GENERATOR'})
    for i,po in enumerate(pos,1):
        amount=round(float(po['total_amount'])*(1+((i%9)-4)/100),2); variance=round(amount-float(po['total_amount']),2)
        invoices.append({'invoice_id':f'INV-2026-{i:06d}','po_id':po['po_id'],'supplier_id':po['supplier_id'],'invoice_date':iso(date.fromisoformat(po['expected_date'])+timedelta(days=5)),'po_amount':po['total_amount'],'invoice_amount':f'{amount:.2f}','variance_amount':f'{variance:.2f}','variance_pct':f'{(variance/float(po["total_amount"])*100 if float(po["total_amount"]) else 0):.2f}','status':'MATCHED' if abs(variance)<=float(po['total_amount'])*.02 else 'VARIANCE','currency':'USD'})
    # Generate risk records before aggregation so risk-based score components are not accidentally empty.
    for i,s in enumerate(suppliers,1):
        sid=s['supplier_id']; observed=datetime(2026,1,1,tzinfo=timezone.utc)+timedelta(days=i%290)
        if i % 29 != 0:  # explicit missing risk observations for a small cohort
            risks.append({'risk_signal_id':f'RSK-{sid}-2026-01','supplier_id':sid,'risk_type':['WEATHER','TRANSPORT','FINANCIAL','QUALITY','CAPACITY','GEOPOLITICAL'][i%6],'severity':['LOW','MEDIUM','HIGH','CRITICAL'][i%4],'source':'SYNTHETIC_FEED','observed_at':observed.isoformat(),'expires_at':(observed+timedelta(days=30)).isoformat(),'description':'Synthetic scenario risk signal'})
    shipments_by_supplier={sid:[] for sid in site_by_supplier}; inspections_by_supplier={sid:[] for sid in site_by_supplier}; invoices_by_supplier={sid:[] for sid in site_by_supplier}; risks_by_supplier={sid:[] for sid in site_by_supplier}
    for x in shipments: shipments_by_supplier[x['supplier_id']].append(x)
    for x in inspections: inspections_by_supplier[x['supplier_id']].append(x)
    for x in invoices: invoices_by_supplier[x['supplier_id']].append(x)
    for x in risks: risks_by_supplier[x['supplier_id']].append(x)
    score_cutoff=date(2026,10,22)
    for i,s in enumerate(suppliers,1):
        sid=s['supplier_id']
        # Prevent temporal leakage: only facts observable on/before the fixed score cutoff contribute.
        sships=[x for x in shipments_by_supplier[sid] if date.fromisoformat(x['delivery_date'])<=score_cutoff]
        sins=[x for x in inspections_by_supplier[sid] if date.fromisoformat(x['inspection_date'])<=score_cutoff]
        invs=[x for x in invoices_by_supplier[sid] if date.fromisoformat(x['invoice_date'])<=score_cutoff]
        rks=[x for x in risks_by_supplier[sid] if date.fromisoformat(x['observed_at'][:10])<=score_cutoff]
        delivery_score=round(100*sum(date.fromisoformat(x['delivery_date'])<=date.fromisoformat(po_by_id[x['po_id']]['expected_date']) for x in sships)/len(sships),2) if sships else 75.0
        quality_score=round(100*(1-sum(float(x['defect_rate']) for x in sins)/len(sins)),2) if sins else 75.0
        cost_score=round(max(0,min(100,100-sum(abs(float(x['variance_pct'])) for x in invs)/len(invs))),2) if invs else 75.0
        capacity_score=round(100*sum(x['status']=='DELIVERED' for x in sships)/len(sships),2) if sships else 75.0; compliance_score=round(max(0,100-20*sum(x['result']=='FAIL' for x in sins)),2) if sins else 75.0; disruption_score=round(max(0,100-sum({'LOW':5,'MEDIUM':15,'HIGH':30,'CRITICAL':50}[x['severity']] for x in rks)),2) if rks else 75.0
        components={'delivery':delivery_score,'quality':quality_score,'cost':cost_score,'capacity':capacity_score,'compliance':compliance_score,'disruption':disruption_score}
        explanation={'components':components,'weights':WEIGHTS,'as_of_date':iso(score_cutoff),'observations':{'shipments':len(sships),'delivery_eligible_denominator':len(sships),'inspections':len(sins),'quality_eligible_denominator':len(sins),'invoices':len(invs),'risk_signals':len(rks)},'missing_data':{'delivery':len(sships)==0,'quality':len(sins)==0,'cost':len(invs)==0,'capacity':False,'compliance':False,'disruption':len(rks)==0},'fallback_by_component':{k:(75.0 if n==0 else None) for k,n in {'delivery':len(sships),'quality':len(sins),'cost':len(invs),'capacity':1,'compliance':1,'disruption':len(rks)}.items()},'fallback':75.0,'metric_definitions':{'delivery':'100 * delivered-status shipments / eligible shipments with delivery_date <= as_of_date','quality':'100 * (1 - mean defect_rate) across inspections dated <= as_of_date','cost':'100 - mean absolute invoice variance percent, clamped to [0,100]','capacity':'100 * delivered-status shipments / eligible shipments; fallback 75 if no eligible shipments','compliance':'100 - 20 * failed inspections, clamped to [0,100]; fallback 75 if no inspections','disruption':'100 - sum severity penalties (LOW 5, MEDIUM 15, HIGH 30, CRITICAL 50) for risk signals observed <= as_of_date, clamped to [0,100]'}}
        score=round(sum(components[k]*WEIGHTS[k] for k in WEIGHTS),2); band='HIGH_RISK' if score<60 else ('WATCH' if score<80 else 'RELIABLE')
        scores.append({'score_id':f'SCORE-2026-{i:06d}','supplier_id':sid,'as_of_date':iso(score_cutoff),'delivery_score':delivery_score,'quality_score':quality_score,'cost_score':cost_score,'capacity_score':capacity_score,'compliance_score':compliance_score,'disruption_score':disruption_score,'reliability_score':score,'risk_band':band,'weights_json':json.dumps(WEIGHTS,sort_keys=True),'explanation_json':json.dumps(explanation,sort_keys=True)})
        state_events.append({'state_event_id':f'STE-{i:07d}','entity_type':'SUPPLIER','entity_id':sid,'from_state':'NEW','to_state':s['status'],'event_time':datetime.combine(date.fromisoformat(s['created_date']),datetime.min.time(),tzinfo=timezone.utc).isoformat(),'actor':'synthetic-generator','reason':'Initial generated state'})
    files={'suppliers.csv':suppliers,'sites.csv':sites,'contracts.csv':contracts,'purchase_orders.csv':pos,'po_lines.csv':lines,'shipments.csv':shipments,'delivery_events.csv':delivery,'inspections.csv':inspections,'invoices.csv':invoices,'risk_signals.csv':risks,'supplier_scores.csv':scores,'state_events.csv':state_events}
    for name,rows in files.items():
        write_csv(out/name,rows)
        (out/(name[:-4]+'.json')).write_text(json.dumps(rows,indent=2),encoding='utf-8')
    # Portable SQL seed uses JSON payload staging table so schema-specific imports remain explicit.
    sql_lines=["-- SupplierIQ reproducible synthetic seed; load only into a dedicated demo database.","CREATE TABLE IF NOT EXISTS synthetic_seed_records (entity_name VARCHAR(80) NOT NULL, record_id VARCHAR(120) NOT NULL, payload JSON NOT NULL, PRIMARY KEY(entity_name, record_id));"]
    for name,rows in files.items():
        entity=name[:-4]
        idfield=next((k for k in rows[0] if k.endswith('_id')),None)
        for row in rows:
            rid=row.get(idfield,'') if idfield else ''
            payload=json.dumps(row,separators=(',',':')).replace('\\','\\\\').replace("'","''")
            sql_lines.append(f"INSERT INTO synthetic_seed_records(entity_name,record_id,payload) VALUES ('{entity}','{rid}','{payload}') ON DUPLICATE KEY UPDATE payload=VALUES(payload);")
    (out/'seed_data.sql').write_text('\n'.join(sql_lines)+'\n',encoding='utf-8')
    # Isolated labelled challenge cases: never mix them into baseline input files.
    challenges=[
      {'entity_type':'Shipment','entity_id':'SHP-ANOM-000001','issue_type':'SHIPMENT_WITHOUT_PO','expected_detection':'shipment references nonexistent PO','ground_truth_label':'ANOMALY'},
      {'entity_type':'Shipment','entity_id':'SHP-ANOM-000002','issue_type':'DELIVERY_BEFORE_DISPATCH','expected_detection':'delivery_date < dispatch_date','ground_truth_label':'ANOMALY'},
      {'entity_type':'SupplierScore','entity_id':'SCORE-ANOM-000001','issue_type':'FUTURE_DATA_LEAKAGE','expected_detection':'score as_of_date precedes a source observation used in score','ground_truth_label':'ANOMALY'},
      {'entity_type':'RiskSignal','entity_id':'RSK-ANOM-000001','issue_type':'MISSING_RISK_SOURCE_OR_TIME','expected_detection':'source or observed_at is null/blank','ground_truth_label':'ANOMALY'},
      {'entity_type':'Shipment','entity_id':'SHP-ANOM-000003','issue_type':'SUPPLIER_SITE_MISMATCH','expected_detection':'shipment supplier_id differs from site owner','ground_truth_label':'ANOMALY'}]
    write_csv(out/'ground_truth.csv',challenges)
    (out/'ground_truth.json').write_text(json.dumps(challenges,indent=2),encoding='utf-8')
    edge={'SHP-ANOM-000001':{'shipment_id':'SHP-ANOM-000001','po_id':'PO-NOT-FOUND','supplier_id':'SUP-000001','site_id':'SITE-SUP-000001-01','dispatch_date':'2026-10-01','delivery_date':'2026-10-03','status':'DELIVERED'},'SHP-ANOM-000002':{'shipment_id':'SHP-ANOM-000002','po_id':'PO-2026-000001','supplier_id':'SUP-000001','site_id':'SITE-SUP-000001-01','dispatch_date':'2026-10-05','delivery_date':'2026-10-02','status':'DELIVERED'},'SHP-ANOM-000003':{'shipment_id':'SHP-ANOM-000003','po_id':'PO-2026-000001','supplier_id':'SUP-000001','site_id':f'SITE-SUP-{min(2,n_sup):06d}-01','dispatch_date':'2026-10-01','delivery_date':'2026-10-03','status':'DELIVERED'}}
    write_csv(out/'challenge_shipments.csv',list(edge.values()))
    (out/'challenge_shipments.json').write_text(json.dumps(list(edge.values()),indent=2),encoding='utf-8')
    challenge_risks=[{'risk_signal_id':'RSK-ANOM-000001','supplier_id':'SUP-000001','risk_type':'TRANSPORT','severity':'HIGH','source':'','observed_at':''}]
    write_csv(out/'challenge_risk_signals.csv',challenge_risks); (out/'challenge_risk_signals.json').write_text(json.dumps(challenge_risks,indent=2),encoding='utf-8')
    challenge_scores=[{'score_id':'SCORE-ANOM-000001','supplier_id':'SUP-000001','as_of_date':'2026-01-01','source_observation_time':'2026-02-01','issue_type':'FUTURE_DATA_LEAKAGE'}]
    write_csv(out/'challenge_supplier_scores.csv',challenge_scores); (out/'challenge_supplier_scores.json').write_text(json.dumps(challenge_scores,indent=2),encoding='utf-8')
    # Dictionary: every field gets type/required/enums/units/uniqueness/validation metadata.
    dict_rows=[]
    for fname,rows in files.items():
      entity=fname[:-4]
      for field in rows[0].keys():
        val=rows[0][field]; typ='DECIMAL' if field.endswith(('_amount','_price','_score','_rate','_pct')) or field in ('total_quantity','quantity_ordered','quantity_shipped','sample_size','payment_terms_days') else ('DATE' if field.endswith('_date') else ('TIMESTAMP' if field.endswith(('_at','_time')) else ('JSON' if field.endswith('_json') else 'VARCHAR')))
        required='NO' if field in ('valid_to',) else 'YES'; unique='YES' if field.endswith('_id') and field not in ('supplier_id',) else ('YES' if field in ('supplier_id',) and entity in ('suppliers','supplier_scores') else 'NO')
        enum=next((','.join(v) for k,v in ENUMS.items() if k in field or (field=='status' and entity in ('suppliers','contracts','purchase_orders','shipments','invoices'))), '')
        units='USD' if 'amount' in field or 'price' in field else ('percent' if field.endswith(('_rate','_pct')) or field.endswith('_score') or field=='reliability_score' else ('days' if 'days' in field or field=='lead_time' else ''))
        validation='Must reference existing parent ID where applicable; immutable ID; trim strings.'
        if field.endswith('_date') or field.endswith(('_at','_time')): validation+=' ISO-8601; enforce domain date ordering.'
        if field.endswith(('_amount','_price','_score','_rate','_pct')): validation+=' Numeric; non-negative unless variance; score/rate within documented range.'
        if field in ('supplier_id','site_id','contract_id','po_id','shipment_id'): validation+=' Foreign-key ownership and join consistency must pass.'
        dict_rows.append({'entity':entity,'field':field,'type':typ,'required':required,'allowed_values':enum,'units':units,'unique':unique,'validation_rule':validation})
    write_csv(out/'data_dictionary.csv',dict_rows)
    # validation and source manifest
    validations=[]
    for name,rows in files.items():
      idfield=next((k for k in rows[0] if k.endswith('_id')),None)
      vals=[r[idfield] for r in rows] if idfield else []
      validations.append({'check':f'{name}: row count','status':'PASS','details':str(len(rows))})
      if idfield: validations.append({'check':f'{name}: primary-key uniqueness','status':'PASS' if len(vals)==len(set(vals)) else 'FAIL','details':f'{len(vals)} rows; {len(set(vals))} unique IDs'})
    site_ids={s['site_id'] for s in sites}; sup_ids={s['supplier_id'] for s in suppliers}; con_ids={c['contract_id'] for c in contracts}; po_ids={p['po_id'] for p in pos}; sh_ids={s['shipment_id'] for s in shipments}
    po_lookup={p['po_id']:p for p in pos}
    checks=[('PO supplier FK',all(p['supplier_id'] in sup_ids for p in pos)),('PO site FK',all(p['site_id'] in site_ids for p in pos)),('PO contract FK',all(p['contract_id'] in con_ids for p in pos)),('PO date order',all(p['expected_date']>=p['order_date'] for p in pos)),('shipment PO FK',all(s['po_id'] in po_ids for s in shipments)),('shipment supplier/site consistency',all(po_lookup[s['po_id']]['supplier_id']==s['supplier_id'] and po_lookup[s['po_id']]['site_id']==s['site_id'] for s in shipments)),('shipment date order',all(s['delivery_date']>=s['dispatch_date'] for s in shipments)),('delivery event shipment FK',all(e['shipment_id'] in sh_ids for e in delivery)),('inspection PO FK',all(x['po_id'] in po_ids for x in inspections)),('inspection defect-rate bounds',all(0<=float(x['defect_rate'])<=1 for x in inspections)),('invoice arithmetic',all(round(float(x['invoice_amount'])-float(x['po_amount']),2)==float(x['variance_amount']) for x in invoices)),('PO line arithmetic',all(round(float(x['quantity_ordered'])*float(x['unit_price']),2)==float(x['line_total']) for x in lines)),('score weights sum to 1',round(sum(WEIGHTS.values()),8)==1.0)]
    validations += [{'check':n,'status':'PASS' if ok else 'FAIL','details':'baseline dataset validation'} for n,ok in checks]
    write_csv(out/'validation_summary.csv',validations)
    manifest={'generator':'tools/generate_synthetic_data.py','seed':seed,'scale':scale,'generated_at':'fixed-by-seed; intentionally omitted wall-clock timestamp','baseline_counts':{k:len(v) for k,v in files.items()},'anomaly_cases':len(challenges),'weights':WEIGHTS,'risk_bands':{'HIGH_RISK':'score < 60','WATCH':'60 <= score < 80','RELIABLE':'score >= 80'},'files':{}}
    base_csv_names=['suppliers.csv','sites.csv','contracts.csv','purchase_orders.csv','po_lines.csv','shipments.csv','delivery_events.csv','inspections.csv','invoices.csv','risk_signals.csv','supplier_scores.csv','state_events.csv']
    generated_names=set(base_csv_names) | {Path(n).with_suffix('.json').name for n in base_csv_names} | {'ground_truth.csv','ground_truth.json','challenge_shipments.csv','challenge_shipments.json','challenge_risk_signals.csv','challenge_risk_signals.json','challenge_supplier_scores.csv','challenge_supplier_scores.json','data_dictionary.csv','seed_data.sql','validation_summary.csv'}
    for p in sorted(out.iterdir()):
      if p.is_file() and p.name in generated_names:
        b=p.read_bytes(); manifest['files'][p.name]={'sha256':hashlib.sha256(b).hexdigest(),'bytes':len(b)}
    (out/'source_manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
    if any(v['status']=='FAIL' for v in validations): raise SystemExit('Validation failed; inspect validation_summary.csv')
    print(json.dumps({'output':str(out),'seed':seed,'scale':scale,'counts':manifest['baseline_counts'],'validations':'PASS'},indent=2))

def main():
    p=argparse.ArgumentParser(description=__doc__); p.add_argument('--out',default='data/generated'); p.add_argument('--seed',type=int,default=SEED_DEFAULT); p.add_argument('--scale',type=float,default=1.0,help='0.01=starter (~10 suppliers, 100 POs)'); a=p.parse_args()
    if a.scale<=0: p.error('--scale must be > 0')
    make_dataset(Path(a.out),a.seed,a.scale)
if __name__=='__main__': main()
