# npz_insights.py
import json
import numpy as np

def summarize_npz(path: str) -> str:
    """
    返回 JSON 字符串，Kotlin 侧直接 parse.
    你需要按你真实 npz key 名改下面的候选列表。
    """
    data = np.load(path, allow_pickle=True)

    def pick(keys):
        for k in keys:
            if k in data:
                v = data[k]
                # 转成 python 标量/列表，避免 numpy 类型 json 不可序列化
                if isinstance(v, np.ndarray):
                    return v
                return np.array(v)
        return None

    # 你项目里常见的候选命名（你可以按实际再加）
    ahi = pick(["ahi", "ahi_est", "ahi_est_base", "AHI"])
    event_durs = pick(["event_durations", "event_durs", "apnea_durations_sec"])
    event_cnt = pick(["event_count", "num_events", "events_n"])
    valid_sec = pick(["valid_sec", "tst_eff_sec", "effective_sleep_sec"])

    out = {}
    if ahi is not None:
        ahi_val = float(np.mean(ahi)) if np.size(ahi) > 1 else float(ahi.reshape(-1)[0])
        out["ahi"] = ahi_val

    if event_durs is not None:
        d = event_durs.astype(float).reshape(-1)
        out["event_count"] = int(d.size)
        out["event_dur_mean_sec"] = float(np.mean(d)) if d.size else 0.0
        out["event_dur_p95_sec"] = float(np.percentile(d, 95)) if d.size else 0.0
        out["event_dur_max_sec"] = float(np.max(d)) if d.size else 0.0

    if event_cnt is not None and "event_count" not in out:
        out["event_count"] = int(np.mean(event_cnt))

    if valid_sec is not None:
        out["valid_sleep_min"] = float(np.mean(valid_sec)) / 60.0

    out["keys"] = list(data.keys())
    return json.dumps(out, ensure_ascii=False)
