# -*- coding: utf-8 -*-
"""
================================================================
 在电脑上验证导出的 int8 TFLite 模型
 项目：加油站夜班值守车辆报警 APP
 阶段：M2
================================================================

作用：不接手机也能确认「模型 + 预处理 + 解码 + NMS」这一整套是对的。

它把安卓端 YoloDetector.kt 里的算法在 Python 里原样实现一遍：
  letterbox 到 640x640 → 量化填输入 → 推理 → 反量化 → 解码 → NMS
然后把框和类别打印出来。

⚠️ 注意：这里验证的是【算法和模型】是否正确，
   不能代替真机验证（真机还要验相机的旋转、坐标映射、性能）。

用法：
  python tools/verify_tflite.py --dir datasets/coco128/images/train2017 --count 30
  python tools/verify_tflite.py --image some_car_photo.jpg
"""

import argparse
import sys
from pathlib import Path

import numpy as np

# Windows 中文控制台默认是 GBK，直接 print 表情符号会抛 UnicodeEncodeError。
# 强制走 UTF-8，遇到打不出的字符用 ? 代替，保证脚本不会因为"打印"而崩掉。
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:                                              # noqa: BLE001
    pass

# 和安卓端 YoloDetector.kt 里保持完全一致
TARGET_CLASSES = {2: "汽车", 3: "摩托车", 5: "客车", 7: "卡车"}
CONF_THRESHOLD = 0.35
IOU_THRESHOLD = 0.45


def load_image(path: Path) -> np.ndarray:
    """读成 RGB uint8 数组 (H, W, 3)。用 TensorFlow 读取，避免额外依赖。"""
    import tensorflow as tf

    raw = tf.io.read_file(str(path))
    img = tf.io.decode_image(raw, channels=3, expand_animations=False)
    return img.numpy().astype(np.uint8)


def letterbox(img: np.ndarray, size: int):
    """等比缩放 + 居中补灰边。返回 (画布, 缩放比, 左偏移, 上偏移)。"""
    h, w = img.shape[:2]
    scale = min(size / w, size / h)
    new_w, new_h = max(1, int(round(w * scale))), max(1, int(round(h * scale)))

    # 最近邻缩放（验证用足够了，安卓端用的是双线性）
    ys = np.clip((np.arange(new_h) / scale).astype(np.int32), 0, h - 1)
    xs = np.clip((np.arange(new_w) / scale).astype(np.int32), 0, w - 1)
    resized = img[ys][:, xs]

    canvas = np.full((size, size, 3), 114, dtype=np.uint8)
    dx, dy = (size - new_w) // 2, (size - new_h) // 2
    canvas[dy:dy + new_h, dx:dx + new_w] = resized
    return canvas, scale, dx, dy


def iou(a, b) -> float:
    left, top = max(a[0], b[0]), max(a[1], b[1])
    right, bottom = min(a[2], b[2]), min(a[3], b[3])
    if right <= left or bottom <= top:
        return 0.0
    inter = (right - left) * (bottom - top)
    area_a = (a[2] - a[0]) * (a[3] - a[1])
    area_b = (b[2] - b[0]) * (b[3] - b[1])
    union = area_a + area_b - inter
    return inter / union if union > 0 else 0.0


def nms(dets):
    dets = sorted(dets, key=lambda d: -d["conf"])
    kept = []
    for d in dets:
        if all(d["cls"] != k["cls"] or iou(d["box"], k["box"]) <= IOU_THRESHOLD for k in kept):
            kept.append(d)
    return kept


def run(interp, img: np.ndarray, size: int, debug=False):
    inp = interp.get_input_details()[0]
    out = interp.get_output_details()[0]
    in_scale, in_zp = inp["quantization"]
    out_scale, out_zp = out["quantization"]

    canvas, scale, dx, dy = letterbox(img, size)

    # ---- 量化填输入 ----
    if inp["dtype"] == np.int8:
        q = np.round(canvas.astype(np.float32) / 255.0 / in_scale) + in_zp
        tensor = np.clip(q, -128, 127).astype(np.int8)[None, ...]
    else:
        tensor = (canvas.astype(np.float32) / 255.0)[None, ...].astype(inp["dtype"])

    interp.set_tensor(inp["index"], tensor)
    interp.invoke()
    raw = interp.get_tensor(out["index"])[0]           # [84, 8400]

    # ---- 反量化 ----
    if out["dtype"] == np.int8:
        pred = (raw.astype(np.float32) - out_zp) * out_scale
    else:
        pred = raw.astype(np.float32)

    num_ch = pred.shape[0]
    num_anchor = pred.shape[1]
    if debug:
        print(f"   [debug] 输入int8 scale={in_scale:.6f} zp={in_zp} | "
              f"输出int8 scale={out_scale:.4f} zp={out_zp}")
        print(f"   [debug] pred 形状 {pred.shape}，框参数范围 "
              f"[{pred[:4].min():.1f}, {pred[:4].max():.1f}]，"
              f"类别分数范围 [{pred[4:].min():.3f}, {pred[4:].max():.3f}]")

    # ---- 解码 ----
    h, w = img.shape[:2]
    dets = []
    for a in range(num_anchor):
        scores = {c: float(pred[4 + c, a]) for c in TARGET_CLASSES}
        cls, conf = max(scores.items(), key=lambda kv: kv[1])
        if conf < CONF_THRESHOLD:
            continue
        # 框是【归一化的 0~1】（导出时在 ONNX 里插了 Div(640)，
        # 目的是让框和类别分数共用同一个量化 scale，否则分数会被压成 0）。
        # 所以要先乘回输入尺寸，才能和 letterbox 的偏移/缩放对齐。
        cx, cy, bw, bh = (float(pred[i, a]) * size for i in range(4))
        # 反 letterbox → 原图像素坐标
        x1 = ((cx - bw / 2) - dx) / scale
        y1 = ((cy - bh / 2) - dy) / scale
        x2 = ((cx + bw / 2) - dx) / scale
        y2 = ((cy + bh / 2) - dy) / scale
        dets.append({
            "cls": cls,
            "conf": conf,
            "box": (max(0, x1), max(0, y1), min(w, x2), min(h, y2)),
        })

    if debug and dets:
        print(f"   [debug] NMS 前候选 {len(dets)} 个，最高置信度 "
              f"{max(d['conf'] for d in dets):.3f}")
    return nms(dets)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="../app/src/main/assets/yolo_int8.tflite")
    ap.add_argument("--image")
    ap.add_argument("--dir")
    ap.add_argument("--count", type=int, default=30)
    ap.add_argument("--debug", action="store_true")
    args = ap.parse_args()

    model_path = Path(args.model)
    if not model_path.exists():
        model_path = Path(__file__).resolve().parent.parent / "app/src/main/assets/yolo_int8.tflite"
    if not model_path.exists():
        raise SystemExit(f"[错误] 找不到模型：{model_path}")

    import tensorflow as tf
    interp = tf.lite.Interpreter(model_path=str(model_path))
    interp.allocate_tensors()
    size = interp.get_input_details()[0]["shape"][1]
    print(f"模型：{model_path}  输入 {size}x{size}")

    images = []
    if args.image:
        images = [Path(args.image)]
    elif args.dir:
        d = Path(args.dir)
        images = sorted(
            p for p in d.rglob("*") if p.suffix.lower() in (".jpg", ".jpeg", ".png")
        )[:args.count]
    if not images:
        raise SystemExit("[错误] 没有找到测试图片")

    total_hits = 0
    per_class = {name: 0 for name in TARGET_CLASSES.values()}

    print(f"\n共 {len(images)} 张图，逐张检测：\n")
    for path in images:
        try:
            dets = run(interp, load_image(path), size, debug=args.debug)
        except Exception as exc:                                  # noqa: BLE001
            print(f"  {path.name}: 读取/推理失败 {exc}")
            continue
        if dets:
            total_hits += len(dets)
            summary = ", ".join(
                f"{TARGET_CLASSES[d['cls']]} {d['conf']:.0%}" for d in dets
            )
            print(f"  ✅ {path.name}: {summary}")
            for d in dets:
                per_class[TARGET_CLASSES[d["cls"]]] += 1
        else:
            print(f"  ·  {path.name}: 无车")

    print("\n" + "=" * 56)
    print(f"合计检出 {total_hits} 个目标")
    for name, n in per_class.items():
        print(f"   {name}: {n}")
    print("=" * 56)
    if total_hits == 0:
        print("⚠️ 一张都没检出 —— 模型或解码逻辑可能有问题，请加 --debug 复跑")
        return 1
    print("✅ 模型能认出车，算法链路是通的")
    return 0


if __name__ == "__main__":
    sys.exit(main())
