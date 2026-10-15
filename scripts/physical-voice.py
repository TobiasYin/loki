#!/usr/bin/env python3
"""Live ASR on the selected Thor, with optional stdin-only credential import.
Uses known reference speech; never resets model profiles or prints credentials.
"""
import argparse,hashlib,json,os,pathlib,re,shlex,subprocess,sys,wave
p=argparse.ArgumentParser()
p.add_argument('--serial',required=True)
p.add_argument('--adb-port',type=int,required=True)
p.add_argument('--chat-display',type=int,required=True)
p.add_argument('--reference-wav',type=pathlib.Path,required=True)
p.add_argument('--phase',choices=['transport','microphone'],required=True)
p.add_argument('--configure-key-stdin',action='store_true')
p.add_argument('--no-install',action='store_true')
a=p.parse_args()
root=pathlib.Path(__file__).resolve().parents[1];os.chdir(root)
out=root/'build/voice-live';out.mkdir(parents=True,exist_ok=True)
adb_bin=pathlib.Path(os.environ.get('ANDROID_HOME',pathlib.Path.home()/'Library/Android/sdk'))/'platform-tools/adb'
adb=[str(adb_bin),'-P',str(a.adb_port),'-s',a.serial]
def run(*args):return subprocess.check_output(adb+list(args),stdin=subprocess.DEVNULL)
def private(name,data):subprocess.run(adb+['shell','run-as','dev.thor.agent','tee','cache/'+name],input=data,stdout=subprocess.DEVNULL,check=True)
assert run('shell','getprop','ro.product.model').strip()==b'AYN Thor'
with wave.open(str(a.reference_wav.resolve())) as w:
 assert w.getframerate()==16000 and w.getnchannels()==1 and w.getsampwidth()==2,'Reference must be PCM16/16kHz/mono WAV'
 pcm=w.readframes(w.getnframes())
assert 32000<=len(pcm)<=32000*30
if not a.no_install:
 for apk in ['app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']:
  assert b'Success' in run('install','-r',apk)
private('voice-reference.pcm',pcm);private('voice-reference.wav',a.reference_wav.read_bytes())
metadata_path=a.reference_wav.with_suffix('.json')
metadata=json.loads(metadata_path.read_text()) if metadata_path.exists() else {}
manifest_path=a.reference_wav.parent/'manifest.json'
if manifest_path.exists():metadata['provider']=json.loads(manifest_path.read_text()).get('provider','synthetic-reference')
private('voice-reference.json',json.dumps(metadata,ensure_ascii=False).encode())
if a.configure_key_stdin:
 assert a.phase=='transport','Credential import belongs to transport phase'
 secret=sys.stdin.buffer.read(8193).strip();assert 0<len(secret)<=8192 and b'\n' not in secret and b'\r' not in secret
 private('physical-voice-credential',secret);secret=None
if a.phase=='microphone':
 assert metadata.get('expectedPhrase','语音测试成功')=='语音测试成功','Use the harmless short-command fixture for live Agent submission'
 run('shell','pm','grant','dev.thor.agent','android.permission.RECORD_AUDIO')
method='nativeStreamRecognizesKnownSpeechOverDeviceNetwork' if a.phase=='transport' else 'microphoneWebViewReleaseSendsRealAgentMessageAndCancelDoesNotSend'
name='physical-cloud-voice.json' if a.phase=='transport' else 'physical-voice-agent.json'
try:
 result=run('shell','am','instrument','-w','-r','-e','physicalAcceptance','true','-e','chatDisplay',str(a.chat_display),'-e','class','dev.thor.agent.PhysicalCloudVoiceTest#'+method,'dev.thor.agent.test/androidx.test.runner.AndroidJUnitRunner')
 (out/(a.phase+'-instrumentation.txt')).write_bytes(result)
 try:(out/name).write_bytes(run('exec-out','run-as','dev.thor.agent','cat','files/'+name))
 except subprocess.CalledProcessError:pass
 assert re.search(rb'OK \(\d+ tests?\)',result) and b'FAILURES' not in result,'Acceptance failed; inspect redacted evidence in build/voice-live'
 report=json.loads((out/name).read_text());assert report['passed']
 print(json.dumps({'phase':a.phase,'passed':True,'report':str((out/name).relative_to(root)),'referenceSha256':hashlib.sha256(pcm).hexdigest(),'referenceDurationMs':len(pcm)*1000//32000},ensure_ascii=False))
finally:
 run('shell','run-as','dev.thor.agent','rm','-f','cache/physical-voice-credential','cache/voice-reference.pcm','cache/voice-reference.wav','cache/voice-reference.json')
