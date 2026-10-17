import {chromium} from '../runtime/node_modules/@playwright/test/index.mjs';
import {createServer} from 'node:http';
import {readFileSync,mkdirSync,writeFileSync} from 'node:fs';
import assert from 'node:assert/strict';
const base='app/src/main/assets/chat/',allowed=new Set(['index.html','style.css','usage-footer.js','tool-presentation.js','ui.js','vendor/marked.umd.js','vendor/purify.min.js']);
const server=createServer((req,res)=>{const name=req.url==='/'?'index.html':req.url.slice(1);if(name==='runtime-host.js'){res.setHeader('content-type','text/javascript');res.end('');return;}if(!allowed.has(name)){res.writeHead(404);res.end();return;}res.setHeader('content-type',name.endsWith('.js')?'text/javascript':name.endsWith('.css')?'text/css':'text/html');res.end(readFileSync(base+name));});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const browser=await chromium.launch({headless:true,executablePath:'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'});
const directory='build/activity-collapse',report={passed:false,layouts:[]};mkdirSync(directory,{recursive:true});
try {
 for(const [width,height] of [[620,540],[360,640],[620,180]]) {
  const page=await browser.newPage({viewport:{width,height},colorScheme:'dark',reducedMotion:'no-preference'}),errors=[];
  page.on('pageerror',error=>errors.push(error.message));await page.addInitScript(()=>{window.uiCommands=[];window.ThorUI={postMessage:raw=>uiCommands.push(JSON.parse(raw))};});
  await page.goto(`http://127.0.0.1:${server.address().port}/`);
  await page.evaluate(()=>{
   window.fixture={selected:'a',sessions:[{id:'a',title:'查看当前应用并打开设备偏好',archivedAt:null,updatedAt:1,capabilities:{perception:true,search:true,screen_control:true,debug:false}}],
    messages:[{id:'u',sessionId:'a',role:'user',text:'查看上屏应用，打开设备偏好。'},{id:'m',sessionId:'a',runId:'r',role:'assistant',text:''}],runs:[{id:'r',sessionId:'a',status:'generating',startedAt:Date.now()-80000}],events:[
     {type:'thinking_delta',data:{text:'先观察上屏应用与页面。'}},{type:'tool_started',toolCallId:'screen',data:{toolId:'perception.capture_screen'}},
     {type:'tool_result',toolCallId:'screen',data:{toolId:'perception.capture_screen',outcome:'observed'}},{type:'thinking_delta',data:{text:'页面中有设备偏好入口，确认坐标后点击。'}},
     {type:'tool_started',toolCallId:'tap',data:{toolId:'input.tap'}},{type:'tool_result',toolCallId:'tap',data:{toolId:'input.tap',outcome:'submitted'}},
     {type:'thinking_delta',data:{text:'核对新页面，整理结果。'}}].map((event,index)=>({...event,runId:'r',sequence:index+1,elapsedMs:(index+1)*1000})),
    availability:{perception:'已连接',search:'已配置',screen_control:'已连接',debug:'已授权'},modelChoices:[{id:'deepseek-flash',name:'DeepSeek Flash'}],modelSelection:{modelId:'deepseek-flash',label:'DeepSeek Flash',thinkingEffort:'high',efforts:['off','high']},skills:[]};receiveNative(fixture);
  });
  assert.equal(await page.locator('.activity-item:visible').count(),2);assert.equal(await page.locator('.execution-history').getAttribute('open'),null);
  assert.equal(await page.locator('.execution-history > summary').textContent(),'正在运行 · 已完成 2 项操作');
  assert.deepEqual(await page.locator('.activity-item:visible').evaluateAll(nodes=>nodes.map(n=>n.dataset.activityKind)),['tool','thinking']);
  assert.equal(await page.locator('#chat .shimmer:visible').count(),1);
  if(height===540)await page.screenshot({path:`${directory}/running-compact.png`});
  await page.locator('.execution-history > summary').click();await page.locator('.execution-history[data-disclosure-animating=true]').waitFor({state:'detached'});
  assert.equal(await page.locator('.activity-item:visible').count(),5);assert.equal(await page.locator('.tool-body,.thinking-body').count(),0);
  if(height===540)await page.screenshot({path:`${directory}/running-expanded.png`});
  await page.evaluate(()=>{fixture.runs[0].status='completed';fixture.messages[1].text='已打开 **设备偏好**，确认页面中有“选择模式”和“夜间阅读”。';fixture.events.push({runId:'r',sequence:8,type:'text_delta',elapsedMs:79000,data:{text:fixture.messages[1].text}},{runId:'r',sequence:9,type:'state',elapsedMs:80000,data:{state:'completed'}});receiveNative(fixture);});
  assert.equal(await page.locator('.execution-history').getAttribute('open'),null);assert.equal(await page.locator('.execution-history > summary').textContent(),'用时 1分钟 20秒');
  assert.equal(await page.locator('.activity-item:visible').count(),0);assert.equal(await page.locator('.markdown:visible').count(),1);
  if(height===540){await page.screenshot({path:`${directory}/completed-compact.png`});await page.locator('.message.assistant').screenshot({path:`${directory}/completed-summary.png`});}
  await page.locator('.execution-history > summary').click();await page.locator('.execution-history[data-disclosure-animating=true]').waitFor({state:'detached'});
  assert.equal(await page.locator('.activity-item:visible').count(),5);assert.equal(await page.locator('.markdown:visible').count(),1);
  if(height===540)await page.screenshot({path:`${directory}/completed-expanded.png`});
  await page.locator('.execution-history > summary').click();await page.locator('.execution-history[data-disclosure-animating=true]').waitFor({state:'detached'});
  assert.equal(await page.locator('.activity-item:visible').count(),0);assert.equal(await page.locator('.markdown:visible').count(),1);
  const layout=await page.evaluate(()=>({overflow:document.documentElement.scrollWidth>innerWidth,send:document.querySelector('#send').getBoundingClientRect().toJSON(),mutatingCommands:uiCommands.filter(command=>command.op!=='ready').length}));
  assert.equal(layout.overflow,false);assert.equal(layout.mutatingCommands,0);assert.ok(layout.send.bottom<=height);assert.deepEqual(errors,[]);
  await page.evaluate(()=>{
   fixture.runs=[{id:'recovery',sessionId:'a',status:'executing_tools'}];fixture.messages=[{id:'m',sessionId:'a',runId:'recovery',role:'assistant',text:''}];
   fixture.events=[{runId:'recovery',sequence:1,type:'tool_result',toolCallId:'old-tap',data:{toolId:'input.tap',error:'TARGET_CHANGED',outcome:'observed',recovery:{submission:'not_dispatched',sideEffectPossible:false}}},
    {runId:'recovery',sequence:2,type:'diagnostic',toolCallId:'old-tap',data:{code:'TARGET_CHANGED'}}];receiveNative(fixture);
  });
  assert.equal(await page.locator('.recovery-status:visible').count(),1);assert.equal(await page.locator('.execution-history > summary').textContent(),'正在运行 · 已完成 0 项操作');
  await page.evaluate(()=>{fixture.events.push({runId:'recovery',sequence:3,type:'tool_result',toolCallId:'controller-a',data:{toolId:'input.press_button',outcome:'submitted'}});receiveNative(fixture);});
  assert.equal(await page.locator('.recovery-status:visible').count(),1);
  await page.evaluate(()=>{fixture.events.push({runId:'recovery',sequence:4,type:'tool_result',toolCallId:'fresh-screen',data:{toolId:'perception.capture_screen',outcome:'observed'}});receiveNative(fixture);});
  assert.equal(await page.locator('.recovery-status').count(),0);assert.doesNotMatch(await page.locator('#chat').innerText(),/页面已变化|需重新观察|需要重新观察/);
  assert.equal(await page.locator('.execution-history > summary').textContent(),'正在运行 · 已完成 2 项操作');
  await page.evaluate(()=>{fixture.events.push({runId:'recovery',sequence:5,type:'diagnostic',toolCallId:'old-tap',data:{code:'TARGET_CHANGED'}});receiveNative(fixture);});
  assert.equal(await page.locator('.recovery-status').count(),0);assert.doesNotMatch(await page.locator('#chat').innerText(),/页面已变化|需重新观察|需要重新观察/);assert.deepEqual(errors,[]);
  await page.evaluate(()=>{
   fixture.runs=[{id:'tool-failure',sessionId:'a',status:'completed'}];fixture.messages=[{id:'m',sessionId:'a',runId:'tool-failure',role:'assistant',text:'重新读取后已确认结果。'}];
   fixture.events=[{sequence:1,type:'tool_result',toolCallId:'failed-read',data:{toolId:'perception.capture_screen',error:'BACKEND_UNAVAILABLE',outcome:'observed'}},{sequence:2,type:'diagnostic',toolCallId:'failed-read',data:{code:'TOOL_FAILED',toolName:'perception_capture_screen'}},{sequence:3,type:'tool_result',toolCallId:'retry-read',data:{toolId:'perception.capture_screen',outcome:'observed'}}].map(e=>({...e,runId:'tool-failure'}));receiveNative(fixture);
  });
  assert.doesNotMatch(await page.locator('#chat').innerText(),/运行提示|TOOL_FAILED|执行失败|BACKEND_UNAVAILABLE/);assert.match(await page.locator('.markdown:visible').innerText(),/已确认结果/);
  await page.evaluate(pricing=>{
   fixture.tokenPricing=pricing;Object.assign(fixture.runs[0],{provider:'opencode-go',modelId:'gpt-6-luna',startedAt:Date.parse('2026-10-18T02:00:00Z')});
   fixture.events.push({runId:'tool-failure',sequence:4,type:'model_round',data:{modelRound:1,totalInputTokens:100000,cacheReadTokens:90000,outputTokens:1000}},{runId:'tool-failure',sequence:5,type:'model_round',data:{modelRound:2,totalInputTokens:100000,cacheReadTokens:95000,outputTokens:1000}});receiveNative(fixture);
  },JSON.parse(readFileSync('app/src/main/assets/token-pricing.json','utf8')));
  assert.match(await page.locator('.run-usage-footer:visible').innerText(),/输入 200.0K · 输出 2.0K · 缓存命中率 92.5% · 估算 US\$0.0044/);
  assert.equal(await page.locator('.run-usage-footer:visible').evaluate(node=>!node.closest('details') && node===node.parentElement.lastElementChild),true);
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
  if(height===540)await page.screenshot({path:`${directory}/token-footer-completed.png`});
  await page.locator('.execution-history > summary').click();await page.locator('.execution-history[data-disclosure-animating=true]').waitFor({state:'detached'});
  const failedTool=page.locator('.tool-card').first();assert.match(await failedTool.locator('summary').innerText(),/执行失败/);
  await failedTool.locator('summary').click();await page.locator('.tool-card[data-disclosure-animating=true]').waitFor({state:'detached'});assert.match(await failedTool.locator('.tool-body').innerText(),/BACKEND_UNAVAILABLE/);
  assert.equal(await page.locator('.attention').evaluateAll(nodes=>nodes.every(n=>n.closest('.tool-card'))),true);assert.doesNotMatch(await page.locator('.tool-card').nth(1).innerText(),/执行失败|BACKEND_UNAVAILABLE/);
  if(height===540)await page.screenshot({path:`${directory}/tool-failure-inline.png`});
  await page.locator('.execution-history > summary').click();await page.locator('.execution-history[data-disclosure-animating=true]').waitFor({state:'detached'});assert.doesNotMatch(await page.locator('#chat').innerText(),/运行提示|TOOL_FAILED|执行失败|BACKEND_UNAVAILABLE/);assert.deepEqual(errors,[]);
  report.layouts.push({width,height,latestActivities:2,expandedActivities:5,completedActivities:0,duration:'1分钟 20秒',answerAlwaysVisible:true,animationVerified:true,completedOperationCountVerified:true,recoveryClearedAfterObservation:true,duplicateDiagnosticIgnored:true,toolWarningsInlineOnly:true,tokenFooterVerified:true,...layout});await page.close();
 }
 report.passed=true;
} finally {writeFileSync(`${directory}/browser-check.json`,JSON.stringify(report,null,2)+'\n');await browser.close();await new Promise(resolve=>server.close(resolve));}
