# Third-party notices and provenance

GPL-3.0-only applies to original Manga Lens code and original project artwork.
It does not replace the licenses of the following dependencies or data. License
texts in [LICENSES](./) are also packaged as APK assets. Rubik's original
notice is retained at `android/app/LICENSE-Rubik.txt`.

| Component | Upstream and license | Treatment in Manga Lens |
| --- | --- | --- |
| JMdict/EDICT | Copyright James William BREEN and the Electronic Dictionary Research and Development Group; [EDRDG license statement](https://www.edrdg.org/edrdg/licence.html); CC BY-SA 4.0 | Japanese-English data converted using [scriptin/jmdict-simplified](https://github.com/scriptin/jmdict-simplified), then to mobile SQLite; FTS index and unused tags removed |
| Dictionary-derived grammar lexicon | Same JMdict attribution and CC BY-SA 4.0 | Filtered forms and senses with morphology restrictions in `tools/grammar_lexicon.json`; provenance is recorded in its metadata |
| IPADIC | [mecab-ipadic 2.7.0-20070801](https://atilika.com/releases/mecab-ipadic/mecab-ipadic-2.7.0-20070801.tar.gz); original IPADIC COPYING, including its ICOT provisions | Tokenizer data and grammatical inventory; the original COPYING is retained in `LICENSES/IPADIC-COPYING.txt` |
| Kuromoji 0.9.0 | [atilika/kuromoji](https://github.com/atilika/kuromoji); Apache-2.0; IPADIC data has its separate terms | JVM Japanese tokenizer; component notices and contributor files retained under LICENSES |
| Manga OCR weights | [kha-white/manga-ocr-base](https://huggingface.co/kha-white/manga-ocr-base); Apache-2.0 | Original weights exported to separate ONNX encoder/decoder, then dynamically QUInt8-quantized; these modified artifacts are distributed separately and bundled during APK builds |
| Comic detector | [ogkalu/comic-text-and-bubble-detector](https://huggingface.co/ogkalu/comic-text-and-bubble-detector); Apache-2.0 | ONNX detector downloaded separately by the app |
| ONNX Runtime 1.20.0 | [microsoft/onnxruntime](https://github.com/microsoft/onnxruntime/tree/v1.20.0); MIT | On-device model execution; MIT notice retained |
| llama.cpp v0.2.0 | [ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp/tree/v0.2.0); MIT | Gradle downloads the pinned source and checks its SHA-256; JNI wrapper is project code |
| Rubik | Copyright 2015 The Rubik Project Authors; [googlefonts/rubik](https://github.com/googlefonts/rubik); SIL Open Font License 1.1 | Bundled font files; original OFL text retained |
| AndroidX, Compose, CameraX, Room, WorkManager, Media3 | [AndroidX](https://android.googlesource.com/platform/frameworks/support/); Apache-2.0 | Android UI, capture, persistence, and background work |
| Kotlin, kotlinx libraries, Ktor | [JetBrains](https://github.com/JetBrains); Apache-2.0 | Language/runtime, coroutines, serialization, HTTP |
| OpenCV 4.11.0 | [opencv/opencv](https://github.com/opencv/opencv/tree/4.11.0); Apache-2.0 | Image processing |

Exact direct dependency versions are in `android/gradle/libs.versions.toml`; the
native revision and checksum are in `android/ml/translate/build.gradle.kts`.
Dependency archives can contain additional notices for transitive or native
components; preserve those notices when updating dependencies or redistributing them.
APK packaging excludes colliding generic META-INF notice filenames from Kuromoji;
the distinct component copies in LICENSES preserve their notices instead.

Optional translation GGUFs retain the licenses and terms published by their model
authors; consult the linked model cards before redistributing weights. They are
user-initiated downloads and are not part of the source or build-asset bundle:
[LiquidAI LFM2-350M-ENJP-MT](https://huggingface.co/LiquidAI/LFM2-350M-ENJP-MT-GGUF),
[Qwen3-1.7B](https://huggingface.co/ggml-org/Qwen3-1.7B-GGUF), and
[Qwen3-4B](https://huggingface.co/ggml-org/Qwen3-4B-GGUF).

The packaged tutorial videos and posters may depict manga excerpts. Their inclusion
does not transfer rights in the underlying works or place them under GPL. Private
sample manga photographs and full test pages are not included in this repository.
Original launcher and startup artwork source files are retained under `Design mockups/`.
