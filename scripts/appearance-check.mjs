import {chromium} from '../runtime/node_modules/@playwright/test/index.mjs';
import {createServer} from 'node:http';
import {readFileSync,mkdirSync,writeFileSync} from 'node:fs';
import assert from 'node:assert/strict';
const base='app/src/main/assets/chat/';
const server=createServer((req,res)=>{
  const name=req.url==='/'?'index.html':req.url.slice(1);
  if(!/^(?:[a-z-]+\.(?:html|css|js)|vendor\/[a-z.-]+\.js)$/.test(name)){res.writeHead(404);res.end();return;}
  try{const data=readFileSync(base+name);res.setHeader('content-type',name.endsWith('.js')?'text/javascript':name.endsWith('.css')?'text/css':'text/html');res.end(data);}
  catch{res.writeHead(404);res.end();}
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const browser=await chromium.launch({headless:true,executablePath:'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'});
mkdirSync('build/appearance-evidence',{recursive:true});
const checks=[];
try {
  for(const system of ['light','dark'])for(const selected of ['system','light','dark']) {
    const page=await browser.newPage({viewport:{width:620,height:540},colorScheme:system,reducedMotion:'reduce'});
    const errors=[];page.on('pageerror',e=>errors.push(e.message));
    await page.addInitScript(()=>window.ThorUI={postMessage(){}});
    await page.goto(`http://127.0.0.1:${server.address().port}/`);
    await page.evaluate(({selected})=>{
      if(selected!=='system')document.documentElement.dataset.theme=selected;
      window.receiveNative({selected:'demo',sessions:[{id:'demo',title:'背景与页面配色',archivedAt:null,updatedAt:1,capabilities:{perception:true}}],messages:[
        {id:'u',sessionId:'demo',role:'user',text:'保留背景细节，也让文字清楚。',status:'complete'},
        {id:'a',sessionId:'demo',role:'assistant',text:'页面与背景使用协调的配色。\n\n亮色清爽，暗色柔和；毛玻璃和背景不透明度仍可独立调整。',status:'complete'}],runs:[],events:[],activeRuns:[],availability:{},modelSelection:{modelId:'demo',label:'DeepSeek V4.1 Flash',efforts:['high'],thinkingEffort:'high'}});
    },{selected});
    const expected=selected==='system'?system:selected;
    const colors=await page.evaluate(()=>{const c=getComputedStyle(document.documentElement);return {background:c.getPropertyValue('--bg').trim(),ink:c.getPropertyValue('--ink').trim(),scheme:c.colorScheme};});
    assert.equal(colors.background,expected==='dark'?'#181b20':'#f8f9fc');
    assert.equal(colors.scheme,expected);
    await page.evaluate(({expected})=>{
      document.documentElement.classList.add('chat-background');
      // Emulate the native layer: colorful details survive the theme's scrim.
      const tint=expected==='dark'?'rgba(24,27,32,.30)':'rgba(248,249,252,.22)';
      document.documentElement.style.background=`linear-gradient(${tint},${tint}),repeating-linear-gradient(135deg,#457a8d 0px,#457a8d 80px,#d6b981 80px,#d6b981 160px,#637c68 160px,#637c68 240px)`;
    },{expected});
    const layers=await page.evaluate(()=>Object.fromEntries(['body','header','#composer','.message.assistant','.message.user'].map(selector=>[selector,getComputedStyle(document.querySelector(selector)).backgroundColor])));
    assert.equal(layers.body,'rgba(0, 0, 0, 0)');
    for(const selector of ['header','#composer','.message.assistant','.message.user'])assert.match(layers[selector],/^rgba\(.+, 0\.\d+\)$/);
    const cardChecks=[];
    for(const alpha of [0,.25,1]) {
      await page.evaluate(alpha=>document.documentElement.style.setProperty('--card-alpha',String(alpha)),alpha);
      const fill=await page.evaluate(()=>['header','#composer','.message.assistant','.message.user'].map(selector=>({selector,fill:getComputedStyle(document.querySelector(selector)).backgroundColor,textOpacity:getComputedStyle(document.querySelector(selector)).opacity})));
      for(const item of fill) {
        const channels=item.fill.match(/[\d.]+/g).map(Number);
        assert.equal(channels.length===4?channels[3]:1,alpha,`${item.selector} follows independent card opacity`);
        assert.equal(item.textOpacity,'1','Only card fill fades');
      }
      cardChecks.push({alpha,fill});
    }
    await page.evaluate(()=>document.documentElement.style.setProperty('--card-alpha','.84'));
    assert.deepEqual(errors,[]);
    if(selected===expected)await page.screenshot({path:`build/appearance-evidence/${expected}.png`});
    checks.push({system,selected,colors,layers,cardChecks});await page.close();
  }
  writeFileSync('build/appearance-evidence/checks.json',JSON.stringify({passed:true,checks},null,2));
  console.log(`Appearance checks passed: ${checks.length} system/selection combinations`);
} finally {await browser.close();await new Promise(resolve=>server.close(resolve));}
