# app/src/main/python/breath_offline.py
# -*- coding: utf-8 -*-
import os, io, math, zipfile, struct, wave
import numpy as np

# ============ config (must match training) ============
K = 96
C = 2
H = 64
W = 64

SR = 48000
CLIP_SEC = 30
OVERLAP_SEC = 15

N_FFT = 2048
HOP = N_FFT // 4
WIN = N_FFT
N_MELS = 128

# --------- helpers: mel filter (HTK=False, slaney-ish) ----------
def hz_to_mel(hz):
    return 2595.0 * np.log10(1.0 + hz / 700.0)

def mel_to_hz(m):
    return 700.0 * (10.0 ** (m / 2595.0) - 1.0)

def mel_filterbank(sr, n_fft, n_mels, fmin=0.0, fmax=None):
    if fmax is None:
        fmax = sr / 2.0
    n_freq = n_fft // 2 + 1
    m_min = hz_to_mel(fmin)
    m_max = hz_to_mel(fmax)
    m_pts = np.linspace(m_min, m_max, n_mels + 2, dtype=np.float32)
    hz_pts = mel_to_hz(m_pts)
    bins = np.floor((n_fft + 1) * hz_pts / sr).astype(np.int32)
    fb = np.zeros((n_mels, n_freq), dtype=np.float32)
    for i in range(n_mels):
        a, b, c = bins[i], bins[i + 1], bins[i + 2]
        if b <= a: b = a + 1
        if c <= b: c = b + 1
        for j in range(a, b):
            if 0 <= j < n_freq:
                fb[i, j] = (j - a) / (b - a)
        for j in range(b, c):
            if 0 <= j < n_freq:
                fb[i, j] = (c - j) / (c - b)
    return fb

FB = mel_filterbank(SR, N_FFT, N_MELS, 0.0, SR/2.0)

def stft_mag(x, n_fft=N_FFT, hop=HOP, win=WIN):
    x = np.asarray(x, dtype=np.float32)
    if x.ndim != 1:
        x = x.reshape(-1)
    w = np.hanning(win).astype(np.float32)
    n = len(x)
    if n < win:
        pad = win - n
        x = np.pad(x, (0, pad), mode="reflect")
        n = len(x)
    frames = 1 + (n - win) // hop
    out = np.empty((n_fft//2 + 1, frames), dtype=np.float32)
    for i in range(frames):
        s = i * hop
        frame = x[s:s+win] * w
        spec = np.fft.rfft(frame, n=n_fft)
        mag = (np.abs(spec) ** 2).astype(np.float32)  # power=2.0
        out[:, i] = mag
    return out

def log_mel(x):
    # x: pcm float [-1,1]
    S = stft_mag(x)
    M = np.maximum(1e-10, FB @ S)   # [n_mels, T]
    L = np.log(M).astype(np.float32)
    return L

def center_crop_or_pad_2d(a, th=H, tw=W):
    # a: [H0,W0]
    h, w = a.shape
    # crop/pad H
    if h > th:
        s = (h - th) // 2
        a = a[s:s+th, :]
    elif h < th:
        p = th - h
        pt = p // 2
        pb = p - pt
        a = np.pad(a, ((pt,pb),(0,0)), mode="constant", constant_values=0.0)
    # crop/pad W
    h, w = a.shape
    if w > tw:
        s = (w - tw) // 2
        a = a[:, s:s+tw]
    elif w < tw:
        p = tw - w
        pl = p // 2
        pr = p - pl
        a = np.pad(a, ((0,0),(pl,pr)), mode="constant", constant_values=0.0)
    return a.astype(np.float32)

def ensure_k_windows(wins, K=K):
    # wins: list of [C,H,W]
    if len(wins) == 0:
        z = np.zeros((C,H,W), dtype=np.float32)
        wins = [z]
    if len(wins) >= K:
        # uniform select
        idx = np.linspace(0, len(wins)-1, K).astype(np.int32)
        return [wins[i] for i in idx]
    # pad by repeat
    out = list(wins)
    while len(out) < K:
        out.append(out[len(out) % len(wins)])
    return out[:K]

def build_input_from_audio_pcm(pcm, sr=SR):
    # pcm float [-1,1] at sr
    clip = int(CLIP_SEC * sr)
    hop = int((CLIP_SEC - OVERLAP_SEC) * sr)  # 15s
    if hop <= 0:
        hop = clip
    n = len(pcm)
    if n < clip:
        pcm = np.pad(pcm, (0, clip-n), mode="reflect")
        n = len(pcm)

    wins = []
    for s in range(0, n - clip + 1, hop):
        seg = pcm[s:s+clip]
        lm = log_mel(seg)             # [128, T]
        lm = center_crop_or_pad_2d(lm, H, W)  # -> [64,64] (你训练里是64)
        ch0 = lm
        ch1 = np.zeros_like(ch0, dtype=np.float32)  # 你 ch1 先占位（后续可做 delta/置信图）
        x = np.stack([ch0, ch1], axis=0)            # [2,64,64]
        wins.append(x)
        if len(wins) >= K:
            break

    wins = ensure_k_windows(wins, K)
    X = np.stack(wins, axis=0).astype(np.float32)   # [K,2,64,64]
    return X

def read_wav(path):
    with wave.open(path, "rb") as wf:
        ch = wf.getnchannels()
        sr = wf.getframerate()
        n = wf.getnframes()
        raw = wf.readframes(n)
    # pcm16
    x = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0
    if ch > 1:
        x = x.reshape(-1, ch).mean(axis=1)
    return x, sr

def naive_resample(x, sr_in, sr_out):
    if sr_in == sr_out:
        return x
    # linear resample (够用来跑通)
    ratio = float(sr_out) / float(sr_in)
    n_out = int(len(x) * ratio)
    t_in = np.linspace(0.0, 1.0, len(x), endpoint=False)
    t_out = np.linspace(0.0, 1.0, n_out, endpoint=False)
    y = np.interp(t_out, t_in, x).astype(np.float32)
    return y

def load_npz_X(path):
    d = np.load(path, allow_pickle=True)
    X = np.asarray(d["X"], dtype=np.float32)
    # X: [N,H,W] or [N,C,H,W]
    if X.ndim == 3:
        X = X[:, None, :, :]
    if X.ndim != 4:
        raise ValueError("NPZ X must be [N,H,W] or [N,C,H,W]")
    return X

def npz_to_model_input(path):
    X = load_npz_X(path)  # [N,C,H,W]
    N = X.shape[0]
    # select/pad to K
    if N >= K:
        idx = np.linspace(0, N-1, K).astype(np.int32)
        Xk = X[idx]
    else:
        Xk = X
        while Xk.shape[0] < K:
            ridx = np.random.randint(0, Xk.shape[0])
            Xk = np.concatenate([Xk, Xk[ridx:ridx+1]], axis=0)
        Xk = Xk[:K]
    # fix channels to C
    if Xk.shape[1] != C:
        if Xk.shape[1] > C:
            Xk = Xk[:, :C]
        else:
            last = Xk[:, -1:, :, :]
            pads = [last] * (C - Xk.shape[1])
            Xk = np.concatenate([Xk] + pads, axis=1)[:, :C]
    # resize/crop/pad to 64x64 each window
    out = np.empty((K, C, H, W), dtype=np.float32)
    for i in range(K):
        for c in range(C):
            out[i, c] = center_crop_or_pad_2d(Xk[i, c], H, W)
    return out

def prepare_input(file_path):
    """
    returns dict:
      x_flat: list[float] length = K*C*H*W
      meta: str
    """
    ext = os.path.splitext(file_path)[1].lower()
    if ext == ".npz":
        X = npz_to_model_input(file_path)
        meta = f"from=npz X={X.shape}"
    elif ext in (".wav",):
        pcm, sr = read_wav(file_path)
        pcm = naive_resample(pcm, sr, SR)
        X = build_input_from_audio_pcm(pcm, SR)
        meta = f"from=wav pcm={len(pcm)} sr={SR} X={X.shape}"
    elif ext in (".mp4", ".m4a", ".aac"):
        # Android 侧先把 mp4 抽成 wav（我们 Kotlin 做），这里按 wav 处理更稳
        raise RuntimeError("mp4/m4a 请先在 Android 侧抽音频为 wav，再调用 prepare_input(wav_path)")
    else:
        raise RuntimeError(f"unsupported file: {ext}")

    x_flat = X.reshape(-1).astype(np.float32).tolist()
    return {"x_flat": x_flat, "meta": meta}
