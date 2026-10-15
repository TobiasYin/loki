#!/usr/bin/env python3
"""Offline integrity checks for the checked-in physical evidence; never contacts a device."""
import hashlib,json,pathlib,re
root=pathlib.Path(__file__).resolve().parents[1]/'tests/physical-evidence'
manifest=json.loads((root/'manifest.json').read_text())
for item in manifest['files']:
    path=root/item['path'];data=path.read_bytes()
    assert len(data)==item['bytes'] and hashlib.sha256(data).hexdigest()==item['sha256'],path
    if path.suffix in ['.json','.jsonl','.log','.txt']:
        assert not re.search(rb'oc_sk_[A-Za-z0-9_-]{20,}|BSA[A-Za-z0-9_-]{20,}',data),f'Credential-shaped value in {path}'
reports=0
for path in root.rglob('*.json'):
    report=json.loads(path.read_text())
    if not isinstance(report,dict) or 'eventPrefixSha256' not in report:continue
    raw_path=path.parent/'events.jsonl'
    if not raw_path.exists():
        own_name=path.with_name(path.name.replace('-report.json','-events.jsonl'))
        kind='frame' if 'frame' in path.name else 'logical'
        raw_path=own_name if own_name.exists() else path.parent/f'{kind}-events.jsonl'
    data=raw_path.read_bytes();prefix=data[:report['eventPrefixBytes']]
    assert len(prefix)==report['eventPrefixBytes']
    assert hashlib.sha256(prefix).hexdigest()==report['eventPrefixSha256'],path
    events=[json.loads(line) for line in data.splitlines()]
    assert [e['sequence'] for e in events]==list(range(1,len(events)+1)),raw_path
    assert not report['integrity']['incomplete'] and report['integrity']['droppedSamples']==0,path
    reports+=1
print(json.dumps({'files':len(manifest['files']),'receiverReports':reports,'prefixHashes':'verified','sequences':'contiguous','credentialPatternMatches':0},indent=2))
