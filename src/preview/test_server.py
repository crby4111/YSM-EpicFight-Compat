"""Exercise the real local HTTP handler without Java, models, a browser or a game."""
import http.client
import importlib.util
import json
from pathlib import Path
import tempfile
import threading
import unittest
from http.server import ThreadingHTTPServer

spec = importlib.util.spec_from_file_location('preview_http', Path(__file__).with_name('server.py'))
preview = importlib.util.module_from_spec(spec)
spec.loader.exec_module(preview)


class Worker:
    def __init__(self):
        self.calls = []

    def call(self, request):
        self.calls.append(dict(request))
        if request['command'] == 'catalogue' and Path(request['root']).name == 'unreadable':
            raise ValueError('fixture: model catalogue could not be read')
        return {'models': ['fixture']} if request['command'] == 'catalogue' else {'ok': True}


class PreviewHttpTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.original = self.root / 'original'
        self.original.mkdir()
        self.sentinel = self.original / 'keep.json'
        self.sentinel.write_bytes(b'{"must_remain":"unchanged"}')
        self.worker = Worker()
        self.app = preview.App(self.worker, self.original)
        self.http = ThreadingHTTPServer(('127.0.0.1', 0), preview.Handler)
        self.http.daemon_threads = True
        self.http.app = self.app
        self.thread = threading.Thread(target=self.http.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.http.shutdown()
        self.http.server_close()
        self.thread.join(timeout=2)
        self.assertEqual(b'{"must_remain":"unchanged"}', self.sentinel.read_bytes())
        self.temporary.cleanup()

    def post(self, operation, body=None, **headers):
        payload = json.dumps(body if body is not None else {}).encode()
        request_headers = {'X-Preview-Token': self.app.token,
                           'Content-Type': 'application/json', **headers}
        connection = http.client.HTTPConnection(*self.http.server_address, timeout=2)
        try:
            connection.request('POST', '/api/' + operation, payload, request_headers)
            response = connection.getresponse()
            return response.status, json.loads(response.read())
        finally:
            connection.close()

    def test_failed_catalogue_keeps_previous_directory_and_recovers(self):
        bad = self.root / 'unreadable'
        bad.mkdir()
        code, error = self.post('catalogue', {'root': str(bad)})
        self.assertEqual(400, code)
        self.assertIn('could not be read', error['error'])
        self.assertEqual(str(self.original.resolve()), self.app.root)
        self.assertEqual(200, self.post('inspect')[0])
        self.assertEqual(str(self.original.resolve()), self.worker.calls[-1]['root'])
        good = self.root / 'valid'
        good.mkdir()
        self.assertEqual(200, self.post('catalogue', {'root': str(good)})[0])
        self.assertEqual(str(good.resolve()), self.app.root)

    def test_token_and_foreign_origin_reject_before_worker(self):
        self.assertEqual(403, self.post('inspect', **{'X-Preview-Token': 'invalid'})[0])
        self.assertEqual(403, self.post('inspect', Origin='https://foreign.invalid')[0])
        self.assertEqual([], self.worker.calls)
        origin = 'http://127.0.0.1:' + str(self.http.server_port)
        self.assertEqual(200, self.post('inspect', Origin=origin)[0])

    def test_invalid_shape_and_oversized_body_do_not_reach_worker(self):
        self.assertEqual(400, self.post('inspect', [1, 2])[0])
        # Reject from the announced length before reading/allocating the body.
        # Do not stream a large body into a socket intentionally closed by HTTP/1.0:
        # Windows can reset that upload before the client receives the response.
        self.assertEqual(400, self.post('sample', **{
            'Content-Length': str(256 * 1024 + 1)})[0])
        self.assertEqual([], self.worker.calls)
        self.assertEqual(200, self.post('inspect')[0])

    def test_inspect_cannot_override_the_selected_root(self):
        self.assertEqual(200, self.post('inspect', {'root': str(self.root / 'other')})[0])
        self.assertEqual(str(self.original.resolve()), self.worker.calls[-1]['root'])
        self.assertEqual(('127.0.0.1', self.http.server_port), self.http.server_address)


if __name__ == '__main__':
    unittest.main(verbosity=2)
