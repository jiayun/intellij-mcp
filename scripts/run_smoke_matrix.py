#!/usr/bin/env python3
"""Run SDK-backed IDE smoke fixtures and record every pass/failure without stopping the matrix."""
import argparse,json,pathlib,subprocess,sys
p=argparse.ArgumentParser();p.add_argument('--ide',required=True);p.add_argument('--plugins');p.add_argument('--license-file');p.add_argument('--languages',required=True);p.add_argument('--reports',required=True);p.add_argument('--mcp',action='store_true');a=p.parse_args()
repo=pathlib.Path(__file__).resolve().parent.parent
out=pathlib.Path(a.reports).resolve();out.mkdir(parents=True,exist_ok=True)
summary=[]
for lang in a.languages.split(','):
 command=[sys.executable,str(repo/'scripts/run_ide_smoke.py'),a.ide,str(repo/'docs/fixtures'/lang),str(out/(lang+'.json'))]
 for key in ['plugins','license_file']:
  if getattr(a,key):command += ['--'+key.replace('_','-'),getattr(a,key)]
 if a.mcp:command.append('--mcp')
 if lang.startswith('go') or lang.startswith('vue'):command.append('--ui')
 with (out/(lang+'-runner.log')).open('w') as log:
  run=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT)
 report=out/(lang+'.json')
 data=json.loads(report.read_text()) if report.exists() else {}
 item=dict(language=lang,exit=run.returncode,passed=data.get('passed',False),checks=len(data.get('checks',[])))
 summary.append(item);(out/'summary.json').write_text(json.dumps(summary,indent=2)+'\n');print(item,flush=True)
raise SystemExit(0 if all(x['passed'] for x in summary) else 1)
