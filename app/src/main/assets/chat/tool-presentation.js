(function(root){
  'use strict';
  const id=value=>typeof value==='string' && /^[A-Za-z0-9_-]{1,160}$/.test(value);
  const finite=value=>typeof value==='number' && Number.isFinite(value);
  const tool=call=>call.toolId??call.toolName?.replace('_','.');
  const gestures=new Set(['input.tap','input.long_press','input.swipe','input.drag','input.activate']);
  function objects(call){
    return (call.result?.content??[]).slice(0,12).flatMap(content=>{
      if(content.type!=='text' || typeof content.text!=='string' || content.text.length>65536)return [];
      try{const value=JSON.parse(content.text);return value && typeof value==='object' && !Array.isArray(value)?[value]:[];}catch{return [];}
    });
  }
  function transform(value){
    if(!value || !['left','top','displayWidth','displayHeight','imageWidth','imageHeight'].every(key=>finite(value[key])))return null;
    if(value.left<0 || value.top<0 || ['displayWidth','displayHeight','imageWidth','imageHeight'].some(key=>value[key]<=0 || value[key]>32768))return null;
    return value;
  }
  function capture(call){
    if(!['perception.capture_screen','perception.capture_companion'].includes(tool(call)) || call.error || call.result?.contentDiscarded)return null;
    const attachment=(call.result?.content??[]).find(item=>item.type==='attachment' && id(item.attachmentId));
    if(!attachment)return null;
    const metadata=objects(call).find(item=>item.frameId || item.companionImageTransform || item.width)??{};
    return {attachmentId:attachment.attachmentId,frameId:metadata.frameId,transform:transform(metadata.transform),
      width:metadata.width,height:metadata.height,role:typeof metadata.displayRole==='string'?metadata.displayRole:undefined,readOnly:metadata.readOnly===true || metadata.frameInputUsable===false || tool(call)==='perception.capture_companion'};
  }
  function annotation(image,points,bounds,space){
    if(!['frame_pixels','display_logical'].includes(space))return null;
    const t=transform(image.transform);if(!t)return null;
    const map=p=>space==='frame_pixels'?p:{x:(p.x-t.left)*t.imageWidth/t.displayWidth,y:(p.y-t.top)*t.imageHeight/t.displayHeight};
    const inside=(p,edge=false)=>finite(p.x) && finite(p.y) && p.x>=0 && p.y>=0 && (edge?p.x<=t.imageWidth && p.y<=t.imageHeight:p.x<t.imageWidth && p.y<t.imageHeight);
    if(bounds) {
      if(!['left','top','right','bottom'].every(key=>finite(bounds[key])) || bounds.left>=bounds.right || bounds.top>=bounds.bottom)return null;
      const start=map({x:bounds.left,y:bounds.top}),end=map({x:bounds.right,y:bounds.bottom});
      return inside(start,true) && inside(end,true)?{bounds:{x:start.x,y:start.y,width:end.x-start.x,height:end.y-start.y},width:t.imageWidth,height:t.imageHeight}:null;
    }
    if(!Array.isArray(points) || points.length<1 || points.length>32 || !points.every(p=>p && finite(p.x) && finite(p.y)))return null;
    const mapped=points.map(map);return mapped.every(p=>inside(p))?{points:mapped,width:t.imageWidth,height:t.imageHeight}:null;
  }
  function describe(call,calls=[]){
    const kind=tool(call);
    if(kind==='web.search')return {kind:'search',query:typeof call.arguments?.query==='string'?call.arguments.query:objects(call).find(value=>typeof value.query==='string')?.query??''};
    const image=capture(call);if(image)return {kind:'image',image};
    if(['perception.capture_screen','perception.capture_companion'].includes(kind))return {kind:'missing-image'};
    if(!gestures.has(kind))return null;
    const visual=call.result?.visual;
    if(visual && visual.kind===kind && id(visual.attachmentId) && visual.coordinateSpace==='display_logical') {
      const image={attachmentId:visual.attachmentId,frameId:visual.frameId,transform:transform(visual.transform),role:typeof visual.displayRole==='string'?visual.displayRole:undefined};
      const marker=annotation(image,visual.points,visual.bounds,visual.coordinateSpace);
      if(marker)return {kind:'action',image,marker,component:!!marker.bounds};
    }
    // Old histories with explicit frame_pixels are exact too. Never substitute
    // an arbitrary recent screen, companion image or another run's frame.
    const args=call.arguments??{};
    if(args.coordinateSpace==='frame_pixels' && typeof args.frameId==='string') {
      const source=calls.find(candidate=>candidate!==call && candidate.runId===call.runId && candidate.sessionId===call.sessionId && candidate.firstSequence<call.firstSequence && candidate.resultSequence<call.firstSequence && capture(candidate)?.frameId===args.frameId);
      const image=source?capture(source):null;
      if(image && !image.readOnly) {
        const marker=annotation(image,args.points??[{x:args.x,y:args.y}],null,'frame_pixels');
        if(marker)return {kind:'action',image,marker};
      }
    }
    return {kind:'missing-action',component:kind==='input.activate',x:args.x,y:args.y};
  }
  const api={describe,annotation};
  if(typeof module==='object' && module.exports)module.exports=api;else root.ThorToolPresentation=api;
})(typeof window==='object'?window:globalThis);
