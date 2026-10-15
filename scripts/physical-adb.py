#!/usr/bin/env python3
"""Real saved-profile ADB policy/dual-panel acceptance. Never resets secrets or history."""
import argparse,json,os,pathlib,re,shlex,subprocess,time
p=argparse.ArgumentParser()
p.add_argument('--serial',required=True);p.add_argument('--adb-port',required=True,type=int)
p.add_argument('--chat-display',required=True,type=int)
p.add_argument('--fan',action='store_true',help='Run live firmware fan-mode acceptance instead of brightness')
p.add_argument('--refresh',action='store_true',help='Run live refresh-rate acceptance instead of brightness')
p.add_argument('--storage',action='store_true',help='Run the isolated real SQLite regression, without model or live history changes')
a=p.parse_args();root=pathlib.Path(__file__).resolve().parents[1];os.chdir(root)
assert sum([a.fan,a.refresh,a.storage])<=1
label='fan-policy' if a.fan else 'refresh-policy' if a.refresh else 'storage-regression' if a.storage else 'adb-policy'
out=root/f'build/physical/{label}';index=1
while out.exists():out=root/f'build/physical/{label}-attempt-{index}';index+=1
out.mkdir(parents=True)
binary=pathlib.Path(os.environ.get('ANDROID_HOME',pathlib.Path.home()/'Library/Android/sdk'))/'platform-tools/adb'
prefix=[str(binary),'-P',str(a.adb_port),'-s',a.serial]
def adb(*args):return subprocess.check_output(prefix+list(args),stdin=subprocess.DEVNULL)
assert adb('shell','getprop','ro.product.model').strip()==b'AYN Thor'
original={k:adb('shell','settings','get','secure',k).decode().strip() for k in ['enabled_accessibility_services','accessibility_enabled']}
try:
    own='dev.thor.agent/dev.thor.tools.diagnostic.ThorAccessibilityService'
    services=':'.join(s for s in original['enabled_accessibility_services'].split(':') if s not in [own,'null',''])
    adb('shell','settings put secure enabled_accessibility_services '+shlex.quote(services) if services else 'settings delete secure enabled_accessibility_services')
    time.sleep(.6)
    assert b'Success' in adb('install','-r','app/build/outputs/apk/debug/app-debug.apk')
    assert b'Success' in adb('install','-r','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
    adb('shell','input','keyevent','224')
    report='physical-fan-policy.json' if a.fan else 'physical-refresh-policy.json' if a.refresh else 'physical-storage-regression.json' if a.storage else 'physical-adb-policy.json'
    test='PhysicalFanPolicyTest' if a.fan else 'PhysicalRefreshPolicyTest' if a.refresh else 'PhysicalStorageRegressionTest' if a.storage else 'PhysicalAdbPolicyTest'
    adb('shell','run-as','dev.thor.agent','rm','-f','files/'+report)
    result=adb('shell','am','instrument','-w','-r','-e','physicalAcceptance','true','-e','chatDisplay',str(a.chat_display),'-e','class','dev.thor.agent.'+test,'dev.thor.agent.test/androidx.test.runner.AndroidJUnitRunner')
    (out/'suite.log').write_bytes(result);print(result.decode(),flush=True)
    for name in [report]+(['physical-storage-streaming-regression.json'] if a.storage else []):
        try:(out/name).write_bytes(adb('exec-out','run-as','dev.thor.agent','cat','files/'+name))
        except subprocess.CalledProcessError:pass
    assert re.search(rb'OK \(\d+ tests?\)',result) and b'FAILURES' not in result,'Physical ADB acceptance failed; inspect redacted evidence'
finally:
    for key,value in original.items():adb('shell',f'settings delete secure {key}' if value=='null' else f'settings put secure {key} {shlex.quote(value)}')
