#!/usr/bin/env python3
"""Import JSON credentials from stdin into the selected app's private Native cache.
Never prints credentials, headers or provider response bodies. No fixture resets.
"""
import argparse, json, os, pathlib, re, subprocess, sys, shlex, time, shutil, http.server, threading
p=argparse.ArgumentParser()
p.add_argument('--serial',required=True)
p.add_argument('--adb-port',required=True,type=int)
p.add_argument('--chat-display',required=True,type=int)
p.add_argument('--reuse-profile',action='store_true')
p.add_argument('--local-web-smoke',action='store_true',help='Use a real model to fetch a harmless localhost HTTP page over the production web tool')
p.add_argument('--configure-budget-128k',action='store_true',help='Save 128K local budget through Native UI, then perform a genuine fresh probe')
a=p.parse_args()
root=pathlib.Path(__file__).resolve().parents[1];os.chdir(root)
out=root/'build/physical';out.mkdir(parents=True,exist_ok=True)
# Preserve previous successful and failed attempts before refreshing the latest view.
previous=['live-suite.log','physical-live-services.json','physical-live-chat.png','profile-budget-suite.log','physical-profile-budget.json']
if any((out/name).exists() for name in previous):
    ordinal=1
    while (out/f'live-services-attempt-{ordinal}').exists():ordinal+=1
    archive=out/f'live-services-attempt-{ordinal}';archive.mkdir()
    for name in previous:
        if (out/name).exists():shutil.copy2(out/name,archive/name)
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
original_secure={}
local_server=None;local_port=None;local_url=None;local_requests=[]
try:
    if a.local_web_smoke:
        class Page(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                local_requests.append({'path':self.path,'authorizationReceived':self.headers.get('Authorization') is not None,'searchTokenReceived':self.headers.get('X-Subscription-Token') is not None})
                payload=b'THOR_LOCAL_WEB_OK: harmless local HTTP reachability test.'
                self.send_response(200);self.send_header('Content-Type','text/plain; charset=utf-8');self.send_header('Content-Length',str(len(payload)));self.end_headers();self.wfile.write(payload)
            def log_message(self,*args):pass
        local_server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Page)
        threading.Thread(target=local_server.serve_forever,daemon=True).start()
        used=adb('reverse','--list').decode()
        local_port=18879
        while f'tcp:{local_port} ' in used:local_port+=1
        adb('reverse',f'tcp:{local_port}',f'tcp:{local_server.server_port}')
        local_url=f'http://127.0.0.1:{local_port}/reachability'

    if a.configure_budget_128k:
        assert a.reuse_profile,'Budget adjustment requires the existing real profile'
        original_secure={k:adb('shell','settings','get','secure',k).decode().strip() for k in ['enabled_accessibility_services','accessibility_enabled']}
        own='dev.thor.agent/dev.thor.tools.diagnostic.ThorAccessibilityService'
        services=':'.join(s for s in original_secure['enabled_accessibility_services'].split(':') if s not in [own,'null',''])
        adb('shell','settings put secure enabled_accessibility_services '+shlex.quote(services) if services else 'settings delete secure enabled_accessibility_services')
        time.sleep(.6)
        assert b'Success' in adb('install','-r','app/build/outputs/apk/debug/app-debug.apk')
    assert b'Success' in adb('install','-r','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
    if a.configure_budget_128k:
        budget=adb('shell','am','instrument','-w','-r','-e','physicalAcceptance','true','-e','chatDisplay',str(a.chat_display),'-e','class','dev.thor.agent.PhysicalProfileBudgetTest','dev.thor.agent.test/androidx.test.runner.AndroidJUnitRunner')
        (out/'profile-budget-suite.log').write_bytes(budget);print(budget.decode(),flush=True)
        assert re.search(rb'OK \(\d+ tests?\)',budget) and b'FAILURES' not in budget,'Native budget UI update failed'
        (out/'physical-profile-budget.json').write_bytes(adb('exec-out','run-as','dev.thor.agent','cat','files/physical-profile-budget.json'))
    adb('shell','input','keyevent','224')
    result=adb('shell','am','instrument','-w','-r','-e','physicalAcceptance','true','-e','reuseProfile',str(a.reuse_profile).lower(),'-e','chatDisplay',str(a.chat_display),*(['-e','localWebUrl',local_url] if local_url else []),'-e','class','dev.thor.agent.PhysicalRealServiceTest','dev.thor.agent.test/androidx.test.runner.AndroidJUnitRunner')
    (out/'live-suite.log').write_bytes(result)
    print(result.decode(),flush=True)
    for name in ['physical-live-services.json','physical-live-chat.png']:
        try:
            adb('shell','run-as','dev.thor.agent','test','-f','files/'+name)
            (out/name).write_bytes(adb('exec-out','run-as','dev.thor.agent','cat','files/'+name))
        except subprocess.CalledProcessError: pass
    if local_url:
        (out/'physical-local-web-server.json').write_text(json.dumps({'scope':'Real model web.fetch to harmless local HTTP target; no model fixture','requests':local_requests},indent=2)+'\n')
        assert local_requests and all(not x['authorizationReceived'] and not x['searchTokenReceived'] for x in local_requests)
    assert re.search(rb'OK \(\d+ tests?\)',result) and b'FAILURES' not in result, 'Live acceptance failed; inspect the redacted report'
finally:
    if local_port is not None:adb('reverse','--remove',f'tcp:{local_port}')
    if local_server is not None:local_server.shutdown();local_server.server_close()
    adb('shell','run-as','dev.thor.agent','rm','-f','cache/physical-credentials.json')
    for key,value in original_secure.items():adb('shell',f'settings delete secure {key}' if value=='null' else f'settings put secure {key} {shlex.quote(value)}')
