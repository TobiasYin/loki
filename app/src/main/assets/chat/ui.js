'use strict';
(() => {
  const $ = id => document.getElementById(id);
  let state = null, showArchives = false, modalCallback = null;
  const labels = {perception:'屏幕感知',search:'联网搜索',screen_control:'屏幕操作',debug:'ADB 调试'};
  const statuses = {preparing:'正在准备',generating:'正在回答',executing_tools:'工具执行中',awaiting_user:'等待用户批准',stopping:'正在停止',completed:'已完成',failed:'失败',cancelled:'已取消',interrupted:'已中断'};
  const terminal = new Set(['completed','failed','cancelled','interrupted']);
  const command = payload => {
    if(window.ThorUI) window.ThorUI.postMessage(JSON.stringify(payload));
    else { $('notice').textContent = '浏览器预览没有 Native 桥；请运行 Android APK。'; $('notice').hidden = false; }
  };
  const element = (tag, text, className) => { const n=document.createElement(tag); if(text !== undefined)n.textContent=text; if(className)n.className=className; return n; };
  const button = (text, action, className) => { const n=element('button',text,className); n.type='button'; n.addEventListener('click',action); return n; };
  const selected = () => state?.sessions.find(s=>s.id===state.selected);
  const active = () => (state?.activeRuns??state?.runs??[]).find(r=>r.sessionId===state?.selected && !terminal.has(r.status));
  const preparing=new Map();
  const drafts=new Map(),sectionSignatures=new Map();
  function changedSection(name,value){const signature=JSON.stringify([state?.selected,value]);if(sectionSignatures.get(name)===signature)return false;sectionSignatures.set(name,signature);return true;}
  function drawer(open) { $('drawer').hidden=!open; $('shade').hidden=!open; $('menu').setAttribute('aria-expanded',String(open)); if(open)$('close-drawer').focus(); else $('menu').focus(); }
  function modal(title,text,initial,callback) {
    $('modal-title').textContent=title; $('modal-text').textContent=text;
    $('modal-input').hidden=initial===null; $('modal-input').value=initial??''; modalCallback=callback;
    $('modal').showModal(); if(initial!==null)$('modal-input').focus();
  }
  $('modal').addEventListener('close',()=>{ const callback=modalCallback; modalCallback=null; if($('modal').returnValue==='ok')callback?.($('modal-input').value); });
  let sessionsSignature='';
  function sessionMenu(id) {
    const s=state.sessions.find(s=>s.id===id);if(!s)return;
    $('session-menu-title').textContent=s.title;const actions=$('session-menu-actions');actions.replaceChildren();
    const closeThen=action=>()=>{$('session-menu').close();action();};
    actions.append(button('重命名',closeThen(()=>modal('重命名会话','标题仅在本地保存。',s.title,title=>command({op:'rename',sessionId:s.id,title}))),'wide'));
    actions.append(button(s.archivedAt!==null?'恢复':'归档',closeThen(()=>command({op:'archive',sessionId:s.id,archived:s.archivedAt===null})),'wide'));
    actions.append(button('删除会话',closeThen(()=>modal('删除会话','将删除本会话的消息和附件。活动任务需先停止。',null,()=>command({op:'delete',sessionId:s.id}))),'wide danger'));
    $('session-menu').showModal();
  }
  $('session-menu-close').addEventListener('click',()=>$('session-menu').close());
  function renderSessions() {
    const visible=[...state.sessions].filter(s=>showArchives?s.archivedAt!==null:s.archivedAt===null).sort((a,b)=>b.updatedAt-a.updatedAt);
    const signature=JSON.stringify([state.selected,showArchives,visible.map(s=>[s.id,s.title,s.archivedAt]),(state.activeRuns??state.runs).filter(r=>!terminal.has(r.status)).map(r=>r.sessionId)]);
    if(signature===sessionsSignature)return;sessionsSignature=signature;
    const list=$('sessions');list.replaceChildren();
    for(const s of visible) {
      const row=element('div',undefined,'session-row'+(s.id===state.selected?' selected':''));row.dataset.sessionId=s.id;
      let timer=null,origin=null,suppressClick=false;
      const choose=button('',()=>{if(suppressClick){suppressClick=false;return;}if(s.archivedAt!==null)command({op:'archive',sessionId:s.id,archived:false});else{command({op:'select',sessionId:s.id});drawer(false);}},'session-select');
      choose.append(element('strong',s.title));choose.setAttribute('aria-label',s.title);choose.setAttribute('aria-haspopup','dialog');
      const running=(state.activeRuns??state.runs).some(r=>r.sessionId===s.id && !terminal.has(r.status));
      choose.classList.toggle('session-running',running);if(running)choose.setAttribute('aria-description','任务运行中');
      const cancel=()=>{clearTimeout(timer);timer=null;};
      choose.addEventListener('pointerdown',e=>{if(e.button>0 || e.isPrimary===false)return;suppressClick=false;origin=[e.clientX,e.clientY];timer=setTimeout(()=>{timer=null;suppressClick=true;sessionMenu(s.id);},550);});
      choose.addEventListener('pointermove',e=>{if(origin && Math.hypot(e.clientX-origin[0],e.clientY-origin[1])>10)cancel();});
      for(const type of ['pointerup','pointercancel','pointerleave'])choose.addEventListener(type,cancel);
      choose.addEventListener('contextmenu',e=>{e.preventDefault();cancel();suppressClick=true;sessionMenu(s.id);});
      choose.addEventListener('keydown',e=>{if(e.key==='ContextMenu' || (e.shiftKey&&e.key==='F10')){e.preventDefault();cancel();sessionMenu(s.id);}});
      row.append(choose);list.append(row);
    }
    $('archives').textContent=showArchives?'返回最近会话':'查看归档';
  }
  const capabilityPending=new Map(),capabilityConfirmed=new Map(),capabilityErrors=new Map(),capabilityErrorIds=new Map();let capabilityMutation=0;
  const capabilityKey=(sid,cap)=>`${sid}:${cap}`;
  function reconcileCapabilities(next) {
    for(const session of next.sessions??[]) {
      const revision=Number.isSafeInteger(session.capabilityRevision)?session.capabilityRevision:0;
      const saved=capabilityConfirmed.get(session.id);
      if(!saved || revision>=saved.revision)capabilityConfirmed.set(session.id,{revision,values:{...session.capabilities}});
    }
    for(const ack of next.capabilityAcks??[]) {
      if(!ack?.sessionId || !labels[ack.capability] || !ack.clientMutationId)continue;
      const key=capabilityKey(ack.sessionId,ack.capability),pending=capabilityPending.get(key);
      const saved=capabilityConfirmed.get(ack.sessionId)??{revision:0,values:{}};
      const revision=Number.isSafeInteger(ack.capabilityRevision)?ack.capabilityRevision:saved.revision;
      if(revision>=saved.revision){saved.revision=revision;saved.values[ack.capability]=!!ack.enabled;capabilityConfirmed.set(ack.sessionId,saved);}
      if(pending?.id!==ack.clientMutationId){
        if(!pending && ack.ok && capabilityErrorIds.get(key)===ack.clientMutationId && revision>=saved.revision){capabilityErrors.delete(key);capabilityErrorIds.delete(key);}
        continue;
      }
      capabilityPending.delete(key);
      if(ack.ok){capabilityErrors.delete(key);capabilityErrorIds.delete(key);}
      else {capabilityErrors.set(key,({STORAGE_FAILURE:'更改未保存，已恢复原设置。',INVALID_ARGUMENT:'能力设置未生效，请重试。'})[ack.error]||ack.error||'更改未保存，已恢复原设置。');capabilityErrorIds.set(key,ack.clientMutationId);}
    }
  }
  function effectiveCapabilities(session) {
    const values={...(capabilityConfirmed.get(session?.id)?.values??session?.capabilities??{})};
    for(const cap of Object.keys(labels)){const pending=capabilityPending.get(capabilityKey(session?.id,cap));if(pending)values[cap]=pending.enabled;}
    return values;
  }
  function requestCapability(session,cap,enabled) {
    const key=capabilityKey(session.id,cap),id=`cap-${Date.now().toString(36)}-${++capabilityMutation}`;
    capabilityPending.set(key,{id,enabled});capabilityErrors.delete(key);capabilityErrorIds.delete(key);renderCapabilities();
    command({op:'capability',sessionId:session.id,capability:cap,enabled,clientMutationId:id});
  }
  window.receiveCapabilityFailure=command=>{
    const key=capabilityKey(command?.sessionId,command?.capability),pending=capabilityPending.get(key);
    if(!pending || pending.id!==command?.clientMutationId)return;
    capabilityPending.delete(key);capabilityErrors.set(key,'保存结果未确认，已显示上次确认的选择。');capabilityErrorIds.set(key,pending.id);renderCapabilities();
  };
  function renderCapabilities() {
    const s=selected();if(!s)return;const values=effectiveCapabilities(s);
    const mutations=Object.keys(labels).map(cap=>{const key=capabilityKey(s.id,cap);return [cap,capabilityPending.get(key),capabilityErrors.get(key)];});
    if(!changedSection('capabilities',[values,s.pendingAttachments,state.availability,mutations]))return;
    $('attach').textContent=s.pendingAttachments?.length ? `附图 · ${s.pendingAttachments.length}` : '附图';
    $('capabilities').textContent=`◇ 能力 ${Object.values(values).filter(Boolean).length} ⌄`;
    const panel=$('cap-panel');const focus=panel.contains(document.activeElement)?document.activeElement?.getAttribute('aria-label'):null;panel.replaceChildren();
    for(const [cap,label] of Object.entries(labels)) {
      const key=capabilityKey(s.id,cap),pending=capabilityPending.get(key),error=capabilityErrors.get(key);
      const row=element('label',undefined,'cap-row'),check=element('input');check.type='checkbox';check.checked=!!values[cap];check.setAttribute('aria-label',label);
      if(pending)check.setAttribute('aria-busy','true');
      check.addEventListener('change',()=>{
        const enabled=check.checked;
        const apply=()=>requestCapability(s,cap,enabled);
        if(enabled && (cap==='debug'||cap==='screen_control')) {
          check.checked=!!effectiveCapabilities(s)[cap];
          modal(`开启${label}`,cap==='debug'?'开启后，Agent 可运行经过分级审核的设备命令：白名单命令及亮度、风扇、刷新率等限定范围的常用调整直接执行；涉及隐私或按键分配等灰名单操作逐条确认；抹除、格式化、刷机和未审查命令禁止执行。完整操作手册可在 Skill 管理中查看。配置文件写入仍需单独批准。':'屏幕操作可能影响上屏应用和焦点。执行前会校验目标屏幕、系统授权与设备支持情况。',null,apply);
        } else apply();
      });
      const text=element('span',label);text.append(element('small',`${values[cap]?'意愿：已选择':'意愿：未选择'} · ${state.availability[cap]}`));
      if(pending || error){const status=element('small',pending?'正在保存…':error,pending?'cap-pending':'cap-error');status.setAttribute('role','status');text.append(status);}
      row.append(check,text);panel.append(row);
    }
    panel.append(button('打开设置',()=>{cancelVoice();refreshVoice();command({op:'settings'});},'wide'));
    if(focus)[...panel.querySelectorAll('input')].find(node=>node.getAttribute('aria-label')===focus)?.focus();
  }
  const disclosures=new Map(),runDisclosures=new Map(),disclosureControllers=new WeakMap(),movingDisclosures=new Set();
  const reducedMotion=window.matchMedia?.('(prefers-reduced-motion: reduce)');
  // Keep a visible middle phase when frames are sparse, without delaying input
  // reversal or the reduced-motion path.
  const disclosureTiming={duration:250,easing:'cubic-bezier(.4,0,.2,1)',fill:'both'};
  function bindDisclosure(node,key,initial,renderOpen=()=>{},onChoice=()=>{}) {
    node.dataset.disclosureKey=key;
    const summary=node.querySelector('summary');let desired=!!initial,expectedOpen=desired,animation=null,version=0;
    const writeOpen=open=>{expectedOpen=open;node.open=open;};
    const mark=()=>{node.dataset.expanded=String(desired);summary.setAttribute('aria-expanded',String(desired));};
    function cancelAnimation(){version++;if(animation){animation.onfinish=null;animation.cancel();animation=null;}movingDisclosures.delete(controller);delete node.dataset.disclosureAnimating;node.style.height='';node.style.overflow='';}
    function settle(){cancelAnimation();writeOpen(desired);renderOpen(desired);mark();}
    function change(open,{fromHeight,notify=true,force=false}={}) {
      open=!!open;if(open===desired && !force){if(!animation){writeOpen(open);renderOpen(open);}return;}
      const wasOpen=desired;
      const measured=node.getBoundingClientRect().height;
      cancelAnimation();desired=open;mark();
      // Native details retains its semantics; only closing is delayed until the
      // last animation frame so its children remain available to animate out.
      writeOpen(true);renderOpen(true);
      const full=node.getBoundingClientRect().height;
      writeOpen(false);const collapsed=node.getBoundingClientRect().height;writeOpen(true);
      const start=fromHeight??(!wasOpen && open?measured:measured>collapsed+.5?measured:full);
      const end=open?full:collapsed;
      if(!reducedMotion?.matches && typeof node.animate==='function' && node.isConnected && full>collapsed+.5 && Math.abs(end-start)>.5) {
        node.style.overflow='hidden';node.dataset.disclosureAnimating='true';const token=version;
        animation=node.animate([{height:`${start}px`},{height:`${end}px`}],disclosureTiming);
        movingDisclosures.add(controller);animation.onfinish=()=>{if(token===version)settle();};
      }else settle();
      if(notify)onChoice(desired);
    }
    const controller={settle,pause(){const snapshot=animation?{key,height:node.getBoundingClientRect().height,open:desired}:null;settle();return snapshot;},resume(snapshot){if(snapshot.open===desired)change(desired,{fromHeight:snapshot.height,notify:false,force:true});}};
    disclosureControllers.set(node,controller);
    writeOpen(desired);mark();renderOpen(desired);
    summary.addEventListener('click',event=>{event.preventDefault();change(!desired);});
    node.addEventListener('toggle',event=>{if(event.target===node && node.isConnected && node.open!==expectedOpen)change(node.open);});
    return node;
  }
  reducedMotion?.addEventListener?.('change',()=>{if(reducedMotion.matches)for(const controller of [...movingDisclosures])controller.settle();});
  function pauseDisclosures(parent){return [...parent.querySelectorAll('details[data-disclosure-key]')].reverse().map(node=>disclosureControllers.get(node)?.pause()).filter(Boolean).reverse();}
  function resumeDisclosures(parent,snapshots){for(const snapshot of snapshots){const node=[...parent.querySelectorAll('details[data-disclosure-key]')].find(n=>n.dataset.disclosureKey===snapshot.key);if(node)disclosureControllers.get(node)?.resume(snapshot);}}
  function rememberDisclosure(node,key,initial,renderOpen) {
    return bindDisclosure(node,key,disclosures.has(key)?disclosures.get(key):initial,renderOpen,open=>disclosures.set(key,open));
  }
  const fieldNames={downloadId:'下载文件编号',filename:'文件名',sha256:'SHA-256 校验值',size:'字节数',finalUrl:'下载地址',mime:'文件类型',apk:'应用信息',packageName:'应用包名',versionName:'版本',versionCode:'版本编号',signerSha256:'签名指纹',requestedPermissions:'声明权限',downloadedAt:'下载时间',downloads:'下载记录',installed:'已安装',apkPath:'安装路径',headers:'响应头',method:'请求方法',screen:'目标屏幕',selectedScreen:'当前屏幕',displayRole:'屏幕角色',displayId:'显示编号',displayName:'显示名称',brightness:'亮度',before:'修改前',requested:'请求值',quantizationTolerance:'量化容差',unit:'单位',verified:'回读一致',policyVersion:'策略版本',policyTier:'安全等级',reason:'原因',requiresApproval:'需要确认',allowed:'允许提交',executed:'已执行',white:'白名单示例',gray:'灰名单示例',black:'禁止执行',execution:'执行规则',stdout:'输出',stderr:'错误输出',exitCode:'退出码',uid:'执行身份',command:'具体命令',query:'搜索词',url:'网址',name:'名称',path:'路径',text:'内容',content:'正文',message:'说明',results:'结果',title:'标题',summary:'摘要',arguments:'参数',enabled:'启用',expectedHash:'预期版本',hash:'版本',resources:'附带资料',source:'来源',observedAt:'观测时间',fetchedAt:'读取时间',error:'错误',untrusted:'外部资料',status:'状态',description:'简介',skills:'技能',builtin:'内置',fileCount:'文件数'};
  function structured(value,depth=0,brightnessInput=false) {
    if(value===null || value===undefined)return element('span','无','muted');
    if(typeof value==='string' && depth<7 && /^[\s]*[\[{]/.test(value)) {try {return structured(JSON.parse(value),depth+1,brightnessInput);}catch{}}
    if(typeof value!=='object')return element('div',typeof value==='boolean'?(value?'是':'否'):String(value),'text');
    if(depth>=7)return element('div','内容层级过深，请展开更具体的结果','muted');
    const list=element('dl',undefined,'structured');
    // Units belong to this object only. Legacy results without unit=percent keep
    // plain values, and another tool's generic value/before fields stay untouched.
    const percentFields=new Set(value.unit==='percent'?['before','brightness','requested','quantizationTolerance']:brightnessInput?['value']:[]);
    for(const [key,entry] of Object.entries(value).slice(0,80)) {
      list.append(element('dt',Array.isArray(value)?`第 ${Number(key)+1} 项`:(fieldNames[key]??key)));
      const row=element('dd');
      if(percentFields.has(key) && Number.isInteger(entry) && entry>=0 && entry<=100)row.append(element('div',`${entry}%`,'text'));
      else row.append(structured(entry,depth+1));
      list.append(row);
    }
    return list;
  }
  function runView(run,runEvents,message) {
    const events=(runEvents??state.events.filter(e=>e.runId===run.id)).slice().sort((a,b)=>a.sequence-b.sequence);
    const container=element('div');
    const toolLabels={'web.request':'网络请求 · curl','files.download':'下载文件','files.download_info':'查看下载','apps.install':'安装应用','apps.launch':'打开应用','agent.background':'聊天转入后台','apps.package_info':'核验应用安装','skills.list':'查看 Skill 列表','skills.read':'读取 Skill','skills.save':'保存 Skill','skills.enable':'调整 Skill 启用状态','skills.delete':'删除 Skill','web.search':'搜索网页','web.fetch':'读取网页','perception.capture_screen':'查看当前屏幕','perception.select_screen':'选择操作屏幕','perception.capture_companion':'查看同应用下屏','perception.find_apps':'查找应用','perception.read_ui':'读取界面','perception.ui_tree':'读取组件树','input.scroll':'滚动页面','input.drag':'拖动控件','input.tap':'点击当前屏幕','input.long_press':'长按当前屏幕','input.swipe':'滑动当前屏幕','input.activate':'激活界面控件','input.navigate':'当前屏幕返回','input.type_text':'输入文字','input.key_press':'按键盘键','input.press_button':'按手柄键','input.set_axes':'移动摇杆与扳机','input.release_inputs':'释放输入','device.info':'读取设备信息','perception.device_info':'读取设备信息','debug.shell':'运行设备指令','debug.command_policy':'检查命令安全等级','debug.thor_brightness':'调整 Thor 独立屏幕亮度'};
    const outcomeLabels={observed:'已完成',submitted:'已提交',unknown:'结果未知'};
    const calls=new Map(),resultIds=new Set();
    for(const event of events) {
      if(!['tool_call_update','tool_intent','tool_started','tool_result'].includes(event.type))continue;
      const id=event.toolCallId??`event:${event.sequence}`;
      const current=calls.get(id)??{id};const data=event.data??{};
      Object.assign(current,{toolId:data.toolId??event.toolId??current.toolId,toolName:data.toolName??current.toolName});
      if(event.type==='tool_call_update')current.arguments=data.arguments;
      if(event.type==='tool_started'){current.started=true;current.arguments=data.arguments??current.arguments;}
      if(event.type==='tool_result'){current.result=data;current.outcome=data.outcome??event.outcome;current.error=data.error??event.error;if(event.toolCallId)resultIds.add(event.toolCallId);}
      calls.set(id,current);
    }
    const title=call=>toolLabels[call.toolId]??toolLabels[call.toolName?.replace('_','.')]??call.toolId??call.toolName??'工具';
    // Actionable summaries never depend on opening an operation history.
    if(['failed','cancelled','interrupted'].includes(run.status) && run.stopReason)container.append(element('div',run.stopReason,run.status==='cancelled'?'timeline-note':'attention'));
    else if(run.status==='failed')container.append(element('div','这次任务未能完成，请重试或检查设置。','attention'));
    const unknown=events.filter(e=>(e.data?.outcome??e.outcome)==='unknown' && (e.data?.error??e.error)!=='TARGET_CHANGED' && (e.type==='tool_result' || (['failed','cancelled','interrupted'].includes(run.status) && !resultIds.has(e.toolCallId))));
    for(const e of unknown)container.append(element('div',`副作用未知 · ${toolLabels[e.toolId??e.data?.toolId]??e.toolId??e.data?.toolId??'工具操作'}，请检查目标状态`,'attention'));
    const recoveryFact=call=>call.outcome==='unknown' && call.result?.recovery?.sideEffectPossible!==false && call.result?.recovery?.submission!=='not_dispatched'?'先前操作的提交状态尚未确认，请勿重复旧动作':call.result?.recovery?.submission==='submitted' || call.outcome==='submitted'?'先前操作已提交，需观察结果':'';
    // Recovery is one current Run state, not one perpetual status per old error.
    // A result event exists only after Runtime's post-authority checks. Companion
    // reads/selection/discarded or failed reads cannot qualify a fresh target.
    const currentScreenReads=new Set(['perception.app_info','perception.window_info','perception.ui_tree','perception.capture_screen']);
    let recoverySeen=false,needsObservation=false;
    for(const event of events)if(event.type==='tool_result') {
      const data=event.data??{},error=data.error??event.error,toolId=data.toolId??event.toolId??calls.get(event.toolCallId)?.toolId;
      if(error==='TARGET_CHANGED'){recoverySeen=true;needsObservation=true;}
      else if(recoverySeen && !error && !data.contentDiscarded && (data.outcome??event.outcome)==='observed') {
        if(currentScreenReads.has(toolId))needsObservation=false;
        else if(toolId==='perception.select_screen')needsObservation=true;
      }
    }
    const recoveryCalls=[...calls.values()].filter(call=>call.error==='TARGET_CHANGED');
    const uncertainRecovery=recoveryCalls.find(call=>call.outcome==='unknown' && recoveryFact(call));
    const recoveryStatus=needsObservation?(terminal.has(run.status)?'此前页面发生变化，需以新观察决定后续操作':run.status==='awaiting_user'?'页面已变化，需重新观察':'页面已变化，正在重新观察'):'';
    const fact=uncertainRecovery?recoveryFact(uncertainRecovery):needsObservation?recoveryFact(recoveryCalls.at(-1)??{}):'';
    if(recoveryStatus || fact) {
      const note=element('div',[recoveryStatus,fact].filter(Boolean).join('；')+'。','timeline-note recovery-status');note.setAttribute('role','status');container.append(note);
    }
    for(const call of calls.values())if(call.error && call.error!=='TARGET_CHANGED')container.append(element('div',`${title(call)} · ${call.error==='COMMAND_BLOCKED'?'已禁止':'执行失败'}，可展开操作详情查看原因`,'attention'));
    if(run.status==='awaiting_user') {
      const waiting=events.findLast(e=>e.type==='awaiting_user');
      const card=element('div',run.approval?.summary??waiting?.summary??'等待用户','wait');
      card.append(document.createElement('br'),button('批准此操作',()=>command({op:'approve',runId:run.id,approvalId:run.approval?.id}),'primary'),button('拒绝',()=>command({op:'reject',runId:run.id,approvalId:run.approval?.id})));container.append(card);
    }
    const thinkingLimit=65536;
    let thinkingLength=0,thinkingTruncated=message?.thinkingTruncated===true;
    for(const event of events)if(event.type==='thinking_delta') {
      if(typeof event.data?.text==='string')thinkingLength+=event.data.text.length;
      if(event.data?.textTruncated===true)thinkingTruncated=true;
    }
    thinkingTruncated ||= thinkingLength>thinkingLimit;
    if(thinkingLength) {
      const thought=element('details',undefined,'thinking');
      thought.append(element('summary',terminal.has(run.status)?'思考过程':'思考中','activity-label'+(!terminal.has(run.status)?' shimmer':'')));
      let body=null;
      rememberDisclosure(thought,`${run.id}:thinking`,false,open=>{
        if(!open){body?.remove();body=null;return;}
        if(body)return;
        // Fold only the visible tail and only when opened. Collapsed history
        // never concatenates the complete ledger or builds hidden text nodes.
        const parts=[];let remaining=thinkingLimit;
        for(let i=events.length-1;i>=0 && remaining>0;i--)if(events[i].type==='thinking_delta' && typeof events[i].data?.text==='string') {
          const part=events[i].data.text.slice(-remaining);parts.push(part);remaining-=part.length;
        }
        let text=parts.reverse().join('');
        if(thinkingTruncated && text.charCodeAt(0)>=0xdc00 && text.charCodeAt(0)<=0xdfff)text=text.slice(1);
        body=element('div',undefined,'thinking-body');
        if(thinkingTruncated)body.append(element('small','仅显示最近的思考内容','muted thinking-truncation'));
        body.append(element('div',text,'text'));thought.append(body);
      });
      container.append(thought);
    }
    const disclosure=element('details',undefined,'run');
    disclosure.append(element('summary',`查看 ${calls.size} 项操作`));
    const last=events.findLast(e=>e.type==='diagnostic');
    let contents=null;
    function renderCallBody(card,call) {
      let body=null;
      rememberDisclosure(card,`${run.id}:tool:${call.id}`,false,open=>{
        if(!open){body?.remove();body=null;return;}
        if(body)return;
        body=element('div',undefined,'tool-body');
        // Parsing and recursive structured DOM construction happen only inside
        // the one tool the user actually opened, never for collapsed history.
        const brightnessCall=call.toolId==='debug.thor_brightness' || call.toolName==='debug_thor_brightness';
        const percentInput=brightnessCall && ((!call.result && !terminal.has(run.status)) || (call.result?.content??[]).some(c=>{if(c.type!=='text')return false;try{return JSON.parse(c.text)?.unit==='percent';}catch{return false;}}));
        if(call.arguments && Object.keys(call.arguments).length){body.append(element('small','输入'));body.append(structured(call.arguments,0,percentInput));}
        if(call.result) {
          if(call.error)body.append(element('div',call.error==='TARGET_CHANGED'?'此操作因页面变化被拒绝，后续需依据新观察。':`失败原因：${call.error}`,call.error==='TARGET_CHANGED'?'timeline-note':'attention'));
          if(call.error==='TARGET_CHANGED' && recoveryFact(call))body.append(element('div',recoveryFact(call)+'。','timeline-note'));
          for(const content of call.result.content??[]) {
            if(content.type==='attachment')body.append(element('div','已返回本地图片附件','muted'));
            else if(content.type==='text') {
              let value=content.text;try{value=JSON.parse(value);}catch{if(/^[\s]*[\[{]/.test(value))value='结构化结果不完整，请请求更小的结果或重试。';}
              if(call.error==='TARGET_CHANGED' && value?.recovery?.kind==='observe_again')continue;
              body.append(structured(value));
            }
          }
          if(call.result.truncated)body.append(element('small','结果已按长度限制截断'));
          if(call.result.source)body.append(element('small',`来源：${call.result.source}`));
        }
        card.append(body);
      });
    }
    function renderOperations(open) {
      if(!open){contents?.remove();contents=null;return;}
      if(contents)return;
      contents=element('div',undefined,'tool-list');
      for(const call of calls.values()) {
        const card=element('details',undefined,'tool-card');
        const status=call.error==='TARGET_CHANGED'?'需要重新观察':call.error==='COMMAND_BLOCKED'?'已禁止':call.error?'执行失败':call.result?(outcomeLabels[call.outcome]??'已完成'):call.started?'执行中':'正在准备';
        card.append(element('summary',`${title(call)} · ${status}`));
        renderCallBody(card,call);contents.append(card);
      }
      if(last)contents.append(element('div',last.data?.code==='TARGET_CHANGED'?'页面已变化，需重新观察。':`运行提示：${last.data?.code??'工具需要检查'}`,'timeline-note'));
      disclosure.append(contents);
    }
    // Native persists the outer choice. Keep an immediate local choice while
    // awaiting its acknowledgement; a changed Native value or terminal transition
    // still applies its explicit history-collapse policy.
    const nativeExpanded=!!run.expanded;
    let remembered=runDisclosures.get(run.id);
    if(!remembered || remembered.nativeExpanded!==nativeExpanded || (!terminal.has(remembered.status)&&terminal.has(run.status))) {
      remembered={open:nativeExpanded,nativeExpanded,status:run.status};runDisclosures.set(run.id,remembered);
    }else remembered.status=run.status;
    bindDisclosure(disclosure,`${run.id}:run`,remembered.open,renderOperations,open=>{
      remembered.open=open;
      const current=state?.runs.find(r=>r.id===run.id)??run;
      if(open!==!!current.expanded)command({op:'expanded',runId:run.id,expanded:open});
    });
    if(!terminal.has(run.status) && run.status!=='awaiting_user') {
      const latest=[...calls.values()].at(-1);
      container.append(element('div',latest?title(latest):'正在处理','activity-label shimmer'));
    }
    if(calls.size || last)container.append(disclosure);return container;
  }
  // HTML from messages is treated as text. Markdown images remain text too:
  // simply reading a message must never trigger an external image request.
  const escapeHtml=value=>String(value).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const markdownRenderer=window.marked?new window.marked.Renderer():null;
  if(markdownRenderer){markdownRenderer.html=token=>escapeHtml(token.text);markdownRenderer.image=token=>escapeHtml(token.text||'图片');}
  function markdown(text) {
    const body=element('div',undefined,'markdown');
    if(!window.marked || !window.DOMPurify){body.classList.add('text');body.textContent=text;return body;}
    body.innerHTML=window.DOMPurify.sanitize(window.marked.parse(text,{renderer:markdownRenderer,gfm:true,breaks:true}),{
      ALLOWED_TAGS:['p','br','strong','em','del','blockquote','ul','ol','li','h1','h2','h3','h4','h5','h6','pre','code','hr','table','thead','tbody','tr','th','td','a'],ALLOWED_ATTR:['href','title','start','align'],ALLOW_DATA_ATTR:false
    });
    for(const link of body.querySelectorAll('a')){
      let url;try{url=new URL(link.getAttribute('href'));}catch{link.removeAttribute('href');continue;}
      if(!['https:','http:'].includes(url.protocol)){link.removeAttribute('href');continue;}
      link.rel='noopener noreferrer';link.addEventListener('click',event=>{event.preventDefault();command({op:'openLink',url:url.href});});
    }
    return body;
  }
  const messageNodes=new Map();let renderedSession=null;
  function renderChat() {
    const chat=$('chat'),bottom=chat.scrollHeight-chat.clientHeight-chat.scrollTop<80,oldTop=chat.scrollTop;
    if(renderedSession!==state.selected){pauseDisclosures(chat);chat.replaceChildren();messageNodes.clear();renderedSession=state.selected;}
    const runs=new Map(state.runs.map(r=>[r.id,r])),events=new Map();
    for(const event of state.events){if(!events.has(event.runId))events.set(event.runId,[]);events.get(event.runId).push(event);}
    const messages=state.messages.filter(m=>m.sessionId===state.selected && !runs.get(m.runId)?.probe),keep=new Set(messages.map(m=>m.id));
    for(const [id,entry] of messageNodes)if(!keep.has(id)){entry.card.remove();messageNodes.delete(id);}
    chat.querySelector('.empty')?.remove();if(!messages.length)chat.append(element('div','今天想做什么？','empty'));
    let previous=null;
    for(const message of messages) {
      let entry=messageNodes.get(message.id);
      if(!entry){const card=element('article',undefined,`message ${message.role}`),runSlot=element('div'),textSlot=element('div'),attachments=element('div',undefined,'message-attachments');card.append(element('div',message.role==='user'?'你':'Thor · Pi Agent','role'),runSlot,textSlot,attachments);entry={card,runSlot,textSlot,attachments};messageNodes.set(message.id,entry);}
      const run=runs.get(message.runId),runSignature=message.role==='assistant'&&run?JSON.stringify([run,events.get(run.id),message.thinkingTruncated===true]):'';
      if(entry.runSignature!==runSignature){
        const focused=document.activeElement;
        const focusKey=entry.runSlot.contains(focused) && focused.tagName==='SUMMARY'?focused.parentElement.dataset.disclosureKey:null;
        const transitions=pauseDisclosures(entry.runSlot);entry.runSlot.replaceChildren();
        if(runSignature)entry.runSlot.append(runView(run,events.get(run.id),message));resumeDisclosures(entry.runSlot,transitions);
        if(focusKey){const target=[...entry.runSlot.querySelectorAll('details[data-disclosure-key]')].find(node=>node.dataset.disclosureKey===focusKey);(target?.querySelector('summary')??entry.runSlot.querySelector('details.run > summary'))?.focus({preventScroll:true});}
        entry.runSignature=runSignature;
      }
      if(entry.text!==message.text || entry.textTruncated!==(message.textTruncated===true)){
        entry.textSlot.replaceChildren(message.role==='assistant'?markdown(message.text||''):element('div',message.text||'','text'));
        if(message.textTruncated===true)entry.textSlot.append(element('small','消息内容较长，仅显示最近一段。','muted message-truncation'));
        entry.text=message.text;entry.textTruncated=message.textTruncated===true;
      }
      const attachmentsSignature=JSON.stringify((message.attachments??[]).map(id=>state.attachments?.find(a=>a.id===id)??id));
      if(entry.attachmentsSignature!==attachmentsSignature){entry.attachments.replaceChildren();for(const aid of message.attachments??[]){const a=state.attachments?.find(a=>a.id===aid);entry.attachments.append(element('small',a?`本地附件 · ${a.size} 字节 · ${Date.now()>a.expiresAt?'已过期':'本地保存'} · SHA-256 ${a.hash.slice(0,12)}`:'附件引用不可用'));}entry.attachmentsSignature=attachmentsSignature;}
      const expected=previous?previous.nextSibling:chat.firstChild;if(expected!==entry.card)chat.insertBefore(entry.card,expected);previous=entry.card;
    }
    chat.scrollTop=bottom?chat.scrollHeight:oldTop;
  }
  window.receiveNative = next => {
    if(next.fatal) { $('notice').hidden=false; $('notice').textContent=next.fatal; $('send').disabled=true; return; }
    reconcileCapabilities(next);const switched=state?.selected!==next.selected;if(switched && state)drafts.set(state.selected,$('input').value);state=next;if(switched)$('input').value=drafts.get(next.selected)??'';
    $('title').textContent=selected()?.title??'Thor Agent';
    $('notice').hidden=!next.error; $('notice').textContent=next.error??'';
    $('active').hidden=true;$('active').replaceChildren();refreshComposerState();
    renderSessions();renderCapabilities();renderChat();renderSkills();renderModelPicker();renderSkillSelection();refreshVoice();if(switched)$('chat').scrollTop=$('chat').scrollHeight;
  };
  function refreshComposerState(){
    const running=active(),pending=preparing.get(state?.selected);const stopping=!!running||!!pending;
    $('send').textContent=stopping?'停止':'发送';$('send').dataset.running=String(stopping);$('send').setAttribute('aria-label',stopping?'停止当前任务':'发送');
    $('preparation-status').hidden=!pending || !!running;
    $('preparation-status').textContent=pending?({checking:'正在准备任务…',probing:'首次连接：正在验证模型…',starting:'正在启动任务…'}[pending.phase]??'正在准备任务…'):'';
  }
  window.receivePreparationState=next=>{
    if(!next?.sessionId)return;if(next.phase==='idle')preparing.delete(next.sessionId);else if(['checking','probing','starting'].includes(next.phase))preparing.set(next.sessionId,next);
    refreshComposerState();refreshVoice();
  };
  const effortNames={off:'默认',minimal:'最少',low:'低',medium:'中',high:'高',xhigh:'很高',max:'最高'};
  // The composer uses in-page listboxes; no native select/system popup is
  // involved. Keep the original IDs as stable component/test identifiers.
  function activePickerOption(list,option){list.setAttribute('aria-activedescendant',option.id);for(const item of list.querySelectorAll('[role=option]'))item.classList.toggle('picker-active',item===option);}
  const pickerPanel=$('model-panel');pickerPanel.replaceChildren();pickerPanel.setAttribute('role','dialog');pickerPanel.setAttribute('aria-label','模型与思考强度');
  for(const [id,label] of [['model-select','模型'],['effort-select','思考强度']]) {
    const title=element('div',label,'picker-label');title.id=id+'-label';
    const list=element('div',undefined,'picker-listbox');list.id=id;list.tabIndex=0;list.setAttribute('role','listbox');list.setAttribute('aria-labelledby',title.id);
    pickerPanel.append(title,list);
    list.addEventListener('keydown',event=>{
      const options=[...list.querySelectorAll('[role=option]')];if(!options.length)return;
      let index=Math.max(0,options.findIndex(option=>option.id===list.getAttribute('aria-activedescendant')));
      if(event.key==='ArrowDown')index=Math.min(index+1,options.length-1);
      else if(event.key==='ArrowUp')index=Math.max(index-1,0);
      else if(event.key==='Home')index=0;else if(event.key==='End')index=options.length-1;
      else if(event.key==='Enter' || event.key===' '){event.preventDefault();options[index].click();return;}
      else if(event.key==='Escape'){event.preventDefault();event.stopPropagation();closePopovers();$('model-picker').focus();return;}else return;
      event.preventDefault();activePickerOption(list,options[index]);options[index].scrollIntoView?.({block:'nearest'});
    });
  }
  const modelContext=element('div',undefined,'muted');modelContext.id='model-context';pickerPanel.append(modelContext);
  $('model-picker').setAttribute('aria-haspopup','dialog');$('model-picker').setAttribute('aria-controls','model-panel');
  function fillPicker(id,items,value,choose) {
    const list=$(id),focused=list.contains(document.activeElement),oldActive=document.getElementById(list.getAttribute('aria-activedescendant'))?.dataset.value;
    list.replaceChildren();list.dataset.value=value??'';
    for(const [index,item] of items.entries()) {
      const option=button(item.label,()=>{
        list.dataset.value=item.value;activePickerOption(list,option);
        for(const sibling of list.querySelectorAll('[role=option]'))sibling.setAttribute('aria-selected',String(sibling===option));
        choose(item.value);list.focus();
      },'picker-option');
      option.id=`${id}-option-${index}`;option.dataset.value=item.value;option.tabIndex=-1;option.setAttribute('role','option');option.setAttribute('aria-selected',String(item.value===value));list.append(option);
    }
    if(!items.length)list.append(element('div','请先在设置中配置模型','muted'));
    list.setAttribute('aria-disabled',String(!items.length));list.tabIndex=items.length?0:-1;
    const active=[...list.querySelectorAll('[role=option]')].find(option=>option.dataset.value===(focused?oldActive:value))??list.querySelector('[role=option]');
    if(active)activePickerOption(list,active);else list.removeAttribute('aria-activedescendant');
    if(focused)list.focus();
  }
  function renderModelPicker() {
    if(!changedSection('model',[state.modelSelection,state.modelChoices]))return;
    const profile=state.modelSelection??{};
    fillPicker('model-select',(state.modelChoices??[]).map(model=>({value:model.id,label:model.name??model.id})),profile.modelId,modelId=>command({op:'sessionOptions',sessionId:state.selected,modelId}));
    fillPicker('effort-select',(profile.efforts??['off']).map(value=>({value,label:effortNames[value]??value})),profile.thinkingEffort??'off',thinkingEffort=>command({op:'sessionOptions',sessionId:state.selected,thinkingEffort}));
    $('model-picker').textContent=`${profile.label||'选择模型'}${profile.thinkingEffort && profile.thinkingEffort!=='off'?' · '+effortNames[profile.thinkingEffort]:''} ⌄`;
    $('model-context').textContent=profile.contextWindow?`上下文 ${Intl.NumberFormat('en',{notation:'compact'}).format(profile.contextWindow)} tokens`:'';
  }
  function renderSkillSelection() {
    if(!changedSection('skill-selection',[state.skills,selected()?.selectedSkills]))return;
    const list=$('skill-selection');list.replaceChildren();const s=selected();if(!s)return;
    for(const skill of state.skills??[]) {
      const row=element('label',undefined,'cap-row');const input=element('input');input.type='checkbox';input.checked=skill.enabled && (skill.builtin || !s.selectedSkills || s.selectedSkills.includes(skill.name));input.disabled=skill.builtin || !skill.enabled;
      input.addEventListener('change',()=>{const selectedNames=(state.skills??[]).filter(k=>!k.builtin && k.enabled && (s.selectedSkills?s.selectedSkills.includes(k.name):true)).map(k=>k.name);command({op:'sessionOptions',sessionId:s.id,selectedSkills:input.checked?[...new Set([...selectedNames,skill.name])]:selectedNames.filter(n=>n!==skill.name)});});
      const text=element('span',skill.name);text.append(element('small',skill.builtin?'内置':skill.description));row.append(input,text);list.append(row);
    }
  }
  function fitPopovers() {const available=Math.max(44,Math.min(innerHeight*.65,$('composer').getBoundingClientRect().top-12));for(const id of ['more-panel','model-panel','skill-panel','cap-panel'])$(id).style.maxHeight=available+'px';}
  function closePopovers(){for(const [panel,anchor] of [['more-panel','more'],['model-panel','model-picker'],['skill-panel','skill-choose'],['cap-panel','capabilities']]){$(panel).hidden=true;$(anchor).setAttribute('aria-expanded','false');}}
  function popover(id) {$('input').blur();const open=$(id).hidden;closePopovers();$(id).hidden=!open;const anchor={'more-panel':'more','model-panel':'model-picker','skill-panel':'skill-choose','cap-panel':'capabilities'}[id];$(anchor).setAttribute('aria-expanded',String(open));fitPopovers();if(open && id==='model-panel')$('model-select').focus();}
  window.addEventListener('resize',fitPopovers);
  $('more').addEventListener('click',()=>{popover('more-panel');$('more').setAttribute('aria-expanded',String(!$('more-panel').hidden));});
  $('model-picker').addEventListener('click',()=>popover('model-panel'));
  $('skill-choose').addEventListener('click',()=>popover('skill-panel'));
  function renderSkills() {
    if(!changedSection('skills',[state.skills,state.skillDetail,!!active()]))return;
    const list=$('skills-list');list.replaceChildren();
    for(const skill of state.skills??[]) {
      const row=element('div',undefined,'skill-row');row.append(element('strong',skill.name),element('p',skill.description),element('small',`${skill.builtin?'内置':'已导入 / 已创建'} · ${skill.enabled?'已启用':'已停用'} · ${skill.fileCount} 个文件`));
      const read=button('查看',()=>command({op:'skillRead',name:skill.name}));read.disabled=!!active();row.append(read);
      const toggle=button(skill.enabled?'停用':'启用',()=>command({op:'skillEnable',name:skill.name,hash:skill.hash,enabled:!skill.enabled}));toggle.disabled=!!active();row.append(toggle);
      if(!skill.builtin) {const remove=button('删除',()=>modal('删除 Skill',`将删除 ${skill.name} 及其附带资料。`,null,()=>command({op:'skillDelete',name:skill.name,hash:skill.hash})));remove.disabled=!!active();row.append(remove);}
      list.append(row);
    }
    $('skills-import').disabled=!!active();
    const detail=state.skillDetail;$('skill-detail').hidden=!detail;
    if(detail) {
      $('skill-name').textContent=detail.name;$('skill-content').textContent=detail.content;
      const resources=$('skill-resource');resources.replaceChildren();
      for(const path of detail.resources??[]) {const option=element('option',path);option.value=path;resources.append(option);}resources.value=detail.path;
    }
  }
  $('skills-open').addEventListener('click',()=>{drawer(false);$('more-panel').hidden=true;$('skills-dialog').showModal();});
  $('skills-close').addEventListener('click',()=>$('skills-dialog').close());
  $('skills-dialog').addEventListener('close',()=>command({op:'skillClose'}));
  $('skills-import').addEventListener('click',()=>{cancelVoice();command({op:'skillImport'});});
  $('skill-resource').addEventListener('change',()=>command({op:'skillRead',name:state.skillDetail.name,path:$('skill-resource').value}));
  let voiceMode=false,hold=null,voiceText='',voicePhase='idle',voiceError='',submitted=new Set();
  function refreshVoice() {
    if(hold && (hold.sessionId!==state?.selected || active()))cancelVoice();
    $('input').hidden=voiceMode;$('voice-panel').hidden=!voiceMode;
    $('voice-toggle').innerHTML=voiceMode?'⌨':'<svg width=20 height=24 viewBox="0 0 24 24" fill=none stroke=currentColor stroke-width=1.8 aria-hidden=true><rect x=8 y=2 width=8 height=13 rx=4></rect><path d="M5 10v2a7 7 0 0 0 14 0v-2M12 19v3M8 22h8"></path></svg>';
    $('voice-toggle').setAttribute('aria-pressed',String(voiceMode));$('voice-toggle').setAttribute('aria-label',voiceMode?'切换到文字输入':'切换到语音输入');
    const holding=!!hold&&!hold.released;
    $('voice-hold').disabled=!!active() || preparing.has(state?.selected) || voicePhase==='finalizing';
    $('voice-hold').classList.toggle('holding',holding);$('voice-hold').setAttribute('aria-pressed',String(holding));
    $('voice-hold').textContent=holding?'松手发送 · 上滑取消':voicePhase==='finalizing'?'正在完成识别…':'按住说话';
    $('voice-overlay').hidden=!holding;$('voice-overlay').classList.toggle('cancel-pending',!!hold?.cancelPending);
    $('voice-instruction').textContent=hold?.cancelPending?'松手取消，滑回继续':'松手发送，上滑取消';
    $('voice-status').textContent=voiceError||({finalizing:'正在完成识别…',cancelled:'已取消'}[voicePhase]??'');
    $('voice-status').hidden=!$('voice-status').textContent || holding;
    // Keep interim and successful transcripts out of the composer. A failure
    // offers an explicit draft recovery action without sending partial speech.
    $('voice-transcript').textContent='';$('voice-transcript').hidden=true;
    $('voice-cancel').hidden=!holding;$('voice-edit').hidden=!voiceText || !!hold || !voiceError;
    $('send').hidden=voiceMode && !active() && !preparing.has(state?.selected);
  }
  function cancelVoice() {
    const wasHolding=!!hold;
    if(hold)command({op:'voice.cancel',id:hold.id,sessionId:hold.sessionId});
    hold=null;if(wasHolding)voicePhase='idle';voiceError='';
  }
  function changeVoiceMode() {
    cancelVoice();voiceMode=!voiceMode;
    if(voiceMode){$('input').blur();command({op:'voice.permission'});}
    refreshVoice();if(!voiceMode)$('input').focus();
  }
  $('voice-toggle').addEventListener('click',changeVoiceMode);
  $('voice-hold').addEventListener('contextmenu',e=>e.preventDefault());
  function beginVoice(event) {
    if(!state || active() || preparing.has(state.selected) || hold || voicePhase==='finalizing')return;
    if(event.type==='pointerdown' && (event.isPrimary===false || event.button>0))return;
    event.preventDefault();voiceText='';voiceError='';voicePhase='connecting';
    hold={id:window.crypto.randomUUID(),sessionId:state.selected,pointerId:event.pointerId,key:event.key,y:event.clientY,released:false,cancelPending:false,cancelDistance:Math.max(120,Math.min(240,innerHeight*.4))};
    // Capture is an optimization; document-level release/cancel still owns the
    // gesture if WebView drops capture or this API is unavailable.
    if(event.pointerId!==undefined)try{$('voice-hold').setPointerCapture?.(event.pointerId);}catch{}
    command({op:'voice.start',id:hold.id,sessionId:hold.sessionId});refreshVoice();
  }
  function finishVoice(event) {
    if(!hold || hold.released)return;
    if(event.type==='pointerup' ? event.pointerId!==hold.pointerId : hold.pointerId!==undefined || event.key!==hold.key)return;
    event.preventDefault();if(hold.cancelPending){cancelVoice();refreshVoice();return;}hold.released=true;voicePhase='finalizing';command({op:'voice.finish',id:hold.id,sessionId:hold.sessionId});refreshVoice();
  }
  $('voice-hold').addEventListener('pointerdown',beginVoice);
  document.addEventListener('pointerup',finishVoice);
  document.addEventListener('pointermove',event=>{if(hold && !hold.released && event.pointerId===hold.pointerId){const pending=hold.y-event.clientY>=hold.cancelDistance;if(hold.cancelPending!==pending){hold.cancelPending=pending;refreshVoice();}}});
  document.addEventListener('pointercancel',event=>{if(hold && !hold.released && event.pointerId===hold.pointerId){cancelVoice();refreshVoice();}});
  $('voice-hold').addEventListener('keydown',event=>{if([' ','Enter'].includes(event.key) && !event.repeat)beginVoice(event);});
  $('voice-hold').addEventListener('keyup',event=>{if([' ','Enter'].includes(event.key))finishVoice(event);});
  $('voice-cancel').addEventListener('click',()=>{cancelVoice();refreshVoice();});
  $('voice-edit').addEventListener('click',()=>{cancelVoice();voiceMode=false;$('input').value=voiceText;refreshVoice();$('input').focus();});

  document.addEventListener('visibilitychange',()=>{if(document.hidden){cancelVoice();refreshVoice();}});
  window.addEventListener('blur',()=>{cancelVoice();refreshVoice();});
  window.addEventListener('pagehide',()=>{cancelVoice();});
  window.receiveVoiceState=next=>{
    if(!hold || next.id!==hold.id || next.sessionId!==hold.sessionId || next.sessionId!==state?.selected)return;
    voiceText=typeof next.text==='string'?next.text:voiceText;voicePhase=next.phase;voiceError=next.error??'';
    if(next.phase==='complete' && hold.released && !submitted.has(next.id) && typeof next.submit==='string' && next.submit.trim() && !active()) {
      submitted.add(next.id);if(submitted.size>100)submitted.delete(submitted.values().next().value);
      command({op:'send',sessionId:hold.sessionId,text:next.submit.trim()});hold=null;voicePhase='idle';voiceText='';voiceError='';
    } else if(next.phase==='empty') {
      hold=null;voicePhase='idle';voiceText='';voiceError='';
    } else if(['error','cancelled'].includes(next.phase))hold=null;
    refreshVoice();
  };
  window.receiveSendFailure=(text,sessionId=state?.selected)=>{drafts.set(sessionId,text);if(sessionId===state?.selected){voiceMode=false;$('input').value=text;voiceError='发送未完成，文字已恢复到输入框';refreshVoice();}};
  $('attach').addEventListener('click',()=>{$('more-panel').hidden=true;command({op:'pickImage'});});
  $('composer').addEventListener('submit',event=>{
    event.preventDefault();if(!state)return;
    if(active()){ $('send').textContent='停止'; command({op:'stop',runId:active().id});return; }
    if(preparing.has(state.selected)){command({op:'stopPreparing',sessionId:state.selected});return;}
    const text=$('input').value.trim();if(!text)return;
    command({op:'send',sessionId:state.selected,text,scenario:$('scenario').value});drafts.delete(state.selected);$('input').value='';$('cap-panel').hidden=true;
  });
  $('menu').addEventListener('click',()=>{cancelVoice();refreshVoice();drawer(true);});$('close-drawer').addEventListener('click',()=>drawer(false));$('shade').addEventListener('click',()=>drawer(false));
  for(const id of ['new','drawer-new'])$(id).addEventListener('click',()=>{command({op:'new'});drawer(false);});
  for(const id of ['settings','drawer-settings'])$(id).addEventListener('click',()=>{cancelVoice();refreshVoice();command({op:'settings'});});
  $('archives').addEventListener('click',()=>{showArchives=!showArchives;renderSessions();});
  $('seed').addEventListener('click',()=>modal('加载长历史示例','向当前会话添加 200 条模拟消息，并新建 49 个会话。此操作会保存到本地，可逐个删除。',null,()=>command({op:'seed'})));
  $('capabilities').addEventListener('click',()=>{popover('cap-panel');$('capabilities').setAttribute('aria-expanded',String(!$('cap-panel').hidden));});
  document.addEventListener('keydown',event=>{if(event.key==='Escape'){if(hold){cancelVoice();refreshVoice();}drawer(false);closePopovers();}});
  document.addEventListener('click',e=>{if(!e.target.closest('footer'))closePopovers();});
  command({op:'ready'});
})();
