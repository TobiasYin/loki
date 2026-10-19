#!/usr/bin/env python3
"""Create/start the project's dedicated AVD and verify Thor's logical displays.
Never targets a physical device or changes another AVD. Requires an installed image.
"""
import argparse, json, os, pathlib, re, subprocess, time

ROOT=pathlib.Path(__file__).resolve().parents[1]
NAME='thor_agent_dual_api36'
IMAGE='system-images;android-36;google_apis_playstore;arm64-v8a'
EXPECTED={0:(1920,1080,369),4:(1240,1080,369)}
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--port',type=int,default=5560)
p.add_argument('--headless',action='store_true')
p.add_argument('--configure-only',action='store_true')
a=p.parse_args()
assert a.port%2==0 and 5554<=a.port<=5682
sdk=pathlib.Path(os.environ.get('ANDROID_HOME',os.environ.get('ANDROID_SDK_ROOT',str(pathlib.Path.home()/'Library/Android/sdk'))))
adb=sdk/'platform-tools/adb';emulator=sdk/'emulator/emulator'
avdroot=pathlib.Path(os.environ.get('ANDROID_AVD_HOME',str(pathlib.Path.home()/'.android/avd')))
avd=avdroot/(NAME+'.avd');config=avd/'config.ini'
output=ROOT/'build/emulator/thor-project';output.mkdir(parents=True,exist_ok=True)
serial=f'emulator-{a.port}'
def run(*args):return subprocess.check_output([str(v) for v in args],text=True,stderr=subprocess.STDOUT,timeout=30).strip()
def device(*args):return run(adb,'-s',serial,*args)
def identity():
    try:return device('emu','avd','name').splitlines()[0].strip()
    except (subprocess.CalledProcessError,subprocess.TimeoutExpired):return None
def displays():
    raw=device('shell','cmd','display','get-displays')
    found={}
    for line in raw.splitlines():
        m=re.search(r'displayId (\d+).*?real (\d+) x (\d+).*?density (\d+)',line)
        if m:found[int(m[1])]=tuple(map(int,m.groups()[1:]))
    return found
running=identity()
if running and running!=NAME:raise SystemExit(f'{serial} belongs to {running}; choose a free --port')
if not running:
    if not config.exists():
        manager=sdk/'cmdline-tools/latest/bin/avdmanager'
        subprocess.run([str(manager),'create','avd','-n',NAME,'-k',IMAGE,'-d','medium_tablet'],input='no\n',text=True,check=True)
    values=dict(line.split('=',1) for line in config.read_text().splitlines() if '=' in line)
    values.update({'avd.id':NAME,'avd.name':NAME,'AvdId':NAME,'avd.ini.displayname':'Thor Agent - Dual Screen',
        'hw.lcd.width':'1920','hw.lcd.height':'1080','hw.lcd.density':'369','hw.ramSize':'2048','hw.cpu.ncore':'2',
        'disk.dataPartition.size':'2G','disk.cachePartition.size':'32MB','skin.name':'1920x1080','skin.path':'_no_skin',
        'showDeviceFrame':'no','hw.initialOrientation':'landscape','fastboot.forceColdBoot':'yes','fastboot.forceFastBoot':'no',
        'firstboot.bootFromDownloadableSnapshot':'no','firstboot.bootFromLocalSnapshot':'no','firstboot.saveToLocalSnapshot':'no',
        'hw.keyboard':'yes','hw.gpu.enabled':'yes','hw.gpu.mode':'auto'})
    # Hotplug below reserves logical IDs 1..3 before leaving only the real lower screen.
    for slot in (1,2,3):
        for key in ('width','height','density'):values[f'hw.display{slot}.{key}']='0'
    config.write_text(''.join(f'{key}={value}\n' for key,value in values.items()))
    (avd/'thor-project.json').write_text(json.dumps({'project':'thor-agent','displays':EXPECTED},indent=2)+'\n')
    if a.configure_only:print(f'Configured {NAME}: {config}');raise SystemExit(0)
    data_image=avd/'userdata-qemu.img'
    if not data_image.exists():
        raw=avd/'thor-factory-data.raw'
        try:
            with raw.open('wb') as fresh:fresh.truncate(6*1024**3)
            subprocess.run([str(sdk/'emulator/bin64/mkfs.ext4'),'-F','-b','4096','-L','data',str(raw)],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
            subprocess.run([str(sdk/'emulator/qemu-img'),'convert','-f','raw','-O','qcow2',str(raw),str(data_image)],check=True)
        finally:
            if raw.exists():raw.unlink()
    args=[str(emulator),'-avd',NAME,'-port',str(a.port),'-no-snapshot','-no-boot-anim','-gpu','swiftshader','-memory','2048','-partition-size','2048']
    if a.headless:args+=['-no-window']
    log=open(output/'emulator.log','a')
    process=subprocess.Popen(args,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
    print(f'Starting {NAME} on {serial}; log: {output / "emulator.log"}',flush=True)
    deadline=time.monotonic()+180
    while time.monotonic()<deadline:
        if process.poll() is not None:raise SystemExit(f'Emulator stopped; see {output / "emulator.log"}')
        try:
            if device('shell','getprop','sys.boot_completed')=='1':break
        except (subprocess.CalledProcessError,subprocess.TimeoutExpired):pass
        time.sleep(1)
    else:raise SystemExit('Emulator boot timeout')
assert identity()==NAME
if 'stopped' in device('emu','avd','status'):device('emu','avd','start')
assert device('shell','getprop','ro.kernel.qemu')=='1'
current=displays()
if current!=EXPECTED:
    if set(current)!={0}:raise SystemExit(f'Unexpected existing displays {current}; cold boot this dedicated AVD with this script')
    for logical in range(8):
        result=device('emu','multidisplay','add','1','1240','1080','369','0')
        # The emulator pipe can report not-ready briefly during first boot;
        # verify Android's actual display before accepting/retrying the command.
        deadline=time.monotonic()+15
        while time.monotonic()<deadline:
            current=displays()
            if len(current)>1:break
            time.sleep(.2)
        ids=set(current)-{0}
        if ids=={4}:break
        if not ids:
            time.sleep(1);continue
        if len(ids)!=1 or not 1<=next(iter(ids))<4:raise SystemExit(f'Unexpected display ID allocation: {current}')
        device('emu','multidisplay','del','1')
        deadline=time.monotonic()+10
        while time.monotonic()<deadline and set(displays())!={0}:time.sleep(.2)
# Lock each display to its landscape geometry without changing the logical IDs.
for display in EXPECTED:
    device('shell','wm','user-rotation','-d',str(display),'lock','0')
current=displays()
if current!=EXPECTED:raise SystemExit(f'Display verification failed: {current}')
summary={'avd':NAME,'serial':serial,'emulator':True,'android':device('shell','getprop','ro.build.version.release'),
    'screens':[{ 'role':'upper' if k==0 else 'lower','displayId':k,'width':v[0],'height':v[1],'densityDpi':v[2]} for k,v in current.items()],
    'idStrategy':'Boot without secondary displays; hotplug/remove temporary displays with IDs below 4, then retain logical display 4.'}
(output/'displays.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(summary,ensure_ascii=False,indent=2))
print(f'Install: {adb} -s {serial} install -r app/build/outputs/apk/debug/app-debug.apk')
