const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const {summarize,format}=require('../src/main/assets/chat/usage-footer.js');
const pricing=JSON.parse(fs.readFileSync('src/main/assets/token-pricing.json','utf8'));
const sunday=Date.parse('2026-10-18T02:00:00Z');
const run={id:'r',status:'completed',provider:'deepseek',modelId:'deepseek-flash',startedAt:sunday};
const event=(round,data,extra={})=>({runId:'r',sequence:round,type:'model_round',wallTime:sunday,data:{modelRound:round,...data},...extra});
const usage={totalInputTokens:10000,cacheReadTokens:8000,outputTokens:1000,reasoningTokens:700};
const near=(actual,expected)=>assert.ok(Math.abs(actual-expected)<1e-12,`${actual} != ${expected}`);

test('a user round totals all of its model requests once and never rebills cached input or reasoning',()=>{
 const events=[event(1,usage),event(2,{...usage,totalInputTokens:5000,cacheReadTokens:4000,outputTokens:500}),{...event(3,usage),runId:'another-run'}, {runId:'r',sequence:4,type:'thinking_delta',data:{text:'not usage'}}];
 const result=summarize(run,events,pricing);assert.equal(result.requests,2);assert.equal(result.input.value,15000);assert.equal(result.cache.value,12000);assert.equal(result.output.value,1500);near(result.amount,.00924);assert.equal(result.currency,'CNY');assert.match(format(result).text,/输入 15,000 · 缓存 12,000 · 输出 1,500 · 估算 ¥0.0092/);
});
test('the snapshot prices all advertised current Go models, without guessing retired model prices',()=>{
 assert.equal(pricing.unitTokens,1000000);assert.equal(pricing.providers.deepseek.currency,'CNY');assert.equal(pricing.providers['opencode-go'].currency,'USD');
 const models=JSON.parse(fs.readFileSync('src/main/assets/opencode-go-models.json','utf8')).models;
 for(const model of models)if(!['cat-jam-free','minimax-m2.7'].includes(model.id))assert.ok(pricing.providers['opencode-go'].models[model.id],model.id);
});
test('Go uses its own USD rates and the model captured on the run',()=>{
 const result=summarize({...run,provider:'opencode-go',modelId:'deepseek-v4.1-flash'},[event(1,{totalInputTokens:1000000,cacheReadTokens:800000,outputTokens:100000})],pricing);
 near(result.amount,.0924);assert.equal(result.currency,'USD');assert.match(format(result).text,/US\$0.0924/);assert.match(format(result).title,/不代表订阅之外的实际扣款/);
});
test('long-context tiers apply per request instead of to the total task input',()=>{
 const go={...run,provider:'opencode-go',modelId:'gpt-6-luna'};
 near(summarize(go,[event(1,{totalInputTokens:200000,cacheReadTokens:0,outputTokens:0}),event(2,{totalInputTokens:200000,cacheReadTokens:0,outputTokens:0})],pricing).amount,.04);
 near(summarize(go,[event(1,{totalInputTokens:272001,cacheReadTokens:0,outputTokens:0})],pricing).amount,.0544002);
});
test('peak windows use each historical request time in UTC, with exclusive ends and weekends off peak',()=>{
 const base=event(1,usage);
 for(const [time,multiplier] of [['2026-10-19T00:59:59Z',1],['2026-10-19T01:00:00Z',2],['2026-10-19T04:00:00Z',1],['2026-10-19T06:00:00Z',2],['2026-10-19T10:00:00Z',1],['2026-10-18T02:00:00Z',1]])near(summarize(run,[{...base,wallTime:Date.parse(time)}],pricing).amount,.00616*multiplier);
 const withoutWallTime={...base,wallTime:undefined,elapsedMs:3600000};near(summarize({...run,startedAt:Date.parse('2026-10-19T00:00:00Z')},[withoutWallTime],pricing).amount,.01232);
});
test('Anthropic input adds cache reads and writes only when a normalized total is absent',()=>{
 const go={...run,provider:'opencode-go',modelId:'claude-haiku-5-5',protocol:'anthropic-messages'};
 for(const normalized of [false,true]) {
  const result=summarize(go,[event(1,{inputTokens:100,cacheReadTokens:800,cacheWriteTokens:200,outputTokens:40,...(normalized?{totalInputTokens:1100}:{})})],pricing);
  assert.equal(result.input.value,1100);near(result.amount,.000063);
 }
 assert.equal(summarize(go,[event(1,{inputTokens:100,totalInputTokens:1100,cacheReadTokens:800,outputTokens:40})],pricing).amount,undefined);
});
test('missing counters, partial reporting, invalid totals and unknown prices never claim a free or fully priced task',()=>{
 const partial=summarize(run,[event(1,usage),event(2,{totalInputTokens:1000,outputTokens:100})],pricing);assert.equal(partial.cache.value,8000);assert.equal(partial.cache.partial,true);assert.equal(partial.amount,undefined);assert.match(format(partial).text,/缓存 8,000\+.*费用未估算/);
 const missing=summarize(run,[event(1,{})],pricing);assert.match(format(missing).text,/输入 — · 缓存 — · 输出 — · 费用未估算/);
 assert.equal(summarize(run,[event(1,{totalInputTokens:10,cacheReadTokens:20,outputTokens:1})],pricing).amount,undefined);
 assert.equal(summarize({...run,modelId:'future-model'},[event(1,usage)],pricing).amount,undefined);
 const overflow=summarize(run,[event(1,{...usage,totalInputTokens:Number.MAX_SAFE_INTEGER}),event(2,usage)],pricing);assert.equal(overflow.input.value,undefined);
});
test('custom providers keep actual token counters but never display a monetary estimate',()=>{
 const result=summarize({...run,provider:'custom'},[event(1,usage)],pricing);assert.equal(result.amount,undefined);assert.equal(format(result).text,'输入 10,000 · 缓存 8,000 · 输出 1,000');assert.doesNotMatch(format(result).text,/估算|费用|¥|\$/);
});
test('known free models show zero only with reported usage and tiny nonzero amounts never round to a free task',()=>{
 const free=summarize({...run,provider:'opencode-go',modelId:'beam-free'},[event(1,usage)],pricing);assert.equal(free.amount,0);assert.match(format(free).text,/US\$0.0000/);
 const tiny=summarize(run,[event(1,{totalInputTokens:1,cacheReadTokens:0,outputTokens:0})],pricing);assert.match(format(tiny).text,/估算 < ¥0.0001/);
});
test('duplicate round deliveries replace the prior usage instead of inflating cost',()=>{
 const result=summarize(run,[event(1,usage),event(1,{...usage,outputTokens:2000},{sequence:5}),event(1,usage,{sequence:2})],pricing);
 assert.equal(result.requests,1);assert.equal(result.output.value,2000);near(result.amount,.01016);
});
test('in-flight or probe runs do not display a completion footer',()=>{
 for(const status of ['queued','preparing','generating','executing_tools','awaiting_user','stopping'])assert.equal(summarize({...run,status},[event(1,usage)],pricing),null);
 assert.equal(summarize({...run,probe:true},[event(1,usage)],pricing),null);
 for(const status of ['completed','failed','cancelled','interrupted'])assert.ok(summarize({...run,status},[event(1,usage)],pricing));
});
