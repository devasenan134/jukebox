"""A stand-in for Navidrome's sign-in check, for testing Jukebox locally: user "tester", password "secret"."""
import hashlib, json, sys
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import parse_qs, urlparse

USER, PASSWORD = "tester", "secret"


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        q = {k: v[0] for k, v in parse_qs(urlparse(self.path).query).items()}
        good = q.get("u") == USER and q.get("t") == hashlib.md5((PASSWORD + q.get("s", "")).encode()).hexdigest()
        body = {"subsonic-response": {"status": "ok", "version": "1.16.1"} if good else
                {"status": "failed", "version": "1.16.1", "error": {"code": 40, "message": "Wrong username or password"}}}
        data = json.dumps(body).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *a):
        pass


HTTPServer(("127.0.0.1", int(sys.argv[1]) if len(sys.argv) > 1 else 4599), Handler).serve_forever()
