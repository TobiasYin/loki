import { ThorRuntime } from '../runtime/src/runtime.js';
import { ToolRegistry } from '../runtime/src/registry.js';
import { RuntimeError, type RuntimePorts, type ToolDescriptor, type ToolEnvelope } from '../runtime/src/contracts.js';
// The bridge is a browser-only module with deliberately bounded Fetch support.
// @ts-ignore JS module
import { createNativeFetch, decodeFrame } from '../native-bridge/js/fetch-adapter.js';
import { skillTools } from './skill-tools.js';
import contract from '../tools/contracts/toolpack-v1.json';

declare const __THOR_DEBUG__: boolean;
declare global { interface Window {
  ThorNative: { postMessage(data: string | ArrayBuffer): void; onmessage: (event: MessageEvent) => void };
  ThorUI: { postMessage(data: string): void };
  receiveNative: (state: any) => void;
  runNativeProbe: (token: string, sessionId: string) => Promise<void>;
  productReady?: boolean;
} }
let connectionId = '';
const executions=new Map<string,ReturnType<typeof createExecution>>();
const starting=new Set<string>();
let profileChecks:Promise<void>=Promise.resolve();
const pending = new Map<string, { resolve: (v: any) => void; reject: (e: any) => void; timer: ReturnType<typeof setTimeout> }>();
const queue: string[] = [];

let currentState: any;
const send = (data: string | ArrayBuffer) => window.ThorNative.postMessage(data);
const rpc = (op: string, data: any = {}, timeout = 185000): Promise<any> => new Promise((resolve, reject) => {
  if (!connectionId) { reject(new RuntimeError('BACKEND_UNAVAILABLE')); return; }
  const requestId = crypto.randomUUID();
  const timer = setTimeout(() => { pending.delete(requestId); reject(new RuntimeError('TIMEOUT')); executions.get(data.runId)?.runtime.stop(); }, timeout);
  pending.set(requestId, { resolve, reject, timer });
  send(JSON.stringify({ type: 'host.rpc', bridgeVersion: 1, connectionId, requestId, op, data }));
});
function handle(message: any) {
  if (message.type === 'bridge.ready') {
    connectionId = message.connectionId; window.productReady = true;
    void rpc('view').then(update);
    for (const raw of queue.splice(0)) window.ThorUI.postMessage(raw);
  } else if (message.type === 'host.result') {
    const p = pending.get(message.requestId); if (!p) return;
    clearTimeout(p.timer); pending.delete(message.requestId);
    if (message.code || message.value?.error && !message.value?.content) p.reject(new RuntimeError(message.value?.error ?? 'IO_ERROR'));
    else p.resolve(message.value);
  }
}
function update(state: any) {
  currentState = state;
  for(const execution of executions.values()) {
    const lease=execution.lease;
    const s=state.sessions?.find((s:any)=>s.id===lease.sessionId);
    if(s)execution.runtime.authorizationChanged(s.id,s.capabilityEpoch);
    const r=(state.activeRuns??state.runs)?.find((r:any)=>r.id===lease.runId);
    if(r?.stopReason)execution.runtime.stop();
  }
  if (window.receiveNative) window.receiveNative(state);
}
(window as any).receiveProductState = update;
window.ThorNative.onmessage = event => {
  try { const message = decodeFrame(event.data); if (message.type === 'host.result' || message.type === 'bridge.ready') handle(message); else for(const execution of executions.values())execution.receive(event.data); }
  catch { for(const execution of executions.values())execution.runtime.stop(); }
};
const tools = (contract.tools as any[]).map(t => ({ ...t,
  schemaVersion: String(t.schemaVersion), modelName: t.toolId.replaceAll('.', '_'),
  platformPrerequisites: [t.toolId], executor: 'native-device',
  outputPolicy: { maxTextBytes: 65536, maxImageBytes: 2097152, maxImages: 1, imageTokenEstimate: 4096 }
})) as ToolDescriptor[];
const probe: ToolDescriptor = { toolId: 'probe.picture', modelName: 'probe_picture', schemaVersion: '1', description: 'Configuration probe only: return a small red test image. Call once, describe both input and returned image, then answer PROBE_OK.',
  inputSchema: { type: 'object', properties: {}, additionalProperties: false }, requiredCapabilities: [], platformPrerequisites: ['probe.picture'], executor: 'native-probe', resources: ['fixture'], effectClass: 'read', timeoutMs: 60000, cancellable: true, concurrencyKey: 'device', provenance: 'native-configuration-probe', modelVisibility: true,
  outputPolicy: { maxTextBytes: 1024, maxImageBytes: 2097152, maxImages: 1, imageTokenEstimate: 4096 } };
const webTools: ToolDescriptor[] = ['search','fetch'].map(name=>({toolId:`web.${name}`,modelName:`web_${name}`,schemaVersion:'1',description:name==='search'?'Search Brave and return source titles, URLs, snippets and source time when present.':'Read an HTTP/HTTPS page as bounded untrusted text over the system network. LAN, loopback and proxy DNS are supported.',
    inputSchema:{type:'object',properties:{[name==='search'?'query':'url']:{type:'string',minLength:1,maxLength:name==='search'?400:8192}},required:[name==='search'?'query':'url'],additionalProperties:false},requiredCapabilities:['web_search'],platformPrerequisites:[`web.${name}`],executor:'native-fetch',resources:['public-web'],effectClass:'network',timeoutMs:30000,cancellable:true,concurrencyKey:'network',provenance:'builtin',modelVisibility:true,outputPolicy:{maxTextBytes:65536,maxImageBytes:0,maxImages:0,imageTokenEstimate:4096}}));
const registry = new ToolRegistry(); registry.register({packId:'thor.skills',packVersion:'1',tools:skillTools}); registry.register({packId:'thor.web',packVersion:'1',tools:webTools}); registry.register({ packId: contract.packId, packVersion: contract.packVersion, tools }); registry.register({ packId: 'configuration-probe', packVersion: '1', tools: [probe] });
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
        const url=search ? `${native.url}?q=${encodeURIComponent(args.query)}&count=5` : native.url;
        const response=await toolFetchBridge.fetch(url,{method:'GET',signal,headers:{accept:search?'application/json':'text/html,text/plain'}});
        let bytes=0;const parts:Uint8Array[]=[];const reader=response.body?.getReader();
        if(reader) while(true){const next=await reader.read();if(next.done)break;bytes+=next.value.byteLength;if(bytes>2097152){await reader.cancel();throw new RuntimeError('LIMIT_EXCEEDED');}parts.push(next.value);}
        const payload=new Uint8Array(bytes);let offset=0;for(const p of parts){payload.set(p,offset);offset+=p.byteLength;}const text=new TextDecoder().decode(payload);
        if(!response.ok) return {content:[{type:'text',text:`HTTP_${response.status}`}],observedAt:Date.now(),source:search?'BraveSearch':'web-page',truncated:false,error:'IO_ERROR',outcome:'observed'};
        const plain=(v:unknown)=>String(v??'').replace(/<[^>]*>/g,'').slice(0,12000);
        const result=search?{query:args.query,fetchedAt:new Date().toISOString(),results:(JSON.parse(text).web?.results??[]).slice(0,5).map((r:any)=>({title:plain(r.title),url:r.url,summary:plain(r.description),sourceTime:r.page_age??null}))}:{url:response.url,text:plain(text),fetchedAt:new Date().toISOString()};
        return {content:[{type:'text',text:JSON.stringify(result)}],observedAt:Date.now(),source:search?'BraveSearch':'web-page',truncated:!search&&text.length>12000,outcome:'observed'};
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
  persistence: { appendEvent: e => persist('event', e), commitRun: (r,e) => persist('commit',r,e), saveRun: r => persist('run',r), saveContext: s => persist('context',s), saveTurn: (_sid,t) => persist('turn',t) },
  attachments: { put: a => rpc('attachment.put', bound({ value: a })).then(() => undefined), get: async id => { const a = await rpc('attachment.get', bound({ id })); return a.attachmentId ? a : undefined; } }
};
const runtime=new ThorRuntime(ports,registry);
return {runtime,lease:activeLease,receive:(raw:any)=>{fetchBridge.receive(raw);toolFetchBridge.receive(raw);},run:()=>runtime.run(activeLease,begun.input,begun.history)};
}
async function start(sessionId:string,text:string,probeToken?:string) {
  if(starting.has(sessionId) || [...executions.values()].some(e=>e.lease.sessionId===sessionId))throw new RuntimeError('BACKEND_UNAVAILABLE');
  starting.add(sessionId);
  try {
    if(!probeToken) {
      const check=profileChecks.catch(()=>{}).then(async()=>{
        const status=await rpc('profile.check',{sessionId});
        if(status.probeNeeded)await executeBegun(await rpc('begin',{sessionId,text:probeText,probeToken:status.token}));
      });
      profileChecks=check;await check;
    }
    await executeBegun(await rpc('begin',{sessionId,text,...(probeToken?{probeToken}:{})}));
  } finally {starting.delete(sessionId);}
}
async function executeBegun(begun:any) {
  if([...executions.values()].some(e=>e.lease.sessionId===begun.lease.sessionId))throw new RuntimeError('BACKEND_UNAVAILABLE');
  const execution=createExecution(begun);const lease=execution.lease;executions.set(lease.runId,execution);
  try {await execution.run();}
  catch { /* Native end records failure; no intent replay. */ }
  finally {await rpc('end',lease).catch(()=>{});executions.delete(lease.runId);void rpc('view').then(update);}
}
const probeText='Configuration probe: inspect the attached red image, call probe_picture once, inspect its returned image, and finish with PROBE_OK. Do not call any device tool.';
if(__THOR_DEBUG__) (window as any).runPreparedNative = executeBegun;
window.runNativeProbe = (token, sessionId) => start(sessionId,probeText,token);
window.ThorUI = { postMessage(raw) {
  if(!connectionId) { queue.push(raw); return; }
  const command = JSON.parse(raw);
  if(['settings','pickImage','skillImport','voiceSettings','voice.start','voice.finish','voice.cancel','voice.permission'].includes(command.op)) { send(JSON.stringify({ type: 'host.rpc', bridgeVersion: 1, connectionId, requestId: crypto.randomUUID(), op: command.op, data: command })); return; }
  if(command.op === 'send') { void start(command.sessionId, command.text).catch(() => { (window as any).receiveSendFailure?.(command.text,command.sessionId); update({ ...currentState, error: '发送失败：请先配置模型并完成图片/工具探测；文字已保留。' }); }); return; }
  if(command.op === 'stop')executions.get(command.runId)?.runtime.stop();
  void rpc('ui', command).then(update).catch(() => update({ ...currentState, error: '操作被拒绝：检查授权、活动运行或本地存储。' }));
} };
// SDK receives only the supplied native Fetch. CSP and WebView interception also block all direct requests.
globalThis.fetch = (() => Promise.reject(new RuntimeError('PERMISSION_MISSING'))) as typeof fetch;
(globalThis as any).WebSocket = class { constructor() { throw new RuntimeError('PERMISSION_MISSING'); } };
send(JSON.stringify({ type: 'bridge.hello', bridgeVersion: 1 }));
