#!/usr/bin/env python3
"""Additive live Thor UI/session acceptance; preserves real credentials and history."""
import argparse,hashlib,json,os,pathlib,re,shlex,shutil,subprocess,time
p=argparse.ArgumentParser()
p.add_argument('--serial',required=True);p.add_argument('--adb-port',required=True,type=int)
p.add_argument('--output',required=True,type=pathlib.Path)
p.add_argument('--suite',choices=['context','ime','readback','receiver','install','voice-hold'],required=True)
p.add_argument('--expanded',action='store_true',help='Receiver suite: autonomous visual targeting plus gamepad button and axes')
p.add_argument('--cleanup-session',help='Readback only: disable test control capabilities in this exact selected session')
p.add_argument('--drop-pointer-capture',action='store_true',help='Voice hold only: test release outside the button after losing capture')
a=p.parse_args();root=pathlib.Path(__file__).resolve().parents[1];os.chdir(root)
assert not a.cleanup_session or (a.suite=='readback' and re.fullmatch(r'[0-9a-f-]{36}',a.cleanup_session))
assert not a.drop_pointer_capture or a.suite=='voice-hold'
assert not a.expanded or a.suite=='receiver'
assert not a.output.exists(),'Use a new evidence directory; do not overwrite failures'
a.output.mkdir(parents=True)
adb_bin=pathlib.Path(os.environ.get('ANDROID_HOME',pathlib.Path.home()/'Library/Android/sdk'))/'platform-tools/adb'
prefix=[str(adb_bin),'-P',str(a.adb_port),'-s',a.serial]
def adb(*args,body=None):return subprocess.check_output(prefix+list(args),input=body)
assert not a.serial.startswith('emulator-')
assert adb('shell','getprop','ro.product.model').strip()==b'AYN Thor'
assert adb('shell','getprop','ro.kernel.qemu').strip()!=b'1'
keys=['enabled_accessibility_services','accessibility_enabled']
original={k:adb('shell','settings','get','secure',k).decode().strip() for k in keys}
component='dev.thor.agent/dev.thor.tools.diagnostic.ThorAccessibilityService'
classes={'context':'PhysicalResumeTest','ime':'PhysicalImeTest','readback':'PhysicalDeviceStateTest','receiver':'PhysicalDeviceFlowTest','install':'EmulatorLiveInstallTest','voice-hold':'PhysicalVoiceHoldTest'}
outputs={'context':['physical-resume'],'ime':['physical-ime.json','physical-ime-settled.png'],'readback':['physical-device-final-state.json'],'receiver':['physical-cloud-input.json','physical-cloud-logical-report.json','physical-cloud-frame-report.json'],'install':['physical-live-install.json','physical-live-install.png'],'voice-hold':['physical-voice-hold.json']}
if a.expanded:outputs['receiver']+=['physical-cloud-button-report.json','physical-cloud-axes-report.json']
metadata={'serial':a.serial,'port':a.adb_port,'physical':True,'suite':a.suite,'sourceCommit':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),'sourceDirty':bool(subprocess.check_output(['git','status','--porcelain'],text=True).strip()),'apks':{},'sourceFiles':{}}
for f in ['app/src/androidTest/java/dev/thor/agent/PhysicalResumeTest.kt','app/src/androidTest/java/dev/thor/agent/PhysicalImeTest.kt','app/src/androidTest/java/dev/thor/agent/PhysicalDeviceFlowTest.kt','app/src/androidTest/java/dev/thor/agent/EmulatorLiveInstallTest.kt','app/src/androidTest/java/dev/thor/agent/PhysicalDeviceStateTest.kt','app/src/androidTest/java/dev/thor/agent/PhysicalVoiceHoldTest.kt','app/src/androidTest/java/dev/thor/agent/PhysicalCloudVoiceTest.kt']:
 metadata['sourceFiles'][f]=hashlib.sha256(pathlib.Path(f).read_bytes()).hexdigest()
for apk in ['app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']:
 target=a.output/'apks'/pathlib.Path(apk).name;target.parent.mkdir(exist_ok=True);shutil.copyfile(apk,target);metadata['apks'][apk]=hashlib.sha256(target.read_bytes()).hexdigest()
(a.output/'metadata.json').write_text(json.dumps(metadata,indent=2)+'\n')
try:
 services=':'.join(s for s in original[keys[0]].split(':') if s not in [component,'null',''])
 adb('shell','settings put secure '+keys[0]+' '+shlex.quote(services) if services else 'settings delete secure '+keys[0]);time.sleep(.6)
 adb('shell','am','force-stop','dev.thor.agent')
 for apk in (a.output/'apks').glob('*.apk'):assert b'Success' in adb('install','-r',str(apk))
 if a.suite=='context':
  guide=(root/'tests/skill-live-evidence/stardew-valley-guide.zip').read_bytes()
  adb('shell','run-as','dev.thor.agent','sh','-c',"'umask 077; cat > cache/physical-guide.zip'",body=guide)
  # Archive prior additive acceptance reports without removing product data.
  exists=subprocess.run(prefix+['shell','run-as','dev.thor.agent','test','-d','files/physical-resume'],stdout=subprocess.DEVNULL).returncode==0
  if exists:adb('shell','run-as','dev.thor.agent','mv','files/physical-resume',f'files/physical-resume-{time.time_ns()}')
 else:
  for name in outputs[a.suite]:
   exists=subprocess.run(prefix+['shell','run-as','dev.thor.agent','test','-f','files/'+name],stdout=subprocess.DEVNULL).returncode==0
   if exists:adb('shell','run-as','dev.thor.agent','mv','files/'+name,f'files/{name}.previous-{time.time_ns()}')
 adb('shell','input','keyevent','224')
 result=adb('shell','am','instrument','-w','-r','-e','physicalAcceptance','true','-e','physicalLiveInstall',str(a.suite=='install').lower(),'-e','reuseProfile','true','-e','chatDisplay','4','-e','labDisplay','0',*(['-e','expanded','true'] if a.expanded else []),*(['-e','cleanupSession',a.cleanup_session] if a.cleanup_session else []),*(['-e','dropPointerCapture','true'] if a.drop_pointer_capture else []),'-e','class','dev.thor.agent.'+classes[a.suite],'dev.thor.agent.test/androidx.test.runner.AndroidJUnitRunner')
 (a.output/'suite.log').write_bytes(result);print(result.decode(),flush=True)
 metadata['passed']=bool(re.search(rb'OK \(\d+ tests?\)',result)) and b'FAILURES' not in result
 assert metadata['passed'],'Physical acceptance failed; original evidence retained'
finally:
 try:
  for name in outputs[a.suite]:
   with (a.output/(name.replace('/','-')+'.tar')).open('wb') as f:
    subprocess.run(prefix+['exec-out','run-as','dev.thor.agent','tar','-cf','-','-C','files',name],stdout=f,stderr=subprocess.DEVNULL)
  if a.suite=='receiver':
   for kind in (['logical','frame','button','axes'] if a.expanded else ['logical','frame']):
    raw=subprocess.run(prefix+['exec-out','run-as','dev.thor.agent','cat',f'files/physical-cloud-{kind}-report.json'],capture_output=True)
    if raw.returncode or not raw.stdout.lstrip().startswith(b'{'):
     (a.output/f'{kind}-collection-error.txt').write_bytes(raw.stdout+raw.stderr);continue
    report=json.loads(raw.stdout);(a.output/f'{kind}-report.json').write_bytes(raw.stdout)
    events=adb('exec-out','run-as','dev.thor.inputlab','cat',f"files/reports/{report['metadata']['attemptId']}/events.jsonl")
    (a.output/f'{kind}-events.jsonl').write_bytes(events)
    assert hashlib.sha256(events[:report['eventPrefixBytes']]).hexdigest()==report['eventPrefixSha256']
    assert not report['integrity']['incomplete'] and report['integrity']['droppedSamples']==0
 finally:
  adb('shell','run-as','dev.thor.agent','rm','-f','cache/physical-guide.zip')
  for key,value in original.items():adb('shell',f'settings delete secure {key}' if value=='null' else f'settings put secure {key} {shlex.quote(value)}')
  (a.output/'metadata.json').write_text(json.dumps(metadata,indent=2)+'\n')
