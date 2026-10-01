# -*- coding: utf-8 -*-
"""
================================================================
 YOLO → int8 量化 TFLite 导出脚本（三步管线）
 项目：加油站夜班值守车辆报警 APP
 阶段：M2
================================================================

【为什么不用 ultralytics 自带的导出】
  实测（2026-10，ultralytics 8.4.170 / 8.3.253，Windows）：
    - 8.4.170：format='tflite' 被改成 LiteRT 格式，代码里硬断言
                "LiteRT export only supported on Linux x86 and macOS"
    - 8.3.253：export_tflite() 是个空壳，只算了个路径就返回，
                源码里还挂着 "# BUG .../issues/13436" 的注释
  两条路都走不通，所以这里改成自己控制的三步管线，反而更透明、可调试。

【三步管线】
  ① yolov8n.pt  --(ultralytics / torch.onnx)-->      yolov8n.onnx
  ② yolov8n.onnx --(onnx2tf)-->                      saved_model/  (float32)
  ③ saved_model  --(tf.lite.TFLiteConverter + 标定)--> yolo_int8.tflite

【关于 int8 标定集 —— 这点对夜间识别很关键】
  int8 量化需要一批"代表性图片"来统计每一层激活值的取值范围。
  如果只用白天图片标定，量化区间会偏向白天，
  到了夜里画面整体偏暗时，量化误差会变大 —— 直接影响夜间识别率。

  所以本脚本的标定集是：【COCO128 原图】+【同一批图按 gamma 压暗后的版本】。
  这样量化区间能同时覆盖白天和夜间两种亮度分布。
  ⚠️ 这只是权宜之计。等 M5 能录下真实现场的夜间画面后，
     应该用【真实现场夜拍图】重新标定一次，效果会明显更好。

【用法】
  python tools/export_yolo_tflite.py                       # 默认 yolov8n / 640
  python tools/export_yolo_tflite.py --model yolo11n.pt
  python tools/export_yolo_tflite.py --imgsz 480
  python tools/export_yolo_tflite.py --no-darken           # 只用原始图标定
"""

import argparse
import shutil
import subprocess
import sys
from pathlib import Path

# Windows 中文控制台默认 GBK，直接 print 特殊符号会抛 UnicodeEncodeError。
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:                                              # noqa: BLE001
    pass

PROJECT_ROOT = Path(__file__).resolve().parent.parent
ASSETS_DIR = PROJECT_ROOT / "app" / "src" / "main" / "assets"
DEFAULT_OUT_NAME = "yolo_int8.tflite"

# App 运行时会用到的类别（COCO 索引）。模型导出的是完整 80 类，运行时再筛。
TARGET_CLASSES = {2: "car", 3: "motorcycle", 5: "bus", 7: "truck"}


# ============================================================
#  第一步：PyTorch → ONNX
# ============================================================
def step1_export_onnx(model_name: str, imgsz: int) -> Path:
    print("\n" + "=" * 60)
    print("① PyTorch → ONNX")
    print("=" * 60)
    try:
        from ultralytics import YOLO
    except ImportError:
        print("[错误] 没有 ultralytics，请先：python -m pip install ultralytics")
        raise SystemExit(1)

    model = YOLO(model_name)
    onnx_path = Path(model.export(format="onnx", imgsz=imgsz, simplify=True, opset=17))
    if not onnx_path.exists():
        raise SystemExit(f"[错误] ONNX 导出失败，没找到 {onnx_path}")
    print(f"   ✅ {onnx_path}  ({onnx_path.stat().st_size / 1024 / 1024:.1f} MB)")
    return onnx_path


# ============================================================
#  第二步：ONNX → TensorFlow SavedModel
# ============================================================
def normalize_box_branch(onnx_path: Path, imgsz: int) -> Path:
    """
    修复 int8 量化把「类别分数」压成 0 的致命问题（实测踩到，2026-10）。

    ---------- 问题 ----------
    YOLOv8 的最终输出是：
        cat([ 框参数(0~640), 类别分数(0~1) ], dim=1)   →  [1, 84, 8400]

    int8 量化会给这【一整个张量】只算一个缩放系数（per-tensor）。
    标定时观测到的值域是 0~656（被框参数撑大的），于是：
        scale ≈ 656/255 ≈ 2.57
    结果所有 0~1 的类别分数，量化时全部 round 到同一个格子：
        0.9 / 2.57 ≈ 0.35 → round → 0 → 反量化回来还是 0
    → 类别分数【全变 0】，模型一个目标都检不出来，而且不会报任何错。

    ---------- 修复 ----------
    在 concat 之前给「框参数」除以 imgsz，把两个分支的值域都拉到 0~1。
    这样单一 scale ≈ 1/255 就能同时精确表示两者：
        框  精度 ≈ 640/255 ≈ 2.5 像素（够用）
        分数 精度 ≈ 1/255 ≈ 0.4%（够用）

    ⚠️ 副作用：模型输出的框坐标变成【归一化的 0~1】而不是像素值。
       安卓端 YoloDetector.kt 里必须相应乘以 inputSize，两边要一起改。
    """
    import onnx
    from onnx import TensorProto, helper
    from onnx import shape_inference

    print("\n" + "=" * 60)
    print("①′ 归一化框输出（修复 int8 量化压掉类别分数的问题）")
    print("=" * 60)

    model = shape_inference.infer_shapes(onnx.load(str(onnx_path)))
    graph = model.graph
    out_name = graph.output[0].name

    producer_idx = next(
        (i for i, n in enumerate(graph.node) if out_name in n.output), None
    )
    if producer_idx is None:
        print("   ⚠️ 找不到输出节点，跳过（可能导致分数全 0，请检查）")
        return onnx_path
    producer = graph.node[producer_idx]
    if producer.op_type != "Concat":
        print(f"   ⚠️ 输出节点是 {producer.op_type} 而不是 Concat，跳过归一化")
        return onnx_path

    # 张量形状表：找出 4 通道的那个输入（框），80 通道的（分数）不动
    shapes = {}
    for coll in (graph.input, graph.output, graph.value_info):
        for v in coll:
            shapes[v.name] = [d.dim_value for d in v.type.tensor_type.shape.dim]

    box_idx = None
    for i, name in enumerate(producer.input):
        shape = shapes.get(name)
        if shape and len(shape) == 3 and shape[1] == 4:
            box_idx = i
            break
    if box_idx is None:
        print("   ⚠️ 没找到 4 通道的框分支，跳过归一化")
        return onnx_path

    box_name = producer.input[box_idx]
    norm_name = box_name + "_normalized"
    const_name = f"yolo_box_divisor_{imgsz}"

    graph.initializer.append(
        helper.make_tensor(const_name, TensorProto.FLOAT, [1], [float(imgsz)])
    )
    graph.node.insert(
        producer_idx,
        helper.make_node(
            "Div", [box_name, const_name], [norm_name], name="NormalizeBoxBranch"
        ),
    )
    producer.input[box_idx] = norm_name

    out_path = onnx_path.with_name(onnx_path.stem + "_normalized.onnx")
    onnx.save(model, str(out_path))
    print(f"   ✅ 已把 {box_name} 除以 {imgsz} → {norm_name}")
    print(f"   ✅ 新模型：{out_path}")
    print("   ⚠️ 框坐标现在是归一化的 0~1，安卓端解码要相应乘以 inputSize")
    return out_path


def ensure_onnx2tf_sample_data():
    """
    绕开 onnx2tf 的一个坑（实测踩到，2026-10）。

    onnx2tf 启动时会自动下载一份自检样本：
        calibration_image_sample_data_20x128x128x3_float32.npy
    但它用的是 requests.get(URL, timeout=(1.0, 5.0)) —— 读超时只有 5 秒。
    国内网络下根本下不完，拿到的是截断的坏文件，然后 np.load 抛：
        ValueError: This file contains pickled (object) data

    这个报错极具误导性，会让人以为是 numpy 版本问题。

    解决办法：提前在工作目录生成一份合法的同形状 float32 文件。
    onnx2tf 发现文件已存在就会直接用它，不再走下载。
    """
    import numpy as np

    name = "calibration_image_sample_data_20x128x128x3_float32.npy"
    path = Path.cwd() / name
    if path.exists():
        print(f"   自检样本已存在：{path.name}")
        return

    rng = np.random.default_rng(0)
    data = rng.random((20, 128, 128, 3), dtype=np.float32)
    # 叠一层渐变，避免纯随机数据让某些算子输出恒为 0，影响自检判断
    gradient = np.linspace(0, 1, 128, dtype=np.float32)[None, :, None, None]
    data = np.clip(data * 0.5 + gradient * 0.5, 0, 1).astype(np.float32)
    np.save(path, data)
    print(f"   已生成自检样本：{path.name}  {data.shape} {data.dtype}")


def step2_onnx_to_saved_model(onnx_path: Path, out_dir: Path) -> Path:
    print("\n" + "=" * 60)
    print("② ONNX → TensorFlow SavedModel（onnx2tf）")
    print("=" * 60)
    ensure_onnx2tf_sample_data()
    if out_dir.exists():
        shutil.rmtree(out_dir)

    cmd = [
        sys.executable, "-m", "onnx2tf",
        "-i", str(onnx_path),
        "-o", str(out_dir),
        "-nuo",          # 不做 onnxsim 简化（避免它把某些节点折叠坏）
        "-kat", "input", # 保持输入名不改成 tf 风格，方便对齐
    ]
    print("   执行：" + " ".join(cmd))
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        print(result.stdout[-3000:])
        print(result.stderr[-3000:])
        raise SystemExit("[错误] onnx2tf 转换失败")

    if not (out_dir / "saved_model.pb").exists():
        # onnx2tf 某些版本会多套一层目录
        nested = list(out_dir.rglob("saved_model.pb"))
        if not nested:
            raise SystemExit(f"[错误] {out_dir} 里没有 saved_model.pb")
        out_dir = nested[0].parent
    print(f"   ✅ SavedModel 在 {out_dir}")
    return out_dir


# ============================================================
#  第三步：SavedModel → int8 TFLite
# ============================================================
def build_representative_dataset(imgsz: int, darken: bool):
    """产出一批标定图片，喂给 TFLite 转换器统计激活值范围。"""
    import numpy as np
    import tensorflow as tf
    from ultralytics.data.utils import check_det_dataset

    data = check_det_dataset("coco128.yaml")
    image_dir = Path(data["train"])
    files = sorted(
        [p for p in image_dir.rglob("*") if p.suffix.lower() in (".jpg", ".jpeg", ".png")]
    )[:128]
    if not files:
        raise SystemExit(f"[错误] 标定集目录里没有图片：{image_dir}")
    print(f"   标定集：{image_dir}，共 {len(files)} 张"
          f"{'（另含 gamma 压暗版本）' if darken else ''}")

    def generator():
        for path in files:
            raw = tf.io.read_file(str(path))
            img = tf.io.decode_jpeg(raw, channels=3)
            img = tf.image.resize(img, [imgsz, imgsz])
            img = tf.cast(img, tf.float32) / 255.0
            yield [tf.expand_dims(img, 0).numpy()]

            if darken:
                # gamma 压暗：模拟夜间低照度的亮度分布
                dark = tf.pow(img, 1.8)
                yield [tf.expand_dims(dark, 0).numpy()]

    return generator


def step3_saved_model_to_int8_tflite(
    saved_model_dir: Path, imgsz: int, darken: bool, out_path: Path
) -> Path:
    print("\n" + "=" * 60)
    print("③ SavedModel → int8 TFLite")
    print("=" * 60)
    import numpy as np
    import tensorflow as tf

    rep = build_representative_dataset(imgsz, darken)

    converter = tf.lite.TFLiteConverter.from_saved_model(str(saved_model_dir))
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.representative_dataset = rep
    # 强制全整数量化：哪怕有算子不支持 int8 也要报错，而不是偷偷退回 float
    # （章程红线：禁止静默降级）
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
    converter.inference_input_type = tf.int8
    converter.inference_output_type = tf.int8

    print("   正在量化（要跑一遍标定集，需要几分钟）…")
    tflite_model = converter.convert()
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_bytes(tflite_model)
    print(f"   ✅ {out_path}  ({out_path.stat().st_size / 1024 / 1024:.2f} MB)")
    return out_path


# ============================================================
#  自检
# ============================================================
def verify(tflite_path: Path):
    import numpy as np
    import tensorflow as tf

    print("\n" + "=" * 60)
    print("④ 模型自检")
    print("=" * 60)
    interp = tf.lite.Interpreter(model_path=str(tflite_path))
    interp.allocate_tensors()

    inp = interp.get_input_details()[0]
    out = interp.get_output_details()[0]
    print(f"   [输入] shape={list(inp['shape'])}  dtype={inp['dtype'].__name__}  "
          f"quant={inp['quantization']}")
    print(f"   [输出] shape={list(out['shape'])}  dtype={out['dtype'].__name__}  "
          f"quant={out['quantization']}")

    # 跑一张全灰图，确认模型能正常前向、输出形状正确
    shape = list(inp["shape"])
    dummy = np.full(shape, 114, dtype=inp["dtype"])
    interp.set_tensor(inp["index"], dummy)
    interp.invoke()
    result = interp.get_tensor(out["index"])
    print(f"   前向测试通过，输出 shape = {list(result.shape)}")

    okay = True
    if inp["dtype"] != np.int8:
        print("   ⚠️ 输入不是 int8，说明整数量化没有完全生效")
        okay = False
    if out["dtype"] != np.int8:
        print("   ⚠️ 输出不是 int8，说明整数量化没有完全生效")
        okay = False
    if len(result.shape) != 3 or result.shape[0] != 1:
        print("   ⚠️ 输出形状不是 [1, C, N]，安卓端解码会出错")
        okay = False
    else:
        channels = min(result.shape[1], result.shape[2])
        if channels != 84:
            print(f"   ⚠️ 期望 84 个通道（4 框 + 80 类），实际 {channels}")
            okay = False
    print("   ✅ 自检通过" if okay else "   ❌ 自检有问题，见上面的警告")
    return okay


def main() -> int:
    ap = argparse.ArgumentParser(description="YOLO → int8 TFLite（三步管线）")
    ap.add_argument("--model", default="yolov8n.pt")
    ap.add_argument("--imgsz", type=int, default=640)
    ap.add_argument("--no-darken", action="store_true",
                    help="标定集不使用 gamma 压暗版本")
    ap.add_argument("--output", default=DEFAULT_OUT_NAME)
    args = ap.parse_args()

    print("=" * 60)
    print("  YOLO → int8 TFLite 导出")
    print("=" * 60)
    print(f"  模型     : {args.model}")
    print(f"  输入尺寸 : {args.imgsz}x{args.imgsz}")
    print(f"  目标类别 : {', '.join(TARGET_CLASSES.values())}（运行时筛选）")
    print(f"  输出     : {ASSETS_DIR / args.output}")

    onnx_path = step1_export_onnx(args.model, args.imgsz)
    onnx_path = normalize_box_branch(onnx_path, args.imgsz)
    saved_dir = step2_onnx_to_saved_model(
        onnx_path, onnx_path.parent / f"{onnx_path.stem}_saved_model"
    )
    out_path = step3_saved_model_to_int8_tflite(
        saved_dir, args.imgsz, not args.no_darken, ASSETS_DIR / args.output
    )
    ok = verify(out_path)

    print("\n" + "=" * 60)
    print("完成 ✅  下一步：Android Studio 里 Rebuild，模型会打进 APK")
    print("=" * 60)
    return 0 if ok else 2


if __name__ == "__main__":
    sys.exit(main())
