#!/usr/bin/env python3
"""
Turn recordings of a bike holding steady revs into a grain bank for the game's engine sound.

Each recording becomes one bank: its rpm is measured from the firing rate (single cylinder: one
firing every two revolutions), the firing cycles are found and aligned against an averaged
template, and ~32 steady cycles are cut out, each starting just before its exhaust pulse and
ending just before the next one (a grain that carried the next pulse would double it up when
grains overlap, and the misaligned copies cancel the exhaust's high-frequency crackle).

Usage:
    python3 make_grains.py OUT.grains IN1.mp3 IN2.mp3 ...   (needs ffmpeg and numpy)

Output (little-endian): "NWGR", int32 version=1, int32 sampleRate, int32 bankCount, then per
bank: float32 rpm, float32 rms, int32 grainCount, then per grain: int32 length, int16 samples.
Banks are sorted by rpm.
"""
import struct
import subprocess
import sys

import numpy as np

SR = 22050
GRAINS = 32
FIRINGS_PER_REV = 0.5  # four-stroke single


def decode(path):
    raw = subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-i", path, "-ac", "1", "-ar", str(SR),
                          "-f", "s16le", "-"], check=True, capture_output=True).stdout
    return np.frombuffer(raw, dtype=np.int16).astype(np.float64) / 32768


def envelope(x, ms=2.5):
    k = max(1, int(SR * ms / 1000))
    return np.convolve(np.abs(x), np.ones(k) / k, mode="same")


def firing_period(env):
    """Median firing period (samples) from envelope autocorrelation over 1 s windows."""
    win = SR
    lo, hi = int(SR / 90), int(SR / 8)
    lags = []
    for s in range(0, len(env) - win, win // 2):
        seg = env[s:s + win] - env[s:s + win].mean()
        ac = np.correlate(seg, seg, "full")[win - 1:]
        ac /= ac[0] + 1e-12
        lag = lo + int(np.argmax(ac[lo:hi]))
        for div in (2, 3, 4):  # prefer the shortest strong period (avoid octave errors)
            l2 = int(round(lag / div))
            if l2 >= lo and ac[l2] > 0.75 * ac[lag]:
                lag = l2
        lags.append(lag)
    return float(np.median(lags))


def cycles(env, period):
    """Cycle start positions, aligned to a template of the average cycle."""
    L = int(period)
    pos = np.arange(int(period * 0.5), len(env) - int(3 * period), period).astype(int)
    for _ in range(4):
        T = np.mean([env[p:p + L] for p in pos], axis=0)
        T = T - T.mean()
        new = [int(pos[0])]
        while True:
            exp = new[-1] + L
            a, b = exp - int(0.18 * period), exp + int(0.18 * period)
            if b + 2 * L >= len(env):
                break
            step = max(1, L // 200)
            best = max(range(a, b, step), key=lambda q: float(np.dot(env[q:q + L] - env[q:q + L].mean(), T)))
            new.append(best)
        pos = np.array(new)
    # Shift so each cycle starts just before the exhaust pulse (steepest rise of the template).
    T = np.mean([env[p:p + L] for p in pos], axis=0)
    smooth = np.convolve(T, np.ones(5) / 5, mode="same")
    onset = int(np.argmax(np.diff(smooth)))
    return pos + onset - int(0.002 * SR), L


def bank(path):
    x = decode(path)
    env = envelope(x)
    period = firing_period(env)
    starts, L = cycles(env, period)
    starts = starts[(starts > 0) & (starts + int(L * 1.25) < len(x))]
    d = np.diff(starts)
    # Steady cycles: interval close to the median; take them from the middle of the take.
    med = np.median(d)
    good = [i for i in range(len(d)) if abs(d[i] - med) < 0.03 * med]
    if len(good) < GRAINS:
        good = [i for i in range(len(d)) if abs(d[i] - med) < 0.06 * med]
    mid = len(good) // 2
    pick = good[max(0, mid - GRAINS // 2): mid + GRAINS // 2]
    grains = []
    for i in pick:
        fi = int(0.001 * SR)
        # One cycle (never longer than the typical one, or it would reach into the next
        # pulse when played at that rpm) plus a 1 ms crossfade.
        n = int(min(d[i], med)) + fi
        g = x[starts[i]:starts[i] + n].copy()
        g[:fi] *= np.linspace(0, 1, fi)
        fo = 2 * fi
        g[-fo:] *= np.linspace(1, 0, fo)
        grains.append(g)
    rpm = SR / np.median(d) / FIRINGS_PER_REV * 60
    rms = float(np.sqrt(np.mean(x ** 2)))
    sd = np.std(d) / SR * 1000
    print("%-28s %5.0f rpm  %d grains  cycle %.1f ms (sd %.1f)  rms %.3f" % (path.split("/")[-1], rpm, len(grains), np.median(d) / SR * 1000, sd, rms))
    return rpm, rms, grains


def main():
    out, ins = sys.argv[1], sys.argv[2:]
    banks = sorted((bank(p) for p in ins), key=lambda b: b[0])
    peak = max(np.max(np.abs(g)) for b in banks for g in b[2])
    scale = 0.9 / peak  # one gain for all banks keeps their relative loudness
    with open(out, "wb") as f:
        f.write(b"NWGR")
        f.write(struct.pack("<iii", 1, SR, len(banks)))
        for rpm, rms, grains in banks:
            f.write(struct.pack("<ffi", rpm, rms * scale, len(grains)))
            for g in grains:
                q = np.clip(np.round(g * scale * 32767), -32767, 32767).astype("<i2")
                f.write(struct.pack("<i", len(q)))
                f.write(q.tobytes())
    print("wrote", out)


if __name__ == "__main__":
    main()
