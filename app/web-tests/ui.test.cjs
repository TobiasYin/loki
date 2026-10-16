const {test}=require('node:test');const assert=require('node:assert/strict');const fs=require('node:fs');const {JSDOM}=require('jsdom');
function setup(beforeScript){const dom=new JSDOM(fs.readFileSync('src/main/assets/chat/index.html','utf8'),{runScripts:'outside-only',url:'https://appassets.androidplatform.net/assets/chat/index.html'});const calls=[];beforeScript?.(dom.window,dom.window.document);dom.window.ThorUI={postMessage:s=>calls.push(JSON.parse(s))};for(const name of ['vendor/marked.umd.js','vendor/purify.min.js','ui.js'])dom.window.eval(fs.readFileSync('src/main/assets/chat/'+name,'utf8'));dom.window.HTMLDialogElement.prototype.showModal=function(){this.open=true;};dom.window.HTMLDialogElement.prototype.close=function(value=''){this.returnValue=value;this.open=false;this.dispatchEvent(new dom.window.Event('close'));};return{window:dom.window,doc:dom.window.document,calls};}
function state(){return{selected:'a',sessions:[{id:'a',title:'会话 A',archivedAt:null,updatedAt:1,capabilities:{perception:true,search:true,screen_control:false,debug:false}}],messages:[],runs:[],events:[],availability:{perception:'模拟就绪',search:'真实服务未配置',screen_control:'待验证',debug:'高权限'}};}
function disclose(window,node,open=true){assert.ok(node,'Disclosure must exist');if(node.open!==open)node.querySelector(':scope > summary').click();node.dispatchEvent(new window.Event('toggle'));return node;}
function openOperations(window,doc){return disclose(window,doc.querySelector('details.run'));}
test('gray approval binds actual run and approval ID; black denial stays structured',()=>{
 const{window,doc,calls}=setup();const s=state();s.messages=[{id:'m',sessionId:'a',runId:'r',role:'assistant',text:'',status:'streaming'}];
 s.runs=[{id:'r',sessionId:'a',status:'awaiting_user',approval:{id:'approval-1',summary:'灰名单 · 屏幕焦点锁 1'}}];window.receiveNative(s);
 doc.querySelector('.wait button').click();assert.deepEqual(calls.at(-1),{op:'approve',runId:'r',approvalId:'approval-1'});
 s.runs[0].status='completed';s.events=[{runId:'r',sequence:1,type:'tool_result',toolCallId:'c',data:{toolId:'debug.shell',error:'COMMAND_BLOCKED',outcome:'observed',content:[{type:'text',text:JSON.stringify({policyTier:'black',executed:false,reason:'禁止格式化'})}]}}];window.receiveNative(s);
 assert.equal(doc.querySelector('.wait'),null);assert.match(doc.querySelector('.attention').textContent,/已禁止/);assert.equal(doc.querySelector('.tool-card'),null);openOperations(window,doc);disclose(window,doc.querySelector('.tool-card'));assert.match(doc.querySelector('.tool-card').textContent,/已禁止/);assert.match(doc.querySelector('.structured').textContent,/禁止格式化/);assert.doesNotMatch(doc.querySelector('.tool-card').textContent,/"executed"/);
});
test('untrusted text never creates HTML, scripts, remote links or images',()=>{const{window,doc}=setup();const s=state();s.messages.push({id:'m',sessionId:'a',role:'assistant',text:'<img src="https://evil.invalid" onerror="alert(1)"><script>bad()</script>',status:'completed'});window.receiveNative(s);assert.equal(doc.querySelector('#chat img'),null);assert.equal(doc.querySelector('#chat script'),null);assert.match(doc.querySelector('#chat').textContent,/<img/);});
test('failed and unknown remain visible outside collapsed disclosure',()=>{const{window,doc}=setup();const s=state();s.messages.push({id:'m',sessionId:'a',runId:'r',role:'assistant',text:'',status:'failed'});s.runs.push({id:'r',sessionId:'a',status:'failed',expanded:false,stopReason:'工具失败'});s.events.push({runId:'r',sequence:1,outcome:'unknown',summary:'提交结果未知',elapsedMs:100,type:'tool_result'});window.receiveNative(s);assert.ok(!doc.querySelector('details.run') || !doc.querySelector('details.run').open);const warnings=[...doc.querySelectorAll('.attention')];assert.equal(warnings.length,2);assert.ok(warnings.every(n=>n.parentElement.tagName!=='DETAILS'));});
test('background tasks keep running while composer stop targets only the selected session',()=>{const{window,doc,calls}=setup();const s=state();s.selected='b';s.sessions.push({...s.sessions[0],id:'b',title:'会话 B'});s.runs.push({id:'r',sessionId:'a',status:'executing_tools'});window.receiveNative(s);assert.equal(doc.querySelector('#active').hidden,true);assert.equal(doc.querySelector('#send').textContent,'发送');assert.ok(doc.querySelector('.session-running'));s.runs.push({id:'r2',sessionId:'b',status:'generating'});window.receiveNative(s);assert.equal(doc.querySelector('#send').textContent,'停止');doc.querySelector('#composer').dispatchEvent(new window.Event('submit',{cancelable:true}));assert.deepEqual(calls.at(-1),{op:'stop',runId:'r2'});});
test('capability choice and unavailable reason are distinct and bound to session',()=>{const{window,doc,calls}=setup();window.receiveNative(state());assert.match(doc.querySelector('#cap-panel').textContent,/意愿：已选择 · 真实服务未配置/);const check=doc.querySelector('input[aria-label="屏幕感知"]');check.checked=false;check.dispatchEvent(new window.Event('change'));const request=calls.at(-1);assert.ok(request.clientMutationId);assert.deepEqual({...request,clientMutationId:undefined},{op:'capability',sessionId:'a',capability:'perception',enabled:false,clientMutationId:undefined});});
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
 window.receiveNative(s);assert.equal(doc.querySelectorAll('.tool-card').length,1);assert.equal(doc.querySelector('.structured'),null);disclose(window,doc.querySelector('.tool-card'));assert.match(doc.querySelector('.thinking').textContent,/先看攻略/);assert.match(doc.querySelector('.tool-card').textContent,/读取 Skill · 已完成/);assert.ok(doc.querySelector('.structured dt'));assert.equal(doc.querySelector('#chat pre'),null);assert.equal(doc.querySelector('#chat script'),null);assert.doesNotMatch(doc.querySelector('#chat').textContent,/thinking_delta|text_delta|"toolId"/);
});
test('brightness percentages are scoped to current percent objects; legacy and unrelated values keep their units',()=>{
 const{window,doc}=setup();const s=state();s.runs=[{id:'r',sessionId:'a',status:'completed',expanded:true}];s.messages=[{id:'m',sessionId:'a',runId:'r',role:'assistant',text:'完成'}];
 const definitions=[['percent','debug.thor_brightness',{action:'set',screen:'upper',value:42},{unit:'percent',before:49,brightness:42,requested:42,quantizationTolerance:1,nested:{value:17,before:23}}],['legacy','debug.thor_brightness',{action:'set',screen:'upper',value:124},{before:125,brightness:124,requested:124}],['other','files.write',{value:42},{before:49,brightness:124,requested:42}]];
 s.events=definitions.flatMap(([toolCallId,toolId,args,data],i)=>[{runId:'r',sequence:i*2+1,type:'tool_started',toolCallId,data:{toolId,arguments:args}},{runId:'r',sequence:i*2+2,type:'tool_result',toolCallId,data:{toolId,outcome:'submitted',content:[{type:'text',text:JSON.stringify(data)}]}}]);window.receiveNative(s);
 const [percent,legacy,other]=doc.querySelectorAll('.tool-card');for(const card of [percent,legacy,other])disclose(window,card);const fields=node=>[...node.querySelectorAll('dt')].map(dt=>[dt.textContent,dt.nextElementSibling.textContent]);
 assert.ok(fields(percent).some(([k,v])=>k==='value'&&v==='42%'));for(const [key,value] of [['修改前','49%'],['亮度','42%'],['请求值','42%'],['量化容差','1%']])assert.ok(fields(percent).some(([k,v])=>k===key&&v===value));
 assert.ok(fields(percent).some(([k,v])=>k==='修改前'&&v==='23'));assert.doesNotMatch(legacy.textContent,/%/);assert.doesNotMatch(other.textContent,/%/);assert.ok(fields(legacy).some(([k,v])=>k==='亮度'&&v==='124'));
 const pending=structuredClone(s);pending.runs[0].status='executing_tools';pending.events=[{runId:'r',sequence:1,type:'tool_started',toolCallId:'new',data:{toolId:'debug.thor_brightness',arguments:{action:'set',screen:'lower',value:37}}}];window.receiveNative(pending);disclose(window,doc.querySelector('.tool-card'));assert.ok(fields(doc.querySelector('.tool-card')).some(([k,v])=>k==='value'&&v==='37%'));
});
test('semantic tree, scrolling and dragging use readable operation labels',()=>{
 const{window,doc}=setup();const s=state();s.runs=[{id:'r',sessionId:'a',status:'completed',expanded:true}];s.messages=[{id:'m',sessionId:'a',runId:'r',role:'assistant',text:'完成'}];s.events=['perception.ui_tree','input.scroll','input.drag'].map((toolId,i)=>({runId:'r',sequence:i+1,type:'tool_started',toolCallId:String(i),data:{toolId,arguments:{}}}));window.receiveNative(s);assert.deepEqual([...doc.querySelectorAll('.tool-card summary')].map(n=>n.textContent),['读取组件树 · 执行中','滚动页面 · 执行中','拖动控件 · 执行中']);
});
test('Skill manager exposes metadata and explicit read, enable and import actions',()=>{
 const{window,doc,calls}=setup();const s=state();s.skills=[{name:'game-guide',description:'需要攻略时使用',hash:'version',enabled:true,builtin:false,fileCount:2}];window.receiveNative(s);
 assert.match(doc.querySelector('#skills-list').textContent,/game-guide/);assert.equal(doc.querySelector('#skill-detail').hidden,true);
 doc.querySelector('.skill-row button').click();assert.deepEqual(calls.at(-1),{op:'skillRead',name:'game-guide'});doc.querySelectorAll('.skill-row button')[1].click();assert.deepEqual(calls.at(-1),{op:'skillEnable',name:'game-guide',hash:'version',enabled:false});
 s.skillDetail={name:'game-guide',path:'SKILL.md',content:'<img src="evil">',resources:['SKILL.md','references/a.md']};window.receiveNative(s);assert.equal(doc.querySelector('#skill-content').textContent,'<img src="evil">');assert.equal(doc.querySelector('#skill-content img'),null);
 doc.querySelector('#skills-import').click();assert.equal(calls.at(-1).op,'skillImport');
});

test('compact composer keeps attachments and Skill selection in plus menu, hides probe success',()=>{const{window,doc}=setup();const s=state();s.modelStatus='配置探测已通过';window.receiveNative(s);assert.equal(doc.querySelector('#notice').hidden,true);assert.equal(doc.querySelector('#voice-settings'),null);assert.ok(doc.querySelector('#more-panel #attach'));assert.ok(doc.querySelector('#more-panel #skill-choose'));doc.querySelector('#more').click();assert.equal(doc.querySelector('#more-panel').hidden,false);});
test('model effort selection and per-session drafts stay isolated',()=>{const{window,doc,calls}=setup();const s=state();s.modelChoices=[{id:'deepseek-v4.1-flash',name:'DeepSeek V4.1 Flash'}];s.modelSelection={modelId:'deepseek-v4.1-flash',label:'DeepSeek V4.1 Flash',efforts:['low','high','max'],thinkingEffort:'high',contextWindow:1000000};window.receiveNative(s);assert.match(doc.querySelector('#model-context').textContent,/1M/);doc.querySelector('#effort-select [data-value=max]').click();assert.deepEqual(calls.at(-1),{op:'sessionOptions',sessionId:'a',thinkingEffort:'max'});doc.querySelector('#input').value='A 的草稿';const next=structuredClone(s);next.selected='b';next.sessions.push({...next.sessions[0],id:'b'});window.receiveNative(next);assert.equal(doc.querySelector('#input').value,'');window.receiveSendFailure('A 发送失败','a');assert.equal(doc.querySelector('#input').value,'');window.receiveNative(s);assert.equal(doc.querySelector('#input').value,'A 发送失败');});
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

function treeHistory(callCount=20){
 const s=state();s.runs=[{id:'large',sessionId:'a',status:'executing_tools',expanded:false}];s.messages=[{id:'large-message',runId:'large',sessionId:'a',role:'assistant',text:'继续读取'}];
 s.events=Array.from({length:callCount},(_,i)=>{
  const nodes=Array.from({length:36},(_,j)=>({text:`tool-${i}-node-${j}`,visibleToUser:j<8,scrollAncestorRef:`current-container-${i}`,bounds:[0,j*100,500,j*100+80],...Object.fromEntries(Array.from({length:26},(_,k)=>[`field${k}`,`value-${i}-${j}-${k}`]))}));
  return [{runId:'large',sequence:i*2+1,type:'tool_started',toolCallId:`call-${i}`,data:{toolId:'perception.ui_tree',arguments:{query:`target-${i}`}}},{runId:'large',sequence:i*2+2,type:'tool_result',toolCallId:`call-${i}`,data:{toolId:'perception.ui_tree',outcome:'observed',content:[{type:'text',text:JSON.stringify({nodes,coverage:{offscreenExposedNodes:28}})}]}}];
 }).flat();return s;
}
test('large collapsed operation histories construct bounded DOM across stream updates; opening each level reveals only its requested fields',()=>{
 const{window,doc}=setup();const s=treeHistory();let created=0;const create=doc.createElement.bind(doc);doc.createElement=(...args)=>{created++;return create(...args);};
 window.receiveNative(s);assert.ok(created<200,`Collapsed history created ${created} elements`);assert.ok(doc.querySelector('#chat').querySelectorAll('*').length<35);
 assert.match(doc.querySelector('details.run > summary').textContent,/20 项操作/);assert.equal(doc.querySelector('.tool-card,.tool-body,.structured'),null);assert.ok(doc.querySelector('.activity-label.shimmer'));
 const baseline=created;
 for(let i=0;i<8;i++){s.messages[0].text+='。';s.events.push({runId:'large',sequence:41+i,type:'text_delta',data:{text:'。'}});window.receiveNative(s);}
 assert.ok(created-baseline<200,`Collapsed streaming rebuilt ${created-baseline} elements`);assert.equal(doc.querySelector('.tool-card,.structured'),null);
 openOperations(window,doc);const cards=[...doc.querySelectorAll('.tool-card')];assert.equal(cards.length,20);assert.ok(cards.every(card=>card.children.length===1));assert.equal(doc.querySelector('.structured'),null);
 disclose(window,cards[7]);assert.match(cards[7].textContent,/tool-7-node-35/);assert.match(cards[7].textContent,/current-container-7/);assert.match(cards[7].textContent,/value-7-35-25/);
 assert.equal(doc.querySelectorAll('.tool-body').length,1);assert.ok(cards.filter(card=>card!==cards[7]).every(card=>card.children.length===1));
 disclose(window,cards[7],false);assert.equal(doc.querySelector('.tool-body,.structured'),null);disclose(window,doc.querySelector('details.run'),false);assert.equal(doc.querySelector('.tool-card'),null);
});
test('expanded tool content stays current across deltas and session switches, while collapsed siblings stay lazy',()=>{
 const{window,doc,calls}=setup();const s=treeHistory(2);s.events.unshift({runId:'large',sequence:0,type:'thinking_delta',data:{text:'先检查'}});window.receiveNative(s);
 disclose(window,doc.querySelector('.thinking'));openOperations(window,doc);disclose(window,doc.querySelectorAll('.tool-card')[0]);
 assert.ok(calls.some(c=>c.op==='expanded' && c.runId==='large' && c.expanded));
 doc.querySelector('.tool-card > summary').focus();
 // Native has not acknowledged expanded yet. A simultaneous stream update must
 // retain the user's immediate disclosure choice instead of closing it.
 const next=structuredClone(s);next.messages[0].text='读取到新结果';next.events.push({runId:'large',sequence:5,type:'tool_result',toolCallId:'call-0',data:{toolId:'perception.ui_tree',outcome:'observed',content:[{type:'text',text:JSON.stringify({nodes:[{text:'更新后的实际字段',visibleToUser:true}]})}]}});window.receiveNative(next);
 assert.equal(doc.activeElement,doc.querySelector('.tool-card > summary'));assert.ok(doc.querySelector('details.run').open);assert.ok(doc.querySelector('.thinking').open);let cards=doc.querySelectorAll('.tool-card');assert.ok(cards[0].open);assert.match(cards[0].textContent,/更新后的实际字段/);assert.equal(cards[1].open,false);assert.equal(cards[1].querySelector('.tool-body'),null);
 const away=structuredClone(next);away.sessions.push({...away.sessions[0],id:'b',title:'会话 B'});away.selected='b';window.receiveNative(away);assert.equal(doc.querySelector('details.run'),null);window.receiveNative(next);
 assert.ok(doc.querySelector('details.run').open);assert.ok(doc.querySelector('.tool-card').open);assert.match(doc.querySelector('.tool-body').textContent,/更新后的实际字段/);
 disclose(window,doc.querySelector('details.run'),false);assert.equal(doc.querySelector('.tool-card'),null);window.receiveNative({...next,messages:[{...next.messages[0],text:'再流入'}]});assert.equal(doc.querySelector('.tool-card'),null);
 openOperations(window,doc);assert.ok(doc.querySelector('.tool-card').open);assert.match(doc.querySelector('.tool-body').textContent,/更新后的实际字段/);
 const acknowledged=structuredClone(next);acknowledged.runs[0].expanded=true;window.receiveNative(acknowledged);assert.ok(doc.querySelector('details.run').open);
 const finished=structuredClone(acknowledged);finished.runs[0].status='completed';finished.runs[0].expanded=false;window.receiveNative(finished);assert.equal(doc.querySelector('details.run').open,false);assert.equal(doc.querySelector('.tool-card'),null);
});
test('collapsed operation history keeps nested unknown outcomes, failed tools and approval actions immediately visible',()=>{
 const{window,doc,calls}=setup();const s=treeHistory(2);s.runs[0].status='awaiting_user';s.runs[0].approval={id:'actual-approval',summary:'确认安装这个应用'};
 s.events[1].data.outcome='unknown';s.events[3].data.error='BACKEND_UNAVAILABLE';window.receiveNative(s);
 assert.equal(doc.querySelector('.tool-card,.structured'),null);const warnings=[...doc.querySelectorAll('.attention')];assert.ok(warnings.some(n=>n.textContent.includes('副作用未知')));assert.ok(warnings.some(n=>n.textContent.includes('执行失败')));assert.ok(warnings.every(n=>!n.closest('details')));
 assert.match(doc.querySelector('.wait').textContent,/确认安装这个应用/);doc.querySelector('.wait button').click();assert.deepEqual(calls.at(-1),{op:'approve',runId:'large',approvalId:'actual-approval'});
 openOperations(window,doc);disclose(window,doc.querySelectorAll('.tool-card')[1]);assert.match(doc.querySelectorAll('.tool-card')[1].textContent,/BACKEND_UNAVAILABLE/);
});

test('common input operations and streaming tool aliases have readable labels; child toggle never changes its parent disclosure',()=>{
 const{window,doc,calls}=setup();const s=state();s.runs=[{id:'r',sessionId:'a',status:'executing_tools',expanded:true}];s.messages=[{id:'m',sessionId:'a',runId:'r',role:'assistant',text:''}];
 const names=[['input.tap','点击当前屏幕'],['input.long_press','长按当前屏幕'],['input.swipe','滑动当前屏幕'],['input.press_button','按手柄键'],['input.set_axes','移动摇杆与扳机'],['input.release_inputs','释放输入'],['perception.find_apps','查找应用'],['apps.launch','打开应用'],['input.activate','激活界面控件'],['input.navigate','当前屏幕返回'],['perception.capture_companion','查看同应用下屏'],['perception.select_screen','选择操作屏幕']];
 s.events=names.map(([toolId],i)=>({runId:'r',sequence:i+1,type:'tool_started',toolCallId:'c'+i,data:{toolId,arguments:{target:'可读参数'}}}));
 s.events.push({runId:'r',sequence:names.length+1,type:'tool_call_update',toolCallId:'pending',data:{toolName:'input_tap',arguments:{x:30,y:40}}});window.receiveNative(s);
 const cards=doc.querySelectorAll('.tool-card');assert.deepEqual([...cards].slice(0,names.length).map(c=>c.querySelector('summary').textContent),names.map(([,label])=>label+' · 执行中'));assert.equal(doc.querySelector('.activity-label.shimmer').textContent,'点击当前屏幕');
 // Accessibility/keyboard/programmatic state changes use toggle too, without
 // depending on a mouse click. A bubbling child toggle must not notify Native
 // that the outer operation history was closed.
 cards[0].open=true;cards[0].dispatchEvent(new window.Event('toggle',{bubbles:true}));assert.ok(cards[0].querySelector('.structured'));
 cards[0].open=false;cards[0].dispatchEvent(new window.Event('toggle',{bubbles:true}));assert.equal(cards[0].querySelector('.tool-body'),null);assert.ok(doc.querySelector('details.run').open);assert.equal(calls.filter(c=>c.op==='expanded').length,0);
 window.receiveNative({...s,messages:[{...s.messages[0],text:'流式更新'}]});assert.equal(doc.querySelector('.tool-card').open,false);assert.equal(doc.querySelector('.tool-body'),null);
});

test('unfinished legacy brightness histories never guess units while a current running input uses integer percent',()=>{
 const{window,doc}=setup();const s=state();s.messages=[{id:'m',sessionId:'a',runId:'r',role:'assistant',text:''}];s.runs=[{id:'r',sessionId:'a',status:'interrupted',expanded:true}];
 s.events=[42,124].map((value,i)=>({runId:'r',sequence:i+1,type:'tool_started',toolCallId:'old-'+i,data:{toolId:'debug.thor_brightness',arguments:{action:'set',screen:'upper',value}}}));
 for(const status of ['interrupted','failed','cancelled','completed']){
  s.runs[0].status=status;window.receiveNative(s);openOperations(window,doc);for(const card of doc.querySelectorAll('.tool-card'))disclose(window,card);
  const cards=doc.querySelectorAll('.tool-card');assert.doesNotMatch(cards[0].textContent,/%/);assert.doesNotMatch(cards[1].textContent,/%/);
  const values=[...cards].map(card=>[...card.querySelectorAll('dt')].find(dt=>dt.textContent==='value').nextElementSibling.textContent);assert.deepEqual(values,['42','124']);
 }
 const running=structuredClone(s);running.runs[0].status='executing_tools';running.events=[{runId:'r',sequence:1,type:'tool_started',toolCallId:'current',data:{toolId:'debug.thor_brightness',arguments:{action:'set',screen:'lower',value:42}}}];window.receiveNative(running);openOperations(window,doc);disclose(window,doc.querySelector('.tool-card'));
 const value=[...doc.querySelectorAll('.tool-card dt')].find(dt=>dt.textContent==='value');assert.equal(value.nextElementSibling.textContent,'42%');
});

test('normal pending input never flashes unknown-action warnings; explicit uncertain results and abnormal unfinished operations remain visible',()=>{
 const{window,doc}=setup();const s=state();s.messages=[{id:'m',runId:'r',sessionId:'a',role:'assistant',text:''}];s.runs=[{id:'r',sessionId:'a',status:'executing_tools',stopReason:'未校验动作',expanded:false}];
 s.events=[{runId:'r',sequence:1,type:'tool_intent',toolCallId:'c',outcome:'unknown',data:{toolId:'input.tap'}},{runId:'r',sequence:2,type:'tool_started',toolCallId:'c',outcome:'unknown',data:{toolId:'input.tap'}}];window.receiveNative(s);
 assert.equal(doc.querySelector('.attention'),null);assert.ok(doc.querySelector('.activity-label.shimmer'));
 const done=structuredClone(s);done.runs[0].status='completed';done.events.push({runId:'r',sequence:3,type:'tool_result',toolCallId:'c',data:{toolId:'input.tap',outcome:'submitted'}});window.receiveNative(done);assert.equal(doc.querySelector('.attention'),null);
 const uncertain=structuredClone(s);uncertain.runs[0].stopReason='';uncertain.events.push({runId:'r',sequence:3,type:'tool_result',toolCallId:'c',data:{toolId:'input.tap',outcome:'unknown'}});window.receiveNative(uncertain);assert.match(doc.querySelector('.attention').textContent,/副作用未知/);
 s.runs[0].status='interrupted';s.runs[0].stopReason='';window.receiveNative(s);assert.ok([...doc.querySelectorAll('.attention')].some(n=>n.textContent.includes('检查目标状态')));
});
test('capability toggles acknowledge exact mutation IDs, resist stale snapshots, isolate sessions and roll back actual failures',()=>{
 const{window,doc,calls}=setup();const s=state();s.sessions[0].capabilityRevision=10;s.sessions.push({...s.sessions[0],id:'b',title:'会话 B',capabilities:{...s.sessions[0].capabilities}});window.receiveNative(s);
 const check=()=>doc.querySelector('input[aria-label="屏幕感知"]');
 check().checked=false;check().dispatchEvent(new window.Event('change'));const first=calls.at(-1);assert.equal(check().checked,false);assert.equal(check().getAttribute('aria-busy'),'true');assert.match(doc.querySelector('#cap-panel').textContent,/正在保存/);assert.match(doc.querySelector('#capabilities').textContent,/能力 1/);
 window.receiveNative(structuredClone(s));assert.equal(check().checked,false);
 check().checked=true;check().dispatchEvent(new window.Event('change'));const second=calls.at(-1);assert.notEqual(first.clientMutationId,second.clientMutationId);
 const oldAck=structuredClone(s);oldAck.sessions[0].capabilityRevision=11;oldAck.sessions[0].capabilities.perception=false;oldAck.capabilityAcks=[{sessionId:'a',capability:'perception',clientMutationId:first.clientMutationId,enabled:false,ok:true,capabilityRevision:11}];window.receiveNative(oldAck);assert.equal(check().checked,true);assert.equal(check().getAttribute('aria-busy'),'true');
 const away=structuredClone(oldAck);away.selected='b';window.receiveNative(away);assert.equal(check().checked,true);assert.equal(check().hasAttribute('aria-busy'),false);window.receiveNative(oldAck);assert.equal(check().checked,true);
 const failed=structuredClone(oldAck);failed.capabilityAcks=[{sessionId:'a',capability:'perception',clientMutationId:second.clientMutationId,enabled:false,ok:false,error:'保存失败，已恢复',capabilityRevision:11}];window.receiveNative(failed);assert.equal(check().checked,false);assert.equal(check().hasAttribute('aria-busy'),false);assert.match(doc.querySelector('.cap-error').textContent,/保存失败/);
 window.receiveNative(s);assert.equal(check().checked,false);
 check().checked=true;check().dispatchEvent(new window.Event('change'));const third=calls.at(-1);const success=structuredClone(s);success.sessions[0].capabilityRevision=12;success.capabilityAcks=[{sessionId:'a',capability:'perception',clientMutationId:third.clientMutationId,enabled:true,ok:true,capabilityRevision:12}];window.receiveNative(success);assert.equal(check().checked,true);assert.equal(doc.querySelector('.cap-error'),null);assert.equal(check().hasAttribute('aria-busy'),false);
 window.receiveNative(failed);assert.equal(check().checked,true);assert.equal(doc.querySelector('.cap-error'),null);
});
test('model and effort use keyboard-accessible in-page listboxes without native selects or system popup',()=>{
 const{window,doc,calls}=setup();const s=state();s.modelChoices=[{id:'one',name:'模型一'},{id:'two',name:'模型二'}];s.modelSelection={modelId:'one',label:'模型一',efforts:['low','high','max'],thinkingEffort:'low',contextWindow:1000000};window.receiveNative(s);
 assert.equal(doc.querySelector('#model-panel select'),null);doc.querySelector('#model-picker').click();assert.equal(doc.querySelector('#model-panel').hidden,false);assert.equal(doc.querySelector('#model-picker').getAttribute('aria-expanded'),'true');const models=doc.querySelector('#model-select');assert.equal(models.getAttribute('role'),'listbox');assert.equal(doc.activeElement,models);
 models.dispatchEvent(new window.KeyboardEvent('keydown',{key:'ArrowDown',bubbles:true,cancelable:true}));models.dispatchEvent(new window.KeyboardEvent('keydown',{key:'Enter',bubbles:true,cancelable:true}));assert.deepEqual(calls.at(-1),{op:'sessionOptions',sessionId:'a',modelId:'two'});assert.equal(doc.querySelector('#model-select [data-value=two]').getAttribute('aria-selected'),'true');
 const efforts=doc.querySelector('#effort-select');efforts.focus();efforts.dispatchEvent(new window.KeyboardEvent('keydown',{key:'End',bubbles:true,cancelable:true}));efforts.dispatchEvent(new window.KeyboardEvent('keydown',{key:' ',bubbles:true,cancelable:true}));assert.deepEqual(calls.at(-1),{op:'sessionOptions',sessionId:'a',thinkingEffort:'max'});
 efforts.dispatchEvent(new window.KeyboardEvent('keydown',{key:'Escape',bubbles:true,cancelable:true}));assert.equal(doc.querySelector('#model-panel').hidden,true);assert.equal(doc.activeElement,doc.querySelector('#model-picker'));assert.equal(doc.querySelector('#model-picker').getAttribute('aria-expanded'),'false');
});
function animationFixture(reduce=false){
 const animations=[],active=new WeakMap();const fixture=setup(window=>{
  window.matchMedia=()=>({matches:reduce,addEventListener(){}});
  window.HTMLElement.prototype.getBoundingClientRect=function(){return{height:active.has(this)?65:this.tagName==='DETAILS'?(this.open?140:30):20,width:400,top:0,left:0,right:400,bottom:140};};
  window.HTMLElement.prototype.animate=function(frames,options){const node=this;const animation={frames,options,cancelled:false,onfinish:null,cancel(){this.cancelled=true;active.delete(node);}};active.set(node,animation);animations.push(animation);return animation;};
 });return{...fixture,animations};
}
test('disclosure animation supports rapid reversal, retains content through close, resumes after streaming and releases lazy DOM only when settled',()=>{
 const{window,doc,animations}=animationFixture();const s=treeHistory(1);window.receiveNative(s);
 doc.querySelector('details.run > summary').click();assert.equal(doc.querySelector('details.run').dataset.disclosureAnimating,'true');animations.at(-1).onfinish();assert.equal(doc.querySelector('.structured'),null);
 doc.querySelector('.tool-card > summary').click();assert.ok(doc.querySelector('.tool-body'));animations.at(-1).onfinish();
 doc.querySelector('.tool-card > summary').click();const closing=animations.at(-1);assert.equal(doc.querySelector('.tool-card').dataset.expanded,'false');assert.ok(doc.querySelector('.tool-body'));assert.ok(doc.querySelector('.tool-card').open);
 doc.querySelector('.tool-card > summary').click();assert.equal(closing.cancelled,true);assert.equal(doc.querySelector('.tool-card').dataset.expanded,'true');
 const next=structuredClone(s);next.messages[0].text='新回复';next.events.push({runId:'large',sequence:3,type:'text_delta',data:{text:'新回复'}});window.receiveNative(next);assert.ok(doc.querySelector('.tool-card').open);assert.equal(doc.querySelector('.tool-card').dataset.disclosureAnimating,'true');assert.equal(animations.at(-1).frames[0].height,'65px');animations.at(-1).onfinish();
 doc.querySelector('.tool-card > summary').click();animations.at(-1).onfinish();assert.equal(doc.querySelector('.tool-body'),null);assert.equal(doc.querySelector('.tool-card').open,false);
 doc.querySelector('details.run > summary').click();assert.ok(doc.querySelector('.tool-card'));animations.at(-1).onfinish();assert.equal(doc.querySelector('.tool-card'),null);
});
test('reduced-motion disclosure changes settle directly without animation or retaining collapsed content',()=>{
 const{window,doc,animations}=animationFixture(true);window.receiveNative(treeHistory(1));doc.querySelector('details.run > summary').click();doc.querySelector('.tool-card > summary').click();assert.ok(doc.querySelector('.structured'));doc.querySelector('.tool-card > summary').click();assert.equal(doc.querySelector('.tool-body'),null);doc.querySelector('details.run > summary').click();assert.equal(doc.querySelector('.tool-card'),null);assert.equal(animations.length,0);
});
test('topic text remains full length in DOM so available width, rather than a fixed character limit, controls ellipsis',()=>{
 const{window,doc}=setup();const s=state();const title='这是一条超过十个字符并且包含模型名称的完整会话标题';s.sessions[0].title=title;window.receiveNative(s);assert.equal(doc.querySelector('.session-select strong').textContent,title);assert.equal(doc.querySelector('.session-select').getAttribute('aria-label'),title);
});

test('bridge capability failures roll back only the matching pending mutation and remain isolated from other capabilities',()=>{
 const{window,doc,calls}=setup();const s=state();s.sessions[0].capabilityRevision=5;window.receiveNative(s);
 const change=(label,enabled)=>{const input=doc.querySelector(`input[aria-label="${label}"]`);input.checked=enabled;input.dispatchEvent(new window.Event('change'));return calls.at(-1);};
 const first=change('屏幕感知',false);change('屏幕感知',true);const latest=change('屏幕感知',false);const other=change('联网搜索',false);
 window.receiveCapabilityFailure(first);assert.equal(doc.querySelector('input[aria-label="屏幕感知"]').checked,false);assert.equal(doc.querySelector('input[aria-label="屏幕感知"]').getAttribute('aria-busy'),'true');
 window.receiveCapabilityFailure(other);assert.equal(doc.querySelector('input[aria-label="联网搜索"]').checked,true);assert.equal(doc.querySelector('input[aria-label="屏幕感知"]').getAttribute('aria-busy'),'true');
 window.receiveCapabilityFailure(latest);assert.equal(doc.querySelector('input[aria-label="屏幕感知"]').checked,true);assert.equal(doc.querySelector('input[aria-label="屏幕感知"]').hasAttribute('aria-busy'),false);assert.match(doc.querySelector('.cap-error').textContent,/上次确认/);
 // If Native eventually confirms that exact timed-out write, its newer revision
 // is authoritative and clears only the uncertainty attached to that mutation.
 const confirmed=structuredClone(s);confirmed.sessions[0].capabilityRevision=6;confirmed.sessions[0].capabilities.perception=false;confirmed.capabilityAcks=[{sessionId:'a',capability:'perception',clientMutationId:latest.clientMutationId,ok:true,enabled:false,capabilityRevision:6}];window.receiveNative(confirmed);assert.equal(doc.querySelector('input[aria-label="屏幕感知"]').checked,false);assert.equal(doc.querySelector('input[aria-label="屏幕感知"]').closest('.cap-row').querySelector('.cap-error'),null);
});
