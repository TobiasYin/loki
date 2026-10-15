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
  const active = () => (state?.activeRuns??state?.runs??[]).find(r=>r.sessionId===state?.selected && !terminal.has(r.status));
  const drafts=new Map();
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
      row.dataset.sessionId=s.id;
      const choose=button('',()=>{ if(s.archivedAt!==null)command({op:'archive',sessionId:s.id,archived:false}); else{command({op:'select',sessionId:s.id});drawer(false);} },'session-select');
      choose.append(element('strong',s.title));
      const running=(state.activeRuns??state.runs).some(r=>r.sessionId===s.id && !terminal.has(r.status));
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
    $('capabilities').textContent=`◇ 能力 ${Object.values(s.capabilities).filter(Boolean).length} ⌄`;
    const panel=$('cap-panel');panel.replaceChildren();
    for(const [cap,label] of Object.entries(labels)) {
      const row=element('label',undefined,'cap-row'), check=element('input');check.type='checkbox';check.checked=s.capabilities[cap];check.setAttribute('aria-label',label);
      check.addEventListener('change',()=>{
        const enabled=check.checked;
        const apply=()=>command({op:'capability',sessionId:s.id,capability:cap,enabled});
        if(enabled && (cap==='debug'||cap==='screen_control')) {
          check.checked=false;
          modal(`开启${label}`,cap==='debug'?'开启后，Agent 可运行经过分级审核的设备命令：白名单命令及亮度、风扇、刷新率等限定范围的常用调整直接执行；涉及隐私或按键分配等灰名单操作逐条确认；抹除、格式化、刷机和未审查命令禁止执行。完整操作手册可在 Skill 管理中查看。配置文件写入仍需单独批准。':'屏幕操作可能影响上屏应用和焦点，需系统授权与设备验证。只有已校准且授权的目标可执行触摸；手柄后端尚不可用。',null,apply);
        } else apply();
      });
      const text=element('span',label);text.append(element('small',`${s.capabilities[cap]?'意愿：已选择':'意愿：未选择'} · ${state.availability[cap]}`));row.append(check,text);panel.append(row);
    }

    panel.append(button('打开设置',()=>{cancelVoice();refreshVoice();command({op:'settings'});},'wide'));
  }
  const disclosures=new Map();
  function rememberDisclosure(node,key,initial) {
    node.open=disclosures.has(key)?disclosures.get(key):initial;
    node.querySelector('summary').addEventListener('click',()=>disclosures.set(key,!node.open));
    return node;
  }
  const fieldNames={downloadId:'下载文件编号',filename:'文件名',sha256:'SHA-256 校验值',size:'字节数',finalUrl:'下载地址',mime:'文件类型',apk:'应用信息',packageName:'应用包名',versionName:'版本',versionCode:'版本编号',signerSha256:'签名指纹',requestedPermissions:'声明权限',downloadedAt:'下载时间',downloads:'下载记录',installed:'已安装',apkPath:'安装路径',headers:'响应头',method:'请求方法',screen:'目标屏幕',displayId:'显示编号',displayName:'显示名称',brightness:'亮度（0–255）',before:'修改前',requested:'请求值',verified:'回读一致',policyVersion:'策略版本',policyTier:'安全等级',reason:'原因',requiresApproval:'需要确认',allowed:'允许提交',executed:'已执行',white:'白名单示例',gray:'灰名单示例',black:'禁止执行',execution:'执行规则',stdout:'输出',stderr:'错误输出',exitCode:'退出码',uid:'执行身份',command:'具体命令',query:'搜索词',url:'网址',name:'名称',path:'路径',text:'内容',content:'正文',message:'说明',results:'结果',title:'标题',summary:'摘要',arguments:'参数',enabled:'启用',expectedHash:'预期版本',hash:'版本',resources:'附带资料',source:'来源',observedAt:'观测时间',fetchedAt:'读取时间',error:'错误',untrusted:'外部资料',status:'状态',description:'简介',skills:'技能',builtin:'内置',fileCount:'文件数'};
  function structured(value,depth=0) {
    if(value===null || value===undefined)return element('span','无','muted');
    if(typeof value==='string' && depth<7 && /^[\s]*[\[{]/.test(value)) {try {return structured(JSON.parse(value),depth+1);}catch{}}
    if(typeof value!=='object')return element('div',typeof value==='boolean'?(value?'是':'否'):String(value),'text');
    if(depth>=7)return element('div','内容层级过深，请展开更具体的结果','muted');
    const list=element('dl',undefined,'structured');
    for(const [key,entry] of Object.entries(value).slice(0,80)) {
      list.append(element('dt',Array.isArray(value)?`第 ${Number(key)+1} 项`:(fieldNames[key]??key)));
      const row=element('dd');row.append(structured(entry,depth+1));list.append(row);
    }
    return list;
  }
  function runView(run) {
    const events=state.events.filter(e=>e.runId===run.id).sort((a,b)=>a.sequence-b.sequence);
    const container=element('div');
    // Actionable summaries live outside the collapsed disclosure, including unknown side effects.
    if(run.stopReason)container.append(element('div',run.stopReason,'attention'));
    else if(run.status==='failed')container.append(element('div','这次任务未能完成，请重试或检查设置。','attention'));
    const unknown=events.filter(e=>e.outcome==='unknown' && (e.type==='tool_result' || !events.some(r=>r.type==='tool_result' && r.toolCallId===e.toolCallId)));
    for(const e of unknown)container.append(element('div',`副作用未知 · ${e.toolId??e.data?.toolId??'工具操作'}，请检查目标状态`,'attention'));
    if(run.status==='awaiting_user') {
      const waiting=events.findLast(e=>e.type==='awaiting_user');
      const card=element('div',run.approval?.summary??waiting?.summary??'等待用户','wait');
      card.append(document.createElement('br'),button('批准此操作',()=>command({op:'approve',runId:run.id,approvalId:run.approval?.id}),'primary'),button('拒绝',()=>command({op:'reject',runId:run.id,approvalId:run.approval?.id})));container.append(card);
    }
    const thinking=events.filter(e=>e.type==='thinking_delta').map(e=>e.data?.text??'').join('');
    if(thinking) {
      const thought=element('details',undefined,'thinking');thought.open=false;
      thought.append(element('summary',terminal.has(run.status)?'思考过程':'思考中','activity-label'+(!terminal.has(run.status)?' shimmer':'')),element('div',thinking,'text'));rememberDisclosure(thought,`${run.id}:thinking`,false);container.append(thought);
    }
    const disclosure=element('details',undefined,'run');disclosure.open=run.expanded;
    const count=events.filter(e=>e.type==='tool_started').length;
    disclosure.append(element('summary',`查看 ${count} 项操作`));
    const calls=new Map();
    const toolLabels={'web.request':'网络请求 · curl','files.download':'下载文件','files.download_info':'查看下载','apps.install':'安装应用','apps.package_info':'核验应用安装','skills.list':'查看 Skill 列表','skills.read':'读取 Skill','skills.save':'保存 Skill','skills.enable':'调整 Skill 启用状态','skills.delete':'删除 Skill','web.search':'搜索网页','web.fetch':'读取网页','perception.capture_screen':'查看上屏','perception.read_ui':'读取界面','device.info':'读取设备信息','perception.device_info':'读取设备信息','debug.shell':'运行设备指令','debug.command_policy':'检查命令安全等级','debug.thor_brightness':'调整 Thor 独立屏幕亮度'};
    const outcomeLabels={observed:'已完成',submitted:'已提交',unknown:'结果未知'};
    for(const event of events) {
      if(!['tool_call_update','tool_intent','tool_started','tool_result'].includes(event.type))continue;
      const id=event.toolCallId??`event:${event.sequence}`;
      const current=calls.get(id)??{id};const data=event.data??{};
      Object.assign(current,{toolId:data.toolId??event.toolId??current.toolId,toolName:data.toolName??current.toolName});
      if(event.type==='tool_call_update')current.arguments=data.arguments;
      if(event.type==='tool_started'){current.started=true;current.arguments=data.arguments??current.arguments;}
      if(event.type==='tool_result'){current.result=data;current.outcome=data.outcome??event.outcome;current.error=data.error??event.error;}
      calls.set(id,current);
    }
    for(const call of calls.values()) {
      const card=element('details',undefined,'tool-card');
      const title=toolLabels[call.toolId]??call.toolId??call.toolName??'工具';
      const status=call.error==='COMMAND_BLOCKED'?'已禁止':call.error?'执行失败':call.result?(outcomeLabels[call.outcome]??'已完成'):call.started?'执行中':'正在准备';
      card.append(element('summary',`${title} · ${status}`));
      if(call.arguments && Object.keys(call.arguments).length) {card.append(element('small','输入'));card.append(structured(call.arguments));}
      if(call.result) {
        if(call.error)card.append(element('div',`失败原因：${call.error}`,'attention'));
        for(const content of call.result.content??[]) {
          if(content.type==='attachment')card.append(element('div','已返回本地图片附件','muted'));
          else if(content.type==='text') {
            let value=content.text;try {value=JSON.parse(value);}catch{if(/^[\s]*[\[{]/.test(value))value='结构化结果不完整，请请求更小的结果或重试。';}
            card.append(structured(value));
          }
        }
        if(call.result.truncated)card.append(element('small','结果已按长度限制截断'));
        if(call.result.source)card.append(element('small',`来源：${call.result.source}`));
      }
      rememberDisclosure(card,`${run.id}:tool:${call.id}`,false);disclosure.append(card);
    }
    const last=events.findLast(e=>e.type==='diagnostic');if(last)disclosure.append(element('div',`运行提示：${last.data?.code??'工具需要检查'}`,'timeline-note'));
    disclosure.addEventListener('toggle',()=> { if(disclosure.isConnected && disclosure.open!==run.expanded)command({op:'expanded',runId:run.id,expanded:disclosure.open}); });
    if(!terminal.has(run.status) && run.status!=='awaiting_user') {
      const latest=[...calls.values()].at(-1);
      container.append(element('div',latest?(toolLabels[latest.toolId]??latest.toolName??'处理任务'):'正在处理','activity-label shimmer'));
    }
    if(calls.size || last)container.append(disclosure);return container;
  }
  function renderChat() {
    const chat=$('chat'), bottom=chat.scrollHeight-chat.clientHeight-chat.scrollTop<80, oldTop=chat.scrollTop;
    chat.replaceChildren();
    const messages=state.messages.filter(m=>m.sessionId===state.selected);
    if(!messages.length)chat.append(element('div','今天想做什么？','empty'));
    for(const message of messages) {
      if(state.runs.find(r=>r.id===message.runId)?.probe)continue;
      const card=element('article',undefined,`message ${message.role}`);card.append(element('div',message.role==='user'?'你':'Thor · Pi Agent','role'));
      if(message.role==='assistant'&&message.runId){ const run=state.runs.find(r=>r.id===message.runId);if(run)card.append(runView(run)); }
      card.append(element('div',message.text||'','text'));
      for(const aid of message.attachments??[]) { const a=state.attachments?.find(a=>a.id===aid); card.append(element('small',a ? `本地附件 · ${a.size} 字节 · ${Date.now()>a.expiresAt?'已过期':'本地保存'} · SHA-256 ${a.hash.slice(0,12)}` : '附件引用不可用')); }
      chat.append(card);
    }
    chat.scrollTop=bottom?chat.scrollHeight:oldTop;
  }
  window.receiveNative = next => {
    if(next.fatal) { $('notice').hidden=false; $('notice').textContent=next.fatal; $('send').disabled=true; return; }
    const switched=state?.selected!==next.selected;if(switched && state)drafts.set(state.selected,$('input').value);state=next;if(switched)$('input').value=drafts.get(next.selected)??'';
    $('title').textContent=selected()?.title??'Thor Agent';
    $('notice').hidden=!next.error; $('notice').textContent=next.error??'';
    const running=active();$('active').hidden=true;$('active').replaceChildren();
    $('send').textContent=running?'停止':'发送';$('send').dataset.running=String(!!running);$('send').setAttribute('aria-label',running?'停止当前任务':'发送');
    renderSessions();renderCapabilities();renderChat();renderSkills();renderModelPicker();renderSkillSelection();refreshVoice();if(switched)$('chat').scrollTop=$('chat').scrollHeight;
  };
  const effortNames={off:'默认',minimal:'最少',low:'低',medium:'中',high:'高',xhigh:'很高',max:'最高'};
  function renderModelPicker() {
    const profile=state.modelSelection??{};const picker=$('model-select');picker.replaceChildren();
    for(const model of state.modelChoices??[]) {const o=element('option',model.name??model.id);o.value=model.id;picker.append(o);}picker.value=profile.modelId??'';
    const effort=$('effort-select');effort.replaceChildren();for(const e of profile.efforts??['off']){const o=element('option',effortNames[e]??e);o.value=e;effort.append(o);}effort.value=profile.thinkingEffort??'off';
    $('model-picker').textContent=`${profile.label||'选择模型'}${profile.thinkingEffort && profile.thinkingEffort!=='off'?' · '+effortNames[profile.thinkingEffort]:''} ⌄`;
    $('model-context').textContent=profile.contextWindow?`上下文 ${Intl.NumberFormat('en',{notation:'compact'}).format(profile.contextWindow)} tokens`:'';
  }
  function renderSkillSelection() {
    const list=$('skill-selection');list.replaceChildren();const s=selected();if(!s)return;
    for(const skill of state.skills??[]) {
      const row=element('label',undefined,'cap-row');const input=element('input');input.type='checkbox';input.checked=skill.enabled && (skill.builtin || !s.selectedSkills || s.selectedSkills.includes(skill.name));input.disabled=skill.builtin || !skill.enabled;
      input.addEventListener('change',()=>{const selectedNames=(state.skills??[]).filter(k=>!k.builtin && k.enabled && (s.selectedSkills?s.selectedSkills.includes(k.name):true)).map(k=>k.name);command({op:'sessionOptions',sessionId:s.id,selectedSkills:input.checked?[...new Set([...selectedNames,skill.name])]:selectedNames.filter(n=>n!==skill.name)});});
      const text=element('span',skill.name);text.append(element('small',skill.builtin?'内置':skill.description));row.append(input,text);list.append(row);
    }
  }
  function fitPopovers() {const available=Math.max(44,Math.min(innerHeight*.65,$('composer').getBoundingClientRect().top-12));for(const id of ['more-panel','model-panel','skill-panel','cap-panel'])$(id).style.maxHeight=available+'px';}
  function popover(id) {$('input').blur();const open=$(id).hidden;for(const p of ['more-panel','model-panel','skill-panel','cap-panel'])$(p).hidden=true;$(id).hidden=!open;fitPopovers();}
  window.addEventListener('resize',fitPopovers);
  $('more').addEventListener('click',()=>{popover('more-panel');$('more').setAttribute('aria-expanded',String(!$('more-panel').hidden));});
  $('model-picker').addEventListener('click',()=>popover('model-panel'));
  $('skill-choose').addEventListener('click',()=>popover('skill-panel'));
  $('model-select').addEventListener('change',()=>command({op:'sessionOptions',sessionId:state.selected,modelId:$('model-select').value}));
  $('effort-select').addEventListener('change',()=>command({op:'sessionOptions',sessionId:state.selected,thinkingEffort:$('effort-select').value}));
  function renderSkills() {
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
    $('voice-toggle').innerHTML=voiceMode?'⌨':'<svg width=20 height=24 viewBox="0 0 24 24" fill=none stroke=currentColor stroke-width=1.8 aria-hidden=true><rect x=8 y=2 width=8 height=13 rx=4></rect><path d="M5 10v2a7 7 0 0 0 14 0v-2M12 19v3M8 22h8"></path></svg>';$('voice-toggle').setAttribute('aria-pressed',String(voiceMode));$('voice-toggle').setAttribute('aria-label',voiceMode?'切换到文字输入':'切换到语音输入');
    $('voice-hold').disabled=!!active() || voicePhase==='finalizing';
    $('voice-hold').classList.toggle('holding',!!hold && ['connecting','recording'].includes(voicePhase));
    $('voice-hold').textContent=['connecting','recording'].includes(voicePhase)?'松手发送 · 上滑取消':voicePhase==='finalizing'?'正在完成识别…':'按住说话';
    $('voice-status').textContent=voiceError || ({connecting:'正在连接语音服务…',recording:'正在听 · 识别文字会实时更新',finalizing:'已松手，等待最终结果…',complete:'已发送',empty:'未识别到文字，请重试',cancelled:'已取消'}[voicePhase]??'按住说话，松手发送；上滑取消');
    $('voice-transcript').textContent=voiceText;$('voice-cancel').hidden=!hold;$('voice-edit').hidden=!voiceText || !!hold;
    $('send').hidden=voiceMode && !active();
  }
  function cancelVoice() {
    const wasHolding=!!hold;
    if(hold)command({op:'voice.cancel',id:hold.id,sessionId:hold.sessionId});
    hold=null;if(wasHolding)voicePhase='cancelled';voiceError='';
  }
  function changeVoiceMode() {
    cancelVoice();voiceMode=!voiceMode;
    if(voiceMode){$('input').blur();command({op:'voice.permission'});}
    refreshVoice();if(!voiceMode)$('input').focus();
  }
  $('voice-toggle').addEventListener('click',changeVoiceMode);
  $('voice-hold').addEventListener('contextmenu',e=>e.preventDefault());
  function beginVoice(event) {
    if(!state || active() || hold || voicePhase==='finalizing')return;
    if(event.type==='pointerdown' && (event.isPrimary===false || event.button>0))return;
    event.preventDefault();voiceText='';voiceError='';voicePhase='connecting';
    hold={id:window.crypto.randomUUID(),sessionId:state.selected,pointerId:event.pointerId,y:event.clientY,released:false};
    if(event.pointerId!==undefined)$('voice-hold').setPointerCapture?.(event.pointerId);
    command({op:'voice.start',id:hold.id,sessionId:hold.sessionId});refreshVoice();
  }
  function finishVoice(event) {
    if(!hold || hold.released || (event.pointerId!==undefined && event.pointerId!==hold.pointerId))return;
    event.preventDefault();hold.released=true;voicePhase='finalizing';command({op:'voice.finish',id:hold.id,sessionId:hold.sessionId});refreshVoice();
  }
  $('voice-hold').addEventListener('pointerdown',beginVoice);
  $('voice-hold').addEventListener('pointerup',finishVoice);
  $('voice-hold').addEventListener('pointermove',event=>{if(hold && event.pointerId===hold.pointerId && hold.y-event.clientY>70){cancelVoice();refreshVoice();}});
  for(const type of ['pointercancel','lostpointercapture'])$('voice-hold').addEventListener(type,()=>{if(hold && !hold.released){cancelVoice();refreshVoice();}});
  $('voice-hold').addEventListener('keydown',event=>{if([' ','Enter'].includes(event.key) && !event.repeat)beginVoice(event);});
  $('voice-hold').addEventListener('keyup',event=>{if([' ','Enter'].includes(event.key))finishVoice(event);});
  $('voice-cancel').addEventListener('click',()=>{cancelVoice();refreshVoice();});
  $('voice-edit').addEventListener('click',()=>{cancelVoice();voiceMode=false;$('input').value=voiceText;refreshVoice();$('input').focus();});

  document.addEventListener('visibilitychange',()=>{if(document.hidden){cancelVoice();refreshVoice();}});
  window.addEventListener('pagehide',()=>{cancelVoice();});
  window.receiveVoiceState=next=>{
    if(!hold || next.id!==hold.id || next.sessionId!==hold.sessionId || next.sessionId!==state?.selected)return;
    voiceText=typeof next.text==='string'?next.text:voiceText;voicePhase=next.phase;voiceError=next.error??'';
    if(next.phase==='complete' && hold.released && !submitted.has(next.id) && typeof next.submit==='string' && next.submit.trim() && !active()) {
      submitted.add(next.id);if(submitted.size>100)submitted.delete(submitted.values().next().value);
      command({op:'send',sessionId:hold.sessionId,text:next.submit.trim()});hold=null;
    } else if(['error','cancelled','empty'].includes(next.phase))hold=null;
    refreshVoice();
  };
  window.receiveSendFailure=(text,sessionId=state?.selected)=>{drafts.set(sessionId,text);if(sessionId===state?.selected){voiceMode=false;$('input').value=text;voiceError='发送未完成，文字已恢复到输入框';refreshVoice();}};
  $('attach').addEventListener('click',()=>{$('more-panel').hidden=true;command({op:'pickImage'});});
  $('composer').addEventListener('submit',event=>{
    event.preventDefault();if(!state)return;
    if(active()){ $('send').textContent='停止'; command({op:'stop',runId:active().id});return; }
    const text=$('input').value.trim();if(!text)return;
    command({op:'send',sessionId:state.selected,text,scenario:$('scenario').value});drafts.delete(state.selected);$('input').value='';$('cap-panel').hidden=true;
  });
  $('menu').addEventListener('click',()=>{cancelVoice();refreshVoice();drawer(true);});$('close-drawer').addEventListener('click',()=>drawer(false));$('shade').addEventListener('click',()=>drawer(false));
  for(const id of ['new','drawer-new'])$(id).addEventListener('click',()=>{command({op:'new'});drawer(false);});
  for(const id of ['settings','drawer-settings'])$(id).addEventListener('click',()=>{cancelVoice();refreshVoice();command({op:'settings'});});
  $('archives').addEventListener('click',()=>{showArchives=!showArchives;renderSessions();});
  $('seed').addEventListener('click',()=>modal('加载长历史示例','向当前会话添加 200 条模拟消息，并新建 49 个会话。此操作会保存到本地，可逐个删除。',null,()=>command({op:'seed'})));
  $('capabilities').addEventListener('click',()=>{popover('cap-panel');$('capabilities').setAttribute('aria-expanded',String(!$('cap-panel').hidden));});
  document.addEventListener('keydown',event=>{if(event.key==='Escape'){drawer(false);for(const p of ['more-panel','model-panel','skill-panel','cap-panel'])$(p).hidden=true;}});
  document.addEventListener('click',e=>{if(!e.target.closest('footer'))for(const p of ['more-panel','model-panel','skill-panel','cap-panel'])$(p).hidden=true;});
  command({op:'ready'});
})();
