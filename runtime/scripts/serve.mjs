import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
export async function serve(port = 0) {
  const server = createServer(async (req, res) => {
    const files = { '/': 'index.html', '/index.html': 'index.html', '/harness.js': 'harness.js', '/thor-runtime.js': 'thor-runtime.js' };
    const name = files[req.url];
    if (!name) { res.writeHead(404).end(); return; }
    try { res.writeHead(200, { 'Content-Type': name.endsWith('.js') ? 'text/javascript' : 'text/html', 'Cache-Control': 'no-store' }); res.end(await readFile(`dist/browser/${name}`)); }
    catch { res.writeHead(500).end('Run npm run build first'); }
  });
  await new Promise(resolve => server.listen(port, '127.0.0.1', resolve));
  return { server, url: `http://127.0.0.1:${server.address().port}` };
}
if (process.argv[1] === fileURLToPath(import.meta.url)) { const { url } = await serve(Number(process.env.THOR_RUNTIME_PORT ?? 4173)); console.log(`Local harness: ${url}`); }
