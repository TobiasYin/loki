# Local Markdown dependencies

The APK loads these bundled scripts under the existing content security policy. It does not fetch a parser or sanitizer from a CDN.

- `marked.umd.js`: marked **15.0.12**, copied from the exact `app/package-lock.json` dependency `node_modules/marked/lib/marked.umd.js`. License: `marked.LICENSE.md` (MIT).
- `purify.min.js`: DOMPurify **3.3.1**, copied from `node_modules/dompurify/dist/purify.min.js`. License: `dompurify.LICENSE` (Apache 2.0 or MPL 2.0).

`ui.js` escapes raw HTML, displays Markdown image descriptions as text, sanitizes the generated markup with an explicit tag/attribute allowlist, and intercepts valid HTTP(S) links for Native opening. User messages and tool values remain plain text. Tests cover malicious HTML and URL schemes, Markdown formatting, links, and streamed message DOM retention. Update the exact package versions, lockfile, local files and licenses together.
