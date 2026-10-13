#!/usr/bin/env python3
"""Loopback-only fixture. adb reverse tcp:8877 tcp:8877 makes it accessible from the test host."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import time
class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args): pass  # do not print headers, URLs, or credentials
    def do_HEAD(self): self.send_response(204); self.end_headers()
    def do_POST(self):
        body = self.rfile.read(min(int(self.headers.get('Content-Length', 0)), 16777216))
        self.reply(200, body)
    def reply(self, status, body):
        self.send_response(status); self.send_header('Content-Length', str(len(body))); self.end_headers(); self.wfile.write(body)
    def do_GET(self):
        if self.path == '/redirect':
            self.send_response(302); self.send_header('Location', 'http://127.0.0.1:8878/must-never-receive'); self.send_header('Content-Length', '0'); self.end_headers()
        elif self.path in ['/status400', '/status500']: self.reply(int(self.path[-3:]), ('error-' + self.path[-3:]).encode())
        elif self.path in ['/stream', '/slow']:
            payload = '中文🙂\nbytes only\n'.encode() if self.path == '/stream' else b'x' * 100
            self.send_response(200); self.send_header('Content-Length', str(len(payload))); self.end_headers()
            try:
                for byte in payload:
                    self.wfile.write(bytes([byte])); self.wfile.flush(); time.sleep(.04 if self.path == '/stream' else .5)
            except (BrokenPipeError, ConnectionResetError): pass
        elif self.path == '/auth': self.reply(200 if self.headers.get('Authorization') == 'Bearer fixture-secret' else 401, b'fixture auth')
        else: self.reply(404, b'not found')
if __name__ == '__main__':
    ThreadingHTTPServer(('127.0.0.1', 8877), Handler).serve_forever()
