"""Turns CC0 recordings into the app's sound assets (replaces the synthesised real-object sounds of tools/gen_sounds.py).

    python -X utf8 tools/import_cc0.py <dir with the downloaded previews>

Expected inputs (any format ffmpeg reads): fridge.*, clock.*, tap.*, lamp.*, bag.*, phone_a.*, phone_b.*
Outputs (48 kHz mono 16-bit, app/src/main/assets/audio/): the same names and lengths the engine already uses, each
at the RMS its synthesised file had (SYNTH_RMS) with the peak <= -1 dBFS, so the level tables (and the gentle -8 dB
group) keep their meaning. The cue layers are filtered copies of the same recording (never synthesis):
fridge_hiss = high-passed fridge, clock_hard = high-passed clock with lifted transients, tap_bass = tap plus its own
low-passed body. Loops get a 50 ms seam cross-fade. Untrusted input: decoded by ffmpeg into raw floats only.
"""
import os
import subprocess
import sys
import wave

import imageio_ffmpeg
import numpy as np

SR = 48000
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "audio")


def find(src, stem):
    for f in sorted(os.listdir(src)):
        if os.path.splitext(f)[0] == stem:
            return os.path.join(src, f)
    raise SystemExit("missing input: %s.*" % stem)


def load(path):
    ff = imageio_ffmpeg.get_ffmpeg_exe()
    raw = subprocess.run([ff, "-v", "error", "-i", path, "-ac", "1", "-ar", str(SR), "-f", "f32le", "-"], capture_output=True, check=True).stdout
    x = np.frombuffer(raw, dtype=np.float32).astype(np.float64)
    return x - x.mean()


def biquad(x, kind, f0, q=0.707):
    w0 = 2 * np.pi * f0 / SR
    a = np.sin(w0) / (2 * q)
    c = np.cos(w0)
    if kind == "lp":
        b = [(1 - c) / 2, 1 - c, (1 - c) / 2]
    else:
        b = [(1 + c) / 2, -(1 + c), (1 + c) / 2]
    aa = [1 + a, -2 * c, 1 - a]
    b = [v / aa[0] for v in b]
    a1, a2 = aa[1] / aa[0], aa[2] / aa[0]
    y = np.zeros_like(x)
    x1 = x2 = y1 = y2 = 0.0
    for i, v in enumerate(x):
        o = b[0] * v + b[1] * x1 + b[2] * x2 - a1 * y1 - a2 * y2
        x2, x1, y2, y1 = x1, v, y1, o
        y[i] = o
    return y


def rms(x):
    return float(np.sqrt(np.mean(x * x)) + 1e-12)


# RMS (dBFS) each file had when gen_sounds.py synthesised it; the engine's level tables and the gentle -8 dB group
# were tuned on these, so a recording takes over its file's level rather than a common target.
SYNTH_RMS = {
    "fridge": -14.0, "fridge_hiss": -16.0, "clock": -33.8, "clock_hard": -34.4, "tap": -26.4, "tap_bass": -25.4,
    "lamp": -14.0, "bag_0": -15.8, "bag_1": -16.6, "bag_2": -15.8, "bag_3": -14.1, "bag_4": -16.3, "bag_5": -15.1,
    "phone_0": -14.0, "phone_1": -14.0,
}
MAX_LIMIT_DB = 8.0


def normalise(x, name, peak_db=-1.0):
    """Gain the recording to its synthesised file's RMS ([SYNTH_RMS]) with the peak at most -1 dBFS. Recorded crinkles
    and drips have sharper peaks than the synthesis, so overs are soft-limited (tanh knee), at most [MAX_LIMIT_DB] of
    peak reduction; beyond that the file stays quieter rather than squashed (write() prints the result)."""
    peak = 10 ** (peak_db / 20)
    target = 10 ** (SYNTH_RMS[name] / 20)
    top = float(np.max(np.abs(x)))

    def limited(g):
        y = x * g
        m = float(np.max(np.abs(y)))
        if m > peak:
            k = 0.5 * peak
            over = np.abs(y) > k
            s = 1.5  # tanh knee from k up to the limit; the loudest sample lands exactly on the limit
            y[over] = np.sign(y[over]) * (k + (peak - k) * np.tanh((np.abs(y[over]) - k) / (m - k) * s) / np.tanh(s))
        return y

    # RMS after limiting grows with the gain: bisect for the target, capped at MAX_LIMIT_DB over the peak limit
    lo, hi = min(target / rms(x), peak / top), peak * 10 ** (MAX_LIMIT_DB / 20) / top
    if rms(limited(lo)) >= target or rms(limited(hi)) <= target:
        return limited(lo if rms(limited(lo)) >= target else hi)
    for _ in range(30):
        mid = (lo * hi) ** 0.5
        if rms(limited(mid)) < target:
            lo = mid
        else:
            hi = mid
    return limited(hi)


def seam(x, ms=50):
    n = int(SR * ms / 1000)
    head, body = x[:n], x[n:].copy()
    fade = np.linspace(0, 1, n)
    body[-n:] = body[-n:] * (1 - fade) + head * fade
    return body


def steadiest(x, secs):
    """The window of [secs] whose 100 ms RMS varies least (hum / buzz beds)."""
    n = int(SR * secs)
    hop = SR // 10
    e = np.array([rms(x[i:i + hop]) for i in range(0, len(x) - hop, hop)])
    w = n // hop
    best, at = None, 0
    for s in range(0, max(1, len(e) - w)):
        seg = e[s:s + w]
        v = float(np.std(seg) / (np.mean(seg) + 1e-9)) - 0.2 * float(np.log10(np.mean(seg) + 1e-9))
        if best is None or v < best:
            best, at = v, s
    return x[at * hop: at * hop + n + int(SR * 0.05)]


def loudest(x, secs):
    n = int(SR * secs)
    hop = SR // 10
    best, at = -1.0, 0
    for s in range(0, max(1, len(x) - n), hop):
        r = rms(x[s:s + n])
        if r > best:
            best, at = r, s
    return x[at: at + n + int(SR * 0.05)]


def onsets(x, min_gap_s=0.4):
    hop = SR // 200
    e = np.array([rms(x[i:i + hop]) for i in range(0, len(x) - hop, hop)])
    thr = 0.35 * float(np.max(e))
    out, last = [], -1e9
    for i in range(1, len(e)):
        if e[i] >= thr and e[i - 1] < thr and (i - last) * hop > min_gap_s * SR:
            out.append(i * hop)
            last = i
    return out


def clock_loop(x, secs=8):
    """Eight ticks, one per second, cut from the recording (each tick 0.25 s from its own onset)."""
    ticks = [x[max(0, o - SR // 200): max(0, o - SR // 200) + SR // 4] for o in onsets(x)]
    if not ticks:
        raise SystemExit("clock: no ticks found")
    y = np.zeros(int(SR * (secs + 0.05)))
    for k in range(secs):
        t = ticks[k % len(ticks)]
        s = int(k * SR + 0.05 * SR)
        y[s:s + len(t)] += t[: len(y) - s]
    return y


def pieces(x, count, lo=0.3, hi=1.2):
    """[count] non-overlapping energetic bits of lo..hi s (bag rustles), each with 10 / 30 ms fades."""
    hop = SR // 20
    e = np.array([rms(x[i:i + hop]) for i in range(0, len(x) - hop, hop)])
    order = list(np.argsort(-e))
    used = np.zeros(len(e), bool)
    rng = np.random.default_rng(4)
    out = []
    for i in order:
        if len(out) == count:
            break
        length = rng.uniform(lo, hi)
        w = int(length * SR / hop)
        s = max(0, i - w // 3)
        if used[s:s + w].any() or s + w >= len(e):
            continue
        used[s:s + w] = True
        seg = x[s * hop:(s + w) * hop].copy()
        fi, fo = int(SR * 0.01), int(SR * 0.03)
        seg[:fi] *= np.linspace(0, 1, fi)
        seg[-fo:] *= np.linspace(1, 0, fo)
        out.append(seg)
    if len(out) < count:
        raise SystemExit("bag: found only %d pieces" % len(out))
    return out


def trimmed(x, max_s=1.2):
    thr = 0.03 * float(np.max(np.abs(x)))
    idx = np.nonzero(np.abs(x) > thr)[0]
    s, e = int(idx[0]), int(idx[-1])
    y = x[max(0, s - SR // 200): min(len(x), e + SR // 20)][: int(SR * max_s)].copy()
    fo = int(SR * 0.04)
    y[-fo:] *= np.linspace(1, 0, fo)
    return y


def normalise_as(name, x):
    return normalise(x, name)


def write(name, x):
    p = os.path.join(OUT, name + ".wav")
    pcm = np.clip(np.round(x * 32767), -32768, 32767).astype("<i2")
    with wave.open(p, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())
    print("%-12s %6.2f s  rms %6.1f dBFS  peak %5.1f dBFS" % (name, len(x) / SR, 20 * np.log10(rms(x)), 20 * np.log10(float(np.max(np.abs(x))) + 1e-12)))


def main():
    src = sys.argv[1]
    fridge = steadiest(load(find(src, "fridge")), 8.0)
    write("fridge", normalise_as("fridge", seam(fridge)))
    write("fridge_hiss", normalise_as("fridge_hiss", seam(biquad(biquad(fridge, "hp", 3000), "hp", 3000))))
    clock = clock_loop(load(find(src, "clock")))
    write("clock", normalise_as("clock", seam(clock)))
    hard = biquad(clock, "hp", 2000)
    write("clock_hard", normalise_as("clock_hard", seam(hard + 0.5 * clock)))
    tap = loudest(load(find(src, "tap")), 8.0)
    write("tap", normalise_as("tap", seam(tap)))
    body = biquad(biquad(tap, "lp", 180), "lp", 180)
    write("tap_bass", normalise_as("tap_bass", seam(tap + 4.0 * body)))
    lamp = steadiest(load(find(src, "lamp")), 6.0)
    write("lamp", normalise_as("lamp", seam(lamp)))
    for v, seg in enumerate(pieces(load(find(src, "bag")), 6)):
        write("bag_%d" % v, normalise_as("bag_%d" % v, seg))
    write("phone_0", normalise_as("phone_0", trimmed(load(find(src, "phone_a")))))
    write("phone_1", normalise_as("phone_1", trimmed(load(find(src, "phone_b")))))


if __name__ == "__main__":
    main()
