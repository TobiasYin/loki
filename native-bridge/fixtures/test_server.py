import unittest
import threading
import urllib.request
import urllib.error
from http.server import ThreadingHTTPServer
from server import Handler

class FixturesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.url = 'http://127.0.0.1:' + str(cls.server.server_port)
    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown(); cls.server.server_close(); cls.thread.join()
    def test_stream(self):
        with urllib.request.urlopen(self.url + '/stream', timeout=3) as r:
            self.assertEqual(r.read(), '中文🙂\nbytes only\n'.encode())
    def test_status_error_bodies(self):
        for status in [400, 500]:
            with self.assertRaises(urllib.error.HTTPError) as caught:
                urllib.request.urlopen(self.url + '/status' + str(status), timeout=3)
            with caught.exception as r:
                self.assertEqual(r.code, status); self.assertEqual(r.read(), ('error-' + str(status)).encode())
    def test_auth_and_redirect_fixture(self):
        request = urllib.request.Request(self.url + '/auth', headers={'Authorization': 'Bearer fixture-secret'})
        with urllib.request.urlopen(request, timeout=3) as r: self.assertEqual(r.status, 200)
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *args, **kwargs): return None
        opener = urllib.request.build_opener(NoRedirect())
        with self.assertRaises(urllib.error.HTTPError) as caught: opener.open(self.url + '/redirect', timeout=3)
        with caught.exception as r:
            self.assertEqual(r.code, 302); self.assertEqual(r.headers['Location'], 'http://127.0.0.1:8878/must-never-receive')
    def test_opaque_echo_and_head(self):
        body = bytes([0, 255, 1, 128])
        with urllib.request.urlopen(urllib.request.Request(self.url + '/echo', data=body), timeout=3) as r: self.assertEqual(r.read(), body)
        with urllib.request.urlopen(urllib.request.Request(self.url + '/stream', method='HEAD'), timeout=3) as r:
            self.assertEqual(r.status, 204); self.assertEqual(r.read(), b'')

if __name__ == '__main__': unittest.main()
