

"""
signal_processor.py  (ApneaApp-aligned ROI locker)

FMCW 声纳 OSA 诊断（ApneaApp 对齐版）
- v2 性能修正版 + Hypopnea 必修
- ✅ 修复 call_cost 越跑越大：rt_rows/rt_cx_rows 改 ring buffer，只取最近窗口
- ✅ 补齐方案 4 / 2 / 1（融合 / phase-only / amp-only）
- ✅ 新增：ApneaApp 对齐胸腔 ROI 自动锁定/重锁机制（SEARCH→CANDIDATE→LOCK→HOLD→RELOCK）
- ✅ 新增：Posture/Turn gating（避免对准空气误判翻身）

==========================
[论文创新点落地]
1) Quality Stratification（质量分层而非 hard gate）
   - 将 quality_index 转为连续权重 quality_weight w(t)∈[0,1]
   - 用 TST_eff = Σ w(t)*dt 替代二值可用时长

2) AHI Uncertainty / Confidence Interval（AHI 不确定性）
   - 使用软计数 N_eff = Σ event_confidence_i
   - 使用 Poisson-rate 近似给出 AHI 的 95% CI（无需 scipy）

3) Event-level Confidence（事件级可信度）
   - 每个事件输出 confidence_i ∈ [0,1]（基于质量、ROI/姿态稳定、门控就绪、事件特征）
   - AHI 使用 soft counting（confidence 加权）

重要：
- 避免 Chaquopy numpy 版本不支持的 context manager：
  ❌ with np.load(...) as z:
  ❌ with np.printoptions(...):
"""

import os
import time
import math
from dataclasses import dataclass
from typing import Dict, Any, List, Tuple, Optional, Sequence

import numpy as np

# =========================
# 方案选择：4/2/1
# =========================
# 1 = AMP-only
# 2 = PHASE-only
# 4 = AMP+PHASE 融合（推荐）
DIAG_SCHEME = 4

# ------- 全局流式状态 -------
_STREAMS: Dict[str, Dict[str, Any]] = {}

# 缓存最多 10min（ring buffer 会按这个上限分配容量）
_STREAM_MAX_SEC = 600.0

# =========================
# chest gate（可选：自动范围）
# =========================
CHEST_START_BIN = 50
CHEST_END_BIN = 250
AUTO_CHEST_RANGE = True
AUTO_RANGE_HALF_WIDTH = 120

REF_START_BIN = 5
REF_END_BIN = 40
ROI_HALF_BW = 2

RESELECT_SEC = 10.0
HOLD_SEC = 20.0
SWITCH_RATIO = 1.45
PRESENCE_TH = 0.30

BREATH_F_MIN = 0.10
BREATH_F_MAX = 0.70

DEFAULT_FC_HZ = 20000.0
SPEED_SOUND = 343.0

# ============== baseline（peak-baseline）参数 ==============
PEAK_BASELINE_PCT = 75.0
PEAK_BASELINE_CAP_PCT = 90.0
PEAK_BASELINE_EMA_ALPHA = 0.08
PEAK_BASELINE_WIN_SEC = 180.0
PEAK_BASELINE_MAX_SEC = 600.0
PEAK_BASELINE_MIN_PEAKS = 10

# 冻结更新门限（信号质量门控）
BASELINE_UPDATE_MIN_PRESENCE = 0.10
BASELINE_UPDATE_MIN_CONF = 0.18
BASELINE_UPDATE_MAX_COVERAGE = 0.80
BASELINE_UPDATE_MIN_SNR_DB = -2.0

# 连续高coverage冻结阈值（秒）
COVERAGE_FREEZE_CONSEC_SEC = 8.0

# baseline hist 忽略开头 peaks
IGNORE_BASELINE_HEAD_SEC = 10.0

# ============== ApneaApp 风格 peak gate 参数 ==============
PEAK_MIN_DIST_SEC = 1.2
PEAK_GATE_WARMUP_SEC = 25.0
PEAK_GATE_MIN_PEAKS = 8
PEAK_GATE_K_SIGMA = 2.0

# ============== 事件参数（OA=gap+effort） ==============
EVENT_MIN_SEC = 1.5
PERIOD_MIN_SEC = 2.0
PERIOD_MAX_SEC = 6.0

HYPO_DROP_FRAC = 0.38
HYPO_RATIO = 1.0 - HYPO_DROP_FRAC
APNEA_LOW_RATIO = 0.20

# ✅ Hypopnea 必修参数
HYPO_RECOVER_RATIO = 0.85
HYPO_MIN_CONSEC_PEAKS = 3

# OA vs Central 的 effort 判据（phase_rms）
PHASE_STRONG_TH = 0.0015
PHASE_WEAK_TH = 0.0006

# ---------- 多特征滑窗 ----------
WIN_SEC = 12.0
STRIDE_SEC = 3.0

# ===================== 3) Effort spike（ApneaApp风格） =====================
SPIKE_GAIN_RATIO = 1.50
SPIKE_MERGE_SEC = 1.0
SPIKE_MIN_GAP_SEC = 3.0

# ===================== 6) 距离bin扫描兜底 =====================
FALLBACK_SCAN_ENABLE = True
FALLBACK_SCAN_MIN_PRESENCE = 0.12
FALLBACK_SCAN_MIN_SCORE = 0.05

# ===================== 5) 清醒/体动/伪迹闭环（宽松版） =====================
CLOSED_LOOP_ENABLE = True
INVALID_CONSEC_SEC = 20.0
VALID_RECOVER_SEC = 6.0
AWAKE_MERGE_GAP_SEC = 120.0

INVALID_MIN_QUALITY = 0.15
INVALID_MAX_COVERAGE = 0.80
INVALID_MIN_SNR_DB = -8.0

# =========================
# ROI locker（ApneaApp 对齐版）
# =========================
ROI_TOPK = 5
ROI_SCORE_MIN_LOCK = 0.06
ROI_PRESENCE_MIN_LOCK = 0.18
ROI_CANDIDATE_SEC = 2.0
ROI_RELOCK_DROP_PRES = 0.10
ROI_RELOCK_SCORE_RATIO = 0.55
ROI_SWITCH_GAIN = 1.35
ROI_HOLD_SEC = HOLD_SEC

ROI_SEARCH_WIN_SEC = 40.0

# ===================== Posture / Turn (ApneaApp-style minimal) =====================
POSTURE_ENABLE = True

TURN_ENTER_SEC = 1.2
TURN_EXIT_SEC  = 4.0
TURN_HOLD_SEC  = 6.0

TURN_PRES_JUMP_TH = 0.22
TURN_RATIO_JUMP_TH = 0.80
TURN_BIN_JUMP_TH = 6
TURN_ENV_JERK_Z_TH = 6.0

POSTURE_STABLE_MIN_SEC = 6.0
POSTURE_CONF_STABLE_TH = 0.55
POSTURE_CONF_TRANS_TH  = 0.35
POSTURE_CONF_EMA_ALPHA = 0.15
POSTURE_BIN_EMA_ALPHA  = 0.10

POSTURE_MIN_PRESENCE_FOR_TURN = 0.18
POSTURE_REQUIRE_ROI_LOCK = True
POSTURE_MIN_ENV_SEC = 2.0

JERK_WIN_SEC = 2.0
JERK_EMA_ALPHA = 0.06
JERK_Z_EPS = 1e-6

# ==========================
# Innovation controls
# ==========================
# AHI CI 置信水平：95% 用 1.96
AHI_CI_Z = 1.96

# 质量分层：对 AHI 的“最低贡献”下限（避免长时间低质量直接 TST_eff≈0）
QUALITY_WEIGHT_FLOOR = 0.05

# event confidence 最低值（避免 UI/统计出现全 0）
EVENT_CONF_FLOOR = 0.05


def _safe_float(x, default=0.0):
    try:
        if x is None:
            return float(default)
        return float(x)
    except Exception:
        return float(default)


def _safe_int(x, default=0):
    try:
        if x is None:
            return int(default)
        return int(x)
    except Exception:
        return int(default)


# =========================
# Innovation Pack
# =========================
def _clamp01(x: float) -> float:
    try:
        return float(np.clip(float(x), 0.0, 1.0))
    except Exception:
        return 0.0


def _sigmoid(x: float) -> float:
    x = float(x)
    if x >= 0:
        z = math.exp(-x)
        return 1.0 / (1.0 + z)
    else:
        z = math.exp(x)
        return z / (1.0 + z)


def _quality_weight(
        quality_index: float,
        presence: float,
        snr_db: float,
        coverage: float,
        roi_state: str,
        posture_state: str,
        turn_active: bool,
) -> float:
    """
    质量分层权重 w(t)∈[0,1]
    """
    q = _clamp01(quality_index)
    p = _clamp01(presence)

    # snr -> [0,1] (6~18dB)
    s = _clamp01((float(snr_db) - 6.0) / 12.0)

    # coverage -> decay
    c = 1.0 - _clamp01((float(coverage) - 0.05) / 0.55)

    roi_ok = 1.0 if str(roi_state) in ("LOCK", "HOLD") else 0.7
    post_ok = 1.0 if str(posture_state) == "STABLE" else 0.75
    turn_pen = 0.35 if bool(turn_active) else 1.0

    p_soft = 0.15 + 0.85 * _sigmoid((p - 0.18) / 0.06)  # 0.15~1.0

    w = q
    w *= (0.55 + 0.45 * p_soft)
    w *= (0.50 + 0.50 * s)
    w *= (0.40 + 0.60 * c)
    w *= roi_ok
    w *= post_ok
    w *= turn_pen

    # 下限：体现“分层而非 hard gate”
    w = max(float(QUALITY_WEIGHT_FLOOR), float(w))
    return float(np.clip(w, 0.0, 1.0))


def _quality_tier(w: float) -> str:
    w = float(w)
    if w >= 0.75:
        return "HIGH"
    elif w >= 0.45:
        return "MID"
    else:
        return "LOW"


def _wilson_poisson_rate_ci(k_eff: float, T_hours: float, z: float = 1.96) -> Tuple[float, float, float]:
    """
    Poisson-rate 近似 CI（无需 scipy），支持连续软计数 & k=0
    """
    T = max(1e-9, float(T_hours))
    k = max(0.0, float(k_eff))
    rate = k / T

    k_adj = k + 1e-6
    std = math.sqrt(max(1e-9, k_adj)) / T

    low = max(0.0, rate - z * std)
    high = rate + z * std
    return float(rate), float(low), float(high)


def _compute_ahi_soft_with_ci(
        events: List[Dict[str, Any]],
        tst_eff_sec: float,
        z: float = 1.96,
) -> Dict[str, Any]:
    """
    AHI = Σconf_i / (TST_eff/3600)
    """
    T_hours = float(max(1e-6, float(tst_eff_sec) / 3600.0))

    k_total = 0.0
    k_obst = 0.0
    k_hypo = 0.0
    k_cen = 0.0

    for e in (events or []):
        c = _clamp01(e.get("confidence", 1.0))
        typ = str(e.get("type", ""))
        k_total += c
        if typ == "obstructive":
            k_obst += c
        elif typ == "hypopnea":
            k_hypo += c
        elif typ == "central":
            k_cen += c

    r_total, lo_total, hi_total = _wilson_poisson_rate_ci(k_total, T_hours, z=z)
    r_obst, lo_obst, hi_obst = _wilson_poisson_rate_ci(k_obst, T_hours, z=z)
    r_hypo, lo_hypo, hi_hypo = _wilson_poisson_rate_ci(k_hypo, T_hours, z=z)
    r_cen, lo_cen, hi_cen = _wilson_poisson_rate_ci(k_cen, T_hours, z=z)

    return {
        "tst_eff_sec": float(tst_eff_sec),
        "tst_eff_hours": float(T_hours),

        "n_eff_total": float(k_total),
        "n_eff_obstructive": float(k_obst),
        "n_eff_hypopnea": float(k_hypo),
        "n_eff_central": float(k_cen),

        "ahi_total": float(r_total),
        "ahi_total_ci_low": float(lo_total),
        "ahi_total_ci_high": float(hi_total),

        "ahi_obstructive": float(r_obst),
        "ahi_obstructive_ci_low": float(lo_obst),
        "ahi_obstructive_ci_high": float(hi_obst),

        "ahi_hypopnea": float(r_hypo),
        "ahi_hypopnea_ci_low": float(lo_hypo),
        "ahi_hypopnea_ci_high": float(hi_hypo),

        "ahi_central": float(r_cen),
        "ahi_central_ci_low": float(lo_cen),
        "ahi_central_ci_high": float(hi_cen),
    }


def _event_confidence(
        ev_type: str,
        ev_start: float,
        ev_end: float,
        quality_index: float,
        presence: float,
        snr_db: float,
        coverage: float,
        roi_state: str,
        posture_state: str,
        turn_active: bool,
        breath_conf: float,
        baseline_ready: bool,
        gate_ready: bool,
        gap_sec: Optional[float] = None,
        phase_rms: Optional[float] = None,
        med_env: Optional[float] = None,
) -> float:
    """
    事件级可信度 confidence_i ∈ [0,1]
    """
    dur = max(0.0, float(ev_end) - float(ev_start))

    # 用质量权重做底座
    w = _quality_weight(
        quality_index=quality_index,
        presence=presence,
        snr_db=snr_db,
        coverage=coverage,
        roi_state=roi_state,
        posture_state=posture_state,
        turn_active=turn_active,
    )
    conf = float(w)

    # 呼吸 conf
    conf *= (0.45 + 0.55 * _clamp01(breath_conf))

    # baseline/gate readiness
    if not bool(baseline_ready):
        conf *= 0.55
    if not bool(gate_ready):
        conf *= 0.60

    # duration soft penalty
    if dur < 1.5:
        conf *= 0.35
    elif dur < 3.0:
        conf *= 0.70

    # gap longer -> better
    if gap_sec is not None:
        g = float(max(0.0, gap_sec))
        conf *= (0.55 + 0.45 * _clamp01(g / 10.0))

    # obstructive: strong phase -> better
    if ev_type == "obstructive" and phase_rms is not None:
        pr = float(max(0.0, phase_rms))
        pr_score = _clamp01((pr - PHASE_WEAK_TH) / max(1e-6, (PHASE_STRONG_TH - PHASE_WEAK_TH)))
        conf *= (0.70 + 0.30 * pr_score)

    # central: too strong phase -> a bit worse
    if ev_type == "central" and phase_rms is not None:
        pr = float(max(0.0, phase_rms))
        pr_bad = _clamp01((pr - PHASE_WEAK_TH) / max(1e-6, (PHASE_STRONG_TH - PHASE_WEAK_TH)))
        conf *= (1.0 - 0.25 * pr_bad)

    # hypopnea: med_env weak hint (optional)
    if ev_type == "hypopnea" and med_env is not None:
        m = float(max(0.0, med_env))
        conf *= (0.75 + 0.25 * _clamp01(m / (m + 1.0)))

    conf = max(float(EVENT_CONF_FLOOR), float(conf))
    return float(np.clip(conf, 0.0, 1.0))


# =========================
# Ring buffer helpers
# =========================
def _ring_init_if_needed(state: Dict[str, Any], n_bins: int, fs_env: float):
    """初始化环形缓冲：buf shape [cap_chirps, n_bins]"""
    if state.get("rt_mag_buf") is not None and state.get("rt_cx_buf") is not None:
        if int(state.get("rt_n_bins", -1)) == int(n_bins):
            # quality ring 也要存在（兼容旧状态）
            if state.get("rt_qw_buf") is None:
                cap_chirps = int(state.get("rt_cap_chirps", 0))
                state["rt_qw_buf"] = np.zeros((cap_chirps,), dtype=np.float32)
            return

    cap_chirps = int(max(1, math.ceil(float(_STREAM_MAX_SEC) * float(fs_env))))
    state["rt_mag_buf"] = np.zeros((cap_chirps, int(n_bins)), dtype=np.float32)
    state["rt_cx_buf"] = np.zeros((cap_chirps, int(n_bins)), dtype=np.complex64)

    # ✅ 新增：质量权重 ring（每 chirp 一个 w）
    state["rt_qw_buf"] = np.zeros((cap_chirps,), dtype=np.float32)

    state["rt_cap_chirps"] = int(cap_chirps)
    state["rt_write_pos"] = 0
    state["rt_filled"] = 0
    state["rt_total_chirps"] = 0
    state["rt_n_bins"] = int(n_bins)
    state["rt_fs_env"] = float(fs_env)


def _ring_push(state: Dict[str, Any], mag_block: np.ndarray, cx_block: np.ndarray):
    """将 (n_chirp, n_bins) 写入 ring"""
    buf_mag = state["rt_mag_buf"]
    buf_cx = state["rt_cx_buf"]
    cap = int(state["rt_cap_chirps"])
    w = int(state["rt_write_pos"])

    n = int(mag_block.shape[0])
    if n <= 0:
        return

    if n >= cap:
        mag_block = mag_block[-cap:]
        cx_block = cx_block[-cap:]
        n = cap

    end = w + n
    if end <= cap:
        buf_mag[w:end, :] = mag_block
        buf_cx[w:end, :] = cx_block
    else:
        k1 = cap - w
        k2 = end - cap
        buf_mag[w:cap, :] = mag_block[:k1]
        buf_mag[0:k2, :] = mag_block[k1:]
        buf_cx[w:cap, :] = cx_block[:k1]
        buf_cx[0:k2, :] = cx_block[k1:]

    w = (w + n) % cap
    state["rt_write_pos"] = int(w)
    state["rt_filled"] = int(min(cap, int(state["rt_filled"]) + n))
    state["rt_total_chirps"] = int(state.get("rt_total_chirps", 0)) + n


def _ring_push_quality_weight(state: Dict[str, Any], w_block: np.ndarray):
    """
    ✅ 新增：把每帧的质量权重 w(t) 写入 quality ring
    w_block: shape [n_chirp]
    """
    buf = state.get("rt_qw_buf", None)
    if buf is None:
        cap = int(state.get("rt_cap_chirps", 0))
        state["rt_qw_buf"] = np.zeros((cap,), dtype=np.float32)
        buf = state["rt_qw_buf"]

    cap = int(state["rt_cap_chirps"])
    wpos = int(state["rt_write_pos"])

    n = int(w_block.shape[0])
    if n <= 0:
        return

    # 注意：_ring_push 已经更新了 write_pos，所以这里要用“写入之前的起点”
    start = (wpos - n) % cap
    end = start + n

    if n >= cap:
        w_block = w_block[-cap:]
        n = cap
        start = (wpos - n) % cap
        end = start + n

    if end <= cap:
        buf[start:end] = w_block.astype(np.float32)
    else:
        k1 = cap - start
        k2 = end - cap
        buf[start:cap] = w_block[:k1].astype(np.float32)
        buf[0:k2] = w_block[k1:].astype(np.float32)


def _ring_get_last_chirps(state: Dict[str, Any], last_k: int) -> Tuple[np.ndarray, np.ndarray]:
    filled = int(state.get("rt_filled", 0))
    if filled <= 0:
        n_bins = int(state.get("rt_n_bins", 0))
        return np.zeros((0, n_bins), np.float32), np.zeros((0, n_bins), np.complex64)

    K = int(min(int(last_k), filled))
    cap = int(state["rt_cap_chirps"])
    w = int(state["rt_write_pos"])
    buf_mag = state["rt_mag_buf"]
    buf_cx = state["rt_cx_buf"]

    start = (w - K) % cap
    if start < w and (start + K) <= cap:
        mag = buf_mag[start:start + K, :].copy()
        cx = buf_cx[start:start + K, :].copy()
    else:
        k1 = cap - start
        k2 = K - k1
        mag = np.concatenate([buf_mag[start:cap, :], buf_mag[0:k2, :]], axis=0).copy()
        cx = np.concatenate([buf_cx[start:cap, :], buf_cx[0:k2, :]], axis=0).copy()
    return mag, cx


def _ring_get_last_sec(state: Dict[str, Any], last_sec: float) -> Tuple[np.ndarray, np.ndarray]:
    fs_env = float(state.get("rt_fs_env", 0.0))
    if fs_env <= 0:
        return _ring_get_last_chirps(state, 0)
    last_k = int(max(0, round(float(last_sec) * fs_env)))
    return _ring_get_last_chirps(state, last_k)


def _get_state(stream_id: str) -> Dict[str, Any]:
    st = _STREAMS.get(stream_id)
    if st is None:
        st = _STREAMS.setdefault(
            stream_id,
            dict(
                start_time=time.time(),

                rt_mag_buf=None,
                rt_cx_buf=None,
                rt_qw_buf=None,      # ✅ quality ring

                rt_cap_chirps=0,
                rt_write_pos=0,
                rt_filled=0,
                rt_total_chirps=0,
                rt_n_bins=0,
                rt_fs_env=0.0,

                chest_bin=None,
                chest_score=0.0,
                presence_score=0.0,
                presence_strength_ratio=0.0,

                last_reselect_sec=0.0,
                last_switch_sec=0.0,

                env_amp=None,
                env_ph=None,
                env_fused=None,

                phase_env_smooth=None,

                peak_tracker=None,
                peak_baseline=None,
                peak_baseline_hist=[],
                peak_hist_last_t=-1e9,
                peak_last_feed_t=-1e9,

                last_save=0.0,
                fc_hz=None,

                baseline_frozen=False,
                baseline_freeze_reason="",

                total_chirps_acc=0,
                coverage_high_consec_sec=0.0,

                invalid_consec_sec=0.0,
                valid_consec_sec=0.0,
                in_awake=False,
                awake_segments=[],
                awake_start_sec=None,

                # ✅ 有效时长：质量分层用
                tst_eff_sec=0.0,

                # 旧的“硬可用”时长也保留
                tst_valid_sec=0.0,

                # ROI locker state
                roi_state="SEARCH",
                roi_candidate_bin=None,
                roi_candidate_score=0.0,
                roi_candidate_since=0.0,
                roi_lock_bin=None,
                roi_lock_score=0.0,
                roi_lock_since=0.0,
                roi_hold_until=0.0,
                roi_last_reason="init",
                roi_switch_count=0,
                roi_topk_bins=[],
                roi_topk_scores=[],

                # Posture/Turn state
                posture_state="UNKNOWN",
                posture_conf=0.0,
                posture_conf_ema=0.0,
                posture_stable_sec=0.0,
                posture_reason="init",

                turn_active=False,
                turn_since=0.0,
                turn_hold_until=0.0,
                turn_enter_acc=0.0,
                turn_exit_acc=0.0,
                last_turn_print_t=-1e9,

                presence_ema=0.0,
                ratio_ema=0.0,
                best_bin_ema=None,

                jerk_mu=0.0,
                jerk_var=0.0,

                # ✅ 最新一帧质量权重
                quality_weight=0.0,
                quality_tier="LOW",

                ui_last_plot_t=-1e9,
                ui_cached_envelope_plot=None,
                ui_cached_phase_plot=None,

            )
        )
    return st


# ----------------- utils -----------------
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
        return x.astype(np.float32, copy=True)
    kernel = np.ones(k, dtype=np.float32) / float(k)
    y = np.convolve(x.astype(np.float32), kernel, mode="same")
    return y.astype(np.float32)


def _analytic_signal(x: np.ndarray) -> np.ndarray:
    x = np.asarray(x, dtype=np.float32).reshape(-1)
    n = x.size
    if n < 8:
        return x.astype(np.complex64)

    X = np.fft.fft(x.astype(np.float32), n=n)
    h = np.zeros(n, dtype=np.float32)
    if n % 2 == 0:
        h[0] = 1.0
        h[n // 2] = 1.0
        h[1:n // 2] = 2.0
    else:
        h[0] = 1.0
        h[1:(n + 1) // 2] = 2.0
    z = np.fft.ifft(X * h.astype(np.complex64))
    return z.astype(np.complex64)


def _estimate_center_freq_hz(tx: np.ndarray, fs: int) -> float:
    try:
        x = tx.astype(np.float32)
        x = x - float(np.mean(x))
        n = x.size
        if n < 64:
            return float(DEFAULT_FC_HZ)
        nfft = _next_pow2(n)
        spec = np.fft.rfft(x * np.hanning(n).astype(np.float32), n=nfft)
        mag = np.abs(spec)
        freqs = np.fft.rfftfreq(nfft, d=1.0 / float(fs))

        band = (freqs >= 16000.0) & (freqs <= 24000.0)
        if not np.any(band):
            idx = int(np.argmax(mag))
            f0 = float(freqs[idx]) if freqs[idx] > 0 else float(DEFAULT_FC_HZ)
            return f0

        idx = int(np.argmax(mag[band]))
        f0 = float(freqs[band][idx])
        return f0 if f0 > 0 else float(DEFAULT_FC_HZ)
    except Exception:
        return float(DEFAULT_FC_HZ)


def _wrap_diff(phi: np.ndarray) -> np.ndarray:
    if phi.size <= 1:
        return np.zeros(0, dtype=np.float32)
    d = phi[1:] - phi[:-1]
    d = np.angle(np.exp(1j * d)).astype(np.float32)
    return d


def _phase_rms(x: np.ndarray) -> float:
    if x is None or x.size == 0:
        return 0.0
    y = x.astype(np.float32)
    y = y - float(np.mean(y))
    return float(np.sqrt(np.mean(y * y) + 1e-12))


def _time_to_idx(t_sec: float, fs_env: float, n: int) -> int:
    if fs_env <= 0:
        return 0
    idx = int(round(float(t_sec) * float(fs_env)))
    return int(np.clip(idx, 0, max(0, n - 1)))


# ===================== 3) effort spike detector =====================
def _detect_effort_spikes(sig: np.ndarray, fs: float, baseline: float) -> List[Tuple[float, float]]:
    if sig is None or fs <= 0 or sig.size < int(fs * 2):
        return []
    x = np.abs(sig).astype(np.float32)
    med = float(np.median(x)) + 1e-6
    th = max(SPIKE_GAIN_RATIO * med, SPIKE_GAIN_RATIO * float(baseline))

    mask = x >= th
    if not np.any(mask):
        return []

    idx = np.where(mask)[0]
    segs = []
    s = idx[0]
    prev = idx[0]
    for k in idx[1:]:
        if k == prev + 1:
            prev = k
        else:
            segs.append((s, prev))
            s = k
            prev = k
    segs.append((s, prev))

    out = []
    for a, b in segs:
        t0 = a / fs
        t1 = b / fs
        if not out:
            out.append([t0, t1])
        else:
            if t0 - out[-1][1] <= SPIKE_MERGE_SEC:
                out[-1][1] = max(out[-1][1], t1)
            else:
                out.append([t0, t1])
    return [(float(a), float(b)) for a, b in out]


# ===================== 5) 宽松 invalid 判定（保留，但不再用 hard gate 做 AHI） =====================
def _is_invalid_lenient(presence: float, quality_index: float, coverage: float, snr_db: float, baseline_frozen: bool) -> bool:
    if presence < (PRESENCE_TH * 0.70):
        return True
    if quality_index < INVALID_MIN_QUALITY:
        return True
    if coverage > INVALID_MAX_COVERAGE:
        return True
    if snr_db < INVALID_MIN_SNR_DB:
        return True
    return False


# ============================
# 自动确定 chest 搜索范围
# ============================
def _auto_chest_range(rt_cx_all: np.ndarray, default_s: int, default_e: int) -> Tuple[int, int]:
    try:
        if rt_cx_all.size == 0:
            return default_s, default_e
        T, n_bins = rt_cx_all.shape
        if T < 8:
            return default_s, default_e

        med = np.median(np.abs(rt_cx_all), axis=0).astype(np.float32)
        ignore = min(5, n_bins)
        med[:ignore] = 0.0

        peak = int(np.argmax(med))
        hw = int(AUTO_RANGE_HALF_WIDTH)
        s = max(0, peak - hw)
        e = min(n_bins, peak + hw)

        if e - s < 32:
            s = max(0, peak - 64)
            e = min(n_bins, peak + 64)
        return int(s), int(e)
    except Exception:
        return default_s, default_e


def _score_bins_topk_breath(
        rt_cx_all: np.ndarray,
        fs_env: float,
        start_bin: int,
        end_bin: int,
        f_min: float,
        f_max: float,
        win_sec: float,
        topk: int = 5,
) -> Tuple[List[int], List[float], float, float]:
    if rt_cx_all.size == 0 or fs_env <= 0:
        return [], [], 0.0, 0.0
    T_all, n_bins = rt_cx_all.shape
    max_len = int(win_sec * fs_env)
    rt_win = rt_cx_all[-max_len:] if T_all > max_len else rt_cx_all
    T = rt_win.shape[0]
    if T < int(fs_env * 8):
        return [], [], 0.0, 0.0

    sbin = max(0, int(start_bin))
    ebin = min(int(end_bin), n_bins)
    if ebin <= sbin:
        sbin, ebin = 0, n_bins

    A = np.abs(rt_win[:, sbin:ebin]).astype(np.float32)
    if A.size == 0:
        return [], [], 0.0, 0.0

    med_per = np.median(A, axis=0).astype(np.float32) + 1e-6
    mx = float(np.max(med_per))
    bg = float(np.percentile(med_per, 20)) + 1e-6
    strength_ratio = mx / bg
    presence = (strength_ratio - 1.2) / 1.8
    presence = float(np.clip(presence, 0.0, 1.0))

    nfft = _next_pow2(T)
    freqs = np.fft.rfftfreq(nfft, d=1.0 / fs_env)
    band = (freqs >= f_min) & (freqs <= f_max)
    if not np.any(band):
        idx = np.argsort(-med_per)[:topk]
        bins = (sbin + idx).tolist()
        scores = (med_per[idx].astype(np.float32) / float(np.max(med_per) + 1e-6)).tolist()
        return [int(b) for b in bins], [float(s) for s in scores], presence, float(strength_ratio)

    win = np.hanning(T).astype(np.float32)
    K = A.shape[1]
    scores = np.zeros(K, dtype=np.float32)

    for j in range(K):
        sig = A[:, j].astype(np.float32)
        sig = (sig - float(np.mean(sig))) * win
        spec = np.fft.rfft(sig, n=nfft)
        mag = np.abs(spec).astype(np.float32)
        be = float(np.sum((mag[band] ** 2)) + 1e-6)
        te = float(np.sum((mag ** 2)) + 1e-6)
        scores[j] = be / te

    idx = np.argsort(-scores)[:max(1, int(topk))]
    top_bins = (sbin + idx).tolist()
    top_scores = scores[idx].tolist()
    return [int(b) for b in top_bins], [float(s) for s in top_scores], float(presence), float(strength_ratio)


def _fuse_bins(rt_cx_all: np.ndarray, center_bin: int, half_bw: int) -> np.ndarray:
    T, n_bins = rt_cx_all.shape
    c = int(center_bin)
    bins = np.arange(c - half_bw, c + half_bw + 1, dtype=np.int32)
    bins = bins[(bins >= 0) & (bins < n_bins)]
    if bins.size <= 0:
        return rt_cx_all[:, c].astype(np.complex64, copy=True)

    Z = rt_cx_all[:, bins].astype(np.complex64)
    A = np.abs(Z).astype(np.float32) + 1e-6
    W = A / np.sum(A, axis=1, keepdims=True)
    z = np.sum(W * Z, axis=1)
    return z.astype(np.complex64)


def _fuse_ref(rt_cx_all: np.ndarray, start_bin: int, end_bin: int) -> np.ndarray:
    T, n_bins = rt_cx_all.shape
    s = max(0, int(start_bin))
    e = min(int(end_bin), n_bins)
    if e <= s:
        s, e = 0, min(16, n_bins)

    Z = rt_cx_all[:, s:e].astype(np.complex64)
    if Z.size == 0:
        return np.ones(T, dtype=np.complex64)

    med = np.median(np.abs(Z), axis=0).astype(np.float32) + 1e-6
    w = med / float(np.sum(med))
    z = np.sum(Z * w.reshape(1, -1), axis=1)
    return z.astype(np.complex64)


def _phase_displacement_stable(
        z_target: np.ndarray,
        z_ref: np.ndarray,
        fc_hz: float,
        fs_env: float,
        smooth_sec: float = 0.5,
) -> np.ndarray:
    if z_target.size == 0 or z_ref.size == 0:
        return np.zeros(0, dtype=np.float32)

    zt = z_target.astype(np.complex64)
    zr = z_ref.astype(np.complex64)
    T = min(zt.size, zr.size)
    zt = zt[:T]
    zr = zr[:T]

    phi = np.angle(zt * np.conj(zr)).astype(np.float32)
    dphi = _wrap_diff(phi)
    phi_rel = np.concatenate([[0.0], np.cumsum(dphi).astype(np.float32)]).astype(np.float32)

    fc = float(fc_hz) if (fc_hz is not None and fc_hz > 0) else float(DEFAULT_FC_HZ)
    lam = float(SPEED_SOUND) / fc
    disp = (lam / (4.0 * math.pi)) * phi_rel
    disp = disp - float(np.mean(disp))

    disp_s = _moving_average(disp.astype(np.float32), win_sec=smooth_sec, fs=fs_env)
    return disp_s.astype(np.float32)


def _estimate_breath_rate_fft(env: np.ndarray, fs_env: float,
                              f_min: float = BREATH_F_MIN, f_max: float = BREATH_F_MAX) -> Tuple[float, float]:
    if env.size < fs_env * 8:
        return 0.0, 0.0

    MAX_SEC = 40.0
    max_len = int(MAX_SEC * fs_env)
    sig = env[-max_len:] if env.size > max_len else env

    sig = sig.astype(np.float32)
    sig = sig - float(np.mean(sig))
    T = sig.size
    if T < 8:
        return 0.0, 0.0

    win = np.hanning(T).astype(np.float32)
    sig = sig * win

    nfft = _next_pow2(T)
    freqs = np.fft.rfftfreq(nfft, d=1.0 / fs_env)
    spec = np.fft.rfft(sig, n=nfft)
    mag = np.abs(spec).astype(np.float32)

    band = (freqs >= f_min) & (freqs <= f_max)
    if not np.any(band):
        return 0.0, 0.0

    mag_band = mag[band]
    freqs_band = freqs[band]
    idx = int(np.argmax(mag_band))
    f_peak = float(freqs_band[idx])

    ratio = float(mag_band[idx]) / float(np.mean(mag_band) + 1e-6)
    conf = max(0.0, min(1.0, (ratio - 1.0) / 3.0))

    bpm = f_peak * 60.0
    if bpm < 6.0 or bpm > 42.0:
        return 0.0, 0.0
    return float(bpm), float(conf)


def _estimate_signal_quality(
        env_smooth: np.ndarray,
        fs_env: float,
        window_sec: float = 20.0,
        total_sec: Optional[float] = None,
        ignore_head_sec: float = 10.0,
) -> Tuple[float, float, float]:
    if env_smooth.size == 0 or fs_env <= 0:
        return 0.0, 0.0, 0.0

    if total_sec is not None and float(total_sec) < float(ignore_head_sec):
        return 0.0, 0.0, 0.0

    max_len = int(window_sec * fs_env)
    env = env_smooth[-max_len:].astype(np.float32) if env_smooth.size > max_len else env_smooth.astype(np.float32)
    if env.size < int(fs_env * 2):
        return 0.0, 0.0, 0.0

    lo = float(np.percentile(env, 5))
    hi = float(np.percentile(env, 95))
    env_w = np.clip(env, lo, hi).astype(np.float32)

    median = float(np.median(env_w))
    mad = float(np.median(np.abs(env_w - median)) + 1e-6)
    noise = 1.4826 * mad

    p95 = float(np.percentile(env_w, 95))
    p5 = float(np.percentile(env_w, 5))
    dynamic_range = max(1e-6, p95 - p5)

    snr_lin = dynamic_range / max(noise, 1e-6)
    snr_db = 20.0 * math.log10(max(snr_lin, 1e-6))

    threshold = median + 2.0 * noise
    coverage = float(np.mean(np.abs(env_w - median) > threshold))

    q_snr = (snr_db - 3.0) / 15.0
    q_snr = max(0.0, min(1.0, q_snr))
    q_cov = (coverage - 0.05) / 0.55
    q_cov = max(0.0, min(1.0, q_cov))

    quality_index = 0.7 * q_snr + 0.3 * q_cov
    quality_index = max(0.0, min(1.0, quality_index))
    return float(quality_index), float(snr_db), float(coverage)


# ==========================
# Peak tracker
# ==========================
@dataclass
class Peak:
    t: float
    amp: float


class ApneaPeakTracker:
    def __init__(self, fs: float):
        self.fs = float(fs)
        self.min_dist = float(PEAK_MIN_DIST_SEC)
        self.last_peak_t = -1e9
        self.prev2 = None
        self.prev1 = None
        self.peaks_all: List[Peak] = []
        self.gate_ready = False
        self.amp_gate = None
        self.warmup_start_t = None

        self.total_feed_samples = 0
        self.total_peak_candidates = 0
        self.total_peaks_accepted = 0

    def _update_gate(self, now_t: float):
        if self.warmup_start_t is None:
            self.warmup_start_t = now_t
        dur = now_t - self.warmup_start_t
        if dur < PEAK_GATE_WARMUP_SEC:
            return
        if len(self.peaks_all) < PEAK_GATE_MIN_PEAKS:
            return

        amps = np.array([p.amp for p in self.peaks_all], dtype=np.float32)
        med = float(np.median(amps))
        mad = float(np.median(np.abs(amps - med)) + 1e-6)
        robust_sigma = 1.4826 * mad
        self.amp_gate = med - float(PEAK_GATE_K_SIGMA) * float(robust_sigma)
        self.gate_ready = True

    def feed(self, x: float, t: float):
        self.total_feed_samples += 1
        cur = (float(t), float(x))
        if self.prev2 is None:
            self.prev2 = cur
            return
        if self.prev1 is None:
            self.prev1 = cur
            return

        t2, x2 = self.prev2
        t1, x1 = self.prev1
        t0, x0 = cur

        is_peak = (x1 > x2) and (x1 >= x0)
        if is_peak:
            self.total_peak_candidates += 1

        if is_peak and (t1 - self.last_peak_t) >= self.min_dist:
            amp = float(x1)
            if (not self.gate_ready) or (self.amp_gate is None) or (amp >= float(self.amp_gate)):
                self.peaks_all.append(Peak(t=t1, amp=amp))
                self.total_peaks_accepted += 1
                self.last_peak_t = t1
                self._update_gate(now_t=t1)

        self.prev2 = self.prev1
        self.prev1 = cur

    def get_recent_peaks(self, now_t: float, max_sec: float) -> List[Peak]:
        t0 = now_t - float(max_sec)
        return [p for p in self.peaks_all if p.t >= t0]


# ===================== Posture / Turn (你的版本，保留) =====================
def _posture_turn_update(
        state: Dict[str, Any],
        now_t: float,
        frame_sec: float,
        env_amp: np.ndarray,
        fs_env: float,
        presence: float,
        strength_ratio: float,
        roi_state: str,
        roi_lock_bin: Optional[int],
        roi_best_bin: Optional[int],
        roi_topk_bins: List[int],
        roi_topk_scores: List[float],
        quality_index: float = 1.0,
) -> Dict[str, Any]:
    dbg: Dict[str, Any] = {}

    if (not POSTURE_ENABLE) or (fs_env <= 0):
        dbg["posture_state"] = str(state.get("posture_state", "UNKNOWN"))
        dbg["turn_event"] = False
        dbg["posture_reason"] = "posture_disabled"
        return dbg

    min_need = int(max(4, round(POSTURE_MIN_ENV_SEC * fs_env)))
    if env_amp is None or env_amp.size < min_need:
        dbg["posture_state"] = str(state.get("posture_state", "UNKNOWN"))
        dbg["turn_event"] = False
        dbg["posture_reason"] = f"env_too_short({env_amp.size if env_amp is not None else 0})"
        return dbg

    stable_target = True
    if POSTURE_REQUIRE_ROI_LOCK:
        stable_target = stable_target and (str(roi_state) in ("LOCK", "HOLD")) and (roi_lock_bin is not None) and (int(roi_lock_bin) >= 0)
    stable_target = stable_target and (float(presence) >= float(POSTURE_MIN_PRESENCE_FOR_TURN)) and (float(quality_index) >= 0.10)

    if not stable_target:
        state["posture_state"] = "NO_TARGET"
        state["posture_reason"] = f"no_target(roi={roi_state},pres={presence:.2f},q={quality_index:.2f})"
        state["posture_conf_ema"] = float(state.get("posture_conf_ema", 0.0))
        state["posture_stable_sec"] = 0.0

        state["turn_active"] = False
        state["turn_enter_acc"] = 0.0
        state["turn_exit_acc"] = 0.0
        state["turn_hold_until"] = 0.0

        dbg.update(dict(
            posture_state="NO_TARGET",
            posture_conf=float(state.get("posture_conf_ema", 0.0)),
            posture_stable_sec=0.0,
            posture_reason=str(state["posture_reason"]),
            turn_event=False,
            turn_reason="",
            turn_active=False,
            turn_left_sec=0.0,
            pres_jump=0.0,
            ratio_jump=0.0,
            bin_jump=0,
            jerk_z=0.0,
        ))
        return dbg

    a_p = 0.20
    a_r = 0.20

    p_ema_prev = float(state.get("presence_ema", presence))
    r_ema_prev = float(state.get("ratio_ema", strength_ratio))

    pres_jump = abs(float(presence) - float(p_ema_prev))
    ratio_jump = abs(float(strength_ratio) - float(r_ema_prev)) / max(1e-6, float(r_ema_prev))

    p_ema = (1.0 - a_p) * float(p_ema_prev) + a_p * float(presence)
    r_ema = (1.0 - a_r) * float(r_ema_prev) + a_r * float(strength_ratio)

    state["presence_ema"] = float(p_ema)
    state["ratio_ema"] = float(r_ema)

    bb_ema = state.get("best_bin_ema", None)
    bin_jump = 0
    if roi_best_bin is not None:
        if bb_ema is None:
            bb_ema = float(roi_best_bin)
        else:
            bb_ema = (1.0 - POSTURE_BIN_EMA_ALPHA) * float(bb_ema) + POSTURE_BIN_EMA_ALPHA * float(roi_best_bin)
        state["best_bin_ema"] = float(bb_ema)
        bin_jump = int(abs(int(roi_best_bin) - int(round(float(bb_ema)))))

    conf_sep = 0.0
    if roi_topk_scores and len(roi_topk_scores) >= 2:
        s1 = float(roi_topk_scores[0])
        s2 = float(roi_topk_scores[1])
        conf_sep = (s1 - s2) / max(1e-6, s1)

    conf_ratio = (float(strength_ratio) - 1.2) / 2.2
    conf_ratio = float(np.clip(conf_ratio, 0.0, 1.0))

    conf = 0.65 * float(np.clip(conf_sep, 0.0, 1.0)) + 0.35 * conf_ratio
    conf = float(np.clip(conf, 0.0, 1.0))

    conf_ema_prev = float(state.get("posture_conf_ema", conf))
    conf_ema = (1.0 - POSTURE_CONF_EMA_ALPHA) * float(conf_ema_prev) + POSTURE_CONF_EMA_ALPHA * float(conf)
    state["posture_conf_ema"] = float(conf_ema)

    w = int(min(env_amp.size, max(16, int(round(JERK_WIN_SEC * fs_env)))))
    seg = env_amp[-w:].astype(np.float32)
    d1 = np.diff(seg)
    jerk = float(np.sqrt(np.mean(d1 * d1) + 1e-12))

    jerk_mu = float(state.get("jerk_mu", jerk))
    jerk_var = float(state.get("jerk_var", 0.0))

    alpha = float(JERK_EMA_ALPHA)
    mu_new = (1.0 - alpha) * jerk_mu + alpha * jerk
    var_new = (1.0 - alpha) * jerk_var + alpha * ((jerk - mu_new) ** 2)

    state["jerk_mu"] = float(mu_new)
    state["jerk_var"] = float(var_new)

    jerk_std = float(np.sqrt(max(1e-12, var_new)))
    jerk_z = float((jerk - mu_new) / max(JERK_Z_EPS, jerk_std))

    hard_turn = (
            pres_jump >= TURN_PRES_JUMP_TH
            or ratio_jump >= TURN_RATIO_JUMP_TH
            or bin_jump >= TURN_BIN_JUMP_TH
            or jerk_z >= TURN_ENV_JERK_Z_TH
    )

    turn_active = bool(state.get("turn_active", False))
    hold_until = float(state.get("turn_hold_until", 0.0))
    enter_acc = float(state.get("turn_enter_acc", 0.0))
    exit_acc = float(state.get("turn_exit_acc", 0.0))

    turn_event = False
    turn_reason = ""

    if not turn_active:
        if hard_turn:
            enter_acc += float(frame_sec)
        else:
            enter_acc = 0.0

        if enter_acc >= TURN_ENTER_SEC:
            turn_active = True
            hold_until = float(now_t + TURN_HOLD_SEC)
            exit_acc = 0.0
            enter_acc = 0.0
            turn_event = True

            if jerk_z >= TURN_ENV_JERK_Z_TH:
                turn_reason = f"turn_jerk(z={jerk_z:.1f})"
            elif pres_jump >= TURN_PRES_JUMP_TH:
                turn_reason = f"turn_presence_jump(dp={pres_jump:.2f})"
            elif ratio_jump >= TURN_RATIO_JUMP_TH:
                turn_reason = f"turn_ratio_jump(dr={ratio_jump:.2f})"
            else:
                turn_reason = f"turn_bin_jump(db={bin_jump})"
    else:
        if now_t < hold_until:
            exit_acc = 0.0
        else:
            if hard_turn:
                exit_acc = 0.0
            else:
                exit_acc += float(frame_sec)

            if exit_acc >= TURN_EXIT_SEC:
                turn_active = False
                hold_until = 0.0
                enter_acc = 0.0
                exit_acc = 0.0
                turn_reason = "turn_exit"

    state["turn_active"] = bool(turn_active)
    state["turn_hold_until"] = float(hold_until)
    state["turn_enter_acc"] = float(enter_acc)
    state["turn_exit_acc"] = float(exit_acc)

    posture_state = str(state.get("posture_state", "UNKNOWN"))
    posture_stable_sec = float(state.get("posture_stable_sec", 0.0))

    if turn_active:
        posture_state = "TURN"
        posture_stable_sec = 0.0
        posture_reason = turn_reason if turn_reason else f"turning(hold_left={max(0.0, hold_until-now_t):.1f}s)"
    else:
        if conf_ema >= POSTURE_CONF_STABLE_TH:
            posture_stable_sec += float(frame_sec)
            if posture_stable_sec >= POSTURE_STABLE_MIN_SEC:
                posture_state = "STABLE"
                posture_reason = f"stable(conf={conf_ema:.2f})"
            else:
                posture_state = "TRANSITION"
                posture_reason = f"stabilizing({posture_stable_sec:.1f}s conf={conf_ema:.2f})"
        elif conf_ema <= POSTURE_CONF_TRANS_TH:
            posture_state = "TRANSITION"
            posture_stable_sec = 0.0
            posture_reason = f"low_conf(conf={conf_ema:.2f})"
        else:
            posture_state = "TRANSITION"
            posture_stable_sec = 0.0
            posture_reason = f"mid_conf(conf={conf_ema:.2f})"

    state["posture_state"] = str(posture_state)
    state["posture_conf"] = float(conf_ema)
    state["posture_stable_sec"] = float(posture_stable_sec)
    state["posture_reason"] = str(posture_reason)

    dbg.update(dict(
        posture_state=str(posture_state),
        posture_conf=float(conf_ema),
        posture_stable_sec=float(posture_stable_sec),
        posture_reason=str(posture_reason),

        turn_event=bool(turn_event),
        turn_reason=str(turn_reason),
        turn_active=bool(turn_active),
        turn_left_sec=float(max(0.0, hold_until - now_t)) if turn_active else 0.0,

        hard_turn=bool(hard_turn),
        pres_jump=float(pres_jump),
        ratio_jump=float(ratio_jump),
        bin_jump=int(bin_jump),
        jerk=float(jerk),
        jerk_mu=float(state.get("jerk_mu", 0.0)),
        jerk_std=float(np.sqrt(max(1e-12, float(state.get("jerk_var", 0.0))))),
        jerk_z=float(jerk_z),

        roi_state=str(roi_state),
        roi_lock_bin=int(roi_lock_bin) if roi_lock_bin is not None else -1,
        roi_best_bin=int(roi_best_bin) if roi_best_bin is not None else -1,
        presence=float(presence),
        strength_ratio=float(strength_ratio),
        quality_index=float(quality_index),
    ))
    return dbg


# ==========================
# ROI locker 核心状态机
# ==========================
def _roi_lock_update(
        state: Dict[str, Any],
        rt40_cx: np.ndarray,
        fs_env: float,
        total_duration_sec: float,
        s_auto: int,
        e_auto: int,
) -> Tuple[Optional[int], float, float, float, Dict[str, Any]]:
    dbg: Dict[str, Any] = {}

    top_bins, top_scores, presence, strength_ratio = _score_bins_topk_breath(
        rt_cx_all=rt40_cx,
        fs_env=fs_env,
        start_bin=s_auto,
        end_bin=e_auto,
        f_min=BREATH_F_MIN,
        f_max=BREATH_F_MAX,
        win_sec=ROI_SEARCH_WIN_SEC,
        topk=ROI_TOPK,
    )

    state["roi_topk_bins"] = top_bins
    state["roi_topk_scores"] = top_scores
    state["presence_score"] = float(presence)
    state["presence_strength_ratio"] = float(strength_ratio)

    best_bin = int(top_bins[0]) if top_bins else None
    best_score = float(top_scores[0]) if top_scores else 0.0

    cur_state = str(state.get("roi_state", "SEARCH"))
    lock_bin = state.get("roi_lock_bin", None)
    lock_score = float(state.get("roi_lock_score", 0.0))
    hold_until = float(state.get("roi_hold_until", 0.0))

    reason = "ok"

    def set_state(new_state: str, why: str):
        nonlocal cur_state, reason
        cur_state = new_state
        reason = why
        state["roi_state"] = new_state
        state["roi_last_reason"] = why

    now_t = float(total_duration_sec)

    if cur_state not in ("SEARCH", "CANDIDATE", "LOCK", "HOLD", "RELOCK"):
        set_state("SEARCH", "state_reset")

    if cur_state == "HOLD":
        if now_t < hold_until and lock_bin is not None:
            hold_left = float(max(0.0, hold_until - now_t))
            if float(presence) < float(ROI_RELOCK_DROP_PRES):
                set_state("RELOCK", "hold_drop_presence")
            else:
                set_state("HOLD", f"holding_wait({hold_left:.1f}s_left)")
        else:
            set_state("LOCK", "hold_end")

    if cur_state == "LOCK":
        if lock_bin is None:
            set_state("SEARCH", "lock_missing")
        else:
            if float(presence) < float(ROI_RELOCK_DROP_PRES):
                set_state("RELOCK", "drop_presence")
            elif lock_score > 1e-6 and best_score < (float(ROI_RELOCK_SCORE_RATIO) * lock_score):
                set_state("RELOCK", "score_decay")
            else:
                if best_bin is not None and best_bin != int(lock_bin):
                    if best_score >= float(ROI_SWITCH_GAIN) * max(1e-6, lock_score):
                        state["roi_lock_bin"] = int(best_bin)
                        state["roi_lock_score"] = float(best_score)
                        state["roi_lock_since"] = float(now_t)
                        state["roi_hold_until"] = float(now_t + ROI_HOLD_SEC)
                        state["roi_switch_count"] = int(state.get("roi_switch_count", 0)) + 1
                        set_state("HOLD", f"switch_gain({best_score:.3f}>{ROI_SWITCH_GAIN:.2f}x)")
                    else:
                        set_state("LOCK", "keep_lock")
                else:
                    set_state("LOCK", "keep_lock")

    if cur_state == "RELOCK":
        if best_bin is not None and best_score >= ROI_SCORE_MIN_LOCK and presence >= ROI_PRESENCE_MIN_LOCK:
            state["roi_candidate_bin"] = int(best_bin)
            state["roi_candidate_score"] = float(best_score)
            state["roi_candidate_since"] = float(now_t)
            set_state("CANDIDATE", "relock_candidate")
        else:
            set_state("SEARCH", "relock_search")

    if cur_state == "SEARCH":
        if best_bin is None:
            set_state("SEARCH", "no_topk")
        else:
            if best_score >= ROI_SCORE_MIN_LOCK and presence >= ROI_PRESENCE_MIN_LOCK:
                state["roi_candidate_bin"] = int(best_bin)
                state["roi_candidate_score"] = float(best_score)
                state["roi_candidate_since"] = float(now_t)
                set_state("CANDIDATE", "enter_candidate")
            else:
                set_state("SEARCH", "weak_best")

    if cur_state == "CANDIDATE":
        cb = state.get("roi_candidate_bin", None)
        cs = float(state.get("roi_candidate_score", 0.0))
        since = float(state.get("roi_candidate_since", now_t))

        ok = False
        if cb is not None and top_bins:
            if int(cb) == int(top_bins[0]):
                ok = True
            else:
                for b, s in zip(top_bins, top_scores):
                    if int(b) == int(cb) and float(s) >= 0.85 * max(1e-6, cs):
                        ok = True
                        break

        if (not ok) or (presence < ROI_PRESENCE_MIN_LOCK) or (best_score < 0.85 * max(1e-6, cs)):
            state["roi_candidate_bin"] = None
            state["roi_candidate_score"] = 0.0
            set_state("SEARCH", "candidate_lost")
        else:
            if (now_t - since) >= ROI_CANDIDATE_SEC:
                state["roi_lock_bin"] = int(cb)
                state["roi_lock_score"] = float(cs)
                state["roi_lock_since"] = float(now_t)
                state["roi_hold_until"] = float(now_t + ROI_HOLD_SEC)
                set_state("HOLD", "candidate_confirm_lock")
            else:
                set_state("CANDIDATE", "candidate_wait")

    cur_state = str(state.get("roi_state", "SEARCH"))
    lock_bin = state.get("roi_lock_bin", None)
    lock_score = float(state.get("roi_lock_score", 0.0))
    cand_bin = state.get("roi_candidate_bin", None)
    cand_score = float(state.get("roi_candidate_score", 0.0))

    use_bin = None
    use_score = 0.0

    if lock_bin is not None:
        use_bin = int(lock_bin)
        use_score = float(lock_score)
    elif cand_bin is not None:
        use_bin = int(cand_bin)
        use_score = float(cand_score)

    dbg.update(dict(
        roi_state=str(cur_state),
        roi_reason=str(state.get("roi_last_reason", reason)),
        roi_presence=float(presence),
        roi_strength_ratio=float(strength_ratio),
        roi_topk_bins=top_bins,
        roi_topk_scores=top_scores,
        roi_best_bin=int(best_bin) if best_bin is not None else -1,
        roi_best_score=float(best_score),
        roi_candidate_bin=int(cand_bin) if cand_bin is not None else -1,
        roi_candidate_score=float(cand_score),
        roi_candidate_since=float(state.get("roi_candidate_since", 0.0)),
        roi_lock_bin=int(lock_bin) if lock_bin is not None else -1,
        roi_lock_score=float(lock_score),
        roi_lock_since=float(state.get("roi_lock_since", 0.0)),
        roi_hold_until=float(state.get("roi_hold_until", 0.0)),
        roi_hold_left_sec=float(max(0.0, float(state.get("roi_hold_until", 0.0)) - now_t)),
        roi_switch_count=int(state.get("roi_switch_count", 0)),
        roi_range_s=int(s_auto),
        roi_range_e=int(e_auto),
    ))

    return use_bin, float(use_score), float(presence), float(strength_ratio), dbg


# ==========================
# peak baseline（你原版）
# ==========================
def _update_peak_baseline_b1b2(
        state: Dict[str, Any],
        now_t: float,
        frame_sec: float,
        presence_score: float,
        breath_conf: float,
        snr_db: float,
        coverage: float,
        peaks: List[Peak],
) -> Optional[float]:
    state["baseline_frozen"] = False
    state["baseline_freeze_reason"] = ""

    prev = state.get("peak_baseline")
    baseline_not_ready = (prev is None) or (float(prev) <= 0.0)
    state["baseline_peaks_in_win"] = int(state.get("baseline_peaks_in_win", 0))

    freeze = False
    reasons = []

    if breath_conf < BASELINE_UPDATE_MIN_CONF:
        freeze = True
        reasons.append(f"low_conf({breath_conf:.2f})")

    if snr_db < BASELINE_UPDATE_MIN_SNR_DB:
        freeze = True
        reasons.append(f"low_snr({snr_db:.1f}dB)")

    if presence_score < BASELINE_UPDATE_MIN_PRESENCE and breath_conf < (BASELINE_UPDATE_MIN_CONF + 0.06):
        freeze = True
        reasons.append(f"low_presence({presence_score:.2f})")

    if not baseline_not_ready:
        if coverage > BASELINE_UPDATE_MAX_COVERAGE:
            state["coverage_high_consec_sec"] = float(state.get("coverage_high_consec_sec", 0.0)) + float(frame_sec)
        else:
            state["coverage_high_consec_sec"] = 0.0

        if float(state.get("coverage_high_consec_sec", 0.0)) >= float(COVERAGE_FREEZE_CONSEC_SEC):
            freeze = True
            reasons.append(f"high_coverage({coverage:.2f})")

    if freeze:
        state["baseline_frozen"] = True
        state["baseline_freeze_reason"] = "|".join(reasons)
        return None if baseline_not_ready else float(prev)

    hist = state.get("peak_baseline_hist") or []
    last_t = float(state.get("peak_hist_last_t", -1e9))

    if peaks:
        for p in sorted(peaks, key=lambda x: x.t):
            pt = float(p.t)
            if pt < float(IGNORE_BASELINE_HEAD_SEC):
                continue
            if pt > last_t + 1e-6:
                hist.append((pt, float(p.amp)))
                last_t = pt

    state["peak_hist_last_t"] = last_t
    t_cut = float(now_t) - float(PEAK_BASELINE_MAX_SEC)
    if hist:
        hist = [(t, a) for (t, a) in hist if t >= t_cut]
    state["peak_baseline_hist"] = hist

    t_win = float(now_t) - float(PEAK_BASELINE_WIN_SEC)
    amps = [a for (t, a) in hist if t >= t_win]
    state["baseline_peaks_in_win"] = int(len(amps))
    if len(amps) < PEAK_BASELINE_MIN_PEAKS:
        return None

    w = np.asarray(amps, dtype=np.float32)

    med = float(np.median(w))
    mad = float(np.median(np.abs(w - med)) + 1e-6)
    robust_sigma = 1.4826 * mad
    spike_th = med + 6.0 * robust_sigma

    w = w[w <= spike_th]
    if w.size < PEAK_BASELINE_MIN_PEAKS:
        return None

    cap = float(np.percentile(w, PEAK_BASELINE_CAP_PCT))
    w2 = np.minimum(w, cap)

    baseline = float(np.percentile(w2, PEAK_BASELINE_PCT))
    baseline = max(baseline, float(np.mean(w2) + 1e-6))

    prev = state.get("peak_baseline")
    if prev is not None and float(prev) > 0.0:
        alpha = float(PEAK_BASELINE_EMA_ALPHA)
        baseline = (1.0 - alpha) * float(prev) + alpha * float(baseline)

    state["peak_baseline"] = float(baseline)
    return float(baseline)


# ==========================
# 事件检测：Hypopnea + Apnea(gap) + OA/CEN（你原版，保留）
# ==========================
def _events_from_peaks_apneaapp_v2(
        peaks: List[Peak],
        peak_baseline: float,
        env_pos: np.ndarray,
        phase_env: Optional[np.ndarray],
        fs_env: float,
        baseline_frozen: bool = False,
) -> List[Dict[str, Any]]:
    if peak_baseline is None or peak_baseline <= 0:
        return []
    if len(peaks) < 2:
        return []

    peaks = sorted(peaks, key=lambda p: p.t)
    events: List[Dict[str, Any]] = []

    hypo_ratio_th = float(HYPO_RATIO) * float(peak_baseline)
    recover_th = float(HYPO_RECOVER_RATIO) * float(peak_baseline)

    in_hypo = False
    hypo_start = None
    last_t = None
    low_cnt = 0

    for p in peaks:
        a = float(p.amp)
        t = float(p.t)

        periodic_ok = True
        if last_t is not None:
            dt = t - last_t
            periodic_ok = (PERIOD_MIN_SEC <= dt <= PERIOD_MAX_SEC)

        low_amp = (a <= hypo_ratio_th)
        recover = (a >= recover_th)

        if baseline_frozen and (not in_hypo):
            low_cnt = 0
            last_t = t
            continue

        if not in_hypo:
            if low_amp and periodic_ok:
                low_cnt += 1
            else:
                low_cnt = 0
            if low_cnt >= int(HYPO_MIN_CONSEC_PEAKS):
                in_hypo = True
                hypo_start = t
        else:
            if recover or (not periodic_ok):
                if hypo_start is not None:
                    dur = t - hypo_start
                    if dur >= EVENT_MIN_SEC:
                        events.append(dict(type="hypopnea", start_sec=float(hypo_start), end_sec=float(t)))
                in_hypo = False
                hypo_start = None
                low_cnt = 0

        last_t = t

    if in_hypo and hypo_start is not None:
        t_end = float(peaks[-1].t)
        if (t_end - hypo_start) >= EVENT_MIN_SEC:
            events.append(dict(type="hypopnea", start_sec=float(hypo_start), end_sec=float(t_end)))

    n_env = int(env_pos.size)
    n_ph = int(phase_env.size) if (phase_env is not None) else 0

    for i in range(1, len(peaks)):
        t0 = float(peaks[i - 1].t)
        t1 = float(peaks[i].t)
        gap = t1 - t0
        if gap < EVENT_MIN_SEC:
            continue

        i0 = _time_to_idx(t0, fs_env, n_env)
        i1 = _time_to_idx(t1, fs_env, n_env)
        if i1 <= i0:
            continue

        seg_env = env_pos[i0:i1]
        med_env = float(np.median(seg_env)) if seg_env.size else 0.0

        prms = 0.0
        if n_ph > 0:
            j0 = _time_to_idx(t0, fs_env, n_ph)
            j1 = _time_to_idx(t1, fs_env, n_ph)
            if j1 > j0:
                prms = _phase_rms(phase_env[j0:j1])

        low_amp = (med_env <= float(APNEA_LOW_RATIO) * float(peak_baseline))

        typ = None
        if gap >= SPIKE_MIN_GAP_SEC and (phase_env is not None) and (n_ph > 0):
            j0 = _time_to_idx(t0, fs_env, n_ph)
            j1 = _time_to_idx(t1, fs_env, n_ph)
            if j1 > j0:
                spikes = _detect_effort_spikes(phase_env[j0:j1], fs_env, baseline=peak_baseline)
                if len(spikes) > 0:
                    typ = "obstructive"

        if typ is None:
            if (phase_env is not None) and (n_ph > 0):
                if prms >= PHASE_STRONG_TH:
                    typ = "obstructive"
                elif prms <= PHASE_WEAK_TH:
                    typ = "central"
                else:
                    typ = "central" if low_amp else "obstructive"
            else:
                typ = "central" if low_amp else "obstructive"

        events.append(dict(
            type=typ,
            start_sec=float(t0),
            end_sec=float(t1),
            gap_sec=float(gap),
            phase_rms=float(prms),
            med_env=float(med_env),
        ))

    events.sort(key=lambda x: (x["start_sec"], x["end_sec"]))
    merged: List[Dict[str, Any]] = []
    for ev in events:
        if not merged:
            merged.append(ev)
            continue
        last = merged[-1]
        g = float(ev["start_sec"] - last["end_sec"])
        if ev["type"] == last["type"] and g <= 1.0:
            last["end_sec"] = max(float(last["end_sec"]), float(ev["end_sec"]))
        else:
            merged.append(ev)

    out: List[Dict[str, Any]] = []
    for ev in merged:
        dur = float(ev["end_sec"] - ev["start_sec"])
        if ev["type"] in ("obstructive", "central"):
            if dur >= EVENT_MIN_SEC:
                out.append(ev)
        else:
            out.append(ev)
    return out


def _classify_severity(ahi_total: float) -> Tuple[int, str]:
    if ahi_total < 5.0:
        return 0, "None"
    elif ahi_total < 15.0:
        return 1, "Mild"
    elif ahi_total < 30.0:
        return 2, "Moderate"
    else:
        return 3, "Severe"


# =========================
# 方案 4 / 2 / 1：env 构造
# =========================
def _build_env_by_scheme(
        scheme: int,
        env_amp: np.ndarray,
        env_ph: np.ndarray,
        conf_amp: float,
        conf_ph: float,
        presence: float
) -> Tuple[np.ndarray, str]:
    env_amp = env_amp.astype(np.float32)
    env_ph = env_ph.astype(np.float32)

    if scheme == 1:
        return env_amp, "amp"
    if scheme == 2:
        return env_ph, "phase"

    ca = float(np.clip(conf_amp, 0.0, 1.0))
    cp = float(np.clip(conf_ph, 0.0, 1.0))
    pr = float(np.clip(presence, 0.0, 1.0))

    w_phase = 0.15 + 0.70 * (0.55 * cp + 0.25 * pr + 0.20 * max(0.0, cp - ca))
    w_phase = float(np.clip(w_phase, 0.15, 0.85))

    def _robust_norm(x: np.ndarray) -> np.ndarray:
        if x.size < 8:
            return x
        m = float(np.median(x))
        mad = float(np.median(np.abs(x - m)) + 1e-6)
        s = 1.4826 * mad
        return (x - m) / max(1e-6, s)

    a = _robust_norm(env_amp)
    p = _robust_norm(env_ph)

    fused = (1.0 - w_phase) * a + w_phase * p
    fused = fused.astype(np.float32)
    return fused, "fusion"


# =========================
# main
# =========================
def process_fmcw_frame_stream(
        stream_id: str,
        rxPcm: Sequence[int],
        txChirp: Sequence[int],
        chirpsPerFrame: int,
        sampleRate: int = 48000,
        save_dir: Optional[str] = None,
):
    state = _get_state(stream_id)

    # ---------- 1. 输入整理 ----------
    rx = np.asarray(rxPcm, dtype=np.float32).reshape(-1)
    tx = np.asarray(txChirp, dtype=np.float32).reshape(-1)
    n_chirp = int(chirpsPerFrame)
    n_samp = int(tx.shape[0]) if tx.size > 0 else 0

    if n_samp <= 0 or n_chirp <= 0:
        raise ValueError(f"Invalid chirpsPerFrame={n_chirp} or tx length={n_samp}")

    need = n_chirp * n_samp
    if rx.size < need:
        rx_padded = np.zeros(need, dtype=np.float32)
        rx_padded[:rx.size] = rx
        rx = rx_padded
        print(f"[FMCW] frame too short: pad to {need}")
    elif rx.size > need:
        rx = rx[:need]
    rx = rx.reshape(n_chirp, n_samp)

    # ---------- 2. 估计 fc ----------
    if state.get("fc_hz") is None:
        state["fc_hz"] = float(_estimate_center_freq_hz(tx, sampleRate))
    fc_hz = float(state["fc_hz"])

    # ---------- 3. FMCW 解调 ----------
    tx_cx = _analytic_signal(tx)
    tx_cx_conj = np.conj(tx_cx)

    nfft = _next_pow2(n_samp)
    n_bins = nfft // 2 + 1

    beat_mag = np.empty((n_chirp, n_bins), dtype=np.float32)
    beat_cx = np.empty((n_chirp, n_bins), dtype=np.complex64)

    for i in range(n_chirp):
        rx_cx = _analytic_signal(rx[i])
        L = min(rx_cx.shape[0], tx_cx_conj.shape[0])
        if L <= 0:
            raise ValueError("Invalid rx/tx length after analytic_signal")
        b = rx_cx[:L] * tx_cx_conj[:L]
        B = np.fft.rfft(b, n=nfft)
        beat_cx[i] = B.astype(np.complex64)
        beat_mag[i] = np.abs(B).astype(np.float32)

    # ---------- 4. ring buffer 累积 ----------
    frame_sec = float(n_chirp * n_samp) / float(sampleRate)
    fs_env = float(sampleRate) / float(n_samp) if n_samp > 0 else 1.0

    _ring_init_if_needed(state, n_bins=n_bins, fs_env=fs_env)
    _ring_push(state, beat_mag, beat_cx)

    total_chirps = int(state.get("rt_total_chirps", 0))
    state["total_chirps_acc"] = int(total_chirps)
    total_duration_sec = float(total_chirps / fs_env) if fs_env > 0 else 0.0

    now = time.time()
    start_time = state.get("start_time") or now
    state["start_time"] = start_time
    wall_duration_sec = float(now - start_time)
    time_drift_sec = float(wall_duration_sec - total_duration_sec)

    # ---------- 只取最近窗口 ----------
    rt40_mag, rt40_cx = _ring_get_last_sec(state, 40.0)
    rt600_mag, rt600_cx = _ring_get_last_sec(state, 600.0)

    # ---------- 5. chest bin 选择（ROI locker） ----------
    if AUTO_CHEST_RANGE:
        s_auto, e_auto = _auto_chest_range(rt40_cx, CHEST_START_BIN, CHEST_END_BIN)
    else:
        s_auto, e_auto = CHEST_START_BIN, CHEST_END_BIN

    chest_bin, chest_score, presence_score, strength_ratio, roi_dbg = _roi_lock_update(
        state=state,
        rt40_cx=rt40_cx,
        fs_env=fs_env,
        total_duration_sec=total_duration_sec,
        s_auto=s_auto,
        e_auto=e_auto,
    )

    state["chest_bin"] = chest_bin
    state["chest_score"] = float(chest_score)
    state["presence_score"] = float(presence_score)
    state["presence_strength_ratio"] = float(strength_ratio)

    effective_bin = int(chest_bin) if (chest_bin is not None) else -1

    # ---------- 6. AMP env（600s） ----------
    rt_all_mag = rt600_mag
    rt_all_cx = rt600_cx

    if chest_bin is None or chest_bin < 0 or chest_bin >= n_bins:
        env_amp_raw = rt_all_mag.mean(axis=1).astype(np.float32)
        effective_bin = -1
    else:
        env_amp_raw = rt_all_mag[:, int(chest_bin)].astype(np.float32)
        effective_bin = int(chest_bin)

    env_amp_raw = env_amp_raw - float(np.mean(env_amp_raw))
    env_amp = _moving_average(env_amp_raw, win_sec=0.5, fs=fs_env)
    state["env_amp"] = env_amp

    # ---------- 9. 信号质量（先算 quality，再做 posture） ----------
    quality_index, snr_db, coverage = _estimate_signal_quality(
        env_amp, fs_env, total_sec=total_duration_sec, ignore_head_sec=10.0
    )

    # ---------- Posture / Turn update ----------
    posture_dbg = _posture_turn_update(
        state=state,
        now_t=float(total_duration_sec),
        frame_sec=float(frame_sec),
        env_amp=env_amp,
        fs_env=fs_env,
        presence=presence_score,
        strength_ratio=float(state.get("presence_strength_ratio", 0.0)),
        roi_state=str(state.get("roi_state", "SEARCH")),
        roi_lock_bin=state.get("roi_lock_bin", None),
        roi_best_bin=int(roi_dbg.get("roi_best_bin", -1)) if int(roi_dbg.get("roi_best_bin", -1)) >= 0 else None,
        roi_topk_bins=roi_dbg.get("roi_topk_bins", []),
        roi_topk_scores=roi_dbg.get("roi_topk_scores", []),
        quality_index=float(quality_index),
    )

    # ---------- ROI protection during TURN/TRANSITION ----------
    if bool(posture_dbg.get("turn_active", False)):
        if state.get("roi_lock_bin", None) is not None:
            state["roi_state"] = "HOLD"
            left = float(posture_dbg.get("turn_left_sec", 0.0))
            state["roi_last_reason"] = f"holding_turn({left:.1f}s_left)"
            state["roi_hold_until"] = max(
                float(state.get("roi_hold_until", 0.0)),
                float(total_duration_sec) + left
            )
    else:
        if str(posture_dbg.get("posture_state", "")) == "TRANSITION" and state.get("roi_lock_bin", None) is not None:
            state["roi_state"] = "HOLD"
            conf = float(posture_dbg.get("posture_conf", 0.0))
            state["roi_last_reason"] = f"holding_posture_transition(conf={conf:.2f})"
            state["roi_hold_until"] = max(float(state.get("roi_hold_until", 0.0)), float(total_duration_sec) + 2.0)

    # ---------- TURN print (once) ----------
    if bool(posture_dbg.get("turn_event", False)):
        last_print_t = float(state.get("turn_last_print_t", -1e9))
        if float(total_duration_sec) > last_print_t + 1.0:
            state["turn_last_print_t"] = float(total_duration_sec)
            print(
                "[PY][TURN] detected:",
                posture_dbg.get("turn_reason", ""),
                "t=", f"{total_duration_sec:.1f}s",
                "posture=", f"{posture_dbg.get('posture_state','')}|{posture_dbg.get('posture_reason','')}",
                "turn_left=", f"{float(posture_dbg.get('turn_left_sec',0.0)):.1f}s",
                "roi=", f"{state.get('roi_state','')}|{state.get('roi_last_reason','')}",
                "presence=", f"{presence_score:.2f}",
                "ratio=", f"{float(state.get('presence_strength_ratio',0.0)):.2f}",
                "lock_bin=", int(state.get("roi_lock_bin", -1)) if state.get("roi_lock_bin", None) is not None else -1,
                "best_bin=", int(roi_dbg.get("roi_best_bin", -1)),
                "dp=", f"{float(posture_dbg.get('pres_jump',0.0)):.2f}",
                "dr=", f"{float(posture_dbg.get('ratio_jump',0.0)):.2f}",
                "db=", int(posture_dbg.get("bin_jump", 0)),
                "jz=", f"{float(posture_dbg.get('jerk_z',0.0)):.1f}",
            )

    # ✅ 修 bug：baseline_rebuild_flag 不要引用未定义变量 baseline_rebuild
    state["posture_dbg"] = posture_dbg
    state["baseline_rebuild_flag"] = bool(state.get("turn_active", False))

    # ---------- 7. PHASE env ----------
    env_ph = np.zeros_like(env_amp, dtype=np.float32)
    ph_tail = np.zeros(0, dtype=np.float32)

    if (
            chest_bin is not None
            and 0 <= int(chest_bin) < n_bins
            and rt40_cx.shape[0] >= int(fs_env * 8)
            and presence_score >= ROI_PRESENCE_MIN_LOCK
    ):
        z_target = _fuse_bins(rt40_cx, int(chest_bin), half_bw=ROI_HALF_BW)
        z_ref = _fuse_ref(rt40_cx, REF_START_BIN, REF_END_BIN)
        ph_tail = _phase_displacement_stable(
            z_target=z_target,
            z_ref=z_ref,
            fc_hz=fc_hz,
            fs_env=fs_env,
            smooth_sec=0.5,
        )

    if ph_tail.size > 0:
        tail_len = int(min(ph_tail.size, env_ph.size))
        env_ph[-tail_len:] = ph_tail[-tail_len:]
    state["env_ph"] = env_ph
    state["phase_env_smooth"] = env_ph

    # ---------- 8. 呼吸频率估计 ----------
    bpm_amp, conf_amp = _estimate_breath_rate_fft(env_amp, fs_env)
    bpm_ph, conf_ph = _estimate_breath_rate_fft(env_ph, fs_env) if env_ph.size else (0.0, 0.0)

    # ---------- 方案4/2/1：env 用于后续 peak/baseline/events ----------
    env_used, scheme_tag = _build_env_by_scheme(
        scheme=int(DIAG_SCHEME),
        env_amp=env_amp,
        env_ph=env_ph,
        conf_amp=conf_amp,
        conf_ph=conf_ph,
        presence=presence_score
    )
    state["env_fused"] = env_used if scheme_tag == "fusion" else state.get("env_fused")

    # breath 输出也跟随方案
    if scheme_tag == "phase" and conf_ph > 0:
        breath_bpm, breath_conf, breath_src = bpm_ph, conf_ph, "phase"
    elif scheme_tag == "fusion":
        if conf_ph >= max(conf_amp, 0.25) and bpm_ph > 0:
            breath_bpm, breath_conf, breath_src = bpm_ph, conf_ph, "phase"
        else:
            breath_bpm, breath_conf, breath_src = bpm_amp, conf_amp, "amp"
    else:
        breath_bpm, breath_conf, breath_src = bpm_amp, conf_amp, "amp"

    # ---------- 10. peak tracker ----------
    if state.get("peak_tracker") is None:
        state["peak_tracker"] = ApneaPeakTracker(fs=fs_env)
    pk: ApneaPeakTracker = state["peak_tracker"]

    env_pos = np.abs(env_used).astype(np.float32)
    frame_len = int(n_chirp)

    if env_pos.size >= frame_len and fs_env > 0:
        t_end = float(total_duration_sec)
        t_start = t_end - float(frame_len / fs_env)

        last_feed_t = float(state.get("peak_last_feed_t", -1e9))
        for i in range(frame_len):
            t_i = t_start + float(i / fs_env)
            if t_i > last_feed_t + 1e-6:
                pk.feed(x=float(env_pos[-frame_len + i]), t=t_i)
                last_feed_t = t_i
        state["peak_last_feed_t"] = last_feed_t

    recent_peaks = pk.get_recent_peaks(now_t=float(total_duration_sec), max_sec=float(PEAK_BASELINE_WIN_SEC))

    # ---------- 11. peak baseline ----------
    peak_baseline = _update_peak_baseline_b1b2(
        state=state,
        now_t=float(total_duration_sec),
        frame_sec=float(frame_sec),
        presence_score=presence_score,
        breath_conf=breath_conf,
        snr_db=snr_db,
        coverage=coverage,
        peaks=recent_peaks,
    )
    baseline_frozen = bool(state.get("baseline_frozen", False))

    # ---------- 5) 清醒/体动/伪迹闭环（保留，但不用于 hard gate AHI） ----------
    frame_is_invalid = False
    if CLOSED_LOOP_ENABLE:
        frame_is_invalid = _is_invalid_lenient(
            presence=presence_score,
            quality_index=quality_index,
            coverage=coverage,
            snr_db=snr_db,
            baseline_frozen=baseline_frozen
        )

        if frame_is_invalid:
            state["invalid_consec_sec"] = float(state.get("invalid_consec_sec", 0.0)) + float(frame_sec)
            state["valid_consec_sec"] = 0.0
        else:
            state["valid_consec_sec"] = float(state.get("valid_consec_sec", 0.0)) + float(frame_sec)
            state["invalid_consec_sec"] = 0.0

        in_awake = bool(state.get("in_awake", False))

        if (not in_awake) and float(state["invalid_consec_sec"]) >= float(INVALID_CONSEC_SEC):
            state["in_awake"] = True
            state["awake_start_sec"] = float(total_duration_sec) - float(state["invalid_consec_sec"])

        if in_awake and float(state["valid_consec_sec"]) >= float(VALID_RECOVER_SEC):
            state["in_awake"] = False
            st0 = state.get("awake_start_sec")
            st1 = float(total_duration_sec)
            if st0 is not None:
                state.setdefault("awake_segments", []).append((float(st0), float(st1)))
            state["awake_start_sec"] = None

        # 旧的硬时长仍保留（兼容原逻辑）
        if not bool(state.get("in_awake", False)):
            state["tst_valid_sec"] = float(state.get("tst_valid_sec", 0.0)) + float(frame_sec)

    # ============================
    # ✅ 创新点1：质量分层（权重 w(t)）
    # ============================
    q_w = _quality_weight(
        quality_index=float(quality_index),
        presence=float(presence_score),
        snr_db=float(snr_db),
        coverage=float(coverage),
        roi_state=str(state.get("roi_state", "SEARCH")),
        posture_state=str(state.get("posture_state", "UNKNOWN")),
        turn_active=bool(state.get("turn_active", False)),
    )
    q_tier = _quality_tier(q_w)
    state["quality_weight"] = float(q_w)
    state["quality_tier"] = str(q_tier)

    # TST_eff：每帧累积 w*dt（awake 时仍可累积，但权重一般会更低；你也可以选择 awake 时额外衰减）
    state["tst_eff_sec"] = float(state.get("tst_eff_sec", 0.0)) + float(q_w) * float(frame_sec)

    # 把每 chirp 的 w 写进 ring（用于报告/回放）
    _ring_push_quality_weight(state, np.full((n_chirp,), float(q_w), dtype=np.float32))

    # ---------- 12. 事件检测 ----------
    events_all: List[Dict[str, Any]] = []
    gate_ready = bool(pk.gate_ready)
    baseline_ready = (peak_baseline is not None and peak_baseline > 0)

    phase_for_events = env_ph if int(DIAG_SCHEME) in (2, 4) else None

    # 事件检测仍保留 presence & ready 门控（避免纯噪声生成事件）
    if baseline_ready and gate_ready and presence_score >= PRESENCE_TH:
        peaks_long = pk.get_recent_peaks(now_t=float(total_duration_sec), max_sec=600.0)
        events_all = _events_from_peaks_apneaapp_v2(
            peaks=peaks_long,
            peak_baseline=float(peak_baseline),
            env_pos=env_pos,
            phase_env=phase_for_events,
            fs_env=fs_env,
            baseline_frozen=baseline_frozen,
        )

    # ============================
    # ✅ 创新点3：事件级可信度（给每个事件打分）
    # ============================
    for e in events_all:
        et = str(e.get("type", ""))
        st = float(e.get("start_sec", 0.0))
        ed = float(e.get("end_sec", 0.0))
        conf = _event_confidence(
            ev_type=et,
            ev_start=st,
            ev_end=ed,
            quality_index=float(quality_index),
            presence=float(presence_score),
            snr_db=float(snr_db),
            coverage=float(coverage),
            roi_state=str(state.get("roi_state", "SEARCH")),
            posture_state=str(state.get("posture_state", "UNKNOWN")),
            turn_active=bool(state.get("turn_active", False)),
            breath_conf=float(breath_conf),
            baseline_ready=bool(baseline_ready),
            gate_ready=bool(gate_ready),
            gap_sec=e.get("gap_sec", None),
            phase_rms=e.get("phase_rms", None),
            med_env=e.get("med_env", None),
        )
        e["confidence"] = float(conf)

    # ============================
    # ✅ 创新点2：AHI 不确定性（用 TST_eff + soft counting）
    # ============================
    # awake 中的事件，你原来会截断；这里保持你原逻辑：awake 后不计入 AHI
    events_for_ahi = events_all
    if CLOSED_LOOP_ENABLE and bool(state.get("in_awake", False)):
        awake_start = state.get("awake_start_sec")
        if awake_start is not None:
            events_for_ahi = [e for e in events_all if float(e.get("end_sec", 0.0)) <= float(awake_start)]

    # 用有效时长（质量分层）做 TST_eff
    tst_eff_sec = float(state.get("tst_eff_sec", total_duration_sec))
    ahi_soft = _compute_ahi_soft_with_ci(events_for_ahi, tst_eff_sec=tst_eff_sec, z=float(AHI_CI_Z))
    ahi_total = float(ahi_soft["ahi_total"])
    severity_idx, severity_name = _classify_severity(ahi_total)

    # ---------- 14. 决策窗口 & 诊断文案 ----------
    DECISION_WINDOW = 30.0
    cut_t = max(0.0, total_duration_sec - DECISION_WINDOW)
    recent_events = [e for e in events_all if float(e.get("end_sec", 0.0)) >= cut_t]
    recent_event_count = len(recent_events)

    shown_sec = int(wall_duration_sec)

    if presence_score < PRESENCE_TH:
        diagnosis = f"目标不够稳定（presence={presence_score:.2f}），请对准胸口并保持距离/角度"
    else:
        if not baseline_ready or not gate_ready:
            diagnosis = (f"warmup中：baseline_ready={baseline_ready}, gate_ready={gate_ready}，"
                         f"已采集约 {shown_sec} 秒（conf={breath_conf:.2f}, src={breath_src}, scheme={scheme_tag}）")
        elif total_duration_sec < 30.0:
            diagnosis = f"已采集约 {shown_sec} 秒，呼吸置信度={breath_conf:.2f}（src={breath_src}），AHI 仅供参考"
        else:
            if not events_all:
                diagnosis = f"未见明显呼吸事件（正常），AHI≈{ahi_total:.1f} ({severity_name})"
            elif recent_event_count == 0:
                diagnosis = f"历史存在可疑事件，最近 {int(DECISION_WINDOW)} 秒未见新事件，AHI≈{ahi_total:.1f} ({severity_name})"
            else:
                diagnosis = f"检测到呼吸事件，AHI≈{ahi_total:.1f} ({severity_name})"

        # ---------- 15. 绘图曲线（固定点数 + 降频，避免跨语言大拷贝/GC） ----------
    PLOT_SEC = 60.0              # 仍然只看最近 60s（即使你别处给了更长数组也会裁掉）
    PLOT_POINTS = 256            # ✅ 永远固定 256 点（128/256/320 都可）
    UI_PLOT_HZ = 1.0             # ✅ UI 1Hz 更新（需要更丝滑可改 2.0）
    UI_PLOT_INTERVAL = 1.0 / max(1e-6, UI_PLOT_HZ)

    def _plot_pack_fixed(x: np.ndarray, n: int = PLOT_POINTS) -> np.ndarray:
        if x is None or x.size == 0:
            return np.zeros((n,), dtype=np.float32)

        # 只取最近 PLOT_SEC 秒，避免数组越跑越大
        max_len = int(max(1, round(PLOT_SEC * fs_env)))
        y = x[-max_len:] if x.size > max_len else x
        y = y.astype(np.float32, copy=False)

        # 归一化
        y = y - float(np.mean(y))
        std = float(np.std(y))
        if std > 1e-6:
            y = y / std
        y = np.clip(y, -5.0, 5.0)

        # ✅ 定长下采样到 n 点
        if y.size == n:
            return y.astype(np.float32, copy=True)
        if y.size < n:
            out = np.zeros((n,), dtype=np.float32)
            out[-y.size:] = y
            return out

        idx = np.linspace(0, y.size - 1, n).astype(np.int32)
        return y[idx].astype(np.float32, copy=False)

    # ✅ 降频：不是每帧都生成/发送 plot
    last_plot_t = float(state.get("ui_last_plot_t", -1e9))
    if float(total_duration_sec) >= last_plot_t + UI_PLOT_INTERVAL:
        state["ui_last_plot_t"] = float(total_duration_sec)
        state["ui_cached_envelope_plot"] = _plot_pack_fixed(env_used, PLOT_POINTS)
        state["ui_cached_phase_plot"] = _plot_pack_fixed(env_ph, PLOT_POINTS)

    envelope_plot = state.get("ui_cached_envelope_plot", None)
    phase_plot = state.get("ui_cached_phase_plot", None)

    if envelope_plot is None:
        envelope_plot = np.zeros((PLOT_POINTS,), dtype=np.float32)
    if phase_plot is None:
        phase_plot = np.zeros((PLOT_POINTS,), dtype=np.float32)


    # ---------- 16. 可选保存 npz（保存 600s 窗口） ----------
    rt_npz_saved = None
    if save_dir:
        now2 = time.time()
        if now2 - float(state.get("last_save", 0.0)) > 60.0 and rt_all_mag.size > 0:
            state["last_save"] = now2
            try:
                os.makedirs(save_dir, exist_ok=True)
                fname = f"rt_{stream_id}_{int(now2)}.npz"
                fpath = os.path.join(save_dir, fname)

                n_evt = len(events_all)
                evt_type = np.zeros(n_evt, dtype=np.int8)
                evt_start = np.zeros(n_evt, dtype=np.float32)
                evt_end = np.zeros(n_evt, dtype=np.float32)
                evt_conf = np.zeros(n_evt, dtype=np.float32)

                type_map = {"hypopnea": 1, "obstructive": 2, "central": 3}

                for i, e in enumerate(events_all):
                    evt_type[i] = type_map.get(e.get("type", ""), -1)
                    evt_start[i] = float(e.get("start_sec", 0.0))
                    evt_end[i] = float(e.get("end_sec", 0.0))
                    evt_conf[i] = float(e.get("confidence", 1.0))

                np.savez_compressed(
                    fpath,
                    rt=rt_all_mag.astype(np.float32),
                    rt_cx=rt_all_cx.astype(np.complex64),
                    sample_rate=int(sampleRate),

                    fs_env=float(fs_env),
                    fc_hz=np.array([float(fc_hz)], dtype=np.float32),

                    chest_bin=np.array([int(chest_bin if chest_bin is not None else -1)], dtype=np.int32),
                    chest_score=np.array([float(state.get("chest_score", 0.0))], dtype=np.float32),

                    presence_score=np.array([float(presence_score)], dtype=np.float32),
                    presence_strength_ratio=np.array([float(state.get("presence_strength_ratio", 0.0))], dtype=np.float32),

                    env_amp=env_amp.astype(np.float32),
                    env_ph=env_ph.astype(np.float32),
                    env_used=env_used.astype(np.float32),
                    diag_scheme=np.array([int(DIAG_SCHEME)], dtype=np.int32),

                    peak_baseline=np.array([float(-1.0 if peak_baseline is None else peak_baseline)], dtype=np.float32),

                    evt_type=evt_type,
                    evt_start=evt_start,
                    evt_end=evt_end,
                    evt_conf=evt_conf,

                    total_duration_sec=np.array([float(total_duration_sec)], dtype=np.float32),
                    record_wall_sec=np.array([float(wall_duration_sec)], dtype=np.float32),
                    time_drift_sec=np.array([float(time_drift_sec)], dtype=np.float32),

                    baseline_frozen=np.array([1 if baseline_frozen else 0], dtype=np.int8),
                    baseline_freeze_reason=np.array([state.get("baseline_freeze_reason", "")], dtype=np.unicode_),

                    peak_gate_ready=np.array([1 if gate_ready else 0], dtype=np.int8),
                    peak_amp_gate=np.array([float(-1.0 if pk.amp_gate is None else pk.amp_gate)], dtype=np.float32),

                    in_awake=np.array([1 if bool(state.get("in_awake", False)) else 0], dtype=np.int8),
                    tst_valid_sec=np.array([float(state.get("tst_valid_sec", 0.0))], dtype=np.float32),

                    # ✅ 新增：TST_eff & quality stratification
                    tst_eff_sec=np.array([float(tst_eff_sec)], dtype=np.float32),
                    quality_weight=np.array([float(state.get("quality_weight", 0.0))], dtype=np.float32),
                    quality_tier=np.array([str(state.get("quality_tier", "LOW"))], dtype=np.unicode_),

                    # ROI debug
                    roi_state=np.array([str(state.get("roi_state", "SEARCH"))], dtype=np.unicode_),
                    roi_reason=np.array([str(state.get("roi_last_reason", ""))], dtype=np.unicode_),
                    roi_topk_bins=np.array(state.get("roi_topk_bins", []), dtype=np.int32),
                    roi_topk_scores=np.array(state.get("roi_topk_scores", []), dtype=np.float32),
                    roi_switch_count=np.array([int(state.get("roi_switch_count", 0))], dtype=np.int32),

                    posture_state=np.array([str(state.get("posture_state", "UNKNOWN"))], dtype=np.unicode_),
                    posture_conf=np.array([float(state.get("posture_conf", 0.0))], dtype=np.float32),
                    posture_stable_sec=np.array([float(state.get("posture_stable_sec", 0.0))], dtype=np.float32),
                    posture_reason=np.array([str(state.get("posture_reason", ""))], dtype=np.unicode_),

                    turn_active=np.array([1 if bool(state.get("turn_active", False)) else 0], dtype=np.int8),
                    turn_hold_until=np.array([float(state.get("turn_hold_until", 0.0))], dtype=np.float32),
                )
                rt_npz_saved = fpath
            except Exception as e:
                print("[WARN] save rt_npz failed:", e)
                rt_npz_saved = None

    # ---------- 17. 质量等级 / usable ----------
    if quality_index < 0.25:
        quality_grade = 0
        quality_label = "bad"
    elif quality_index < 0.5:
        quality_grade = 1
        quality_label = "ok"
    else:
        quality_grade = 2
        quality_label = "good"

    usable_for_ahi = bool(total_duration_sec >= 60.0 and quality_index >= 0.25 and presence_score >= PRESENCE_TH)

    peak_count_all = int(len(pk.peaks_all)) if pk is not None else 0
    gate_elapsed = 0.0
    if pk is not None and pk.warmup_start_t is not None:
        gate_elapsed = float(total_duration_sec) - float(pk.warmup_start_t)
        gate_elapsed = max(0.0, gate_elapsed)

    # ---------- 18. 输出 ----------
    features = {
        "sample_rate": int(round(fs_env)),

        "envelope_plot": envelope_plot,
        "resp_env_plot": envelope_plot,
        "posture_env_plot": phase_plot,

        "artifact_mask_plot": np.zeros_like(envelope_plot),
        "posture_flag_plot": (np.ones_like(envelope_plot) if bool(state.get("turn_active", False)) else np.zeros_like(envelope_plot)),
        "baseline_rebuild": bool(state.get("turn_active", False)),

        "breath_freq": float(breath_bpm),
        "breath_freq_fft": float(breath_bpm),
        "breath_freq_peak": float(breath_bpm),
        "breath_fft_conf": float(breath_conf),
        "breath_src": breath_src,

        "quality_index": float(quality_index),
        "snr_db": float(snr_db),
        "coverage": float(coverage),

        # ✅ 创新点1：质量分层输出
        "quality_weight": float(state.get("quality_weight", 0.0)),
        "quality_tier": str(state.get("quality_tier", "LOW")),

        "presence_score": float(presence_score),
        "presence_strength_ratio": float(state.get("presence_strength_ratio", 0.0)),
        "target_range_bin": int(effective_bin),

        "decision_period_sec": float(DECISION_WINDOW),
        "recent_event_count": int(recent_event_count),
        "diagnosis": diagnosis,

        # ✅ 创新点2：soft AHI + CI
        "ahi_est": float(ahi_total),
        "ahi_total": float(ahi_total),
        "ahi_total_ci_low": float(ahi_soft.get("ahi_total_ci_low", 0.0)),
        "ahi_total_ci_high": float(ahi_soft.get("ahi_total_ci_high", 0.0)),

        "ahi_obstructive": float(ahi_soft.get("ahi_obstructive", 0.0)),
        "ahi_obstructive_ci_low": float(ahi_soft.get("ahi_obstructive_ci_low", 0.0)),
        "ahi_obstructive_ci_high": float(ahi_soft.get("ahi_obstructive_ci_high", 0.0)),

        "ahi_hypopnea": float(ahi_soft.get("ahi_hypopnea", 0.0)),
        "ahi_hypopnea_ci_low": float(ahi_soft.get("ahi_hypopnea_ci_low", 0.0)),
        "ahi_hypopnea_ci_high": float(ahi_soft.get("ahi_hypopnea_ci_high", 0.0)),

        "ahi_central": float(ahi_soft.get("ahi_central", 0.0)),
        "ahi_central_ci_low": float(ahi_soft.get("ahi_central_ci_low", 0.0)),
        "ahi_central_ci_high": float(ahi_soft.get("ahi_central_ci_high", 0.0)),

        # ✅ 有效时长
        "ahi_valid_sec": float(state.get("tst_valid_sec", total_duration_sec)),
        "ahi_eff_valid_sec": float(state.get("tst_eff_sec", total_duration_sec)),

        "ahi_level_idx": int(severity_idx),
        "ahi_level_name": severity_name,
        "plot_window_sec": float(PLOT_SEC),

        "quality_grade": int(quality_grade),
        "quality_label": quality_label,
        "usable_for_ahi": bool(usable_for_ahi),

        "rt_npz_saved": rt_npz_saved,
        "record_wall_sec": float(wall_duration_sec),

        "time_total_sec": float(total_duration_sec),
        "time_wall_sec": float(wall_duration_sec),
        "time_drift_sec": float(time_drift_sec),
        "total_chirps_acc": int(state.get("total_chirps_acc", 0)),

        "fc_hz": float(fc_hz),

        "peak_gate_ready": bool(gate_ready),
        "peak_amp_gate": float(-1.0 if pk.amp_gate is None else pk.amp_gate),
        "gate_elapsed_sec": float(gate_elapsed),
        "gate_warmup_need_sec": float(PEAK_GATE_WARMUP_SEC),
        "gate_min_peaks_need": int(PEAK_GATE_MIN_PEAKS),
        "peak_min_dist_sec": float(PEAK_MIN_DIST_SEC),
        "peak_count_all": int(peak_count_all),
        "peak_candidates": int(getattr(pk, "total_peak_candidates", 0)),
        "peak_accepted": int(getattr(pk, "total_peaks_accepted", 0)),

        "baseline_ready": bool(baseline_ready),
        "peak_baseline": float(-1.0 if peak_baseline is None else peak_baseline),
        "baseline_win_sec": float(PEAK_BASELINE_WIN_SEC),
        "baseline_min_peaks_need": int(PEAK_BASELINE_MIN_PEAKS),
        "baseline_peaks_in_win": int(state.get("baseline_peaks_in_win", 0)),

        "baseline_frozen": bool(baseline_frozen),
        "baseline_freeze_reason": str(state.get("baseline_freeze_reason", "")),

        "in_awake": bool(state.get("in_awake", False)),
        "tst_valid_sec": float(state.get("tst_valid_sec", 0.0)),
        "frame_is_invalid": bool(frame_is_invalid),

        "diag_scheme": int(DIAG_SCHEME),
        "diag_scheme_tag": scheme_tag,

        # ===== Posture / Turn debug =====
        "posture_state": str(state.get("posture_state", posture_dbg.get("posture_state", "UNKNOWN"))),
        "posture_conf": float(state.get("posture_conf", posture_dbg.get("posture_conf", 0.0))),
        "posture_stable_sec": float(state.get("posture_stable_sec", posture_dbg.get("posture_stable_sec", 0.0))),
        "posture_reason": str(state.get("posture_reason", posture_dbg.get("posture_reason", ""))),

        "turn_active": bool(state.get("turn_active", posture_dbg.get("turn_active", False))),
        "turn_left_sec": float(posture_dbg.get("turn_left_sec", 0.0)),
        "turn_hold_left_sec": float(max(0.0, float(state.get("turn_hold_until", 0.0)) - float(total_duration_sec))),
        "turn_event": bool(posture_dbg.get("turn_event", False)),
        "turn_reason": str(posture_dbg.get("turn_reason", "")),

        "baseline_rebuild_reason": str(posture_dbg.get("turn_reason", "")) if bool(state.get("turn_active", False)) else "",

        "turn_dbg_hard": bool(posture_dbg.get("hard_turn", False)),
        "turn_dbg_pres_jump": float(posture_dbg.get("pres_jump", 0.0)),
        "turn_dbg_ratio_jump": float(posture_dbg.get("ratio_jump", 0.0)),
        "turn_dbg_bin_jump": int(posture_dbg.get("bin_jump", 0)),
        "turn_dbg_jerk_z": float(posture_dbg.get("jerk_z", 0.0)),
        "turn_dbg_jerk_mu": float(posture_dbg.get("jerk_mu", 0.0)),
        "turn_dbg_jerk_std": float(posture_dbg.get("jerk_std", 0.0)),

        # ===== ROI debug =====
        "roi_state": roi_dbg.get("roi_state", ""),
        "roi_reason": roi_dbg.get("roi_reason", ""),
        "roi_presence": float(roi_dbg.get("roi_presence", 0.0)),
        "roi_strength_ratio": float(roi_dbg.get("roi_strength_ratio", 0.0)),
        "roi_topk_bins": roi_dbg.get("roi_topk_bins", []),
        "roi_topk_scores": roi_dbg.get("roi_topk_scores", []),
        "roi_best_bin": int(roi_dbg.get("roi_best_bin", -1)),
        "roi_best_score": float(roi_dbg.get("roi_best_score", 0.0)),
        "roi_candidate_bin": int(roi_dbg.get("roi_candidate_bin", -1)),
        "roi_candidate_score": float(roi_dbg.get("roi_candidate_score", 0.0)),
        "roi_lock_bin": int(roi_dbg.get("roi_lock_bin", -1)),
        "roi_lock_score": float(roi_dbg.get("roi_lock_score", 0.0)),
        "roi_hold_left_sec": float(roi_dbg.get("roi_hold_left_sec", 0.0)),
        "roi_switch_count": int(roi_dbg.get("roi_switch_count", 0)),
        "roi_range_s": int(roi_dbg.get("roi_range_s", s_auto)),
        "roi_range_e": int(roi_dbg.get("roi_range_e", e_auto)),
    }

    print("[PY] events=", len(events_all),
          "scheme=", scheme_tag,
          "roi=", f"{features['roi_state']}|{features['roi_reason']}",
          "presence=", f"{presence_score:.2f}",
          "ratio=", f"{float(state.get('presence_strength_ratio', 0.0)):.2f}",
          "bin=", effective_bin,
          "topk=", features.get("roi_topk_bins", []),
          "peak_baseline=", (None if peak_baseline is None else f"{peak_baseline:.2f}"),
          "baseline_frozen=", baseline_frozen,
          "freeze_reason=", state.get("baseline_freeze_reason", ""),
          "conf=", f"{breath_conf:.2f}", "src=", breath_src,
          "gate_ready=", gate_ready,
          "t_total=", f"{total_duration_sec:.1f}",
          "t_wall=", f"{wall_duration_sec:.1f}",
          "tst_eff=", f"{float(tst_eff_sec):.1f}",
          "q_w=", f"{float(q_w):.2f}", "tier=", q_tier,
          "awake=", bool(state.get("in_awake", False)),
          "drift=", f"{time_drift_sec:.1f}s",
          "ring_filled=", int(state.get("rt_filled", 0)),
          "ring_cap=", int(state.get("rt_cap_chirps", 0)),
          )

    return features, events_all


# =========================
# Report 离线解析入口
# =========================
def parse_rt_npz_for_report(npz_path: str) -> Dict[str, Any]:
    z = np.load(npz_path, allow_pickle=True)
    try:
        fs_env = _safe_float(z.get("fs_env", 0.0))
        total_duration_sec = _safe_float(z.get("total_duration_sec", 0.0))
        wall_sec = _safe_float(z.get("record_wall_sec", total_duration_sec))
        tst_valid_sec = _safe_float(z.get("tst_valid_sec", total_duration_sec))
        tst_eff_sec = _safe_float(z.get("tst_eff_sec", tst_valid_sec))

        events: List[Dict[str, Any]] = []
        if "evt_type" in z and "evt_start" in z and "evt_end" in z:
            evt_type = z["evt_type"].astype(np.int32)
            evt_start = z["evt_start"].astype(np.float32)
            evt_end = z["evt_end"].astype(np.float32)
            evt_conf = z["evt_conf"].astype(np.float32) if "evt_conf" in z else None

            inv = {1: "hypopnea", 2: "obstructive", 3: "central"}
            for i, (t, s, e) in enumerate(zip(evt_type, evt_start, evt_end)):
                ev = {
                    "type": inv.get(int(t), "unk"),
                    "start_sec": float(s),
                    "end_sec": float(e)
                }
                if evt_conf is not None and i < evt_conf.size:
                    ev["confidence"] = float(evt_conf[i])
                events.append(ev)

        ahi_soft = _compute_ahi_soft_with_ci(events, tst_eff_sec=tst_eff_sec, z=float(AHI_CI_Z))
        ahi_total = float(ahi_soft.get("ahi_total", 0.0))
        severity_idx, severity_name = _classify_severity(ahi_total)

        roi_state = None
        roi_reason = None
        try:
            if "roi_state" in z:
                roi_state = str(z["roi_state"][0])
            if "roi_reason" in z:
                roi_reason = str(z["roi_reason"][0])
        except Exception:
            pass

        return {
            "npz_path": npz_path,
            "fs_env": fs_env,
            "total_duration_sec": total_duration_sec,
            "record_wall_sec": wall_sec,
            "tst_valid_sec": tst_valid_sec,
            "tst_eff_sec": tst_eff_sec,

            "events": events,

            "ahi_total": float(ahi_total),
            "ahi_total_ci_low": float(ahi_soft.get("ahi_total_ci_low", 0.0)),
            "ahi_total_ci_high": float(ahi_soft.get("ahi_total_ci_high", 0.0)),

            "severity_name": severity_name,

            "roi_state": roi_state,
            "roi_reason": roi_reason,
        }
    finally:
        try:
            z.close()
        except Exception:
            pass


