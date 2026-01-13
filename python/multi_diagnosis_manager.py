# -*- coding: utf-8 -*-
"""
multi_diagnosis_manager.py

多人轮流测（同一设备轮流测多人）的会话管理壳：
- ✅ 不改 signal_processor.py 内部任何诊断逻辑
- ✅ 每个人一个独立 stream_id -> signal_processor 内部 _STREAMS 隔离状态
- ✅ 提供：start/switch/process/finalize/reset/list 等接口
- ✅ Kotlin 端依旧传 rxPcm/txChirp/chirpsPerFrame/sampleRate/save_dir
"""

import time
import json
import os
from dataclasses import dataclass, asdict
from typing import Dict, Any, Optional, Sequence, Tuple, List

# 直接复用你现有单人诊断（完全不动它）
import signal_processor as sp


# =========================
# 配置
# =========================
_DEFAULT_SESSION_TTL_SEC = 24 * 3600  # 会话记录保留（管理层元数据），不影响 sp 的 ring
_SAFE_ID_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-:@."


def _sanitize_id(x: str, fallback: str = "unknown") -> str:
    if x is None:
        return fallback
    s = str(x).strip()
    if not s:
        return fallback
    # 只保留安全字符，避免文件名/路径/日志问题
    out = []
    for ch in s:
        out.append(ch if ch in _SAFE_ID_CHARS else "_")
    return "".join(out)[:128]


def _now() -> float:
    return time.time()


def _make_stream_id(session_id: str, person_id: str) -> str:
    # stream_id 是 sp 的 key：只要不同就能隔离所有 state
    sid = _sanitize_id(session_id, "sess")
    pid = _sanitize_id(person_id, "p")
    return f"{sid}__{pid}"


def _stream_exists(stream_id: str) -> bool:
    try:
        return stream_id in getattr(sp, "_STREAMS", {})
    except Exception:
        return False


def _reset_stream(stream_id: str) -> bool:
    """
    清掉 signal_processor 里的单人流状态（不改 signal_processor 代码，通过访问模块全局变量实现）
    """
    try:
        streams = getattr(sp, "_STREAMS", None)
        if isinstance(streams, dict) and stream_id in streams:
            del streams[stream_id]
            return True
        return False
    except Exception:
        return False


def _safe_get_feature(features: Dict[str, Any], k: str, default=None):
    try:
        if features is None:
            return default
        v = features.get(k, default)
        return v
    except Exception:
        return default


@dataclass
class PersonSession:
    session_id: str
    person_id: str
    stream_id: str

    created_ts: float
    last_seen_ts: float

    # 最近一次 process 的核心快照（便于 finalize 输出 summary）
    last_features: Optional[Dict[str, Any]] = None
    last_event_count: int = 0
    total_frames: int = 0

    # 可选：标记是否“当前正在测”
    active: bool = False


# 管理层：一个设备/一次多人轮测 -> 一个 session_id
_SESSIONS: Dict[str, Dict[str, PersonSession]] = {}        # session_id -> person_id -> PersonSession
_ACTIVE_PERSON: Dict[str, str] = {}                        # session_id -> active person_id
_SESSION_META: Dict[str, Dict[str, Any]] = {}              # session_id -> meta


# =========================
# 对外 API
# =========================
def start_multi_session(session_id: str, meta: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    """
    开始一轮“多人轮测”的会话（管理层）。
    """
    sid = _sanitize_id(session_id, "sess")
    if sid not in _SESSIONS:
        _SESSIONS[sid] = {}
        _SESSION_META[sid] = {
            "created_ts": _now(),
            "meta": meta or {},
        }
        _ACTIVE_PERSON[sid] = ""
    return {
        "session_id": sid,
        "created_ts": _SESSION_META[sid]["created_ts"],
        "meta": _SESSION_META[sid]["meta"],
        "persons": list(_SESSIONS[sid].keys()),
        "active_person": _ACTIVE_PERSON.get(sid, ""),
    }


def list_persons(session_id: str) -> List[str]:
    sid = _sanitize_id(session_id, "sess")
    return sorted(list(_SESSIONS.get(sid, {}).keys()))


def get_active_person(session_id: str) -> str:
    sid = _sanitize_id(session_id, "sess")
    return str(_ACTIVE_PERSON.get(sid, "") or "")


def ensure_person(session_id: str, person_id: str) -> Dict[str, Any]:
    """
    确保某个人在本次 session 里有独立 stream。
    """
    sid = _sanitize_id(session_id, "sess")
    pid = _sanitize_id(person_id, "p")
    if sid not in _SESSIONS:
        start_multi_session(sid)

    persons = _SESSIONS[sid]
    if pid not in persons:
        stream_id = _make_stream_id(sid, pid)
        persons[pid] = PersonSession(
            session_id=sid,
            person_id=pid,
            stream_id=stream_id,
            created_ts=_now(),
            last_seen_ts=_now(),
            last_features=None,
            last_event_count=0,
            total_frames=0,
            active=False,
        )

    p = persons[pid]
    return {
        "session_id": sid,
        "person_id": pid,
        "stream_id": p.stream_id,
        "created_ts": p.created_ts,
        "last_seen_ts": p.last_seen_ts,
        "active": p.active,
        "stream_exists_in_signal_processor": _stream_exists(p.stream_id),
    }


def switch_person(session_id: str, person_id: str, finalize_prev: bool = True,
                  save_dir: Optional[str] = None, reset_prev_stream: bool = False) -> Dict[str, Any]:
    """
    切人：
    - finalize_prev=True：把上一个人的 summary 输出（可选落盘）
    - reset_prev_stream=True：把上一个人的 sp._STREAMS[stream_id] 清掉（节省内存；下次测该人会重新 warmup）
      注意：如果你希望“同一个人下次继续测接着来”，就把 reset_prev_stream=False
    """
    sid = _sanitize_id(session_id, "sess")
    pid = _sanitize_id(person_id, "p")
    if sid not in _SESSIONS:
        start_multi_session(sid)

    prev_pid = get_active_person(sid)
    prev_summary = None

    if prev_pid and prev_pid != pid:
        if finalize_prev:
            prev_summary = finalize_person(sid, prev_pid, save_dir=save_dir, reset_stream=reset_prev_stream)
        else:
            # 只是标记 inactive
            try:
                _SESSIONS[sid][prev_pid].active = False
            except Exception:
                pass

    ensure_person(sid, pid)
    _ACTIVE_PERSON[sid] = pid

    # 标记 active 状态
    for ppp in _SESSIONS[sid].values():
        ppp.active = (ppp.person_id == pid)

    return {
        "session_id": sid,
        "active_person": pid,
        "prev_person": prev_pid,
        "prev_summary": prev_summary,
        "persons": list_persons(sid),
    }


def process_fmcw_frame_stream_multi(
        session_id: str,
        person_id: str,
        rxPcm: Sequence[int],
        txChirp: Sequence[int],
        chirpsPerFrame: int,
        sampleRate: int = 48000,
        save_dir: Optional[str] = None,
        auto_switch: bool = True,
) -> Tuple[Dict[str, Any], List[Dict[str, Any]]]:
    """
    多人轮测版 process：
    - 本质：把 (session_id, person_id) 映射成一个稳定的 stream_id
    - 然后原封不动调用 sp.process_fmcw_frame_stream(...)，返回值完全一致
    """
    sid = _sanitize_id(session_id, "sess")
    pid = _sanitize_id(person_id, "p")
    if sid not in _SESSIONS:
        start_multi_session(sid)

    ensure_person(sid, pid)

    if auto_switch:
        # 如果上层没显式切人，这里帮你“当前人=正在测的人”
        if get_active_person(sid) != pid:
            switch_person(sid, pid, finalize_prev=False)

    p = _SESSIONS[sid][pid]
    p.last_seen_ts = _now()
    p.total_frames += 1
    p.active = True

    features, events = sp.process_fmcw_frame_stream(
        stream_id=p.stream_id,
        rxPcm=rxPcm,
        txChirp=txChirp,
        chirpsPerFrame=chirpsPerFrame,
        sampleRate=sampleRate,
        save_dir=save_dir,
    )

    # 记录最后快照（便于 finalize summary）
    try:
        p.last_features = features
        p.last_event_count = int(len(events) if events is not None else 0)
    except Exception:
        pass

    return features, events


def finalize_person(session_id: str, person_id: str,
                    save_dir: Optional[str] = None,
                    reset_stream: bool = False) -> Dict[str, Any]:
    """
    结束某个人的一段测量，输出一个 summary（可选落盘 json），并可选清理 sp 的 stream 状态。
    """
    sid = _sanitize_id(session_id, "sess")
    pid = _sanitize_id(person_id, "p")
    persons = _SESSIONS.get(sid, {})
    if pid not in persons:
        return {"ok": False, "reason": "person_not_found", "session_id": sid, "person_id": pid}

    p = persons[pid]
    feat = p.last_features or {}

    summary = {
        "ok": True,
        "session_id": sid,
        "person_id": pid,
        "stream_id": p.stream_id,
        "created_ts": p.created_ts,
        "last_seen_ts": p.last_seen_ts,
        "total_frames": int(p.total_frames),
        "last_event_count": int(p.last_event_count),

        # 从 features 抽取你关心的核心结果（不影响原 features 返回）
        "ahi_total": float(_safe_get_feature(feat, "ahi_total", 0.0) or 0.0),
        "ahi_total_ci_low": float(_safe_get_feature(feat, "ahi_total_ci_low", 0.0) or 0.0),
        "ahi_total_ci_high": float(_safe_get_feature(feat, "ahi_total_ci_high", 0.0) or 0.0),
        "ahi_level_idx": int(_safe_get_feature(feat, "ahi_level_idx", 0) or 0),
        "ahi_level_name": str(_safe_get_feature(feat, "ahi_level_name", "" ) or ""),
        "tst_eff_sec": float(_safe_get_feature(feat, "ahi_eff_valid_sec", 0.0) or 0.0),
        "tst_valid_sec": float(_safe_get_feature(feat, "ahi_valid_sec", 0.0) or 0.0),
        "presence_score": float(_safe_get_feature(feat, "presence_score", 0.0) or 0.0),
        "quality_index": float(_safe_get_feature(feat, "quality_index", 0.0) or 0.0),
        "quality_weight": float(_safe_get_feature(feat, "quality_weight", 0.0) or 0.0),
        "quality_tier": str(_safe_get_feature(feat, "quality_tier", "" ) or ""),
        "turn_active": bool(_safe_get_feature(feat, "turn_active", False)),
        "roi_state": str(_safe_get_feature(feat, "roi_state", "" ) or ""),
        "roi_reason": str(_safe_get_feature(feat, "roi_reason", "" ) or ""),
        "target_range_bin": int(_safe_get_feature(feat, "target_range_bin", -1) or -1),
        "diagnosis": str(_safe_get_feature(feat, "diagnosis", "" ) or ""),
        "rt_npz_saved": _safe_get_feature(feat, "rt_npz_saved", None),
        "time_total_sec": float(_safe_get_feature(feat, "time_total_sec", 0.0) or 0.0),
        "time_drift_sec": float(_safe_get_feature(feat, "time_drift_sec", 0.0) or 0.0),
    }

    # 可选落盘
    saved_path = None
    if save_dir:
        try:
            os.makedirs(save_dir, exist_ok=True)
            fname = f"multi_summary_{sid}__{pid}_{int(_now())}.json"
            fpath = os.path.join(save_dir, fname)
            with open(fpath, "w", encoding="utf-8") as f:
                json.dump(summary, f, ensure_ascii=False, indent=2)
            saved_path = fpath
        except Exception as e:
            summary["save_error"] = str(e)

    summary["summary_saved"] = saved_path

    # 标记 inactive
    p.active = False

    # 可选清理 sp 内部状态（节省内存）
    if reset_stream:
        summary["reset_stream_ok"] = bool(_reset_stream(p.stream_id))
    else:
        summary["reset_stream_ok"] = False

    return summary


def finalize_all(session_id: str,
                 save_dir: Optional[str] = None,
                 reset_stream: bool = False) -> Dict[str, Any]:
    """
    结束整轮多人会话，把所有人都 finalize。
    """
    sid = _sanitize_id(session_id, "sess")
    persons = _SESSIONS.get(sid, {})
    out = []
    for pid in list(persons.keys()):
        out.append(finalize_person(sid, pid, save_dir=save_dir, reset_stream=reset_stream))
    return {
        "session_id": sid,
        "count": len(out),
        "summaries": out,
    }


def cleanup_session(session_id: str,
                    hard_reset_streams: bool = True) -> Dict[str, Any]:
    """
    删除管理层 session，并可选把 signal_processor 里所有对应 stream 都清掉。
    """
    sid = _sanitize_id(session_id, "sess")
    persons = _SESSIONS.get(sid, {})
    reset_ok = []
    if hard_reset_streams:
        for p in persons.values():
            reset_ok.append((p.person_id, bool(_reset_stream(p.stream_id))))

    _SESSIONS.pop(sid, None)
    _ACTIVE_PERSON.pop(sid, None)
    _SESSION_META.pop(sid, None)

    return {
        "session_id": sid,
        "hard_reset_streams": bool(hard_reset_streams),
        "reset_results": reset_ok,
        "ok": True,
    }


def gc_idle_sessions(ttl_sec: float = _DEFAULT_SESSION_TTL_SEC,
                     reset_streams: bool = False) -> Dict[str, Any]:
    """
    管理层 GC：把长时间不活跃的 session 清掉。
    （默认不动 sp._STREAMS，避免误删正在用的）
    """
    now = _now()
    removed = []
    for sid, persons in list(_SESSIONS.items()):
        last_seen = 0.0
        for p in persons.values():
            last_seen = max(last_seen, float(p.last_seen_ts or 0.0))
        if last_seen <= 0:
            last_seen = float(_SESSION_META.get(sid, {}).get("created_ts", 0.0))
        if now - last_seen >= float(ttl_sec):
            removed.append(sid)
            cleanup_session(sid, hard_reset_streams=reset_streams)

    return {"removed": removed, "count": len(removed), "reset_streams": bool(reset_streams)}
