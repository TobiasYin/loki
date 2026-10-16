/* Display-only peer: no Fetch adapter, lease, Native RPC or Agent lives here. */
(() => {
  'use strict';
  const maxBytes=256*1024,maxQueued=32,maxQueueBytes=1024*1024;
  let generation='',queue=[],queueBytes=0,currentState;
  const sends=new Map();
  const textBytes=text=>new TextEncoder().encode(text).byteLength;
  function showError(message){if(currentState)window.receiveNative?.({...currentState,error:message});}
  function failure(command,code='IO_ERROR') {
    if(command?.op==='send') {
      const sent=sends.get(command.clientSendId);
      if(!sent || sent.sessionId!==command.sessionId)return;
      sends.delete(command.clientSendId);window.receiveSendFailure?.(sent.text,sent.sessionId);
      if(code!=='CANCELLED')showError(window.sendFailureMessage(code,currentState?.modelSelection));
    }else if(command?.op==='capability')window.receiveCapabilityFailure?.(command);
    else showError('操作未完成，请检查授权、活动运行或本地存储。');
  }
  function post(command) {
    const raw=JSON.stringify({type:'ui.command',generation,command});
    if(textBytes(raw)>maxBytes){failure(command,'LIMIT_EXCEEDED');return;}
    try {window.ThorChat.postMessage(raw);}catch {failure(command);}
  }
  window.ThorUI={postMessage(raw) {
    let command;
    try {if(typeof raw!=='string' || textBytes(raw)>maxBytes)throw Error();command=JSON.parse(raw);if(!command || typeof command!=='object' || Array.isArray(command))throw Error();}
    catch {showError('界面操作数据无效，请重新打开聊天。');return;}
    if(command.op==='send') {
      command.clientSendId=crypto.randomUUID();
      if(typeof command.text!=='string' || command.text.length>20000){showError('消息过长，请缩短后再发送。');return;}
      sends.set(command.clientSendId,{sessionId:command.sessionId,text:command.text});
      while(sends.size>16)sends.delete(sends.keys().next().value);
    }
    if(generation)post(command);
    else {
      const size=textBytes(JSON.stringify(command));
      if(queue.length>=maxQueued || queueBytes+size>maxQueueBytes){failure(command,'LIMIT_EXCEEDED');return;}
      queue.push(command);queueBytes+=size;
    }
  }};
  window.receiveChatBootstrap=bootstrap=>{
    if(!bootstrap || typeof bootstrap.generation!=='string' || bootstrap.generation.length!==36 || bootstrap.generation===generation)return;
    if(generation){queue=[];queueBytes=0;sends.clear();} // Never replay an old document's intent.
    generation=bootstrap.generation;window.productReady=false;
    window.ThorChat.postMessage(JSON.stringify({type:'ui.hello',generation}));
    for(const command of queue.splice(0))post(command);queueBytes=0;
  };
  if(window.ThorChat)window.ThorChat.onmessage=event=>{
    let message;try {message=JSON.parse(event.data);}catch {return;}
    if(!generation || message.generation!==generation || !message.payload || typeof message.payload!=='object' || Array.isArray(message.payload))return;
    const value=message.payload;
    switch(message.type) {
      case 'ui.ready':window.productReady=true;break;
      case 'ui.state':currentState=value;window.receiveNative?.(value);break;
      case 'ui.preparationState':window.receivePreparationState?.(value);break;
      case 'ui.sendFailure':failure({op:'send',sessionId:value.sessionId,clientSendId:value.clientSendId},value.errorCode);break;
      case 'ui.capabilityFailure':window.receiveCapabilityFailure?.(value);break;
      case 'ui.operationFailure':showError('操作被拒绝：检查授权、活动运行或本地存储。');break;
      case 'ui.rejected':failure(value.command,value.code);break;
    }
  };
})();
