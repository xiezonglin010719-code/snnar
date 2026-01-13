# apnea_detector.py
# 高级版 ApneaDetector：
# - 流式维护 envelope 时间序列
# - 多路呼吸频率估计：FFT + 峰间距
# - 多特征事件检测（能量 drop / 方差 collapse / 频带能量熄灭 / 振幅比例）
# - 信号质量评估（SNR / 覆盖度）-> quality_index ∈ [0,1]
# - 输出最近 DECISION_WINDOW 的事件 + 总事件 + AHI 粗略估计 + diagnosis 文案

from __future__ import annotations
from dataclasses import dataclass, field
from typing import List, Dict, Any, Optional, Tuple

import math
import numpy as np


@dataclass
class ApneaEvent:
    """单个事件结构."""
    type: str           # "central_apnea" / "hypopnea" / "obstructive_apnea"
    start_sec: float
    end_sec: float


@dataclass
class ApneaDetectorConfig:
    # 历史缓存最长时长（秒）
    max_history_sec: float = 900.0

    # 呼吸分析窗口
    breath_fft_window_sec: float = 60.0
    breath_peak_window_sec: float = 60.0

    # 事件判定窗口（短期）
    event_short_sec: float = 12.0

    # baseline 估计所需最短“看起来正常”的时长
    baseline_min_sec: float = 120.0

    # 最小事件持续时间
    min_event_sec: float = 10.0

    # 诊断窗口：最近多少秒的事件用于 diagnosis / recent_event_count
    decision_window_sec: float = 30.0

    # 呼吸频率 band（Hz）
    breath_band_low: float = 0.08  # ~5 bpm
    breath_band_high: float = 0.8  # ~48 bpm

    # 质量评估窗口
    quality_window_sec: float = 20.0

    # 质量阈值
    quality_good_threshold: float = 0.6
    quality_min_threshold: float = 0.3


@dataclass
class ApneaDetectorState:
    # 时间 & envelope 缓存
    env: np.ndarray = field(default_factory=lambda: np.zeros(0, dtype=np.float32))
    t: np.ndarray = field(default_factory=lambda: np.zeros(0, dtype=np.float32))

    # baseline 统计
    baseline_amp_rms: Optional[float] = None
    baseline_var: Optional[float] = None
    baseline_breath_power: Optional[float] = None

    baseline_last_update_sec: float = 0.0

    # 当前事件状态（流式）
    event_active: bool = False
    event_type: Optional[str] = None
    event_start_sec: float = 0.0
    event_last_ok_sec: float = 0.0

    # 历史已完成事件
    events: List[ApneaEvent] = field(default_factory=list)

    # 监测总时长（秒）
    total_duration_sec: float = 0.0


class ApneaDetector:
    """
    高级版 ApneaDetector：
      - 每帧调用 update(frame_env)，会自动将 frame_env 接到历史上；
      - 内部保证 fs_env 一致；
      - 每次 update 返回一个 metrics dict，用于 UI / 上层逻辑。
    """

    def __init__(self, fs_env: float, cfg: Optional[ApneaDetectorConfig] = None):
        if fs_env <= 0:
            raise ValueError("fs_env must be positive")
        self.fs_env = float(fs_env)
        self.cfg = cfg or ApneaDetectorConfig()
        self.state = ApneaDetectorState()
        self.current_time_sec: float = 0.0  # 累计时间轴（秒）
        self.resp_env_t: List[float] = []
        self.resp_env: List[float] = []

    # ----------------- 公共 API ----------------- #

    def update(self, frame_env: np.ndarray) -> Dict[str, Any]:
        """
        流式喂入一帧 envelope（每 chirp 一个点），返回当前各种指标。
        frame_env: 1D np.ndarray, dtype=float32/64, 长度=n_chirps
        """
        frame = np.asarray(frame_env, dtype=np.float32).reshape(-1)
        if frame.size == 0:
            return self._empty_metrics()

        # 1) 更新时间轴 & 历史缓存
        self._append_frame(frame)

        st = self.state  # alias
        cfg = self.cfg

        if st.env.size < self.fs_env * 5:
            # 数据太短，返回初始状态
            return self._metrics_no_confidence()

        # 2) 呼吸频率估计（FFT + 峰间距）
        breath_bpm_fft, breath_freq_peak_hz, fft_conf = self._estimate_breath_fft()
        breath_bpm_peak = self._estimate_breath_peak()

        # 选一个综合 breath_bpm（融合）
        breath_bpm = self._fuse_breath_rate(
            breath_bpm_fft, breath_bpm_peak, fft_conf
        )

        # 3) 信号质量评估
        quality_idx, snr_db, coverage = self._estimate_quality()

        # 4) baseline 更新（只在信号质量不错 & 非 apnea 状态时）
        self._maybe_update_baseline()

        # 5) 事件检测 + AHI 粗估
        self._update_event_state()

        events_all = list(st.events)
        events_recent = self._get_recent_events(cfg.decision_window_sec)

        ahi_total, ahi_central, ahi_obstructive, ahi_hypopnea = \
            self._compute_ahi(events_all)

        # 6) 诊断文案
        diagnosis = self._make_diagnosis(
            quality_idx=quality_idx,
            events_recent=events_recent,
            total_duration_sec=st.total_duration_sec,
            ahi_total=ahi_total
        )

        # 7) 返回最近 PLOT 窗的 envelope（标准化）
        envelope_plot = self._make_envelope_plot(window_sec=30.0)

        metrics = {
            "fs_env": self.fs_env,
            "envelope_plot": envelope_plot,
            # 呼吸
            "breath_bpm": float(breath_bpm),
            "breath_bpm_fft": float(breath_bpm_fft),
            "breath_bpm_peak": float(breath_bpm_peak),
            "breath_fft_conf": float(fft_conf),

            # 质量
            "quality_index": float(quality_idx),
            "snr_db": float(snr_db),
            "coverage": float(coverage),

            # baseline 是否可用
            "baseline_ready": bool(
                st.baseline_amp_rms is not None and
                st.baseline_var is not None and
                st.baseline_breath_power is not None
            ),

            # 事件 / 诊断
            "events": [e.__dict__ for e in events_all],
            "events_recent": [e.__dict__ for e in events_recent],
            "total_duration_sec": float(st.total_duration_sec),
            "ahi_total": float(ahi_total),
            "ahi_central": float(ahi_central),
            "ahi_obstructive": float(ahi_obstructive),
            "ahi_hypopnea": float(ahi_hypopnea),
            "decision_window_sec": float(cfg.decision_window_sec),
            "diagnosis": diagnosis,
        }

        return metrics

    # ----------------- 内部实现：基础维护 ----------------- #

    def _append_frame(self, frame: np.ndarray) -> None:
        st = self.state
        cfg = self.cfg

        n = frame.size
        dt = n / self.fs_env

        # 时间轴：从 current_time_sec 开始，步长 1/fs
        t_start = self.current_time_sec
        t_frame = t_start + np.arange(n, dtype=np.float32) / self.fs_env
        self.current_time_sec += dt
        st.total_duration_sec = self.current_time_sec

        if st.env.size == 0:
            st.env = frame.copy()
            st.t = t_frame
        else:
            st.env = np.concatenate([st.env, frame])
            st.t = np.concatenate([st.t, t_frame])

        # 裁剪历史长度
        cutoff = self.current_time_sec - cfg.max_history_sec
        if cutoff > 0:
            mask = st.t >= cutoff
            st.env = st.env[mask]
            st.t = st.t[mask]

    # ----------------- 呼吸频率估计 ----------------- #

    def _estimate_breath_fft(self) -> Tuple[float, float, float]:
        """在最近 breath_fft_window_sec 内做 FFT 分析."""
        st = self.state
        cfg = self.cfg
        fs = self.fs_env

        dur = st.t[-1] - st.t[0]
        win_sec = min(cfg.breath_fft_window_sec, dur)
        if win_sec < 8.0:  # 至少几秒
            return 0.0, 0.0, 0.0

        t_end = st.t[-1]
        t_start = t_end - win_sec
        mask = st.t >= t_start
        env = st.env[mask].astype(np.float32)
        if env.size < fs * 4:
            return 0.0, 0.0, 0.0

        # 去均值
        x = env - float(env.mean())
        # 简单汉宁窗
        win = np.hanning(x.size).astype(np.float32)
        xw = x * win

        # 零填充提高频率分辨率
        nfft = int(2 ** math.ceil(math.log2(xw.size * 2)))
        spec = np.fft.rfft(xw, n=nfft)
        freqs = np.fft.rfftfreq(nfft, d=1.0 / fs)
        power = (np.abs(spec) ** 2).astype(np.float32)

        # 限制在呼吸频率 band 内
        band_mask = (freqs >= cfg.breath_band_low) & (freqs <= cfg.breath_band_high)
        if not np.any(band_mask):
            return 0.0, 0.0, 0.0

        band_freqs = freqs[band_mask]
        band_power = power[band_mask]

        if band_power.max() <= 0:
            return 0.0, 0.0, 0.0

        peak_idx = int(band_power.argmax())
        peak_freq = float(band_freqs[peak_idx])
        breath_bpm_fft = 60.0 * peak_freq

        # 置信度：峰值功率 / band 平均功率
        mean_p = float(band_power.mean() + 1e-9)
        conf = float(band_power[peak_idx] / mean_p)

        return breath_bpm_fft, peak_freq, conf

    def _estimate_breath_peak(self) -> float:
        """在最近 breath_peak_window_sec 内通过 envelope 峰间距估计呼吸频率."""
        st = self.state
        cfg = self.cfg
        fs = self.fs_env

        dur = st.t[-1] - st.t[0]
        win_sec = min(cfg.breath_peak_window_sec, dur)
        if win_sec < 8.0:
            return 0.0

        t_end = st.t[-1]
        t_start = t_end - win_sec
        mask = st.t >= t_start
        env = st.env[mask].astype(np.float32)
        t = st.t[mask].astype(np.float32)

        # 轻微平滑
        env = self._moving_average(env, win_sec=0.5)

        # 简单峰检测：局部极大 + 最小间隔 2s
        min_dist_sec = 2.0
        min_dist_samp = int(max(1, round(min_dist_sec * fs)))

        N = env.size
        if N < min_dist_samp * 3:
            return 0.0

        cand_idx = []
        for i in range(1, N - 1):
            if env[i] >= env[i - 1] and env[i] >= env[i + 1]:
                cand_idx.append(i)
        if not cand_idx:
            return 0.0

        peaks = []
        last_keep = -10 * min_dist_samp
        for i in cand_idx:
            if i - last_keep < min_dist_samp:
                if peaks and env[i] > peaks[-1][1]:
                    peaks[-1] = (i, float(env[i]), float(t[i]))
                    last_keep = i
                continue
            peaks.append((i, float(env[i]), float(t[i])))
            last_keep = i

        if len(peaks) < 2:
            return 0.0

        times = np.array([p[2] for p in peaks], dtype=np.float32)
        intervals = np.diff(times)
        # 合理的呼吸周期 2~20s
        mask_int = (intervals >= 2.0) & (intervals <= 20.0)
        if not np.any(mask_int):
            return 0.0

        T = float(np.median(intervals[mask_int]))
        if T <= 0:
            return 0.0
        return float(60.0 / T)

    def _fuse_breath_rate(self,
                          bpm_fft: float,
                          bpm_peak: float,
                          fft_conf: float) -> float:
        """简单融合 FFT 与峰间距呼吸频率."""
        # FFT 优先，当 conf 足够高
        if bpm_fft <= 0 and bpm_peak <= 0:
            return 0.0
        if bpm_fft <= 0:
            return bpm_peak
        if bpm_peak <= 0:
            return bpm_fft

        # 两者都有效时，用 conf 调权
        # conf>5 时几乎相信 FFT；接近 1 时倾向平均
        alpha = max(0.0, min(1.0, (fft_conf - 1.0) / 4.0))
        bpm = alpha * bpm_fft + (1 - alpha) * bpm_peak
        # 再简单地把超出合理范围的砍掉
        if bpm < 4.0 or bpm > 40.0:
            return bpm_fft if 4.0 <= bpm_fft <= 40.0 else bpm_peak
        return bpm

    # ----------------- 质量评估 ----------------- #

    def _estimate_quality(self) -> Tuple[float, float, float]:
        """
        评估信号质量，返回：
          - quality_index ∈ [0,1]
          - snr_db
          - coverage ∈ [0,1]
        """
        st = self.state
        cfg = self.cfg
        fs = self.fs_env

        dur = st.t[-1] - st.t[0]
        win_sec = min(cfg.quality_window_sec, dur)
        if win_sec < 4.0:
            return 0.0, 0.0, 0.0

        t_end = st.t[-1]
        t_start = t_end - win_sec
        mask = st.t >= t_start
        env = st.env[mask].astype(np.float32)

        if env.size < fs * 2:
            return 0.0, 0.0, 0.0

        # 1) 动态范围 + 噪声估计
        p95 = float(np.percentile(env, 95))
        p5 = float(np.percentile(env, 5))
        dynamic_range = max(1e-6, p95 - p5)

        median = float(np.median(env))
        mad = float(np.median(np.abs(env - median)) + 1e-6)
        noise = 1.4826 * mad  # ≈ σ

        snr_lin = dynamic_range / max(noise, 1e-6)
        snr_db = 20.0 * math.log10(max(snr_lin, 1e-6))

        # 2) 覆盖度：有显著波动的样本比例
        threshold = median + 2.0 * noise
        coverage = float(np.mean(np.abs(env - median) > threshold))

        # 3) 综合 quality index
        #   q_snr: snr_db: 0~18 dB → 0~1
        q_snr = (snr_db - 3.0) / 15.0
        q_snr = max(0.0, min(1.0, q_snr))

        #   q_cov: coverage 0.05~0.6 → 0~1
        q_cov = (coverage - 0.05) / 0.55
        q_cov = max(0.0, min(1.0, q_cov))

        quality = 0.7 * q_snr + 0.3 * q_cov
        quality = max(0.0, min(1.0, quality))

        return quality, snr_db, coverage

    # ----------------- baseline 更新 ----------------- #

    def _maybe_update_baseline(self) -> None:
        st = self.state
        cfg = self.cfg

        dur = st.t[-1] - st.t[0]
        if dur < cfg.baseline_min_sec:
            return

        # 最近 baseline_min_sec 的数据
        t_end = st.t[-1]
        t_start = t_end - cfg.baseline_min_sec
        mask = st.t >= t_start
        env = st.env[mask].astype(np.float32)

        if env.size < self.fs_env * 10:
            return

        # 简单认为当前没有 event_active 时可以用于 baseline 更新
        if st.event_active:
            return

        rms = float(math.sqrt(float(np.mean(env ** 2)) + 1e-8))
        var = float(np.var(env) + 1e-8)

        # 呼吸频带功率
        bpower = self._compute_breath_band_power(env)

        # EMA 更新
        alpha = 0.05
        if st.baseline_amp_rms is None:
            st.baseline_amp_rms = rms
            st.baseline_var = var
            st.baseline_breath_power = bpower
        else:
            st.baseline_amp_rms = (1 - alpha) * st.baseline_amp_rms + alpha * rms
            st.baseline_var = (1 - alpha) * st.baseline_var + alpha * var
            st.baseline_breath_power = (
                    (1 - alpha) * st.baseline_breath_power + alpha * bpower
            )
        st.baseline_last_update_sec = t_end

    def _compute_breath_band_power(self, env: np.ndarray) -> float:
        fs = self.fs_env
        cfg = self.cfg

        x = env.astype(np.float32)
        x = x - float(x.mean())
        if x.size < fs * 4:
            return 0.0

        nfft = int(2 ** math.ceil(math.log2(x.size)))
        spec = np.fft.rfft(x, n=nfft)
        freqs = np.fft.rfftfreq(nfft, d=1.0 / fs)
        power = (np.abs(spec) ** 2).astype(np.float32)

        mask = (freqs >= cfg.breath_band_low) & (freqs <= cfg.breath_band_high)
        if not np.any(mask):
            return 0.0
        return float(power[mask].mean())

    # ----------------- 事件检测核心 ----------------- #

    def _update_event_state(self) -> None:
        """根据当前窗口的统计量更新 event_active 状态，并记录事件."""
        st = self.state
        cfg = self.cfg
        fs = self.fs_env

        if st.baseline_amp_rms is None or st.baseline_var is None or st.baseline_breath_power is None:
            # baseline 还没 ready，不做事件检测
            return

        dur = st.t[-1] - st.t[0]
        win_sec = min(cfg.event_short_sec, dur)
        if win_sec < 8.0:
            return

        t_end = st.t[-1]
        t_start = t_end - win_sec
        mask = st.t >= t_start
        env = st.env[mask].astype(np.float32)

        if env.size < fs * 4:
            return

        # 当前窗口统计量
        rms_short = float(math.sqrt(float(np.mean(env ** 2)) + 1e-8))
        var_short = float(np.var(env) + 1e-8)
        bpower_short = self._compute_breath_band_power(env)

        # 相对 baseline 的比例
        amp_ratio = rms_short / max(st.baseline_amp_rms, 1e-6)
        var_ratio = var_short / max(st.baseline_var, 1e-6)
        bpower_ratio = bpower_short / max(st.baseline_breath_power, 1e-6)

        # —— 规则 —— #
        # central_apnea 候选：
        #   - 振幅大幅下降：amp_ratio < 0.2
        #   - 方差 collapse: var_ratio < 0.2
        #   - 呼吸带功率显著下降：bpower_ratio < 0.2
        is_central = (
                amp_ratio < 0.2 and
                var_ratio < 0.2 and
                bpower_ratio < 0.2
        )

        # hypopnea 候选：
        #   - 振幅下降 30%~70%
        #   - 呼吸带功率下降明显但未完全消失
        is_hypopnea = (
                0.2 <= amp_ratio < 0.7 and
                0.2 <= bpower_ratio < 0.7
        )

        # obstructive_apnea 候选（简化版）：
        #   - 振幅下降，但方差没有完全 collapse（仍有明显起伏）
        #   - 呼吸 band 功率下降较多
        is_obstructive = (
                amp_ratio < 0.5 and
                bpower_ratio < 0.4 and
                var_ratio >= 0.2
        )

        # 选当前最“严重”的类型
        new_type: Optional[str] = None
        if is_central:
            new_type = "central_apnea"
        elif is_obstructive:
            new_type = "obstructive_apnea"
        elif is_hypopnea:
            new_type = "hypopnea"

        self._update_event_fsm(new_type=new_type,
                               window_start=t_start,
                               window_end=t_end)

    def _update_event_fsm(self,
                          new_type: Optional[str],
                          window_start: float,
                          window_end: float) -> None:
        """简单 FSM：根据 new_type 更新 event_active 状态."""
        st = self.state
        cfg = self.cfg

        now = window_end
        if not st.event_active:
            if new_type is not None:
                # 启动新事件，从当前窗口开始
                st.event_active = True
                st.event_type = new_type
                st.event_start_sec = window_start
                st.event_last_ok_sec = now
        else:
            # 已有事件在进行
            if new_type == st.event_type and new_type is not None:
                st.event_last_ok_sec = now
            elif new_type is None:
                # 条件暂时消失，观察 2 秒缓冲
                if now - st.event_last_ok_sec > 2.0:
                    # 判定事件结束
                    dur = st.event_last_ok_sec - st.event_start_sec
                    if dur >= cfg.min_event_sec:
                        st.events.append(ApneaEvent(
                            type=st.event_type or "unknown",
                            start_sec=st.event_start_sec,
                            end_sec=st.event_last_ok_sec
                        ))
                    # 重置
                    st.event_active = False
                    st.event_type = None
                    st.event_start_sec = 0.0
                    st.event_last_ok_sec = 0.0
            else:
                # 类型切换（例如 hypopnea → central），先结束旧事件
                dur = st.event_last_ok_sec - st.event_start_sec
                if dur >= cfg.min_event_sec:
                    st.events.append(ApneaEvent(
                        type=st.event_type or "unknown",
                        start_sec=st.event_start_sec,
                        end_sec=st.event_last_ok_sec
                    ))
                # 开启新事件
                st.event_active = True
                st.event_type = new_type
                st.event_start_sec = window_start
                st.event_last_ok_sec = now

    # ----------------- AHI & diagnosis ----------------- #

    def _get_recent_events(self, window_sec: float) -> List[ApneaEvent]:
        st = self.state
        if not st.events:
            return []
        cutoff = self.current_time_sec - window_sec
        return [e for e in st.events if e.end_sec >= cutoff]

    def _compute_ahi(self, events: List[ApneaEvent]) -> Tuple[float, float, float, float]:
        st = self.state
        hours = st.total_duration_sec / 3600.0
        if hours <= 0:
            return 0.0, 0.0, 0.0, 0.0

        total = len(events)
        n_central = sum(1 for e in events if e.type == "central_apnea")
        n_ob = sum(1 for e in events if e.type == "obstructive_apnea")
        n_hypo = sum(1 for e in events if e.type == "hypopnea")

        ahi_total = total / hours
        ahi_central = n_central / hours
        ahi_ob = n_ob / hours
        ahi_hypo = n_hypo / hours

        return ahi_total, ahi_central, ahi_ob, ahi_hypo

    def _make_diagnosis(self,
                        quality_idx: float,
                        events_recent: List[ApneaEvent],
                        total_duration_sec: float,
                        ahi_total: float) -> str:
        cfg = self.cfg

        if quality_idx < cfg.quality_min_threshold:
            return "信号质量过低，当前时段无法可靠判断，请调整手机位置或环境"

        if total_duration_sec < 60.0:
            return "数据采集中（<60 秒，结果不稳定）"

        n_recent = len(events_recent)

        if ahi_total < 5 and n_recent == 0:
            return "当前未见明显呼吸暂停/低通气事件"

        # 轻度提示（少量事件）
        if n_recent > 0 and n_recent < 3:
            return f"轻度可疑：最近 {int(cfg.decision_window_sec)} 秒内检测到 {n_recent} 次可疑呼吸暂停/低通气事件"

        if n_recent >= 3:
            return f"明显可疑 OSA：最近 {int(cfg.decision_window_sec)} 秒内检测到 {n_recent} 次可疑事件"

        # fallback
        return "监测中，已记录到零星可疑事件，请持续监测更长时间"

    # ----------------- Envelope plot ----------------- #

    def _make_envelope_plot(self, window_sec: float) -> np.ndarray:
        st = self.state
        fs = self.fs_env

        if st.env.size < fs * 1:
            return np.zeros(0, dtype=np.float32)

        t_end = st.t[-1]
        t_start = max(st.t[0], t_end - window_sec)
        mask = st.t >= t_start
        env = st.env[mask].astype(np.float32)
        if env.size == 0:
            return np.zeros(0, dtype=np.float32)

        env = env - float(env.mean())
        std = float(env.std() + 1e-6)
        env_norm = env / std
        return env_norm.astype(np.float32)

    # ----------------- 工具函数 ----------------- #

    def _moving_average(self, x: np.ndarray, win_sec: float) -> np.ndarray:
        if x.size == 0:
            return x
        k = int(max(1, round(win_sec * self.fs_env)))
        if k <= 1:
            return x
        kernel = np.ones(k, dtype=np.float32) / float(k)
        y = np.convolve(x.astype(np.float32), kernel, mode="same")
        return y.astype(np.float32)

    def _empty_metrics(self) -> Dict[str, Any]:
        return {
            "fs_env": self.fs_env,
            "envelope_plot": np.zeros(0, dtype=np.float32),
            "breath_bpm": 0.0,
            "breath_bpm_fft": 0.0,
            "breath_bpm_peak": 0.0,
            "breath_fft_conf": 0.0,
            "quality_index": 0.0,
            "snr_db": 0.0,
            "coverage": 0.0,
            "baseline_ready": False,
            "events": [],
            "events_recent": [],
            "total_duration_sec": 0.0,
            "ahi_total": 0.0,
            "ahi_central": 0.0,
            "ahi_obstructive": 0.0,
            "ahi_hypopnea": 0.0,
            "decision_window_sec": float(self.cfg.decision_window_sec),
            "diagnosis": "等待数据…",
        }

    def _metrics_no_confidence(self) -> Dict[str, Any]:
        m = self._empty_metrics()
        m["total_duration_sec"] = float(self.state.total_duration_sec)
        m["diagnosis"] = "数据采集中（时长不足，正在建立基线）"
        return m
