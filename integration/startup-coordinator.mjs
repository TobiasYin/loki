/** Only unverified profiles wait for the single Native probe slot. */
export class StartupCoordinator {
  tail=Promise.resolve();
  async verify(check,probe,cancelled=()=>false,onProbe=()=>{}) {
    const first=await check(true);
    if(cancelled())throw new Error('CANCELLED');
    if(!first.probeNeeded)return;
    const job=this.tail.catch(()=>{}).then(async()=>{
      if(cancelled())throw new Error('CANCELLED');
      // A preceding session may have verified the same profile while we waited.
      const latest=await check(true);
      if(!latest.probeNeeded)return;
      if(cancelled())throw new Error('CANCELLED');
      onProbe();
      const status=await check(false);
      if(status.probeNeeded)await probe(status.token);
    });
    this.tail=job;
    await job;
    if(cancelled())throw new Error('CANCELLED');
  }
}
