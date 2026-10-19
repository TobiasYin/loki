#!/usr/bin/env python3
"""Thor fixture gate, restricted to an app installation this runner created fresh.

Never use this runner after importing real credentials/history. It resets only its
disposable loki fixture data and restores existing Accessibility settings.
Physical input baseline and live cloud services are separate acceptance steps.
"""
import argparse, hashlib, json, os, pathlib, re, shlex, socket, subprocess, time

p = argparse.ArgumentParser()
p.add_argument('--serial', required=True)
p.add_argument('--adb-port', required=True, type=int)
p.add_argument('--upper-display', required=True, type=int)
p.add_argument('--chat-display', required=True, type=int)
p.add_argument('--class-name', default='dev.thor.agent.WebViewFlowTest,dev.thor.agent.NativeStorageTest')
p.add_argument('--require-shizuku', action='store_true')
a = p.parse_args()
root = pathlib.Path(__file__).resolve().parents[1]
os.chdir(root)
out = root / 'build/physical'
out.mkdir(parents=True, exist_ok=True)
adb_bin = pathlib.Path(os.environ.get('ANDROID_HOME', pathlib.Path.home() / 'Library/Android/sdk')) / 'platform-tools/adb'
def adb(*args, binary=False, input=None):
    return subprocess.check_output([str(adb_bin), '-P', str(a.adb_port), '-s', a.serial, *args], text=not binary, input=input)
assert adb('shell','getprop','ro.product.model').strip() == 'AYN Thor', 'Explicit Thor target required'
assert adb('shell','getprop','ro.kernel.qemu').strip() != '1', 'Physical device required'
assert a.upper_display != a.chat_display
marker = out / 'disposable-installation.json'
if not marker.exists():
    assert not adb('shell','pm','list','packages','dev.thor').strip(), 'Existing Thor app data must be preserved; fixture runner refused'
    for apk in ['app/build/outputs/apk/debug/app-debug.apk', 'tests/input-lab-app/app/build/outputs/apk/debug/input-lab-debug.apk']:
        assert 'Success' in adb('install', apk)
    marker.write_text(json.dumps({'serial':a.serial,'port':a.adb_port,'fresh':True}))
assert json.loads(marker.read_text()) == {'serial':a.serial,'port':a.adb_port,'fresh':True}
# Importing a real profile permanently locks out fixture resets for this installation.
assert not (out / 'real-profile-imported').exists(), 'Real credentials/history imported; fixture resets refused'
for apk in ['app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']:
    assert 'Success' in adb('install','-r',apk)
original = {k:adb('shell','settings','get','secure',k).strip() for k in ['enabled_accessibility_services','accessibility_enabled']}
reverse_existed = any(line.split()[-2:] == ['tcp:8877','tcp:8877'] for line in adb('reverse','--list').splitlines())
server = None
result = ''
try:
    with socket.socket() as check: check.bind(('127.0.0.1',8877))
    server = subprocess.Popen(['node','scripts/model-fixture.mjs'],env={**os.environ,'THOR_FIXTURE_OUTPUT':str(out)},stdout=open(out/'fixture-server.log','w'),stderr=subprocess.STDOUT)
    for _ in range(50):
        try:
            with socket.create_connection(('127.0.0.1',8877),timeout=.2): break
        except OSError: time.sleep(.1)
    adb('reverse','tcp:8877','tcp:8877')
    result = adb('shell','am','instrument','-w','-r','-e','labDisplay',str(a.upper_display),'-e','chatDisplay',str(a.chat_display),'-e','disposableInstallation','true','-e','requireShizuku',str(a.require_shizuku).lower(),'-e','class',a.class_name,'com.tobias.loki.test/androidx.test.runner.AndroidJUnitRunner')
    (out/'product-suite.log').write_text(result)
    print(result, flush=True)
    for name in ['integrated-lab-report.json','integrated-lab-native.json','integrated-capture-result.json','integrated-frame-report.json','integrated-frame-native.json','integrated-shell-result.json','integrated-chat.png']:
        try:
            adb('shell','run-as','com.tobias.loki','test','-f','files/'+name)
            (out/name).write_bytes(adb('exec-out','run-as','com.tobias.loki','cat','files/'+name,binary=True))
        except subprocess.CalledProcessError:
            (out/name).unlink(missing_ok=True)
    for kind in ['lab','frame']:
        report_path=out/f'integrated-{kind}-report.json'
        if not report_path.exists(): continue
        report=json.loads(report_path.read_text())
        raw=adb('exec-out','run-as','dev.thor.inputlab','cat',f"files/reports/{report['metadata']['attemptId']}/events.jsonl",binary=True)
        (out/f'integrated-{kind}-events.jsonl').write_bytes(raw)
        assert hashlib.sha256(raw[:report['eventPrefixBytes']]).hexdigest() == report['eventPrefixSha256']
        assert [json.loads(line)['sequence'] for line in raw.splitlines()] == list(range(1,len(raw.splitlines())+1))
        assert not report['integrity']['incomplete'] and report['integrity']['droppedSamples']==0
    metadata={'sourceCommit':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),'sourceDirty':bool(subprocess.check_output(['git','status','--porcelain'],text=True).strip()),'physical':True,'model':adb('shell','getprop','ro.product.model').strip(),'firmware':adb('shell','getprop','ro.build.display.id').strip(),'api':adb('shell','getprop','ro.build.version.sdk').strip(),'fingerprint':adb('shell','getprop','ro.build.fingerprint').strip(),'webView':adb('shell','dumpsys','webviewupdate'),'upperDisplay':a.upper_display,'chatDisplay':a.chat_display,'remoteService':'controlled fixture, not live cloud','receiverQualification':'unqualified-physical-baseline-pending','suitePassed':bool(re.search(r'OK \(\d+ tests\)',result)) and 'FAILURES' not in result}
    (out/'fixture-verification.json').write_text(json.dumps(metadata,ensure_ascii=False,indent=2))
    assert metadata['suitePassed'], 'Physical product fixture suite failed; inspect product-suite.log'
finally:
    for key,value in original.items():
        command = f'settings delete secure {key}' if value=='null' else f'settings put secure {key} {shlex.quote(value)}'
        adb('shell',command)
    if not reverse_existed: adb('reverse','--remove','tcp:8877')
    if server: server.terminate(); server.wait(timeout=5)
