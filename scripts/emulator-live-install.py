#!/usr/bin/env python3
"""Explicit emulator-only, real model -> GitHub -> Native download -> confirmed shell-UID install.
Credential JSON is read from stdin and imported into Native Keystore, never printed.
This runner does not download or install the target APK on the model's behalf.
"""
import argparse, hashlib, json, os, pathlib, re, subprocess, sys
p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--reuse-profile',action='store_true');p.add_argument('--reject-install',action='store_true');p.add_argument('--output',default='build/integration/live-install');a=p.parse_args()
assert re.fullmatch(r'emulator-\d+',a.serial),'Emulator serial required'
root=pathlib.Path(__file__).resolve().parents[1];os.chdir(root)
adb=str(pathlib.Path(os.environ.get('ANDROID_HOME',pathlib.Path.home()/'Library/Android/sdk'))/'platform-tools/adb');prefix=[adb,'-s',a.serial]
def call(*args,input=None):return subprocess.check_output(prefix+list(args),input=input)
assert call('shell','getprop','ro.kernel.qemu').strip()==b'1','Physical device refused'
out=root/a.output;out.mkdir(parents=True,exist_ok=True)
assert not (out/'result.json').exists(),'Use a fresh evidence directory'
original={k:call('shell','settings','get','secure',k).decode().strip() for k in ['enabled_accessibility_services','accessibility_enabled']}
own='dev.thor.agent/dev.thor.tools.diagnostic.ThorAccessibilityService'
try:
 if own in original['enabled_accessibility_services'].split(':'):
  others=':'.join(v for v in original['enabled_accessibility_services'].split(':') if v not in [own,'null',''])
  call('shell','settings','put','secure','enabled_accessibility_services',others) if others else call('shell','settings','delete','secure','enabled_accessibility_services')
 for apk in ['app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']:
  assert b'Success' in call('install','-r',apk)
 if not a.reuse_profile:
  config=json.load(sys.stdin);assert set(config)=={'modelKey'} and isinstance(config['modelKey'],str) and config['modelKey']
  call('shell','run-as','dev.thor.agent','sh','-c',"'umask 077; mkdir -p cache; cat > cache/emulator-live-credentials.json'",input=json.dumps(config).encode());config.clear()
 result=call('shell','am','instrument','-w','-r','-e','emulatorLiveInstall','true','-e','reuseProfile',str(a.reuse_profile).lower(),'-e','rejectInstall',str(a.reject_install).lower(),'-e','class','dev.thor.agent.EmulatorLiveInstallTest','dev.thor.agent.test/androidx.test.runner.AndroidJUnitRunner')
 (out/'suite.log').write_bytes(result);print(result.decode(),flush=True)
 report=call('exec-out','run-as','dev.thor.agent','cat','files/emulator-live-install.json');(out/'result.json').write_bytes(report)
 if b'OK (1 test)' in result and not a.reject_install:
  (out/'chat.png').write_bytes(call('exec-out','run-as','dev.thor.agent','cat','files/emulator-live-install.png'))
 metadata={'emulator':a.serial,'fingerprint':call('shell','getprop','ro.build.fingerprint').decode().strip(),'sourceCommit':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),'workingChanges':subprocess.check_output(['git','status','--porcelain'],text=True).splitlines(),'apkSha256':hashlib.sha256((root/'app/build/outputs/apk/debug/app-debug.apk').read_bytes()).hexdigest(),'reportSha256':hashlib.sha256(report).hexdigest()}
 (out/'metadata.json').write_text(json.dumps(metadata,indent=2)+'\n')
 assert b'OK (1 test)' in result and json.loads(report).get('passed') is True,'Real model install acceptance failed'
finally:
 call('shell','run-as','dev.thor.agent','rm','-f','cache/emulator-live-credentials.json')
 for k,v in original.items():
  call('shell','settings','delete','secure',k) if v=='null' else call('shell','settings','put','secure',k,v)
