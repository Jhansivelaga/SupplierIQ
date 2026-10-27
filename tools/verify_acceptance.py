#!/usr/bin/env python3
"""Acceptance checks for SupplierIQ synthetic baseline and isolated challenge data."""
import argparse, csv, hashlib, json, sys
from datetime import date
from pathlib import Path

EXPECTED = {'suppliers.csv':1000, 'purchase_orders.csv':10000, 'shipments.csv':15000, 'inspections.csv':20000}
WEIGHTS = {'delivery':.25,'quality':.25,'cost':.15,'capacity':.15,'compliance':.10,'disruption':.10}

def rows(path):
    with path.open(newline='', encoding='utf-8') as f: return list(csv.DictReader(f))
def main():
    p=argparse.ArgumentParser(); p.add_argument('data_dir', nargs='?', default='data/full'); a=p.parse_args(); d=Path(a.data_dir)
    checks=[]
    def check(name, ok, detail=''):
        checks.append((name, bool(ok), detail))
    needed=['suppliers.csv','sites.csv','contracts.csv','purchase_orders.csv','po_lines.csv','shipments.csv','delivery_events.csv','inspections.csv','invoices.csv','risk_signals.csv','supplier_scores.csv','state_events.csv','ground_truth.csv','challenge_shipments.csv','challenge_risk_signals.csv','challenge_supplier_scores.csv','source_manifest.json','data_dictionary.csv']
    missing=[n for n in needed if not (d/n).is_file()]
    check('required artifacts exist',not missing,','.join(missing))
    if missing:
        for n,ok,detail in checks: print(('PASS' if ok else 'FAIL')+f' | {n} | {detail}')
        return 1
    data={n:rows(d/n) for n in needed if n.endswith('.csv')}
    for fn,count in EXPECTED.items(): check(f'{fn} exact target count',len(data[fn])==count,f'actual={len(data[fn])}; expected={count}')
    # Variation and distribution checks.
    su=data['suppliers.csv']; po=data['purchase_orders.csv']; sh=data['shipments.csv']; ins=data['inspections.csv']; risks=data['risk_signals.csv']
    check('supplier tier variation',len({x['tier'] for x in su})>=3,str(sorted({x['tier'] for x in su})))
    delays=[int((__import__('datetime').date.fromisoformat(x['delivery_date'])-__import__('datetime').date.fromisoformat(x['dispatch_date'])).days) for x in sh]
    check('lead-time variation',len(set(delays))>=5,f'unique delivery durations={len(set(delays))}')
    defects=[float(x['defect_rate']) for x in ins]
    check('quality incidents represented',any(x>=.03 for x in defects) and any(x==0 for x in defects),f'min={min(defects):.4f}; max={max(defects):.4f}')
    check('missing risk/performance cohort represented',len(risks)<len(su),f'risk records={len(risks)}; suppliers={len(su)}')
    # ID and relationship integrity.
    ids=lambda rs,k:{x[k] for x in rs}
    sid=ids(su,'supplier_id'); site=data['sites.csv']; site_map={x['site_id']:x for x in site}; con=data['contracts.csv']; con_map={x['contract_id']:x for x in con}; po_map={x['po_id']:x for x in po}
    check('supplier IDs unique',len(sid)==len(su)); check('site IDs unique',len(ids(site,'site_id'))==len(site)); check('PO IDs unique',len(ids(po,'po_id'))==len(po)); check('shipment IDs unique',len(ids(sh,'shipment_id'))==len(sh)); check('inspection IDs unique',len(ids(ins,'inspection_id'))==len(ins))
    check('PO supplier/site/contract joins valid',all(x['supplier_id'] in sid and x['site_id'] in site_map and site_map[x['site_id']]['supplier_id']==x['supplier_id'] and x['contract_id'] in con_map and con_map[x['contract_id']]['supplier_id']==x['supplier_id'] for x in po))
    check('PO lifecycle dates valid',all(x['order_date']<=x['expected_date'] and con_map[x['contract_id']]['start_date']<=x['order_date']<=con_map[x['contract_id']]['end_date'] for x in po))
    check('shipment joins and site ownership valid',all(x['po_id'] in po_map and x['supplier_id']==po_map[x['po_id']]['supplier_id'] and x['site_id']==po_map[x['po_id']]['site_id'] and site_map[x['site_id']]['supplier_id']==x['supplier_id'] for x in sh))
    check('shipment date ordering valid',all(x['delivery_date']>=x['dispatch_date'] for x in sh))
    check('baseline risk signals have source and timestamp',all(x.get('source','').strip() and x.get('observed_at','').strip() for x in risks))
    check('baseline shipments reference existing POs',all(x.get('po_id') in po_map for x in sh))
    check('baseline supplier-site ownership is consistent',all(x.get('site_id') in site_map and site_map[x['site_id']]['supplier_id']==x['supplier_id'] for x in sh))
    check('inspection joins valid',all(x['po_id'] in po_map and x['supplier_id']==po_map[x['po_id']]['supplier_id'] for x in ins))
    check('inspection bounds and results valid',all(0<=float(x['defect_rate'])<=1 and int(x['sample_size'])>0 and x['result']==('FAIL' if float(x['defect_rate'])>=.10 else ('CONDITIONAL' if float(x['defect_rate'])>=.03 else 'PASS')) for x in ins))
    defects_set={round(float(x['defect_rate']),4) for x in ins}
    check('normal and boundary inspection cases represented',0.0 in defects_set and 0.03 in defects_set and 0.10 in defects_set and any(x['result']=='FAIL' for x in ins) and any(x['result']=='PASS' for x in ins))
    check('invoice arithmetic reconciles',all(round(float(x['invoice_amount'])-float(x['po_amount']),2)==round(float(x['variance_amount']),2) and (float(x['po_amount'])==0 or round(float(x['variance_amount'])/float(x['po_amount'])*100,2)==round(float(x['variance_pct']),2)) for x in data['invoices.csv']))
    check('PO line totals reconcile',all(round(float(x['quantity_ordered'])*float(x['unit_price']),2)==round(float(x['line_total']),2) for x in data['po_lines.csv']))
    # Score reproducibility/explainability and temporal cutoff.
    score_rows=data['supplier_scores.csv']; score_map={x['supplier_id']:x for x in score_rows}; check('one score per supplier',len(score_map)==len(su) and set(score_map)==sid)
    score_ok=True; cutoff_ok=True; explain_ok=True
    for sc in score_rows:
        components={k:float(sc[k+'_score']) for k in WEIGHTS}
        expected=round(sum(components[k]*WEIGHTS[k] for k in WEIGHTS),2)
        if expected!=round(float(sc['reliability_score']),2): score_ok=False
        if sc['as_of_date']!='2026-10-22': cutoff_ok=False
        try:
            exp=json.loads(sc['explanation_json'])
            cutoff=date.fromisoformat(sc['as_of_date'])
            source_rows=[x for x in sh if x['supplier_id']==sc['supplier_id'] and date.fromisoformat(x['delivery_date'])<=cutoff]
            source_ins=[x for x in ins if x['supplier_id']==sc['supplier_id'] and date.fromisoformat(x['inspection_date'])<=cutoff]
            source_inv=[x for x in data['invoices.csv'] if x['supplier_id']==sc['supplier_id'] and date.fromisoformat(x['invoice_date'])<=cutoff]
            source_risk=[x for x in risks if x['supplier_id']==sc['supplier_id'] and date.fromisoformat(x['observed_at'][:10])<=cutoff]
            explain_ok &= exp.get('as_of_date')==sc['as_of_date'] and exp.get('weights')==WEIGHTS and set(exp.get('components',{}))==set(WEIGHTS) and all(v>=0 for v in exp.get('observations',{}).values())
            explain_ok &= exp.get('observations',{}).get('shipments')==len(source_rows) and exp.get('observations',{}).get('inspections')==len(source_ins) and exp.get('observations',{}).get('invoices')==len(source_inv) and exp.get('observations',{}).get('risk_signals')==len(source_risk)
            explain_ok &= all(date.fromisoformat(x['delivery_date'])<=cutoff for x in source_rows) and all(date.fromisoformat(x['inspection_date'])<=cutoff for x in source_ins) and all(date.fromisoformat(x['invoice_date'])<=cutoff for x in source_inv) and all(date.fromisoformat(x['observed_at'][:10])<=cutoff for x in source_risk)
        except Exception: explain_ok=False
    check('score weighted formula reconciles',score_ok); check('fixed historical score cutoff documented',cutoff_ok); check('score explanation includes weights, cutoff and denominators',explain_ok)
    check('delivery and quality denominators are explicit',all((lambda e: 'delivery_eligible_denominator' in e.get('observations',{}) and 'quality_eligible_denominator' in e.get('observations',{}))(json.loads(x['explanation_json'])) for x in score_rows))
    check('missing data treatment is explicit',all('missing_data' in json.loads(x['explanation_json']) and 'fallback_by_component' in json.loads(x['explanation_json']) for x in score_rows))
    check('scores exclude future-dated source observations',all(date.fromisoformat(x['as_of_date'])==date(2026,10,22) for x in score_rows))
    # Anomalies must be isolated from baseline and all required classes labeled.
    truth=data['ground_truth.csv']; types={x['issue_type'] for x in truth}; required={'SHIPMENT_WITHOUT_PO','DELIVERY_BEFORE_DISPATCH','FUTURE_DATA_LEAKAGE','MISSING_RISK_SOURCE_OR_TIME','SUPPLIER_SITE_MISMATCH'}
    check('all five required anomaly labels exist',required<=types,str(sorted(types)))
    challenge_ship=data['challenge_shipments.csv']; challenge_risk=data['challenge_risk_signals.csv']; challenge_scores=data['challenge_supplier_scores.csv']
    check('challenge shipment failures are actually invalid',any(x['po_id']=='PO-NOT-FOUND' and x['shipment_id']=='SHP-ANOM-000001' for x in challenge_ship) and any(x['delivery_date']<x['dispatch_date'] and x['shipment_id']=='SHP-ANOM-000002' for x in challenge_ship) and any(x['supplier_id'] not in x['site_id'] and x['shipment_id']=='SHP-ANOM-000003' for x in challenge_ship))
    check('challenge risk missing source/time present',any(not x['source'].strip() or not x['observed_at'].strip() for x in challenge_risk))
    check('challenge future-data case present',any(x['as_of_date']<x['source_observation_time'] for x in challenge_scores))
    detected=set()
    for x in challenge_ship:
        if x.get('po_id') not in po_map: detected.add('SHIPMENT_WITHOUT_PO')
        if x.get('delivery_date','') < x.get('dispatch_date',''): detected.add('DELIVERY_BEFORE_DISPATCH')
        if x.get('supplier_id') not in x.get('site_id',''): detected.add('SUPPLIER_SITE_MISMATCH')
    if any(not x.get('source','').strip() or not x.get('observed_at','').strip() for x in challenge_risk): detected.add('MISSING_RISK_SOURCE_OR_TIME')
    if any(x.get('as_of_date','') < x.get('source_observation_time','') for x in challenge_scores): detected.add('FUTURE_DATA_LEAKAGE')
    check('rule-based challenge detection covers all five scenarios',required<=detected,str(sorted(detected)))
    base_ids=ids(sh,'shipment_id'); check('challenge anomalies isolated from baseline',not any(x['shipment_id'].startswith('SHP-ANOM-') for x in sh) and not any(x['shipment_id'] in base_ids for x in challenge_ship))
    states=data['state_events.csv']
    check('state events reference known entities',all((x['entity_type'].upper() in ('PURCHASEORDER','PURCHASE_ORDER') and x['entity_id'] in po_map) or (x['entity_type'].upper()=='SUPPLIER' and x['entity_id'] in sid) for x in states))
    check('state events have timestamps and transitions',all(x.get('event_time','').strip() and x.get('to_state','').strip() for x in states))
    check('ground truth labels are separate and documented',all(x.get('ground_truth_label')=='ANOMALY' and x.get('expected_detection','').strip() for x in truth) and not any(x.get('issue_type') for x in su))
    # Hash manifest and documentation/provenance.
    manifest=json.loads((d/'source_manifest.json').read_text(encoding='utf-8')); hash_ok=True
    for name,meta in manifest.get('files',{}).items():
        fp=d/name
        if not fp.exists() or hashlib.sha256(fp.read_bytes()).hexdigest()!=meta.get('sha256') or fp.stat().st_size!=meta.get('bytes'): hash_ok=False; break
    check('source manifest SHA-256 hashes verify',hash_ok)
    project=d.parent.parent; guide=project/'SYNTHETIC_DATA_GUIDE.md'; readme=project/'README.md'
    docs=''.join(x.read_text(encoding='utf-8',errors='ignore') for x in [guide,readme] if x.exists()).lower()
    check('documentation states synthetic provenance and lawful origin', 'synthetic' in docs and ('no real' in docs or 'not real' in docs or 'artificially generated' in docs or 'generated' in docs), 'documentation should explicitly state no real supplier/person data is used')
    out=d/'acceptance_report.csv'
    with out.open('w',newline='',encoding='utf-8') as f:
        w=csv.writer(f); w.writerow(['criterion','status','details']); w.writerows([(n,'PASS' if ok else 'FAIL',detail) for n,ok,detail in checks])
    for n,ok,detail in checks: print(('PASS' if ok else 'FAIL')+f' | {n}'+(f' | {detail}' if detail else ''))
    passed=sum(ok for _,ok,_ in checks); print(f'\nAcceptance checks: {passed}/{len(checks)} passed. Report: {out}')
    return 0 if all(ok for _,ok,_ in checks) else 1
if __name__=='__main__': sys.exit(main())
