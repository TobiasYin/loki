'use strict';
(() => {
  const $ = id => document.getElementById(id);
  let state = null, showArchives = false, modalCallback = null;
  const labels = {perception:'上屏感知',search:'联网搜索',screen_control:'屏幕操作',debug:'ADB 调试'};
  const statuses = {preparing:'正在准备',generating:'正在回答',executing_tools:'工具执行中',awaiting_user:'等待用户批准',stopping:'正在停止',completed:'已完成',failed:'失败',cancelled:'已取消',interrupted:'已中断'};
  const terminal = new Set(['completed','failed','cancelled','interrupted']);
  const command = payload => {
    if(window.ThorUI) window.ThorUI.postMessage(JSON.stringify(payload));
    else { $('notice').textContent = '浏览器预览没有 Native 桥；请运行 Android APK。'; $('notice').hidden = false; }
  };
  const element = (tag, text, className) => { const n=document.createElement(tag); if(text !== undefined)n.textContent=text; if(className)n.className=className; return n; };
  const button = (text, action, className) => { const n=element('button',text,className); n.type='button'; n.addEventListener('click',action); return n; };
  const selected = () => state?.sessions.find(s=>s.id===state.selected);
  const active = () => state?.runs.find(r=>state.activeRunId ? r.id===state.activeRunId : !terminal.has(r.status));
  function drawer(open) { $('drawer').hidden=!open; $('shade').hidden=!open; $('menu').setAttribute('aria-expanded',String(open)); if(open)$('close-drawer').focus(); else $('menu').focus(); }
  function modal(title,text,initial,callback) {
    $('modal-title').textContent=title; $('modal-text').textContent=text;
    $('modal-input').hidden=initial===null; $('modal-input').value=initial??''; modalCallback=callback;
    $('modal').showModal(); if(initial!==null)$('modal-input').focus();
  }
  $('modal').addEventListener('close',()=>{ const callback=modalCallback; modalCallback=null; if($('modal').returnValue==='ok')callback?.($('modal-input').value); });
  function renderSessions() {
    const list=$('sessions'); list.replaceChildren();
    for(const s of [...state.sessions].filter(s=>showArchives ? s.archivedAt!==null : s.archivedAt===null).sort((a,b)=>b.updatedAt-a.updatedAt)) {
      const row=element('div',undefined,'session-row'+(s.id===state.selected?' selected':''));
      const choose=button('',()=>{ if(s.archivedAt!==null)command({op:'archive',sessionId:s.id,archived:false}); else{command({op:'select',sessionId:s.id});drawer(false);} },'session-select');
      choose.append(element('strong',s.title));
      const running=active()?.sessionId===s.id;
      choose.append(element('small',(running?'● 运行中 · ':'')+new Date(s.updatedAt).toLocaleString('zh-CN',{month:'numeric',day:'numeric',hour:'2-digit',minute:'2-digit'})));
      const actions=element('div',undefined,'session-actions');
      actions.append(button('重命名',()=>modal('重命名会话','标题仅在本地保存。',s.title,title=>command({op:'rename',sessionId:s.id,title}))));
      actions.append(button(s.archivedAt!==null?'恢复':'归档',()=>command({op:'archive',sessionId:s.id,archived:s.archivedAt===null})));
      actions.append(button('删除',()=>modal('删除会话','将删除该会话的消息、运行、工具事件和附件引用。活动运行需先停止。连接资料独立保留。',null,()=>command({op:'delete',sessionId:s.id}))));
      row.append(choose,actions);list.append(row);
    }
    $('archives').textContent=showArchives?'返回最近会话':'查看归档';
  }
  function renderCapabilities() {
    const s=selected(); if(!s)return;
    $('attach').textContent=s.pendingAttachments?.length ? `附图 · ${s.pendingAttachments.length}` : '附图';
    $('capabilities').textContent=`能力 · ${Object.values(s.capabilities).filter(Boolean).length} 项 ⌄`;
    const panel=$('cap-panel');panel.replaceChildren();
    for(const [cap,label] of Object.entries(labels)) {
      const row=element('label',undefined,'cap-row'), check=element('input');check.type='checkbox';check.checked=s.capabilities[cap];check.setAttribute('aria-label',label);
      check.addEventListener('change',()=>{
        const enabled=check.checked;
        const apply=()=>command({op:'capability',sessionId:s.id,capability:cap,enabled});
        if(enabled && (cap==='debug'||cap==='screen_control')) {
          check.checked=false;
          modal(`开启${label}`,cap==='debug'?'开启后，Agent 可运行设备 Shell 并访问系统允许的文件。Shell 可能截图、操作屏幕或联网，因此其他能力开关无法限制 Shell 的这些行为。部分应用私有文件仍可能无法访问。\n\n每次 Shell 或配置写入仍需批准具体操作。':'屏幕操作可能影响上屏应用和焦点，需系统授权与设备验证。只有已校准且授权的目标可执行触摸；手柄后端尚不可用。',null,apply);
        } else apply();
      });
      const text=element('span',label);text.append(element('small',`${s.capabilities[cap]?'意愿：已选择':'意愿：未选择'} · ${state.availability[cap]}`));row.append(check,text);panel.append(row);
    }
    panel.append(element('p','选择按会话保存。运行中撤销会停止当前运行；重新开启只授权新的运行。模型配置需从设置完成实际图片/工具探测。'));
    panel.append(button('打开 Native 设置',()=>command({op:'settings'}),'wide'));
  }
  function runView(run) {
    const events=state.events.filter(e=>e.runId===run.id).sort((a,b)=>a.sequence-b.sequence);
    const container=element('div');
    // Actionable summaries live outside the collapsed disclosure, including unknown side effects.
    if(run.stopReason)container.append(element('div',run.stopReason,'attention'));
    const unknown=events.filter(e=>e.outcome==='unknown' && (e.type==='tool_result' || !events.some(r=>r.type==='tool_result' && r.toolCallId===e.toolCallId)));
    for(const e of unknown)container.append(element('div',`副作用未知 · ${e.summary}`,'attention'));
    if(run.status==='awaiting_user') {
      const waiting=events.findLast(e=>e.type==='awaiting_user');
      const card=element('div',run.approval?.summary??waiting?.summary??'等待用户','wait');
      card.append(document.createElement('br'),button('批准此操作',()=>command({op:'approve',runId:run.id,approvalId:run.approval?.id}),'primary'),button('拒绝',()=>command({op:'reject',runId:run.id,approvalId:run.approval?.id})));container.append(card);
    }
    const disclosure=element('details',undefined,'run');disclosure.open=run.expanded;
    const count=events.filter(e=>e.type==='tool_started').length;
    disclosure.append(element('summary',`${statuses[run.status]??run.status} · ${count} 项工具操作`));
    for(const event of events) {
      const result=event.type==='tool_started'?events.find(e=>e.type==='tool_result'&&e.toolCallId===event.toolCallId):null;
      const outcome=result?.outcome??event.outcome;
      const summary=result?event.summary.replace('模拟执行中',`已结束 · ${statuses[outcome]??'副作用未知'}`):event.summary;
      const card=element('div',summary,'event');card.append(element('small',`#${event.sequence} · ${outcome||event.type} · ${(event.elapsedMs/1000).toFixed(1)}s`));
      if(event.toolCallId) {
        const detail=element('details');detail.append(element('summary','事件详情'),element('pre',JSON.stringify({toolId:event.toolId,toolCallId:event.toolCallId,capability:event.capability,outcome:event.outcome},null,2)));card.append(detail);
      }
      disclosure.append(card);
    }
    disclosure.addEventListener('toggle',()=> { if(disclosure.isConnected && disclosure.open!==run.expanded)command({op:'expanded',runId:run.id,expanded:disclosure.open}); });
    container.append(disclosure);return container;
  }
  function renderChat() {
    const chat=$('chat'), bottom=chat.scrollHeight-chat.clientHeight-chat.scrollTop<80, oldTop=chat.scrollTop;
    chat.replaceChildren();
    const messages=state.messages.filter(m=>m.sessionId===state.selected);
    if(!messages.length)chat.append(element('div','从一个问题开始。\n请配置模型并完成图片与工具探测。','empty'));
    for(const message of messages) {
      const card=element('article',undefined,`message ${message.role}`);card.append(element('div',message.role==='user'?'你':'Thor · Pi Agent','role'));
      if(message.role==='assistant'&&message.runId){ const run=state.runs.find(r=>r.id===message.runId);if(run)card.append(runView(run)); }
      card.append(element('div',message.text || (message.status==='streaming'?'正在准备回答…':'没有产生回答文本'),'text'));
      for(const aid of message.attachments??[]) { const a=state.attachments?.find(a=>a.id===aid); card.append(element('small',a ? `本地附件 · ${a.size} 字节 · ${Date.now()>a.expiresAt?'已过期':'本地保存'} · SHA-256 ${a.hash.slice(0,12)}` : '附件引用不可用')); }
      chat.append(card);
    }
    chat.scrollTop=bottom?chat.scrollHeight:oldTop;
  }
  window.receiveNative = next => {
    if(next.fatal) { $('notice').hidden=false; $('notice').textContent=next.fatal; $('send').disabled=true; return; }
    const switched=state?.selected!==next.selected;state=next;
    $('title').textContent=selected()?.title??'Thor Agent';
    $('notice').hidden=!next.error&&!next.modelStatus; $('notice').textContent=next.error??next.modelStatus??'';
    const running=active();const banner=$('active');banner.hidden=!running;banner.replaceChildren();
    if(running){const s=state.sessions.find(s=>s.id===running.sessionId);banner.append(element('span',`活动：${s?.title} · ${statuses[running.status]}（切会话不会停止）`),button('停止',()=>command({op:'stop',runId:running.id})));}
    $('send').textContent=running?'停止':'发送';
    renderSessions();renderCapabilities();renderChat();if(switched)$('chat').scrollTop=$('chat').scrollHeight;
  };
  $('attach').addEventListener('click',()=>command({op:'pickImage'}));
  $('composer').addEventListener('submit',event=>{
    event.preventDefault();if(!state)return;
    if(active()){ $('send').textContent='正在停止'; command({op:'stop',runId:active().id});return; }
    const text=$('input').value.trim();if(!text)return;
    command({op:'send',sessionId:state.selected,text,scenario:$('scenario').value});$('input').value='';$('cap-panel').hidden=true;
  });
  $('menu').addEventListener('click',()=>drawer(true));$('close-drawer').addEventListener('click',()=>drawer(false));$('shade').addEventListener('click',()=>drawer(false));
  for(const id of ['new','drawer-new'])$(id).addEventListener('click',()=>{command({op:'new'});drawer(false);});
  for(const id of ['settings','drawer-settings'])$(id).addEventListener('click',()=>command({op:'settings'}));
  $('archives').addEventListener('click',()=>{showArchives=!showArchives;renderSessions();});
  $('seed').addEventListener('click',()=>modal('加载长历史示例','向当前会话添加 200 条模拟消息，并新建 49 个会话。此操作会保存到本地，可逐个删除。',null,()=>command({op:'seed'})));
  $('capabilities').addEventListener('click',()=>{const open=$('cap-panel').hidden;$('cap-panel').hidden=!open;$('capabilities').setAttribute('aria-expanded',String(open));});
  document.addEventListener('keydown',event=>{if(event.key==='Escape'){drawer(false);$('cap-panel').hidden=true;}});
  command({op:'ready'});
})();
