#!/usr/bin/env python3
"""Check actual APK manifests/resources and build provenance, never infer packaging from sources."""
import os, pathlib, subprocess, zipfile, hashlib, json, re
root=pathlib.Path(__file__).resolve().parents[1]
sdk=pathlib.Path(os.environ['ANDROID_HOME'])
aapt=sdk/'build-tools/35.0.0/aapt'
artifacts={
 'agent-debug':root/'app/build/outputs/apk/debug/app-debug.apk',
 'agent-release-unsigned':root/'app/build/outputs/apk/release/app-release-unsigned.apk',
 'input-lab-debug':root/'tests/input-lab-app/app/build/outputs/apk/debug/input-lab-debug.apk',
 'input-lab-release-unsigned':root/'tests/input-lab-app/app/build/outputs/apk/release/input-lab-release-unsigned.apk',
}
report={}
for name,path in artifacts.items():
 manifest=subprocess.check_output([str(aapt),'dump','xmltree',str(path),'AndroidManifest.xml'],text=True)
 badging=subprocess.check_output([str(aapt),'dump','badging',str(path)],text=True)
 permissions=subprocess.check_output([str(aapt),'dump','permissions',str(path)],text=True)
 assert 'sharedUserId' not in manifest
 with zipfile.ZipFile(path) as z:
  files=z.namelist()
  if name.startswith('agent'):
   assert "name='dev.thor.agent'" in badging
   assert 'android.permission.INTERNET' in permissions
   assert re.search(r'android:usesCleartextTraffic.*0xffffffff', manifest), 'HTTP must work in both debug and release'
   assert 'assets/chat/runtime-host.js' in files
   assert all('assets/chat/'+f in files for f in ('runtime.html','chat-host.js','send-errors.js'))
   chat_page=z.read('assets/chat/index.html').decode()
   execution_page=z.read('assets/chat/runtime.html').decode()
   assert 'runtime-host.js' not in chat_page and 'chat-host.js' in chat_page
   assert 'runtime-host.js' in execution_page and 'ui.js' not in execution_page and 'chat-host.js' not in execution_page
   offline_tts={p.name for p in (root/'tests/fixtures/volc-voice').glob('*.wav')}
   assert not any(pathlib.Path(f).name in offline_tts for f in files), 'Offline TTS fixtures must not enter APK'
   assert not any(b'/api/v3/tts/' in z.read(f) for f in files if f.endswith('.dex') or f.startswith('assets/')), 'Integrated TTS must use the AgentPlan /plan/ endpoint, never the ordinary billing endpoint'
   bundle=z.read('assets/chat/runtime-host.js').decode()
   assert 'sk-thor-native-placeholder' in bundle and 'integration-only-secret' not in bundle
   assert not any('node_modules' in f or f.endswith('.test.mjs') or f.endswith('package-lock.json') for f in files)
   if 'release' in name:
    assert 'runPreparedNative' not in bundle
    assert '__thorRuntimeDiagnostics' not in bundle and '__thorRuntimeNativeDiagnostics' not in bundle
    assert 'dev.thor.inputlab.permission.CONTROL' not in permissions
    assert 'debug_network' not in manifest
  else:
   assert "name='dev.thor.inputlab'" in badging
   assert 'android.permission.INTERNET' not in permissions
   assert not any('/chat/' in f for f in files)
   assert ('dev.thor.inputlab.RunnerProvider' in manifest) == ('debug' in name)
   assert ('dev.thor.inputlab.ScenarioRunnerProvider' in manifest) == ('debug' in name)
   assert ('dev.thor.inputlab.PresentationFixtureActivity' in manifest) == ('debug' in name)
   assert ('dev.thor.inputlab.PresentationFixtureProvider' in manifest) == ('debug' in name)
   assert ('dev.thor.inputlab.permission.CONTROL' in manifest) == ('debug' in name)
  report[name]={'path':str(path.relative_to(root)),'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'bytes':path.stat().st_size,'permissions':permissions.strip()}
 (root/'build/integration'/f'{name}-manifest.txt').write_text(manifest)
(root/'build/integration/artifacts.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
print(json.dumps(report,ensure_ascii=False,indent=2))
