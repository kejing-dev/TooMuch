"""Synthesises the app's sounds (design `audio.sources[].synth`, `audio.fileSpec`) into app/src/main/assets/audio.

Pure standard library (no numpy on the build PCs), deterministic (fixed seeds): 48 kHz, 16-bit, mono WAV.
Loops are exact multiples of their periods with a 50 ms cross-fade at the seam; everything is normalised to
about -14 dBFS RMS with peaks at or below -1 dBFS (whichever is lower).

    python -I -X utf8 tools/gen_sounds.py
"""
import math
import os
import random
import struct
import wave

SR = 48000
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "audio")
TAU = 2 * math.pi


def biquad(x, kind, f0, q=0.707):
    w0 = TAU * f0 / SR
    a = math.sin(w0) / (2 * q)
    c = math.cos(w0)
    if kind == "lp":
        b0, b1, b2 = (1 - c) / 2, 1 - c, (1 - c) / 2
    elif kind == "hp":
        b0, b1, b2 = (1 + c) / 2, -(1 + c), (1 + c) / 2
    else:  # band-pass, constant peak gain
        b0, b1, b2 = a, 0.0, -a
    a0, a1, a2 = 1 + a, -2 * c, 1 - a
    b0, b1, b2, a1, a2 = b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0
    y = [0.0] * len(x)
    x1 = x2 = y1 = y2 = 0.0
    for i, v in enumerate(x):
        o = b0 * v + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2, x1, y2, y1 = x1, v, y1, o
        y[i] = o
    return y


def noise(n, rng):
    return [rng.uniform(-1, 1) for _ in range(n)]


def pinkish(n, rng):
    # Paul Kellet's economy pink filter
    b0 = b1 = b2 = 0.0
    out = []
    for _ in range(n):
        w = rng.uniform(-1, 1)
        b0 = 0.99765 * b0 + w * 0.0990460
        b1 = 0.96300 * b1 + w * 0.2965164
        b2 = 0.57000 * b2 + w * 1.0526913
        out.append((b0 + b1 + b2 + w * 0.1848) * 0.2)
    return out


def seam(x, ms=50):
    """Cross-fades the tail into the head so the loop has no click (the tail is generated beyond the loop)."""
    n = int(SR * ms / 1000)
    body = x[:-n]
    tail = x[-n:]
    for i in range(n):
        t = i / n
        body[i] = body[i] * t + tail[i] * (1 - t)
    return body


def normalise(x, rms_db=-14.0, peak_db=-1.0):
    peak = max(1e-9, max(abs(v) for v in x))
    rms = math.sqrt(sum(v * v for v in x) / len(x)) or 1e-9
    g = min(10 ** (rms_db / 20) / rms, 10 ** (peak_db / 20) / peak)
    return [v * g for v in x]


def write(name, x):
    os.makedirs(OUT, exist_ok=True)
    p = os.path.join(OUT, name + ".wav")
    with wave.open(p, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(b"".join(struct.pack("<h", max(-32767, min(32767, int(v * 32767)))) for v in x))
    print("%-12s %6.2f s %7d bytes" % (name, len(x) / SR, os.path.getsize(p)))


def env_exp(n, tau_s):
    return [math.exp(-i / (tau_s * SR)) for i in range(n)]


# ---------------------------------------------------------------------------------------------
def fridge(rng):
    secs, extra = 8.0, 0.05
    n = int(SR * (secs + extra))
    lp = biquad(pinkish(n, rng), "lp", 800)
    x = []
    for i in range(n):
        t = i / SR
        wob = 1 + 0.08 * math.sin(TAU * 0.25 * t)  # 2 cycles in 8 s
        tone = math.sin(TAU * 50 * t) + 0.6 * math.sin(TAU * 100 * t) + 0.3 * math.sin(TAU * 150 * t) + 0.15 * math.sin(TAU * 250 * t)
        x.append(wob * tone * 0.5 + lp[i] * 0.4)
    return normalise(seam(x))


def fridge_hiss(rng):
    n = int(SR * 8.05)
    x = biquad(biquad(noise(n, rng), "bp", 1200, 2.0), "bp", 1200, 2.0)
    return normalise(seam(x), rms_db=-16)


def clock(rng, hp):
    secs = 8.0
    n = int(SR * secs)
    x = [0.0] * n
    for k in range(8):
        start = int(k * SR)
        f = 2500 * (1.03 if k % 2 else 1.0)
        m = int(SR * 0.004)
        ring = int(SR * 0.03)
        for i in range(m + ring):
            if start + i >= n:
                break
            t = i / SR
            e = math.exp(-t / 0.004)
            v = math.sin(TAU * f * t) * e + (rng.uniform(-1, 1) * math.exp(-t / 0.0015) * 0.6)
            x[start + i] += v
    x = biquad(x, "hp", hp)
    return normalise(x)


def bag(rng, length):
    n = int(SR * length)
    base = biquad(biquad(noise(n, rng), "hp", 3000), "lp", 9000)
    env = [0.0] * n
    t = 0
    while t < n:
        g = int(SR * rng.uniform(0.02, 0.08))
        amp = rng.uniform(0.3, 1.0)
        for i in range(g):
            if t + i >= n:
                break
            p = i / g
            env[t + i] = max(env[t + i], amp * math.sin(math.pi * p))
        t += int(g * rng.uniform(0.5, 1.1))
    fade = int(SR * 0.02)
    for i in range(fade):
        env[i] *= i / fade
        env[n - 1 - i] *= i / fade
    return normalise([b * e for b, e in zip(base, env)])


def drips(rng, bass):
    secs, extra = 8.0, 0.05
    n = int(SR * (secs + extra))
    x = [0.0] * n
    t = 0.3
    times = []
    while t < secs - 0.2:
        times.append(t)
        t += rng.uniform(1.5, 3.0)
    for t0 in times:
        s = int(t0 * SR)
        m = int(SR * 0.06)
        ph = 0.0
        for i in range(int(SR * 0.4)):
            if s + i >= n:
                break
            p = min(1.0, i / m)
            f = 1500 - 600 * p
            ph += TAU * f / SR
            e = math.exp(-i / (SR * 0.025)) + 0.15 * math.exp(-i / (SR * 0.12))
            v = math.sin(ph) * e
            if bass:
                v += 0.7 * math.sin(TAU * 80 * i / SR) * math.exp(-i / (SR * 0.15))
            x[s + i] += v
    return normalise(seam(x))


def lamp(rng):
    secs, extra = 6.0, 0.05
    n = int(SR * (secs + extra))
    sq = []
    for i in range(n):
        t = i / SR
        s = 0.0
        for k in range(1, 30, 2):
            s += math.sin(TAU * 100 * k * t) / k
        sq.append(s)
    sq = biquad(sq, "lp", 3000)
    hiss = biquad(biquad(noise(n, rng), "bp", 8000, 6.0), "bp", 8000, 6.0)
    x = [a * 0.5 + b * 0.6 for a, b in zip(sq, hiss)]
    return normalise(seam(x))


def phone(rng, variant):
    f1, f2 = (1318.5, 987.8) if variant == 0 else (1174.7, 1568.0)
    n = int(SR * (0.09 * 2 + 0.25))
    x = []
    for i in range(n):
        t = i / SR
        if t < 0.09:
            f, tt = f1, t
        elif t < 0.18:
            f, tt = f2, t - 0.09
        else:
            f, tt = f2, t - 0.09
        fm = 0.3 * math.sin(TAU * f * 2 * t)
        a = min(1.0, tt / 0.004) * (math.exp(-(t - 0.18) / 0.08) if t > 0.18 else 1.0)
        x.append(math.sin(TAU * f * t + fm) * a)
    return normalise(x)


def ui_snap(rng):
    n = int(SR * 0.03)
    x = [math.sin(TAU * 1800 * i / SR) * math.exp(-i / (SR * 0.004)) for i in range(n)]
    return normalise(x)


def ui_ding(rng):
    n = int(SR * 0.7)
    x = [(math.sin(TAU * 880 * i / SR) + 0.3 * math.sin(TAU * 1760 * i / SR)) * math.exp(-i / (SR * 0.18)) * min(1.0, i / (SR * 0.003)) for i in range(n)]
    return normalise(x)


def main():
    write("fridge", fridge(random.Random(1)))
    write("fridge_hiss", fridge_hiss(random.Random(2)))
    write("clock", clock(random.Random(3), 1000))
    write("clock_hard", clock(random.Random(3), 2000))
    rng = random.Random(4)
    for v in range(6):
        write("bag_%d" % v, bag(rng, rng.uniform(0.3, 1.2)))
    write("tap", drips(random.Random(5), bass=False))
    write("tap_bass", drips(random.Random(5), bass=True))
    write("lamp", lamp(random.Random(6)))
    for v in range(2):
        write("phone_%d" % v, phone(random.Random(7), v))
    write("ui_snap", ui_snap(None))
    write("ui_ding", ui_ding(None))
    total = sum(os.path.getsize(os.path.join(OUT, f)) for f in os.listdir(OUT))
    print("total %.2f MB" % (total / 1e6))


if __name__ == "__main__":
    main()
