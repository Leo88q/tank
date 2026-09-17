#!/usr/bin/env python3
"""ORBITFALL original SFX generator: replaces ALL upstream .wav samples with
procedurally synthesized equivalents (original work, no third-party license).

Usage: python3 tools/gen_sfx.py   (needs: numpy, soundfile)
"""
import os
import numpy as np
import soundfile as sf

SR = 22050
OUT = os.path.join(os.path.dirname(__file__), "..", "assets", "audio")


def hz(m):
    return 440.0 * 2 ** ((m - 69) / 12)


def sine(m, dur, vol=0.5, glide=0.0):
    n = int(dur * SR)
    t = np.arange(n) / SR
    f = hz(m) * (1 + glide * t / max(dur, 1e-6))
    s = np.sin(2 * np.pi * f * t)
    e = np.exp(-t * 6 / dur)
    return (s * e * vol).astype("float32")


def square(m, dur, vol=0.35, glide=0.0):
    n = int(dur * SR)
    t = np.arange(n) / SR
    f = hz(m) * (1 + glide * t / max(dur, 1e-6))
    s = np.sign(np.sin(2 * np.pi * f * t)) * 0.6 + np.sin(2 * np.pi * f * t) * 0.4
    e = np.exp(-t * 8 / dur)
    return (s * e * vol).astype("float32")


def burst(dur, vol=0.5, decay=6, lp=0.2):
    n = int(dur * SR)
    s = np.random.randn(n)
    # crude low-pass
    o = np.zeros(n)
    for i in range(1, n):
        o[i] = o[i - 1] + lp * (s[i] - o[i - 1])
    e = np.exp(-np.arange(n) / (SR * dur / decay))
    return (o * e * vol).astype("float32")


def padadd(a, b):
    n = max(len(a), len(b))
    r = np.zeros(n, dtype="float32")
    r[:len(a)] += a
    r[:len(b)] += b
    return r


def seq(parts, gap=0.09):
    out = []
    t = 0
    for sig, dur in parts:
        i = int(t * SR)
        if len(out) < i + len(sig):
            out = np.concatenate([out, np.zeros(i + len(sig) - len(out))])
        out[i:i + len(sig)] += sig
        t += dur + gap
    b = np.clip(np.asarray(out, dtype="float32"), -1, 1)
    return 0.9 * b / max(1e-6, np.max(np.abs(b)))


def write(name, sig):
    sf.write(os.path.join(OUT, name + ".wav"), sig, SR, subtype="PCM_16")
    print("wrote", name + ".wav", round(len(sig) / SR, 2), "s")


np.random.seed(7)

write("menu", square(81, 0.07, 0.3))
write("menu_click", square(81, 0.07, 0.3))
write("click", square(84, 0.06, 0.3))
write("button", square(79, 0.08, 0.32))
write("menu_back", seq([(square(76, 0.07, 0.3), 0.07), (square(69, 0.1, 0.3), 0.1)]))
write("map_click", sine(88, 0.08, 0.35, glide=0.3))
write("select", seq([(square(72, 0.05, 0.28), 0.05), (square(76, 0.08, 0.28), 0.08)]))
write("move", burst(0.16, 0.35, decay=5, lp=0.35))
write("explosion", padadd(burst(0.6, 0.7, decay=5, lp=0.12), sine(33, 0.6, 0.5, glide=-0.4)))
write("hurt", square(57, 0.14, 0.4, glide=-0.35))
write("no_attack", seq([(square(50, 0.09, 0.35), 0.09), (square(48, 0.12, 0.35), 0.12)], gap=0.02))
write("no_moves", seq([(square(52, 0.09, 0.32), 0.09), (square(46, 0.12, 0.32), 0.12)], gap=0.02))
write("not_dead", seq([(square(55, 0.08, 0.3), 0.08), (square(55, 0.08, 0.3), 0.08)], gap=0.03))
write("end_turn", seq([(square(67, 0.08, 0.3), 0.08), (square(62, 0.12, 0.3), 0.12)], gap=0.02))
write("spawn", sine(60, 0.25, 0.4, glide=0.8))
write("powerup", seq([(square(72, 0.06, 0.3), 0.06), (square(76, 0.06, 0.3), 0.06), (square(81, 0.12, 0.3), 0.12)]))
write("pickup_box", seq([(square(74, 0.05, 0.3), 0.05), (square(78, 0.09, 0.3), 0.09)]))
write("occupy_building", padadd(sine(41, 0.3, 0.5, glide=-0.2), burst(0.3, 0.2, lp=0.15)))
write("building_capture_drum", padadd(sine(38, 0.35, 0.6, glide=-0.5), burst(0.35, 0.3, lp=0.2)))
write("building_capture_drum_2", padadd(sine(36, 0.4, 0.6, glide=-0.5), burst(0.4, 0.3, lp=0.2)))
write("fanfare", seq([(square(72, 0.1, 0.3), 0.1), (square(76, 0.1, 0.3), 0.1), (square(79, 0.1, 0.3), 0.1), (square(84, 0.3, 0.32), 0.3)]))
write("failfare", seq([(square(67, 0.12, 0.3), 0.12), (square(65, 0.12, 0.3), 0.12), (square(63, 0.35, 0.32), 0.35)]))
print("done")
