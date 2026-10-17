import {chromium} from '../runtime/node_modules/@playwright/test/index.mjs';
import {createServer} from 'node:http';
import {readFileSync,mkdirSync,writeFileSync} from 'node:fs';
import assert from 'node:assert/strict';
const base='app/src/main/assets/chat/',allowed=new Set(['index.html','style.css','ui.js','vendor/marked.umd.js','vendor/purify.min.js']);
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
  report.layouts.push({width,height,latestActivities:2,expandedActivities:5,completedActivities:0,duration:'1分钟 20秒',answerAlwaysVisible:true,animationVerified:true,...layout});await page.close();
 }
 report.passed=true;
} finally {writeFileSync(`${directory}/browser-check.json`,JSON.stringify(report,null,2)+'\n');await browser.close();await new Promise(resolve=>server.close(resolve));}
