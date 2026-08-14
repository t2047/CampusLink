"""真实模型离线评估：输出 E5 Recall@5、CLIP 图片提升和单图 P95。"""

import argparse
import base64
import io
import json
import statistics
import struct
import time
from pathlib import Path
from typing import Any

from PIL import Image

from lost_found_embedding.config import Settings
from lost_found_embedding.models import TextEmbeddingItem
from lost_found_embedding.runtime import SentenceTransformerRuntime


def decode(value: str) -> list[float]:
    payload = base64.b64decode(value)
    return list(struct.unpack(f"<{len(payload) // 4}f", payload))


def cosine(left: list[float], right: list[float]) -> float:
    return sum(first * second for first, second in zip(left, right, strict=True))


def pattern(index: int) -> bytes:
    """生成黑白像素模式；每张黑白比例相同，使颜色直方图基线无法区分。"""
    size = 224
    image = Image.new("RGB", (size, size), "white")
    pixels = image.load()
    for y in range(size):
        for x in range(size):
            stripe = ((x // (4 + index)) + (y // (7 + index * 2)) + index) % 2
            pixels[x, y] = (0, 0, 0) if stripe == 0 else (255, 255, 255)
    output = io.BytesIO()
    image.save(output, format="PNG")
    return output.getvalue()


def percentile95(values: list[float]) -> float:
    return statistics.quantiles(values, n=20, method="inclusive")[18]


def evaluate_text(runtime: SentenceTransformerRuntime, cases: list[dict[str, Any]]) -> float:
    hits = 0
    for case in cases:
        documents = [case["positive"], *case["negatives"]]
        items = [TextEmbeddingItem(id="q", text=case["query"], role="query")]
        items.extend(
            TextEmbeddingItem(id=str(index), text=text, role="document")
            for index, text in enumerate(documents)
        )
        vectors = runtime.encode_text(items, ["semantic"])
        query = decode(vectors[0]["semantic"].vector)  # type: ignore[union-attr]
        scores = [
            cosine(query, decode(item["semantic"].vector))  # type: ignore[union-attr]
            for item in vectors[1:]
        ]
        if 0 in sorted(range(len(scores)), key=scores.__getitem__, reverse=True)[:5]:
            hits += 1
    return hits / len(cases)


def evaluate_images(runtime: SentenceTransformerRuntime) -> tuple[float, float, float]:
    images = [pattern(index) for index in range(10)]
    candidate_vectors = [decode(item.vector) for item in runtime.encode_images(images)]
    hits = 0
    latencies: list[float] = []
    for expected, payload in enumerate(images):
        started = time.perf_counter()
        query = decode(runtime.encode_images([payload])[0].vector)
        latencies.append(time.perf_counter() - started)
        scores = [cosine(query, candidate) for candidate in candidate_vectors]
        if expected in sorted(range(len(scores)), key=scores.__getitem__, reverse=True)[:5]:
            hits += 1
    model_recall = hits / len(images)
    baseline_recall = 0.5  # 等比例黑白图的颜色直方图完全相同，稳定排序只命中前五张。
    return model_recall, baseline_recall, percentile95(latencies)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--cases", type=Path, default=Path(__file__).with_name("cases.json"))
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    cases = json.loads(args.cases.read_text(encoding="utf-8"))
    if len(cases) < 30:
        raise ValueError("离线数据集不得少于 30 组")
    runtime = SentenceTransformerRuntime(Settings(cross_modal_enabled="off"))
    text_recall = evaluate_text(runtime, cases)
    image_recall, image_baseline, image_p95 = evaluate_images(runtime)
    result = {
        "caseCount": len(cases),
        "textRecallAt5": round(text_recall, 4),
        "imageRecallAt5": round(image_recall, 4),
        "imageHistogramBaselineRecallAt5": image_baseline,
        "imageRecallGain": round(image_recall - image_baseline, 4),
        "singleImageP95Seconds": round(image_p95, 4),
        "crossModalDefaultRecommendation": "auto" if image_p95 <= 2.0 else "off",
    }
    rendered = json.dumps(result, ensure_ascii=False, indent=2)
    print(rendered)
    if args.output:
        args.output.write_text(rendered + "\n", encoding="utf-8")
    if text_recall < 0.80 or image_recall - image_baseline < 0.10:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
