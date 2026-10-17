(function(root){
  'use strict';
  const count=value=>Number.isSafeInteger(value) && value>=0?value:undefined;
  const terminal=new Set(['completed','failed','cancelled','interrupted']);
  function summarize(run,events,pricing){
    if(!terminal.has(run.status) || run.probe)return null;
    const provider=pricing?.providers?.[run.provider],model=provider?.models?.[run.modelId];
    const rounds=new Map();
    for(const [index,event] of events.entries()) {
      if(event.runId!==run.id || event.type!=='model_round')continue;
      const round=count(event.data?.modelRound),key=round!==undefined?`round:${round}`:`event:${event.eventId??event.id??event.sequence??index}`;
      const previous=rounds.get(key);
      if(!previous || (event.sequence??index)>=(previous.sequence??0))rounds.set(key,event);
    }
    const rows=[...rounds.values()].map(event=>{
      const d=event.data??{},read=count(d.cacheReadTokens),write=count(d.cacheWriteTokens);
      let input=count(d.totalInputTokens);
      if(input===undefined && count(d.inputTokens)!==undefined) {
        if(run.protocol==='anthropic-messages' || model?.inputExcludesCache) {
          if(read!==undefined && write!==undefined)input=count(d.inputTokens+read+write);
        }else input=count(d.inputTokens);
      }
      const time=count(event.wallTime)??(count(run.startedAt)!==undefined && count(event.elapsedMs)!==undefined?count(run.startedAt+event.elapsedMs):count(run.startedAt));
      return {input,read,write,output:count(d.outputTokens),time};
    });
    function total(field){
      const values=rows.map(row=>row[field]).filter(value=>value!==undefined);
      const value=count(values.reduce((sum,n)=>sum+n,0));
      return {value:values.length?value:undefined,partial:values.length<rows.length,reported:values.length};
    }
    function estimate(row){
      if(!['opencode-go','deepseek'].includes(run.provider) || !model || !['USD','CNY'].includes(provider?.currency) || count(pricing?.unitTokens)===undefined || pricing.unitTokens===0)return undefined;
      if(row.input===undefined || row.read===undefined || row.output===undefined)return undefined;
      if((run.protocol==='anthropic-messages' || model.inputExcludesCache) && row.write===undefined)return undefined;
      const write=row.write??0,uncached=row.input-row.read-write;
      if(uncached<0 || !Number.isSafeInteger(uncached))return undefined;
      let rates=model.rates;
      if(model.tiers)rates=model.tiers.find(tier=>tier.maxInputTokens===undefined || row.input<=tier.maxInputTokens);
      if(model.schedule) {
        const schedule=pricing.schedules?.[model.schedule],date=new Date(row.time);
        if(!schedule || row.time===undefined || !Number.isFinite(date.getTime()))return undefined;
        const minute=date.getUTCHours()*60+date.getUTCMinutes();
        const peak=schedule.weekdays.includes(date.getUTCDay()) && schedule.peakMinuteRanges.some(([start,end])=>minute>=start && minute<end);
        rates=peak?model.peak:model.offPeak;
      }
      const costs=[rates?.input,rates?.cacheRead,rates?.output,rates?.cacheWrite??rates?.input];
      if(costs.some(rate=>typeof rate!=='number' || !Number.isFinite(rate) || rate<0))return undefined;
      const amount=(uncached*costs[0]+row.read*costs[1]+row.output*costs[2]+write*costs[3])/pricing.unitTokens;
      return Number.isFinite(amount) && amount>=0?amount:undefined;
    }
    const input=total('input'),cache=total('read'),output=total('output');
    const amounts=rows.map(estimate),amount=amounts.length && [input,cache,output].every(metric=>metric.value!==undefined && !metric.partial) && amounts.every(n=>n!==undefined)?amounts.reduce((sum,n)=>sum+n,0):undefined;
    return {input,cache,output,requests:rows.length,
      amount:Number.isFinite(amount)?amount:undefined,currency:provider?.currency,priceKnown:!!model,provider:run.provider,updatedAt:pricing?.updatedAt};
  }
  function format(summary){
    if(!summary)return null;
    const tokens=metric=>metric.value===undefined?'—':metric.value.toLocaleString('en-US')+(metric.partial?'+':'');
    const parts=[`输入 ${tokens(summary.input)}`,`缓存 ${tokens(summary.cache)}`,`输出 ${tokens(summary.output)}`];
    if(summary.amount!==undefined) {
      const symbol=summary.currency==='CNY'?'¥':'US$';
      const amount=summary.amount>0 && summary.amount<.0001?`< ${symbol}0.0001`:`${symbol}${summary.amount.toLocaleString('en-US',{minimumFractionDigits:summary.amount<1?4:2,maximumFractionDigits:4})}`;
      parts.push(`估算 ${amount}`);
    }else if(['opencode-go','deepseek'].includes(summary.provider))parts.push('费用未估算');
    const detail=`本轮累计 ${summary.requests} 次模型请求；缓存命中包含在输入内；输出已包含供应商计入其中的思考 Token。— 表示未返回，+ 表示仅部分请求返回用量。`;
    const price=summary.provider==='opencode-go'?'按内置 API 单价估算额度用量，不代表订阅之外的实际扣款。':summary.provider==='deepseek'?'按内置 API 单价估算。':'';
    return {text:parts.join(' · '),title:detail+(price?` ${price}价格快照 ${summary.updatedAt??'未知'}；峰谷按请求完成时间估算，未额外推断法定假日。`:'')};
  }
  const api={summarize,format};
  if(typeof module==='object' && module.exports)module.exports=api;
  else root.ThorUsage=api;
})(typeof window==='object'?window:globalThis);
