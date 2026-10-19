#!/usr/bin/env python3
"""Tiny ADB client for the TV, built on the adb-shell package.

Used instead of the stock adb binary because macOS Local Network privacy can
block adb ("No route to host") even when port 5555 is open.

Usage:
  tv.py shell '<command>'          run one shell command, print output
  tv.py key KEY [KEY ...]          inject key events (DPAD_UP, HOME, ENTER, ...)
  tv.py shot out.png               screenshot from the TV
  tv.py push local remote          push a file (base64 chunks, md5 verified)
  tv.py pull remote local          pull a file (base64, md5 verified)
  tv.py install app.apk            push + pm install -r (files >20 MB go over nc)
Env: TV_IP (default 192.168.1.232)
"""
import base64, hashlib, os, sys, time
from adb_shell.adb_device import AdbDeviceTcp
from adb_shell.auth.keygen import keygen
from adb_shell.auth.sign_pythonrsa import PythonRSASigner

HERE = os.path.dirname(os.path.abspath(__file__))
KEY = os.path.join(HERE, "adbkey")
IP = os.environ.get("TV_IP", "192.168.1.232")


def connect():
    if not os.path.exists(KEY):
        keygen(KEY)
    with open(KEY) as f:
        priv = f.read()
    with open(KEY + ".pub") as f:
        pub = f.read()
    dev = AdbDeviceTcp(IP, 5555, default_transport_timeout_s=30.0)
    dev.connect(rsa_keys=[PythonRSASigner(pub, priv)], auth_timeout_s=120)
    return dev


def sh(dev, cmd, timeout=120):
    return dev.shell(cmd, transport_timeout_s=timeout, read_timeout_s=timeout,
                     decode=True)


def push(dev, local, remote, chunk=48 * 1024):
    data = open(local, "rb").read()
    want = hashlib.md5(data).hexdigest()
    tmp = remote + ".b64"
    sh(dev, f"rm -f '{tmp}' '{remote}'")
    b64 = base64.b64encode(data).decode()
    for i in range(0, len(b64), chunk):
        sh(dev, f"echo -n '{b64[i:i+chunk]}' >> '{tmp}'")
    sh(dev, f"base64 -d '{tmp}' > '{remote}' && rm -f '{tmp}'")
    got = sh(dev, f"md5sum '{remote}'").split()[0]
    if got != want:
        sys.exit(f"md5 mismatch: {got} != {want}")
    print(f"pushed {len(data)} bytes, md5 ok")


def stream_push(dev, local, remote, port=9876):
    """Fast push for big files: the TV listens with nc and we stream raw bytes.
    (ADB's own sync push stalls on this TV, and base64 is slow past ~20 MB.)"""
    import socket, threading
    data = open(local, "rb").read()
    sh(dev, f"rm -f '{remote}'")
    lis = connect()
    th = threading.Thread(target=lambda: sh(
        lis, f"toybox nc -l -p {port} > '{remote}'", timeout=900))
    th.start()
    time.sleep(3)
    s = socket.create_connection((IP, port), timeout=60)
    s.sendall(data)
    s.shutdown(socket.SHUT_WR)
    th.join(900)
    s.close()
    lis.close()
    got = sh(dev, f"md5sum '{remote}'").split()[0]
    if got != hashlib.md5(data).hexdigest():
        sys.exit("md5 mismatch on stream push")
    print(f"pushed {len(data)} bytes, md5 ok")


def pull(dev, remote, local):
    out = dev.shell(f"base64 '{remote}' | tr -d '\\n'", decode=False,
                    transport_timeout_s=120, read_timeout_s=120)
    data = base64.b64decode(out)
    want = sh(dev, f"md5sum '{remote}'").split()[0]
    if hashlib.md5(data).hexdigest() != want:
        sys.exit("md5 mismatch on pull")
    open(local, "wb").write(data)


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    cmd, args = sys.argv[1], sys.argv[2:]
    dev = connect()
    try:
        if cmd == "shell":
            print(sh(dev, " ".join(args)), end="")
        elif cmd == "key":
            for k in args:
                if k.startswith("sleep"):
                    time.sleep(float(k[5:] or 1))
                    continue
                sh(dev, f"input keyevent KEYCODE_{k}" if not k.isdigit()
                   else f"input keyevent {k}")
                time.sleep(0.35)
        elif cmd == "shot":
            sh(dev, "screencap -p /data/local/tmp/shot.png")
            pull(dev, "/data/local/tmp/shot.png", args[0])
            print(args[0])
        elif cmd == "push":
            push(dev, args[0], args[1])
        elif cmd == "pull":
            pull(dev, args[0], args[1])
        elif cmd == "install":
            big = os.path.getsize(args[0]) > 20 * 1024 * 1024
            (stream_push if big else push)(dev, args[0], "/data/local/tmp/app.apk")
            print(sh(dev, "pm install -r -t /data/local/tmp/app.apk; "
                          "rm -f /data/local/tmp/app.apk", timeout=600), end="")
        else:
            sys.exit(__doc__)
    finally:
        dev.close()


if __name__ == "__main__":
    main()
