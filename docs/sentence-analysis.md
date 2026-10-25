# Unified sentence analysis

The sheet now presents recognized text, translation, and a combined sentence breakdown.
Phrase links remain interactive. Each lexical unit presents its reading, lemma, leading
dictionary sense, POS, sentence role and inflection chain, with additional senses and
internal morphology expandable. An outlined/filled bookmark sits at the upper right;
“Definition & morphology” aligns with the card's left content edge. Attached grammar
uses always-visible notes below the related vocabulary card, without separate translation
cards or bookmarks for particles, auxiliaries, suffixes or grammatical dependent forms.
Saving uses the independent base lemma and reading; existing saved words are retained.
The romaji setting also controls component and construction notes. Construction readings
come from their matched original tokens, including corrected readings, rather than the
rule's illustrative fragment. Split auxiliaries ending in small `っ` retain their doubled
consonant using the adjacent following reading (e.g. `じゃっ` before `た` displays `jat`).

## Offline pipeline

`SentenceAnalysis` keeps Kuromoji morphology, learner-facing `LexicalUnit`s and grammar.
Source offsets are half-open UTF-16 offsets into the original OCR/corrected text. Matching
does not replace that text. Whitespace remains in the recognized text and offsets; it is
not shown as a dictionary word. Punctuation is retained in lexical output.

Grouping uses exact adjacent dictionary compounds (at most four noun tokens), supported
colloquial spellings, suru verbs, and morphology-supported auxiliary chains (bounded to
eight following tokens). Equally ranked compound entries retain the original segmentation.
The supported colloquial repairs include なーんか and もしもーし. Arbitrary OCR damage is
not normalized into one of these spellings. Unknown names are not assigned meanings by
pronunciation alone. A unique dictionary-verified prefix/verb repair handles 御/し → 御す.

JMdict schema 2 preserves inherited sense POS and spelling/reading restrictions. Lookup
returns up to four ranked exact-spelling candidates, each with up to sixteen compatible
senses. Reading agreement precedes commonness. The synchronized lookup cache retains at
most 512 requests. There are no new model weights or automatic LLM calls.

Grammar uses lexical boundaries while retaining original tokens for component explanations
and rule matching. POS and sentence roles are separate. Bare quoted nouns can be nominal
fragments; unsupported roles display “Other”. Phrase dependencies cannot cross
sentence-ending punctuation. This remains a heuristic learner aid, not a full syntactic
parser or a guarantee that damaged OCR can be interpreted.

`LearnerBreakdown` derives presentation entries from the existing lexical units and phrase
components without rewriting stored analysis. Full POS tags distinguish noun suffixes
from independent nouns. Adjacent noun suffixes extend the visible construction; particles
and auxiliaries are explained underneath. `負けっぷりに` presents `負けっぷり`, explains
the noun-forming manner/state suffix and `に`, and bookmarks the base verb `負ける`.
`俺たち` bookmarks `俺`, including when the stored dictionary lookup grouped the plural.
Ordinary dictionary compounds and suru verbs retain their headword identities.
Prefixes open a phrase and attach forward to an adjacent head word. For
`最後のひと試合入るか`, Kuromoji already identifies `ひと` as a prefix; the phrase builder
now produces `最後の / ひと試合 / 入るか`, instead of attaching `ひと` to `最後の`.
The prefix is explained under `試合`, which remains the bookmark target. Source gaps and
punctuation prevent prefix attachment; independent `人` remains ordinary vocabulary.
Bundled morphology-constrained rules explain the compound particle `として` and the
complete `としての` construction as “as / in the capacity of”; the latter also explains
how `の` connects to a following noun. They render once beneath the preceding vocabulary
card, with romaji when enabled, and work for grammar-only fragments. These rules match
the compound-particle POS tag, so volitional `〜ようとする` is not mislabeled. No LLM
call or dictionary replacement is needed. The basic meaning follows
[Digital Daijisen's として entry](https://kotobank.jp/word/%E3%81%A8%E3%81%97%E3%81%A6-583453).

Matched construction notes replace generic explanations over the same token span, so
`かな` appears once. Model explanations retain their provenance within the construction
note. Notes spanning phrases stay in the “across the sentence” list. Explicit case roles
survive trailing tone particles, and noun-forming suffixes no longer make their containing
phrase look like a verb predicate. The original morphology, source offsets and IDs remain
available to the phrase overview and explanation requests. These presentation and grammar
changes apply on reopening existing pages, with no schema migration or OCR rerun.

## Context, identities and persistence

Page → volume → series resolution supplies the library title, Japanese title and existing
user note. The default Unsorted series is omitted. Remote and general local LLM prompts
request recognized series naming conventions while prioritizing the supplied Japanese and
forbidding imported plot facts or invented names. Liquid retains its fixed translation-only
input and receives no series context.

Translation identity `t2` includes model, prompt version, page kind, ordered Japanese and
effective context. Android and Python parity fixtures use the same serialization. Explanation
identity `g3` includes the actual lexical/phrase/token structure, readings, source offsets,
analysis version, model and context. Optional contextual meanings use lexical-unit IDs;
grammar notes use token IDs. Unknown IDs and invalid/cross-sentence head corrections are
discarded, including in cached responses. Model-written output is labelled.

In-flight translation results are committed only if current text, page kind and effective
context still match. Obsolete answers remain in their original cache, without replacing
displayed results. Reader context observation clears mismatched stored translations; memory
explanations track page, region and request identity. The background worker checks the
persisted user note again when it finishes.

Room migration 9 → 10 adds lexical JSON, analysis version, token offsets and translation
request identity. Older analysis refreshes lazily from stored corrected text on opening,
without accessing the image or rerunning OCR/detection. Refresh and editing are serialized;
reading corrections are reapplied to matching source spans, including legacy offsets and
repeated words. Text, morphology and lexical output are committed together.

Dictionary installation verifies the packaged SHA-256, size and installed hash marker,
including equal-size replacements. Rebuild the dictionary with `tools/build_jmdict.py` and
`tools/build_jmdict_mobile.py`; regenerate parity with `tools/export_analysis_fixture.py`
and `tools/export_translate_fixture.py`.

## Offline grammar coverage (2026-10-04)

Grammar now has a checked-in inventory and review ledger in
`tools/grammar_coverage.json`, derived from the exact IPADIC 2.7.0-20070801 source used by
Kuromoji 0.9.0, supplemented with manga fixture spelling variants. The ledger records
the source archive, fixture and packaged JMdict database SHA-256 hashes. Full POS,
readings and conjugation constraints remain available for every source form.

| Coverage | Count |
| --- | ---: |
| Grammatical lemmas grouped by full POS and conjugation type | 2,030 |
| Form-specific construction dispositions | 152 |
| Compatible dictionary-supported dispositions | 1,000 |
| Tested suru-verbaliser family dispositions | 2 |
| Explicit context-dependent dispositions | 876 |
| Verb/adjective/auxiliary conjugation families and slots | 411 |
| Curated rules with positive and negative fixtures | 182 |
| Grammatical JMdict senses / entries / spelling forms | 1,714 / 957 / 2,271 |

The context-dependent cases are intentional, individually documented in the ledger:
384 counters, 209 other suffixes, 129 dependent nouns, 67 prefixes, 34 dependent verbs,
33 particles, 9 dependent adjectives, 8 auxiliaries and 3 address suffixes. They retain
attachment and morphology explanations without inventing a contextual interpretation.
Four source spellings (`としましたら`, `を通じ`, `をめぐる`, `につきまして`) split in the
tested noun frames; they do not get unverified whole-token rules. Four dictionary
homographs (`元`, `槽`, `畑`, `筆`) have grammatical senses whose reading restrictions
do not support the inventory readings. A corrected reading may make those senses
available; the ledger counts their original morphology as context-dependent.

`GrammarLexicon` resolves exact written forms, then grammatical inflected lemmas.
Reading only validates restrictions on an already matched spelling; corrected surface
readings take precedence for exact forms, while lemma lookups use lemma readings. Ordinary noun
and verb senses never become grammatical definitions; expression senses are admitted
only for compound-particle POS. The asset preserves entry and sense IDs, reading and
spelling restrictions, source metadata and EDRDG/CC BY-SA attribution.

Construction rules take precedence over component notes. Remaining grammatical
components retain their attachment explanations and show compatible fallback senses
under **Dictionary meanings.**, followed by **JMdict · EDRDG**. Multiple possibilities
are explicitly left to context. Model explanations and model IDs remain separate.
No token gloss, stored word, offset, original ID, translation response, model request,
analysis version or Room schema changes are needed. `DictionaryStore` caches the asset;
`AnalysisController` loads it on an IO dispatcher and updates derived learner entries.
Reopening stored pages uses the new rules and resolver automatically.

### Regeneration and checks

See [build-assets.md](build-assets.md) for source pins, setup, and the reviewed
regeneration process. `python tools/build_grammar_coverage.py` checks generated
freshness and reviewed inventory, dictionary references, morphology and construction
coverage. The build runs it using Python from PATH, overridable with Gradle's
`pythonExecutable` property. The analysis JVM tests exercise tokenizer fixtures,
inflections, dictionary restrictions, ambiguity, model provenance and duplicate
suppression. Run the host suite from CONTRIBUTING.md and relevant device checks
on a disposable installation.
