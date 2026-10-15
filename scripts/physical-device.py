#!/usr/bin/env python3
"""Run cloud-backed physical input acceptance, preserving the saved Native profile."""
import argparse,hashlib,json,os,pathlib,re,shlex,subprocess,time
p=argparse.ArgumentParser()
p.add_argument('--serial',required=True);p.add_argument('--adb-port',required=True,type=int)
p.add_argument('--upper-display',required=True,type=int);p.add_argument('--chat-display',required=True,type=int)
p.add_argument('--guard-controls',action='store_true',help='Native component controls with TEST authority, no model')
a=p.parse_args();root=pathlib.Path(__file__).resolve().parents[1];os.chdir(root)
out=root/'build/physical/resume-2026-10-15'/('native-guards' if a.guard_controls else 'cloud-input')
if out.exists():
    index=1
    while out.with_name(f'{out.name}-attempt-{index}').exists():index+=1
    out.rename(out.with_name(f'{out.name}-attempt-{index}'))
out.mkdir(parents=True)
adb_bin=pathlib.Path(os.environ.get('ANDROID_HOME',pathlib.Path.home()/'Library/Android/sdk'))/'platform-tools/adb'
prefix=[str(adb_bin),'-P',str(a.adb_port),'-s',a.serial]
def adb(*args):return subprocess.check_output(prefix+list(args),stdin=subprocess.DEVNULL)
assert adb('shell','getprop','ro.product.model').strip()==b'AYN Thor'
assert a.upper_display!=a.chat_display
original={k:adb('shell','settings','get','secure',k).decode().strip() for k in ['enabled_accessibility_services','accessibility_enabled']}
try:
    component='dev.thor.agent/dev.thor.tools.diagnostic.ThorAccessibilityService'
    services=[s for s in original['enabled_accessibility_services'].split(':') if s not in [component,'null','']]
    value=':'.join(services)
    adb('shell','settings put secure enabled_accessibility_services '+shlex.quote(value) if value else 'settings delete secure enabled_accessibility_services')
    time.sleep(.6)
    assert b'Success' in adb('install','-r','app/build/outputs/apk/debug/app-debug.apk')
    assert b'Success' in adb('install','-r','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
    names=['physical-target-guards.json','physical-guard-clipping-report.json','physical-guard-switch-report.json'] if a.guard_controls else ['physical-cloud-input.json','physical-cloud-logical-report.json','physical-cloud-frame-report.json']
    adb('shell','run-as','dev.thor.agent','rm','-f',*['files/'+n for n in names])
    test_class='dev.thor.agent.PhysicalTargetGuardTest' if a.guard_controls else 'dev.thor.agent.PhysicalDeviceFlowTest'
    result=adb('shell','am','instrument','-w','-r','-e','physicalAcceptance','true','-e','chatDisplay',str(a.chat_display),'-e','labDisplay',str(a.upper_display),'-e','class',test_class,'dev.thor.agent.test/androidx.test.runner.AndroidJUnitRunner')
    (out/'suite.log').write_bytes(result);print(result.decode(),flush=True)
    for name in names:
        try:
            adb('shell','run-as','dev.thor.agent','test','-f','files/'+name)
            (out/name).write_bytes(adb('exec-out','run-as','dev.thor.agent','cat','files/'+name))
        except subprocess.CalledProcessError:pass
    for kind in (['clipping','switch'] if a.guard_controls else ['logical','frame']):
        report_path=out/f'physical-guard-{kind}-report.json' if a.guard_controls else out/f'physical-cloud-{kind}-report.json'
        if not report_path.exists():continue
        report=json.loads(report_path.read_text());attempt=report['metadata']['attemptId']
        raw=adb('exec-out','run-as','dev.thor.inputlab','cat',f'files/reports/{attempt}/events.jsonl')
        (out/(f'physical-guard-{kind}-events.jsonl' if a.guard_controls else f'{kind}-events.jsonl')).write_bytes(raw)
        assert hashlib.sha256(raw[:report['eventPrefixBytes']]).hexdigest()==report['eventPrefixSha256']
        assert not report['integrity']['incomplete'] and report['integrity']['droppedSamples']==0
    assert re.search(rb'OK \(\d+ tests?\)',result) and b'FAILURES' not in result,'Physical cloud input acceptance failed; inspect evidence'
finally:
    for key,value in original.items():
        adb('shell',f'settings delete secure {key}' if value=='null' else f'settings put secure {key} {shlex.quote(value)}')
