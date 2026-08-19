#!/usr/bin/env python3
"""
Servidor TCP de prueba para la app RSpeech
Recibe audio del micro del móvil y permite enviar audio de vuelta.
Formato: PCM 16-bit Mono @ 16000 Hz
"""

import socket
import struct
import sys
import threading

HOST = "0.0.0.0"
PORT = 14144

TYPE_AUTH = 0x01
TYPE_AUDIO = 0x02
TYPE_PING = 0x03
TYPE_PONG = 0x04

def recv_exact(sock, n):
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            return None
        buf.extend(chunk)
    return bytes(buf)

def handle_client(conn, addr):
    print(f"[+] Conexión establecida desde {addr}", flush=True)
    try:
        while True:
            # Leer cabecera exacta de 5 bytes (1 byte tipo + 4 bytes longitud)
            header = recv_exact(conn, 5)
            if not header:
                print(f"[*] Cliente {addr} cerró conexión (sin cabecera)", flush=True)
                break

            msg_type, length = struct.unpack(">BI", header)

            # Leer payload completo
            payload = recv_exact(conn, length)
            if payload is None:
                print(f"[*] Cliente {addr} cerró conexión durante payload", flush=True)
                break

            if msg_type == TYPE_AUTH:
                auth_str = payload.decode("utf-8", errors="ignore")
                print(f"[*] Autenticación recibida: {auth_str}", flush=True)
            elif msg_type == TYPE_AUDIO:
                print(f"[Audio] Recibidos {len(payload)} bytes de PCM", flush=True)
            elif msg_type == TYPE_PONG:
                pass
    except Exception as e:
        print(f"[-] Error con cliente {addr}: {e}", flush=True)
    finally:
        conn.close()
        print(f"[-] Cliente {addr} desconectado", flush=True)

def main():
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind((HOST, PORT))
    s.listen(5)
    print(f"[*] Servidor RSpeech escuchando en {HOST}:{PORT}")

    try:
        while True:
            conn, addr = s.accept()
            t = threading.Thread(target=handle_client, args=(conn, addr), daemon=True)
            t.start()
    except KeyboardInterrupt:
        print("\nCerrando servidor...")
    finally:
        s.close()

if __name__ == "__main__":
    main()
