#!/usr/bin/env python3
"""Revoke our Shizuku-manager grant through its UI, test, and restore that exact grant."""
import argparse,json,os,pathlib,re,shlex,subprocess,time,xml.etree.ElementTree as ET
p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--adb-port',type=int,required=True);p.add_argument('--chat-display',type=int,required=True)
a=p.parse_args();root=pathlib.Path(__file__).resolve().parents[1];os.chdir(root)
out=root/'build/physical/resume-2026-10-15/accessibility-denied';out.mkdir(parents=True,exist_ok=True)
adb_bin=pathlib.Path(os.environ.get('ANDROID_HOME',pathlib.Path.home()/'Library/Android/sdk'))/'platform-tools/adb'
prefix=[str(adb_bin),'-P',str(a.adb_port),'-s',a.serial]
def adb(*args):return subprocess.check_output(prefix+list(args),stdin=subprocess.DEVNULL)
assert adb('shell','getprop','ro.product.model').strip()==b'AYN Thor'
original={k:adb('shell','settings','get','secure',k).decode().strip() for k in ['enabled_accessibility_services','accessibility_enabled']}
component='com.tobias.loki/dev.thor.tools.diagnostic.ThorAccessibilityService'
manager='moe.shizuku.privileged.api/moe.shizuku.manager.MainActivity'
ui_path='/data/local/tmp/thor-shizuku-permission.xml'
original_grant=None
def hierarchy():
    subprocess.run(prefix+['shell','uiautomator','dump',ui_path],check=True,stdout=subprocess.DEVNULL,timeout=20)
    return ET.fromstring(adb('exec-out','cat',ui_path))
def tap(node):
    x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
    assert 0<=x1<x2<=1920 and 0<=y1<y2<=1080
    adb('shell','input','-d','0','tap',str((x1+x2)//2),str((y1+y2)//2));time.sleep(.5)
def own_switch(tree):
    parents={child:parent for parent in tree.iter() for child in parent}
    summary=next((n for n in tree.iter('node') if n.get('text')=='com.tobias.loki'),None)
    if summary is None:return None
    row=summary
    while row in parents:
        row=parents[row]
        switches=[n for n in row.iter('node') if n.get('checkable')=='true']
        if len(switches)==1:return switches[0]
        if len(switches)>1:raise AssertionError('Ambiguous grant row; no toggle performed')
    return None
def manager_switch():
    # Android's uiautomator dump is on the default display. This test uses
    # that actual upper display and never guesses a control's coordinates.
    adb('shell','am','start','--display','0','-n',manager)
    for _ in range(4):
        tree=hierarchy();switch=own_switch(tree)
        if switch is not None:return switch
        entry=next((n for n in tree.iter('node') if '点按以管理授权的应用' in n.get('text','')),None)
        if entry is not None:tap(entry)
        else:adb('shell','input','-d','0','swipe','1000','900','1000','450','400')
    raise AssertionError('loki grant row not visible; no toggle performed')
def set_grant(enabled):
    switch=manager_switch()
    if (switch.get('checked')=='true')!=enabled:tap(switch)
    assert (manager_switch().get('checked')=='true')==enabled,'Shizuku grant UI readback differs'
try:
    original_grant=manager_switch().get('checked')=='true'
    set_grant(False)
    services=[s for s in original['enabled_accessibility_services'].split(':') if s not in [component,'null','']]
    value=':'.join(services)
    adb('shell','settings put secure enabled_accessibility_services '+shlex.quote(value) if value else 'settings delete secure enabled_accessibility_services')
    time.sleep(.6);adb('shell','am','force-stop','com.tobias.loki')
    for apk in ['app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']:
        assert b'Success' in adb('install','-r',apk)
    result=adb('shell','am','instrument','-w','-r','-e','physicalAcceptance','true','-e','permissionMissingAcceptance','true','-e','chatDisplay',str(a.chat_display),'-e','class','dev.thor.agent.PhysicalAccessibilityDeniedTest','com.tobias.loki.test/androidx.test.runner.AndroidJUnitRunner')
    (out/'suite.log').write_bytes(result);print(result.decode(),flush=True)
    (out/'result.json').write_bytes(adb('exec-out','run-as','com.tobias.loki','cat','files/physical-accessibility-denied.json'))
    assert re.search(rb'OK \(1 test\)',result) and b'FAILURES' not in result
finally:
    if original_grant is not None:
        set_grant(original_grant)
        (out/'restoration.json').write_text(json.dumps({'originalShizukuGrant':original_grant,'restoredShizukuGrant':original_grant,'readbackVerified':True},indent=2))
    for key,value in original.items():
        adb('shell',f'settings delete secure {key}' if value=='null' else f'settings put secure {key} {shlex.quote(value)}')
    adb('shell','rm','-f',ui_path)
