const {test}=require('node:test');
const assert=require('node:assert/strict');
const load=()=>import('../../integration/startup-coordinator.mjs');
const deferred=()=>{let resolve;const promise=new Promise(r=>resolve=r);return {promise,resolve};};
test('a verified session starts immediately while another profile is probing',async()=>{
 const {StartupCoordinator}=await load();const c=new StartupCoordinator();const gate=deferred(),entered=deferred();
 const slow=c.verify(async()=>({probeNeeded:true,token:'slow'}),async()=>{entered.resolve();await gate.promise;});
 await entered.promise;let probes=0;
 await c.verify(async()=>({probeNeeded:false}),async()=>{probes++;});
 assert.equal(probes,0);gate.resolve();await slow;
});
test('waiting sessions recheck and do not repeat a successful profile probe',async()=>{
 const {StartupCoordinator}=await load();const c=new StartupCoordinator();let verified=false,count=0;
 const check=async()=>({probeNeeded:!verified,token:'one'});
 const probe=async()=>{count++;await new Promise(r=>setTimeout(r,5));verified=true;};
 await Promise.all([c.verify(check,probe),c.verify(check,probe)]);assert.equal(count,1);
});
test('cancelled queued preparation never starts an extra probe',async()=>{
 const {StartupCoordinator}=await load();const c=new StartupCoordinator();const gate=deferred(),entered=deferred();let cancelled=false,count=0;
 const first=c.verify(async()=>({probeNeeded:true,token:'first'}),async()=>{entered.resolve();await gate.promise;});
 await entered.promise;
 const second=c.verify(async()=>({probeNeeded:true,token:'second'}),async()=>{count++;},()=>cancelled);
 cancelled=true;const rejected=assert.rejects(second,/CANCELLED/);gate.resolve();await first;await rejected;assert.equal(count,0);
});
