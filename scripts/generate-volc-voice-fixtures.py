#!/usr/bin/env python3
"""Generate offline ASR WAV fixtures using Volcengine TTS. API key is stdin-only.
This host utility and its generated fixtures are never APK assets.
"""
import argparse,base64,hashlib,json,pathlib,sys,time,urllib.request,urllib.error,uuid,wave
ENDPOINT='https://openspeech.bytedance.com/api/v3/tts/unidirectional'
SPEAKER='zh_female_vv_uranus_bigtts'
CASES=[
 {'id':'01-short-command','text':'请只回复语音测试成功，不要使用任何工具。','expectedPhrase':'语音测试成功','speechRate':0,'purpose':'短句、松手后发送与取消对照'},
 {'id':'02-numbers-settings','text':'把屏幕亮度调到70%，音量设为30%。今天是2026年10月15日。','expectedPhrase':'亮度','speechRate':-10,'purpose':'百分比、数字、日期与多分句；只做转写测试'},
 {'id':'03-game-mixed','text':'请查看星露谷物语攻略，打开 Skill 管理，检查 Wi-Fi 和 USB 连接状态。','expectedPhrase':'攻略','speechRate':0,'purpose':'游戏名称、中英混合与英文缩写；只做转写测试'},
]
class NoRedirect(urllib.request.HTTPRedirectHandler):
 def redirect_request(self,*args,**kwargs):return None

def main():
 p=argparse.ArgumentParser();p.add_argument('--output',type=pathlib.Path,default=pathlib.Path('tests/fixtures/volc-voice'));a=p.parse_args()
 secret=sys.stdin.buffer.read(8193).decode().strip()
 if not secret or len(secret)>8192 or any(c in secret for c in '\r\n'):raise SystemExit('Expected a single API key on stdin')
 a.output.mkdir(parents=True,exist_ok=True)
 opener=urllib.request.build_opener(NoRedirect());manifest={'provider':'Volcengine TTS','endpoint':ENDPOINT,'resource':'seed-tts-2.0','speaker':SPEAKER,'format':'PCM16 LE / 16000 Hz / mono WAV','apkIntegration':False,'cases':[]}
 for case in CASES:
  body={'req_params':{'text':case['text'],'speaker':SPEAKER,'audio_params':{'format':'pcm','sample_rate':16000,'speech_rate':case['speechRate']}}}
  request=urllib.request.Request(ENDPOINT,data=json.dumps(body,ensure_ascii=False).encode(),headers={'Content-Type':'application/json','X-Api-Key':secret,'X-Api-Resource-Id':'seed-tts-2.0','X-Api-Request-Id':str(uuid.uuid4())},method='POST')
  start=time.monotonic();pcm=bytearray();packets=0;terminal=False;codes=[]
  try:
   with opener.open(request,timeout=60) as response:
    while True:
     line=response.readline(8*1024*1024)
     if not line:break
     if not line.strip():continue
     event=json.loads(line);code=event.get('code',0);codes.append(code)
     if code not in (0,20000000):raise RuntimeError('TTS service code '+str(code))
     if event.get('data'):pcm.extend(base64.b64decode(event['data'],validate=True));packets+=1
     if len(pcm)>2*1024*1024:raise RuntimeError('TTS audio exceeded fixture budget')
     if code==20000000:terminal=True
  except urllib.error.HTTPError as e:raise SystemExit('TTS HTTP '+str(e.code)) from None
  if not pcm or len(pcm)%2:raise SystemExit('Invalid PCM fixture')
  name=case['id']+'.wav';path=a.output/name
  with wave.open(str(path),'wb') as wav:wav.setnchannels(1);wav.setsampwidth(2);wav.setframerate(16000);wav.writeframes(pcm)
  item={**case,'file':name,'durationMs':len(pcm)*1000//32000,'pcmSha256':hashlib.sha256(pcm).hexdigest(),'wavSha256':hashlib.sha256(path.read_bytes()).hexdigest(),'ttsPackets':packets,'terminalReceived':terminal,'serviceCodes':sorted(set(codes)),'generationMs':round((time.monotonic()-start)*1000)}
  (a.output/(case['id']+'.json')).write_text(json.dumps(item,ensure_ascii=False,indent=2)+'\n');manifest['cases'].append(item)
  print(json.dumps({'fixture':name,'durationMs':item['durationMs'],'ttsPackets':packets,'passed':True},ensure_ascii=False),flush=True)
 (a.output/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
 secret=None
if __name__=='__main__':
 try:main()
 except Exception as error:raise SystemExit('TTS fixture generation failed: '+error.__class__.__name__) from None
