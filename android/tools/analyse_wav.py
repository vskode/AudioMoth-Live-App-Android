#!/usr/bin/env python3
"""Check a Phase 0 recording: header, duration, and energy by frequency band.

Usage: python3 analyse_wav.py recording.wav [more.wav ...]
Needs only numpy. If matplotlib is installed, also saves <file>.png spectrogram.
"""

import sys
import wave

import numpy as np


def analyse(path):
    with wave.open(path, 'rb') as w:
        rate, ch, width, n = w.getframerate(), w.getnchannels(), w.getsampwidth(), w.getnframes()
        data = np.frombuffer(w.readframes(n), dtype='<i2').astype(np.float64)

    print(f'{path}\n  {rate} Hz, {ch} ch, {width * 8} bit, {n} frames = {n / rate:.2f} s')
    print(f'  peak {np.abs(data).max():.0f}, rms {data.std():.1f}')

    nfft = 4096
    blocks = len(data) // nfft
    if blocks == 0:
        print('  too short')
        return

    seg = data[:blocks * nfft].reshape(blocks, nfft)
    seg = seg - seg.mean(axis=1, keepdims=True)
    spec = np.abs(np.fft.rfft(seg * np.hanning(nfft), axis=1)) ** 2
    psd = spec.mean(axis=0)
    freqs = np.fft.rfftfreq(nfft, 1 / rate)

    edges = [e for e in (0, 1e3, 24e3, 48e3, 96e3, 144e3, 192e3) if e < rate / 2] + [rate / 2]
    total = psd.sum()
    for lo, hi in zip(edges[:-1], edges[1:]):
        s = psd[(freqs >= lo) & (freqs < hi)].sum()
        print(f'  {lo / 1e3:5.0f}-{hi / 1e3:5.0f} kHz: {10 * np.log10(s / total + 1e-30):7.1f} dB')

    hf = freqs > 24e3
    if hf.any():
        print(f'  strongest component above 24 kHz: {freqs[hf][psd[hf].argmax()] / 1e3:.1f} kHz')

    try:
        import matplotlib
        matplotlib.use('Agg')
        import matplotlib.pyplot as plt
        plt.figure(figsize=(12, 5))
        plt.imshow(10 * np.log10(spec.T + 1e-9), origin='lower', aspect='auto',
                   extent=[0, blocks * nfft / rate, 0, rate / 2e3], cmap='magma')
        plt.xlabel('time (s)')
        plt.ylabel('frequency (kHz)')
        plt.colorbar(label='dB')
        plt.savefig(path + '.png', dpi=100, bbox_inches='tight')
        print(f'  spectrogram saved to {path}.png')
    except ImportError:
        pass


if __name__ == '__main__':
    for p in sys.argv[1:]:
        analyse(p)
