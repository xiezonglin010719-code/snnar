# #步骤1
# import numpy as np
#
# def generate_swept_sinusoid(
#     f_start: float = 18000.0,
#     f_end: float = 22000.0,
#     sweep_duration: float = 0.01075,
#     sample_rate: int = 48000
# ) -> np.ndarray:
#     """生成单个扫频周期信号，返回16位PCM格式"""
#     n_samples = int(sample_rate * sweep_duration)
#     t = np.linspace(0, sweep_duration, n_samples, endpoint=False)
#     # 线性扫频公式
#     freq = f_start + (f_end - f_start) * t / sweep_duration
#     phase = 2 * np.pi * np.cumsum(freq) / sample_rate  # 积分计算相位
#     signal = np.sin(phase)  # 生成正弦信号
#     # 归一化并转换为16位PCM（安卓扬声器要求）
#     signal_norm = signal / np.max(np.abs(signal))  # 归一化到[-1,1]
#     return (signal_norm * 32767).astype(np.int16)  # 转换为int16
#
# def generate_continuous_signal(
#     single_sweep: np.ndarray,
#     total_duration: float = 10.0,
#     sample_rate: int = 48000
# ) -> np.ndarray:
#     """生成连续扫频信号（循环拼接单个周期）"""
#     n_total = int(sample_rate * total_duration)
#     n_single = len(single_sweep)
#     # 循环拼接并截取到目标长度
#     return np.tile(single_sweep, (n_total // n_single) + 1)[:n_total]


import time
import os
import numpy as np

def generate_swept_sinusoid(
        f_start: float = 18000.0,
        f_end: float = 22000.0,
        sweep_duration: float = 0.01075,
        sample_rate: int = 48000
) -> np.ndarray:
    """生成单个扫频周期信号，返回16位PCM格式"""
    n_samples = int(sample_rate * sweep_duration)
    t = np.linspace(0, sweep_duration, n_samples, endpoint=False)
    freq = f_start + (f_end - f_start) * t / sweep_duration
    phase = 2 * np.pi * np.cumsum(freq) / sample_rate
    signal = np.sin(phase)
    signal_norm = signal / np.max(np.abs(signal))
    return (signal_norm * 32767).astype(np.int16)


def generate_sonar_sweep(
        f_start: float = 18000.0,
        f_end: float = 22000.0,
        sweep_duration: float = 0.01075,
        sample_rate: int = 48000,
        phase_offset: float = 0.0
) -> np.ndarray:
    """单周期扫频波，和上面类似，只是多了相位偏移"""
    n_samples = int(sample_rate * sweep_duration)
    t = np.linspace(0, sweep_duration, n_samples, endpoint=False)
    freq = f_start + (f_end - f_start) * t / sweep_duration
    phase = 2 * np.pi * np.cumsum(freq) / sample_rate + phase_offset
    signal = np.sin(phase)
    return (signal / np.max(np.abs(signal)) * 32767).astype(np.int16)



def generate_swept_sinusoid(f_start=18000.0, f_end=20000.0, duration=0.01075, sample_rate=48000):
    n = int(round(duration * sample_rate))
    t = np.arange(n) / sample_rate
    # 线性扫频相位：phi(t) = 2π (f0 t + 0.5 k t^2), k = (f1-f0)/T
    k = (f_end - f_start) / duration
    phase = 2 * np.pi * (f_start * t + 0.5 * k * t * t)
    sig = 0.9 * np.sin(phase)  # 适当留裕量避免削顶
    # 转 int16
    return (sig * 32767.0).astype(np.int16)


def generate_continuous_sonar_segment(
        single_sweep: np.ndarray,
        segment_duration: float = 2.0,
        sample_rate: int = 48000,
        phase_offset: float = 0.0
) -> np.ndarray:
    """
    生成一小段（默认2秒）连续声纳，方便端上循环写 AudioTrack。
    """
    n_total_samples = int(sample_rate * segment_duration)
    n_sweep_samples = len(single_sweep)
    n_cycles = (n_total_samples // n_sweep_samples) + 1

    continuous_segment = []
    for cycle_idx in range(n_cycles):
        cycle = generate_sonar_sweep(
            f_start=18000.0,
            f_end=22000.0,
            sweep_duration=n_sweep_samples / sample_rate,
            sample_rate=sample_rate,
            phase_offset=phase_offset
        )
        continuous_segment.append(cycle)

    return np.concatenate(continuous_segment)[:n_total_samples]


def generate_continuous_sonar(
        single_sweep: np.ndarray,
        total_duration: float = 3600.0,
        sample_rate: int = 48000,
        phase_sync_interval: int = 100
) -> np.ndarray:
    """
    生成超长的连续声纳（比如夜间监测）。通常不用在手机端一次性生成这么长，
    但保留这个接口。
    """
    n_total = int(sample_rate * total_duration)
    n_sweep = len(single_sweep)
    n_cycles = (n_total // n_sweep) + 1
    continuous = []
    for i in range(n_cycles):
        phase_offset = 0.0 if i % phase_sync_interval == 0 else np.pi * 0.1
        cycle = generate_sonar_sweep(
            f_start=18000.0,
            f_end=22000.0,
            sweep_duration=n_sweep / sample_rate,
            sample_rate=sample_rate,
            phase_offset=phase_offset
        )
        continuous.append(cycle)
    return np.concatenate(continuous)[:n_total]


def create_sonar_stream_generator(
        f_start: float = 18000.0,
        f_end: float = 22000.0,
        sweep_duration: float = 0.01075,
        sample_rate: int = 48000,
        chunk_size: int = 512
):
    """
    实时流式生成的版本，用不到可以不管。
    """
    n_sweep_samples = int(sample_rate * sweep_duration)
    current_phase = 0.0

    while True:
        t = np.linspace(0, sweep_duration, n_sweep_samples, endpoint=False)
        freq = f_start + (f_end - f_start) * t / sweep_duration
        phase = 2 * np.pi * np.cumsum(freq) / sample_rate + current_phase
        sweep = np.sin(phase)
        sweep = (sweep / np.max(np.abs(sweep)) * 32767).astype(np.int16)

        for i in range(0, len(sweep), chunk_size):
            chunk = sweep[i:i + chunk_size]
            if len(chunk) < chunk_size:
                chunk = np.pad(chunk, (0, chunk_size - len(chunk)), mode="constant")
            yield chunk

        current_phase = phase[-1] % (2 * np.pi)


# ============================================================
# ✅ 关键补丁：给 Kotlin 用的名字
# ============================================================
def generate_continuous_signal(
        single_sweep: np.ndarray,
        total_duration: float = 10.0,
        sample_rate: int = 48000
) -> np.ndarray:
    """
    Kotlin 里叫的就是这个名字，我们这里兼容一下。
    实现策略：
      - 如果 total_duration <= 2s，就走 2s 小段
      - 否则就生成一个 2s 小段，让 Kotlin 去循环写
    """
    if total_duration <= 2.0:
        return generate_continuous_sonar_segment(
            single_sweep=single_sweep,
            segment_duration=total_duration,
            sample_rate=sample_rate,
        )
    else:
        # 手机端其实不会真要一次10秒/1小时，我们返回2秒即可，让端上循环写
        return generate_continuous_sonar_segment(
            single_sweep=single_sweep,
            segment_duration=2.0,
            sample_rate=sample_rate,
        )
