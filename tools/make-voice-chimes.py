#!/usr/bin/env python3
"""Generates the voice-button chimes (res/raw/voice_start.wav, voice_stop.wav):
two soft sine notes with a smooth envelope, rising for "listening", falling
for "done". Original sounds, no samples. Run: tools/.venv/bin/python tools/make-voice-chimes.py"""
import math, os, struct, wave

RATE = 44100
OUT = os.path.join(os.path.dirname(__file__), "..", "launcher", "app", "src", "main", "res", "raw")


def note(freq, dur, vol):
    n = int(RATE * dur)
    out = []
    for i in range(n):
        t = i / RATE
        env = min(1.0, t / 0.008) * math.exp(-t * 9.0)       # quick attack, soft decay
        s = math.sin(2 * math.pi * freq * t) + 0.25 * math.sin(2 * math.pi * freq * 2 * t)
        out.append(vol * env * s / 1.25)
    return out


def chime(freqs, gap, dur, vol):
    total = int(RATE * (gap * (len(freqs) - 1) + dur)) + 1
    mix = [0.0] * total
    for k, f in enumerate(freqs):
        start = int(RATE * gap * k)
        for i, v in enumerate(note(f, dur, vol)):
            mix[start + i] += v
    return mix


def save(name, samples):
    with wave.open(os.path.join(OUT, name), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, s)) * 32767)) for s in samples))


os.makedirs(OUT, exist_ok=True)
save("voice_start.wav", chime([880.0, 1318.5], 0.07, 0.22, 0.45))   # A5 -> E6, rising
save("voice_stop.wav", chime([1318.5, 880.0], 0.07, 0.20, 0.35))    # E6 -> A5, falling
print("ok")
