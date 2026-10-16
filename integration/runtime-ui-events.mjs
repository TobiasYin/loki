/** Fixed, small execution -> chat notifications. Prompt/model/tool text is never
 * a relay payload; chat correlates send failures with its own bounded draft. */
const codes = new Set(['CAPABILITY_DISABLED','STALE_AUTHORIZATION','PERMISSION_MISSING','BACKEND_UNAVAILABLE','TARGET_CHANGED','UNSUPPORTED','TIMEOUT','CANCELLED','LIMIT_EXCEEDED','IO_ERROR','PROTOCOL_ERROR','PERSISTENCE_ERROR','INVALID_ARGUMENT','HASH_CONFLICT','APPROVAL_REQUIRED','COMMAND_BLOCKED','SECURE_CONTENT','DISPLAY_GONE','RATE_LIMITED']);
const id = value => typeof value === 'string' && value.length > 0 && value.length <= 160;
export function runtimeUiEvent(name, payload = {}) {
  if (!payload || typeof payload !== 'object' || Array.isArray(payload)) return undefined;
  let value;
  if (name === 'ready') value = {};
  else if (name === 'preparationState' && id(payload.sessionId) && ['checking','probing','starting','idle'].includes(payload.phase) && Number.isSafeInteger(payload.elapsedMs) && payload.elapsedMs >= 0)
    value = {sessionId:payload.sessionId,phase:payload.phase,elapsedMs:payload.elapsedMs};
  else if (name === 'sendFailure' && id(payload.sessionId) && id(payload.clientSendId))
    value = {sessionId:payload.sessionId,clientSendId:payload.clientSendId,errorCode:codes.has(payload.errorCode)?payload.errorCode:'IO_ERROR'};
  else if (name === 'capabilityFailure' && id(payload.sessionId) && id(payload.clientMutationId) && ['perception','search','screen_control','debug'].includes(payload.capability))
    value = {sessionId:payload.sessionId,capability:payload.capability,clientMutationId:payload.clientMutationId};
  else if (name === 'operationFailure' && (payload.sessionId === undefined || id(payload.sessionId)))
    value = payload.sessionId === undefined ? {} : {sessionId:payload.sessionId};
  else return undefined;
  const event = {name,payload:value};
  return new TextEncoder().encode(JSON.stringify(event)).byteLength <= 2048 ? event : undefined;
}

/** No acknowledgement promise, callback registry, timer or replay queue. */
export function postRuntimeUiEvent(send, connectionId, name, payload = {}) {
  const event = runtimeUiEvent(name, payload);
  if (!connectionId || !event) return false;
  try {
    send(JSON.stringify({type:'host.rpc',bridgeVersion:1,connectionId,requestId:crypto.randomUUID(),op:'runtime.ui.event',data:event}));
    return true;
  } catch {return false;}
}
