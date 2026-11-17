"""Offline render of what the app plays during the task, for the factory's loudness evidence (gate `loudness`).

    python -X utf8 tools/loudness.py <out.wav> [--intensity gentle|standard] [--audio <dir of WAVs>] [--seed n] [--standard-db x]

This PC has no audio loopback from the emulator, so the file is rendered, not recorded ("渲染，非录音"): the asset WAVs
mixed with the engine's tables (Sources.kt gains per level, cue layers, pulse intervals, the intensity group gain),
30 s of the task's climb (10 s each at L2, L3, L4), mono, no distance attenuation / HRTF (ObjectAudioComponent would
make the pinned sources 1 - 3 m away somewhat quieter). Output 44.1 kHz 16-bit mono, the rate the gate's 20 ms window
assumes. Prints RMS of the voiced windows (the gate's measure) and the peak.
"""
import os
import random
import sys
import wave

import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
AUDIO = os.path.join(ROOT, "app", "src", "main", "assets", "audio")
SR = 44100

# Sources.kt (dB per level L0..L4) and the cue layers
GAIN = {
    "fridge": [-27, -21, -15, -9, -4],
    "clock": [-26, -20, -14, -9, -5],
    "bag": [-24, -18, -12, -7, -3],
    "tap": [-27, -21, -15, -10, -6],
    "lamp": [-31, -25, -19, -13, -8],
    "phone": [-23, -17, -12, -8, -4],
}
INTERVAL = {"bag": ((4, 6), (1.5, 3)), "phone": ((10, 15), (5, 8))}
GROUP = {"gentle": -8.0, "standard": float(sys.argv[sys.argv.index("--standard-db") + 1]) if "--standard-db" in sys.argv else -3.5}
SEGMENTS = [2, 3, 4]  # 10 s each
SEG_S = 10


def load(name):
    with wave.open(os.path.join(sys.argv[sys.argv.index("--audio") + 1] if "--audio" in sys.argv else AUDIO, name + ".wav")) as w:
        sr = w.getframerate()
        x = np.frombuffer(w.readframes(w.getnframes()), "<i2").astype(np.float64) / 32768.0
    if sr != SR:
        t = np.arange(int(len(x) * SR / sr)) * sr / SR
        x = np.interp(t, np.arange(len(x)), x)
    return x


def db(v):
    return 10 ** (v / 20)


def loop(x, n):
    return np.tile(x, n // len(x) + 1)[:n]


def interval(src, level):
    (a0, b0), (a4, b4) = INTERVAL[src]
    f = level / 4
    return a0 + (a4 - a0) * f, b0 + (b4 - b0) * f


def main():
    out = sys.argv[1]
    intensity = sys.argv[sys.argv.index("--intensity") + 1] if "--intensity" in sys.argv else "gentle"
    n_seg = SR * SEG_S
    total = np.zeros(n_seg * len(SEGMENTS))
    loops = {k: load(k) for k in ["fridge", "fridge_hiss", "clock", "clock_hard", "tap", "tap_bass", "lamp"]}
    shots = {"bag": [load("bag_%d" % i) for i in range(6)], "phone": [load("phone_%d" % i) for i in range(2)]}
    rng = random.Random(int(sys.argv[sys.argv.index("--seed") + 1]) if "--seed" in sys.argv else 1)
    for s, level in enumerate(SEGMENTS):
        a, b = s * n_seg, (s + 1) * n_seg
        seg = np.zeros(n_seg)
        seg += loop(loops["fridge"], n_seg) * db(GAIN["fridge"][level])
        hiss = -12 if level >= 4 else (-18 if level == 3 else None)
        if hiss is not None:
            seg += loop(loops["fridge_hiss"], n_seg) * db(hiss)
        seg += loop(loops["clock_hard" if level >= 2 else "clock"], n_seg) * db(GAIN["clock"][level])
        seg += loop(loops["tap"], n_seg) * db(GAIN["tap"][level])
        if level >= 3:
            seg += loop(loops["tap_bass"], n_seg) * db(GAIN["tap"][level])
        seg += loop(loops["lamp"], n_seg) * db(GAIN["lamp"][level])
        for src, pool in shots.items():
            lo, hi = interval(src, level)
            t = rng.uniform(0.3, lo)
            while t < SEG_S:
                x = pool[rng.randrange(len(pool))] * db(GAIN[src][level])
                i = int(t * SR)
                seg[i:i + len(x)] += x[: n_seg - i]
                t += rng.uniform(lo, hi)
        total[a:b] = seg
    total *= db(GROUP[intensity])
    peak = float(np.max(np.abs(total)))
    pcm = np.clip(np.round(total * 32767), -32768, 32767).astype("<i2")
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    with wave.open(out, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())
    win = 882
    e = np.array([np.mean(total[k:k + win] ** 2) for k in range(0, len(total) - win + 1, win)])
    voiced = e[e >= (peak * 0.0316) ** 2]
    rms = 10 * np.log10(np.mean(voiced) + 1e-20)
    print("%s  %s  voiced RMS %.1f dBFS  peak %.1f dBFS  (L2 / L3 / L4 x 10 s, rendered, not recorded)" % (out, intensity, rms, 20 * np.log10(peak + 1e-12)))


if __name__ == "__main__":
    main()
