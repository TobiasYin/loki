const {test}=require('node:test');const assert=require('node:assert/strict');
const {describe,annotation}=require('../src/main/assets/chat/tool-presentation.js');
const transform={left:400,top:200,displayWidth:800,displayHeight:450,imageWidth:400,imageHeight:225};
const screenshot={id:'shot',runId:'r',sessionId:'s',firstSequence:1,resultSequence:3,toolId:'perception.capture_screen',result:{content:[{type:'attachment',attachmentId:'image'},{type:'text',text:JSON.stringify({frameId:'frame',width:400,height:225,displayId:0,displayRole:'upper',transform})}]}};
test('capture expansion uses its exact local attachment instead of parsing protocol fields into a detail table',()=>{
 const view=describe(screenshot);assert.equal(view.kind,'image');assert.equal(view.image.attachmentId,'image');assert.equal(view.image.frameId,'frame');
 assert.equal(describe({...screenshot,error:'PERMISSION_MISSING'}).kind,'missing-image');
 assert.equal(describe({...screenshot,result:{contentDiscarded:true,content:screenshot.result.content}}).kind,'missing-image');
});
test('display-logical points use the original crop and scale, even if the capture event has left the page',()=>{
 const call={toolId:'input.tap',arguments:{x:800,y:450},result:{visual:{kind:'input.tap',coordinateSpace:'display_logical',attachmentId:'image',frameId:'frame',displayRole:'upper',transform,points:[{x:800,y:450}]}}};
 const view=describe(call,[]);assert.equal(view.kind,'action');assert.deepEqual(view.marker.points,[{x:200,y:125}]);assert.equal(view.marker.width,400);assert.equal(view.marker.height,225);
});
test('semantic activation outlines the real control bounds and never invents a physical touch point',()=>{
 const call={toolId:'input.activate',result:{visual:{kind:'input.activate',coordinateSpace:'display_logical',attachmentId:'image',transform,bounds:{left:600,top:300,right:800,bottom:400}}}};
 const view=describe(call);assert.equal(view.component,true);assert.deepEqual(view.marker.bounds,{x:100,y:50,width:100,height:50});assert.equal(view.marker.points,undefined);
});
test('legacy frame pixels need the same preceding run/session frame and never reuse companion or unrelated pictures',()=>{
 const call={toolId:'input.tap',runId:'r',sessionId:'s',firstSequence:5,arguments:{coordinateSpace:'frame_pixels',frameId:'frame',x:100,y:50}};
 assert.deepEqual(describe(call,[screenshot]).marker.points,[{x:100,y:50}]);
 for(const wrong of [{...screenshot,runId:'other'},{...screenshot,sessionId:'other'},{...screenshot,resultSequence:8},{...screenshot,toolId:'perception.capture_companion'}])assert.equal(describe(call,[wrong]).kind,'missing-action');
 assert.equal(describe({...call,arguments:{x:800,y:450,coordinateSpace:'display_logical'}},[screenshot]).kind,'missing-action');
});
test('invalid transforms, offscreen markers, unknown spaces and executable paths never produce overlays or image sources',()=>{
 assert.equal(annotation({transform},[{x:10,y:20}],null,'unknown'),null);
 assert.equal(annotation({transform},[{x:400,y:20}],null,'frame_pixels'),null);
 assert.equal(annotation({transform},[{x:Infinity,y:20}],null,'frame_pixels'),null);
 assert.equal(annotation({transform:{...transform,displayWidth:0}},[{x:10,y:20}],null,'frame_pixels'),null);
 assert.equal(describe({...screenshot,result:{content:[{type:'attachment',attachmentId:'../../private'}]}}).kind,'missing-image');
});
test('search expansion selects only the query, without result bodies or internal request configuration',()=>{
 const view=describe({toolId:'web.search',arguments:{query:'星露谷 铁匠 剑',count:10},result:{content:[{type:'text',text:JSON.stringify({results:[{url:'https://example.invalid',snippet:'long result'}],headers:{private:'ignore'}})}]}});
 assert.deepEqual(view,{kind:'search',query:'星露谷 铁匠 剑'});
});
