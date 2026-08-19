#!/usr/bin/env python3
"""
Servidor TCP RSpeech con:
1. Reproducción en altavoz local del PC con pw-play / aplay del audio recibido del móvil.
2. Envío periódico (cada 5s) del audio /tmp/altavoz_activado.raw hacia el móvil.
"""

import os
import socket
import struct
import subprocess
import threading
import time

HOST = "0.0.0.0"
PORT = 14144

TYPE_AUTH = 0x01
TYPE_AUDIO = 0x02
TYPE_PING = 0x03
TYPE_PONG = 0x04

AUDIO_FILE = "/tmp/altavoz_activado.raw"

def get_audio_payload():
    if os.path.exists(AUDIO_FILE):
        with open(AUDIO_FILE, "rb") as f:
            return f.read()
    return b""

def recv_exact(sock, n):
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            return None
        buf.extend(chunk)
    return bytes(buf)

def periodic_sender(conn, addr, stop_event):
    raw_data = get_audio_payload()
    if not raw_data:
        print("[!] No se encontró /tmp/altavoz_activado.raw para enviar", flush=True)
        return

    print(f"[*] Hilo de envío periódico iniciado para {addr} ({len(raw_data)} bytes)", flush=True)
    # Enviar en bloques pequeños para streaming suave (p.ej. 640 bytes = 20ms @ 16kHz 16-bit mono)
    chunk_size = 640
    
    while not stop_event.is_set():
        # Esperar 5 segundos entre reproducciones completas
        for _ in range(50):
            if stop_event.is_set():
                return
            time.sleep(0.1)

        print(f"[->] Enviando audio de prueba al móvil ({addr})...", flush=True)
        try:
            for i in range(0, len(raw_data), chunk_size):
                if stop_event.is_set():
                    return
                part = raw_data[i:i + chunk_size]
                hdr = struct.pack(">BI", TYPE_AUDIO, len(part))
                conn.sendall(hdr + part)
                time.sleep(0.018) # ~20ms por bloque de audio
        except Exception as e:
            print(f"[-] Error enviando audio periódico a {addr}: {e}", flush=True)
            break

def handle_client(conn, addr):
    print(f"[+] Conexión establecida desde {addr}", flush=True)
    
    # Abrir proceso de reproducción de audio local en el PC con paplay
    play_proc = None
    try:
        play_proc = subprocess.Popen(
            ["paplay", "--raw", "--rate=16000", "--channels=1", "--format=s16le"],
            stdin=subprocess.PIPE,
            stderr=subprocess.DEVNULL
        )
    except Exception:
        try:
            play_proc = subprocess.Popen(
                ["pw-play", "--rate=16000", "--channels=1", "--format=s16", "-"],
                stdin=subprocess.PIPE,
                stderr=subprocess.DEVNULL
            )
        except Exception as e:
            print(f"[!] No se pudo abrir reproductor de audio local: {e}", flush=True)

    stop_event = threading.Event()
    sender_thread = threading.Thread(target=periodic_sender, args=(conn, addr, stop_event), daemon=True)
    sender_thread.start()

    try:
        while True:
            header = recv_exact(conn, 5)
            if not header:
                break

            msg_type, length = struct.unpack(">BI", header)
            payload = recv_exact(conn, length)
            if payload is None:
                break

            if msg_type == TYPE_AUTH:
                auth_str = payload.decode("utf-8", errors="ignore")
                print(f"[*] Autenticación recibida: {auth_str}", flush=True)
            elif msg_type == TYPE_AUDIO:
                print(f"[<- Audio del móvil] {len(payload)} bytes", flush=True)
                if play_proc and play_proc.stdin:
                    try:
                        play_proc.stdin.write(payload)
                        play_proc.stdin.flush()
                    except Exception as pe:
                        print(f"[-] Error reproduciendo en altavoz PC: {pe}", flush=True)
            elif msg_type == TYPE_PONG:
                pass
    except Exception as e:
        print(f"[-] Error con cliente {addr}: {e}", flush=True)
    finally:
        stop_event.set()
        if play_proc:
            try:
                play_proc.stdin.close()
                play_proc.terminate()
            except Exception:
                pass
        conn.close()
        print(f"[-] Cliente {addr} desconectado", flush=True)

def main():
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind((HOST, PORT))
    s.listen(5)
    print(f"[*] Servidor RSpeech escuchando en {HOST}:{PORT}", flush=True)

    try:
        while True:
            conn, addr = s.accept()
            t = threading.Thread(target=handle_client, args=(conn, addr), daemon=True)
            t.start()
    except KeyboardInterrupt:
        print("\nCerrando servidor...", flush=True)
    finally:
        s.close()

if __name__ == "__main__":
    main()
