#!/usr/bin/env python3
"""Minimal WebSocket server (RFC 6455) for testing the Sanji Control client.
Listens on ws://127.0.0.1:8765, accepts one connection, completes the
HTTP Upgrade handshake, reads the hello text frame, replies with a fake
'tap' command frame, reads the client's result frame, then closes.
Prints each step so the test outcome is visible.
"""
import base64
import hashlib
import socket
import struct
import sys

HOST = "127.0.0.1"
PORT = 8765
GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


def read_exact(conn, n):
    data = b""
    while len(data) < n:
        chunk = conn.recv(n - len(data))
        if not chunk:
            raise ConnectionError("connection closed")
        data += chunk
    return data


def read_http_headers(conn):
    buf = b""
    while b"\r\n\r\n" not in buf:
        chunk = conn.recv(4096)
        if not chunk:
            raise ConnectionError("connection closed during handshake")
        buf += chunk
    head, _ = buf.split(b"\r\n\r\n", 1)
    return head.decode("utf-8", "replace")


def send_frame(conn, opcode, payload):
    header = bytes([0x80 | opcode])
    ln = len(payload)
    if ln < 126:
        header += bytes([ln])
    elif ln < 65536:
        header += bytes([126]) + struct.pack(">H", ln)
    else:
        header += bytes([127]) + struct.pack(">Q", ln)
    conn.sendall(header + payload)


def read_frame(conn):
    b0, b1 = read_exact(conn, 2)
    fin = bool(b0 & 0x80)
    opcode = b0 & 0x0F
    masked = bool(b1 & 0x80)
    ln = b1 & 0x7F
    if ln == 126:
        ln = struct.unpack(">H", read_exact(conn, 2))[0]
    elif ln == 127:
        ln = struct.unpack(">Q", read_exact(conn, 8))[0]
    mask = read_exact(conn, 4) if masked else None
    payload = read_exact(conn, ln)
    if masked and mask:
        payload = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
    return fin, opcode, payload


def main():
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((HOST, PORT))
    srv.listen(1)
    srv.settimeout(30)
    print(f"listening ws://{HOST}:{PORT}", flush=True)
    conn, addr = srv.accept()
    print(f"accepted connection from {addr}", flush=True)
    conn.settimeout(20)
    try:
        head = read_http_headers(conn)
        print("--- HTTP Upgrade request ---", flush=True)
        print(head, flush=True)
        key = None
        for line in head.split("\r\n"):
            if line.lower().startswith("sec-websocket-key:"):
                key = line.split(":", 1)[1].strip()
        if not key:
            print("ERROR: no Sec-WebSocket-Key", flush=True)
            sys.exit(1)
        accept = base64.b64encode(
            hashlib.sha1((key + GUID).encode()).digest()
        ).decode()
        resp = (
            "HTTP/1.1 101 Switching Protocols\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Accept: {accept}\r\n\r\n"
        )
        conn.sendall(resp.encode())
        print("sent 101 Switching Protocols", flush=True)

        fin, opcode, payload = read_frame(conn)
        print(f"received frame opcode={opcode} fin={fin} payload={payload!r}", flush=True)
        hello = payload.decode("utf-8", "replace")
        assert '"hello"' in hello, "expected a hello message"
        print("HELLO OK", flush=True)

        # Send a fake tap command.
        cmd = b'{"id":"t1","action":"tap","x":10,"y":20}'
        send_frame(conn, 0x1, cmd)
        print(f"sent command frame: {cmd!r}", flush=True)

        fin, opcode, payload = read_frame(conn)
        print(f"received frame opcode={opcode} fin={fin} payload={payload!r}", flush=True)
        result = payload.decode("utf-8", "replace")
        assert '"result"' in result, "expected a result message"
        print("RESULT OK", flush=True)

        # Send a close frame.
        send_frame(conn, 0x8, b"")
        print("sent close frame", flush=True)
    finally:
        conn.close()
        srv.close()
    print("SERVER DONE", flush=True)


if __name__ == "__main__":
    main()
