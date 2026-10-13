import { build } from 'esbuild';
import { mkdir, writeFile, copyFile } from 'node:fs/promises';
await mkdir('dist/browser', { recursive: true });
for (const [entry, name] of [['src/index.ts', 'thor-runtime'], ['demo/harness.ts', 'harness']]) {
  const result = await build({ entryPoints: [entry], outfile: `dist/browser/${name}.js`, bundle: true, format: 'esm', platform: 'browser', target: 'es2022', metafile: true, minify: true,
    // Explicitly remove guarded Node/Bun environment fallbacks. No Node polyfills.
    define: { process: 'undefined' },
  });
  const imports = Object.values(result.metafile.outputs).flatMap(o => o.imports);
  if (imports.length) throw new Error(`Browser bundle has external imports: ${JSON.stringify(imports)}`);
  await writeFile(`dist/browser/${name}.meta.json`, JSON.stringify(result.metafile, null, 2));
}
await copyFile('demo/index.html', 'dist/browser/index.html');
