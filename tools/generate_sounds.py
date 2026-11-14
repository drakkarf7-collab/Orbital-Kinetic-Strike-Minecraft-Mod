"""
Synthesizes all mod sounds procedurally (no external samples: sine waves, filtered noise, envelopes).
Usage: python tools/generate_sounds.py   (from the project root)
Dependencies: numpy, soundfile (pip install soundfile) for OGG Vorbis encoding.

Every sound is mono, 44.1 kHz, normalized to the same level. Durations match the 1x speed
phases (see StrikePhase); the mod plays cinematic sounds at a pitch equal to the chosen
speed multiplier, keeping them synchronized.
"""
import math
import os

import numpy as np
import soundfile as sf

SR = 44100
PEAK = 0.89  # approx -1 dB
ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources",
                                     "assets", "orbital_strike", "sounds"))
if os.name == "nt" and not ROOT.startswith("\\\\?\\"):
    ROOT = "\\\\?\\" + ROOT  # allows paths longer than 260 characters on Windows


# ---------------------------------------------------------------- building blocks

def t_axis(seconds):
    return np.arange(int(seconds * SR)) / SR


def noise(seconds, seed):
    return np.random.default_rng(seed).uniform(-1, 1, int(seconds * SR))


def band(x, lo, hi):
    """FFT bandpass filter with soft roll-off edges."""
    spectrum = np.fft.rfft(x)
    freqs = np.fft.rfftfreq(len(x), 1 / SR)
    rise = np.clip((freqs - lo * 0.8) / max(lo * 0.2, 1e-6), 0, 1) if lo > 0 else 1.0
    fall = np.clip((hi * 1.25 - freqs) / (hi * 0.25), 0, 1)
    return np.fft.irfft(spectrum * rise * fall, len(x))


def sweep_filter(x, cutoff):
    """One-pole low-pass filter with time-varying cutoff frequency (array in Hz)."""
    out = np.empty(len(x))
    alpha = 1 - np.exp(-2 * np.pi * np.asarray(cutoff) / SR)
    y = 0.0
    for i in range(len(x)):
        y += alpha[i] * (x[i] - y)
        out[i] = y
    return out


def chirp(freq):
    """Sine wave following the given instantaneous frequency array (in Hz)."""
    return np.sin(2 * np.pi * np.cumsum(freq) / SR)


def env(t, attack, decay):
    """Linear attack followed by exponential decay."""
    return np.clip(t / max(attack, 1e-5), 0, 1) * np.exp(-np.clip(t - attack, 0, None) / decay)


def place(target, sound, at):
    """Adds a sound into target at the given timestamp (in seconds)."""
    start = int(at * SR)
    end = min(len(target), start + len(sound))
    if end > start:
        target[start:end] += sound[:end - start]


def fade(x, fade_in=0.004, fade_out=0.02):
    n_in, n_out = int(fade_in * SR), int(fade_out * SR)
    y = x.copy()
    if n_in:
        y[:n_in] *= np.linspace(0, 1, n_in)
    if n_out:
        y[-n_out:] *= np.linspace(1, 0, n_out)
    return y


def drive(x, amount):
    """Soft saturation: thickens bass like a driven loudspeaker."""
    return np.tanh(x * amount) / math.tanh(amount)


def metal(seconds, partials, decay, seed):
    """Metallic impact: decaying inharmonic partials plus a noise transient."""
    t = t_axis(seconds)
    rng = np.random.default_rng(seed)
    out = np.zeros(len(t))
    for i, f in enumerate(partials):
        out += np.sin(2 * np.pi * f * t + rng.uniform(0, 6.28)) * np.exp(-t / (decay / (1 + 0.6 * i))) / (1 + 0.5 * i)
    out += band(noise(seconds, seed + 1), 800, 7000) * np.exp(-t / 0.012) * 1.5
    return out


def thump(seconds, f_from, f_to, decay):
    """Dull thud: downward frequency-sweeping sine wave."""
    t = t_axis(seconds)
    freq = f_to + (f_from - f_to) * np.exp(-t / (decay * 0.35))
    return chirp(freq) * env(t, 0.003, decay)


def reverb(x, wet=0.28, decay=0.75, seed=999):
    """Symphonic FFT convolution reverb (early reflections + damped tail)."""
    n = len(x)
    ir_len = min(int(decay * 2.4 * SR), max(n, int(0.4 * SR)))
    ti = np.arange(ir_len) / SR
    ir = np.random.default_rng(seed).uniform(-1, 1, ir_len) * np.exp(-ti / max(decay * 0.45, 0.05))
    for delay_s, amp in ((0.023, 0.7), (0.037, 0.55), (0.053, 0.42), (0.079, 0.30)):
        idx = int(delay_s * SR)
        if idx < ir_len:
            ir[idx] += amp
    ir = band(ir, 60, 4800)
    ir /= max(np.sqrt(np.sum(ir ** 2)), 1e-9)
    n_fft = 1 << int(math.ceil(math.log2(n + ir_len)))
    wet_sig = np.fft.irfft(np.fft.rfft(x, n_fft) * np.fft.rfft(ir, n_fft), n_fft)[:n]
    return x + wet * wet_sig


def brass_chord(seconds, freqs, attack=0.18, sustain_end=None, brightness=1100.0):
    """Detuned synthetic brass and string pad (symphonic choir in D minor)."""
    t = t_axis(seconds)
    if sustain_end is None:
        sustain_end = seconds * 0.72
    sig = np.zeros(len(t))
    for f in freqs:
        for detune in (0.9965, 1.0, 1.0035):
            fd = f * detune
            for h in range(1, 7):
                if fd * h > 6500:
                    break
                sig += np.sin(2 * np.pi * fd * h * t + h * 0.7) / (h ** 1.15)
        sig += np.sin(2 * np.pi * f * 0.5 * t) * 0.35
    sig /= max(len(freqs) * 2.8, 1.0)
    att = np.clip(t / max(attack, 1e-4), 0, 1) ** 0.7
    rel = np.clip((seconds - t) / max(seconds - sustain_end, 1e-4), 0, 1) ** 1.4
    env_shape = att * rel
    cutoff = 180.0 + brightness * (0.25 + 0.75 * env_shape)
    return sweep_filter(drive(sig * 1.15, 1.3), cutoff) * env_shape


def add_note(buf, at, dur, freq, wave="saw", brightness=1800.0, amp=0.5, attack=0.02, vibrato=0.0):
    """Synthesizes an expressive musical note (spiccato strings, heroic brass, or bass) into a buffer."""
    start = int(at * SR)
    n = min(int(dur * SR), len(buf) - start)
    if n <= 16 or start >= len(buf):
        return
    t = np.arange(n) / SR
    vib = 1.0 + vibrato * 0.0055 * np.sin(2 * np.pi * 5.4 * t) * np.clip(t / 0.25, 0, 1)
    max_h = 10 if wave == "brass" else 8
    tone = np.zeros(n)
    for detune in (0.997, 1.003) if wave in ("brass", "pad") else (1.0,):
        for h in range(1, max_h + 1):
            fh = freq * detune * h
            if fh > 7500:
                break
            w = (1.0 / (h ** (0.88 if wave == "brass" else 1.2)))
            phase = 2 * np.pi * np.cumsum(fh * vib) / SR if vibrato > 0 else 2 * np.pi * fh * t
            tone += np.sin(phase + h * 0.4) * w
    if wave == "pad":
        tone /= 2.2
    att_e = np.clip(t / max(attack, 1e-3), 0, 1) ** 0.75
    rel_t = max(dur * 0.28, 0.04)
    rel_e = np.clip((dur - t) / rel_t, 0, 1) ** 1.25
    note_env = att_e * rel_e
    tone = sweep_filter(tone, 220.0 + brightness * (0.35 + 0.65 * note_env))
    buf[start:start + n] += tone * note_env * amp


def build_score():
    """
    Continuous 120 BPM score in D minor (beat = 0.50 s) spanning the full cinematic sequence (50.70 s):
    - Epic harmonic progression: Dm -> Bb -> F -> C -> Gm -> Dm -> Bb -> A7 -> Dm -> Bb -> Gm -> A7sus4
    - Sixteenth-note spiccato string ostinato + pulsing bass + taiko percussion + heroic brass theme.
    """
    total_s = 53.0
    score = np.zeros(int(total_s * SR))
    # Chord table: [t_start, t_end, bass_hz, chord_freqs, level, arp_bright]
    chords = [
        (0.0,  6.7,  36.71, [73.42, 110.00, 146.83, 174.61], 0.36, 850),    # Dm (Mark + Countdown)
        (6.7,  11.9, 36.71, [73.42, 110.00, 146.83, 174.61], 0.62, 1300),   # Dm (Ascent)
        (11.9, 17.3, 58.27, [58.27, 116.54, 146.83, 174.61], 0.70, 1550),   # Bb (Transfer)
        (17.3, 20.5, 43.65, [87.31, 130.81, 174.61, 220.00], 0.76, 1750),   # F  (Ring p1)
        (20.5, 23.3, 65.41, [65.41, 130.81, 164.81, 196.00], 0.80, 1850),   # C  (Ring p2)
        (23.3, 27.7, 49.00, [98.00, 146.83, 196.00, 233.08], 0.84, 1850),   # Gm (Load)
        (27.7, 31.5, 58.27, [58.27, 116.54, 146.83, 174.61], 0.90, 2150),   # Bb (Spin p1)
        (31.5, 35.3, 55.00, [55.00, 110.00, 138.59, 164.81, 220.00], 0.96, 2450),  # A7 (Spin climax)
        (35.3, 39.4, 36.71, [73.42, 110.00, 146.83, 174.61, 220.00], 1.00, 2700),  # Dm (Release)
        (39.4, 42.6, 58.27, [58.27, 116.54, 174.61, 233.08], 0.98, 2650),   # Bb (Flight p1)
        (42.6, 45.6, 49.00, [98.00, 146.83, 174.61, 233.08], 1.00, 2800),   # Gm (Flight p2)
        (45.6, 48.6, 55.00, [55.00, 110.00, 164.81, 220.00, 277.18], 1.00, 2950),  # A7sus4->A (Streak vertical descent)
    ]

    # 1. Harmonic pads + continuous deep bass.
    for t0, t1, bass, freqs, lvl, bright in chords:
        dur = t1 - t0
        pad = brass_chord(dur + 0.14, [bass] + freqs, attack=0.14, sustain_end=dur * 0.88, brightness=bright)
        place(score, pad * (0.42 * lvl), t0)

    # 2. Sixteenth-note rhythmic ostinato (0.125 s = 4 per beat at 120 BPM) starting at t = 2.0 s.
    arp_pattern = (0, 2, 1, 3, 2, 3, 1, 2)
    step_s = 0.125
    step_idx = 0
    at = 2.0
    while at < 48.55:
        for t0, t1, bass, freqs, lvl, bright in chords:
            if t0 <= at < t1:
                note_f = freqs[arp_pattern[step_idx % len(arp_pattern)] % len(freqs)] * (2.0 if at >= 27.7 else 1.0)
                accent = 1.25 if step_idx % 4 == 0 else (0.95 if step_idx % 2 == 0 else 0.72)
                drive_ramp = np.clip((at - 2.0) / 20.0, 0.25, 1.0)
                add_note(score, at, 0.14, note_f, wave="saw", brightness=bright * accent,
                         amp=0.16 * lvl * accent * drive_ramp, attack=0.008)
                # Eighth-note synchronized bass starting at 6.7 s.
                if at >= 6.7 and step_idx % 2 == 0:
                    add_note(score, at, 0.24, bass * 2.0, wave="saw", brightness=720,
                             amp=0.22 * lvl * (1.2 if step_idx % 4 == 0 else 0.75), attack=0.01)
                break
        at += step_s
        step_idx += 1

    # 3. Orchestral percussion (taiko / timpani + muted snare) at 120 BPM starting at t = 6.7 s.
    beat = 6.7
    beat_i = 0
    while beat < 48.55:
        intensity = np.clip((beat - 5.5) / 26.0, 0.45, 1.0)
        if beat_i % 2 == 0:  # downbeats
            place(score, drive(thump(0.38, 125, 42, 0.18), 1.7) * 0.48 * intensity, beat)
        if beat_i % 4 == 2:  # heavy backbeat
            snare = band(noise(0.18, 200 + beat_i), 350, 4200) * np.exp(-t_axis(0.18) / 0.045)
            place(score, (snare + thump(0.18, 180, 95, 0.06)) * 0.34 * intensity, beat)
        if beat >= 27.7 and beat_i % 4 == 3:  # buildup roll before each fast bar
            place(score, band(noise(0.10, 300 + beat_i), 300, 3600) * np.exp(-t_axis(0.10) / 0.03) * 0.28, beat + 0.25)
        beat += 0.50
        beat_i += 1

    # 4. Heroic brass theme (main memorable melody in D minor: midpoint & climax).
    melody = [
        # Lyrical entrance (RING / LOAD: 17.3 .. 27.7)
        (17.3, 2.10, 293.66, 0.38), (19.5, 1.60, 349.23, 0.40), (21.3, 1.80, 392.00, 0.40),
        (23.3, 2.10, 440.00, 0.46), (25.6, 1.90, 392.00, 0.44),
        # Epic buildup (SPIN: 27.7 .. 35.3)
        (27.7, 2.00, 466.16, 0.50), (29.8, 1.60, 440.00, 0.50), (31.5, 1.60, 349.23, 0.48),
        (33.2, 2.00, 329.63, 0.54),
        # Upper-octave heroic climax (RELEASE + FLIGHT + STREAK: 35.3 .. 48.6)
        (35.3, 2.10, 587.33, 0.68), (37.5, 1.70, 523.25, 0.64),
        (39.4, 2.10, 466.16, 0.66), (41.7, 1.40, 440.00, 0.64),
        (43.2, 2.20, 392.00, 0.68), (45.6, 0.80, 440.00, 0.66),
        (46.5, 2.00, 523.25, 0.74),
    ]
    for m_at, m_dur, m_f, m_amp in melody:
        add_note(score, m_at, m_dur, m_f, wave="brass", brightness=2300, amp=m_amp, attack=0.055, vibrato=1.0)
        add_note(score, m_at, m_dur, m_f * 0.5, wave="brass", brightness=1450, amp=m_amp * 0.45, attack=0.06, vibrato=0.4)

    # Dramatic cutoff at 48.62 s (HUSH entry) with cathedral reverb tail.
    cut_idx = int(48.62 * SR)
    score[cut_idx:] *= np.exp(-np.arange(len(score) - cut_idx) / (0.28 * SR))
    return reverb(score, wet=0.34, decay=1.15, seed=777)


MASTER_SCORE = None


def score_slice(t_start, seconds, tail_fade=0.45):
    """Extracts an exact slice from the master score with click-free crossfade tails."""
    global MASTER_SCORE
    if MASTER_SCORE is None:
        MASTER_SCORE = build_score()
    s0 = int(t_start * SR)
    n = int(seconds * SR)
    chunk = MASTER_SCORE[s0:s0 + n].copy()
    if len(chunk) < n:
        chunk = np.pad(chunk, (0, n - len(chunk)))
    fi = min(int(0.025 * SR), n // 4)
    fo = min(int(tail_fade * SR), n // 2)
    chunk[:fi] *= np.linspace(0, 1, fi)
    chunk[-fo:] *= np.linspace(1, 0, fo)
    return chunk


SOUND_FILES = {
    "mark": "orbital_01_mark", "countdown": "orbital_02_countdown", "ascent": "orbital_03_ascent",
    "transfer": "orbital_04_transfer", "ring": "orbital_05_ring", "load": "orbital_06_load",
    "spin": "orbital_07_spin", "release": "orbital_08_release", "flight": "orbital_09_flight",
    "streak": "orbital_10_streak", "hush": "orbital_11_hush", "crack": "orbital_12_crack",
    "boom": "orbital_13_boom", "wind": "orbital_14_wind", "hum": "orbital_15_hum",
}


def save(name, x, fade_out=0.02, loop=False):
    y = x if loop else fade(x, fade_out=fade_out)
    y = y / max(np.max(np.abs(y)), 1e-9) * PEAK
    os.makedirs(ROOT, exist_ok=True)
    out_name = SOUND_FILES.get(name, name)
    path = os.path.join(ROOT, out_name + ".ogg")
    # Chunked write: some libsndfile builds crash on a single large Vorbis block.
    with sf.SoundFile(path, "w", samplerate=SR, channels=1, format="OGG", subtype="VORBIS") as fh:
        step = SR // 2
        for i in range(0, len(y), step):
            fh.write(y[i:i + step].astype(np.float32))
    print("%-10s %5.2f s" % (name, len(y) / SR))


# ---------------------------------------------------------------- sound generators

def mark():
    """Target lock (t = 0.00..1.90 s): mechanical latch, sub-bass drop, and opening D minor chord."""
    seconds = 1.90
    out = score_slice(0.0, seconds, tail_fade=0.30) * 0.48
    place(out, metal(0.45, [310, 742, 1190, 2260], 0.08, 1) * 0.65, 0.0)
    place(out, thump(0.32, 160, 58, 0.09) * 0.85, 0.0)
    for at, f in ((0.25, 1174.66), (0.38, 1760.0)):
        t = t_axis(0.12)
        place(out, (np.sin(2 * np.pi * f * t) + 0.3 * np.sin(3 * np.pi * f * t)) * env(t, 0.004, 0.06) * 0.38, at)
    save("mark", reverb(out, wet=0.20, decay=0.45, seed=1))


def countdown():
    """Telemetry beep harmonized on a D-A fifth with sub-bass pulse (pitch adjusted in-game)."""
    t = t_axis(0.45)
    beep = (np.sin(2 * np.pi * 880 * t) + 0.45 * np.sin(2 * np.pi * 1318.5 * t) + 0.25 * np.sin(2 * np.pi * 1760 * t))
    beep *= env(t, 0.003, 0.09)
    sub = thump(0.45, 98, 48, 0.16) * 0.90
    save("countdown", reverb(beep * 0.55 + sub, wet=0.22, decay=0.45, seed=2))


def ascent():
    """Ascent (t = 6.70..12.45 s): continuous orchestral score + rocket roar."""
    seconds = 5.75
    t = t_axis(seconds)
    cutoff = 240 * (16.0 ** np.clip(t / 4.4, 0, 1))
    wind_n = sweep_filter(noise(seconds, 11), cutoff) - sweep_filter(noise(seconds, 11), cutoff * 0.25)
    rumble = band(noise(seconds, 12), 28, 120) * 1.45
    body = (wind_n * 1.35 + rumble) * (0.16 + 0.84 * np.clip(t / 4.4, 0, 1) ** 1.5)
    body *= np.clip((5.18 - t) / 0.15, 0, 1)
    music = score_slice(6.70, seconds, tail_fade=0.55) * 0.92
    save("ascent", reverb(body * 0.55, wet=0.24, decay=0.6, seed=10) + music, fade_out=0.35)


def transfer():
    """Orbital transfer (t = 11.90..17.85 s): continuous score + hyperspace whoosh."""
    seconds = 5.95
    t = t_axis(seconds)
    k = np.clip(t / 5.0, 0, 1)
    cutoff = 280 + 9500 * np.sin(np.pi * k) ** 2 * (t < 5.2)
    rush = (sweep_filter(noise(seconds, 111), cutoff) - sweep_filter(noise(seconds, 111), cutoff * 0.3)) * 1.35
    rush *= np.sin(np.pi * k) ** 0.75 * (t < 5.2)
    hit = band(noise(seconds, 112), 1100, 14000) * np.exp(-t / 0.04) * 0.95
    sub = thump(1.8, 108, 32, 0.85) * 0.78
    fx = rush + hit
    place(fx, drive(sub, 1.6), 0.0)
    music = score_slice(11.90, seconds, tail_fade=0.55) * 0.92
    save("transfer", reverb(fx * 0.52, wet=0.24, decay=0.65, seed=11) + music, fade_out=0.38)


def ring():
    """Orbital ring (t = 17.30..23.85 s): heroic theme entry + magnetic cascade."""
    seconds = 6.55
    t = t_axis(seconds)
    k = np.clip(t / 5.9, 0, 1)
    freq = 73.42 * (3.0 ** k)
    coil = (chirp(freq) + 0.45 * chirp(freq * 2) + 0.25 * chirp(freq * 3)) * 0.24
    coil *= (0.2 + 0.8 * k ** 1.2) * np.clip((6.10 - t) / 0.15, 0, 1)
    rng = np.random.default_rng(21)
    at = 0.08
    while at < 5.75:
        click = metal(0.12, [950 + 550 * rng.random(), 2200 + 800 * rng.random(), 3800], 0.022, int(at * 1000))
        place(coil, click * (0.18 + 0.26 * at / 5.9), at)
        at += 0.28 * (1 - 0.65 * at / 5.9)
    music = score_slice(17.30, seconds, tail_fade=0.55) * 0.95
    save("ring", reverb(coil * 0.58, wet=0.26, decay=0.70, seed=20) + music, fade_out=0.38)


def load():
    """Loading (t = 23.30..28.25 s): hydraulics + breech slams synced to the beat."""
    seconds = 4.95
    fx = np.zeros(int(seconds * SR))
    ts = t_axis(1.3)
    scrape = band(noise(1.3, 31), 700, 3600) * (0.6 + 0.4 * np.sin(2 * np.pi * 36 * ts)) * np.sin(np.pi * ts / 1.3) ** 0.7
    place(fx, scrape * 0.36, 0.05)
    for at, gain in ((1.65, 0.85), (3.30, 0.90)):
        place(fx, metal(0.85, [195, 412, 733, 1180, 1790, 2630], 0.30, int(at * 10)) * gain * 0.58, at)
        place(fx, drive(thump(0.50, 118, 38, 0.16) * 1.15, 1.6) * gain, at)
    music = score_slice(23.30, seconds, tail_fade=0.55) * 0.95
    save("load", reverb(fx * 0.60, wet=0.25, decay=0.65, seed=30) + music, fade_out=0.35)


def spin():
    """Acceleration (t = 27.70..35.85 s): electromagnetic ramp-up + symphonic crescendo."""
    seconds = 8.15
    t = t_axis(seconds)
    k = np.clip(t / 7.5, 0, 1)
    freq = 146.83 * (12.0 ** (k ** 1.35))
    whine = chirp(freq) + 0.38 * chirp(freq * 2.01) + 0.18 * chirp(freq * 0.5)
    flutter = 0.75 + 0.25 * chirp(6 + 75 * k ** 2)
    tail_env = np.clip((7.65 - t) / 0.18, 0, 1)
    fx = whine * flutter * (0.14 + 0.86 * k ** 1.2) * 0.24 * tail_env
    air = band(noise(seconds, 42), 500, 4800)
    for lap in (0.45, 0.65, 0.80, 0.92):
        fx += air * np.exp(-((t - lap * 7.5) / 0.08) ** 2) * 0.62
    music = score_slice(27.70, seconds, tail_fade=0.55) * 0.95
    save("spin", reverb(fx * 0.62, wet=0.26, decay=0.70, seed=40) + music, fade_out=0.38)


def release():
    """Release (t = 35.30..39.95 s): upper-octave heroic theme + electromagnetic rail discharge."""
    seconds = 4.65
    t = t_axis(seconds)
    fx = np.zeros(len(t))
    tc = t_axis(0.24)
    crack_fx = band(noise(0.24, 51), 1500, 16000) * np.exp(-tc / 0.006) * 1.4
    crack_fx += chirp(200 + 5200 * np.exp(-tc / 0.025)) * np.exp(-tc / 0.045) * 0.85
    place(fx, crack_fx, 0.0)
    sub = chirp(28 + 80 * np.exp(-t / 0.28)) * env(t, 0.008, 0.72)
    whoosh = sweep_filter(noise(seconds, 53), 6500 * np.exp(-t / 0.65) + 150) * env(t, 0.02, 0.55) * 0.42
    fx = drive(sub * 0.85, 2.0) + whoosh
    music = score_slice(35.30, seconds, tail_fade=0.55) * 0.95
    save("release", reverb(fx * 0.58, wet=0.25, decay=0.65, seed=50) + music, fade_out=0.35)


def flight():
    """Onboard camera (t = 39.40..45.60 s): intense atmospheric reentry + orchestral climax."""
    seconds = 6.20
    t = t_axis(seconds)
    k = t / seconds
    hull = drive(band(noise(seconds, 61), 28, 135) * 1.45 + np.sin(2 * np.pi * 55.0 * t) * 0.32, 1.6) * (0.42 + 0.30 * k)
    rise = np.clip((k - 0.22) / 0.78, 0, 1)
    center = 360 * (9.0 ** rise)
    howl = (sweep_filter(noise(seconds, 62), center * 1.6) - sweep_filter(noise(seconds, 62), center * 0.55)) * 1.6 * rise ** 1.45
    rng = np.random.default_rng(63)
    impulses = (rng.random(len(t)) < 0.0006 + 0.010 * rise ** 2) * rng.uniform(-1, 1, len(t))
    crackle = band(np.convolve(impulses, np.exp(-np.arange(90) / 14.0), "same"), 1800, 15000) * 1.55 * rise
    fx = reverb(hull + howl + crackle, wet=0.22, decay=0.55, seed=60) * 0.58
    static = noise(seconds, 64) * np.clip((t - 5.90) / 0.05, 0, 1) * 0.55
    music = score_slice(39.40, seconds, tail_fade=0.05) * 0.95
    save("flight", (fx + music + static) * np.clip((6.17 - t) / 0.02, 0, 1), fade_out=0.01)


def streak():
    """Final vertical dive (t = 45.60..48.75 s): final rising bar + Doppler tear."""
    seconds = 3.15
    t = t_axis(seconds)
    freq = 2600 * (0.24 ** (t / seconds))
    whistle = chirp(freq) * 0.28 + chirp(freq * 1.5) * 0.12
    air = sweep_filter(noise(seconds, 71), freq * 1.6) - sweep_filter(noise(seconds, 71), freq * 0.45)
    rumble = band(noise(seconds, 72), 40, 220) * 0.95
    fx = reverb((whistle + air * 1.55 + rumble) * np.sin(np.pi * t / seconds) ** 1.4, wet=0.25, decay=0.60, seed=70) * 0.65
    music = score_slice(45.60, seconds, tail_fade=0.14) * 0.92
    save("streak", fx + music, fade_out=0.12)


def hush():
    """Tense silence (t = 48.60..50.95 s): cathedral cutoff echo + heartbeats."""
    seconds = 2.35
    t = t_axis(seconds)
    out = score_slice(48.60, seconds, tail_fade=0.22) * 0.72
    out += (np.sin(2 * np.pi * 138 * t) + np.sin(2 * np.pi * 139.6 * t)) * 0.045
    out += np.sin(2 * np.pi * 3150 * t) * 0.012 * np.sin(np.pi * t / seconds)
    sub_suck = np.sin(2 * np.pi * (95 * (1 - 0.65 * t / seconds)) * t) * np.exp(-t / 0.28) * 0.42
    out += sub_suck
    for at in (0.22, 1.25):
        place(out, thump(0.32, 78, 40, 0.10) * 0.95, at)
        place(out, thump(0.28, 64, 36, 0.08) * 0.68, at + 0.24)
    save("hush", out, fade_out=0.14)


def crack():
    """Initial blast: point-blank air rip, arc snap, and immediate sub-bass punch."""
    seconds = 0.65
    t = t_axis(seconds)
    tear = band(noise(seconds, 81), 850, 16500) * (np.exp(-t / 0.012) * 2.2 + np.exp(-t / 0.13) * 0.55)
    tear = sweep_filter(tear, 15000 * np.exp(-t / 0.14) + 520)
    snap = chirp(140 + 3600 * np.exp(-t / 0.018)) * np.exp(-t / 0.055) * 0.9
    punch = drive(thump(seconds, 130, 32, 0.18) * 1.4, 2.2)
    save("crack", reverb(tear + snap + punch, wet=0.22, decay=0.45, seed=80), fade_out=0.08)


def boom():
    """Seismic detonation + tragic post-impact symphonic coda (majestic D minor)."""
    seconds = 5.40
    t = t_axis(seconds)
    sub = chirp(24 + 74 * np.exp(-t / 0.38)) * env(t, 0.004, 1.25)
    sub += chirp(38 + 48 * np.exp(-t / 0.55)) * env(t, 0.004, 0.95) * 0.65
    blast = band(noise(seconds, 91), 20, 290) * env(t, 0.003, 0.72) * 2.2
    click = band(noise(seconds, 92), 600, 9500) * np.exp(-t / 0.020) * 1.15
    # Low orchestral coda followed by a falling brass/cello elegy.
    dark_brass = brass_chord(seconds, [36.71, 73.42, 110.0, 146.83], attack=0.08, sustain_end=3.4, brightness=1350) * 0.72
    coda = np.zeros(len(t))
    add_note(coda, 0.40, 2.2, 146.83, wave="brass", brightness=1200, amp=0.52, attack=0.14, vibrato=0.8)  # D3
    add_note(coda, 2.40, 2.5, 174.61, wave="brass", brightness=1100, amp=0.46, attack=0.18, vibrato=0.9)  # F3
    add_note(coda, 2.40, 2.5, 116.54, wave="pad",   brightness=850,  amp=0.38, attack=0.18)               # Bb2
    sig = drive((sub + blast) * 1.65, 3.0) + click + dark_brass + coda
    save("boom", reverb(sig, wet=0.38, decay=1.45, seed=90), fade_out=0.95)


def wind():
    """Shockwave and orchestral finale: tectonic rumble + calm low choir."""
    seconds = 8.00
    t = t_axis(seconds)
    base = band(noise(seconds, 101), 20, 185)
    rolls = np.zeros(len(t))
    rng = np.random.default_rng(102)
    for at in (0.0, 0.85, 1.85, 2.9, 4.1, 5.4, 6.6):
        rolls += np.exp(-((t - at) / (0.32 + 0.22 * rng.random())) ** 2) * (1.0 - 0.1 * at) * (0.6 + 0.4 * rng.random())
    rumble = drive(base * (0.35 + rolls) * 2.2, 2.1) * np.exp(-t / 4.2)
    gust = sweep_filter(noise(seconds, 103), 950 * np.exp(-t / 1.7) + 120) * np.exp(-t / 2.0) * 0.88
    impulses = (rng.random(len(t)) < 0.0011) * rng.uniform(-1, 1, len(t))
    cracks = band(np.convolve(impulses, np.exp(-np.arange(400) / 60.0), "same"), 300, 3500) * np.exp(-t / 2.4) * 1.25
    choir = brass_chord(seconds, [36.71, 73.42, 110.0, 164.81, 220.0], attack=0.80, sustain_end=5.4, brightness=1050) * 0.42
    save("wind", reverb(rumble + gust + cracks + choir, wet=0.36, decay=1.35, seed=100), fade_out=1.50)


def hum():
    """Deep harmonic hum of the giant lance embedded in the crater (seamless loop)."""
    seconds = 2.0
    t = t_axis(seconds)
    # 73.5 Hz * 2.0 s = 147 exact periods for click-free looping.
    out = np.sin(2 * np.pi * 73.5 * t) + 0.52 * np.sin(2 * np.pi * 147.0 * t) + 0.24 * np.sin(2 * np.pi * 220.5 * t)
    out *= 0.85 + 0.15 * np.sin(2 * np.pi * 0.5 * t)
    out += np.sin(2 * np.pi * 441.0 * t) * 0.035 * (1 + np.sin(2 * np.pi * 1.5 * t))
    save("hum", out, loop=True)


if __name__ == "__main__":
    for make in (mark, countdown, ascent, transfer, ring, load, spin, release, flight, streak, hush, crack, boom, wind, hum):
        make()
    print("Sounds generated in", ROOT)
