#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
FMCW 声纳 OSA 诊断（简化可用版）

目标：先实现“能诊断”的版本，不追求极致复杂度：
  - FMCW 解调 + 距离门
  - 用慢时间 env 选 target_bin
  - 单 bin envelope 做呼吸频率 + 事件检测
  - 多特征滑窗事件 (central/hypopnea/obstructive)
  - 简单质量评估 + AHI + 严重程度

注意：
  - 不使用相位 / IMU / 姿态 / 复杂 quality gating
  - 适合作为 baseline，后续再慢慢加复杂逻辑
"""

import os
import time
import math
from typing import Dict, Any, List, Tuple, Optional, Sequence

import numpy as np

# ------- 全局流式状态 -------

_STREAMS: Dict[str, Dict[str, Any]] = {}
_STREAM_MAX_SEC = 600.0      # 最多保留 10 分钟历史
CHEST_START_BIN = 3          # 距离门起始 bin
CHEST_END_BIN_MAX = 80       # 距离门最大 bin


def _get_state(stream_id: str) -> Dict[str, Any]:
    st = _STREAMS.get(stream_id)
    if st is None:
        st = _STREAMS.setdefault(
            stream_id,
            dict(
                start_time=time.time(),
                env=[],
                rt_rows=[],
                last_save=0.0,
                peak_baseline=None,
                baseline_ready=False,
                baseline_last_update=0.0,
                target_bin=None,
                last_target_update_sec=0.0,
                breath_fft_conf=0.0,
                breath_bpm_fft=0.0,
                breath_bpm_peak=0.0,
            ),
        )
    return st


# ----------------- 小工具 -----------------

def _next_pow2(n: int) -> int:
    m = 1
    while m < n:
        m <<= 1
    return m


def _moving_average(x: np.ndarray, win_sec: float, fs: float) -> np.ndarray:
    if x.size == 0:
        return x
    k = int(max(1, round(win_sec * fs)))
    if k <= 1:
        return x.copy()
    kernel = np.ones(k, dtype=np.float32) / float(k)
    y = np.convolve(x.astype(np.float32), kernel, mode="same")
    return y.astype(np.float32)


def _find_local_peaks(
        env: np.ndarray,
        fs_env: float,
        min_dist_sec: float = 3.0,
) -> List[Tuple[int, float, float]]:
    """
    简单局部峰查找：
      返回 [(idx, amp, t_sec), ...]
    """
    N = int(env.size)
    if N < 3:
        return []

    min_dist_samp = int(max(1, round(min_dist_sec * fs_env)))
    cand_idx: List[int] = []
    for i in range(1, N - 1):
        if env[i] >= env[i - 1] and env[i] >= env[i + 1]:
            cand_idx.append(i)
    if not cand_idx:
        return []

    peaks: List[Tuple[int, float, float]] = []
    last_keep = -10 * min_dist_samp
    for i in cand_idx:
        if i - last_keep < min_dist_samp:
            if peaks and env[i] > peaks[-1][1]:
                peaks[-1] = (i, float(env[i]), float(i / fs_env))
                last_keep = i
            continue
        peaks.append((i, float(env[i]), float(i / fs_env)))
        last_keep = i
    return peaks


def _update_peak_baseline(
        state: Dict[str, Any],
        peaks: List[Tuple[int, float, float]],
        min_peaks: int = 20,
) -> None:
    """
    基于呼吸峰振幅估计 baseline（μ, σ）：
      - 初始阶段：收集足够多峰后一次性估出 baseline
      - 运行阶段：对新峰做 EMA 更新
    """
    if not peaks:
        return

    peak_amps = np.array([p[1] for p in peaks], dtype=np.float32)

    if not state.get("baseline_ready", False):
        if len(peak_amps) < min_peaks:
            return
        mu = float(np.mean(peak_amps))
        sigma = float(np.std(peak_amps) + 1e-6)
        state["peak_baseline"] = {"mu_amp": mu, "sigma_amp": sigma}
        state["baseline_ready"] = True
        state["baseline_last_update"] = time.time()
        print(f"[BREATH] init baseline: mu={mu:.3f}, sigma={sigma:.3f}")
        return

    pb = state.get("peak_baseline", {})
    mu0 = float(pb.get("mu_amp", np.mean(peak_amps)))
    s0 = float(pb.get("sigma_amp", np.std(peak_amps) + 1e-6))

    mu_new = float(np.mean(peak_amps))
    s_new = float(np.std(peak_amps) + 1e-6)

    alpha = 0.1
    mu = (1 - alpha) * mu0 + alpha * mu_new
    sigma = (1 - alpha) * s0 + alpha * s_new

    state["peak_baseline"] = {"mu_amp": mu, "sigma_amp": sigma}
    state["baseline_last_update"] = time.time()


# ----------------- 目标 bin 搜索 -----------------

def _select_target_bin(
        rt_all: np.ndarray,
        fs_env: float,
        f_min: float = 0.1,
        f_max: float = 0.7,
) -> Optional[int]:
    """
    从所有 range bin 中选出“呼吸主导 bin”：
      - 最近 40s 做 FFT
      - 在 [f_min, f_max] 呼吸带内找峰
      - 取峰值最大的 bin
    """
    if rt_all.size == 0:
        return None

    T, B = rt_all.shape
    if T < fs_env * 8:
        return None

    MAX_WIN_SEC = 40.0
    max_len = int(MAX_WIN_SEC * fs_env)
    if T > max_len:
        rt_win = rt_all[-max_len:]
    else:
        rt_win = rt_all

    T = rt_win.shape[0]
    nfft = _next_pow2(T)
    freqs = np.fft.rfftfreq(nfft, d=1.0 / fs_env)
    band_mask = (freqs >= f_min) & (freqs <= f_max)
    if not np.any(band_mask):
        return None

    scores = np.zeros(B, dtype=np.float32)
    win = np.hanning(T).astype(np.float32)
    for j in range(B):
        sig = rt_win[:, j].astype(np.float32)
        sig = sig - sig.mean()
        sig *= win
        spec = np.fft.rfft(sig, n=nfft)
        mag = np.abs(spec)
        scores[j] = float(np.max(mag[band_mask]) + 1e-6)

    best_idx = int(np.argmax(scores))
    best_score = float(scores[best_idx])
    print(f"[TARGET_BIN] select bin={best_idx}, score={best_score:.4f}")
    return best_idx


# ----------------- 呼吸频率估计 -----------------

def _estimate_breath_rate_fft(
        env_raw: np.ndarray,
        fs_env: float,
        f_min: float = 0.1,
        f_max: float = 0.7,
) -> Tuple[float, float]:
    if env_raw.size < fs_env * 8:
        return 0.0, 0.0

    MAX_SEC = 40.0
    max_len = int(MAX_SEC * fs_env)
    if env_raw.size > max_len:
        env_win = env_raw[-max_len:]
    else:
        env_win = env_raw

    T = env_win.size
    if T < 8:
        return 0.0, 0.0

    env_win = env_win.astype(np.float32)
    env_win = env_win - env_win.mean()
    win = np.hanning(T).astype(np.float32)
    sig = env_win * win

    nfft = _next_pow2(T)
    freqs = np.fft.rfftfreq(nfft, d=1.0 / fs_env)
    spec = np.fft.rfft(sig, n=nfft)
    mag = np.abs(spec)

    band = (freqs >= f_min) & (freqs <= f_max)
    if not np.any(band):
        return 0.0, 0.0

    mag_band = mag[band]
    freqs_band = freqs[band]

    idx = int(np.argmax(mag_band))
    f_peak = float(freqs_band[idx])
    amp_peak = float(mag_band[idx])
    amp_mean = float(np.mean(mag_band) + 1e-6)

    ratio = amp_peak / amp_mean
    conf = max(0.0, min(1.0, (ratio - 1.0) / 4.0))  # ratio>=5 → conf≈1

    bpm = f_peak * 60.0
    if bpm < 6.0 or bpm > 42.0:
        return 0.0, 0.0

    return float(bpm), float(conf)


def _estimate_breath_rate_and_peaks(
        env_raw: np.ndarray,
        fs_env: float,
        state: Dict[str, Any],
) -> Tuple[float, List[Tuple[int, float, float]], float, float]:
    breath_bpm = 0.0
    mu_amp = 0.0
    sigma_amp = 0.0

    if env_raw.size < fs_env * 8:
        return breath_bpm, [], mu_amp, sigma_amp

    env = _moving_average(env_raw, win_sec=0.5, fs=fs_env)
    peaks_all = _find_local_peaks(env, fs_env, min_dist_sec=3.0)
    if not peaks_all:
        return breath_bpm, [], mu_amp, sigma_amp

    _update_peak_baseline(state, peaks_all, min_peaks=8)
    pb = state.get("peak_baseline", None)
    if pb is None:
        return breath_bpm, [], mu_amp, sigma_amp

    mu_amp = float(pb.get("mu_amp", 0.0))
    sigma_amp = float(pb.get("sigma_amp", 1e-6))
    amp_thresh = max(0.0, mu_amp - 2.0 * sigma_amp)

    valid_peaks: List[Tuple[int, float, float]] = []
    for idx, amp, t in peaks_all:
        if amp >= amp_thresh:
            valid_peaks.append((idx, amp, t))

    if len(valid_peaks) < 2:
        return breath_bpm, [], mu_amp, sigma_amp

    times = np.array([p[2] for p in valid_peaks], dtype=np.float32)
    intervals = np.diff(times)
    mask = (intervals >= 2.0) & (intervals <= 20.0)
    if not np.any(mask):
        return breath_bpm, [], mu_amp, sigma_amp

    T_med = float(np.median(intervals[mask]))
    if T_med > 0:
        breath_bpm = 60.0 / T_med

    return float(breath_bpm), valid_peaks, float(mu_amp), float(sigma_amp)


def _combine_breath_rate(
        env_raw: np.ndarray,
        fs_env: float,
        state: Dict[str, Any],
) -> Tuple[float, float, float, List[Tuple[int, float, float]], float, float]:
    bpm_fft, conf_fft = _estimate_breath_rate_fft(env_raw, fs_env)
    bpm_peak, peaks, mu_amp, sigma_amp = _estimate_breath_rate_and_peaks(
        env_raw, fs_env, state
    )

    if bpm_fft > 0 and conf_fft >= 0.4:
        breath_bpm = bpm_fft
    elif bpm_peak > 0:
        breath_bpm = bpm_peak
    elif bpm_fft > 0:
        breath_bpm = bpm_fft
    else:
        breath_bpm = 0.0

    state["breath_fft_conf"] = float(conf_fft)
    state["breath_bpm_fft"] = float(bpm_fft)
    state["breath_bpm_peak"] = float(bpm_peak)

    return (
        float(breath_bpm),
        float(bpm_fft),
        float(bpm_peak),
        peaks,
        float(mu_amp),
        float(sigma_amp),
    )


# ----------------- 滑窗事件检测 -----------------

def _compute_window_features(
        env_win: np.ndarray,
        fs_env: float,
        peaks: List[Tuple[int, float, float]],
        mu_amp: float,
        sigma_amp: float,
        t0: float,
        t1: float,
        env_var_baseline: float,
) -> Dict[str, float]:
    if env_win.size == 0:
        return {
            "amp_ratio": 0.0,
            "var_ratio": 0.0,
            "spec_ratio": 0.0,
            "peak_count": 0.0,
        }

    env_win = env_win.astype(np.float32)
    var_env = float(np.var(env_win))

    pk_amps: List[float] = []
    for idx, amp, t in peaks:
        if t0 <= t < t1:
            pk_amps.append(amp)
    if pk_amps:
        avg_amp = float(np.mean(pk_amps))
    else:
        avg_amp = 0.0

    if mu_amp > 1e-6:
        amp_ratio = avg_amp / mu_amp
    else:
        amp_ratio = 0.0

    base_var = env_var_baseline if env_var_baseline > 0 else max(mu_amp ** 2, 1e-6)
    var_ratio = var_env / base_var

    T = env_win.size
    nfft = _next_pow2(T)
    freqs = np.fft.rfftfreq(nfft, d=1.0 / fs_env)
    sig = env_win - env_win.mean()
    win = np.hanning(T).astype(np.float32)
    sig *= win
    spec = np.fft.rfft(sig, n=nfft)
    mag = np.abs(spec)
    total_power = float(np.sum(mag ** 2) + 1e-6)

    band_mask = (freqs >= 0.1) & (freqs <= 0.7)
    if np.any(band_mask):
        band_power = float(np.sum(mag[band_mask] ** 2))
        spec_ratio = band_power / total_power
    else:
        spec_ratio = 0.0

    return {
        "amp_ratio": float(amp_ratio),
        "var_ratio": float(var_ratio),
        "spec_ratio": float(spec_ratio),
        "peak_count": float(len(pk_amps)),
    }


def _classify_window(feats: Dict[str, float]) -> Optional[str]:
    """
    简单规则：
      - central      : 峰很少，方差低，呼吸带能量低
      - hypopnea     : 振幅下降但呼吸带能量存在
      - obstructive  : 振幅波动大，方差高 & 呼吸带能量高
    """
    amp_ratio = feats["amp_ratio"]
    var_ratio = feats["var_ratio"]
    spec_ratio = feats["spec_ratio"]
    peak_count = feats["peak_count"]

    if peak_count <= 1 and var_ratio < 0.2 and spec_ratio < 0.2:
        return "central_apnea"

    if 0.25 <= amp_ratio <= 0.8 and spec_ratio >= 0.15:
        return "hypopnea"

    if amp_ratio >= 1.2 and var_ratio >= 1.0 and spec_ratio >= 0.15:
        return "obstructive_apnea"

    return None


def _detect_events_apnea_style_windows(
        env_raw: np.ndarray,
        fs_env: float,
        peaks: List[Tuple[int, float, float]],
        mu_amp: float,
        sigma_amp: float,
) -> List[Dict[str, Any]]:
    events: List[Dict[str, Any]] = []
    if env_raw.size == 0 or fs_env <= 0 or mu_amp <= 0:
        return events

    total_sec = env_raw.size / fs_env
    if total_sec < 10.0:
        return events

    WIN_SEC = 10.0
    STEP_SEC = 5.0
    win_samp = int(round(WIN_SEC * fs_env))
    step_samp = int(round(STEP_SEC * fs_env))

    env_var_baseline = float(np.var(env_raw)) + 1e-6
    win_labels: List[Tuple[float, float, Optional[str]]] = []

    start_idx = 0
    while start_idx + win_samp <= env_raw.size:
        end_idx = start_idx + win_samp
        t0 = start_idx / fs_env
        t1 = end_idx / fs_env

        env_win = env_raw[start_idx:end_idx]
        feats = _compute_window_features(
            env_win, fs_env, peaks, mu_amp, sigma_amp, t0, t1, env_var_baseline
        )
        lbl = _classify_window(feats)
        if lbl is not None:
            win_labels.append((t0, t1, lbl))

        start_idx += step_samp

    if not win_labels:
        return events

    cur_label: Optional[str] = None
    cur_start: Optional[float] = None
    cur_end: Optional[float] = None

    for (t0, t1, lbl) in win_labels:
        if cur_label is None:
            cur_label = lbl
            cur_start = t0
            cur_end = t1
            continue

        if lbl == cur_label and t0 <= (cur_end or t0) + STEP_SEC:
            cur_end = t1
        else:
            if cur_label is not None and cur_start is not None and cur_end is not None:
                if cur_end - cur_start >= 10.0:
                    events.append(
                        {
                            "type": cur_label,
                            "start_sec": float(cur_start),
                            "end_sec": float(cur_end),
                        }
                    )
            cur_label = lbl
            cur_start = t0
            cur_end = t1

    if cur_label is not None and cur_start is not None and cur_end is not None:
        if cur_end - cur_start >= 10.0:
            events.append(
                {
                    "type": cur_label,
                    "start_sec": float(cur_start),
                    "end_sec": float(cur_end),
                }
            )

    return events


# ----------------- AHI 计算 & 严重程度 -----------------

def _compute_ahi_by_type(
        events_all: List[Dict[str, Any]],
        total_duration_sec: float,
) -> Dict[str, float]:
    if total_duration_sec <= 0:
        return {
            "ahi_total": 0.0,
            "ahi_central": 0.0,
            "ahi_obstructive": 0.0,
            "ahi_hypopnea": 0.0,
        }

    hours = total_duration_sec / 3600.0
    n_central = sum(1 for e in events_all if e.get("type") == "central_apnea")
    n_obst = sum(1 for e in events_all if e.get("type") == "obstructive_apnea")
    n_hypo = sum(1 for e in events_all if e.get("type") == "hypopnea")

    ahi_central = float(n_central) / hours
    ahi_obst = float(n_obst) / hours
    ahi_hypo = float(n_hypo) / hours
    ahi_total = ahi_central + ahi_obst + ahi_hypo

    return {
        "ahi_total": float(ahi_total),
        "ahi_central": float(ahi_central),
        "ahi_obstructive": float(ahi_obst),
        "ahi_hypopnea": float(ahi_hypo),
    }


def _classify_severity(ahi_total: float) -> Tuple[int, str]:
    if ahi_total < 5.0:
        return 0, "None"
    elif ahi_total < 15.0:
        return 1, "Mild"
    elif ahi_total < 30.0:
        return 2, "Moderate"
    else:
        return 3, "Severe"


# ----------------- 简单信号质量 -----------------

def _estimate_signal_quality(
        env_raw: np.ndarray,
        fs_env: float,
        window_sec: float = 20.0,
) -> Tuple[float, float, float]:
    """
    返回 (quality_index ∈ [0,1], snr_db, coverage)
    """
    if env_raw.size == 0 or fs_env <= 0:
        return 0.0, 0.0, 0.0

    max_len = int(window_sec * fs_env)
    if env_raw.size > max_len:
        env = env_raw[-max_len:].astype(np.float32)
    else:
        env = env_raw.astype(np.float32)

    if env.size < fs_env * 2:
        return 0.0, 0.0, 0.0

    p95 = float(np.percentile(env, 95))
    p5 = float(np.percentile(env, 5))
    dynamic_range = max(1e-6, p95 - p5)

    median = float(np.median(env))
    mad = float(np.median(np.abs(env - median)) + 1e-6)
    noise = 1.4826 * mad

    snr_lin = dynamic_range / max(noise, 1e-6)
    snr_db = 20.0 * math.log10(max(snr_lin, 1e-6))

    threshold = median + 2.0 * noise
    coverage = float(np.mean(np.abs(env - median) > threshold))

    q_snr = (snr_db - 3.0) / 15.0
    q_snr = max(0.0, min(1.0, q_snr))

    q_cov = (coverage - 0.05) / 0.55
    q_cov = max(0.0, min(1.0, q_cov))

    quality_index = 0.7 * q_snr + 0.3 * q_cov
    quality_index = max(0.0, min(1.0, quality_index))

    return float(quality_index), float(snr_db), float(coverage)


# ----------------- 主入口：FMCW 流式处理 -----------------

def process_fmcw_frame_stream(
        stream_id: str,
        rxPcm: Sequence[int],
        txChirp: Sequence[int],
        chirpsPerFrame: int,
        sampleRate: int = 48000,
        save_dir: Optional[str] = None,
):
    """
    Kotlin 调用入口：
      PythonBridge.fmcwProcessFrameStream(streamId, rxPcm, txChirp, chirpsPerFrame, sampleRate, saveDir)

    返回: (features_dict, events_list)
    """
    state = _get_state(stream_id)

    # ---------- 1. 输入整理 ----------
    rx = np.asarray(rxPcm, dtype=np.float32).reshape(-1)
    tx = np.asarray(txChirp, dtype=np.float32).reshape(-1)
    n_chirp = int(chirpsPerFrame)
    n_samp = int(tx.shape[0])
    need = n_chirp * n_samp

    if rx.size < need:
        rx_padded = np.zeros(need, dtype=np.float32)
        rx_padded[:rx.size] = rx
        rx = rx_padded
        print(f"[FMCW] frame too short: pad from {rx.size} to {need}")
    if rx.size > need:
        rx = rx[:need]

    rx = rx.reshape(n_chirp, n_samp)

    # ---------- 2. FMCW 解调 ----------
    nfft = _next_pow2(n_samp)
    TX = np.fft.rfft(tx, n=nfft)
    TX_conj = np.conj(TX)

    beat_mag = np.empty((n_chirp, TX.shape[0]), dtype=np.float32)
    for i in range(n_chirp):
        Ri = np.fft.rfft(rx[i], n=nfft)
        Bi = Ri * TX_conj
        beat_mag[i] = np.abs(Bi).astype(np.float32)

    # ---------- 3. 距离门 ----------
    start_bin = CHEST_START_BIN
    end_bin = min(CHEST_END_BIN_MAX, beat_mag.shape[1])
    gate = beat_mag[:, start_bin:end_bin]   # [n_chirp, gate_bins]

    # ---------- 4. 流式 range-time 累积 ----------
    frame_sec = float(n_chirp * n_samp) / float(sampleRate)
    fs_env = float(n_chirp) / frame_sec if frame_sec > 0 else 1.0

    state["rt_rows"].append(gate)
    max_rows = int(_STREAM_MAX_SEC / frame_sec) if frame_sec > 0 else 1
    if len(state["rt_rows"]) > max_rows:
        state["rt_rows"] = state["rt_rows"][-max_rows:]

    rt_all = np.concatenate(state["rt_rows"], axis=0)  # [T, gate_bins]
    T_chirps, gate_bins = rt_all.shape
    total_duration_sec = float(T_chirps / fs_env) if fs_env > 0 else 0.0

    # ---------- 5. 目标 bin 选择 ----------
    target_bin = state.get("target_bin", None)
    last_target_update = float(state.get("last_target_update_sec", 0.0))
    RESELECT_SEC = 15.0

    if target_bin is None or total_duration_sec - last_target_update >= RESELECT_SEC:
        rel_bin = _select_target_bin(rt_all, fs_env)
        if rel_bin is not None:
            target_bin = rel_bin
            state["target_bin"] = target_bin
            state["last_target_update_sec"] = total_duration_sec

    if target_bin is None or target_bin < 0 or target_bin >= gate_bins:
        env_arr_raw = np.mean(rt_all, axis=1).astype(np.float32)
        effective_bin = None
    else:
        env_arr_raw = rt_all[:, target_bin].astype(np.float32)
        effective_bin = int(start_bin + target_bin)

    # env 历史裁剪并保存
    max_points = int(_STREAM_MAX_SEC * fs_env)
    if env_arr_raw.size > max_points:
        env_arr_raw = env_arr_raw[-max_points:]
    state["env"] = env_arr_raw.tolist()

    total_duration_sec = float(env_arr_raw.size / fs_env) if fs_env > 0 else 0.0

    # ---------- 6. 呼吸频率估计 ----------
    (
        breath_bpm,
        breath_bpm_fft,
        breath_bpm_peak,
        peaks,
        mu_amp,
        sigma_amp,
    ) = _combine_breath_rate(env_arr_raw, fs_env, state)

    # ---------- 7. 滑窗事件检测 ----------
    events_all: List[Dict[str, Any]] = _detect_events_apnea_style_windows(
        env_arr_raw, fs_env, peaks, mu_amp, sigma_amp
    )

    # ---------- 8. 信号质量 ----------
    quality_index, snr_db, coverage = _estimate_signal_quality(env_arr_raw, fs_env)

    # ---------- 9. AHI & 严重程度 ----------
    ahi_stats = _compute_ahi_by_type(events_all, total_duration_sec)
    ahi_total = ahi_stats["ahi_total"]
    ahi_central = ahi_stats["ahi_central"]
    ahi_obst = ahi_stats["ahi_obstructive"]
    ahi_hypo = ahi_stats["ahi_hypopnea"]

    severity_idx, severity_name = _classify_severity(ahi_total)

    # ---------- 10. 决策窗口 & 诊断文案 ----------
    DECISION_WINDOW = 30.0
    recent_events: List[Dict[str, Any]] = []
    cut_t = max(0.0, total_duration_sec - DECISION_WINDOW)
    for ev in events_all:
        if ev["end_sec"] >= cut_t:
            recent_events.append(ev)
    recent_event_count = len(recent_events)

    if total_duration_sec < 60.0:
        diagnosis = "对准胸口，数据采集中（<60 秒，AHI 仅供参考）"
    else:
        if not events_all:
            diagnosis = "当前未检测到明显呼吸暂停/低通气事件"
        elif recent_event_count == 0:
            diagnosis = f"历史存在事件，最近 {int(DECISION_WINDOW)} 秒未见明显异常，AHI≈{ahi_total:.1f} ({severity_name})"
        else:
            diagnosis = f"检测到呼吸暂停/低通气事件，AHI≈{ahi_total:.1f} ({severity_name})"

    # ---------- 11. envelope_plot（最近 60 秒） ----------
    PLOT_SEC = 60.0
    max_plot_points = int(PLOT_SEC * fs_env)
    if env_arr_raw.size > max_plot_points:
        env_plot = env_arr_raw[-max_plot_points:]
    else:
        env_plot = env_arr_raw

    if env_plot.size == 0:
        envelope_plot = np.zeros(0, dtype=np.float32)
    else:
        env_norm = env_plot - env_plot.mean()
        std = env_norm.std()
        if std > 1e-6:
            env_norm /= std
        env_norm = np.clip(env_norm, -5.0, 5.0)
        envelope_plot = env_norm.astype(np.float32)

    # ---------- 12. range-time npz 可选保存 ----------
    rt_npz_saved = None
    if save_dir:
        now = time.time()
        if now - float(state.get("last_save", 0.0)) > 60.0 and rt_all.size > 0:
            state["last_save"] = now
            try:
                os.makedirs(save_dir, exist_ok=True)
                fname = f"rt_{stream_id}_{int(now)}.npz"
                fpath = os.path.join(save_dir, fname)
                np.savez_compressed(
                    fpath,
                    rt=rt_all.astype(np.float32),
                    sample_rate=sampleRate,
                    start_bin=start_bin,
                )
                rt_npz_saved = fpath
            except Exception as e:
                print("[WARN] save rt_npz failed:", e)
                rt_npz_saved = None

    # ---------- 13. 简单质量等级 / usable 标记 ----------
    if quality_index < 0.25:
        quality_grade = 0
        quality_label = "bad"
    elif quality_index < 0.5:
        quality_grade = 1
        quality_label = "ok"
    else:
        quality_grade = 2
        quality_label = "good"

    usable_for_ahi = bool(total_duration_sec >= 60.0 and quality_index >= 0.3)

    # ---------- 14. 打包返回 ----------
    features = {
        "sample_rate": int(fs_env),
        "envelope_plot": envelope_plot,
        "resp_env_plot": envelope_plot,                   # UI 复用
        "posture_env_plot": envelope_plot,                # 暂无姿态，先复用
        "artifact_mask_plot": np.zeros_like(envelope_plot),
        "posture_flag_plot": np.zeros_like(envelope_plot),
        "baseline_rebuild": False,

        "breath_freq": float(breath_bpm),
        "breath_freq_fft": float(breath_bpm_fft),
        "breath_freq_peak": float(breath_bpm_peak),
        "breath_fft_conf": float(state.get("breath_fft_conf", 0.0)),

        "quality_index": float(quality_index),
        "snr_db": float(snr_db),
        "coverage": float(coverage),

        "target_range_bin": int(effective_bin if effective_bin is not None else -1),
        "decision_period_sec": float(DECISION_WINDOW),
        "recent_event_count": int(recent_event_count),
        "diagnosis": diagnosis,

        "ahi_est": float(ahi_total),
        "ahi_total": float(ahi_total),
        "ahi_central": float(ahi_central),
        "ahi_obstructive": float(ahi_obst),
        "ahi_hypopnea": float(ahi_hypo),
        "ahi_valid_sec": float(total_duration_sec),
        "ahi_level_idx": int(severity_idx),
        "ahi_level_name": severity_name,
        "plot_window_sec": float(PLOT_SEC),

        "quality_grade": int(quality_grade),
        "quality_label": quality_label,
        "usable_for_ahi": bool(usable_for_ahi),

        "rt_npz_saved": rt_npz_saved,
        "chest_prior_lo": -1,
        "chest_prior_hi": -1,

        "usable_reason": {
            "quality_grade": int(quality_grade),
            "snr_ok": bool(snr_db >= -5.0),
            "cov_ok": bool(coverage >= 0.003),
            "dur_ok": bool(total_duration_sec >= 60.0),
        },
    }

    print("[PY] process_fmcw_frame_stream(simple) return types:", type(features), type(events_all))
    return features, events_all
