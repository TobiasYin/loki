import { build } from '../runtime/node_modules/esbuild/lib/main.js';
import { strict as assert } from 'node:assert';
const output=await build({entryPoints:['integration/web-services.ts'],bundle:true,write:false,format:'esm',platform:'node'});
const {webRequest,searchResults}=await import(`data:text/javascript;base64,${Buffer.from(output.outputFiles[0].text).toString('base64')}`);
for(const backend of ['Tavily','DeepSeek']) {
  const request=webRequest(backend,true,'https://service.test/search',{query:'中文 Android'});
  assert.equal(request.init.method,'POST');const body=JSON.parse(request.init.body);
  assert.equal(body.query,'中文 Android');assert.equal(body[backend==='Tavily'?'max_results':'count'],5);
  assert.equal(searchResults(backend,JSON.stringify({results:[{title:'Android',url:'https://example.com',content:'Tavily text',summary:'DeepSeek summary'}]}))[0].summary,backend==='Tavily'?'Tavily text':'DeepSeek summary');
}
assert.equal(webRequest('Brave',true,'https://brave.test/search',{query:'a b'}).url,'https://brave.test/search?q=a%20b&count=5');
const fetch=webRequest('DeepSeek',false,'https://api.deepseek.com/v1/tools/fetch',{url:'https://example.com'});
assert.deepEqual(JSON.parse(fetch.init.body),{url:'https://example.com'});
assert.throws(()=>webRequest('DeepSeek',true,'https://service.test',{query:'中'.repeat(129)}));
assert.doesNotThrow(()=>webRequest('DeepSeek',true,'https://service.test',{query:'😀'.repeat(128)}));
assert.throws(()=>searchResults('Tavily','{"error":"denied"}'));
console.log('Search wire formats, result normalization, fetch and Unicode limits passed');
const doubao=webRequest('豆包搜索',true,'https://open.feedcoopapi.com/search_api/web_search',{query:'火山 AgentPlan'});
assert.equal(doubao.init.method,'POST');
assert.deepEqual(JSON.parse(doubao.init.body),{Query:'火山 AgentPlan',Count:5,SearchType:'web'});
assert.deepEqual(searchResults('豆包搜索',JSON.stringify({Result:{WebResults:[{Title:'火山',Url:'https://example.com',Snippet:'摘要',PublishTime:'2026-10-19'}]}})),[{title:'火山',url:'https://example.com',summary:'摘要',sourceTime:'2026-10-19'}]);
assert.throws(()=>searchResults('豆包搜索',JSON.stringify({ResponseMetadata:{Error:{Code:'Denied'}},Result:{WebResults:[]}})));
assert.throws(()=>webRequest('豆包搜索',true,'https://example.com',{query:'字'.repeat(101)}));
assert.throws(()=>webRequest('豆包搜索',true,'https://example.com',{query:' '}));
assert.doesNotThrow(()=>webRequest('豆包搜索',true,'https://example.com',{query:'😀'.repeat(100)}));
assert.equal(webRequest('豆包搜索',false,'https://example.com',{url:'https://example.com'}).init.method,'GET');
console.log('Doubao search normalization, service errors and Unicode limits passed');
