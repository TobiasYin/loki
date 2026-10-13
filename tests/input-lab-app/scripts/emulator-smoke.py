#!/usr/bin/env python3
"""ADB emulator smoke: actual Android dispatch, never Thor/physical-controller qualification.
No setup IPC: operate only visible controls. Requires Python stdlib and an already booted emulator.
"""
import argparse, hashlib, json, pathlib, re, subprocess, time, xml.etree.ElementTree as ET
p=argparse.ArgumentParser()
p.add_argument('--adb',default='adb'); p.add_argument('--port',default='5037'); p.add_argument('--serial',required=True)
p.add_argument('--apk',default=str(pathlib.Path(__file__).resolve().parents[1]/'app/build/outputs/apk/debug/app-debug.apk'))
p.add_argument('--out',default=str(pathlib.Path(__file__).resolve().parents[1]/'evidence/emulator-smoke'))
a=p.parse_args()
if not a.serial.startswith('emulator-'): raise SystemExit('This smoke script only operates an emulator.')
base=[a.adb,'-P',a.port,'-s',a.serial]
out=pathlib.Path(a.out); out.mkdir(parents=True,exist_ok=True)
def adb(*args,binary=False,check=True):
    result=subprocess.run(base+list(args),capture_output=True,check=check,timeout=35)
    return result.stdout if binary else result.stdout.decode()
def shell(*args): return adb('shell',*args)
def nodes():
    status=shell('uiautomator','dump','/sdcard/input-lab-smoke-ui.xml')
    if 'dumped to' not in status: raise RuntimeError('UI not idle: '+status)
    return list(ET.fromstring(shell('cat','/sdcard/input-lab-smoke-ui.xml')).iter('node'))
def bounds(node): return list(map(int,re.findall(r'\d+',node.attrib['bounds'])))
def tap_node(node):
    x1,y1,x2,y2=bounds(node); shell('input','tap',str((x1+x2)//2),str((y1+y2)//2))
def click(text=None,desc=None):
    n=next(n for n in nodes() if (text is not None and n.attrib.get('text')==text) or (desc is not None and n.attrib.get('content-desc')==desc))
    tap_node(n)
def select(desc,text):
    click(desc=desc)
    for _ in range(6):
        ns=nodes(); match=next((n for n in ns if n.attrib.get('text')==text),None)
        if match is not None: tap_node(match); time.sleep(.3); return
        menu=next(n for n in ns if n.attrib.get('class')=='android.widget.ListView')
        x1,y1,x2,y2=bounds(menu); x=(x1+x2)//2
        visible=[n.attrib.get('text','') for n in ns if n.attrib.get('text','').startswith('LAB-')]
        scroll_up=bool(text.startswith('LAB-') and visible and int(text[4:6])<int(visible[0][4:6]))
        start_y,end_y=(y1+50,y2-50) if scroll_up else (y2-50,y1+50)
        shell('input','swipe',str(x),str(start_y),str(x),str(end_y),'300')
        time.sleep(.3)
    raise RuntimeError('Cannot select '+text)
def dirs(): return set(shell('run-as','dev.thor.inputlab','ls','files/reports').split())
def points():
    n=next(n for n in nodes() if n.attrib.get('content-desc','').startswith('Input Lab self-drawn arena'))
    x1,y1,x2,y2=bounds(n)
    return (int(x1+(x2-x1)*.24),int(y1+(y2-y1)*.48)),(int(x1+(x2-x1)*.76),int(y1+(y2-y1)*.48))
def touch(point): shell('input','tap',*[str(v) for v in point])
def read_report(before,label,expected):
    time.sleep(5.8)
    changed=dirs()-before
    assert len(changed)==1,changed
    name=changed.pop()
    folder=out/label; folder.mkdir(exist_ok=True)
    for f in ('report.json','events.jsonl','summary.md','recovery.jsonl','interrupted-metadata.json'):
        contents=adb('shell','run-as','dev.thor.inputlab','cat',f'files/reports/{name}/{f}',check=False)
        if not contents: continue
        # Private debug extraction: hash emulator descriptor before committing any evidence.
        contents=re.sub(r'"descriptor"\s*:\s*"(?:\\.|[^"\\])*"',lambda m:'"descriptorHash":'+json.dumps(hashlib.sha256(m.group().encode()).hexdigest()),contents)
        (folder/f).write_text(contents)
    r=json.loads((folder/'report.json').read_text())
    assert r['status']==expected,(label,r['status'],r.get('assertions'))
    rows=[json.loads(s) for s in (folder/'events.jsonl').read_text().splitlines()]
    if 'eventPrefixSha256' in r:
        original=adb('exec-out','run-as','dev.thor.inputlab','cat',f'files/reports/{name}/events.jsonl',binary=True)
        assert hashlib.sha256(original[:r['eventPrefixBytes']]).hexdigest()==r['eventPrefixSha256']
    if expected=='inconclusive' and label=='log-budget': assert r['integrity']['incomplete'] and r['integrity']['droppedSamples']>0
    sequences=[r['sequence'] for r in rows]
    assert sequences==list(range(1,len(rows)+1)),sequences
    raw=[r for r in rows if r['eventKind'].startswith('raw_') and not r['late']]
    print(label,expected,'raw=',len(raw),flush=True)
    return dict(prefixHashVerifiedBeforeRedaction=("eventPrefixSha256" in r),label=label,caseId=r.get('metadata',{}).get('caseId'),expected=expected,actual=r['status'],rawEvents=len(raw),attemptId=name)
print(adb('install','-r',a.apk),flush=True)
shell('am','force-stop','dev.thor.inputlab'); shell('am','start','-n','dev.thor.inputlab/.LabActivity'); time.sleep(1)
select('Evidence mode selector','manual_platform_api')
results=[]
def run(label,case,expected,stimulus):
    select('Case selector',case); start,end=points(); before=dirs(); click(text='ARM'); time.sleep(.8); stimulus(start,end)
    results.append(read_report(before,label,expected))
run('single-tap','LAB-02','pass',lambda s,e:touch(s))
assert results[-1]['rawEvents']==2,results[-1]
run('no-action','LAB-14','fail',lambda s,e:None)
run('wrong-target','LAB-02','fail',lambda s,e:touch(e))
run('long-press','LAB-04-LONG','pass',lambda s,e:shell('input','swipe',*map(str,(*s,*s,750))))
run('swipe','LAB-04-SWIPE','pass',lambda s,e:shell('input','swipe',*map(str,(*s,*e,450))))
run('keyboard-button-a','LAB-06','fail',lambda s,e:shell('input','keyevent','96'))
run('sequential-gamepad-buttons','LAB-08','fail',lambda s,e:shell('input','gamepad','keyevent','96','97'))
run('two-step-task','LAB-11','pass',lambda s,e:(touch(s),touch(e)))
click(text='FAULT PROBE'); click(text='budget')
run('log-budget','LAB-13','inconclusive',lambda s,e:touch(s))
click(text='FAULT PROBE'); click(text='none')
# Interrupted attempt persists a durable marker and is recovered with a new epoch.
select('Case selector','LAB-13'); before=dirs(); click(text='ARM'); time.sleep(1)
shell('am','force-stop','dev.thor.inputlab'); shell('am','start','-n','dev.thor.inputlab/.LabActivity')
results.append(read_report(before,'process-restart','inconclusive'))
(out/'final.png').write_bytes(adb('exec-out','screencap','-p',binary=True))
summary={'environment':'Android API36 arm64 emulator; single display; no Thor hardware','coverage':'ADB shell input -> Android Activity dispatch. No product gateway, physical baseline or cross-display injection proof.', 'apkSha256':hashlib.sha256(pathlib.Path(a.apk).read_bytes()).hexdigest(),'results':results}
(out/'results.json').write_text(json.dumps(summary,indent=2))
manifest={str(f.relative_to(out)):hashlib.sha256(f.read_bytes()).hexdigest() for f in out.rglob('*') if f.is_file() and f.name!='evidence-manifest.json'}
(out/'evidence-manifest.json').write_text(json.dumps({'privacyTransformation':'descriptor hashed; receiver eventPrefix hash refers to pre-redaction local prefix, verified before redaction by script','fileHashes':manifest},indent=2))
print(json.dumps(summary,indent=2),flush=True)
