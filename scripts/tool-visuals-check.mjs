import {chromium} from '../runtime/node_modules/@playwright/test/index.mjs';
import {createServer} from 'node:http';
import {readFileSync,mkdirSync,writeFileSync} from 'node:fs';
import assert from 'node:assert/strict';
const directory='build/tool-presentation';mkdirSync(directory,{recursive:true});
const browser=await chromium.launch({headless:true,executablePath:'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'});
const source=await browser.newPage({viewport:{width:800,height:450}});
await source.setContent('<style>body{margin:0;background:#17202a;color:#eef4fc;font:22px Arial}h1{margin:35px 48px;font-size:30px}p{margin:20px 48px;color:#b7c5d4}button{position:absolute;left:320px;top:222px;width:160px;height:56px;background:#3978e0;border:0;border-radius:12px;color:white;font:22px Arial}.row{position:absolute;left:48px;top:226px}</style><h1>Display settings</h1><p>Device preferences</p><div class="row">Night mode</div><button>Enable</button>');
const pixels=await source.screenshot({path:directory+'/reference.png'});await source.close();
let imageRequests=0;
const allowed=new Set(['index.html','style.css','ui.js','tool-presentation.js','usage-footer.js','vendor/marked.umd.js','vendor/purify.min.js']);
const server=createServer((req,res)=>{
 if(req.url==='/chat-images/a/r/image'){imageRequests++;res.setHeader('content-type','image/png');res.setHeader('cache-control','no-store');res.end(pixels);return;}
 const name=req.url==='/'?'index.html':req.url.slice(1);
 if(['chat-host.js','send-errors.js'].includes(name)){res.setHeader('content-type','text/javascript');res.end('');return;}
 if(!allowed.has(name)){res.writeHead(404);res.end();return;}
 res.setHeader('content-type',name.endsWith('.js')?'text/javascript':name.endsWith('.css')?'text/css':'text/html');res.end(readFileSync('app/src/main/assets/chat/'+name));
});await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const report={passed:false,layouts:[]};
try {
 for(const [width,height] of [[620,540],[360,640],[620,180]]){
  const page=await browser.newPage({viewport:{width,height},colorScheme:'dark'}),errors=[];
  page.on('pageerror',e=>errors.push(e.message));await page.addInitScript(()=>{window.commands=[];window.ThorUI={postMessage:raw=>commands.push(JSON.parse(raw))};});
  await page.goto(`http://127.0.0.1:${server.address().port}/`);
  const before=imageRequests;
  await page.evaluate(()=>{
   const transform={left:400,top:200,displayWidth:800,displayHeight:450,imageWidth:800,imageHeight:450};
   const events=[{type:'tool_result',toolCallId:'capture',data:{toolId:'perception.capture_screen',outcome:'observed',content:[{type:'attachment',attachmentId:'image'},{type:'text',text:JSON.stringify({width:800,height:450,frameId:'frame',displayRole:'upper',transform})}]}},
    {type:'tool_result',toolCallId:'tap',data:{toolId:'input.tap',outcome:'submitted',submission:'submitted',visual:{kind:'input.tap',frameId:'frame',attachmentId:'image',displayRole:'upper',coordinateSpace:'display_logical',transform,points:[{x:800,y:450}]}}},
    {type:'tool_started',toolCallId:'search',data:{toolId:'web.search',arguments:{query:'星露谷 铁匠 获取武器',count:10}}},
    {type:'tool_result',toolCallId:'search',data:{toolId:'web.search',outcome:'observed',content:[{type:'text',text:JSON.stringify({results:['unneeded result'],headers:{x:'unneeded header'}})}]}}].map((e,i)=>({...e,runId:'r',sequence:i+1}));
   receiveNative({selected:'a',sessions:[{id:'a',title:'检查显示设置',archivedAt:null,capabilities:{perception:true,screen_control:true}}],runs:[{id:'r',sessionId:'a',status:'completed'}],messages:[{id:'m',sessionId:'a',runId:'r',role:'assistant',text:'已检查显示设置。'}],events,availability:{}});
  });
  assert.equal(imageRequests,before);assert.equal(await page.locator('.tool-image').count(),0);
  await page.locator('.execution-history > summary').click();await page.waitForFunction(()=>!document.querySelector('[data-disclosure-animating=true]'));
  assert.equal(imageRequests,before);
  const cards=page.locator('.tool-card');await cards.nth(0).locator('summary').click();
  await page.waitForFunction(()=>document.querySelector('.tool-image')?.naturalWidth===800);
  await page.waitForFunction(()=>!document.querySelector('[data-disclosure-animating=true]'));
  assert.ok(imageRequests>before);assert.equal(await cards.nth(0).locator('.structured').count(),0);
  if(height===540)await page.screenshot({path:directory+'/screenshot-expanded.png'});
  await cards.nth(0).locator('summary').click();await page.waitForFunction(()=>!document.querySelector('[data-disclosure-animating=true]'));
  await cards.nth(1).locator('summary').click();await page.waitForFunction(()=>document.querySelector('.tool-image')?.naturalWidth===800 && !document.querySelector('.tool-markers')?.hasAttribute('hidden'));
  await page.waitForFunction(()=>!document.querySelector('[data-disclosure-animating=true]'));
  const positions=await cards.nth(1).evaluate(card=>{
   const image=card.querySelector('img').getBoundingClientRect(),ring=card.querySelector('.tool-marker-ring'),point=ring.ownerSVGElement.createSVGPoint();point.x=Number(ring.getAttribute('cx'));point.y=Number(ring.getAttribute('cy'));const center=point.matrixTransform(ring.getScreenCTM());
   const svg=ring.ownerSVGElement.getBoundingClientRect();return {x:center.x,y:center.y,expectedX:image.left+image.width*.5,expectedY:image.top+image.height*250/450,imageWidth:image.width,overlayWidth:svg.width,imageHeight:image.height,overlayHeight:svg.height};
  });
  assert.ok(Math.abs(positions.x-positions.expectedX)<.6);assert.ok(Math.abs(positions.y-positions.expectedY)<.6);assert.ok(Math.abs(positions.imageWidth-positions.overlayWidth)<.6);assert.ok(Math.abs(positions.imageHeight-positions.overlayHeight)<.6);
  if(height===540)await page.screenshot({path:directory+'/tap-annotated.png'});
  await cards.nth(2).locator('summary').click();assert.equal(await page.locator('.tool-search-query').innerText(),'星露谷 铁匠 获取武器');assert.doesNotMatch(await cards.nth(2).innerText(),/unneeded|count|headers|results/);assert.equal(await cards.nth(2).locator('.structured').count(),0);
  await page.locator('.execution-history > summary').click();await page.waitForFunction(()=>!document.querySelector('[data-disclosure-animating=true]'));assert.equal(await page.locator('.tool-image').count(),0);
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);assert.deepEqual(await page.evaluate(()=>commands.filter(c=>c.op!=='ready')),[]);assert.deepEqual(errors,[]);
  report.layouts.push({width,height,exactCropAndScale:true,searchKeywordsOnly:true,loadsOnlyWhenExpanded:true,releasesOnCollapse:true,...positions});await page.close();
 }
 report.passed=true;
}finally{writeFileSync(directory+'/browser-check.json',JSON.stringify(report,null,2));await browser.close();await new Promise(resolve=>server.close(resolve));}
