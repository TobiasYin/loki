const {test}=require('node:test');const assert=require('node:assert/strict');const fs=require('node:fs');const {JSDOM}=require('jsdom');
const generation='12345678-1234-1234-1234-123456789012';
function fixture(){
 const window=new JSDOM('',{runScripts:'outside-only'}).window;window.TextEncoder=TextEncoder;
 const sent=[],states=[],drafts=[],preparations=[],capabilities=[];
 window.ThorChat={postMessage:raw=>sent.push(JSON.parse(raw))};window.receiveNative=state=>states.push(state);window.receiveSendFailure=(text,sid)=>drafts.push({text,sid});window.receivePreparationState=state=>preparations.push(state);window.receiveCapabilityFailure=value=>capabilities.push(value);
 for(const file of ['send-errors.js','chat-host.js'])window.eval(fs.readFileSync('src/main/assets/chat/'+file,'utf8'));
 const message=(type,payload={},gen=generation)=>window.ThorChat.onmessage({data:JSON.stringify({type:'ui.'+type,generation:gen,payload})});
 return{window,sent,states,drafts,preparations,capabilities,message,send:command=>window.ThorUI.postMessage(JSON.stringify(command)),boot:()=>window.receiveChatBootstrap({generation})};
}
test('chat and runtime assets load separate documents and only execution loads the Agent bundle',()=>{
 const index=fs.readFileSync('src/main/assets/chat/index.html','utf8'),runtime=fs.readFileSync('src/main/assets/chat/runtime.html','utf8');
 assert.match(index,/chat-host\.js/);assert.doesNotMatch(index,/runtime-host\.js/);assert.match(runtime,/runtime-host\.js/);assert.doesNotMatch(runtime,/ui\.js|chat-host\.js|textarea/);
 const {window}=fixture();for(const name of ['ThorNative','runNativeProbe','runPreparedNative','receiveProductState','__thorRuntimeDiagnostics'])assert.equal(window[name],undefined,name);
});
test('UI sends remain queued until private bootstrap and carry no Native connection or lease',()=>{
 const f=fixture();f.send({op:'ready'});f.send({op:'send',sessionId:'chat',text:'独立聊天文档'});assert.equal(f.sent.length,0);f.boot();
 assert.equal(f.sent[0].type,'ui.hello');assert.equal(f.sent[1].command.op,'ready');const intent=f.sent[2];assert.equal(intent.command.text,'独立聊天文档');assert.ok(intent.command.clientSendId);assert.equal(intent.generation,generation);
 assert.deepEqual(Object.keys(intent).sort(),['command','generation','type']);assert.doesNotMatch(JSON.stringify(intent),/connectionId|credentialRef|leaseId/);
 f.message('ready');assert.equal(f.window.productReady,true);f.boot();assert.equal(f.sent.length,3);
});
test('generation rejects previous-document messages and refresh never replays old send intents',()=>{
 const f=fixture();f.boot();f.message('state',{selected:'chat'});f.send({op:'send',sessionId:'chat',text:'仅发送一次'});const id=f.sent.at(-1).command.clientSendId;
 f.window.receiveChatBootstrap({generation:'87654321-4321-4321-4321-210987654321'});f.message('ready');f.message('state',{selected:'spoofed'});f.message('sendFailure',{sessionId:'chat',clientSendId:id,errorCode:'IO_ERROR'});
 assert.equal(f.states.length,1);assert.equal(f.drafts.length,0);assert.equal(f.window.productReady,false);assert.equal(f.sent.filter(m=>m.command?.op==='send').length,1);
});
test('Chinese and emoji prompt at 20000 UTF16 units fits the independent UI byte boundary',()=>{
 const f=fixture();f.boot();const text='中文😀'.repeat(5000);f.send({op:'send',sessionId:'chat',text});assert.equal(f.sent.at(-1).command.text,text);assert.ok(Buffer.byteLength(JSON.stringify(f.sent.at(-1)))>16384);assert.ok(Buffer.byteLength(JSON.stringify(f.sent.at(-1)))<256*1024);
 const count=f.sent.length;f.send({op:'send',sessionId:'chat',text:text+'超'});assert.equal(f.sent.length,count);
});
test('send failure correlates only local draft ID and session and displays fixed local error text',()=>{
 const f=fixture();f.boot();f.message('state',{selected:'chat',modelSelection:{probeRequired:false}});f.send({op:'send',sessionId:'chat',text:'原始本地草稿'});const id=f.sent.at(-1).command.clientSendId;
 f.message('sendFailure',{sessionId:'another',clientSendId:id,errorCode:'IO_ERROR'});assert.equal(f.drafts.length,0);
 f.message('sendFailure',{sessionId:'chat',clientSendId:id,errorCode:'IO_ERROR',text:'untrusted upstream transcript'});assert.deepEqual(f.drafts,[{sid:'chat',text:'原始本地草稿'}]);assert.match(f.states.at(-1).error,/检查网络/);assert.doesNotMatch(f.states.at(-1).error,/upstream/);
 f.message('sendFailure',{sessionId:'chat',clientSendId:id,errorCode:'IO_ERROR'});assert.equal(f.drafts.length,1);
});
test('preparation and capability control events preserve their identifiers and ordinary stop is a UI intent',()=>{
 const f=fixture();f.boot();f.message('preparationState',{sessionId:'chat',phase:'probing',elapsedMs:50});f.message('capabilityFailure',{sessionId:'chat',capability:'search',clientMutationId:'change-2'});
 assert.equal(f.preparations[0].phase,'probing');assert.equal(f.capabilities[0].clientMutationId,'change-2');f.send({op:'stop',runId:'run-1'});assert.deepEqual(f.sent.at(-1).command,{op:'stop',runId:'run-1'});
});
test('bounded local draft cache evicts old sends without keeping provider payloads and cancelled failure stays neutral',()=>{
 const f=fixture();f.boot();f.message('state',{selected:'chat'});const ids=[];
 for(let i=0;i<17;i++){f.send({op:'send',sessionId:'chat',text:'local-'+i});ids.push(f.sent.at(-1).command.clientSendId);}
 f.message('sendFailure',{sessionId:'chat',clientSendId:ids[0],errorCode:'IO_ERROR'});assert.equal(f.drafts.length,0);
 f.message('sendFailure',{sessionId:'chat',clientSendId:ids.at(-1),errorCode:'CANCELLED'});assert.equal(f.drafts[0].text,'local-16');assert.equal(f.states.length,1);
});
test('local error mapping is exactly shared with runtime instead of exposing upstream messages',()=>{
 const expected=fs.readFileSync('../integration/send-errors.mjs','utf8').replace('export function sendFailureMessage','window.sendFailureMessage = function sendFailureMessage');assert.equal(fs.readFileSync('src/main/assets/chat/send-errors.js','utf8'),expected);
 const f=fixture();f.boot();f.message('state',{selected:'chat'});f.message('operationFailure',{});assert.match(f.states.at(-1).error,/检查授权/);
 f.window.ThorChat.onmessage({data:'malformed'});f.window.ThorChat.onmessage({data:JSON.stringify({type:'ui.state',generation,payload:[]})});assert.equal(f.states.length,2);
});
