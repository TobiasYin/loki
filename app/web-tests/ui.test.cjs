const {test}=require('node:test');const assert=require('node:assert/strict');const fs=require('node:fs');const {JSDOM}=require('jsdom');
function setup(beforeScript){const dom=new JSDOM(fs.readFileSync('src/main/assets/chat/index.html','utf8'),{runScripts:'outside-only',url:'https://appassets.androidplatform.net/assets/chat/index.html'});const calls=[];beforeScript?.(dom.window,dom.window.document);dom.window.ThorUI={postMessage:s=>calls.push(JSON.parse(s))};for(const name of ['vendor/marked.umd.js','vendor/purify.min.js','ui.js'])dom.window.eval(fs.readFileSync('src/main/assets/chat/'+name,'utf8'));dom.window.HTMLDialogElement.prototype.showModal=function(){this.open=true;};dom.window.HTMLDialogElement.prototype.close=function(value=''){this.returnValue=value;this.open=false;this.dispatchEvent(new dom.window.Event('close'));};return{window:dom.window,doc:dom.window.document,calls};}
function state(){return{selected:'a',sessions:[{id:'a',title:'会话 A',archivedAt:null,updatedAt:1,capabilities:{perception:true,search:true,screen_control:false,debug:false}}],messages:[],runs:[],events:[],availability:{perception:'模拟就绪',search:'真实服务未配置',screen_control:'待验证',debug:'高权限'}};}
function disclose(window,node,open=true){assert.ok(node,'Disclosure must exist');if(node.open!==open)node.querySelector(':scope > summary').click();node.dispatchEvent(new window.Event('toggle'));return node;}
function openOperations(window,doc){disclose(window,doc.querySelector('.execution-history'));return doc.querySelector('.activity-timeline');}
test('model output limit explains continuation while ordinary model counters stay invisible',()=>{
 const{window,doc}=setup();const s=state();s.messages=[{id:'m',sessionId:'a',runId:'r',role:'assistant',text:'',status:'complete'}];
 s.runs=[{id:'r',sessionId:'a',status:'completed',expanded:false}];s.events=[{runId:'r',sequence:1,type:'model_round',data:{modelRound:1,requestedMaxOutputTokens:128000,inputTokens:300,outputTokens:30,stopReason:'stop'}}];window.receiveNative(s);
 assert.equal(doc.querySelector('details.run').open,false);assert.equal(doc.querySelector('.attention'),null);assert.doesNotMatch(doc.querySelector('#chat').textContent,/model_round|128000|inputTokens/);
 s.runs[0].status='failed';s.events.push({runId:'r',sequence:2,type:'diagnostic',data:{code:'MODEL_OUTPUT_LIMIT',requestedMaxOutputTokens:2048,outputTokens:2048,reasoningTokens:2048}});window.receiveNative(s);
 openOperations(window,doc);assert.match(doc.querySelector('.timeline-note').textContent,/达到模型输出上限.*保留上下文.*当前会话继续/);assert.doesNotMatch(doc.querySelector('#chat').textContent,/MODEL_OUTPUT_LIMIT|reasoningTokens/);
});
test('unscoped historical diagnostics never become a catch-all footer or leave a warning on completed runs',()=>{
 const{window,doc}=setup();const s=state();s.runs=[{id:'r',sessionId:'a',status:'completed',stopReason:'TOOL_FAILED'}];s.messages=[{id:'m',sessionId:'a',runId:'r',role:'assistant',text:'已完成当前任务'}];
 s.events=['MODEL_OUTPUT_LIMIT','MODEL_STREAM_FAILED','IO_ERROR','PERMISSION_MISSING','UNRECOGNIZED_DIAGNOSTIC'].map((code,index)=>({runId:'r',sequence:index+1,type:'diagnostic',data:{code}}));
 for(const status of ['generating','completed']) {
  s.runs[0].status=status;window.receiveNative(s);assert.equal(doc.querySelector('.run-notice,.attention'),null);assert.doesNotMatch(doc.querySelector('#chat').textContent,/运行提示|MODEL_OUTPUT_LIMIT|MODEL_STREAM_FAILED|IO_ERROR|PERMISSION_MISSING|UNRECOGNIZED_DIAGNOSTIC|TOOL_FAILED/);
 }
});
test('a genuine model failure has one readable run explanation rather than duplicate warnings or raw codes',()=>{
 for(const code of ['MODEL_OUTPUT_LIMIT','MODEL_STREAM_FAILED']) {
  const{window,doc}=setup();const s=state();s.runs=[{id:'r',sessionId:'a',status:'failed',stopReason:'IO_ERROR'}];s.messages=[{id:'m',sessionId:'a',runId:'r',role:'assistant',text:''}];s.events=[{runId:'r',sequence:1,type:'diagnostic',data:{code}}];window.receiveNative(s);
  assert.equal(doc.querySelectorAll('.run-notice').length,1);assert.equal(doc.querySelector('.attention'),null);assert.match(doc.querySelector('.run-notice').textContent,/保留上下文.*当前会话/);assert.doesNotMatch(doc.querySelector('#chat').textContent,/运行提示|MODEL_OUTPUT_LIMIT|MODEL_STREAM_FAILED|IO_ERROR/);
 }
});
test('interruption and internal terminal reasons are readable and never exposed as protocol details',()=>{
 const{window,doc}=setup();const s=state();s.messages=[{id:'m',sessionId:'a',runId:'r',role:'assistant',text:''}];s.runs=[{id:'r',sessionId:'a',status:'failed',stopReason:'Runtime 未完成；不重放'}];
 for(const [status,stopReason] of [['failed','Runtime 未完成；不重放'],['failed','IO_ERROR'],['interrupted','进程恢复：不重放任何副作用'],['cancelled','CANCELLED']]) {
  Object.assign(s.runs[0],{status,stopReason});window.receiveNative(s);assert.equal(doc.querySelectorAll('.run-notice').length,1);assert.doesNotMatch(doc.querySelector('#chat').textContent,/Runtime|IO_ERROR|进程恢复|副作用|CANCELLED/);
 }
 Object.assign(s.runs[0],{status:'cancelled',stopReason:'系统授权或后端失效'});window.receiveNative(s);assert.match(doc.querySelector('.run-notice').textContent,/系统授权或设备连接已失效/);
});
test('gray approval binds actual run and approval ID; black denial stays structured',()=>{
 const{window,doc,calls}=setup();const s=state();s.messages=[{id:'m',sessionId:'a',runId:'r',role:'assistant',text:'',status:'streaming'}];
 s.runs=[{id:'r',sessionId:'a',status:'awaiting_user',approval:{id:'approval-1',summary:'灰名单 · 屏幕焦点锁 1'}}];window.receiveNative(s);
 doc.querySelector('.wait button').click();assert.deepEqual(calls.at(-1),{op:'approve',runId:'r',approvalId:'approval-1'});
 s.runs[0].status='completed';s.events=[{runId:'r',sequence:1,type:'tool_result',toolCallId:'c',data:{toolId:'debug.shell',error:'COMMAND_BLOCKED',outcome:'observed',content:[{type:'text',text:JSON.stringify({policyTier:'black',executed:false,reason:'禁止格式化'})}]}}];window.receiveNative(s);
 assert.equal(doc.querySelector('.wait'),null);assert.equal(doc.querySelector('.attention'),null);assert.equal(doc.querySelector('.tool-body'),null);openOperations(window,doc);disclose(window,doc.querySelector('.tool-card'));assert.match(doc.querySelector('.tool-card').textContent,/已禁止/);assert.match(doc.querySelector('.structured').textContent,/禁止格式化/);assert.doesNotMatch(doc.querySelector('.tool-card').textContent,/"executed"/);
});
test('untrusted text never creates HTML, scripts, remote links or images',()=>{const{window,doc}=setup();const s=state();s.messages.push({id:'m',sessionId:'a',role:'assistant',text:'<img src="https://evil.invalid" onerror="alert(1)"><script>bad()</script>',status:'completed'});window.receiveNative(s);assert.equal(doc.querySelector('#chat img'),null);assert.equal(doc.querySelector('#chat script'),null);assert.match(doc.querySelector('#chat').textContent,/<img/);});
test('run failure stays visible while uncertain tool details belong to their own collapsed operation',()=>{
 const{window,doc}=setup();const s=state();s.messages=[{id:'m',sessionId:'a',runId:'r',role:'assistant',text:'',status:'failed'}];s.runs=[{id:'r',sessionId:'a',status:'failed',stopReason:'任务中断'}];s.events=[{runId:'r',sequence:1,outcome:'unknown',type:'tool_result'}];window.receiveNative(s);
 assert.equal(doc.querySelector('.execution-history').open,false);assert.equal(doc.querySelectorAll('.attention').length,1);assert.equal(doc.querySelector('.attention').textContent,'任务中断');
 openOperations(window,doc);disclose(window,doc.querySelector('.tool-card'));assert.match(doc.querySelector('.tool-body .attention').textContent,/副作用未知/);
});
test('page-change recovery stays neutral and visible while operation details remain lazy',()=>{
 const{window,doc}=setup();const s=state();s.runs=[{id:'r',sessionId:'a',status:'executing_tools',expanded:false}];s.messages=[{id:'m',sessionId:'a',runId:'r',role:'assistant',text:''}];
 const recovery={kind:'observe_again',continueRun:true,retryPreviousAction:false,submission:'not_dispatched',sideEffectPossible:true};
 s.events=[{runId:'r',sequence:1,type:'tool_result',toolCallId:'old',data:{toolId:'input.tap',error:'TARGET_CHANGED',outcome:'unknown',recovery,content:[{type:'text',text:JSON.stringify({error:'TARGET_CHANGED',recovery})}]}}];window.receiveNative(s);
 assert.equal(doc.querySelector('.attention'),null);assert.equal(doc.querySelector('.tool-body'),null);assert.match(doc.querySelector('[role=status]').textContent,/页面已变化，正在重新观察/);assert.doesNotMatch(doc.querySelector('[role=status]').textContent,/提交状态尚未确认/);
 assert.match(doc.querySelector('.tool-card summary').textContent,/需要重新观察/);doc.querySelector('.tool-card summary').click();assert.equal(doc.querySelector('.attention'),null);assert.doesNotMatch(doc.querySelector('.tool-body').textContent,/observe_again|retryPreviousAction/);
});
test('neutral page recovery preserves unknown or submitted facts without claiming an action did not execute',()=>{
 for(const submission of ['unknown','submitted']){
  const{window,doc}=setup();const s=state();s.runs=[{id:'r',sessionId:'a',status:'executing_tools',expanded:false}];s.messages=[{id:'m',sessionId:'a',runId:'r',role:'assistant',text:''}];s.events=[{runId:'r',sequence:1,type:'tool_result',toolCallId:'old',data:{toolId:'input.tap',error:'TARGET_CHANGED',outcome:submission,recovery:{kind:'observe_again',submission,sideEffectPossible:true}}}];window.receiveNative(s);
  assert.equal(doc.querySelector('.attention'),null);const text=doc.querySelector('[role=status]').textContent;assert.match(text,submission==='unknown'?/提交状态尚未确认/:/先前操作已提交/);assert.doesNotMatch(text,/未执行|未提交/);
 }
});
function recoveryHistory(){
 const s=state();s.runs=[{id:'r',sessionId:'a',status:'executing_tools',expanded:false}];s.messages=[{id:'m',sessionId:'a',runId:'r',role:'assistant',text:''}];
 s.events=[1,2,3].map(sequence=>({runId:'r',sequence,type:'tool_result',toolCallId:'stale-'+sequence,data:{toolId:'input.tap',error:'TARGET_CHANGED',outcome:'unknown',recovery:{kind:'observe_again',submission:'not_dispatched',sideEffectPossible:true}}}));return s;
}
test('repeated target changes use one current recovery status and a validated new screen read removes it',()=>{
 const{window,doc}=setup();const s=recoveryHistory();window.receiveNative(s);
 assert.equal(doc.querySelectorAll('.recovery-status').length,1);assert.match(doc.querySelector('.recovery-status').textContent,/正在重新观察/);assert.equal(doc.querySelector('.tool-body'),null);
 s.events.push({runId:'r',sequence:4,type:'tool_result',toolCallId:'fresh',data:{toolId:'perception.capture_screen',outcome:'observed',content:[{type:'attachment',attachmentId:'new-real-frame'}]}});window.receiveNative(s);
 assert.equal(doc.querySelector('.recovery-status'),null);assert.doesNotMatch(doc.querySelector('#chat').textContent,/正在重新观察/);
 s.events.push({runId:'r',sequence:5,type:'tool_result',toolCallId:'next-stale',data:{toolId:'input.activate',error:'TARGET_CHANGED',outcome:'unknown',recovery:{kind:'observe_again',submission:'not_dispatched',sideEffectPossible:true}}});window.receiveNative(s);
 assert.equal(doc.querySelectorAll('.recovery-status').length,1);assert.match(doc.querySelector('.recovery-status').textContent,/正在重新观察/);
});
test('the actual result plus Pi diagnostic does not leave a stale reminder after A input and a validated screenshot',()=>{
 const{window,doc,calls}=setup();const s=recoveryHistory();
 s.events.push({runId:'r',sequence:4,type:'diagnostic',toolCallId:'stale-3',data:{code:'TARGET_CHANGED'}});window.receiveNative(s);
 assert.equal(doc.querySelectorAll('.recovery-status').length,1);
 assert.equal([...doc.querySelectorAll('.timeline-note')].filter(node=>/页面已变化/.test(node.textContent)).length,1);
 s.events.push({runId:'r',sequence:5,type:'tool_result',toolCallId:'controller-a',data:{toolId:'input.press_button',outcome:'submitted'}});window.receiveNative(s);
 assert.ok(doc.querySelector('.recovery-status'),'A key submission is not itself a validated observation');
 s.events.push({runId:'r',sequence:6,type:'tool_result',toolCallId:'fresh-screen',data:{toolId:'perception.capture_screen',outcome:'observed',error:null}});window.receiveNative(s);
 assert.equal(doc.querySelector('.recovery-status'),null);assert.doesNotMatch(doc.querySelector('#chat').textContent,/页面已变化|正在重新观察|需重新观察|需要重新观察/);
 openOperations(window,doc);const old=doc.querySelector('.tool-card');assert.match(old.querySelector('summary').textContent,/已重新观察/);disclose(window,old);
 assert.match(old.querySelector('.tool-body').textContent,/随后已重新观察/);assert.doesNotMatch(old.textContent,/后续需依据新观察|需要重新观察/);
 assert.equal(calls.some(call=>['send','stop','new'].includes(call.op)),false);
});
test('a late duplicate diagnostic cannot reactivate resolved recovery, while an unmatched new change still requires observation',()=>{
 const{window,doc}=setup();const s=recoveryHistory();
 s.events.push({runId:'r',sequence:4,type:'tool_result',toolCallId:'fresh',data:{toolId:'perception.ui_tree',outcome:'observed'}},
  {runId:'r',sequence:5,type:'diagnostic',toolCallId:'stale-3',data:{code:'TARGET_CHANGED'}});window.receiveNative(s);
 assert.equal(doc.querySelector('.recovery-status'),null);assert.doesNotMatch(doc.querySelector('#chat').textContent,/需重新观察|需要重新观察/);
 s.events.push({runId:'r',sequence:6,type:'diagnostic',toolCallId:'new-change',data:{code:'TARGET_CHANGED'}});window.receiveNative(s);
 assert.equal(doc.querySelectorAll('.recovery-status').length,1);
 s.events.push({runId:'r',sequence:7,type:'tool_result',toolCallId:'new-screen',data:{toolId:'perception.capture_screen',outcome:'observed'}});window.receiveNative(s);
 assert.equal(doc.querySelector('.recovery-status'),null);
});
test('diagnostic-only history clears on real observation and finished runs do not retain a current page-change banner',()=>{
 const{window,doc}=setup();const s=recoveryHistory();
 s.events=[{runId:'r',sequence:1,type:'diagnostic',data:{code:'TARGET_CHANGED'}}];window.receiveNative(s);assert.equal(doc.querySelectorAll('.recovery-status').length,1);
 s.events.push({runId:'r',sequence:2,type:'tool_result',toolCallId:'observed',data:{toolId:'perception.window_info',outcome:'observed'}});window.receiveNative(s);
 assert.equal(doc.querySelector('.recovery-status'),null);
 for(const status of ['completed','cancelled','failed','interrupted']) {
  const finished=recoveryHistory();finished.runs[0].status=status;finished.events.push({runId:'r',sequence:4,type:'diagnostic',toolCallId:'stale-3',data:{code:'TARGET_CHANGED'}});window.receiveNative(finished);
  assert.equal(doc.querySelector('.recovery-status'),null);assert.doesNotMatch(doc.querySelector('#chat').textContent,/正在重新观察|需重新观察|需要重新观察|需以新观察/);
  assert.equal(doc.querySelector('.execution-history').open,false);
 }
});
test('failed discarded companion and other-run reads do not clear recovery, and selecting another screen needs new observation',()=>{
 const{window,doc}=setup();const s=recoveryHistory();
 const results=[{toolId:'perception.ui_tree',outcome:'observed',error:'IO_ERROR'},{toolId:'perception.capture_screen',outcome:'observed',contentDiscarded:true},{toolId:'perception.capture_companion',outcome:'observed'},{toolId:'perception.device_info',outcome:'observed'},{toolId:'perception.app_info',outcome:'unknown'}];
 for(const data of results){s.events.push({runId:'r',sequence:s.events.length+1,type:'tool_result',toolCallId:'read-'+s.events.length,data});window.receiveNative(s);assert.match(doc.querySelector('.recovery-status').textContent,/正在重新观察/);}
 s.events.push({runId:'other-run',sequence:100,type:'tool_result',toolCallId:'other-fresh',data:{toolId:'perception.ui_tree',outcome:'observed'}});window.receiveNative(s);assert.match(doc.querySelector('.recovery-status').textContent,/正在重新观察/);
 s.events.push({runId:'r',sequence:10,type:'tool_result',toolCallId:'current-fresh',data:{toolId:'perception.app_info',outcome:'observed'}});window.receiveNative(s);assert.equal(doc.querySelector('.recovery-status'),null);
 s.events.push({runId:'r',sequence:11,type:'tool_result',toolCallId:'switch',data:{toolId:'perception.select_screen',outcome:'observed'}});window.receiveNative(s);assert.match(doc.querySelector('.recovery-status').textContent,/正在重新观察/);
 s.events.push({runId:'r',sequence:12,type:'tool_result',toolCallId:'new-screen',data:{toolId:'perception.window_info',outcome:'observed'}});window.receiveNative(s);assert.equal(doc.querySelector('.recovery-status'),null);
});
test('successful observation clears dynamic recovery but preserves historical unknown and submitted facts in lazy details',()=>{
 const{window,doc}=setup();const s=recoveryHistory();s.events[0].data.recovery.submission='unknown';s.events[1].data.recovery.submission='submitted';s.events[1].data.outcome='submitted';
 s.events.push({runId:'r',sequence:4,type:'tool_result',toolCallId:'fresh',data:{toolId:'perception.ui_tree',outcome:'observed'}});window.receiveNative(s);
 assert.equal(doc.querySelector('.recovery-status'),null);assert.equal(doc.querySelector('.attention,.tool-body'),null);
 openOperations(window,doc);const cards=doc.querySelectorAll('.tool-card');disclose(window,cards[0]);assert.match(cards[0].querySelector('.tool-body').textContent,/提交状态尚未确认/);disclose(window,cards[1]);assert.match(cards[1].querySelector('.tool-body').textContent,/先前操作已提交/);
 s.runs[0].status='completed';window.receiveNative(s);assert.doesNotMatch(doc.querySelector('#chat').textContent,/正在重新观察/);assert.equal(doc.querySelector('.recovery-status'),null);
});
test('real permission and safety errors remain distinct from neutral page recovery',()=>{
 for(const error of ['PERMISSION_MISSING','COMMAND_BLOCKED','SECURE_CONTENT']){
  const{window,doc}=setup();const s=state();s.runs=[{id:'r',sessionId:'a',status:'executing_tools',expanded:false}];s.messages=[{id:'m',sessionId:'a',runId:'r',role:'assistant',text:''}];s.events=[{runId:'r',sequence:1,type:'tool_result',toolCallId:'denied',data:{toolId:'input.tap',error,outcome:'observed'}}];window.receiveNative(s);assert.equal(doc.querySelector('.attention'),null);assert.match(doc.querySelector('.tool-card summary').textContent,/已禁止|执行失败/);disclose(window,doc.querySelector('.tool-card'));assert.doesNotMatch(doc.querySelector('.tool-body .attention').textContent,/重新观察/);
 }
});
test('background tasks keep running while composer stop targets only the selected session',()=>{const{window,doc,calls}=setup();const s=state();s.selected='b';s.sessions.push({...s.sessions[0],id:'b',title:'会话 B'});s.runs.push({id:'r',sessionId:'a',status:'executing_tools'});window.receiveNative(s);assert.equal(doc.querySelector('#active').hidden,true);assert.equal(doc.querySelector('#send').textContent,'发送');assert.ok(doc.querySelector('.session-running'));s.runs.push({id:'r2',sessionId:'b',status:'generating'});window.receiveNative(s);assert.equal(doc.querySelector('#send').textContent,'停止');doc.querySelector('#composer').dispatchEvent(new window.Event('submit',{cancelable:true}));assert.deepEqual(calls.at(-1),{op:'stop',runId:'r2'});});
test('history paging requests a bounded native page once and keeps waiting through ordinary stream snapshots',()=>{
 const{window,doc,calls}=setup();const s=state();window.receiveNative(s);assert.equal(doc.querySelector('#history-more'),null);
 Object.assign(s,{historyHasMore:true,historyRunCount:12,historyPageLimit:12});s.messages=[{id:'recent',sessionId:'a',role:'assistant',text:'最近消息'}];window.receiveNative(s);
 const more=doc.querySelector('#history-more');assert.equal(doc.querySelector('#chat').firstElementChild,more);assert.equal(more.textContent,'加载更早消息');assert.equal(more.disabled,false);
 doc.querySelector('#input').value='没有提交的草稿';doc.querySelector('#input').focus();more.click();more.click();assert.deepEqual(calls.filter(command=>command.op==='historyMore'),[{op:'historyMore',sessionId:'a'}]);assert.equal(more.disabled,true);assert.equal(more.getAttribute('aria-busy'),'true');assert.equal(more.textContent,'正在加载…');assert.equal(doc.activeElement,doc.querySelector('#input'));
 const streaming=structuredClone(s);streaming.messages[0].text+='继续流式输出';window.receiveNative(streaming);assert.equal(doc.querySelector('#history-more'),more);assert.equal(more.disabled,true);assert.equal(doc.querySelector('#input').value,'没有提交的草稿');
 const loaded=structuredClone(streaming);loaded.historyPageLimit=24;loaded.historyRunCount=24;loaded.messages.unshift({id:'older',sessionId:'a',role:'user',text:'较早消息'});window.receiveNative(loaded);assert.equal(more.disabled,false);assert.equal(more.textContent,'加载更早消息');assert.equal(doc.querySelector('#chat').firstElementChild,more);assert.deepEqual([...doc.querySelectorAll('.message .text')].map(node=>node.textContent),['较早消息']);
 loaded.historyHasMore=false;window.receiveNative(loaded);assert.equal(doc.querySelector('#history-more'),null);assert.equal(calls.some(command=>command.op==='send'||command.op==='stop'),false);
});
test('history paging preserves the visible message and its DOM when older rows are prepended',()=>{
 const{window,doc}=setup();const chat=doc.querySelector('#chat');Object.defineProperty(chat,'clientHeight',{get:()=>200});Object.defineProperty(chat,'scrollHeight',{get:()=>chat.querySelectorAll('.message').length*100+(doc.querySelector('#history-more')?40:0)});
 chat.getBoundingClientRect=()=>({top:0,bottom:200});const original=window.HTMLElement.prototype.getBoundingClientRect;window.HTMLElement.prototype.getBoundingClientRect=function(){if(this.classList.contains('message')){const rows=[...chat.querySelectorAll('.message')],top=rows.indexOf(this)*100+(doc.querySelector('#history-more')?40:0)-chat.scrollTop;return{top,bottom:top+100};}return original.call(this);};
 const s=state();Object.assign(s,{historyHasMore:true,historyRunCount:12,historyPageLimit:12});s.messages=[0,1,2].map(i=>({id:'recent-'+i,sessionId:'a',role:'user',text:'近期消息 '+i}));window.receiveNative(s);chat.scrollTop=90;const visible=doc.querySelectorAll('.message')[0],before=visible.getBoundingClientRect().top;doc.querySelector('#history-more').click();
 const loaded=structuredClone(s);loaded.historyRunCount=24;loaded.historyPageLimit=24;loaded.messages.unshift(...[0,1].map(i=>({id:'older-'+i,sessionId:'a',role:'user',text:'更早消息 '+i})));window.receiveNative(loaded);assert.equal(doc.querySelectorAll('.message')[2],visible);assert.equal(visible.getBoundingClientRect().top,before);assert.equal(chat.scrollTop,290);
 loaded.historyHasMore=false;window.receiveNative(loaded);assert.equal(visible.getBoundingClientRect().top,before);assert.equal(chat.scrollTop,250);
});
test('history waiting resets on session changes and errors without changing drafts or another session',()=>{
 const{window,doc,calls}=setup();const s=state();Object.assign(s,{historyHasMore:true,historyRunCount:12,historyPageLimit:12});s.sessions.push({...s.sessions[0],id:'b',title:'会话 B'});window.receiveNative(s);doc.querySelector('#input').value='A 草稿';doc.querySelector('#history-more').click();assert.equal(doc.querySelector('#history-more').disabled,true);
 const b=structuredClone(s);b.selected='b';window.receiveNative(b);assert.equal(doc.querySelector('#history-more').disabled,false);doc.querySelector('#input').value='B 草稿';doc.querySelector('#history-more').click();assert.deepEqual(calls.at(-1),{op:'historyMore',sessionId:'b'});
 window.receiveNative(s);assert.equal(doc.querySelector('#history-more').disabled,false);assert.equal(doc.querySelector('#input').value,'A 草稿');doc.querySelector('#history-more').click();const failed=structuredClone(s);failed.error='读取未完成，请重试';window.receiveNative(failed);assert.equal(doc.querySelector('#history-more').disabled,false);assert.match(doc.querySelector('#notice').textContent,/读取未完成/);
 const withoutPaging=structuredClone(s);delete withoutPaging.historyHasMore;window.receiveNative(withoutPaging);assert.equal(doc.querySelector('#history-more'),null);assert.equal(doc.querySelector('#input').value,'A 草稿');
});
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
test('silent recognition finishes quietly and immediately permits a new hold without sending',()=>{
 const{window,doc,calls}=setup();window.receiveNative(state());doc.querySelector('#voice-toggle').click();const hold=doc.querySelector('#voice-hold');
 pointer(window,hold,'pointerdown');const start=calls.at(-1);pointer(window,hold,'pointerup');
 window.receiveVoiceState({id:start.id,sessionId:'a',phase:'empty',text:'',submit:''});
 assert.equal(calls.filter(c=>c.op==='send').length,0);assert.equal(hold.textContent,'按住说话');assert.equal(hold.disabled,false);assert.equal(hold.getAttribute('aria-pressed'),'false');
 assert.equal(doc.querySelector('#voice-overlay').hidden,true);assert.equal(doc.querySelector('#voice-status').hidden,true);assert.equal(doc.querySelector('#voice-status').textContent,'');assert.equal(doc.querySelector('#voice-edit').hidden,true);
 pointer(window,hold,'pointerdown');assert.equal(calls.at(-1).op,'voice.start');assert.notEqual(calls.at(-1).id,start.id);
});
test('empty result discards old partial and ignores late callbacks even during the next hold',()=>{
 const{window,doc,calls}=setup();window.receiveNative(state());doc.querySelector('#voice-toggle').click();const hold=doc.querySelector('#voice-hold');
 pointer(window,hold,'pointerdown');const old=calls.at(-1);window.receiveVoiceState({id:old.id,sessionId:'a',phase:'recording',text:'旧的误识别草稿'});pointer(window,hold,'pointerup');
 window.receiveVoiceState({id:old.id,sessionId:'a',phase:'empty'});
 assert.equal(doc.querySelector('#voice-edit').hidden,true);assert.equal(doc.querySelector('#voice-transcript').textContent,'');
 for(const phase of ['recording','complete','error'])window.receiveVoiceState({id:old.id,sessionId:'a',phase,text:'迟到文字',submit:'迟到文字',error:'迟到错误'});
 assert.equal(doc.querySelector('#voice-status').hidden,true);assert.equal(hold.textContent,'按住说话');
 pointer(window,hold,'pointerdown');const fresh=calls.at(-1);
 window.receiveVoiceState({id:old.id,sessionId:'a',phase:'empty'});window.receiveVoiceState({id:old.id,sessionId:'a',phase:'complete',submit:'旧结果'});
 assert.equal(hold.getAttribute('aria-pressed'),'true');assert.equal(doc.querySelector('#voice-overlay').hidden,false);assert.equal(calls.filter(c=>c.op==='send').length,0);
 pointer(window,hold,'pointerup');window.receiveVoiceState({id:fresh.id,sessionId:'a',phase:'complete',submit:'新的有效语音'});
 assert.equal(calls.filter(c=>c.op==='send').length,1);assert.equal(calls.find(c=>c.op==='send').text,'新的有效语音');
});
test('silent recognition leaves an existing typed draft intact through native refresh and text mode return',()=>{
 const{window,doc,calls}=setup();const s=state();window.receiveNative(s);const input=doc.querySelector('#input');input.value='尚未发送的文字草稿';input.dispatchEvent(new window.Event('input',{bubbles:true}));
 doc.querySelector('#voice-toggle').click();const hold=doc.querySelector('#voice-hold');pointer(window,hold,'pointerdown');const start=calls.at(-1);window.receiveVoiceState({id:start.id,sessionId:'a',phase:'recording',text:'不应覆盖原草稿'});pointer(window,hold,'pointerup');
 window.receiveVoiceState({id:start.id,sessionId:'a',phase:'empty',text:'',submit:''});window.receiveNative(s);doc.querySelector('#voice-toggle').click();
 assert.equal(input.hidden,false);assert.equal(input.value,'尚未发送的文字草稿');assert.equal(doc.querySelector('#voice-status').hidden,true);assert.equal(doc.querySelector('#voice-edit').hidden,true);assert.equal(calls.filter(c=>c.op==='send').length,0);
});
test('genuine voice errors still show their message and offer explicit partial draft recovery',()=>{
 for(const error of ['语音连接失败（HTTP 401）','语音连接失败，请检查网络','语音响应格式无效，请重试','语音服务拒绝请求']){
  const{window,doc,calls}=setup();window.receiveNative(state());doc.querySelector('#voice-toggle').click();const hold=doc.querySelector('#voice-hold');pointer(window,hold,'pointerdown');const start=calls.at(-1);
  window.receiveVoiceState({id:start.id,sessionId:'a',phase:'recording',text:'待恢复的语音草稿'});pointer(window,hold,'pointerup');window.receiveVoiceState({id:start.id,sessionId:'a',phase:'error',text:'待恢复的语音草稿',error});
  assert.equal(doc.querySelector('#voice-status').hidden,false);assert.equal(doc.querySelector('#voice-status').textContent,error);assert.equal(doc.querySelector('#voice-edit').hidden,false);assert.equal(calls.filter(c=>c.op==='send').length,0);
  doc.querySelector('#voice-edit').click();assert.equal(doc.querySelector('#input').value,'待恢复的语音草稿');assert.equal(doc.querySelector('#input').hidden,false);assert.equal(calls.filter(c=>c.op==='send').length,0);
 }
});
test('stream events become one tool card and an aggregated thinking panel without raw event JSON',()=>{
 const{window,doc}=setup();const s=state();s.runs=[{id:'r',sessionId:'a',status:'executing_tools',expanded:true}];s.messages=[{id:'m',sessionId:'a',runId:'r',role:'assistant',text:'回答',status:'streaming'}];
 s.events=[{type:'thinking_delta',data:{text:'先看'},summary:'{"type":"thinking_delta"}'},{type:'thinking_delta',data:{text:'攻略'}},{type:'text_delta',data:{text:'回答'}},{type:'tool_call_update',toolCallId:'c',data:{toolName:'skills_read',arguments:{name:'game-guide'}}},{type:'tool_started',toolCallId:'c',data:{toolId:'skills.read',arguments:{name:'game-guide'}}},{type:'tool_result',toolCallId:'c',data:{toolId:'skills.read',outcome:'observed',content:[{type:'text',text:JSON.stringify({content:'安全文本 <script>bad()</script>',resources:['references/a.md']})}]}}].map((e,i)=>({...e,runId:'r',sequence:i+1,elapsedMs:10}));
 window.receiveNative(s);assert.equal(doc.querySelectorAll('.tool-card').length,1);assert.equal(doc.querySelector('.structured'),null);disclose(window,doc.querySelector('.tool-card'));assert.equal(doc.querySelector('.thinking-body'),null);disclose(window,doc.querySelector('.thinking'));assert.match(doc.querySelector('.thinking').textContent,/先看攻略/);assert.match(doc.querySelector('.tool-card').textContent,/读取 Skill · 已完成/);assert.ok(doc.querySelector('.structured dt'));assert.equal(doc.querySelector('#chat pre'),null);assert.equal(doc.querySelector('#chat script'),null);assert.doesNotMatch(doc.querySelector('#chat').textContent,/thinking_delta|text_delta|"toolId"/);
});
test('long continuous thinking remains lazy while expanded text is a bounded accurate recent tail across stream updates',()=>{
 const{window,doc}=setup();const s=state();s.runs=[{id:'r',sessionId:'a',status:'executing_tools',expanded:false}];s.messages=[{id:'m',sessionId:'a',runId:'r',role:'assistant',text:'仍在处理'}];
 const chunks=Array.from({length:100},(_,i)=>`第${i}段：`+'推理文本'.repeat(250));
 s.events=chunks.map((text,i)=>({runId:'r',sequence:i+1,type:'thinking_delta',summary:'{"type":"thinking_delta"}',data:{text}}));
 const original=JSON.stringify(s.events);window.receiveNative(s);
 assert.ok(doc.querySelector('.thinking'));assert.equal(doc.querySelector('.thinking-body'),null);assert.doesNotMatch(doc.querySelector('#chat').textContent,/第0段|thinking_delta|"type"/);
 disclose(window,doc.querySelector('.thinking'));let body=doc.querySelector('.thinking-body');assert.equal(body.querySelector('.text').textContent,chunks.join('').slice(-65536));assert.equal(body.querySelector('.text').textContent.length,65536);assert.match(body.querySelector('.muted').textContent,/仅显示最近的思考内容/);assert.equal(doc.querySelector('.attention'),null);assert.equal(JSON.stringify(s.events),original);
 const next=structuredClone(s);next.events.push({runId:'r',sequence:101,type:'thinking_delta',data:{text:'新的思考末尾'}});window.receiveNative(next);body=doc.querySelector('.thinking-body');assert.ok(doc.querySelector('.thinking').open);assert.equal(body.querySelector('.text').textContent,(chunks.join('')+'新的思考末尾').slice(-65536));
 disclose(window,doc.querySelector('.thinking'),false);assert.equal(doc.querySelector('.thinking-body'),null);assert.equal(doc.querySelector('.attention'),null);
});
test('Native tail projection flags show neutral accurate truncation notices even when current text length is short',()=>{
 const{window,doc}=setup();const s=state();s.runs=[{id:'r',sessionId:'a',status:'completed',expanded:false}];s.messages=[{id:'m',sessionId:'a',runId:'r',role:'assistant',text:'**最后一段回复**',textTruncated:true,textOmittedUtf16Units:90000,thinkingTruncated:true,thinkingOmittedUtf16Units:80000}];
 s.events=[{runId:'r',sequence:1,type:'thinking_delta',data:{text:'最后一段思考',textTruncated:true,omittedUtf16Units:80000}}];window.receiveNative(s);
 assert.equal(doc.querySelector('.thinking-body'),null);assert.match(doc.querySelector('.message-truncation.muted').textContent,/仅显示最近一段/);assert.equal(doc.querySelector('.markdown strong').textContent,'最后一段回复');assert.equal(doc.querySelector('.attention'),null);
 disclose(window,doc.querySelector('.thinking'));assert.match(doc.querySelector('.thinking-truncation.muted').textContent,/仅显示最近的思考内容/);assert.equal(doc.querySelector('.thinking-body .text').textContent,'最后一段思考');assert.doesNotMatch(doc.querySelector('#chat').textContent,/textTruncated|omittedUtf16Units|thinking_delta/);
 const restored=structuredClone(s);restored.messages[0].textTruncated=false;restored.messages[0].thinkingTruncated=false;restored.events[0].data.textTruncated=false;window.receiveNative(restored);assert.equal(doc.querySelector('.message-truncation'),null);assert.equal(doc.querySelector('.thinking-truncation'),null);assert.equal(doc.querySelector('.markdown strong').textContent,'最后一段回复');
 // A flag can change without text changing; it still updates the keyed message.
 restored.messages[0].textTruncated=true;restored.messages[0].thinkingTruncated=true;window.receiveNative(restored);assert.ok(doc.querySelector('.message-truncation'));assert.ok(doc.querySelector('.thinking-truncation'));
});
test('thinking tail clipping never starts with an orphaned UTF16 surrogate and renders markup as plain text',()=>{
 const{window,doc}=setup();const s=state();s.runs=[{id:'r',sessionId:'a',status:'generating'}];s.messages=[{id:'m',sessionId:'a',runId:'r',role:'assistant',text:''}];
 const tail='x'.repeat(65500)+'<script>bad()</script>'.padEnd(35,'x');assert.equal(tail.length,65535);
 s.events=[{runId:'r',sequence:1,type:'thinking_delta',data:{text:'😀'+tail}}];window.receiveNative(s);disclose(window,doc.querySelector('.thinking'));
 assert.equal(doc.querySelector('.thinking-body .text').textContent,tail);assert.equal(doc.querySelector('.thinking-body script'),null);assert.equal(doc.querySelector('.thinking-body .text').textContent.length,65535);assert.equal(doc.querySelector('.attention'),null);
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
 const{window,doc}=setup();const s=state();s.runs=[{id:'r',sessionId:'a',status:'completed',expanded:true}];s.messages=[{id:'m',sessionId:'a',runId:'r',role:'assistant',text:'完成'}];s.events=['perception.ui_tree','input.scroll','input.drag'].map((toolId,i)=>({runId:'r',sequence:i+1,type:'tool_started',toolCallId:String(i),data:{toolId,arguments:{}}}));window.receiveNative(s);assert.deepEqual([...doc.querySelectorAll('.tool-card summary')].map(n=>n.textContent),['读取组件树 · 未完成','滚动页面 · 未完成','拖动控件 · 未完成']);
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
 window.receiveNative(s);assert.ok(created<200,`Collapsed history created ${created} elements`);assert.ok(doc.querySelector('#chat').querySelectorAll('*').length<100);
 assert.equal(doc.querySelector('details.run').open,false);assert.equal(doc.querySelectorAll('.tool-card').length,20);assert.equal(doc.querySelector('.tool-body,.structured'),null);assert.ok(doc.querySelector('#chat .activity-label.shimmer'));
 const baseline=created;
 for(let i=0;i<8;i++){s.messages[0].text+='。';s.events.push({runId:'large',sequence:41+i,type:'text_delta',data:{text:'。'}});window.receiveNative(s);}
 assert.ok(created-baseline<200,`Collapsed streaming rebuilt ${created-baseline} elements`);assert.equal(doc.querySelector('.tool-body,.structured'),null);
 openOperations(window,doc);const cards=[...doc.querySelectorAll('.tool-card')];assert.equal(cards.length,20);assert.ok(cards.every(card=>card.children.length===1));assert.equal(doc.querySelector('.structured'),null);
 disclose(window,cards[7]);assert.match(cards[7].textContent,/tool-7-node-35/);assert.match(cards[7].textContent,/current-container-7/);assert.match(cards[7].textContent,/value-7-35-25/);
 assert.equal(doc.querySelectorAll('.tool-body').length,1);assert.ok(cards.filter(card=>card!==cards[7]).every(card=>card.children.length===1));
 disclose(window,cards[7],false);assert.equal(doc.querySelector('.tool-body,.structured'),null);assert.equal(doc.querySelectorAll('.tool-card').length,20);
});
test('expanded timeline operations stay current through stream updates, session switches and task completion',()=>{
 const{window,doc,calls}=setup();const s=treeHistory(2);s.events.unshift({runId:'large',sequence:0,type:'thinking_delta',data:{text:'先检查'}});window.receiveNative(s);
 disclose(window,doc.querySelector('.thinking'));disclose(window,doc.querySelectorAll('.tool-card')[0]);
 assert.equal(calls.some(c=>c.op==='expanded'),false);doc.querySelector('.tool-card > summary').focus();
 const next=structuredClone(s);next.messages[0].text='读取到新结果';next.events.push({runId:'large',sequence:5,type:'tool_result',toolCallId:'call-0',data:{toolId:'perception.ui_tree',outcome:'observed',content:[{type:'text',text:JSON.stringify({nodes:[{text:'更新后的实际字段',visibleToUser:true}]})}]}});window.receiveNative(next);
 assert.equal(doc.activeElement,doc.querySelector('.tool-card > summary'));assert.ok(doc.querySelector('.thinking').open);const cards=doc.querySelectorAll('.tool-card');assert.ok(cards[0].open);assert.match(cards[0].textContent,/更新后的实际字段/);assert.equal(cards[1].open,false);assert.equal(cards[1].querySelector('.tool-body'),null);
 const away=structuredClone(next);away.sessions.push({...away.sessions[0],id:'b',title:'会话 B'});away.selected='b';window.receiveNative(away);assert.equal(doc.querySelector('.tool-card'),null);window.receiveNative(next);
 assert.ok(doc.querySelector('.tool-card').open);assert.match(doc.querySelector('.tool-body').textContent,/更新后的实际字段/);
 const finished=structuredClone(next);finished.runs[0].status='completed';finished.runs[0].expanded=false;window.receiveNative(finished);assert.ok(doc.querySelector('.tool-card').open);assert.equal(doc.querySelectorAll('#chat .activity-label.shimmer').length,0,[...doc.querySelectorAll('#chat .activity-label.shimmer')].map(n=>n.outerHTML).join(''));
});
test('tool warnings remain local to lazy operation details while approvals stay immediately visible',()=>{
 const{window,doc,calls}=setup();const s=treeHistory(2);s.runs[0].status='awaiting_user';s.runs[0].approval={id:'actual-approval',summary:'确认安装这个应用'};
 s.events[1].data.outcome='unknown';s.events[3].data.error='BACKEND_UNAVAILABLE';window.receiveNative(s);
 assert.equal(doc.querySelector('.tool-body,.structured,.attention'),null);assert.match(doc.querySelectorAll('.tool-card summary')[0].textContent,/结果未知/);assert.match(doc.querySelectorAll('.tool-card summary')[1].textContent,/执行失败/);
 assert.match(doc.querySelector('.wait').textContent,/确认安装这个应用/);doc.querySelector('.wait button').click();assert.deepEqual(calls.at(-1),{op:'approve',runId:'large',approvalId:'actual-approval'});
 openOperations(window,doc);disclose(window,doc.querySelectorAll('.tool-card')[0]);assert.match(doc.querySelectorAll('.tool-card')[0].textContent,/副作用未知/);disclose(window,doc.querySelectorAll('.tool-card')[1]);assert.match(doc.querySelectorAll('.tool-card')[1].textContent,/BACKEND_UNAVAILABLE/);
});

test('Pi tool failures stay with the matching call and never trail a successful final answer or reloaded history',()=>{
 const{window,doc}=setup();const s=state();s.runs=[{id:'r',sessionId:'a',status:'generating'}];s.messages=[{id:'m',sessionId:'a',runId:'r',role:'assistant',text:''}];
 s.events=[{sequence:1,type:'tool_result',toolCallId:'bad',data:{toolId:'perception.capture_screen',error:'BACKEND_UNAVAILABLE',outcome:'observed'}},{sequence:2,type:'tool_result',toolCallId:'good',data:{toolId:'perception.capture_screen',outcome:'observed'}},{sequence:3,type:'thinking_delta',data:{text:'继续整理'}},{sequence:4,type:'diagnostic',toolCallId:'bad',data:{code:'TOOL_FAILED',toolName:'perception_capture_screen'}},{sequence:5,type:'thinking_delta',data:{text:'已确认结果'}}].map(e=>({...e,runId:'r'}));window.receiveNative(s);
 assert.equal(doc.querySelector('.attention,.tool-body'),null);assert.doesNotMatch(doc.querySelector('#chat').textContent,/运行提示|TOOL_FAILED/);assert.equal(doc.querySelectorAll('.thinking').length,1);assert.match(doc.querySelector('.thinking summary').textContent,/思考中/);assert.equal(doc.querySelectorAll('.tool-card').length,2);
 s.runs[0].status='completed';s.messages[0].text='已完成任务。';s.events.push({runId:'r',sequence:6,type:'text_delta',data:{text:s.messages[0].text}});
 for(const snapshot of [s,JSON.parse(JSON.stringify(s))]) {
  window.receiveNative(snapshot);assert.equal(doc.querySelector('.execution-history').open,false);assert.ok([...doc.querySelectorAll('.attention')].every(node=>node.closest('.tool-card')));assert.ok([...doc.querySelectorAll('.activity-item')].every(node=>node.hidden));assert.doesNotMatch(doc.querySelector('#chat').textContent,/运行提示|TOOL_FAILED/);assert.match(doc.querySelector('.markdown').textContent,/已完成任务/);
  openOperations(window,doc);const cards=doc.querySelectorAll('.tool-card');disclose(window,cards[0]);assert.match(cards[0].textContent,/执行失败.*BACKEND_UNAVAILABLE/s);assert.equal(cards[0].querySelectorAll('.attention').length,1);assert.doesNotMatch(cards[1].textContent,/执行失败|BACKEND_UNAVAILABLE/);assert.ok([...doc.querySelectorAll('.attention')].every(node=>node.closest('.tool-card')));disclose(window,doc.querySelector('.execution-history'),false);
 }
});

test('diagnostic-only tool failures remain inspectable without reviving a global footer',()=>{
 for(const toolCallId of ['missing-history-call',undefined]) {
  const{window,doc}=setup();const s=state();s.runs=[{id:'r',sessionId:'a',status:'completed'}];s.messages=[{id:'m',sessionId:'a',runId:'r',role:'assistant',text:'已完成'}];s.events=[{runId:'r',sequence:1,type:'diagnostic',toolCallId,data:{code:'TOOL_FAILED',toolName:'web_search'}}];window.receiveNative(s);
  assert.doesNotMatch(doc.querySelector('#chat').textContent,/运行提示|TOOL_FAILED/);assert.equal(doc.querySelector('.tool-body'),null);openOperations(window,doc);const card=doc.querySelector('.tool-card');assert.match(card.querySelector('summary').textContent,/搜索网页.*执行失败/);disclose(window,card);assert.match(card.querySelector('.tool-body').textContent,/工具执行失败/);
 }
});

test('tool diagnostics do not mask a separate model-level continuation message',()=>{
 const{window,doc}=setup();const s=state();s.runs=[{id:'r',sessionId:'a',status:'failed'}];s.messages=[{id:'m',sessionId:'a',runId:'r',role:'assistant',text:''}];s.events=[{runId:'r',sequence:1,type:'diagnostic',data:{code:'MODEL_OUTPUT_LIMIT'}},{runId:'r',sequence:2,type:'diagnostic',toolCallId:'c',data:{code:'TOOL_FAILED',toolName:'web_search'}}];window.receiveNative(s);
 assert.match(doc.querySelector('#chat').textContent,/达到模型输出上限.*保留上下文/);assert.doesNotMatch(doc.querySelector('#chat').textContent,/运行提示|TOOL_FAILED/);
});

test('common input operations and streaming tool aliases remain readable; toggling one row never changes Native expansion state',()=>{
 const{window,doc,calls}=setup();const s=state();s.runs=[{id:'r',sessionId:'a',status:'executing_tools',expanded:true}];s.messages=[{id:'m',sessionId:'a',runId:'r',role:'assistant',text:''}];
 const names=[['input.tap','点击当前屏幕'],['input.long_press','长按当前屏幕'],['input.swipe','滑动当前屏幕'],['input.press_button','按手柄键'],['input.set_axes','移动摇杆与扳机'],['input.release_inputs','释放输入'],['perception.find_apps','查找应用'],['apps.launch','打开应用'],['input.activate','激活界面控件'],['input.navigate','当前屏幕返回'],['input.type_text','输入文字'],['input.key_press','按键盘键'],['perception.capture_companion','查看同应用下屏'],['perception.select_screen','选择操作屏幕']];
 s.events=names.map(([toolId],i)=>({runId:'r',sequence:i+1,type:'tool_started',toolCallId:'c'+i,data:{toolId,arguments:{target:'可读参数'}}}));
 s.events.push({runId:'r',sequence:names.length+1,type:'tool_call_update',toolCallId:'pending',data:{toolName:'input_tap',arguments:{x:30,y:40}}});window.receiveNative(s);
 const cards=doc.querySelectorAll('.tool-card');assert.deepEqual([...cards].slice(0,names.length).map(c=>c.querySelector('summary').textContent),names.map(([,label])=>label+' · 待执行'));assert.equal(doc.querySelector('#chat .activity-label.shimmer').textContent,'点击当前屏幕 · 正在准备');
 // Accessibility/keyboard/programmatic state changes use toggle too. A row
 // toggle stays local and never changes Native run expansion settings.
 cards[0].open=true;cards[0].dispatchEvent(new window.Event('toggle',{bubbles:true}));assert.ok(cards[0].querySelector('.structured'));
 cards[0].open=false;cards[0].dispatchEvent(new window.Event('toggle',{bubbles:true}));assert.equal(cards[0].querySelector('.tool-body'),null);assert.equal(doc.querySelector('details.run').open,false);assert.equal(calls.filter(c=>c.op==='expanded').length,0);
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
 assert.equal(doc.querySelector('.attention'),null);assert.ok(doc.querySelector('#chat .activity-label.shimmer'));
 const done=structuredClone(s);done.runs[0].status='completed';done.events.push({runId:'r',sequence:3,type:'tool_result',toolCallId:'c',data:{toolId:'input.tap',outcome:'submitted'}});window.receiveNative(done);assert.equal(doc.querySelector('.attention'),null);
 const uncertain=structuredClone(s);uncertain.runs[0].stopReason='';uncertain.events.push({runId:'r',sequence:3,type:'tool_result',toolCallId:'c',data:{toolId:'input.tap',outcome:'unknown'}});window.receiveNative(uncertain);assert.equal(doc.querySelector('.attention'),null);disclose(window,doc.querySelector('.tool-card'));assert.match(doc.querySelector('.tool-body .attention').textContent,/副作用未知/);
 s.runs[0].status='interrupted';s.runs[0].stopReason='';window.receiveNative(s);openOperations(window,doc);disclose(window,doc.querySelector('.tool-card'));assert.match(doc.querySelector('.tool-body .attention').textContent,/检查目标状态/);
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
test('same model names keep distinct internal choices without displaying providers',()=>{
 const{window,doc,calls}=setup();const s=state();
 s.modelChoices=[{id:'deepseek-chat',name:'DeepSeek Chat',providerConfigId:'go-work',providerName:'工作 Go',provider:'opencode-go',choiceId:'go-work::deepseek-chat',efforts:['low','high'],thinkingEffort:'low',contextWindow:1000000},{id:'deepseek-chat',name:'DeepSeek Chat',providerConfigId:'official',providerName:'DeepSeek 官方',provider:'deepseek',choiceId:'official::deepseek-chat',efforts:['off'],thinkingEffort:'off',contextWindow:128000}];
 s.modelSelection={modelId:'deepseek-chat',providerConfigId:'go-work',providerName:'工作 Go',label:'DeepSeek Chat',efforts:['low','high'],thinkingEffort:'high',contextWindow:1000000};
 s.runs=[{id:'active-one',sessionId:'a',status:'generating'}];window.receiveNative(s);
 const options=[...doc.querySelectorAll('#model-select [role=option]')];assert.equal(options.length,2);assert.notEqual(options[0].dataset.value,options[1].dataset.value);assert.equal(options[0].textContent,'DeepSeek Chat');assert.equal(options[1].textContent,'DeepSeek Chat');assert.equal(options[0].getAttribute('aria-selected'),'true');
 options[1].click();assert.deepEqual(calls.at(-1),{op:'sessionOptions',sessionId:'a',providerConfigId:'official',modelId:'deepseek-chat'});assert.equal(options[1].getAttribute('aria-selected'),'true');assert.equal(options[0].getAttribute('aria-selected'),'false');assert.match(doc.querySelector('#model-picker').textContent,/DeepSeek Chat/);assert.doesNotMatch(doc.querySelector('#model-picker').textContent,/DeepSeek 官方|工作 Go/);assert.doesNotMatch(doc.querySelector('#model-picker').textContent,/高/);assert.equal(doc.querySelectorAll('#effort-select [role=option]').length,1);assert.match(doc.querySelector('#model-context').textContent,/128K/);
 const streaming=structuredClone(s);streaming.events=[{runId:'active-one',sequence:1,type:'model_round',data:{inputTokens:20}}];window.receiveNative(streaming);assert.match(doc.querySelector('#model-picker').textContent,/DeepSeek Chat/);assert.doesNotMatch(doc.querySelector('#model-picker').textContent,/DeepSeek 官方|工作 Go/);assert.equal(doc.querySelector('#send').textContent,'停止');assert.equal(calls.some(command=>command.op==='stop'||command.op==='new'),false);
 const confirmed=structuredClone(streaming);confirmed.modelSelection={...s.modelChoices[1],modelId:'deepseek-chat',label:'DeepSeek Chat'};window.receiveNative(confirmed);assert.equal([...doc.querySelectorAll('#model-select [role=option]')].find(option=>option.dataset.value==='official::deepseek-chat').getAttribute('aria-selected'),'true');assert.doesNotMatch(doc.querySelector('#chat').textContent,/model_round|inputTokens/);
});
test('provider-aware model choices and effort state remain scoped to the selected session',()=>{
 const{window,doc,calls}=setup();const s=state();s.sessions.push({...s.sessions[0],id:'b',title:'会话 B'});
 s.modelChoices=[{id:'shared',name:'共享模型',providerConfigId:'first',providerName:'配置一',efforts:['low','high']},{id:'shared',name:'共享模型',providerConfigId:'second',providerName:'配置二',efforts:['off']}];
 s.modelSelection={modelId:'shared',providerConfigId:'first',providerName:'配置一',label:'共享模型',efforts:['low','high'],thinkingEffort:'low'};window.receiveNative(s);
 const b=structuredClone(s);b.selected='b';b.modelSelection={modelId:'shared',providerConfigId:'second',providerName:'配置二',label:'共享模型',efforts:['off'],thinkingEffort:'off'};window.receiveNative(b);assert.match(doc.querySelector('#model-picker').textContent,/共享模型/);assert.doesNotMatch(doc.querySelector('#model-picker').textContent,/配置二/);assert.equal(doc.querySelectorAll('#effort-select [role=option]').length,1);
 doc.querySelector('#model-select [data-value="first::shared"]').click();assert.deepEqual(calls.at(-1),{op:'sessionOptions',sessionId:'b',providerConfigId:'first',modelId:'shared'});
 window.receiveNative(s);assert.match(doc.querySelector('#model-picker').textContent,/共享模型/);assert.doesNotMatch(doc.querySelector('#model-picker').textContent,/配置一/);assert.equal(doc.querySelectorAll('#effort-select [role=option]').length,2);doc.querySelector('#effort-select [data-value=high]').click();assert.deepEqual(calls.at(-1),{op:'sessionOptions',sessionId:'a',thinkingEffort:'high'});assert.match(doc.querySelector('#model-picker').textContent,/高/);
});
test('model provider names and configured choice identifiers are treated as inert text',()=>{
 const{window,doc,calls}=setup();const s=state();s.modelChoices=[{id:'shared',name:'<img src=x onerror=bad()>',providerConfigId:'first',providerName:'<script>bad()</script>',choiceId:'configured choice with spaces'}];s.modelSelection={modelId:'shared',providerConfigId:'first',providerName:'<script>bad()</script>',label:'<img src=x onerror=bad()>'};window.receiveNative(s);
 assert.equal(doc.querySelector('#model-panel script,#model-panel img,#model-picker script,#model-picker img'),null);const option=doc.querySelector('#model-select [role=option]');assert.equal(option.dataset.value,'configured choice with spaces');assert.equal(option.getAttribute('aria-selected'),'true');option.click();assert.deepEqual(calls.at(-1),{op:'sessionOptions',sessionId:'a',providerConfigId:'first',modelId:'shared'});
});
test('switching provider immediately uses its model default effort rather than carrying an incompatible prior choice',()=>{
 const{window,doc,calls}=setup();const s=state();s.modelChoices=[{id:'shared',name:'模型',providerConfigId:'first',providerName:'配置一',provider:'opencode-go',efforts:['low','high'],defaultEffort:'low'},{id:'shared',name:'模型',providerConfigId:'second',providerName:'配置二',provider:'deepseek',efforts:['off','low','high','max'],defaultEffort:'high'}];s.modelSelection={providerConfigId:'first',providerName:'配置一',modelId:'shared',label:'模型',efforts:['low','high'],thinkingEffort:'low'};window.receiveNative(s);
 doc.querySelector('#model-select [data-value="second::shared"]').click();assert.match(doc.querySelector('#model-picker').textContent,/模型 · 高/);assert.equal(doc.querySelector('#effort-select [data-value=high]').getAttribute('aria-selected'),'true');doc.querySelector('#effort-select [data-value=max]').click();assert.deepEqual(calls.at(-1),{op:'sessionOptions',sessionId:'a',thinkingEffort:'max'});
});
test('unconfigured startup remains usable and a disabled selected provider is identified without silently selecting another',()=>{
 const{window,doc,calls}=setup();const s=state();s.modelChoices=[];s.modelSelection={providerName:'未配置',provider:'custom',configured:false,modelId:'',label:'添加模型',efforts:['off'],thinkingEffort:'off',contextWindow:32768};window.receiveNative(s);assert.match(doc.querySelector('#model-picker').textContent,/添加模型/);assert.equal(doc.querySelector('#model-select').getAttribute('aria-disabled'),'true');assert.equal(doc.querySelector('#notice').hidden,true);assert.equal(doc.querySelector('#settings').disabled,false);
 s.modelChoices=[{id:'shared',name:'模型',providerConfigId:'enabled',providerName:'可用配置',provider:'deepseek',efforts:['low','high'],defaultEffort:'high'}];s.modelSelection={providerName:'停用配置',providerConfigId:'disabled',provider:'deepseek',configured:false,modelId:'shared',label:'模型',efforts:['low','high'],thinkingEffort:'low'};window.receiveNative(s);assert.match(doc.querySelector('#model-picker').textContent,/模型 · 不可用/);assert.equal(doc.querySelectorAll('#model-select [aria-selected=true]').length,0);assert.equal(calls.some(command=>command.op==='sessionOptions'),false);
 doc.querySelector('#model-select [role=option]').click();assert.deepEqual(calls.at(-1),{op:'sessionOptions',sessionId:'a',providerConfigId:'enabled',modelId:'shared'});assert.match(doc.querySelector('#model-picker').textContent,/模型/);assert.doesNotMatch(doc.querySelector('#model-picker').textContent,/可用配置|停用配置/);assert.doesNotMatch(doc.querySelector('#model-picker').textContent,/不可用/);
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
 assert.equal(doc.querySelector('.structured'),null);
 doc.querySelector('.tool-card > summary').click();assert.ok(doc.querySelector('.tool-body'));animations.at(-1).onfinish();
 doc.querySelector('.tool-card > summary').click();const closing=animations.at(-1);assert.equal(doc.querySelector('.tool-card').dataset.expanded,'false');assert.ok(doc.querySelector('.tool-body'));assert.ok(doc.querySelector('.tool-card').open);
 doc.querySelector('.tool-card > summary').click();assert.equal(closing.cancelled,true);assert.equal(doc.querySelector('.tool-card').dataset.expanded,'true');
 const next=structuredClone(s);next.messages[0].text='新回复';next.events.push({runId:'large',sequence:3,type:'text_delta',data:{text:'新回复'}});window.receiveNative(next);assert.ok(doc.querySelector('.tool-card').open);assert.equal(doc.querySelector('.tool-card').dataset.disclosureAnimating,'true');assert.equal(animations.at(-1).frames[0].height,'65px');animations.at(-1).onfinish();
 doc.querySelector('.tool-card > summary').click();animations.at(-1).onfinish();assert.equal(doc.querySelector('.tool-body'),null);assert.equal(doc.querySelector('.tool-card').open,false);
 assert.ok(doc.querySelector('.tool-card'));assert.equal(doc.querySelector('.tool-body'),null);
});
test('reduced-motion disclosure changes settle directly without animation or retaining collapsed content',()=>{
 const{window,doc,animations}=animationFixture(true);window.receiveNative(treeHistory(1));doc.querySelector('.tool-card > summary').click();assert.ok(doc.querySelector('.structured'));doc.querySelector('.tool-card > summary').click();assert.equal(doc.querySelector('.tool-body'),null);assert.ok(doc.querySelector('.tool-card'));assert.equal(animations.length,0);
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

test('thoughts, tool operations and Markdown responses follow actual event order with only the current phase shining',()=>{
 const{window,doc}=setup();const s=state();s.runs=[{id:'r',sessionId:'a',status:'generating'}];s.messages=[{id:'m',sessionId:'a',runId:'r',role:'assistant',text:'**正在继续**'}];
 s.events=[
  {type:'thinking_delta',data:{text:'先识别页面'}},
  {type:'tool_call_update',toolCallId:'capture',data:{toolName:'perception_capture_screen',arguments:{}}},
  {type:'tool_started',toolCallId:'capture',data:{toolId:'perception.capture_screen',arguments:{}}},
  {type:'tool_result',toolCallId:'capture',data:{toolId:'perception.capture_screen',outcome:'observed'}},
  {type:'thinking_delta',data:{text:'根据截图决定下一步'}},
  {type:'tool_started',toolCallId:'tap',data:{toolId:'input.tap',arguments:{x:10,y:20}}},
  {type:'tool_result',toolCallId:'tap',data:{toolId:'input.tap',outcome:'submitted'}},
  {type:'text_delta',data:{text:'**正在继续**'}},
  {type:'thinking_delta',data:{text:'检查新页面'}},
 ].map((event,index)=>({...event,runId:'r',sequence:index+1}));window.receiveNative(s);
 const items=[...doc.querySelector('.activity-timeline').children];assert.deepEqual(items.map(n=>n.dataset.activityKind),['thinking','tool','thinking','tool','text','thinking']);
 assert.deepEqual([...doc.querySelectorAll('.thinking summary')].map(n=>n.textContent),['已思考','已思考','思考中']);
 assert.equal(doc.querySelectorAll('#chat .shimmer').length,1);assert.equal(doc.querySelector('#chat .shimmer').closest('.thinking'),items[5]);
 assert.equal(doc.querySelectorAll('#chat .markdown strong').length,1);assert.equal(doc.querySelector('#chat .markdown strong').textContent,'正在继续');
 for(const thought of doc.querySelectorAll('.thinking'))disclose(window,thought);
 assert.deepEqual([...doc.querySelectorAll('.thinking-body .text')].map(n=>n.textContent),['先识别页面','根据截图决定下一步','检查新页面']);
 s.runs[0].status='completed';window.receiveNative(s);assert.equal(doc.querySelectorAll('#chat .shimmer').length,0);assert.deepEqual([...doc.querySelectorAll('.thinking summary')].map(n=>n.textContent),['已思考','已思考','已思考']);
});

test('a later Native tool argument update retains its original position before the following thought',()=>{
 const{window,doc}=setup();const s=state();s.runs=[{id:'r',sessionId:'a',status:'executing_tools'}];s.messages=[{id:'m',sessionId:'a',runId:'r',role:'assistant',text:''}];
 s.events=[{runId:'r',sequence:1,lastSequence:5,type:'tool_call_update',toolCallId:'call',data:{toolName:'input_tap',arguments:{x:99,y:20}}},{runId:'r',sequence:2,type:'thinking_delta',presentationSegment:'thought-after-call',data:{text:'工具参数已生成'}}];
 window.receiveNative(s);assert.deepEqual([...doc.querySelector('.activity-timeline').children].map(n=>n.dataset.activityKind),['tool','thinking']);
 assert.equal(doc.querySelector('#chat .shimmer').closest('details'),doc.querySelector('.tool-card'));assert.equal(doc.querySelector('.thinking summary').textContent,'已思考');
 disclose(window,doc.querySelector('.tool-card'));assert.match(doc.querySelector('.tool-body').textContent,/99/);
});

test('explicit Native segment markers preserve separate collapsed thought history after an intermediate call was folded',()=>{
 const{window,doc}=setup();const s=state();s.runs=[{id:'r',sessionId:'a',status:'generating'}];s.messages=[{id:'m',sessionId:'a',runId:'r',role:'assistant',text:''}];
 s.events=[{runId:'r',sequence:1,type:'thinking_delta',presentationSegment:'first',data:{text:'第一段'}},{runId:'r',sequence:3,type:'thinking_delta',presentationSegment:'second',data:{text:'第二段'}}];window.receiveNative(s);
 assert.equal(doc.querySelectorAll('.thinking').length,2);assert.equal(doc.querySelector('.thinking-body'),null);assert.equal(doc.querySelectorAll('#chat .shimmer').length,1);
 const old=doc.querySelector('.thinking');disclose(window,old);assert.equal(old.querySelector('.text').textContent,'第一段');
 s.events[1].data.text+='更新';window.receiveNative(s);assert.equal(doc.querySelector('.thinking'),old);assert.ok(old.open);assert.equal(old.querySelector('.text').textContent,'第一段');
});

test('completed tool summaries remain visible and reused while a later thought streams, without eagerly building result trees',()=>{
 const{window,doc}=setup();const s=treeHistory(20);s.runs[0].status='generating';s.events.push({runId:'large',sequence:41,type:'thinking_delta',data:{text:'继续思考'}});window.receiveNative(s);
 const old=[...doc.querySelectorAll('.tool-card')];assert.equal(old.length,20);assert.ok(old.every(n=>n.dataset.activityState==='completed'));assert.equal(doc.querySelector('.structured'),null);
 for(let i=0;i<5;i++){s.events.at(-1).data.text+='继续';window.receiveNative(s);assert.ok([...doc.querySelectorAll('.tool-card')].every((node,index)=>node===old[index]));}
 assert.equal(doc.querySelector('.structured'),null);assert.equal(doc.querySelectorAll('#chat .shimmer').length,1);assert.ok(doc.querySelector('#chat .shimmer').closest('.thinking'));
});

test('a partially paged event window keeps the complete saved assistant answer rather than rendering only the last delta',()=>{
 const{window,doc}=setup();const s=state();s.runs=[{id:'r',sessionId:'a',status:'completed'}];s.messages=[{id:'m',sessionId:'a',runId:'r',role:'assistant',text:'**完整开头**和最后一段'}];s.events=[{runId:'r',sequence:50,type:'text_delta',data:{text:'最后一段'}}];window.receiveNative(s);
 assert.equal(doc.querySelector('.timeline-answer'),null);assert.equal(doc.querySelector('.markdown').textContent,'完整开头和最后一段\n');assert.equal(doc.querySelectorAll('.markdown').length,1);
 const restored=structuredClone(s);restored.events.unshift({runId:'r',sequence:49,type:'text_delta',data:{text:'**完整开头**和'}});window.receiveNative(restored);assert.ok(doc.querySelector('.timeline-answer'));assert.equal(doc.querySelectorAll('.markdown').length,1);
});

test('Pi context compaction stays in chronological activity flow, shines while active, then becomes a successful expandable history row',()=>{
 const{window,doc}=setup();const s=state();s.runs=[{id:'r',sessionId:'a',status:'generating'}];s.messages=[{id:'m',sessionId:'a',runId:'r',role:'assistant',text:''}];
 s.events=[{runId:'r',sequence:1,type:'thinking_delta',data:{text:'此前思考'}},{runId:'r',sequence:2,type:'diagnostic',data:{code:'CONTEXT_COMPACTION_STARTED',reason:'threshold'}}];window.receiveNative(s);
 const row=doc.querySelector('.compaction');assert.ok(row);assert.deepEqual([...doc.querySelector('.activity-timeline').children].map(n=>n.dataset.activityKind),['thinking','compaction']);assert.equal(row.querySelector('summary').textContent,'正在整理较早的对话…');assert.equal(doc.querySelectorAll('#chat .shimmer').length,1);assert.equal(doc.querySelector('#chat .shimmer').closest('details'),row);assert.equal(doc.querySelector('.attention'),null);assert.doesNotMatch(doc.querySelector('#chat').textContent,/CONTEXT_COMPACTION|threshold|运行提示/);
 disclose(window,row);assert.match(row.textContent,/整理为摘要/);s.events.push({runId:'r',sequence:3,type:'diagnostic',data:{code:'CONTEXT_COMPACTION_FINISHED',reason:'threshold',compacted:true,aborted:false}},{runId:'r',sequence:4,type:'thinking_delta',data:{text:'继续当前任务'}});window.receiveNative(s);
 assert.equal(doc.querySelectorAll('.compaction').length,1);const completed=doc.querySelector('.compaction');assert.equal(completed.querySelector('summary').textContent,'已整理较早的对话');assert.ok(completed.open);assert.equal(completed.dataset.activityState,'completed');assert.deepEqual([...doc.querySelector('.activity-timeline').children].map(n=>n.dataset.activityKind),['thinking','compaction','thinking']);assert.equal(doc.querySelectorAll('#chat .shimmer').length,1);assert.equal(doc.querySelector('#chat .shimmer').closest('details'),doc.querySelectorAll('.thinking:not(.compaction)')[1]);assert.doesNotMatch(doc.querySelector('#chat').textContent,/CONTEXT_COMPACTION|运行提示/);
});

test('failed or cancelled Pi compaction remains neutral, preserves context explanation and never claims successful compaction',()=>{
 for(const outcome of [{compacted:false,aborted:false},{compacted:false,aborted:true},{compacted:true,aborted:true}]){
  const{window,doc}=setup();const s=state();s.runs=[{id:'r',sessionId:'a',status:'cancelled'}];s.messages=[{id:'m',sessionId:'a',runId:'r',role:'assistant',text:''}];s.events=[{runId:'r',sequence:1,type:'diagnostic',data:{code:'CONTEXT_COMPACTION_STARTED',reason:'overflow'}},{runId:'r',sequence:2,type:'diagnostic',data:{code:'CONTEXT_COMPACTION_FINISHED',reason:'overflow',...outcome}}];window.receiveNative(s);
  const row=doc.querySelector('.compaction');assert.equal(row.querySelector('summary').textContent,'对话整理未完成');assert.equal(row.dataset.activityState,'incomplete');assert.equal(doc.querySelectorAll('#chat .shimmer').length,0);assert.equal(doc.querySelector('.attention'),null);disclose(window,row);assert.match(row.textContent,/原有对话仍然保留/);assert.doesNotMatch(doc.querySelector('#chat').textContent,/已整理较早|CONTEXT_COMPACTION|compacted|aborted|overflow|运行提示/);
 }
});

function visibleOperations(doc){return [...doc.querySelectorAll('.activity-item,.activity-pending')].filter(node=>!node.hidden);}
test('compact running process shows only its two newest activities and advances with the live thought',()=>{
 const{window,doc}=setup();const s=treeHistory(20);s.runs[0].status='generating';
 s.events.push({runId:'large',sequence:41,type:'thinking_delta',data:{text:'继续思考'}});window.receiveNative(s);
 assert.equal(doc.querySelector('.execution-history').open,false);
 assert.equal(doc.querySelector('.execution-history > summary').textContent,'正在运行 · 已完成 20 项操作');
 assert.deepEqual(visibleOperations(doc).map(node=>node.dataset.activityKind),['tool','thinking']);
 assert.match(visibleOperations(doc)[0].dataset.activityKey,/call-19$/);assert.equal(doc.querySelector('.structured,.thinking-body'),null);
 const visible=visibleOperations(doc);s.events.at(-1).data.text+='，正在核对';window.receiveNative(s);
 assert.equal(visibleOperations(doc).length,2);assert.equal(visibleOperations(doc)[0],visible[0]);
 openOperations(window,doc);assert.equal(visibleOperations(doc).length,21);
 assert.equal(doc.querySelector('.structured,.thinking-body'),null);
 disclose(window,doc.querySelector('.execution-history'),false);assert.equal(visibleOperations(doc).length,2);
});
test('running operation totals count successful calls once and exclude thoughts, pending, rejected and uncertain results',()=>{
 const{window,doc}=setup();const s=treeHistory(2);window.receiveNative(s);
 assert.equal(doc.querySelector('.execution-history > summary').textContent,'正在运行 · 已完成 2 项操作');
 s.events.push({runId:'large',sequence:5,type:'thinking_delta',data:{text:'继续核对'}},
  {runId:'large',sequence:6,type:'tool_started',toolCallId:'pending',data:{toolId:'input.tap'}},
  {runId:'large',sequence:7,type:'tool_result',toolCallId:'blocked',data:{toolId:'debug.shell',error:'COMMAND_BLOCKED',outcome:'observed'}},
  {runId:'large',sequence:8,type:'tool_result',toolCallId:'unknown',data:{toolId:'input.tap',outcome:'unknown'}},
  {runId:'large',sequence:9,type:'tool_result',toolCallId:'call-0',data:{toolId:'perception.ui_tree',outcome:'observed'}});window.receiveNative(s);
 assert.equal(doc.querySelector('.execution-history > summary').textContent,'正在运行 · 已完成 2 项操作');
 s.events.push({runId:'large',sequence:10,type:'tool_result',toolCallId:'pending',data:{toolId:'input.tap',error:null,outcome:'submitted'}});window.receiveNative(s);
 assert.equal(doc.querySelector('.execution-history > summary').textContent,'正在运行 · 已完成 3 项操作');
 const next=structuredClone(s);next.runs=[{id:'new',sessionId:'a',status:'generating'}];next.messages=[{id:'new-message',runId:'new',sessionId:'a',role:'assistant',text:''}];next.events=[];window.receiveNative(next);
 assert.equal(doc.querySelector('.execution-history > summary').textContent,'正在运行 · 已完成 0 项操作');
});
test('pending phase counts toward the two compact rows, and updated old calls keep their chronological position',()=>{
 const{window,doc}=setup();const s=treeHistory(4);window.receiveNative(s);
 assert.equal(visibleOperations(doc).length,2);assert.ok(visibleOperations(doc)[1].classList.contains('activity-pending'));
 s.events.push({runId:'large',sequence:9,type:'thinking_delta',data:{text:'新观察'}});
 s.events[0]={...s.events[0],lastSequence:10,type:'tool_call_update',data:{toolName:'input_tap',arguments:{x:10,y:20}}};window.receiveNative(s);
 assert.equal(visibleOperations(doc).length,2);
 assert.deepEqual(visibleOperations(doc).map(node=>node.dataset.activityKey),['large:tool:call-0','large:thinking:9']);
});
test('completed process defaults to a saved duration alone and expanding never duplicates or hides Markdown answers',()=>{
 const{window,doc}=setup();const s=treeHistory(4);s.runs[0].status='completed';s.runs[0].expanded=true;
 s.messages[0].text='**操作已完成**';s.events.push({runId:'large',sequence:9,type:'text_delta',elapsedMs:79000,data:{text:'**操作已完成**'}},
  {runId:'large',sequence:10,type:'state',elapsedMs:80000,data:{state:'completed'}});window.receiveNative(s);
 const history=doc.querySelector('.execution-history');assert.equal(history.open,false);assert.equal(history.querySelector('summary').textContent,'用时 1分钟 20秒');
 assert.equal(visibleOperations(doc).length,0);assert.equal(doc.querySelector('.markdown strong').textContent,'操作已完成');
 assert.equal(doc.querySelector('.markdown').closest('details'),null);assert.equal(doc.querySelector('.tool-body'),null);
 const answer=doc.querySelector('.markdown');openOperations(window,doc);assert.equal(visibleOperations(doc).length,4);
 assert.equal(doc.querySelectorAll('.markdown').length,1);assert.equal(doc.querySelector('.markdown'),answer);assert.ok(history.contains(answer));
 disclose(window,history,false);assert.equal(visibleOperations(doc).length,0);assert.equal(doc.querySelector('.markdown').closest('details'),null);
 const now=window.Date.now;window.Date.now=()=>now()+86400000;window.receiveNative({...s,messages:[{...s.messages[0],thinkingTruncated:true}]});
 assert.equal(doc.querySelector('.execution-history > summary').textContent,'用时 1分钟 20秒');
});
test('completion folds an inspected live process, while explicit finished expansion survives refresh and stays scoped to that run',()=>{
 const{window,doc,calls}=setup();const s=treeHistory(3);window.receiveNative(s);openOperations(window,doc);
 disclose(window,doc.querySelector('.tool-card'));assert.equal(doc.querySelector('.execution-history').open,true);
 const done=structuredClone(s);done.runs[0].status='completed';window.receiveNative(done);
 assert.equal(doc.querySelector('.execution-history').open,false);assert.equal(visibleOperations(doc).length,0);
 openOperations(window,doc);window.receiveNative({...done,messages:[{...done.messages[0],text:'已完成全部操作'}]});
 assert.equal(doc.querySelector('.execution-history').open,true);
 const next=structuredClone(done);next.runs.push({id:'new',sessionId:'a',status:'generating'});next.messages.push({id:'new-message',runId:'new',sessionId:'a',role:'assistant',text:''});
 next.events.push({runId:'new',sequence:1,type:'thinking_delta',data:{text:'另一个任务'}});window.receiveNative(next);
 assert.equal(doc.querySelector('.execution-history[data-run-id=large]').open,true);assert.equal(doc.querySelector('.execution-history[data-run-id=new]').open,false);
 assert.equal(calls.some(call=>['stop','new','expanded'].includes(call.op)),false);
});
test('whole-process disclosure animates both ways and keeps the answer visible after closing settles',()=>{
 const{window,doc,animations}=animationFixture();const s=treeHistory(3);s.runs[0].status='completed';
 s.messages[0].text='最终回答';s.events.push({runId:'large',sequence:7,type:'text_delta',elapsedMs:2400,data:{text:'最终回答'}});window.receiveNative(s);
 const history=doc.querySelector('.execution-history');history.querySelector('summary').click();assert.equal(history.dataset.expanded,'true');assert.ok(animations.length);animations.at(-1).onfinish();
 history.querySelector('summary').click();assert.equal(history.dataset.expanded,'false');assert.equal(history.open,true);assert.ok(history.querySelector('.activity-timeline'));
 animations.at(-1).onfinish();assert.equal(history.open,false);assert.equal(visibleOperations(doc).length,0);
 assert.equal(doc.querySelectorAll('.markdown').length,1);assert.equal(doc.querySelector('.markdown').closest('details'),null);
});
