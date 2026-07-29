#!/usr/bin/env python3
"""
XAU Tick Server para Windows VPS
- Porta 8766 : recebe ticks do EA MT5  (HTTP POST /tick, built-in, sem dependencias)
- Porta 8765 : envia ticks ao app Android (WebSocket, pip install websockets)

Instalar: pip install websockets
Executar:  python server.py
"""

import asyncio
import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from typing import Optional, Set

import websockets

clients: Set = set()
last_tick: Optional[dict] = None
_loop: Optional[asyncio.AbstractEventLoop] = None


# ── HTTP server (recebe ticks do EA) ─────────────────────────────────────────

class TickHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        global last_tick
        if self.path != "/tick":
            self.send_response(404)
            self.end_headers()
            return
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length)
        try:
            data = json.loads(body)
            last_tick = data
            bid = data.get("bid", "?")
            ask = data.get("ask", "?")
            print(f"[TICK] bid={bid} ask={ask} clientes={len(clients)}", flush=True)
            if _loop and clients:
                asyncio.run_coroutine_threadsafe(_broadcast(data), _loop)
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"ok")
        except Exception as exc:
            self.send_response(400)
            self.end_headers()
            self.wfile.write(str(exc).encode())

    def log_message(self, fmt, *args):  # silencia logs HTTP de linha
        pass


def _run_http():
    srv = HTTPServer(("0.0.0.0", 8766), TickHandler)
    print("[HTTP] Porta 8766 pronta — aguardando ticks do MT5...", flush=True)
    srv.serve_forever()


# ── WebSocket server (envia ticks ao Android) ────────────────────────────────

async def _broadcast(data: dict):
    dead: Set = set()
    msg = json.dumps(data)
    for ws in clients:
        try:
            await ws.send(msg)
        except Exception:
            dead.add(ws)
    clients.difference_update(dead)


async def _ws_handler(websocket, path=None):
    clients.add(websocket)
    print(f"[WS]  Cliente conectado. Total: {len(clients)}", flush=True)
    if last_tick:
        await websocket.send(json.dumps(last_tick))
    try:
        async for _ in websocket:
            pass
    except Exception:
        pass
    finally:
        clients.discard(websocket)
        print(f"[WS]  Cliente desconectado. Total: {len(clients)}", flush=True)


async def _main():
    global _loop
    _loop = asyncio.get_event_loop()

    t = threading.Thread(target=_run_http, daemon=True)
    t.start()

    print("[WS]  Porta 8765 pronta — aguardando app Android...", flush=True)
    print("", flush=True)
    print("  EA  → POST http://127.0.0.1:8766/tick", flush=True)
    print("  App → ws://148.224.63.171:8765/ws", flush=True)

    async with websockets.serve(_ws_handler, "0.0.0.0", 8765):
        await asyncio.Future()


if __name__ == "__main__":
    asyncio.run(_main())
