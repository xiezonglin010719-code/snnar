# -*- coding: utf-8 -*-
# app/src/main/python/breath_npz_bridge.py

import os
import io
import uuid
import zipfile
import numpy as np

# -------------------------
# 全局句柄表
# -------------------------
_HANDLES = {}

# 默认：5min = 10 个 30s window
_DEFAULT_STEP_WINDOWS = 10


def _extract_npy_from_npz(npz_path: str, x_key: str, out_dir: str) -> str:
    """
    NPZ 是 zip，里面是 X.npy / y.npy ...
    这里把目标 X.npy 解压到 out_dir，返回解压后的 npy 路径
    """
    os.makedirs(out_dir, exist_ok=True)
    target_name = f"{x_key}.npy"

    with zipfile.ZipFile(npz_path, "r") as zf:
        # 兼容 "X.npy" 或者 "xxx/X.npy"
        cand = None
        for name in zf.namelist():
            if name.endswith("/" + target_name) or name == target_name:
                cand = name
                break
        if cand is None:
            raise RuntimeError(f"NPZ 中找不到 {target_name}")

        out_path = os.path.join(out_dir, target_name)
        with zf.open(cand) as fin, open(out_path, "wb") as fout:
            fout.write(fin.read())
        return out_path


def open_npz(android_ctx, npz_path: str, x_key: str = "X") -> str:
    """
    Kotlin: m.callAttr("open_npz", ctx, npzPath, xKey)
    返回 handleId（string）
    """
    # 用 cache 子目录存解压出来的 npy
    cache_dir = android_ctx.getCacheDir().getAbsolutePath()
    hid = str(uuid.uuid4())
    work_dir = os.path.join(cache_dir, f"npz_{hid}")

    x_npy_path = _extract_npy_from_npz(npz_path, x_key, work_dir)

    # mmap 读，不把整夜 X 全读进内存
    X = np.load(x_npy_path, mmap_mode="r")  # ndarray, memmap

    _HANDLES[hid] = {
        "work_dir": work_dir,
        "x_npy_path": x_npy_path,
        "X": X,
        "x_key": x_key,
        "step_windows": _DEFAULT_STEP_WINDOWS,
    }
    return hid


def segmentcount(handle_id: str) -> int:
    """
    Kotlin: m.callAttr("segmentcount", handleId)
    只收 1 个参数，避免你之前 TypeError。

    这里返回 “段数”，而不是 N（窗口数）。
    段划分规则：每段 step_windows 个 30s window（默认 10 -> 5min）
    """
    h = _HANDLES.get(handle_id)
    if h is None:
        raise RuntimeError(f"bad handle: {handle_id}")
    X = h["X"]
    N = int(X.shape[0])
    step = int(h.get("step_windows", _DEFAULT_STEP_WINDOWS))
    if step <= 0:
        step = _DEFAULT_STEP_WINDOWS
    return int((N + step - 1) // step)  # ceil(N/step)


def _ensure_4d(x: np.ndarray) -> np.ndarray:
    """
    支持：
      [N,C,H,W]
      [N,H,W] -> [N,1,H,W]
    """
    if x.ndim == 4:
        return x
    if x.ndim == 3:
        return x[:, None, :, :]
    raise RuntimeError(f"X ndim={x.ndim} not supported, expect 3D/4D")


def _fix_channels(x: np.ndarray, C: int) -> np.ndarray:
    """
    x: [M,c,h,w] -> [M,C,h,w]
    """
    m, c, h, w = x.shape
    if c == C:
        return x
    if c > C:
        return x[:, :C, :, :]
    # c < C: 重复最后一个通道补齐
    pad = np.repeat(x[:, -1:, :, :], repeats=(C - c), axis=1)
    return np.concatenate([x, pad], axis=1)


def _center_crop_pad_2d(img: np.ndarray, H: int, W: int) -> np.ndarray:
    """
    img: [h,w] -> [H,W] center crop or pad(0)
    """
    h, w = img.shape

    # crop/pad height
    if h >= H:
        y0 = (h - H) // 2
        img = img[y0:y0 + H, :]
    else:
        pad = H - h
        pt = pad // 2
        pb = pad - pt
        img = np.pad(img, ((pt, pb), (0, 0)), mode="constant", constant_values=0.0)

    # crop/pad width
    h2, w2 = img.shape
    if w2 >= W:
        x0 = (w2 - W) // 2
        img = img[:, x0:x0 + W]
    else:
        pad = W - w2
        pl = pad // 2
        pr = pad - pl
        img = np.pad(img, ((0, 0), (pl, pr)), mode="constant", constant_values=0.0)

    return img.astype(np.float32, copy=False)


def _pack_segment_to_KCHW(seg: np.ndarray, K: int, C: int, H: int, W: int) -> np.ndarray:
    """
    seg: [M,c,h,w] -> out: [K,C,H,W]
    规则：把 M 均匀采样/重复到 K，再做 center crop/pad 到 64
    """
    seg = _ensure_4d(seg).astype(np.float32, copy=False)
    seg = _fix_channels(seg, C)

    M = int(seg.shape[0])
    if M <= 0:
        raise RuntimeError("empty segment")

    # 均匀采样到 K（M 可能是 10；K=96 会大量重复，这符合“5min 输出一次”的pack方案）
    if M == 1:
        idx = np.zeros((K,), dtype=np.int64)
    else:
        idx = np.linspace(0, M - 1, num=K).astype(np.int64)

    out = np.empty((K, C, H, W), dtype=np.float32)

    for kk in range(K):
        src = seg[int(idx[kk])]  # [C,h,w]
        for cc in range(C):
            out[kk, cc] = _center_crop_pad_2d(src[cc], H, W)

    return out


def read_segment_float32_flat(handle_id: str, seg_idx: int, K: int, C: int, H: int, W: int):
    """
    Kotlin: read_segment_float32_flat(handleId, segIdx, K, C, H, W)

    ✅ seg_idx 是“段索引”
    ✅ start = seg_idx * step_windows
    ✅ 读出 [M,C,224,224]（或别的），pack 成 [K,C,64,64]
    ✅ 返回 list[float] 便于 Kotlin toJava(FloatArray) 成功
    """
    h = _HANDLES.get(handle_id)
    if h is None:
        raise RuntimeError(f"bad handle: {handle_id}")

    X = h["X"]
    X = _ensure_4d(X)
    N = int(X.shape[0])

    step = int(h.get("step_windows", _DEFAULT_STEP_WINDOWS))
    if step <= 0:
        step = _DEFAULT_STEP_WINDOWS

    seg_idx = int(seg_idx)
    start = seg_idx * step
    if start >= N:
        raise RuntimeError(f"seg_idx out of range: seg_idx={seg_idx}, start={start} n={N} step={step}")

    end = min(N, start + step)
    seg = X[start:end]  # [M,c,h,w], M<=step

    packed = _pack_segment_to_KCHW(seg, int(K), int(C), int(H), int(W))  # [K,C,H,W]

    # 返回扁平 list[float] -> Kotlin 可 toJava(FloatArray)
    return packed.reshape(-1).astype(np.float32, copy=False).tolist()


def close(handle_id: str):
    """
    Kotlin: m.callAttr("close", handleId)
    """
    h = _HANDLES.pop(handle_id, None)
    if h is None:
        return

    # 尽量释放 memmap
    try:
        X = h.get("X", None)
        if X is not None:
            # memmap close
            try:
                X._mmap.close()
            except Exception:
                pass
    except Exception:
        pass

    # 删除解压目录
    work_dir = h.get("work_dir")
    if work_dir and os.path.isdir(work_dir):
        try:
            for root, dirs, files in os.walk(work_dir, topdown=False):
                for f in files:
                    try:
                        os.remove(os.path.join(root, f))
                    except Exception:
                        pass
                for d in dirs:
                    try:
                        os.rmdir(os.path.join(root, d))
                    except Exception:
                        pass
            os.rmdir(work_dir)
        except Exception:
            pass
