const {test}=require('node:test');const assert=require('node:assert/strict');const fs=require('node:fs');const {JSDOM}=require('jsdom');
function setup(){const dom=new JSDOM(fs.readFileSync('src/main/assets/chat/index.html','utf8'),{runScripts:'outside-only',url:'https://appassets.androidplatform.net/assets/chat/index.html'});const calls=[];dom.window.ThorUI={postMessage:s=>calls.push(JSON.parse(s))};for(const name of ['vendor/marked.umd.js','vendor/purify.min.js','ui.js'])dom.window.eval(fs.readFileSync('src/main/assets/chat/'+name,'utf8'));dom.window.HTMLDialogElement.prototype.showModal=function(){this.open=true;};dom.window.HTMLDialogElement.prototype.close=function(value=''){this.returnValue=value;this.open=false;this.dispatchEvent(new dom.window.Event('close'));};return{window:dom.window,doc:dom.window.document,calls};}
function state(){return{selected:'a',sessions:[{id:'a',title:'会话 A',archivedAt:null,updatedAt:1,capabilities:{perception:true,search:true,screen_control:false,debug:false}}],messages:[],runs:[],events:[],availability:{perception:'模拟就绪',search:'真实服务未配置',screen_control:'待验证',debug:'高权限'}};}
test('gray approval binds actual run and approval ID; black denial stays structured',()=>{
 const{window,doc,calls}=setup();const s=state();s.messages=[{id:'m',sessionId:'a',runId:'r',role:'assistant',text:'',status:'streaming'}];
 s.runs=[{id:'r',sessionId:'a',status:'awaiting_user',approval:{id:'approval-1',summary:'灰名单 · 屏幕焦点锁 1'}}];window.receiveNative(s);
 doc.querySelector('.wait button').click();assert.deepEqual(calls.at(-1),{op:'approve',runId:'r',approvalId:'approval-1'});
 s.runs[0].status='completed';s.events=[{runId:'r',sequence:1,type:'tool_result',toolCallId:'c',data:{toolId:'debug.shell',error:'COMMAND_BLOCKED',outcome:'observed',content:[{type:'text',text:JSON.stringify({policyTier:'black',executed:false,reason:'禁止格式化'})}]}}];window.receiveNative(s);
 assert.equal(doc.querySelector('.wait'),null);assert.match(doc.querySelector('.tool-card').textContent,/已禁止/);assert.match(doc.querySelector('.structured').textContent,/禁止格式化/);assert.doesNotMatch(doc.querySelector('.tool-card').textContent,/"executed"/);
});
test('untrusted text never creates HTML, scripts, remote links or images',()=>{const{window,doc}=setup();const s=state();s.messages.push({id:'m',sessionId:'a',role:'assistant',text:'<img src="https://evil.invalid" onerror="alert(1)"><script>bad()</script>',status:'completed'});window.receiveNative(s);assert.equal(doc.querySelector('#chat img'),null);assert.equal(doc.querySelector('#chat script'),null);assert.match(doc.querySelector('#chat').textContent,/<img/);});
test('failed and unknown remain visible outside collapsed disclosure',()=>{const{window,doc}=setup();const s=state();s.messages.push({id:'m',sessionId:'a',runId:'r',role:'assistant',text:'',status:'failed'});s.runs.push({id:'r',sessionId:'a',status:'failed',expanded:false,stopReason:'工具失败'});s.events.push({runId:'r',sequence:1,outcome:'unknown',summary:'提交结果未知',elapsedMs:100,type:'tool_result'});window.receiveNative(s);assert.ok(!doc.querySelector('details.run') || !doc.querySelector('details.run').open);const warnings=[...doc.querySelectorAll('.attention')];assert.equal(warnings.length,2);assert.ok(warnings.every(n=>n.parentElement.tagName!=='DETAILS'));});
test('background tasks keep running while composer stop targets only the selected session',()=>{const{window,doc,calls}=setup();const s=state();s.selected='b';s.sessions.push({...s.sessions[0],id:'b',title:'会话 B'});s.runs.push({id:'r',sessionId:'a',status:'executing_tools'});window.receiveNative(s);assert.equal(doc.querySelector('#active').hidden,true);assert.equal(doc.querySelector('#send').textContent,'发送');assert.ok(doc.querySelector('.session-running'));s.runs.push({id:'r2',sessionId:'b',status:'generating'});window.receiveNative(s);assert.equal(doc.querySelector('#send').textContent,'停止');doc.querySelector('#composer').dispatchEvent(new window.Event('submit',{cancelable:true}));assert.deepEqual(calls.at(-1),{op:'stop',runId:'r2'});});
test('capability choice and unavailable reason are distinct and bound to session',()=>{const{window,doc,calls}=setup();window.receiveNative(state());assert.match(doc.querySelector('#cap-panel').textContent,/意愿：已选择 · 真实服务未配置/);const check=doc.querySelector('input[aria-label="上屏感知"]');check.checked=false;check.dispatchEvent(new window.Event('change'));assert.deepEqual(calls.at(-1),{op:'capability',sessionId:'a',capability:'perception',enabled:false});});
test('waiting confirmation remains outside disclosure and approves exact run',()=>{const{window,doc,calls}=setup();const s=state();s.runs.push({id:'r',sessionId:'a',status:'awaiting_user',expanded:false,approval:{id:'approval-1',summary:'volume 60 → 70'}});s.messages.push({id:'m',sessionId:'a',runId:'r',role:'assistant',text:'',status:'streaming'});s.events.push({runId:'r',type:'awaiting_user',sequence:1,summary:'volume 60 → 70',elapsedMs:10});window.receiveNative(s);assert.match(doc.querySelector('.wait').textContent,/volume 60 → 70/);doc.querySelector('.wait button').click();assert.deepEqual(calls.at(-1),{op:'approve',runId:'r',approvalId:'approval-1'});});
test('long histories render 200 messages and 50 sessions without persistence in web storage',()=>{const{window,doc}=setup();const s=state();for(let i=1;i<50;i++)s.sessions.push({...s.sessions[0],id:'s'+i});for(let i=0;i<200;i++)s.messages.push({id:'m'+i,sessionId:'a',role:i%2?'assistant':'user',text:'long/url/'.repeat(100),status:'completed'});window.receiveNative(s);assert.equal(doc.querySelectorAll('.message').length,200);assert.equal(doc.querySelectorAll('.session-row').length,50);assert.equal(window.localStorage.length,0);});
test('storage recovery failure is explicit and disables sending without clearing history',()=>{const{window,doc}=setup();window.receiveNative({fatal:'存储恢复失败；历史保留'});assert.equal(doc.querySelector('#notice').hidden,false);assert.match(doc.querySelector('#notice').textContent,/历史保留/);assert.equal(doc.querySelector('#send').disabled,true);});
function pointer(window,node,type,extra={}) {const e=new window.Event(type,{bubbles:true,cancelable:true});Object.assign(e,{pointerId:1,isPrimary:true,button:0,clientY:200,...extra});node.dispatchEvent(e);}
test('voice mode hides composer and transcript, releases once, resets idle and ignores duplicate finals',()=>{
 const{window,doc,calls}=setup();window.receiveNative(state());doc.querySelector('#voice-toggle').click();assert.equal(doc.querySelector('#input').hidden,true);assert.equal(doc.querySelector('#voice-panel').hidden,false);
 const hold=doc.querySelector('#voice-hold');pointer(window,hold,'pointerdown');const start=calls.at(-1);assert.equal(start.op,'voice.start');
 window.receiveVoiceState({id:start.id,sessionId:'a',phase:'recording',text:'你哈'});assert.equal(doc.querySelector('#voice-transcript').textContent,'');assert.equal(doc.querySelector('#voice-transcript').hidden,true);
 window.receiveVoiceState({id:start.id,sessionId:'a',phase:'recording',text:'你好'});assert.equal(doc.querySelector('#voice-transcript').textContent,'');
 assert.equal(calls.filter(c=>c.op==='send').length,0);pointer(window,hold,'pointerup');pointer(window,hold,'pointerup');assert.equal(calls.filter(c=>c.op==='voice.finish').length,1);
 const final={id:start.id,sessionId:'a',phase:'complete',text:'你好世界',submit:'你好世界'};window.receiveVoiceState(final);window.receiveVoiceState(final);
 assert.equal(calls.filter(c=>c.op==='send').length,1);assert.equal(calls.find(c=>c.op==='send').text,'你好世界');assert.equal(doc.querySelector('#voice-hold').textContent,'按住说话');assert.equal(doc.querySelector('#voice-overlay').hidden,true);assert.equal(doc.querySelector('#voice-transcript').textContent,'');
});
test('voice swipe cancellation and session switch discard late final callbacks',()=>{
 const{window,doc,calls}=setup();const s=state();window.receiveNative(s);doc.querySelector('#voice-toggle').click();const node=doc.querySelector('#voice-hold');
 pointer(window,node,'pointerdown');let started=calls.at(-1);pointer(window,node,'pointermove',{clientY:-200});assert.equal(calls.at(-1).op,'voice.start');assert.ok(doc.querySelector('#voice-overlay').classList.contains('cancel-pending'));pointer(window,node,'pointerup',{clientY:-200});assert.equal(calls.at(-1).op,'voice.cancel');
 window.receiveVoiceState({id:started.id,sessionId:'a',phase:'complete',text:'late',submit:'late'});assert.equal(calls.filter(c=>c.op==='send').length,0);
 pointer(window,node,'pointerdown');started=calls.at(-1);s.selected='b';s.sessions.push({...s.sessions[0],id:'b'});window.receiveNative(s);
 window.receiveVoiceState({id:started.id,sessionId:'a',phase:'complete',submit:'wrong session'});assert.equal(calls.filter(c=>c.op==='send').length,0);
});
test('failed recognition keeps transcript editable without sending partial speech',()=>{
 const{window,doc,calls}=setup();window.receiveNative(state());doc.querySelector('#voice-toggle').click();pointer(window,doc.querySelector('#voice-hold'),'pointerdown');const started=calls.at(-1);
 window.receiveVoiceState({id:started.id,sessionId:'a',phase:'error',text:'保留草稿',error:'网络断开'});assert.match(doc.querySelector('#voice-status').textContent,/网络断开/);
 doc.querySelector('#voice-edit').click();assert.equal(doc.querySelector('#input').value,'保留草稿');assert.equal(doc.querySelector('#input').hidden,false);assert.equal(calls.filter(c=>c.op==='send').length,0);
});
test('stream events become one tool card and an aggregated thinking panel without raw event JSON',()=>{
 const{window,doc}=setup();const s=state();s.runs=[{id:'r',sessionId:'a',status:'executing_tools',expanded:true}];s.messages=[{id:'m',sessionId:'a',runId:'r',role:'assistant',text:'回答',status:'streaming'}];
 s.events=[{type:'thinking_delta',data:{text:'先看'},summary:'{"type":"thinking_delta"}'},{type:'thinking_delta',data:{text:'攻略'}},{type:'text_delta',data:{text:'回答'}},{type:'tool_call_update',toolCallId:'c',data:{toolName:'skills_read',arguments:{name:'game-guide'}}},{type:'tool_started',toolCallId:'c',data:{toolId:'skills.read',arguments:{name:'game-guide'}}},{type:'tool_result',toolCallId:'c',data:{toolId:'skills.read',outcome:'observed',content:[{type:'text',text:JSON.stringify({content:'安全文本 <script>bad()</script>',resources:['references/a.md']})}]}}].map((e,i)=>({...e,runId:'r',sequence:i+1,elapsedMs:10}));
 window.receiveNative(s);assert.equal(doc.querySelectorAll('.tool-card').length,1);assert.match(doc.querySelector('.thinking').textContent,/先看攻略/);assert.match(doc.querySelector('.tool-card').textContent,/读取 Skill · 已完成/);assert.ok(doc.querySelector('.structured dt'));assert.equal(doc.querySelector('#chat pre'),null);assert.equal(doc.querySelector('#chat script'),null);assert.doesNotMatch(doc.querySelector('#chat').textContent,/thinking_delta|text_delta|"toolId"/);
});
test('Skill manager exposes metadata and explicit read, enable and import actions',()=>{
 const{window,doc,calls}=setup();const s=state();s.skills=[{name:'game-guide',description:'需要攻略时使用',hash:'version',enabled:true,builtin:false,fileCount:2}];window.receiveNative(s);
 assert.match(doc.querySelector('#skills-list').textContent,/game-guide/);assert.equal(doc.querySelector('#skill-detail').hidden,true);
 doc.querySelector('.skill-row button').click();assert.deepEqual(calls.at(-1),{op:'skillRead',name:'game-guide'});doc.querySelectorAll('.skill-row button')[1].click();assert.deepEqual(calls.at(-1),{op:'skillEnable',name:'game-guide',hash:'version',enabled:false});
 s.skillDetail={name:'game-guide',path:'SKILL.md',content:'<img src="evil">',resources:['SKILL.md','references/a.md']};window.receiveNative(s);assert.equal(doc.querySelector('#skill-content').textContent,'<img src="evil">');assert.equal(doc.querySelector('#skill-content img'),null);
 doc.querySelector('#skills-import').click();assert.equal(calls.at(-1).op,'skillImport');
});

test('compact composer keeps attachments and Skill selection in plus menu, hides probe success',()=>{const{window,doc}=setup();const s=state();s.modelStatus='配置探测已通过';window.receiveNative(s);assert.equal(doc.querySelector('#notice').hidden,true);assert.equal(doc.querySelector('#voice-settings'),null);assert.ok(doc.querySelector('#more-panel #attach'));assert.ok(doc.querySelector('#more-panel #skill-choose'));doc.querySelector('#more').click();assert.equal(doc.querySelector('#more-panel').hidden,false);});
test('model effort selection and per-session drafts stay isolated',()=>{const{window,doc,calls}=setup();const s=state();s.modelChoices=[{id:'deepseek-v4.1-flash',name:'DeepSeek V4.1 Flash'}];s.modelSelection={modelId:'deepseek-v4.1-flash',label:'DeepSeek V4.1 Flash',efforts:['low','high','max'],thinkingEffort:'high',contextWindow:1000000};window.receiveNative(s);assert.match(doc.querySelector('#model-context').textContent,/1M/);doc.querySelector('#effort-select').value='max';doc.querySelector('#effort-select').dispatchEvent(new window.Event('change'));assert.deepEqual(calls.at(-1),{op:'sessionOptions',sessionId:'a',thinkingEffort:'max'});doc.querySelector('#input').value='A 的草稿';const next=structuredClone(s);next.selected='b';next.sessions.push({...next.sessions[0],id:'b'});window.receiveNative(next);assert.equal(doc.querySelector('#input').value,'');window.receiveSendFailure('A 发送失败','a');assert.equal(doc.querySelector('#input').value,'');window.receiveNative(s);assert.equal(doc.querySelector('#input').value,'A 发送失败');});
test('configuration probe messages do not consume chat space',()=>{const{window,doc}=setup();const s=state();s.runs=[{id:'probe',sessionId:'a',probe:true,status:'completed'}];s.messages=[{id:'probe-text',sessionId:'a',runId:'probe',role:'assistant',text:'PROBE_OK'}];window.receiveNative(s);assert.doesNotMatch(doc.querySelector('#chat').textContent,/PROBE_OK/);});

test('instant failures remain actionable without a transient running banner',()=>{const{window,doc}=setup();const s=state();s.runs=[{id:'r',sessionId:'a',status:'failed',expanded:false}];s.messages=[{id:'m',runId:'r',sessionId:'a',role:'assistant',text:'',status:'complete'}];window.receiveNative(s);assert.match(doc.querySelector('.attention').textContent,/未能完成/);assert.equal(doc.querySelector('#active').hidden,true);assert.equal(doc.querySelector('#send').textContent,'发送');});

// A second contact's cancellation must not end the finger that owns recording.
test('unrelated pointer cancellation does not release an active voice hold',()=>{
 const{window,doc,calls}=setup();window.receiveNative(state());doc.querySelector('#voice-toggle').click();const node=doc.querySelector('#voice-hold');
 pointer(window,node,'pointerdown',{pointerId:1});
 pointer(window,node,'pointercancel',{pointerId:2,isPrimary:false});
 pointer(window,node,'lostpointercapture',{pointerId:2,isPrimary:false});
 assert.equal(calls.filter(c=>c.op==='voice.cancel').length,0);
 pointer(window,node,'pointerup',{pointerId:1});assert.equal(calls.filter(c=>c.op==='voice.finish').length,1);
});
test('voice release belongs to its starting input and real interruption still cancels',()=>{
 const{window,doc,calls}=setup();window.receiveNative(state());doc.querySelector('#voice-toggle').click();const node=doc.querySelector('#voice-hold');
 pointer(window,node,'pointerdown',{pointerId:1});node.dispatchEvent(new window.KeyboardEvent('keyup',{key:'Enter',bubbles:true}));
 assert.equal(calls.filter(c=>c.op==='voice.finish').length,0);
 pointer(window,node,'pointercancel',{pointerId:1});assert.equal(calls.filter(c=>c.op==='voice.cancel').length,1);
 node.dispatchEvent(new window.KeyboardEvent('keydown',{key:' ',bubbles:true,cancelable:true}));
 pointer(window,node,'pointerup',{pointerId:1});assert.equal(calls.filter(c=>c.op==='voice.finish').length,0);
 node.dispatchEvent(new window.KeyboardEvent('keyup',{key:' ',bubbles:true,cancelable:true}));assert.equal(calls.filter(c=>c.op==='voice.finish').length,1);
});
test('losing capture alone keeps recording and release outside the button finishes once',()=>{
 const{window,doc,calls}=setup();window.receiveNative(state());doc.querySelector('#voice-toggle').click();const node=doc.querySelector('#voice-hold');
 node.setPointerCapture=()=>{throw new Error('capture unavailable');};
 pointer(window,node,'pointerdown');pointer(window,node,'lostpointercapture');
 assert.equal(calls.filter(c=>c.op==='voice.start').length,1);assert.equal(calls.filter(c=>c.op==='voice.cancel').length,0);
 pointer(window,doc.body,'pointerup');pointer(window,doc.body,'pointerup');assert.equal(calls.filter(c=>c.op==='voice.finish').length,1);
});
test('actual primary pointer cancellation outside the button and window blur stop capture',()=>{
 const{window,doc,calls}=setup();window.receiveNative(state());doc.querySelector('#voice-toggle').click();const node=doc.querySelector('#voice-hold');
 pointer(window,node,'pointerdown');pointer(window,doc.body,'pointercancel');assert.equal(calls.filter(c=>c.op==='voice.cancel').length,1);
 pointer(window,node,'pointerdown');window.dispatchEvent(new window.Event('blur'));assert.equal(calls.filter(c=>c.op==='voice.cancel').length,2);
});

test('Markdown renders headings, lists, emphasis, fenced code and tables; links open through Native only',()=>{
 const{window,doc,calls}=setup();const s=state();s.messages=[{id:'md',sessionId:'a',role:'assistant',text:'# 计划\n\n1. **查看上屏**\n2. 再执行\n\n```js\nconst x = "<safe>";\n```\n\n|项|状态|\n|--|--|\n|截图|完成|\n\n[文档](https://example.org/help)\n\n![不要请求](https://evil.invalid/track.png)',status:'completed'}];window.receiveNative(s);
 assert.equal(doc.querySelector('.markdown h1').textContent,'计划');assert.equal(doc.querySelectorAll('.markdown ol li').length,2);assert.equal(doc.querySelector('.markdown strong').textContent,'查看上屏');assert.match(doc.querySelector('.markdown pre code').textContent,/<safe>/);assert.ok(doc.querySelector('.markdown table'));assert.equal(doc.querySelector('.markdown img'),null);
 const e=new window.MouseEvent('click',{bubbles:true,cancelable:true});doc.querySelector('.markdown a').dispatchEvent(e);assert.equal(e.defaultPrevented,true);assert.deepEqual(calls.at(-1),{op:'openLink',url:'https://example.org/help'});
});
test('Markdown rejects script schemes and raw HTML handlers, forms and remote media',()=>{
 const{window,doc}=setup();const s=state();s.messages=[{id:'md',sessionId:'a',role:'assistant',text:'[bad](javascript:alert%281%29)\n\n<img src=x onerror=alert(1)>\n<svg onload=alert(1)><a href="javascript:alert(1)">x</a></svg>\n<form><input name=ThorUI></form>\n![image](https://remote.invalid/pixel)',status:'completed'}];window.receiveNative(s);
 assert.equal(doc.querySelector('#chat img,#chat svg,#chat script,#chat form,#chat input'),null);assert.ok([...doc.querySelectorAll('#chat a')].every(a=>!a.hasAttribute('href')));assert.match(doc.querySelector('#chat').textContent,/<img/);
});
test('unchanged historical messages and disclosures keep DOM identity while the latest answer streams',()=>{
 const{window,doc}=setup();const s=state();s.messages=[{id:'old',sessionId:'a',role:'assistant',text:'**旧回答**'},{id:'new',sessionId:'a',role:'assistant',runId:'r',text:'开始'}];s.runs=[{id:'r',sessionId:'a',status:'generating'}];window.receiveNative(s);const old=doc.querySelector('.message'),bold=doc.querySelector('.message strong'),latest=doc.querySelectorAll('.message')[1],row=doc.querySelector('.session-row');
 const next=structuredClone(s);next.messages[1].text='开始继续';next.sessions[0].updatedAt=999;window.receiveNative(next);assert.equal(doc.querySelector('.message'),old);assert.equal(doc.querySelector('.message strong'),bold);assert.equal(doc.querySelectorAll('.message')[1],latest);assert.equal(doc.querySelector('.session-row'),row);assert.match(latest.textContent,/开始继续/);
});
test('voice upward drift stays recording, large swipe only arms cancellation, and sliding back sends',()=>{
 const{window,doc,calls}=setup();window.receiveNative(state());doc.querySelector('#voice-toggle').click();const node=doc.querySelector('#voice-hold');pointer(window,node,'pointerdown',{clientY:600});const start=calls.at(-1);
 pointer(window,node,'pointermove',{clientY:510});assert.equal(doc.querySelector('#voice-overlay').classList.contains('cancel-pending'),false);assert.equal(calls.filter(c=>c.op==='voice.cancel').length,0);
 pointer(window,node,'pointermove',{clientY:100});assert.equal(doc.querySelector('#voice-overlay').classList.contains('cancel-pending'),true);assert.equal(calls.filter(c=>c.op==='voice.cancel').length,0);
 pointer(window,node,'pointermove',{clientY:590});assert.equal(doc.querySelector('#voice-overlay').classList.contains('cancel-pending'),false);pointer(window,doc.body,'pointerup',{clientY:590});assert.equal(calls.filter(c=>c.op==='voice.finish').length,1);assert.equal(calls.filter(c=>c.op==='voice.cancel').length,0);
 window.receiveVoiceState({id:start.id,sessionId:'a',phase:'complete',submit:'恢复后的语音'});assert.equal(calls.filter(c=>c.op==='send').length,1);assert.equal(doc.querySelector('#voice-overlay').hidden,true);
});
test('session rows show only title; context menu opens actions and deletion requires confirmation',()=>{
 const{window,doc,calls}=setup();window.receiveNative(state());const row=doc.querySelector('.session-row');assert.equal(row.textContent,'会话 A');assert.equal(row.querySelector('small'),null);assert.equal(row.querySelectorAll('button').length,1);
 row.querySelector('button').dispatchEvent(new window.MouseEvent('contextmenu',{bubbles:true,cancelable:true}));assert.equal(doc.querySelector('#session-menu').open,true);doc.querySelectorAll('#session-menu-actions button')[2].click();assert.equal(doc.querySelector('#modal').open,true);assert.equal(calls.filter(c=>c.op==='delete').length,0);doc.querySelector('#modal').close('ok');assert.deepEqual(calls.at(-1),{op:'delete',sessionId:'a'});
});
test('session long press opens options while a scrolling gesture does not',async()=>{
 const{window,doc}=setup();window.receiveNative(state());const row=doc.querySelector('.session-select');pointer(window,row,'pointerdown',{clientX:10,clientY:20});pointer(window,row,'pointermove',{clientX:10,clientY:70});await new Promise(r=>setTimeout(r,580));assert.equal(doc.querySelector('#session-menu').open,false);
 pointer(window,row,'pointerdown',{clientX:10,clientY:20});await new Promise(r=>setTimeout(r,580));assert.equal(doc.querySelector('#session-menu').open,true);pointer(window,row,'pointerup');
});
test('preparation status stays inside composer and stop targets the selected pending task',()=>{
 const{window,doc,calls}=setup();window.receiveNative(state());window.receivePreparationState({sessionId:'a',phase:'probing'});assert.match(doc.querySelector('#composer').textContent,/首次连接/);assert.equal(doc.querySelector('#send').textContent,'停止');doc.querySelector('#composer').dispatchEvent(new window.Event('submit',{cancelable:true}));assert.deepEqual(calls.at(-1),{op:'stopPreparing',sessionId:'a'});window.receivePreparationState({sessionId:'a',phase:'idle'});assert.equal(doc.querySelector('#preparation-status').hidden,true);assert.equal(doc.querySelector('#send').textContent,'发送');
});
test('Escape cancels a keyboard voice hold before moving focus away',()=>{
 const{window,doc,calls}=setup();window.receiveNative(state());doc.querySelector('#voice-toggle').click();const node=doc.querySelector('#voice-hold');node.dispatchEvent(new window.KeyboardEvent('keydown',{key:' ',bubbles:true,cancelable:true}));doc.dispatchEvent(new window.KeyboardEvent('keydown',{key:'Escape',bubbles:true}));node.dispatchEvent(new window.KeyboardEvent('keyup',{key:' ',bubbles:true}));assert.equal(calls.filter(c=>c.op==='voice.cancel').length,1);assert.equal(calls.filter(c=>c.op==='voice.finish').length,0);assert.equal(doc.querySelector('#voice-overlay').hidden,true);
});
