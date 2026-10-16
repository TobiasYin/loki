const {test}=require('node:test');const assert=require('node:assert/strict');
const header={type:'host.rpc',bridgeVersion:1,connectionId:'trusted-connection',requestId:'request-1',op:'persist'};
test('small host requests retain the normal inline protocol',async()=>{
 const {encodeHostRpc}=await import('../../integration/rpc-frame.mjs');const data={kind:'context',value:{estimatedTokens:200}};
 assert.deepEqual(JSON.parse(encodeHostRpc(header,data)),{...header,data});
});
test('a complete tool round larger than 64 KiB uses a bounded exact UTF-8 body',async()=>{
 const {encodeHostRpc}=await import('../../integration/rpc-frame.mjs');const text='长列表上下文与追问 🌱 '.repeat(12000);const data={kind:'turn',value:{messages:[{kind:'tool_result',content:[{type:'text',text}]}]}};
 const packet=JSON.parse(encodeHostRpc(header,data));const bytes=Buffer.from(packet.bytes,'base64');
 assert.ok(bytes.length>65536);assert.equal(packet.dataInBody,true);assert.equal(Object.hasOwn(packet,'data'),false);
 const meta={...packet};delete meta.bytes;assert.ok(Buffer.byteLength(JSON.stringify(meta))<65536);
 assert.deepEqual(JSON.parse(bytes.toString('utf8')),data);assert.equal(packet.connectionId,header.connectionId);assert.equal(packet.op,'persist');assert.equal(packet.requestId,header.requestId);
});
test('body fields cannot replace the trusted request header and malformed envelopes fail locally',async()=>{
 const {encodeHostRpc}=await import('../../integration/rpc-frame.mjs');const data={connectionId:'different',op:'end',requestId:'other',text:'x'.repeat(70000)};
 const packet=JSON.parse(encodeHostRpc(header,data));assert.equal(packet.connectionId,header.connectionId);assert.equal(packet.op,'persist');assert.deepEqual(JSON.parse(Buffer.from(packet.bytes,'base64').toString('utf8')),data);
 for(const extra of [{data:{}},{dataInBody:false},{bytes:'x'},{type:'request.start'}])assert.throws(()=>encodeHostRpc({...header,...extra},{}),e=>e.code==='INVALID_REQUEST');
 for(const value of [null,[],undefined,'text'])assert.throws(()=>encodeHostRpc(header,value),e=>e.code==='INVALID_REQUEST');
});
test('oversized UTF-8 state and metadata are rejected before posting',async()=>{
 const {encodeHostRpc}=await import('../../integration/rpc-frame.mjs');assert.throws(()=>encodeHostRpc(header,{text:'x'.repeat(16*1024*1024+1)}),e=>e.code==='LIMIT_EXCEEDED');
 assert.throws(()=>encodeHostRpc({...header,op:'x'.repeat(65537)},{text:'y'.repeat(40000)}),e=>e.code==='LIMIT_EXCEEDED');
});
