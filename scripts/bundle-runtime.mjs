import { build } from '../runtime/node_modules/esbuild/lib/main.js';
import { mkdir, writeFile } from 'node:fs/promises';
await mkdir('build/integration', {recursive:true});
for(const variant of ['debug','release']) {
  await mkdir(`build/runtime-assets/${variant}/chat`, {recursive:true});
  const result = await build({entryPoints:['integration/runtime-host.ts'],bundle:true,platform:'browser',format:'iife',target:'chrome110',outfile:`build/runtime-assets/${variant}/chat/runtime-host.js`,metafile:true,minify:true,define:{process:'undefined',__THOR_DEBUG__:String(variant==='debug')}});
  if(Object.values(result.metafile.outputs).some(o=>o.imports.length))throw new Error('External runtime imports');
  await writeFile(`build/integration/runtime-bundle-${variant}.json`,JSON.stringify(result.metafile,null,2));
}
