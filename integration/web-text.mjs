/** Bounded, inert text extraction. Never inject source HTML into the trusted document. */
export function readableText(source, limit = 12000) {
  const entities = { amp:'&',lt:'<',gt:'>',quot:'"',apos:"'",nbsp:' ',ndash:'–',mdash:'—',hellip:'…',lsquo:'‘',rsquo:'’',ldquo:'“',rdquo:'”' };
  let text=String(source??'').replace(/<!--[\s\S]*?(?:-->|$)/g,'');
  for(const tag of ['head','script','style','noscript','template'])text=text.replace(new RegExp(`<${tag}\\b[^>]*>[\\s\\S]*?(?:<\\/${tag}\\s*>|$)`,'gi'),' ');
  text=text.replace(/<\/(?:p|div|h[1-6]|tr|li|section|article|main)\s*>|<br\b[^>]*>/gi,'\n').replace(/<\/(?:td|th)\s*>/gi,'\t').replace(/<[^>]*>/g,' ')
    .replace(/&(#x[0-9a-f]+|#[0-9]+|[a-z]+);/gi,(whole,name)=>{
      if(name[0]!=='#')return entities[name.toLowerCase()]??whole;
      const code=name[1].toLowerCase()==='x'?Number.parseInt(name.slice(2),16):Number.parseInt(name.slice(1),10);
      return code>0 && code<=0x10ffff && !(code>=0xd800 && code<=0xdfff)?String.fromCodePoint(code):whole;
    }).replace(/[ \t\r\f]+/g,' ').replace(/ *\n */g,'\n').replace(/\n{3,}/g,'\n\n').trim();
  return {text:text.slice(0,limit),truncated:text.length>limit};
}
