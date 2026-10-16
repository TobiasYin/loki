import {test} from 'node:test';
import assert from 'node:assert/strict';
import {StreamEvents} from '../src/stream-events.js';
const text=(s:string,blockId='one')=>({type:'text_delta' as const,data:{text:s,blockId}});
test('first fragment is immediate; a burst preserves UTF-8 text with bounded writes and ordered boundaries',async()=>{
 const written:any[]=[];const stream=new StreamEvents(async f=>{written.push(f);},1000);
 await stream.push(text('首'));assert.equal(written.length,1);
 for(let i=0;i<500;i++)await stream.push(text('中😀'));
 await stream.push({type:'thinking_delta',data:{text:'想',blockId:'two'}});
 assert.equal(written.filter(f=>f.type==='text_delta').map(f=>f.data.text).join(''),'首'+'中😀'.repeat(500));
 assert.ok(written.length<10,'does not durably round-trip once per token');
 assert.equal(written.at(-1).type,'thinking_delta');await stream.flush();stream.dispose();
});
test('timer delivers ongoing fragments without waiting for stream completion',async()=>{
 const written:any[]=[];const stream=new StreamEvents(async f=>{written.push(f);},10);
 await stream.push(text('先'));await stream.push(text('后'));
 await new Promise(r=>setTimeout(r,35));assert.equal(written.map(f=>f.data.text).join(''),'先后');stream.dispose();
});
test('tool updates snapshot mutable arguments, coalesce partial updates and flush final before effects',async()=>{
 const written:any[]=[];const stream=new StreamEvents(async f=>{written.push(f);},1000);
 const args={value:1};await stream.push({type:'tool_call_update',toolCallId:'a',data:{arguments:args,complete:false}});
 args.value=2;await stream.push({type:'tool_call_update',toolCallId:'a',data:{arguments:args,complete:false}});
 args.value=3;await stream.push({type:'tool_call_update',toolCallId:'a',data:{arguments:args,complete:true}});
 await stream.flush();assert.equal(written[0].data.arguments.value,1);assert.equal(written.at(-1).data.arguments.value,3);assert.equal(written.at(-1).data.complete,true);stream.dispose();
});
test('a persistence failure remains fatal across timer, flush and later fragments',async()=>{
 let calls=0;const failure=new Error('local persistence failed');const stream=new StreamEvents(async()=>{if(++calls>1)throw failure;},5);
 await stream.push(text('first'));await stream.push(text('later'));await new Promise(r=>setTimeout(r,20));
 await assert.rejects(stream.flush(),failure);await assert.rejects(stream.push(text('never')),failure);assert.equal(calls,2);stream.dispose();
});
test('slow writes stay serialized and disposal never leaves a delayed write after a flushed terminal boundary',async()=>{
 const written:string[]=[];let active=0;const stream=new StreamEvents(async f=>{assert.equal(active++,0);await new Promise(r=>setTimeout(r,2));written.push(String(f.data.text));active--;},5);
 await stream.push(text('a'));await stream.push(text('b'));await stream.flush();stream.dispose();await new Promise(r=>setTimeout(r,15));assert.deepEqual(written,['a','b']);await assert.rejects(stream.push(text('c')));
});
