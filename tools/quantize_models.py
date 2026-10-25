"""INT8-quantize the ONNX graphs -- plan section 8.1 steps 1 and 2.

Section 4 stage 5 is blunt about why: the fp32 OCR weights are ~444 MB and "even
quantized this bloats the download unacceptably", so the app fetches them on first
launch. Fetching 460 MB over a phone connection is still the difference between a
usable first run and an abandoned one, and section 8.1 caps the decision with "if
quantized CER degrades badly, ship fp16".

Dynamic (weights-only) quantization, not static. Static would need a calibration set
and gives its best returns on activations, which for a 2-layer decoder over a 6144
vocab is not where the bytes are -- the bytes are in the ViT trunk's weight matrices
and the decoder's embedding and LM head, all of which dynamic quantization covers.

    python tools/quantize_models.py
    python harness/cli.py test_input --out var/run_int8 --onnx --no-store
    python tools/compare_runs.py var/run_torch_cpu var/run_int8

The CER that comes out of that last command is the number section 8.1 asks to record.
"""
from __future__ import annotations

import argparse
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MODELS = ROOT / "var" / "models"
OCR_FP32 = MODELS / "manga-ocr-onnx"
OCR_INT8 = MODELS / "manga-ocr-onnx-int8"
DETECTOR = MODELS / "detector"

# Copied alongside the graphs so the quantized directory is a drop-in for the fp32 one.
SIDECAR = ("vocab.txt", "preprocessor_config.json", "tokenizer_config.json",
           "special_tokens_map.json")


def quantize(src: Path, dst: Path) -> tuple[float, float]:
    from onnxruntime.quantization import QuantType, quantize_dynamic

    dst.parent.mkdir(parents=True, exist_ok=True)
    # QUInt8 rather than QInt8: the unsigned kernels are the ones with broad ARM
    # coverage in onnxruntime-android, and this has to run on a Snapdragon 778G
    # (docs/on-device-llm.md) with no usable NPU path, i.e. on CPU.
    quantize_dynamic(str(src), str(dst), weight_type=QuantType.QUInt8)
    before, after = src.stat().st_size / 1e6, dst.stat().st_size / 1e6
    print(f"  {src.name:16} {before:7.1f} MB -> {after:6.1f} MB  "
          f"({after / before * 100:.0f}%)")
    return before, after


def to_float16(src: Path, dst: Path) -> bool:
    """Section 8.1's named fallback for when INT8 costs too much accuracy.

    Halves the file without touching the arithmetic the way INT8 does: ONNX Runtime
    up-converts fp16 weights and computes in fp32 on an ARM CPU, so this buys download
    size and nothing else -- which would be the right trade for the detector, whose
    box coordinates feed every stage after it.

    **It does not work on this detector export**, and the tool says so rather than
    leaving a file that fails at session creation. The published export bakes RT-DETR's
    post-processing into the graph (detect.py's module docstring); converting it
    wholesale leaves a Cast in /model/decoder declaring float while receiving float16,
    and blocking that subtree by name only moves the same mismatch out to the top-level
    Identity nodes on the graph boundary. Making it load would mean hand-placing Cast
    nodes across that boundary, which is not worth 84 MB of one-time download against
    an fp32 detector that already works.

    Returns whether the converted graph loads.
    """
    import onnx
    import onnxruntime as ort
    from onnxconverter_common import float16

    dst.parent.mkdir(parents=True, exist_ok=True)
    model = onnx.load(str(src))
    # Block the post-processing subtree; the weights are in the backbone anyway.
    blocked = [n.name for n in model.graph.node
               if n.name.startswith("/model/decoder")]
    # keep_io_types leaves `images` float32 and `orig_target_sizes` int64, so
    # detect.py's session inputs are unchanged.
    onnx.save(float16.convert_float_to_float16(model, keep_io_types=True,
                                               node_block_list=blocked), str(dst))
    before, after = src.stat().st_size / 1e6, dst.stat().st_size / 1e6

    try:
        ort.InferenceSession(str(dst), providers=["CPUExecutionProvider"])
    except Exception as error:
        dst.unlink(missing_ok=True)
        print(f"  {src.name:16} {before:7.1f} MB -> fp16 REJECTED, file removed")
        print(f"    {str(error).splitlines()[-1][:120]}")
        return False

    print(f"  {src.name:16} {before:7.1f} MB -> {after:6.1f} MB  "
          f"({after / before * 100:.0f}%)  fp16")
    return True


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-detector", action="store_true",
                        help="OCR graphs only")
    args = parser.parse_args(argv)

    if not OCR_FP32.exists():
        raise SystemExit(f"missing {OCR_FP32} -- run tools/export_ocr_onnx.py first")

    OCR_INT8.mkdir(parents=True, exist_ok=True)
    print("manga-ocr:")
    total_before = total_after = 0.0
    for name in ("encoder.onnx", "decoder.onnx"):
        before, after = quantize(OCR_FP32 / name, OCR_INT8 / name)
        total_before, total_after = total_before + before, total_after + after
    for name in SIDECAR:
        if (OCR_FP32 / name).exists():
            shutil.copy2(OCR_FP32 / name, OCR_INT8 / name)
    print(f"  {'total':16} {total_before:7.1f} MB -> {total_after:6.1f} MB")

    if not args.skip_detector:
        source = DETECTOR / "detector.onnx"
        if source.exists():
            # The published export bakes RT-DETR's post-processing into the graph
            # (detect.py's module docstring). Dynamic quantization only rewrites
            # MatMul/Gemm weights, so that tail is left alone -- but section 4's class
            # ids and detect.py's fixture-tuned thresholds still have to be re-checked
            # against a run, not assumed to survive.
            print("\ndetector:")
            quantize(source, DETECTOR / "detector.int8.onnx")
            to_float16(source, DETECTOR / "detector.fp16.onnx")

    print(f"\nwrote {OCR_INT8}")
    print("See docs/build-assets.md before updating runtime and grammar checksums.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
