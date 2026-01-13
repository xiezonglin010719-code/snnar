# apnea_quality_module.py
# -*- coding: utf-8 -*-
"""
ApneaApp 风格的信号质量模块（Python版）

核心输出:
  - quality_index: [0, 1] 信号质量评分
  - has_valid_breath: bool 当前是否有可用呼吸信号
  - allow_events: bool 是否允许做事件检测和 AHI 估计
  - breath_bpm: 呼吸频率 (bpm)
  - breath_conf: 呼吸频率的 FFT 置信度 [0, 1]
  - roi_metrics: ROI / tracking / presence 的调试信息

使用方式:
  1) 在主流程中，每帧调用 QualityEngine.process_frame(...)
  2) 得到 features（一个 dict），塞进你原来的 features map 返回给 Kotlin
"""

from __future__ import annotations
import math
from collections import defaultdict, deque
from dataclasses import dataclass, asdict
from typing import Dict, Any, Tuple, List, Optional

import numpy as np


# ===================== 工具函数 =====================

def _next_pow2(n: int) -> int:
    k = 1
    while k < n:
        k <<= 1
    return k


def _simple_find_peaks(signal: np.ndarray, min_distance_sec: float, fs: float) -> List[int]:
    """
    极简峰值检测：比 scipy.find_peaks 弱很多，但不需要依赖。
    """
    n = len(signal)
    if n < 3:
        return []
    min_d = int(round(min_distance_sec * fs))
    candidates = []
    for i in range(1, n - 1):
        if signal[i] >= signal[i - 1] and signal[i] >= signal[i + 1]:
            candidates.append(i)
    peaks = []
    last = -10 ** 9
    for idx in candidates:
        if idx - last >= min_d:
            peaks.append(idx)
            last = idx
    return peaks


# ===================== 数据类 =====================

@dataclass
class RoiMetrics:
    presence_score: float = 0.0
    resp_snr_db: float = 0.0
    breath_freq_hz: float = 0.0
    stability: float = 0.0          # phase 稳定度 [0,1]
    roi_ok: bool = False
    bin_jitter: float = 1e9         # chest bin 抖动（越小越好）
    target_bin: int = 0
    person_present: bool = False

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)


@dataclass
class CycleStats:
    n_cycles: int = 0
    mean_T: float = 0.0
    std_T: float = 0.0
    cv_T: float = 1.0               # 变异系数，越小越稳定

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)


@dataclass
class QualityResult:
    quality_index: float
    has_valid_breath: bool
    allow_events: bool
    track_score: float
    cycle_score: float
    valid_freq: bool
    breath_bpm: float
    breath_conf: float
    roi: RoiMetrics
    cycle: CycleStats

    def to_features(self) -> Dict[str, Any]:
        """
        转成可以直接塞进 Android 端的 features map。
        你可以统一用这些 key：quality_index, breath_freq_peak, breath_fft_conf 等。
        """
        return {
            "quality_index": float(self.quality_index),
            "breath_freq_peak": float(self.breath_bpm),
            "breath_fft_conf": float(self.breath_conf),
            "breath_freq": float(self.breath_bpm / 60.0),  # Hz
            "quality_track_score": float(self.track_score),
            "quality_cycle_score": float(self.cycle_score),
            "quality_valid_freq": bool(self.valid_freq),
            "person_present": bool(self.roi.person_present),
            "target_range_bin": int(self.roi.target_bin),
            "presence_score": float(self.roi.presence_score),
            "resp_snr_db": float(self.roi.resp_snr_db),
        }


# ===================== 核心引擎 =====================

class QualityEngine:
    """
    针对每个 stream_id 维护状态，模仿 ApneaApp 的质量估计逻辑。
    """

    def __init__(self,
                 fs_breath: float = 10.0,
                 max_hist_sec: float = 40.0,
                 max_breath_sec: float = 10 * 60.0):
        """
        fs_breath: 呼吸 buffer 的降采样频率（默认约 10 Hz）
        max_hist_sec: ROI 分析用的最长 history 窗口时长
        max_breath_sec: 呼吸 buffer 最长长度
        """
        self.fs_breath = float(fs_breath)
        self.max_hist_sec = float(max_hist_sec)
        self.max_breath_sec = float(max_breath_sec)

        # 每个 stream_id 一个 dict state
        self._states: Dict[str, Dict[str, Any]] = defaultdict(dict)

    # ---------- 外部主入口 ----------

    def process_frame(self,
                      stream_id: str,
                      rt_complex_sm: np.ndarray,
                      sample_rate: int,
                      tx_chirp: np.ndarray,
                      frame_dt: float) -> QualityResult:
        """
        外部调用主函数：
          - 更新 range-time 历史（用于 ROI）
          - 根据 ROI 提取胸腔信号到 breath buffer
          - 从 breath buffer 估计呼吸频率和周期稳定性
          - 根据 ROI + 呼吸特征做质量评估

        返回 QualityResult，可转成 features map。
        """
        st = self._get_state(stream_id)

        # 1) range-time -> hist
        self._update_rt_hist(st, rt_complex_sm, frame_dt)

        # 2) ROI + presence
        person_present, target_bin, roi_metrics = self._presence_and_phase_roi(st)

        # 3) chest 一维信号写入呼吸 buffer
        if person_present and rt_complex_sm.size > 0:
            # 简化：取该 range bin 的实部作为“位移”，你可以改成相位等
            chest = np.real(rt_complex_sm[:, target_bin]).astype(np.float32)
            self._append_breath_buffer(st, chest, sample_rate, tx_chirp)

        # 4) 从 breath buffer 估计呼吸频率 + 周期稳定度
        breath = np.asarray(st["breath_buffer"], dtype=np.float32)
        breath_bpm, conf_breath, cycle_stats = self._estimate_breath_and_cycle(
            breath, st["fs_breath"]
        )

        # 5) Apnea 风格质量评估
        quality_index, has_valid, allow_events, q_dbg = self._quality_apnea_style(
            st, roi_metrics, breath_bpm, conf_breath, cycle_stats
        )

        res = QualityResult(
            quality_index=quality_index,
            has_valid_breath=has_valid,
            allow_events=allow_events,
            track_score=q_dbg["track_score"],
            cycle_score=q_dbg["cycle_score"],
            valid_freq=q_dbg["valid_freq"],
            breath_bpm=breath_bpm,
            breath_conf=conf_breath,
            roi=roi_metrics,
            cycle=cycle_stats,
        )
        return res

    # ---------- 状态管理 ----------

    def _get_state(self, stream_id: str) -> Dict[str, Any]:
        st = self._states[stream_id]
        if not st:
            st.update(
                inited=True,
                # range-time 历史
                rt_hist=None,
                hist_dt=None,
                max_hist_steps=256,
                # ROI / presence
                presence_hist=deque(maxlen=120),
                person_present=False,
                target_bin_phase=None,
                bin_hist_phase=deque(maxlen=10),
                # 呼吸 buffer
                fs_breath=self.fs_breath,
                breath_buffer=deque(maxlen=int(self.fs_breath * self.max_breath_sec)),
                time_buffer=deque(maxlen=int(self.fs_breath * self.max_breath_sec)),
                last_time=0.0,
                # 用于统计 tracking / cycle
                track_jitter_hist=deque(maxlen=60),
                cycle_cv_hist=deque(maxlen=60),
            )
        return st

    # ---------- 1) range-time 历史 ----------

    def _update_rt_hist(self,
                        state: Dict[str, Any],
                        rt_complex_sm: np.ndarray,
                        frame_dt: float):
        """
        把当前帧的 range-time 压成一行，对 chirp 求平均，拼成长时矩阵。
        """
        if rt_complex_sm.size == 0 or frame_dt <= 0:
            return

        row = rt_complex_sm.mean(axis=0).astype(np.complex64)
        n_bins = row.shape[0]

        hist = state.get("rt_hist", None)
        if hist is None or hist.shape[1] != n_bins:
            hist = row[None, :]
        else:
            hist = np.concatenate([hist, row[None, :]], axis=0)

        # 基于时间和上限共同裁剪
        max_hist_steps_cfg = state.get("max_hist_steps", 256)
        max_hist_steps_time = int(round(self.max_hist_sec / frame_dt)) if frame_dt > 0 else 256
        max_hist_steps = int(min(max_hist_steps_cfg, max_hist_steps_time, 400))
        max_hist_steps = max(32, max_hist_steps)

        if hist.shape[0] > max_hist_steps:
            hist = hist[-max_hist_steps:, :]

        state["rt_hist"] = hist
        state["hist_dt"] = float(frame_dt)
        state["max_hist_steps"] = max_hist_steps

    # ---------- 2) ROI + presence ----------

    def _presence_and_phase_roi(self, state: Dict[str, Any]) -> Tuple[bool, int, RoiMetrics]:
        hist = state.get("rt_hist", None)
        dt = state.get("hist_dt", None)

        metrics = RoiMetrics()

        if hist is None or dt is None or dt <= 0 or hist.shape[0] < 16:
            prev_bin = state.get("target_bin_phase")
            if prev_bin is None:
                prev_bin = 0
            metrics.target_bin = int(prev_bin)
            metrics.person_present = False
            return False, int(prev_bin), metrics

        T, n_bins = hist.shape
        # 要求至少约 15 秒窗口
        min_win_sec = 15.0
        win_steps = min(T, int(round(min_win_sec / dt)))
        win_steps = max(16, win_steps)
        if T < win_steps:
            prev_bin = state.get("target_bin_phase")
            if prev_bin is None:
                prev_bin = 0
            metrics.target_bin = int(prev_bin)
            metrics.person_present = False
            return False, int(prev_bin), metrics

        hist_win = hist[-win_steps:, :]
        nfft = _next_pow2(win_steps)
        freqs = np.fft.rfftfreq(nfft, d=dt)
        spec_all = np.fft.rfft(hist_win, n=nfft, axis=0)
        power_all = (np.abs(spec_all) ** 2).astype(np.float64)

        # 呼吸频带 0.1~0.5 Hz
        f_low, f_high = 0.10, 0.50
        mask_resp = (freqs >= f_low) & (freqs <= f_high)
        if not np.any(mask_resp):
            prev_bin = state.get("target_bin_phase")
            if prev_bin is None:
                prev_bin = 0
            metrics.target_bin = int(prev_bin)
            metrics.person_present = False
            return False, int(prev_bin), metrics

        P_resp = power_all[mask_resp, :].sum(axis=0)
        P_tot = power_all.sum(axis=0) + 1e-9
        R_resp = P_resp / P_tot
        score = P_resp * R_resp

        top_idx = int(np.argmax(score))
        top_val = float(score[top_idx])
        med_val = float(np.median(score))
        if top_val <= 0:
            prev_bin = state.get("target_bin_phase")
            if prev_bin is None:
                prev_bin = 0
            metrics.target_bin = int(prev_bin)
            metrics.person_present = False
            return False, int(prev_bin), metrics

        presence_score = top_val / (med_val + 1e-9)
        person_present = presence_score >= 4.0

        roi_ok = False
        target_bin = top_idx
        stability = 0.0
        resp_snr_db = 0.0
        breath_freq_hz = 0.0

        if person_present:
            # 邻域检查，防止孤立噪声点
            lo = max(0, top_idx - 2)
            hi = min(n_bins, top_idx + 3)
            neigh_scores = score[lo:hi]
            neigh_ratios = R_resp[lo:hi]
            good_score_cnt = int((neigh_scores >= (0.3 * top_val)).sum())
            good_ratio_cnt = int((neigh_ratios >= 0.15).sum())
            roi_ok = (good_score_cnt >= 2) and (good_ratio_cnt >= 2)

            # 相位残差稳定度
            spec_bin = spec_all[:, top_idx]
            power_bin = np.abs(spec_bin) ** 2
            power_resp = power_bin[mask_resp]
            freqs_resp = freqs[mask_resp]
            if power_resp.size > 0:
                peak_idx = int(np.argmax(power_resp))
                f_peak = float(freqs_resp[peak_idx])
                P_peak = float(power_resp[peak_idx])
                P_noise = float(power_bin.sum() - P_peak)
                resp_snr_db = 10.0 * math.log10(P_peak / (P_noise + 1e-9) + 1e-9)

                x_bin = hist_win[:, top_idx]
                phi = np.unwrap(np.angle(x_bin))
                t_idx = np.arange(phi.size, dtype=np.float64)
                A = np.vstack([t_idx, np.ones_like(t_idx)]).T
                try:
                    w, _, _, _ = np.linalg.lstsq(A, phi, rcond=None)
                    trend = (A @ w)
                    resid = phi - trend
                except Exception:
                    resid = phi - phi.mean()
                std_resid = float(np.std(resid))
                stability = math.exp(-std_resid / 0.6)
                breath_freq_hz = f_peak

        metrics.presence_score = float(presence_score)
        metrics.resp_snr_db = float(resp_snr_db)
        metrics.breath_freq_hz = float(breath_freq_hz)
        metrics.stability = float(stability)
        metrics.roi_ok = bool(roi_ok)

        # 平滑 target_bin
        prev_bin = state.get("target_bin_phase")
        if prev_bin is None:
            prev_bin = target_bin
        else:
            if roi_ok:
                if abs(target_bin - prev_bin) <= 3:
                    prev_bin = target_bin
                else:
                    bin_hist: deque = state.setdefault("bin_hist_phase", deque(maxlen=10))
                    bin_hist.append(target_bin)
                    vals = np.array(bin_hist)
                    uniq, counts = np.unique(vals, return_counts=True)
                    stable_candidate = int(uniq[np.argmax(counts)])
                    if abs(stable_candidate - prev_bin) <= 3:
                        prev_bin = stable_candidate

        state["target_bin_phase"] = int(prev_bin)
        state["person_present"] = bool(person_present)
        state["presence_hist"].append(float(presence_score))

        # 记录 tracking jitter
        bin_hist = state.setdefault("bin_hist_phase", deque(maxlen=10))
        bin_hist.append(int(prev_bin))
        if len(bin_hist) >= 3:
            jitter = float(np.std(np.array(bin_hist, dtype=np.float32)))
        else:
            jitter = 1e9
        metrics.bin_jitter = float(jitter)
        metrics.target_bin = int(prev_bin)
        metrics.person_present = bool(person_present)
        state["track_jitter_hist"].append(float(jitter if jitter < 1e8 else 0.0))

        return bool(person_present), int(prev_bin), metrics

    # ---------- 3) 呼吸 buffer ----------

    def _append_breath_buffer(self,
                              state: Dict[str, Any],
                              chest: np.ndarray,
                              sample_rate: int,
                              tx_chirp: np.ndarray):
        if chest.size == 0:
            return

        # 估算 chest_fs（每 chirp 一个值）
        ntx = len(tx_chirp)
        if ntx <= 0 or sample_rate <= 0:
            chest_fs = float(len(chest))
        else:
            T_chirp = ntx / float(sample_rate)
            chest_fs = 1.0 / max(T_chirp, 1e-6)

        target_fs = float(state["fs_breath"])
        decim = max(1, int(round(chest_fs / target_fs)))
        breath = chest[::decim].astype(np.float32)

        step = decim / chest_fs
        t0 = float(state["last_time"])
        for i, v in enumerate(breath):
            t = t0 + i * step
            state["breath_buffer"].append(float(v))
            state["time_buffer"].append(float(t))
        state["last_time"] = t0 + len(breath) * step

    # ---------- 4) 呼吸频率 + 周期稳定性 ----------

    def _estimate_breath_and_cycle(self,
                                   breath: np.ndarray,
                                   fs_breath: float) -> Tuple[float, float, CycleStats]:
        if breath.size < int(fs_breath * 10):
            return 0.0, 0.0, CycleStats(n_cycles=0, mean_T=0.0, std_T=0.0, cv_T=1.0)

        max_len = int(self.max_breath_sec * fs_breath)
        if breath.size > max_len:
            b_seg = breath[-max_len:]
        else:
            b_seg = breath

        b0 = b_seg - float(np.mean(b_seg))
        nfft = _next_pow2(len(b0))
        spec = np.fft.rfft(b0, nfft)
        freqs = np.fft.rfftfreq(nfft, d=1.0 / fs_breath)

        # 频域估计主频 + 置信度
        mask = (freqs >= 0.05) & (freqs <= 0.7)
        mag = np.abs(spec)[mask]
        f_sel = freqs[mask]

        if mag.size > 3:
            idx = int(np.argmax(mag))
            main_freq_hz = float(f_sel[idx])
            peak = float(mag[idx])
            noise = float(np.median(np.delete(mag, idx)) + 1e-6)
            snr_lin = max(0.0, (peak - noise) / noise)
            conf = snr_lin / (snr_lin + 3.0)
        elif mag.size > 0:
            main_freq_hz = float(f_sel[int(np.argmax(mag))])
            conf = 0.2
        else:
            main_freq_hz = 0.0
            conf = 0.0

        breath_bpm = main_freq_hz * 60.0

        # 时域周期稳定性
        peaks_idx = _simple_find_peaks(b0, min_distance_sec=2.0, fs=fs_breath)
        if len(peaks_idx) >= 3:
            times = np.array(peaks_idx, dtype=np.float32) / float(fs_breath)
            intervals = np.diff(times)
            mask_int = (intervals >= 1.0) & (intervals <= 20.0)
            intervals = intervals[mask_int]
        else:
            intervals = np.array([], dtype=np.float32)

        if intervals.size >= 3:
            mean_T = float(np.mean(intervals))
            std_T = float(np.std(intervals))
            cv_T = float(std_T / (mean_T + 1e-6))
            n_cycles = int(intervals.size)
        else:
            mean_T = 0.0
            std_T = 0.0
            cv_T = 1.0
            n_cycles = int(intervals.size)

        stats = CycleStats(
            n_cycles=n_cycles,
            mean_T=mean_T,
            std_T=std_T,
            cv_T=cv_T,
        )
        return float(breath_bpm), float(conf), stats

    # ---------- 5) 质量评估（Apnea 风格） ----------

    def _quality_apnea_style(self,
                             state: Dict[str, Any],
                             roi_metrics: RoiMetrics,
                             breath_bpm: float,
                             conf_breath: float,
                             cycle_stats: CycleStats):
        # 1) tracking score: bin_jitter 越小越好
        jitter = float(roi_metrics.bin_jitter)
        if jitter > 1e8:
            track_score = 0.0
        else:
            # jitter 0~1 -> score≈1, jitter≥3 -> score≈0
            track_score = float(np.clip(1.0 - jitter / 3.0, 0.0, 1.0))

        # 2) cycle score: cv_T 越小越好
        cv_T = float(cycle_stats.cv_T)
        n_cycles = int(cycle_stats.n_cycles)
        if n_cycles < 3:
            cycle_score = 0.0
        else:
            # cv 0.1 -> 1.0, cv 0.6 -> 0.0
            cycle_score = float(np.clip(1.0 - (cv_T - 0.1) / 0.5, 0.0, 1.0))

        # 3) 呼吸频率合理性
        valid_freq = 5.0 <= breath_bpm <= 30.0

        # 4) 汇总质量评分
        quality_index = 0.7 * track_score + 0.3 * cycle_score
        quality_index = float(np.clip(quality_index, 0.0, 1.0))

        # 5) gating 逻辑
        has_valid = track_score >= 0.3 and conf_breath >= 0.15 and valid_freq
        allow_events = has_valid and track_score >= 0.4 and cycle_score >= 0.3

        state["cycle_cv_hist"].append(float(cv_T))

        return quality_index, has_valid, allow_events, dict(
            track_score=track_score,
            cycle_score=cycle_score,
            valid_freq=valid_freq,
        )
