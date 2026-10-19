#!/usr/bin/env python3
"""Real model creates/researches a Skill, then tests isolated sessions and lazy loading.
Never resets product data, seeds guide contents or bypasses the actual product save UI.
"""
import argparse,hashlib,json,os,pathlib,re,shutil,subprocess,sys,zipfile
p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--phase',choices=['create','refine','tighten','verify','all'],default='all');p.add_argument('--resume-session');p.add_argument('--cases');p.add_argument('--output',required=True);a=p.parse_args()
assert re.fullmatch(r'emulator-\d+',a.serial),'Only emulator serials allowed'
if a.resume_session:assert re.fullmatch(r'[0-9a-f-]{36}',a.resume_session),'Expected a Native session UUID'
if a.cases:assert a.phase=='verify' and set(a.cases.split(',')) <= {'unrelated','unmounted','fish','crops','equipment'},'Unknown verification case'
root=pathlib.Path(__file__).resolve().parents[1];os.chdir(root)
adb=str(pathlib.Path(os.environ.get('ANDROID_HOME',pathlib.Path.home()/'Library/Android/sdk'))/'platform-tools/adb');prefix=[adb,'-s',a.serial]
def call(*args):return subprocess.check_output(prefix+list(args),stdin=subprocess.DEVNULL)
assert call('shell','getprop','ro.kernel.qemu').strip()==b'1','Physical devices refused'
out=root/a.output;out.mkdir(parents=True,exist_ok=True)
assert not (out/'metadata.json').exists(),'Use a fresh output directory'
own='com.tobias.loki/dev.thor.tools.diagnostic.ThorAccessibilityService'
original={k:call('shell','settings','get','secure',k).decode().strip() for k in ['enabled_accessibility_services','accessibility_enabled']}
pinned=out/'apks';pinned.mkdir()
for path in ['app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']:shutil.copy2(path,pinned/pathlib.Path(path).name)
initial={'sourceCommit':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),'workingChanges':subprocess.check_output(['git','status','--porcelain'],text=True).splitlines(),'appApkSha256':hashlib.sha256((pinned/'app-debug.apk').read_bytes()).hexdigest(),'testApkSha256':hashlib.sha256((pinned/'app-debug-androidTest.apk').read_bytes()).hexdigest()}
source_names=subprocess.check_output(['git','ls-files','-co','--exclude-standard','--','app/src','integration','runtime/src','runtime/test','native-bridge/core/src','native-bridge/android/src','native-bridge/js','scripts'],text=True).splitlines()
initial['testedSources']={name:hashlib.sha256(pathlib.Path(name).read_bytes()).hexdigest() for name in sorted(set(source_names)) if pathlib.Path(name).is_file()}
initial['deviceFingerprint']=call('shell','getprop','ro.build.fingerprint').decode().strip()
reports={};completed=False
try:
 if own in original['enabled_accessibility_services'].split(':'):
  rest=':'.join(v for v in original['enabled_accessibility_services'].split(':') if v not in [own,'null',''])
  call('shell','settings','put','secure','enabled_accessibility_services',rest) if rest else call('shell','settings','delete','secure','enabled_accessibility_services')
 for path in [pinned/'app-debug.apk',pinned/'app-debug-androidTest.apk']:
  assert b'Success' in call('install','-r',str(path))
 for phase in (['create','verify'] if a.phase=='all' else [a.phase]):
  stage=out/phase;stage.mkdir()
  result=call('shell','am','instrument','-w','-r','-e','emulatorLiveSkill','true','-e','skillPhase',phase,*(['-e','skillResumeSession',a.resume_session] if a.resume_session else []),*(['-e','skillCases',a.cases] if a.cases and phase=='verify' else []),'-e','class','dev.thor.agent.EmulatorLiveSkillTest','com.tobias.loki.test/androidx.test.runner.AndroidJUnitRunner')
  (stage/'suite.log').write_bytes(result);print(result.decode(),flush=True)
  report=call('exec-out','run-as','com.tobias.loki','cat','files/skill-live/report.json');(stage/'report.json').write_bytes(report);j=json.loads(report);reports[phase]=j.get('passed')
  current_requests={request['requestId'] for request in j.get('requests',[])}
  names=call('shell','run-as','com.tobias.loki','find','files/skill-live','-type','f').decode().splitlines()
  for name in names:
   relative=pathlib.PurePosixPath(name).relative_to('files/skill-live');assert '..' not in relative.parts
   if str(relative)=='report.json':continue
   if relative.parts[0]=='requests' and relative.stem not in current_requests:continue
   if relative.parts[0]=='export' and phase not in ['create','refine','tighten']:continue
   if relative.suffix=='.png' and relative.name not in ({'created.png'} if phase=='create' else {'refined.png'} if phase=='refine' else {'tightened.png'} if phase=='tighten' else {'fish.png','crops.png','equipment.png'}|({'followup.png'} if a.resume_session else set())):continue
   target=stage/pathlib.Path(str(relative));target.parent.mkdir(parents=True,exist_ok=True)
   target.write_bytes(call('exec-out','run-as','com.tobias.loki','cat',name))
  assert b'OK (1 test)' in result and j.get('passed') is True,f'{phase} failed; raw evidence retained in {stage}'
 export=next((out/phase/'export' for phase in ['tighten','refine','create'] if (out/phase/'export/SKILL.md').exists()),None)
 if export is not None:
  with zipfile.ZipFile(out/'stardew-valley-guide.zip','w',zipfile.ZIP_DEFLATED) as archive:
   for f in sorted(export.rglob('*')):
    if f.is_file():archive.write(f,'stardew-valley-guide/'+str(f.relative_to(export)))
 completed=True
finally:
 metadata={'serial':a.serial,'emulator':True,'passed':completed,**initial,'phases':reports,'files':{str(f.relative_to(out)):hashlib.sha256(f.read_bytes()).hexdigest() for f in out.rglob('*') if f.is_file()}}
 (out/'metadata.json').write_text(json.dumps(metadata,ensure_ascii=False,indent=2)+'\n')
 for k,v in original.items():
  call('shell','settings','delete','secure',k) if v=='null' else call('shell','settings','put','secure',k,v)
 call('shell','run-as','com.tobias.loki','rm','-f','cache/skill-live-credentials.json')
