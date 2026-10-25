"""Export manga-ocr to ONNX -- plan section 8.1, the step M0 skipped.

`harness/pipeline/ocr.py` runs the PyTorch checkpoint through transformers, which is
fine on a desktop but is not a thing that ships in an APK. Section 4 stage 5 wants
ONNX, and section 10 flags encoder-decoder export as a known risk, so this runs before
any Kotlin exists and is verified against the same fixture photographs the harness is
already tuned against.

Two graphs, not three:

    encoder.onnx   pixel_values [1,3,224,224]      -> encoder_hidden_states [1,197,768]
    decoder.onnx   input_ids [1,S] + those states  -> logits [1,S,6144]

**No KV cache.** The usual reason to export a `decoder_with_past` variant is that
re-running the decoder over the whole prefix each step is quadratic. Here the decoder
is *two* BERT layers over a <=300 position window with a 6144 vocab -- the cost of a
page is dominated by the 12-layer ViT encoder, which runs once per region either way.
Dropping the cache removes the fiddliest part of section 10's risk (past_key_values
graph surgery) at a cost that measurement, not assumption, has to confirm; `--bench`
prints the split.

Greedy decode only, matching what the harness already does at ocr.py's `num_beams=1`,
so section 10's beam-search tradeoff is unchanged rather than newly incurred.

    python tools/export_ocr_onnx.py            # export + self-check
    python tools/export_ocr_onnx.py --bench    # also time encoder vs decoder
"""
from __future__ import annotations

import argparse
import shutil
import sys
import time
from pathlib import Path

import numpy as np
import torch

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "var" / "models" / "manga-ocr"
OUT = ROOT / "var" / "models" / "manga-ocr-onnx"

OPSET = 17          # widely supported by onnxruntime-android
IMAGE_SIZE = 224
ENCODER_POSITIONS = (IMAGE_SIZE // 16) ** 2 + 1   # 196 patches + CLS


class Encoder(torch.nn.Module):
    """ViT trunk, plus the projection to decoder width if the checkpoint has one."""

    def __init__(self, model):
        super().__init__()
        self.encoder = model.encoder
        # Present only when encoder and decoder hidden sizes differ. manga-ocr is
        # 768 -> 768 so it is normally absent, but exporting it when it exists costs
        # nothing and avoids a silently wrong graph on a re-trained checkpoint.
        self.proj = getattr(model, "enc_to_dec_proj", None)

    def forward(self, pixel_values):
        hidden = self.encoder(pixel_values=pixel_values).last_hidden_state
        return self.proj(hidden) if self.proj is not None else hidden


class Decoder(torch.nn.Module):
    """BertLMHeadModel conditioned on the encoder states, full prefix each call."""

    def __init__(self, model):
        super().__init__()
        self.decoder = model.decoder

    def forward(self, input_ids, encoder_hidden_states):
        return self.decoder(input_ids=input_ids,
                            encoder_hidden_states=encoder_hidden_states,
                            use_cache=False).logits


def export(verbose: bool = True) -> None:
    import transformers
    from transformers import VisionEncoderDecoderModel

    transformers.logging.set_verbosity_error()
    if not SRC.exists():
        raise SystemExit(f"missing {SRC} -- run tools/fetch_models.py first")

    OUT.mkdir(parents=True, exist_ok=True)
    model = VisionEncoderDecoderModel.from_pretrained(str(SRC)).eval()

    encoder, decoder = Encoder(model).eval(), Decoder(model).eval()
    pixel_values = torch.randn(1, 3, IMAGE_SIZE, IMAGE_SIZE)

    with torch.no_grad():
        hidden = encoder(pixel_values)
    if verbose:
        print(f"encoder output {tuple(hidden.shape)}")

    torch.onnx.export(
        encoder, (pixel_values,), str(OUT / "encoder.onnx"),
        input_names=["pixel_values"], output_names=["encoder_hidden_states"],
        dynamic_axes={"pixel_values": {0: "batch"},
                      "encoder_hidden_states": {0: "batch"}},
        opset_version=OPSET, do_constant_folding=True)

    input_ids = torch.tensor([[model.config.decoder_start_token_id, 100, 200]],
                             dtype=torch.long)
    torch.onnx.export(
        decoder, (input_ids, hidden), str(OUT / "decoder.onnx"),
        input_names=["input_ids", "encoder_hidden_states"], output_names=["logits"],
        dynamic_axes={"input_ids": {0: "batch", 1: "sequence"},
                      "encoder_hidden_states": {0: "batch"},
                      "logits": {0: "batch", 1: "sequence"}},
        opset_version=OPSET, do_constant_folding=True)

    # The Android side needs the vocabulary and the special-token ids; copying them
    # next to the graphs keeps the artifact set self-describing.
    for name in ("vocab.txt", "preprocessor_config.json", "tokenizer_config.json",
                 "special_tokens_map.json"):
        if (SRC / name).exists():
            shutil.copy2(SRC / name, OUT / name)

    if verbose:
        for path in sorted(OUT.glob("*.onnx")):
            print(f"  {path.name:16} {path.stat().st_size / 1e6:8.1f} MB")

    _self_check(model, encoder, decoder, pixel_values)


def _self_check(model, encoder, decoder, pixel_values) -> None:
    """Numerical parity of each graph against its torch module on one random input.

    This is not the quality gate -- that is `harness/cli.py` over `test_input/` -- but
    a mismatch here means the export is broken, and finding that out now is cheaper
    than finding it out through garbled Japanese.
    """
    import onnxruntime as ort

    enc_sess = ort.InferenceSession(str(OUT / "encoder.onnx"),
                                    providers=["CPUExecutionProvider"])
    dec_sess = ort.InferenceSession(str(OUT / "decoder.onnx"),
                                    providers=["CPUExecutionProvider"])

    with torch.no_grad():
        torch_hidden = encoder(pixel_values)
    onnx_hidden = enc_sess.run(None, {"pixel_values": pixel_values.numpy()})[0]
    enc_delta = float(np.abs(torch_hidden.numpy() - onnx_hidden).max())

    input_ids = torch.tensor([[model.config.decoder_start_token_id, 100, 200, 300]],
                             dtype=torch.long)
    with torch.no_grad():
        torch_logits = decoder(input_ids, torch_hidden)
    onnx_logits = dec_sess.run(None, {"input_ids": input_ids.numpy().astype(np.int64),
                                      "encoder_hidden_states": onnx_hidden})[0]
    dec_delta = float(np.abs(torch_logits.numpy() - onnx_logits).max())

    print(f"self-check  encoder max|delta| {enc_delta:.2e}   "
          f"decoder max|delta| {dec_delta:.2e}")
    if max(enc_delta, dec_delta) > 1e-3:
        print("  WARNING: larger than expected for an fp32 export")


def bench(runs: int = 5) -> None:
    """Encoder vs decoder cost, which is what justifies skipping the KV cache."""
    import onnxruntime as ort

    enc = ort.InferenceSession(str(OUT / "encoder.onnx"),
                               providers=["CPUExecutionProvider"])
    dec = ort.InferenceSession(str(OUT / "decoder.onnx"),
                               providers=["CPUExecutionProvider"])
    pixel_values = np.random.randn(1, 3, IMAGE_SIZE, IMAGE_SIZE).astype(np.float32)

    hidden = enc.run(None, {"pixel_values": pixel_values})[0]
    start = time.perf_counter()
    for _ in range(runs):
        hidden = enc.run(None, {"pixel_values": pixel_values})[0]
    encoder_ms = (time.perf_counter() - start) / runs * 1000

    # A 20-token bubble is typical; without a cache that is 20 calls over a growing
    # prefix, so time the whole loop rather than one step.
    start = time.perf_counter()
    for _ in range(runs):
        ids = np.array([[2]], dtype=np.int64)
        for _ in range(20):
            logits = dec.run(None, {"input_ids": ids,
                                    "encoder_hidden_states": hidden})[0]
            ids = np.concatenate([ids, [[int(logits[0, -1].argmax())]]], axis=1)
    decode_ms = (time.perf_counter() - start) / runs * 1000

    print(f"\ncpu, per region:  encoder {encoder_ms:6.1f} ms   "
          f"20-token greedy decode {decode_ms:6.1f} ms   "
          f"({decode_ms / (encoder_ms + decode_ms) * 100:.0f}% in the decoder)")


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bench", action="store_true",
                        help="time encoder vs cacheless greedy decode on CPU")
    args = parser.parse_args(argv)

    export()
    if args.bench:
        bench()
    print(f"\nwrote {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
