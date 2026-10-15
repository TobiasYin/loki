#!/usr/bin/env python3
"""Run only on an explicitly selected emulator; controlled service replaces only the remote model."""
import argparse, pathlib, subprocess, os, time, socket, json, hashlib, re
p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--output',default='build/integration');p.add_argument('--lab-display',required=True,type=int);p.add_argument('--adb',default=str(pathlib.Path(os.environ.get('ANDROID_HOME',''))/'platform-tools/adb'));p.add_argument('--fixture-running',action='store_true');p.add_argument('--require-shizuku',action='store_true');a=p.parse_args()
root=pathlib.Path(__file__).resolve().parents[1];os.chdir(root);output=(root/a.output).resolve();output.mkdir(parents=True,exist_ok=True)
assert re.fullmatch(r'emulator-\d+',a.serial),'Only an explicit emulator serial is allowed'
def adb(*args,binary=False):return subprocess.check_output([a.adb,'-s',a.serial,*args],text=not binary)
assert adb('shell','getprop','ro.kernel.qemu').strip()=='1','Selected target is not an emulator'
assert a.lab_display != 0,'Separate lab display is required for this software dispatch test'
server=None
own_service='dev.thor.agent/dev.thor.tools.diagnostic.ThorAccessibilityService'
original_services=adb('shell','settings','get','secure','enabled_accessibility_services').strip()
original_enabled=adb('shell','settings','get','secure','accessibility_enabled').strip()
# Instrumentation kills the target process on launch. Do not leave its old bound
# Accessibility service marked crashed when installing over a previous emulator run.
other_services=':'.join(s for s in original_services.split(':') if s not in (own_service,'null',''))
try:
 if own_service in original_services.split(':'):
  if other_services:adb('shell','settings','put','secure','enabled_accessibility_services',other_services)
  else:adb('shell','settings','delete','secure','enabled_accessibility_services')
 if not a.fixture_running:
  for port in (8877,8878):
   with socket.socket() as probe:probe.bind(('127.0.0.1',port))
  server=subprocess.Popen(['node','scripts/model-fixture.mjs'],stdout=open(output/'model-fixture.log','w'),env={**os.environ,'THOR_FIXTURE_OUTPUT':str(output)},stderr=subprocess.STDOUT)
  for _ in range(50):
   try:
    with socket.create_connection(('127.0.0.1',8877),timeout=.2):break
   except OSError:time.sleep(.1)
 for port in (8877,8878):adb('reverse',f'tcp:{port}',f'tcp:{port}')
 for apk in ['tests/input-lab-app/app/build/outputs/apk/debug/input-lab-debug.apk','app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk','tests/input-lab-app/app/build/outputs/apk/androidTest/debug/input-lab-debug-androidTest.apk']:
  print(adb('install','-r',apk).strip(),flush=True)
 adb('shell','pm','grant','dev.thor.agent','android.permission.RECORD_AUDIO')
 result=adb('shell','am','instrument','-w','-r','-e','chatDisplay','0','-e','softwareAcceptance','true','-e','class','dev.thor.agent.NativeStorageTest,dev.thor.agent.PhysicalFeaturesTest,dev.thor.agent.PhysicalStorageRegressionTest,dev.thor.agent.WebViewFlowTest','-e','labDisplay',str(a.lab_display),'-e','disposableInstallation','true','-e','requireShizuku',str(a.require_shizuku).lower(),'dev.thor.agent.test/androidx.test.runner.AndroidJUnitRunner')
 (output/'product-suite-final.log').write_text(result);print(result,flush=True)
 assert re.search(r'OK \(\d+ tests\)',result) and 'FAILURES' not in result,'Product instrumentation failed'
 result=adb('shell','am','instrument','-w','dev.thor.inputlab.test/dev.thor.inputlab.SnapshotInstrumentation')
 (output/'lab-parser-final.log').write_text(result);print(result,flush=True)
 assert '4 Android parser fixture checks passed.' in result,'Lab parser instrumentation failed'
 if a.require_shizuku:(output/'integrated-shell-result.json').write_bytes(adb('exec-out','run-as','dev.thor.agent','cat','files/integrated-shell-result.json',binary=True))
 for name in ['integrated-probe-0.json','integrated-probe-1.json','integrated-probe-2.json','integrated-text-only-profile.json','integrated-parallel-approval.json','integrated-concurrent-sessions.json','integrated-settings-page.json','integrated-composer-native.png','integrated-settings-native.png','integrated-voice-settings-native.png','physical-features.json','physical-storage-streaming-regression.json','physical-storage-regression.json','integrated-lab-report.json','integrated-lab-native.json','integrated-capture-result.json','integrated-frame-report.json','integrated-frame-native.json','integrated-chat.png']:
  (output/name).write_bytes(adb('exec-out','run-as','dev.thor.agent','cat','files/'+name,binary=True))
 report=json.loads((output/'integrated-lab-report.json').read_text());attempt=report['metadata']['attemptId']
 raw=adb('exec-out','run-as','dev.thor.inputlab','cat',f'files/reports/{attempt}/events.jsonl',binary=True)
 assert not report['integrity']['incomplete'] and report['integrity']['droppedSamples']==0
 assert hashlib.sha256(raw[:report['eventPrefixBytes']]).hexdigest()==report['eventPrefixSha256']
 events=[json.loads(line) for line in raw.splitlines()]
 assert [e['sequence'] for e in events]==list(range(1,len(events)+1))
 dispatch=[e for e in events if e.get('recordOrigin')=='android_dispatch' and e.get('eventKind')=='raw_input']
 # Raw receiver eventKind is copied from the platform capture, not forged by this runner.
 assert report['sceneAfter']['rawCount']==2 and report['sceneAfter']['hits']==1
 (output/'integrated-lab-events.jsonl').write_bytes(raw)
 assert report['status']=='pass' and report['agentRunId'] == json.loads((output/'integrated-lab-native.json').read_text())['runId']
 evidence=['integrated-probe-0.json','integrated-probe-1.json','integrated-probe-2.json','integrated-text-only-profile.json','integrated-parallel-approval.json','integrated-concurrent-sessions.json','integrated-settings-page.json','integrated-composer-native.png','integrated-settings-native.png','integrated-voice-settings-native.png','physical-features.json','physical-storage-streaming-regression.json','physical-storage-regression.json','integrated-lab-report.json','integrated-lab-native.json','integrated-chat.png','integrated-lab-events.jsonl','product-suite-final.log','lab-parser-final.log']
 if a.require_shizuku:evidence.append('integrated-shell-result.json')
 metadata={'sourceCommit':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),'serial':a.serial,'labDisplay':a.lab_display,'emulator':True,'requireShizuku':a.require_shizuku,'deviceFingerprint':adb('shell','getprop','ro.build.fingerprint').strip(),'webView':adb('shell','dumpsys','webviewupdate'),'reportQualification':report['receiverQualificationVersion'],'files':{name:hashlib.sha256((output/name).read_bytes()).hexdigest() for name in evidence}}
 metadata['workingTreeChanges']=subprocess.check_output(['git','status','--short'],text=True).splitlines()
 metadata['testedSources']={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in [pathlib.Path('scripts/model-fixture.mjs'),pathlib.Path('scripts/emulator-verify.py'),*pathlib.Path('app/src/androidTest/java/dev/thor/agent').glob('*.kt')]}
 metadata['apks']={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in [pathlib.Path('app/build/outputs/apk/debug/app-debug.apk'),pathlib.Path('app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')]}
 (output/'emulator-verification.json').write_text(json.dumps(metadata,ensure_ascii=False,indent=2))
finally:
 for port in (8877,8878):
  subprocess.run([a.adb,'-s',a.serial,'reverse','--remove',f'tcp:{port}'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 if server:server.terminate();server.wait(timeout=5)
 for key,value in [('enabled_accessibility_services',original_services),('accessibility_enabled',original_enabled)]:
  if value=='null':adb('shell','settings','delete','secure',key)
  else:adb('shell','settings','put','secure',key,value)
