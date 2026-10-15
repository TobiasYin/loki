#!/usr/bin/env python3
"""Import JSON credentials from stdin into the selected app's private Native cache.
Never prints credentials, headers or provider response bodies. No fixture resets.
"""
import argparse, json, os, pathlib, re, subprocess, sys
p=argparse.ArgumentParser()
p.add_argument('--serial',required=True)
p.add_argument('--adb-port',required=True,type=int)
p.add_argument('--chat-display',required=True,type=int)
p.add_argument('--reuse-profile',action='store_true')
a=p.parse_args()
root=pathlib.Path(__file__).resolve().parents[1];os.chdir(root)
out=root/'build/physical';out.mkdir(parents=True,exist_ok=True)
adb_bin=pathlib.Path(os.environ.get('ANDROID_HOME',pathlib.Path.home()/'Library/Android/sdk'))/'platform-tools/adb'
prefix=[str(adb_bin),'-P',str(a.adb_port),'-s',a.serial]
def adb(*args):return subprocess.check_output(prefix+list(args),stdin=subprocess.DEVNULL)
assert adb('shell','getprop','ro.product.model').strip()==b'AYN Thor'
config={} if a.reuse_profile else json.load(sys.stdin)
if not a.reuse_profile: assert set(config)=={'modelKey','searchKey'} and all(isinstance(v,str) and v for v in config.values())
# This marker prevents any subsequent fixture runner from resetting the live profile.
(out/'real-profile-imported').touch()
if not a.reuse_profile:
    subprocess.run(prefix+['shell','run-as','dev.thor.agent','sh','-c',"'umask 077; mkdir -p cache; cat > cache/physical-credentials.json'"],input=json.dumps(config).encode(),check=True,stdout=subprocess.DEVNULL)
config.clear()
try:
    assert b'Success' in adb('install','-r','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
    result=adb('shell','am','instrument','-w','-r','-e','physicalAcceptance','true','-e','reuseProfile',str(a.reuse_profile).lower(),'-e','chatDisplay',str(a.chat_display),'-e','class','dev.thor.agent.PhysicalRealServiceTest','dev.thor.agent.test/androidx.test.runner.AndroidJUnitRunner')
    (out/'live-suite.log').write_bytes(result)
    print(result.decode(),flush=True)
    for name in ['physical-live-services.json','physical-live-chat.png']:
        try:
            adb('shell','run-as','dev.thor.agent','test','-f','files/'+name)
            (out/name).write_bytes(adb('exec-out','run-as','dev.thor.agent','cat','files/'+name))
        except subprocess.CalledProcessError: pass
    assert re.search(rb'OK \(\d+ tests?\)',result) and b'FAILURES' not in result, 'Live acceptance failed; inspect the redacted report'
finally:
    adb('shell','run-as','dev.thor.agent','rm','-f','cache/physical-credentials.json')
