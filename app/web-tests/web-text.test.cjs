const test=require('node:test');const assert=require('node:assert/strict');
test('page extraction removes executable head noise before enforcing the readable content budget',async()=>{
 const {readableText}=await import('../../integration/web-text.mjs');
 const source='<head><title>Wiki</title><script>'+ 'junk'.repeat(12000)+'</script></head><body><style>noise</style><p>鲶鱼 &amp; Catfish</p><table><tr><th>Time</th><td>6am &#x2013; 12am</td></tr></table><noscript>tracking</noscript></body>';
 const result=readableText(source,100);
 assert.match(result.text,/鲶鱼 & Catfish/);assert.match(result.text,/6am – 12am/);assert.doesNotMatch(result.text,/junk|noise|tracking/);assert.equal(result.truncated,false);
 assert.deepEqual(readableText('123456',4),{text:'1234',truncated:true});
});
