import { webRequest, searchResults } from './web-services.js';
import { ThorRuntime } from '../runtime/src/runtime.js';
import { RuntimeSession } from '../runtime/src/session.js';
import { ToolRegistry } from '../runtime/src/registry.js';
import { RuntimeError, type RuntimePorts, type ToolDescriptor, type ToolEnvelope } from '../runtime/src/contracts.js';
// The bridge is a browser-only module with deliberately bounded Fetch support.
// @ts-ignore JS module
import { createNativeFetch, decodeFrame } from '../native-bridge/js/fetch-adapter.js';
// @ts-ignore Browser-independent text extraction is shared with Node tests.
import { readableText } from './web-text.mjs';
// @ts-ignore Pure coordinator shared with Node concurrency regression tests.
import { StartupCoordinator } from './startup-coordinator.mjs';
// @ts-ignore Bounded host payload framing is shared with Node regression tests.
import { encodeHostRpc } from './rpc-frame.mjs';
// @ts-ignore Fixed execution-to-chat metadata; no transcript or arbitrary callback relay.
import { postRuntimeUiEvent } from './runtime-ui-events.mjs';
import { skillTools } from './skill-tools.js';
import { agentSleepTool } from './agent-tools.js';
import contract from '../tools/contracts/toolpack-v1.json';

declare const __THOR_DEBUG__: boolean;
declare global { interface Window {
  ThorNative: { postMessage(data: string | ArrayBuffer): void; onmessage: (event: MessageEvent) => void };
  ThorUI: { postMessage(data: string): void };
  runNativeProbe: (token: string, sessionId: string) => Promise<void>;
  productReady?: boolean;
} }
let connectionId = '';
const executions=new Map<string,ReturnType<typeof createExecution>>();
const starting=new Set<string>();
const startupCoordinator=new StartupCoordinator();
const preparations=new Map<string,{cancelled:boolean,startedAt:number,runId?:string,phase?:string}>();
// Native notification stop has its own immediate authority revocation. This
// companion hook lets every Pi execution unwind and save its current context.
(window as any).stopAllNativeRuns = () => {
  for(const pending of preparations.values())pending.cancelled=true;
  for(const execution of executions.values())execution.runtime.stop();
};
function preparation(sessionId:string,state:{cancelled:boolean,startedAt:number,runId?:string,phase?:string},phase:string,force=false) {
  if(preparations.get(sessionId)===state && (force || state.phase!==phase)) {state.phase=phase;relayUi('preparationState',{sessionId,phase,elapsedMs:Math.round(performance.now()-state.startedAt)});}
}
const pending = new Map<string, { resolve: (v: any) => void; reject: (e: any) => void; timer: ReturnType<typeof setTimeout>; op: string; kind?: string; since: number }>();
const queue: string[] = [];

let currentState: any;
const send = (data: string | ArrayBuffer) => window.ThorNative.postMessage(data);
const rpc = (op: string, data: any = {}, timeout = 185000): Promise<any> => new Promise((resolve, reject) => {
  if (!connectionId) { reject(new RuntimeError('BACKEND_UNAVAILABLE')); return; }
  const requestId = crypto.randomUUID();
  const timer = setTimeout(() => { pending.delete(requestId); reject(new RuntimeError('TIMEOUT')); executions.get(data.runId)?.runtime.stop(); }, timeout);
  pending.set(requestId, { resolve, reject, timer, op, kind: op==='persist'?data.kind:undefined, since:performance.now() });
  try {send(encodeHostRpc({ type:'host.rpc',bridgeVersion:1,connectionId,requestId,op },data));}
  catch(error:any){clearTimeout(timer);pending.delete(requestId);reject(new RuntimeError(error?.code==='LIMIT_EXCEEDED'?'LIMIT_EXCEEDED':'PROTOCOL_ERROR'));}
});
if (__THOR_DEBUG__) {
  const safeOps=new Set(['view','authority','persist','tool.prepare','tool.execute','tool.cancel','network.tool.begin','begin','end','runtime.work.begin','runtime.work.end','runtime.diagnostics.native']);
  const safeKinds=new Set(['run','event','commit','turn','context','pi_session']);
  (window as any).__thorRuntimeDiagnostics = () => ({
    connected:!!connectionId, executionCount:executions.size, preparationCount:preparations.size, startingCount:starting.size,
    pendingRpcCount:pending.size, residentSessionCount:[...executions.values()].filter(e=>e.modelSession).length,
    pendingRpc:[...pending.values()].slice(0,64).map(p=>({op:safeOps.has(p.op)?p.op:'other',...(p.kind?{kind:safeKinds.has(p.kind)?p.kind:'other'}:{}),ageMs:Math.max(0,Math.round(performance.now()-p.since))})),
    executions:[...executions.values()].slice(0,8).map(e=>({runId:e.lease.runId,sessionId:e.lease.sessionId,...e.runtime.diagnostics()})),
  });
  (window as any).__thorRuntimeNativeDiagnostics = () => rpc('runtime.diagnostics.native',{},5000);
}
function relayUi(name:string,payload:Record<string,unknown>={}) {
  // No RPC promise/timer or absent-chat queue: a view cannot block execution.
  postRuntimeUiEvent(send,connectionId,name,payload);
}
function handle(message: any) {
  if (message.type === 'bridge.ready') {
    connectionId = message.connectionId; window.productReady = true;relayUi('ready');
    void rpc('view').then(update);
    for (const raw of queue.splice(0)) window.ThorUI.postMessage(raw);
  } else if (message.type === 'host.result' || message.type==='response.error' && pending.has(message.requestId)) {
    const p = pending.get(message.requestId); if (!p) return;
    clearTimeout(p.timer); pending.delete(message.requestId);
    if (message.code || message.value?.error && !message.value?.content) {const code=message.value?.error??message.code??'IO_ERROR';p.reject(new RuntimeError(code==='INVALID_REQUEST'?'PROTOCOL_ERROR':code));}
    else p.resolve(message.value);
  }
}
function update(state: any) {
  // A direct `view` RPC can return a chat projection. Keep only execution
  // invalidation metadata, never an idle chat's messages, images or tool ledger.
  currentState = {
    sessions:(state.sessions??[]).map((s:any)=>({id:s.id,capabilityEpoch:s.capabilityEpoch})),
    activeRuns:(state.activeRuns??state.runs??[]).map((r:any)=>({id:r.id,sessionId:r.sessionId,status:r.status,stopReason:r.stopReason})),
  };
  state=currentState;
  for(const [sessionId,preparing] of preparations)if(preparing.runId && (state.activeRuns??state.runs)?.some((r:any)=>r.id===preparing.runId))preparation(sessionId,preparing,'idle');
  for(const execution of executions.values()) {
    const lease=execution.lease;
    const s=state.sessions?.find((s:any)=>s.id===lease.sessionId);
    if(s)execution.runtime.authorizationChanged(s.id,s.capabilityEpoch);
    const r=(state.activeRuns??state.runs)?.find((r:any)=>r.id===lease.runId);
    if(r?.stopReason)execution.runtime.stop();
  }
}
(window as any).receiveProductState = update;
window.ThorNative.onmessage = event => {
  try { const message = decodeFrame(event.data); if (message.type === 'host.result' || message.type === 'bridge.ready' || message.type==='response.error' && pending.has(message.requestId)) handle(message); else for(const execution of executions.values())execution.receive(event.data); }
  catch { for(const p of pending.values()){clearTimeout(p.timer);p.reject(new RuntimeError('PROTOCOL_ERROR'));}pending.clear();for(const execution of executions.values())execution.runtime.stop(); }
};
const tools = (contract.tools as any[]).map(t => ({ ...t,
  schemaVersion: String(t.schemaVersion), modelName: t.toolId.replaceAll('.', '_'),
  platformPrerequisites: [t.toolId], executor: 'native-device',
  outputPolicy: { maxTextBytes: 65536, maxImageBytes: 2097152, maxImages: 1, imageTokenEstimate: 4096 }
})) as ToolDescriptor[];
const probe: ToolDescriptor = { toolId: 'probe.picture', modelName: 'probe_picture', schemaVersion: '1', description: 'Configuration probe only: return a small red test image. Call once, describe both input and returned image, then answer PROBE_OK.',
  inputSchema: { type: 'object', properties: {}, additionalProperties: false }, requiredCapabilities: [], platformPrerequisites: ['probe.picture'], executor: 'native-probe', resources: ['fixture'], effectClass: 'read', timeoutMs: 60000, cancellable: true, concurrencyKey: 'device', provenance: 'native-configuration-probe', modelVisibility: true,
  outputPolicy: { maxTextBytes: 1024, maxImageBytes: 2097152, maxImages: 1, imageTokenEstimate: 4096 } };
const webTools: ToolDescriptor[] = ['search','fetch'].map(name=>({toolId:`web.${name}`,modelName:`web_${name}`,schemaVersion:'1',description:name==='search'?'Search the configured DeepSeek, Tavily or Brave service and return source titles, URLs, snippets and source time when present. DeepSeek queries must be at most 128 Unicode characters.':'Read an HTTP/HTTPS page as bounded untrusted text over the system network. LAN, loopback and proxy DNS are supported.',
    inputSchema:{type:'object',properties:{[name==='search'?'query':'url']:{type:'string',minLength:1,maxLength:name==='search'?400:8192}},required:[name==='search'?'query':'url'],additionalProperties:false},requiredCapabilities:['web_search'],platformPrerequisites:[`web.${name}`],executor:'native-fetch',resources:['public-web'],effectClass:'network',timeoutMs:30000,cancellable:true,concurrencyKey:'network',provenance:'builtin',modelVisibility:true,outputPolicy:{maxTextBytes:65536,maxImageBytes:0,maxImages:0,imageTokenEstimate:4096}}));
const registry = new ToolRegistry(); registry.register({packId:'thor.agent',packVersion:'1',tools:[agentSleepTool]}); registry.register({packId:'thor.skills',packVersion:'1',tools:skillTools}); registry.register({packId:'thor.web',packVersion:'1',tools:webTools}); registry.register({ packId: contract.packId, packVersion: contract.packVersion, tools }); registry.register({ packId: 'configuration-probe', packVersion: '1', tools: [probe] });
function createExecution(begun:any) {
const activeLease=begun.lease,networkContext=begun.network;
let toolNetworkContext:any;
const bound=(extra:any={})=>({...activeLease,...extra});
const fetchBridge=createNativeFetch({send,context:()=>networkContext});
const toolFetchBridge=createNativeFetch({send,context:()=>toolNetworkContext});
const persist = (kind: string, value: any, event?: any) => rpc('persist', bound({ kind, value, ...(event ? { event } : {}) })).then(() => undefined);
const ports: RuntimePorts = {
  authority: { read: (sessionId, runId, refresh) => rpc('authority', bound({ sessionId, runId, refresh: !!refresh })) },
  tools: { execute: async (envelope, signal, awaiting) => {
    const cancel = () => { void rpc('tool.cancel', envelope).catch(() => {}); };
    signal.addEventListener('abort', cancel, { once: true });
    try {
      if(signal.aborted) throw new RuntimeError('CANCELLED');
      const prepared = await rpc('tool.prepare', envelope);
      if(prepared.error) return prepared;
      if(envelope.toolId==='web.search'||envelope.toolId==='web.fetch') {
        const native = await rpc('network.tool.begin',envelope);toolNetworkContext=native.context;
        const search=envelope.toolId==='web.search';const args=envelope.arguments as any;
        const backend=native.backend ?? 'Brave';
        const request=webRequest(backend,search,native.url,args);
        const response=await toolFetchBridge.fetch(request.url,{...request.init,signal});
        let bytes=0;const parts:Uint8Array[]=[];const reader=response.body?.getReader();
        if(reader) while(true){const next=await reader.read();if(next.done)break;bytes+=next.value.byteLength;if(bytes>2097152){await reader.cancel();throw new RuntimeError('LIMIT_EXCEEDED');}parts.push(next.value);}
        const payload=new Uint8Array(bytes);let offset=0;for(const p of parts){payload.set(p,offset);offset+=p.byteLength;}const text=new TextDecoder().decode(payload);
        if(!response.ok) return {content:[{type:'text',text:`HTTP_${response.status}`}],observedAt:Date.now(),source:search?`${backend}Search`:'web-page',truncated:false,error:'IO_ERROR',outcome:'observed'};
        const plain=(v:unknown)=>readableText(v).text;const page=readableText(text);
        const fetched= !search && backend==='DeepSeek' ? JSON.parse(text) : null;
        if(fetched && typeof fetched.content!=='string')throw new RuntimeError('IO_ERROR');
        const fetchedPage=fetched?readableText(fetched.content):page;
        const result=search?{query:args.query,fetchedAt:new Date().toISOString(),results:searchResults(backend,text).map(r=>({...r,title:plain(r.title),summary:plain(r.summary)}))}:{url:args.url,title:fetched?.title,text:fetchedPage.text,fetchedAt:new Date().toISOString()};
        return {content:[{type:'text',text:JSON.stringify(result)}],observedAt:Date.now(),source:search?`${backend}Search`:'web-page',truncated:!search&&fetchedPage.truncated,outcome:'observed'};
      }
      if (prepared.awaiting) await awaiting?.(true);
      const result = await rpc('tool.execute', envelope);
      if (prepared.awaiting && !signal.aborted) await awaiting?.(false);
      if(signal.aborted) throw new RuntimeError('CANCELLED');
      return result;
    } finally { signal.removeEventListener('abort', cancel); }
  } },
  network: { fetch: async (input, init, e) => {
    if (!networkContext || e.sessionId !== activeLease.sessionId || e.runId !== activeLease.runId || e.leaseId !== activeLease.leaseId || e.capabilityEpoch !== activeLease.capabilityEpoch || e.profileRef !== networkContext.profileRef || e.credentialRef !== networkContext.credentialRef) throw new RuntimeError('STALE_AUTHORIZATION');
    const request = new Request(input, init);
    const headers = new Headers(request.headers);
    for (const [name, value] of headers) if (name === 'authorization' || name === 'x-api-key') {
      const expected = name === 'authorization' ? `Bearer ${e.credentialPlaceholder}` : e.credentialPlaceholder;
      if(value !== expected) throw new RuntimeError('PROTOCOL_ERROR');
      headers.delete(name); // Native installs the selected generic header template; SDK placeholders never leave JS.
    }
    const response = await fetchBridge.fetch(new Request(request, {headers}), {signal: request.signal});
    // Native delivers redirects without forwarding secrets; model transport must treat them as failure.
    if(response.status >= 300 && response.status < 400) { await response.body?.cancel(); throw new RuntimeError('IO_ERROR'); }
    return response;
  } },
  persistence: { appendEvent: e => persist('event', e), commitRun: (r,e) => persist('commit',r,e), saveRun: r => persist('run',r), saveContext: s => persist('context',s), saveTurn: (_sid,t) => persist('turn',t), savePiSession:(_sid,value)=>persist('pi_session',value) },
  attachments: { put: a => rpc('attachment.put', bound({ value: a })).then(() => undefined), get: async id => { const a = await rpc('attachment.get', bound({ id })); return a.attachmentId ? a : undefined; } }
};
// Native stores opaque Pi entries for idle conversations. An official Pi session
// lives only for this execution and restores the same entry tree on the next send.
const session=networkContext.purpose==='model'?new RuntimeSession(activeLease.sessionId):undefined;
const runtime=new ThorRuntime(ports,registry,{runTimeoutMs:begun.runTimeoutMs??0},session);
let input=begun.input, piState=begun.piSession, pendingInputs=begun.pendingInputs??[];
return {runtime,lease:activeLease,modelSession:!!session,
  receive:(raw:any)=>{fetchBridge.receive(raw);toolFetchBridge.receive(raw);},run:()=>runtime.run(activeLease,input,piState,pendingInputs),
  dispose:()=>{if(session){session.agent=undefined;session.piSnapshot=undefined;}input=[];piState=undefined;pendingInputs=[];toolNetworkContext=undefined;
    try {fetchBridge.close();}finally {toolFetchBridge.close();}}};
}
async function start(sessionId:string,text:string,probeToken?:string) {
  if(starting.has(sessionId) || [...executions.values()].some(e=>e.lease.sessionId===sessionId))throw new RuntimeError('BACKEND_UNAVAILABLE');
  starting.add(sessionId);
  const state:{cancelled:boolean,startedAt:number,runId?:string}={cancelled:false,startedAt:performance.now()};preparations.set(sessionId,state);preparation(sessionId,state,'checking');
  const workId=crypto.randomUUID();let workAcquired=false;
  const begun=async(data:any)=>{
    state.runId=undefined;
    const result=await rpc('begin',data);
    state.runId=result.lease.runId;
    if(state.cancelled) {
      await rpc('ui',{op:'stop',sessionId,runId:result.lease.runId});
      await rpc('end',result.lease);
      throw new RuntimeError('CANCELLED');
    }
    if((currentState?.activeRuns??currentState?.runs)?.some((r:any)=>r.id===state.runId))preparation(sessionId,state,'idle');
    await executeBegun(result);
  };
  try {
    await rpc('runtime.work.begin',{workId});workAcquired=true;
    if(!probeToken)await startupCoordinator.verify(
      (checkOnly:boolean)=>rpc('profile.check',{sessionId,checkOnly}),
      (token:string)=>begun({sessionId,text:probeText,probeToken:token}),
      ()=>state.cancelled,
      ()=>preparation(sessionId,state,'probing'));
    if(state.cancelled)throw new RuntimeError('CANCELLED');
    preparation(sessionId,state,'starting');
    await begun({sessionId,text,...(probeToken?{probeToken}:{})});
  } finally {
    if(preparations.get(sessionId)===state){preparation(sessionId,state,'idle');preparations.delete(sessionId);starting.delete(sessionId);}
    if(workAcquired)await rpc('runtime.work.end',{workId}).catch(()=>{});
  }
}
async function executeBegun(begun:any) {
  if([...executions.values()].some(e=>e.lease.sessionId===begun.lease.sessionId))throw new RuntimeError('BACKEND_UNAVAILABLE');
  const execution=createExecution(begun);const lease=execution.lease;executions.set(lease.runId,execution);
  try {await execution.run();}
  catch { /* Native end records failure; no intent replay. */ }
  finally {
    try {await rpc('end',lease).catch(()=>{});}
    finally {try {execution.dispose();}finally {executions.delete(lease.runId);}}
    void rpc('view').then(update).catch(()=>{});
  }
}
const probeText='Configuration probe: inspect the attached red image, call probe_picture once, inspect its returned image, and finish with PROBE_OK. Do not call any device tool.';
if(__THOR_DEBUG__) (window as any).runPreparedNative = executeBegun;
window.runNativeProbe = (token, sessionId) => start(sessionId,probeText,token);
window.ThorUI = { postMessage(raw) {
  if(!connectionId) { queue.push(raw); return; }
  const command = JSON.parse(raw);
  if(command.op==='ready') {
    for(const [sessionId,state] of preparations)preparation(sessionId,state,state.phase??'checking',true);
  }
  if(command.op==='stopPreparing') {
    const state=preparations.get(command.sessionId);if(state){state.cancelled=true;preparation(command.sessionId,state,'idle');starting.delete(command.sessionId);}
    for(const e of executions.values())if(e.lease.sessionId===command.sessionId){e.runtime.stop();void rpc('ui',{op:'stop',sessionId:command.sessionId,runId:e.lease.runId}).then(update).catch(()=>{});}
    return;
  }
  if(['openLink','settings','pickImage','skillImport','voiceSettings','voice.start','voice.finish','voice.cancel','voice.permission'].includes(command.op)) { send(JSON.stringify({ type: 'host.rpc', bridgeVersion: 1, connectionId, requestId: crypto.randomUUID(), op: command.op, data: command })); return; }
  if(command.op === 'send') { void start(command.sessionId, command.text).catch((error) => {relayUi('sendFailure',{sessionId:command.sessionId,clientSendId:command.clientSendId,errorCode:error?.code??error?.message});}); return; }
  if(command.op === 'stop')executions.get(command.runId)?.runtime.stop();
  void rpc('ui', command).then(update).catch(() => {if(command.op==='capability')relayUi('capabilityFailure',command);relayUi('operationFailure',{sessionId:command.sessionId});});
} };
// SDK receives only the supplied native Fetch. CSP and WebView interception also block all direct requests.
globalThis.fetch = (() => Promise.reject(new RuntimeError('PERMISSION_MISSING'))) as typeof fetch;
(globalThis as any).WebSocket = class { constructor() { throw new RuntimeError('PERMISSION_MISSING'); } };
send(JSON.stringify({ type: 'bridge.hello', bridgeVersion: 1 }));
