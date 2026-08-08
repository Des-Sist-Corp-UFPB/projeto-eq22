"""Local OpenAI-compatible stub for observability evidence only.

Answers /v1/chat/completions with a fixed, valid scene analysis after a
deterministic delay, so the assisted-analysis route can be exercised end to end
without a paid provider call. Never referenced by the Dockerfile,
docker-compose.yml or any production deploy; it only exists behind
docker-compose.llm-stub.yml. The delay lives here, never in application code.
"""

import json
import os
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DELAY_MS = int(os.environ.get("IWRITE_LLM_STUB_DELAY_MS", "2500"))
PORT = int(os.environ.get("IWRITE_LLM_STUB_PORT", "8080"))

ANALYSIS = {
    "summary": "Stubbed analysis used only for local observability evidence.",
    "tone": "neutral",
    "pacing": "steady",
    "strengths": ["stub strength"],
    "issues": ["stub issue"],
    "suggestions": ["stub suggestion"],
}


class OpenAiStubHandler(BaseHTTPRequestHandler):
    def do_POST(self):  # noqa: N802 - name fixed by BaseHTTPRequestHandler
        self.rfile.read(int(self.headers.get("Content-Length", 0)))
        time.sleep(DELAY_MS / 1000)
        body = json.dumps(
            {
                "id": "chatcmpl-stub",
                "object": "chat.completion",
                "created": 0,
                "model": "gpt-4o-mini",
                "choices": [
                    {
                        "index": 0,
                        "message": {"role": "assistant", "content": json.dumps(ANALYSIS)},
                        "finish_reason": "stop",
                    }
                ],
                "usage": {"prompt_tokens": 100, "completion_tokens": 50, "total_tokens": 150},
            }
        ).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        """Silence the request log: prompts must never reach a local log."""


if __name__ == "__main__":
    print(f"openai stub listening on {PORT} with {DELAY_MS}ms delay", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), OpenAiStubHandler).serve_forever()
