const {test}=require('node:test');const assert=require('node:assert/strict');
test('preset failures never tell users to run a custom image/tool probe',async()=>{
 const {sendFailureMessage}=await import('../../integration/send-errors.mjs');
 for(const provider of ['opencode-go','deepseek-official'])for(const code of ['UNSUPPORTED','INVALID_REQUEST','INVALID_ARGUMENT','IO_ERROR','TIMEOUT'])assert.doesNotMatch(sendFailureMessage(code,{provider,probeRequired:false}),/探测|验证|图片/);
 assert.match(sendFailureMessage('UNSUPPORTED',{probeRequired:true}),/自定义模型.*验证/);
 assert.match(sendFailureMessage('IO_ERROR',{probeRequired:true}),/网络或服务/);
 assert.doesNotMatch(sendFailureMessage('Bearer super-secret body',{probeRequired:false}),/super-secret|Bearer/);
});
