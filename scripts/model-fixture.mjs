import http from 'node:http';
import { appendFileSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { protocolSse } from '../runtime/dist/test/fixtures.js';
const output = process.env.THOR_FIXTURE_OUTPUT ?? 'build/integration';
mkdirSync(output, { recursive: true });
let count = 0;
const server = http.createServer(async (req,res) => {
  let raw = ''; for await(const bytes of req) { raw += bytes; if(raw.length > 16777216) {res.writeHead(413);res.end();return;} }
  if(req.url.startsWith('/res/v1/web/search')) {
    if(req.headers['x-subscription-token'] !== 'integration-search-secret') {res.writeHead(401);res.end();return;}
    appendFileSync(join(output,'model-requests.jsonl'),JSON.stringify({purpose:'web_search',credentialMatched:true,path:'/res/v1/web/search'})+'\n');
    res.writeHead(200,{'content-type':'application/json'});res.end(JSON.stringify({web:{results:[{title:'Fixture source',url:'https://example.org/source',description:'Controlled search boundary result',page_age:'2026-10-13'}]}}));return;
  }
  if(req.url === '/web/redirect') {
    if(req.headers.authorization || req.headers['x-subscription-token']) {res.writeHead(400);res.end('unexpected credential');return;}
    res.writeHead(302,{location:'http://127.0.0.1:8878/web/anonymous%20page'});res.end();return;
  }
  const protocol=req.url.startsWith('/v1/responses')?'openai-responses':req.url.includes('/chat/completions')?'openai-completions':'anthropic-messages';
  const auth = protocol !== 'anthropic-messages' ? req.headers.authorization : req.headers['x-api-key'];
  if(auth !== (protocol !== 'anthropic-messages' ? 'Bearer integration-only-secret' : 'integration-only-secret')) {res.writeHead(401);res.end('invalid credential');return;}
  const body = JSON.parse(raw); const serialized = JSON.stringify(body);
  const messages = body.input ?? body.messages ?? [];
  let userIndex = 0;
  for(let i=0;i<messages.length;i++) if(messages[i].role === 'user' && !(Array.isArray(messages[i].content) && messages[i].content.some(c=>c.text==='Attached image(s) from tool result:')) && (typeof messages[i].content==='string' || Array.isArray(messages[i].content) && messages[i].content.some(c=>c.type==='input_text'||c.type==='text'))) userIndex=i;
  const current = messages.slice(userIndex);
  const userText = JSON.stringify(current);
  const currentSerialized=JSON.stringify(current);
  function backupId(value) {
    if(typeof value==='string') { const match=value.match(/"backupId"\s*:\s*"([0-9a-f-]{36})"/);if(match)return match[1];try{return backupId(JSON.parse(value));}catch{return undefined;} }
    if(value&&typeof value==='object') {if(value.backupId)return value.backupId;for(const v of Object.values(value)){const id=backupId(v);if(id)return id;}}
  }
  const probing = userText.includes('Configuration probe:');
  const result = protocol==='openai-completions'?current.some(m=>m.role==='tool'):protocol === 'openai-responses' ? current.some(m=>m.type==='function_call_output') : current.some(m=>Array.isArray(m.content) && m.content.some(c=>c.type==='tool_result'));
  const imageCount = (serialized.match(/image_url|"type":"image"/g) ?? []).length;
  const names = (body.tools ?? []).map(t=>t.name ?? t.function?.name);
  function frameId(value) {
    if(typeof value==='string') {try{return frameId(JSON.parse(value));}catch{return undefined;}}
    if(value&&typeof value==='object') {if(value.frameId)return value.frameId;for(const v of Object.values(value)){const id=frameId(v);if(id)return id;}}
  }
  let reply;
  if(userText.includes('HTTP_429')) {res.writeHead(429);res.end('fixture HTTP failure');return;}
  if(userText.includes('HTTP_500')) {res.writeHead(500);res.end('fixture HTTP failure');return;}
  if(userText.includes('WAIT_CANCEL')) {res.writeHead(200,{'content-type':'text/event-stream'});res.write(': open\n\n');req.on('close',()=>res.end());return;}
  if(probing && !result) {
    if((!userText.includes('text-only') && imageCount < 1) || !names.includes('probe_picture')) {res.writeHead(422);res.end('missing image or probe tool');return;}
    reply={call:{name:'probe_picture',args:'{}'}};
  } else if(probing) {
    if(!userText.includes('text-only') && imageCount < 2) {res.writeHead(422);res.end('image result missing');return;}
    reply={text:'PROBE_OK：输入与工具图片均已收到，红色测试图。'};
  } else if(userText.includes('WEB_FETCH') && !result) reply={call:{name:'web_fetch',args:JSON.stringify({url:'http://127.0.0.1:8877/web/redirect'})}};
  else if(userText.includes('WEB_FETCH')) reply={text:'ANONYMOUS_WEB_OK：网页内容已读取。'};
  else if(userText.includes('WEB_SEARCH') && !result) reply={call:{name:'web_search',args:'{"query":"thor integration"}'}};
  else if(userText.includes('WRITE_CONFIG') && !result) {const old=userText.match(/WRITE_CONFIG_([0-9a-f]{64})/)[1];reply={call:{name:'files_backup',args:JSON.stringify({path:'settings.json',expectedHash:old})}};}
  else if(userText.includes('WRITE_CONFIG') && !currentSerialized.includes('files_write')) {const old=userText.match(/WRITE_CONFIG_([0-9a-f]{64})/)[1];reply={call:{name:'files_write',args:JSON.stringify({path:'settings.json',expectedHash:old,content:'{"volume":70,"unknown":"keep"}',backupId:backupId(current)})}};}
  else if(userText.includes('DEVICE_INFO') && !result) reply={call:{name:'perception_device_info',args:'{"sections":["system"]}'}};
  else if(userText.includes('LAB_FRAME') && !result) {const m=userText.match(/LAB_FRAME_(\d+)_(\d+)/);const x=Number(m[1]),y=Number(m[2]);reply={call:{name:'perception_capture_screen',args:JSON.stringify({region:[x-50,y-50,x+50,y+50],maxEdge:100,format:'jpeg'})}};}
  else if(userText.includes('LAB_FRAME') && !currentSerialized.includes('input_tap')) reply={call:{name:'input_tap',args:JSON.stringify({x:50,y:50,coordinateSpace:'frame_pixels',frameId:frameId(current),expectedPackage:'dev.thor.inputlab'})}};
  else if(userText.includes('LAB_CAPTURE') && !result) reply={call:{name:'perception_capture_screen',args:'{"region":[0,0,100,100],"maxEdge":64,"format":"jpeg"}'}};
  else if(userText.includes('LAB_TAP') && !result) { const match = serialized.match(/LAB_TAP_(\d+)_(\d+)/); reply={call:{id:'lab_call',name:'input_tap',args:JSON.stringify({x:Number(match[1]),y:Number(match[2]),coordinateSpace:'display_logical',expectedPackage:'dev.thor.inputlab'})}}; }
  else if(userText.includes('SHELL_WAIT') && !result) reply={call:{name:'debug_shell',args:'{"command":"settings put system screen_focus_lock 1"}'}};
  else if(userText.includes('SHELL_REJECT') && !result) reply={call:{name:'debug_shell',args:'{"command":"settings put system screen_focus_lock 1"}'}};
  else reply={text:'集成中文流式回答：真实 Pi 经 Native Fetch 完成。'};
  appendFileSync(join(output,'model-requests.jsonl'), JSON.stringify({index:count++,protocol,url:req.url,imageCount,names,result,credentialMatched:true,requestBytes:Buffer.byteLength(raw),model:body.model,reasoningEffort:body.reasoning?.effort??body.reasoning_effort??null})+'\n');
  res.writeHead(200,{'content-type':'text/event-stream','cache-control':'no-store'});
  const bytes=Buffer.from(protocolSse(protocol,reply,count));
  // Split every UTF-8 character across several writes, including Chinese text.
  for(let i=0;i<bytes.length;i+=3) {if(res.destroyed)break;res.write(bytes.subarray(i,i+3));await new Promise(r=>setTimeout(r,1));}
  // Leave the HTTP body open briefly after Completions' protocol marker, as
  // real SSE servers may do. The SDK's successful reader close must be handled.
  if(protocol==='openai-completions')await new Promise(r=>setTimeout(r,250));
  res.end();
});
server.listen(8877,'127.0.0.1',()=>process.stdout.write('Fixture listening on 127.0.0.1:8877\n'));

// A second origin proves anonymous HTTP redirects through the Android bridge.
http.createServer((req,res)=>{
  const clean=!req.headers.authorization && !req.headers['x-subscription-token'];
  appendFileSync(join(output,'model-requests.jsonl'),JSON.stringify({purpose:'anonymous_web',path:req.url,credentialsAbsent:clean})+'\n');
  res.writeHead(clean && req.url==='/web/anonymous%20page'?200:400,{'content-type':'text/plain; charset=utf-8'});
  res.end(clean?'ANONYMOUS_WEB_OK：无凭据网页内容。':'unexpected credential');
}).listen(8878,'127.0.0.1');
