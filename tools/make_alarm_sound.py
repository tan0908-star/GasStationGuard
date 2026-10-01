# -*- coding: utf-8 -*-
"""
================================================================
 生成报警提示音（WAV）
 项目：加油站夜班值守车辆报警 APP
 阶段：M3
================================================================

【为什么不用系统铃声】
  系统默认闹钟铃声存在两个问题：
    1. 用户可能把它设成"无"，这样报警就完全没声音了 —— 直接导致漏报
    2. 不同手机的音色、时长、循环方式不可控
  内置一段自产的双音警报，音色和循环点都是确定的，可测试。

【声音设计】
  - 两个音高交替（950Hz / 1400Hz），这是火警/警报器的经典双音，
    人耳在这种交替音上最容易惊醒，也最容易从噪音里分辨出来
  - 每个音 0.3 秒，共 8 段 = 2.4 秒，循环无缝
  - 满幅（不衰减），手机拉到最大音量时足够响
  - 段间加 5ms 淡入淡出，避免爆音（爆音听起来像故障，反而不易被当真）

用法：
  python tools/make_alarm_sound.py
输出：
  app/src/main/res/raw/alarm_loop.wav
"""

import sys
import wave
from pathlib import Path

import numpy as np

PROJECT_ROOT = Path(__file__).resolve().parent.parent
OUT_PATH = PROJECT_ROOT / "app" / "src" / "main" / "res" / "raw" / "alarm_loop.wav"

SAMPLE_RATE = 44100
TONE_HIGH = 1400.0     # Hz
TONE_LOW = 950.0       # Hz
SEGMENT_SECONDS = 0.30
SEGMENTS = 8           # 高-低-高-低…… 共 2.4 秒
AMPLITUDE = 0.95       # 留一点余量，避免削顶失真
FADE_SECONDS = 0.005   # 每段首尾淡入淡出，防爆音


def make_segment(freq: float) -> np.ndarray:
    n = int(SAMPLE_RATE * SEGMENT_SECONDS)
    t = np.arange(n) / SAMPLE_RATE

    # 基频 + 一点三次谐波，听起来更"刺耳"（有利于叫醒），但不是方波那么难听
    wave_data = np.sin(2 * np.pi * freq * t) + 0.25 * np.sin(2 * np.pi * freq * 3 * t)
    wave_data /= np.max(np.abs(wave_data))

    # 淡入淡出
    fade_n = int(SAMPLE_RATE * FADE_SECONDS)
    if fade_n > 0:
        envelope = np.ones(n)
        envelope[:fade_n] = np.linspace(0, 1, fade_n)
        envelope[-fade_n:] = np.linspace(1, 0, fade_n)
        wave_data *= envelope

    return wave_data


def main() -> int:
    parts = []
    for i in range(SEGMENTS):
        parts.append(make_segment(TONE_HIGH if i % 2 == 0 else TONE_LOW))

    samples = np.concatenate(parts) * AMPLITUDE
    pcm = (samples * 32767).astype(np.int16)

    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(OUT_PATH), "wb") as f:
        f.setnchannels(1)          # 单声道
        f.setsampwidth(2)          # 16 bit
        f.setframerate(SAMPLE_RATE)
        f.writeframes(pcm.tobytes())

    duration = len(pcm) / SAMPLE_RATE
    print(f"已生成: {OUT_PATH}")
    print(f"  {duration:.2f} 秒 / {SAMPLE_RATE}Hz / 16bit 单声道 / "
          f"{OUT_PATH.stat().st_size / 1024:.0f} KB")
    print(f"  音序: " + " ".join(
        f"{'高' if i % 2 == 0 else '低'}" for i in range(SEGMENTS)
    ))
    return 0


if __name__ == "__main__":
    sys.exit(main())
