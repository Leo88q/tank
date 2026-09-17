#!/usr/bin/env python3
"""ORBITFALL original soundtrack generator (Phase 2 audio replacement).

Produces 8 procedural chiptune/ambient tracks as OGG Vorbis, overwriting the
former CC BY / CC BY-SA placeholders in assets/audio/soundtrack/. The output is
original work of this project (deterministic, seeded) - no third-party license
obligations remain.

Usage: python3 tools/gen_soundtrack.py   (needs: numpy, soundfile)
"""
import os
import numpy as np
import soundfile as sf

SR = 22050
OUT = os.path.join(os.path.dirname(__file__), "..", "assets", "audio", "soundtrack")


def hz(midi):
    return 440.0 * (2.0 ** ((midi - 69) / 12.0))


def env(n, a=0.008, r=0.08, peak=1.0, sus=0.7):
    t = np.arange(n) / SR
    e = np.ones(n)
    ai = max(1, int(a * SR))
    e[:ai] = np.linspace(0, 1, ai)
    ri = max(1, int(r * SR))
    e[-ri:] *= np.linspace(1, 0, ri)
    di = min(n, ai * 4)
    e[ai:di] = np.linspace(1, sus, max(1, di - ai))
    return e * peak


def tone(midi, dur, wave="square", vol=0.2):
    n = int(dur * SR)
    t = np.arange(n) / SR
    f = hz(midi)
    ph = 2 * np.pi * f * t
    if wave == "sine":
        s = np.sin(ph)
    elif wave == "square":
        s = 0.55 * np.sign(np.sin(ph)) + 0.45 * np.sin(ph)
    elif wave == "saw":
        s = 2 * (t * f - np.floor(0.5 + t * f))
    elif wave == "tri":
        s = 2 * np.abs(2 * (t * f - np.floor(t * f + 0.5))) - 1
    elif wave == "pad":
        s = (np.sin(ph) + np.sin(ph * 1.005) + 0.5 * np.sin(2 * ph)) / 2.5
    else:
        raise ValueError(wave)
    return s * env(n) * vol


def noise(dur, vol=0.2, decay=0.05):
    n = int(dur * SR)
    s = np.random.randn(n)
    e = np.exp(-np.arange(n) / (decay * SR))
    return s * e * vol


def kick(vol=0.5):
    n = int(0.14 * SR)
    t = np.arange(n) / SR
    f = 130 * np.exp(-t * 28) + 42
    return np.sin(2 * np.pi * f * t) * np.exp(-t * 18) * vol


def snare(vol=0.3):
    return noise(0.14, vol, 0.05) + tone(45, 0.14, "sine", vol * 0.6)


def hat(vol=0.12):
    return noise(0.04, vol, 0.015)


class Mix:
    def __init__(self, dur):
        self.n = int(dur * SR)
        self.buf = np.zeros(self.n)

    def add(self, sig, at):
        i = int(at * SR)
        j = min(self.n, i + len(sig))
        if i < self.n:
            self.buf[i:j] += sig[: j - i]

    def render(self, fade=1.5):
        b = self.buf
        # simple feedback delay for space
        d = int(0.28 * SR)
        echo = np.zeros_like(b)
        echo[d:] += b[:-d] * 0.18
        echo[2 * d:] += b[:-2 * d] * 0.07
        b = b + echo
        b = np.tanh(b * 1.1)
        b = 0.9 * b / max(1e-6, np.max(np.abs(b)))
        fi = int(fade * SR)
        b[-fi:] *= np.linspace(1, 0, fi)
        b[: int(0.3 * SR)] *= np.linspace(0, 1, int(0.3 * SR))
        return b.astype("float32")


MINOR = [0, 2, 3, 5, 7, 8, 10]
MAJOR = [0, 2, 4, 5, 7, 9, 11]


def make_track(seed, bpm, bars, root, scale, style):
    np.random.seed(seed)
    spb = 60.0 / bpm
    dur = bars * 4 * spb + 2
    m = Mix(dur)
    prog = [0, 0, -4, -2, 0, 0, 3, -2] if scale is MINOR else [0, 5, 3, 4, 0, 5, 3, -5]
    for bar in range(bars):
        t0 = bar * 4 * spb
        deg = prog[bar % len(prog)]
        chord_root = root + deg
        # drums
        if style != "ambient":
            for beat in range(4):
                bt = t0 + beat * spb
                m.add(kick(0.5 if style == "metal" else 0.4), bt)
                if style == "metal":
                    m.add(hat(0.14), bt + spb / 2)
                    m.add(hat(0.1), bt)
                else:
                    m.add(hat(0.1), bt + spb / 2)
                if beat in (1, 3):
                    m.add(snare(0.26 if style == "metal" else 0.2), bt)
        # bass: 8ths
        for e in range(8):
            bt = t0 + e * spb / 2
            note_m = chord_root - 12 + (0 if e % 2 == 0 else (7 if style == "metal" else 0))
            w = "saw" if style == "metal" else "square"
            m.add(tone(note_m, spb / 2 * 0.9, w, 0.16), bt)
        # pad: whole-note triad
        for iv in (0, 4 if scale is MAJOR else 3, 7):
            m.add(tone(chord_root + iv, 4 * spb, "pad", 0.05), t0)
        # lead: seeded walk on scale
        if style != "ambient" or bar % 2 == 0:
            cur = root + 12 + scale[np.random.randint(0, len(scale))]
            for e in range(8 if style != "ambient" else 4):
                bt = t0 + e * (spb / 2 if style != "ambient" else spb)
                if np.random.rand() < (0.85 if style != "ambient" else 0.5):
                    cur += np.random.choice([-2, -1, 0, 1, 2, 3])
                    cur = max(root + 7, min(root + 24, cur))
                    dl = spb / 2 * (0.9 if np.random.rand() < 0.7 else 1.8)
                    w = "saw" if style == "metal" else "square"
                    m.add(tone(cur, dl, w, 0.11), bt)
    return m.render()


TRACKS = [
    # (file, seed, bpm, bars, root, scale, style)
    ("grand_beats_intro.ogg", 11, 100, 10, 57, MINOR, "ambient"),
    ("grand_beats_menu_soundtrack.ogg", 21, 92, 28, 57, MINOR, "ambient"),
    ("grand_beats_soundtrack_1_metal.ogg", 31, 142, 40, 52, MINOR, "metal"),
    ("grand_beats_110.ogg", 41, 110, 34, 55, MINOR, "chip"),
    ("reduz_all_star_champion_sheep.ogg", 51, 122, 32, 60, MAJOR, "chip"),
    ("reduz_like_a_whale.ogg", 61, 100, 30, 53, MINOR, "chip"),
    ("reduz_the_sorrows_of_a_crab.ogg", 71, 82, 26, 50, MINOR, "ambient"),
    ("reduz_capybara_love.ogg", 81, 126, 32, 58, MAJOR, "chip"),
]


def main():
    os.makedirs(OUT, exist_ok=True)
    for fname, seed, bpm, bars, root, scale, style in TRACKS:
        sig = make_track(seed, bpm, bars, root, scale, style)
        path = os.path.join(OUT, fname)
        sf.write(path, sig, SR, format="OGG", subtype="VORBIS")
        print("wrote", fname, os.path.getsize(path), "bytes", round(len(sig) / SR, 1), "s")


if __name__ == "__main__":
    main()
