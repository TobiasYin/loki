#!/usr/bin/env python3
"""Run independent Lab negative controls using explicitly labelled platform stimuli."""
import argparse,hashlib,json,os,pathlib,re,subprocess
p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--adb-port',type=int,required=True);p.add_argument('--upper-display',type=int,required=True)
a=p.parse_args();root=pathlib.Path(__file__).resolve().parents[1];os.chdir(root)
out=root/'build/physical/resume-2026-10-15/receiver-negatives';out.mkdir(parents=True,exist_ok=True)
adb_bin=pathlib.Path(os.environ.get('ANDROID_HOME',pathlib.Path.home()/'Library/Android/sdk'))/'platform-tools/adb'
prefix=[str(adb_bin),'-P',str(a.adb_port),'-s',a.serial]
def adb(*args):return subprocess.check_output(prefix+list(args),stdin=subprocess.DEVNULL)
assert adb('shell','getprop','ro.product.model').strip()==b'AYN Thor'
assert b'Success' in adb('install','-r','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
names=['physical-receiver-negatives.json']+[f'physical-negative-{k}-report.json' for k in ['no-input','wrong-target','target-switch']]
adb('shell','run-as','dev.thor.agent','rm','-f',*['files/'+n for n in names])
result=adb('shell','am','instrument','-w','-r','-e','physicalAcceptance','true','-e','labDisplay',str(a.upper_display),'-e','class','dev.thor.agent.PhysicalReceiverNegativeTest','dev.thor.agent.test/androidx.test.runner.AndroidJUnitRunner')
(out/'suite.log').write_bytes(result);print(result.decode(),flush=True)
for name in names:
    try:
        data=adb('exec-out','run-as','dev.thor.agent','cat','files/'+name);(out/name).write_bytes(data)
        report=json.loads(data)
        if 'eventPrefixSha256' in report:
            raw=adb('exec-out','run-as','dev.thor.inputlab','cat',f"files/reports/{report['metadata']['attemptId']}/events.jsonl")
            (out/name.replace('-report.json','-events.jsonl')).write_bytes(raw)
            assert hashlib.sha256(raw[:report['eventPrefixBytes']]).hexdigest()==report['eventPrefixSha256']
    except subprocess.CalledProcessError:pass
assert re.search(rb'OK \(1 test\)',result) and b'FAILURES' not in result,'Receiver negative control failed; inspect saved evidence'
