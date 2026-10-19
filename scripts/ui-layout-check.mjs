import {chromium} from '../runtime/node_modules/@playwright/test/index.mjs';
import {createServer} from 'node:http';
import {readFileSync,mkdirSync,writeFileSync} from 'node:fs';
import assert from 'node:assert/strict';
const base='app/src/main/assets/chat/';
const server=createServer((req,res)=>{let name=req.url==='/'?'index.html':req.url.slice(1);if(name==='runtime-host.js'){res.setHeader('content-type','text/javascript');res.end('');return;}if(!['index.html','style.css','ui.js','vendor/marked.umd.js','vendor/purify.min.js'].includes(name)){res.writeHead(404);res.end();return;}res.setHeader('content-type',name.endsWith('.js')?'text/javascript':name.endsWith('.css')?'text/css':'text/html');res.end(readFileSync(base+name));});
await new Promise(r=>server.listen(0,'127.0.0.1',r));const url=`http://127.0.0.1:${server.address().port}/`;
const browser=await chromium.launch({headless:true,executablePath:'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'});
mkdirSync('build/ui-preview',{recursive:true});const checks=[];
try {for(const [width,height] of [[620,540],[620,180],[360,640]]) {
 const page=await browser.newPage({viewport:{width,height},colorScheme:'dark',reducedMotion:'reduce'});const errors=[];page.on('pageerror',e=>errors.push(e.message));await page.addInitScript(()=>window.ThorUI={postMessage(){}});await page.goto(url);await page.evaluate(()=>window.receiveNative({selected:'a',sessions:[{id:'a',title:'新会话',archivedAt:null,updatedAt:Date.now(),capabilities:{perception:true,search:true,screen_control:false,debug:false}}],messages:[],events:[],runs:[],activeRuns:[],availability:{perception:'已就绪',search:'已配置',screen_control:'需授权',debug:'需授权'},modelStatus:'配置探测已通过',modelChoices:[{id:'deepseek-v4.1-flash',name:'DeepSeek V4.1 Flash'}],modelSelection:{modelId:'deepseek-v4.1-flash',label:'DeepSeek V4.1 Flash',efforts:['low','high','max'],thinkingEffort:'high',contextWindow:1000000},skills:[{name:'adb-operations',description:'设备操作',enabled:true,builtin:true}]}));
 const toolbar=await page.locator('.controls > button').evaluateAll(nodes=>nodes.map(node=>{const r=node.getBoundingClientRect();return {id:node.id,center:r.y+r.height/2,height:r.height,left:r.left,right:r.right};}));
 for(const button of toolbar){assert.ok(Math.abs(button.center-toolbar[0].center)<1,`${button.id} shares the toolbar center line`);assert.equal(button.height,44);assert.ok(button.left>=0 && button.right<=width,`${button.id} stays inside viewport`);}
 const iconCenters=await page.locator('#more svg, #capabilities svg, #voice-toggle svg').evaluateAll(nodes=>nodes.map(node=>{const r=node.getBoundingClientRect();return r.y+r.height/2;}));
 for(const center of iconCenters)assert.ok(Math.abs(center-toolbar[0].center)<1,'Icons share the toolbar center line');
 await page.screenshot({path:`build/ui-preview/toolbar-${width}x${height}.png`});
 if(height>250) {
 // Keep settings reachable with a long history at every supported viewport.
 await page.evaluate(()=>window.receiveNative({selected:'a',sessions:Array.from({length:40},(_,i)=>({id:String(i),title:'会话 '+i,archivedAt:null,updatedAt:Date.now(),capabilities:{}})),messages:[],events:[],runs:[],activeRuns:[]}));
 await page.click('#menu');
 assert.equal(await page.locator('header button').count(),2);
 assert.equal(await page.locator('header #settings').count(),0);
 const settings=await page.locator('#drawer-settings').boundingBox();
 const scroll=await page.locator('.drawer-scroll').boundingBox();
 assert.ok(settings.y>=0 && settings.y+settings.height<=height,'Settings stays visible below long history');
 assert.ok(scroll.y+scroll.height<=settings.y,'History scrolls above settings');
 await page.locator('.drawer-scroll').evaluate(node=>node.scrollTop=node.scrollHeight);
 const afterScroll=await page.locator('#drawer-settings').boundingBox();
 assert.equal(afterScroll.y,settings.y);
 await page.click('#drawer-settings');
 assert.equal(await page.locator('#drawer').isVisible(),false,'Opening settings dismisses drawer');
 }
 const metrics=await page.evaluate(()=>({height:innerHeight,width:innerWidth,composer:document.querySelector('#composer').getBoundingClientRect().toJSON(),send:document.querySelector('#send').getBoundingClientRect().toJSON(),overflow:document.documentElement.scrollWidth>innerWidth,probeVisible:!document.querySelector('#notice').hidden}));assert.deepEqual(errors,[]);assert.equal(metrics.overflow,false);assert.ok(metrics.send.bottom<=height);assert.ok(metrics.composer.height<=140);assert.ok(metrics.send.width>=44 && metrics.send.height>=44);assert.equal(metrics.probeVisible,false);checks.push(metrics);await page.screenshot({path:`build/ui-preview/composer-${width}x${height}.png`});
 if(height===540){await page.click('#more');await page.screenshot({path:'build/ui-preview/plus-menu.png'});await page.click('#model-picker');await page.screenshot({path:'build/ui-preview/model-picker.png'});}
 if(height===180){await page.click('#more');const menu=await page.locator('#more-panel').boundingBox();assert.ok(menu.y>=0 && menu.y+menu.height<=height);}
 await page.close();
}writeFileSync('build/ui-preview/layout-checks.json',JSON.stringify({passed:true,checks},null,2));}finally{await browser.close();await new Promise(r=>server.close(r));}
