import contextlib
import io
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from tempfile import TemporaryDirectory
import threading
import unittest
from unittest.mock import patch

import ingest


class ImportTest(unittest.TestCase):
    def setUp(self):
        self.messages = []
        self.fail_tool = False
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_POST(self):
                message = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                owner.messages.append((self.path, dict(self.headers), message))
                if 'id' not in message:
                    self.send_response(202)
                    self.send_header('Content-Length', '0')
                    self.end_headers()
                    return
                if message['method'] == 'initialize':
                    result = {'protocolVersion': '2025-06-18', 'capabilities': {'tools': {}},
                              'serverInfo': {'name': 'test', 'version': '1'}}
                else:
                    result = {'isError': owner.fail_tool, 'content': [{'type': 'text', 'text':
                              'provider unavailable' if owner.fail_tool else json.dumps(
                                  {'documentId': 'id', 'chunks': 1, 'unchanged': False})}]}
                body = json.dumps({'jsonrpc': '2.0', 'id': message['id'], 'result': result}).encode()
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.url = f'http://127.0.0.1:{self.server.server_port}/mcp'

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=3)

    def test_import_uses_mcp_and_preserves_unicode_and_authentication(self):
        with TemporaryDirectory() as temp:
            Path(temp, '知识.md').write_text('第一行\n第二行', encoding='utf-8')
            with patch('sys.argv', ['ingest.py', temp, '--namespace', 'engineering', '--url', self.url]), \
                    patch.dict('os.environ', {'TUAI_API_KEY': 'test-token'}), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, ingest.main())
        self.assertEqual(['initialize', 'notifications/initialized', 'tools/call'],
                         [message['method'] for _, _, message in self.messages])
        self.assertTrue(all(path == '/mcp' for path, _, _ in self.messages))
        self.assertTrue(all(headers['Authorization'] == 'Bearer test-token' for _, headers, _ in self.messages))
        self.assertTrue(all('text/event-stream' in headers['Accept'] for _, headers, _ in self.messages))
        params = self.messages[-1][2]['params']
        self.assertEqual('kb_upsert_document', params['name'])
        self.assertEqual('第一行\n第二行', params['arguments']['content'])
        self.assertEqual('engineering', params['arguments']['namespace'])

    def test_tool_failure_is_reported_as_failed_import(self):
        self.fail_tool = True
        with TemporaryDirectory() as temp:
            Path(temp, 'a.md').write_text('text', encoding='utf-8')
            errors = io.StringIO()
            with patch('sys.argv', ['ingest.py', temp, '--namespace', 'test', '--url', self.url]), \
                    contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(errors):
                self.assertEqual(1, ingest.main())
            self.assertIn('provider unavailable', errors.getvalue())

    def test_dry_run_does_not_connect_or_import_secrets(self):
        with TemporaryDirectory() as temp:
            Path(temp, 'a.md').write_text('text', encoding='utf-8')
            Path(temp, '.env').write_text('secret', encoding='utf-8')
            output = io.StringIO()
            with patch('sys.argv', ['ingest.py', temp, '--namespace', 'test', '--url', self.url, '--dry-run']), \
                    contextlib.redirect_stdout(output):
                self.assertEqual(0, ingest.main())
            self.assertIn('a.md', output.getvalue())
            self.assertNotIn('.env', output.getvalue())
            self.assertEqual([], self.messages)


if __name__ == '__main__':
    unittest.main()
