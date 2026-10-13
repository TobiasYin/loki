#!/usr/bin/env python3
"""Export bounded reproducible evidence; raw receiver prefix is checked before descriptor redaction."""
import json, pathlib, hashlib, shutil, xml.etree.ElementTree as ET
root=pathlib.Path(__file__).resolve().parents[1];src=root/'build/integration';dst=root/'tests/integration-evidence';dst.mkdir(parents=True,exist_ok=True)
metadata=json.loads((src/'emulator-verification.json').read_text())
report=json.loads((src/'integrated-lab-report.json').read_text());raw=(src/'integrated-lab-events.jsonl').read_bytes()
assert hashlib.sha256(raw[:report['eventPrefixBytes']]).hexdigest()==report['eventPrefixSha256']
def redact(v):
 if isinstance(v,dict):return {k:(hashlib.sha256(str(x).encode()).hexdigest() if k=='descriptor' else redact(x)) for k,x in v.items()}
 if isinstance(v,list):return [redact(x) for x in v]
 return v
for name in ['integrated-lab-report.json','integrated-lab-native.json','integrated-shell-result.json','integrated-capture-result.json','artifacts.json']:
 (dst/name).write_text(json.dumps(redact(json.loads((src/name).read_text())),ensure_ascii=False,indent=2)+'\n')
(dst/'integrated-lab-events.jsonl').write_text(''.join(json.dumps(redact(json.loads(line)),ensure_ascii=False)+'\n' for line in raw.splitlines()))
for name in ['integrated-chat.png','product-suite-final.log','lab-parser-final.log','commit-verify.log','model-requests.jsonl','shizuku-signature.txt','shizuku-start.log']:
 shutil.copyfile(src/name,dst/name)
counts={}
for directory in ['native-bridge/core','tools/core','integration/device-authority','app','tests/input-lab-app/app']:
 totals={'tests':0,'failures':0,'errors':0,'skipped':0}
 for path in (root/directory/'build/test-results').glob('**/TEST-*.xml'):
  e=ET.parse(path).getroot()
  for k in totals:totals[k]+=int(e.attrib.get(k,0))
 counts[directory]=totals
metadata['jvmTests']=counts;metadata['rawPrefixCheckedBeforeRedaction']=True;metadata['descriptorPolicy']='sha256';metadata['receiverPrefixHashMeaning']='original private receiver bytes; exported file hashes below verify redacted evidence'
metadata['files']={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in dst.iterdir() if p.is_file() and p.name!='verification.json'}
(dst/'verification.json').write_text(json.dumps(metadata,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({'sourceCommit':metadata['sourceCommit'],'jvmTests':counts,'files':len(metadata['files'])},ensure_ascii=False,indent=2))
