// Host state/turn data is not network metadata. Keep the 64 KiB header limit,
// and carry larger JSON in the existing bounded UTF-8 frame body.
const HEADER_LIMIT=65536, BODY_LIMIT=16*1024*1024, INLINE_LIMIT=32768;
const encoder=new TextEncoder();
const failure=code=>Object.assign(new Error(code),{code});
export function encodeHostRpc(header,data) {
  if(header.type!=='host.rpc' || ['data','dataInBody','bytes'].some(k=>Object.hasOwn(header,k)))throw failure('INVALID_REQUEST');
  const text=JSON.stringify(data);
  if(text===undefined || data===null || Array.isArray(data) || typeof data!=='object')throw failure('INVALID_REQUEST');
  const bytes=encoder.encode(text);
  if(bytes.byteLength>BODY_LIMIT)throw failure('LIMIT_EXCEEDED');
  const inline=JSON.stringify({...header,data});
  if(encoder.encode(inline).byteLength<=INLINE_LIMIT)return inline;
  const meta={...header,dataInBody:true};
  if(encoder.encode(JSON.stringify(meta)).byteLength>HEADER_LIMIT)throw failure('LIMIT_EXCEEDED');
  let binary='';
  for(let offset=0;offset<bytes.length;offset+=32768)binary+=String.fromCharCode(...bytes.subarray(offset,offset+32768));
  return JSON.stringify({...meta,bytes:btoa(binary)});
}
