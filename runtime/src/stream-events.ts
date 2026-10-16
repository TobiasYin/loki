import type { RunEvent } from './contracts.js';

type Fragment = {type:RunEvent['type'];data:Record<string,unknown>;toolCallId?:string};
/** Coalesce presentation fragments, never intents/results/authorization. The first
 * fragment remains immediate; message boundaries and every effect flush durably. */
export class StreamEvents {
  private pending?:Fragment;
  private timer?:ReturnType<typeof setTimeout>;
  private tail:Promise<void>=Promise.resolve();
  private failure?:unknown;
  private lastKey?:string;
  private closed=false;
  constructor(private readonly write:(fragment:Fragment)=>Promise<void>,private readonly intervalMs=40,private readonly maxBytes=4096) {}
  private key(f:Fragment):string {return `${f.type}:${f.toolCallId??String(f.data.blockId??'')}`;}
  private enqueue(fragment:Fragment):Promise<void> {
    this.tail=this.tail.then(async()=>{if(this.failure)throw this.failure;await this.write(fragment);}).catch(error=>{this.failure=error;});
    return this.checkTail();
  }
  private async checkTail():Promise<void> {await this.tail;if(this.failure)throw this.failure;}
  async push(fragment:Fragment):Promise<void> {
    if(this.closed)throw new Error('Stream closed');
    if(this.failure)throw this.failure;
    const f=structuredClone(fragment),key=this.key(f);
    if(key!==this.lastKey) {await this.flush();this.lastKey=key;await this.enqueue(f);return;}
    if(this.pending && this.key(this.pending)!==key)await this.flush();
    if(this.pending && (f.type==='text_delta'||f.type==='thinking_delta'))this.pending.data.text=String(this.pending.data.text??'')+String(f.data.text??'');
    else this.pending=f;
    if(f.data.complete===true || new TextEncoder().encode(JSON.stringify(this.pending)).byteLength>=this.maxBytes) {await this.flush();return;}
    if(!this.timer)this.timer=setTimeout(()=>{this.timer=undefined;void this.flush().catch(()=>{});},this.intervalMs);
  }
  async flush():Promise<void> {
    if(this.timer){clearTimeout(this.timer);this.timer=undefined;}
    const fragment=this.pending;this.pending=undefined;
    if(fragment)await this.enqueue(fragment);else await this.checkTail();
  }
  dispose():void {this.closed=true;if(this.timer)clearTimeout(this.timer);this.timer=undefined;this.pending=undefined;}
}
