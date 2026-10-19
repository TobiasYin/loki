#!/usr/bin/env python3
"""Check automatic accessibility with a saved real profile and existing Shizuku grant."""
import argparse,json,os,pathlib,re,shlex,subprocess,time
p=argparse.ArgumentParser()
p.add_argument('--serial',required=True);p.add_argument('--adb-port',type=int,required=True)
p.add_argument('--chat-display',type=int,required=True)
a=p.parse_args();root=pathlib.Path(__file__).resolve().parents[1];os.chdir(root)
out=root/'build/physical/resume-2026-10-15/auto-accessibility';out.mkdir(parents=True,exist_ok=True)
adb_bin=pathlib.Path(os.environ.get('ANDROID_HOME',pathlib.Path.home()/'Library/Android/sdk'))/'platform-tools/adb'
prefix=[str(adb_bin),'-P',str(a.adb_port),'-s',a.serial]
def adb(*args):return subprocess.check_output(prefix+list(args),stdin=subprocess.DEVNULL)
assert adb('shell','getprop','ro.product.model').strip()==b'AYN Thor'
keys=['enabled_accessibility_services','accessibility_enabled']
original={k:adb('shell','settings','get','secure',k).decode().strip() for k in keys}
component='com.tobias.loki/dev.thor.tools.diagnostic.ThorAccessibilityService'
try:
    for attempt in range(1,3):
        services=[s for s in original[keys[0]].split(':') if s not in [component,'null','']]
        value=':'.join(services)
        adb('shell','settings put secure '+keys[0]+' '+shlex.quote(value) if value else 'settings delete secure '+keys[0])
        time.sleep(.6) # Allow the system to consume the explicit initial setting.
        adb('shell','am','force-stop','com.tobias.loki')
        # Package replacement clears Android's stale binding left by the prior
        # instrumentation process. Install only after our setting is disabled,
        # avoiding an automatic bind immediately before instrumentation kills it.
        for apk in ['app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']:
            assert b'Success' in adb('install','-r',apk)
        result=adb('shell','am','instrument','-w','-r','-e','physicalAcceptance','true','-e','chatDisplay',str(a.chat_display),'-e','class','dev.thor.agent.PhysicalAccessibilityTest','com.tobias.loki.test/androidx.test.runner.AndroidJUnitRunner')
        (out/f'suite-{attempt}.log').write_bytes(result);print(result.decode(),flush=True)
        raw=adb('exec-out','run-as','com.tobias.loki','cat','files/physical-accessibility.json')
        (out/f'result-{attempt}.json').write_bytes(raw)
        assert re.search(rb'OK \(1 test\)',result) and b'FAILURES' not in result
        assert json.loads(raw)['passed']
finally:
    for key,value in original.items():
        adb('shell',f'settings delete secure {key}' if value=='null' else f'settings put secure {key} {shlex.quote(value)}')
