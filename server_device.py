#!/usr/bin/env python3
"""
Servidor RSpeech UDP Streaming a 48.000 Hz con Time Sync (Ping/Pong NTP-like).
Incluye timestamp (ms epoch) en cada paquete de audio.
"""

import os
import select
import socket
import struct
import subprocess
import sys
import threading
import time

HOST = "0.0.0.0"
PORT = 14144
SAMPLE_RATE = 48000

TYPE_AUTH = 0x01
TYPE_AUDIO = 0x02
TYPE_PING = 0x03
TYPE_PONG = 0x04

SINK_MIC_NAME = "android_mic_sink"
SOURCE_MIC_NAME = "android_mic"
SINK_SPEAKER_NAME = "android_speaker"

def log(msg):
    print(f"\n[{time.strftime('%Y-%m-%d %H:%M:%S')}] {msg}", flush=True)

def current_time_ms():
    return int(time.time() * 1000)

def run_cmd(cmd):
    try:
        res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=False)
        return res.returncode == 0, res.stdout, res.stderr
    except Exception as e:
        return False, "", str(e)

def setup_virtual_audio_devices():
    log("[*] Verificando y configurando dispositivos virtuales de audio a 48000 Hz...")

    ok, out, _ = run_cmd(["pactl", "list", "modules", "short"])
    if ok:
        for line in out.splitlines():
            parts = line.split()
            if len(parts) >= 3:
                mod_id = parts[0]
                mod_name = parts[1]
                mod_args = " ".join(parts[2:])
                if any(k in mod_args for k in [SINK_MIC_NAME, SOURCE_MIC_NAME, SINK_SPEAKER_NAME, "android_mic", "android_speaker"]):
                    log(f"[*] Limpiando módulo virtual previo: id={mod_id} ({mod_name})")
                    run_cmd(["pactl", "unload-module", mod_id])

    # 1. Crear SINK_SPEAKER (48kHz Mono)
    ok, out, _ = run_cmd([
        "pactl", "load-module", "module-null-sink",
        f"sink_name={SINK_SPEAKER_NAME}",
        "rate=48000", "channels=1",
        "sink_properties=device.description=\"Android_Speaker_Output\""
    ])
    if ok:
        log(f"[+] Sink {SINK_SPEAKER_NAME} creado con ID={out.strip()}")

    # 2. Crear SINK_MIC (48kHz Mono)
    ok, out, _ = run_cmd([
        "pactl", "load-module", "module-null-sink",
        f"sink_name={SINK_MIC_NAME}",
        "rate=48000", "channels=1",
        "sink_properties=device.description=\"Android_Mic_Sink_Internal\""
    ])
    if ok:
        log(f"[+] Sink {SINK_MIC_NAME} creado con ID={out.strip()}")

    # 3. Crear SOURCE_MIC (48kHz Mono)
    ok, out, _ = run_cmd([
        "pactl", "load-module", "module-remap-source",
        f"source_name={SOURCE_MIC_NAME}",
        f"master={SINK_MIC_NAME}.monitor",
        "rate=48000", "channels=1",
        "source_properties=device.description=\"Android_Microphone_Input\""
    ])
    if ok:
        log(f"[+] Micro virtual {SOURCE_MIC_NAME} creado con ID={out.strip()}")

def get_sink_monitor_source(sink_name):
    ok, out, _ = run_cmd(["pactl", "list", "sources", "short"])
    if ok:
        for line in out.splitlines():
            parts = line.split()
            if len(parts) >= 2 and parts[1].startswith(sink_name) and "monitor" in parts[1]:
                return parts[1]
    return f"{sink_name}.monitor"

class UdpAudioServer:
    def __init__(self):
        self.sock = None
        self.client_addr = None
        self.last_client_seen = 0
        self.lock = threading.Lock()
        self.running = True
        self.pacat_proc = None
        self.parec_proc = None
        self.send_seq = 0
        self.recv_seq = -1

    def start_audio_procs(self):
        env = os.environ.copy()
        env["PULSE_LATENCY_MSEC"] = "4"
        
        try:
            self.pacat_proc = subprocess.Popen(
                [
                    "pacat",
                    "-d", SINK_MIC_NAME,
                    "--raw",
                    "--rate=48000",
                    "--channels=1",
                    "--format=s16le",
                    "--latency-msec=4"
                ],
                stdin=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                bufsize=0,
                env=env
            )
        except Exception as e:
            log(f"[!] Error iniciando pacat: {e}")

        source_name = get_sink_monitor_source(SINK_SPEAKER_NAME)
        try:
            self.parec_proc = subprocess.Popen(
                [
                    "parec",
                    "-d", source_name,
                    "--raw",
                    "--rate=48000",
                    "--channels=1",
                    "--format=s16le",
                    "--latency-msec=4"
                ],
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                bufsize=0,
                env=env
            )
            time.sleep(0.1)
            ok, so_out, _ = run_cmd(["pactl", "list", "source-outputs"])
            if ok and self.parec_proc.pid:
                cur_id = None
                for line in so_out.splitlines():
                    if line.startswith("Source Output #") or line.startswith("Salida de fuente #"):
                        cur_id = line.split("#")[-1].strip()
                    elif f"application.process.id = \"{self.parec_proc.pid}\"" in line and cur_id:
                        run_cmd(["pactl", "move-source-output", cur_id, source_name])
                        break
        except Exception as e:
            log(f"[!] Error iniciando parec: {e}")

    def stop_audio_procs(self):
        if self.pacat_proc:
            try:
                self.pacat_proc.terminate()
            except Exception:
                pass
        if self.parec_proc:
            try:
                self.parec_proc.terminate()
            except Exception:
                pass

    def speaker_worker(self):
        chunk_size = 960  # 10ms a 48kHz mono 16-bit
        period = 0.010     # cadencia fija: un paquete (~10ms) por tick
        acc_target = chunk_size * 2  # mantener muy poco backlog (<=20ms)
        acc = bytearray()  # reensambla el audio de parec en bloques uniformes
        next_tick = time.time()
        bytes_read = 0
        pkts_sent = 0
        last_stat = time.time()
        while self.running:
            # Lectura NO bloqueante: no colgar el hilo si parec no entrega.
            if self.parec_proc and self.parec_proc.stdout and self.parec_proc.stdout.fileno() >= 0:
                try:
                    r, _, _ = select.select([self.parec_proc.stdout], [], [], 0)
                    while r:
                        piece = os.read(self.parec_proc.stdout.fileno(), 8192)
                        if not piece:
                            break
                        bytes_read += len(piece)
                        acc += piece
                        r, _, _ = select.select([self.parec_proc.stdout], [], [], 0)
                except Exception:
                    pass

            with self.lock:
                addr = self.client_addr
                active = (time.time() - self.last_client_seen < 4.0)

            # Drenar a cadencia; si hay backlog acumulado, enviar 2/tick para
            # recuperarse antes del tiempo real y volver a ~acc_target.
            # Se envía TODO (incluido silencio) para no acumular datos no enviados.
            max_per_tick = 2 if len(acc) > acc_target else 1
            sent = 0
            while (sent < max_per_tick and len(acc) >= chunk_size
                   and addr and active and self.sock):
                data = bytes(acc[:chunk_size])
                del acc[:chunk_size]
                self.send_seq = (self.send_seq + 1) & 0x7FFFFFFF
                t_send = current_time_ms()
                hdr = struct.pack(">BIQI", TYPE_AUDIO, self.send_seq, t_send, len(data))
                try:
                    self.sock.sendto(hdr + data, addr)
                    pkts_sent += 1
                except Exception:
                    pass
                sent += 1

            # Cadencia libre de deriva: fijamos el tick al siguiente múltiplo de
            # `period`, por lo que a largo plazo enviamos exactamente 100/s,
            # en lugar de `sleep(period - elapsed)` que se atrasa un 0.7% y
            # acumula backlog indefinidamente.
            next_tick = next_tick + period
            dt = next_tick - time.time()
            if dt > 0:
                time.sleep(dt)
            else:
                next_tick = time.time() + period  # nos hemos quedado atrás; resincronizar

            now = time.time()
            if now - last_stat >= 5.0:
                dur = now - last_stat
                last_stat = now
                log(f"[SRV] acc={len(acc)}B ({len(acc)/192.0:.0f}ms) "
                    f"read={bytes_read/dur:.0f}B/s pkts={pkts_sent} rate={pkts_sent/dur:.1f}/s")
                bytes_read = 0
                pkts_sent = 0

    def run(self):
        setup_virtual_audio_devices()
        self.start_audio_procs()

        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind((HOST, PORT))
        log(f"[*] Servidor UDP RSpeech escuchando en {HOST}:{PORT} (48 kHz con TimeSync)")

        speaker_thread = threading.Thread(target=self.speaker_worker, daemon=True)
        speaker_thread.start()

        while self.running:
            try:
                data, addr = self.sock.recvfrom(2048)
                if len(data) < 1:
                    continue

                msg_type = data[0]

                with self.lock:
                    if self.client_addr != addr:
                        log(f"[+] Cliente UDP detectado desde {addr}")
                        self.recv_seq = -1
                    self.client_addr = addr
                    self.last_client_seen = time.time()

                if msg_type == TYPE_AUTH:
                    # Formato AUTH ACK: [1B TYPE_PONG][4B SEQ=0][8B SERVER_TS][4B LEN=0]
                    t_server = current_time_ms()
                    resp = struct.pack(">BIQI", TYPE_PONG, 0, t_server, 0)
                    self.sock.sendto(resp, addr)

                elif msg_type == TYPE_PING:
                    # Formato PING recibido: [1B TYPE_PING][8B T_CLIENT_ORIGIN]
                    if len(data) >= 9:
                        _, t_client_orig = struct.unpack(">BQ", data[:9])
                        t_server = current_time_ms()
                        # Formato PONG respuesta: [1B TYPE_PONG][8B T_CLIENT_ORIGIN][8B T_SERVER_RECEIVE]
                        resp = struct.pack(">BQQ", TYPE_PONG, t_client_orig, t_server)
                        self.sock.sendto(resp, addr)

                elif msg_type == TYPE_AUDIO:
                    # [1B TYPE][4B SEQ][8B TIMESTAMP_MS][4B LEN][PAYLOAD]
                    if len(data) < 17:
                        continue
                    _, seq, t_send, length = struct.unpack(">BIQI", data[:17])
                    payload = data[17:17+length]

                    if self.recv_seq != -1:
                        diff = seq - self.recv_seq
                        if diff <= 0 and diff > -100000:
                            continue
                    self.recv_seq = seq

                    if self.pacat_proc and self.pacat_proc.stdin:
                        try:
                            self.pacat_proc.stdin.write(payload)
                            self.pacat_proc.stdin.flush()
                        except (BrokenPipeError, IOError):
                            pass
            except Exception as e:
                log(f"[-] Error en recvfrom UDP: {e}")

        self.stop_audio_procs()
        if self.sock:
            self.sock.close()

if __name__ == "__main__":
    server = UdpAudioServer()
    try:
        server.run()
    except KeyboardInterrupt:
        log("\n[*] Deteniendo servidor...")
        server.running = False
