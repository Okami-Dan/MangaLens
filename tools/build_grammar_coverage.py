"""Offline grammar assets from the APK's JMdict and Kuromoji's pinned IPADIC source.

Run with --write after reviewing source changes; default checks reproducibility.
No downloads occur here. Put mecab-ipadic-2.7.0-20070801.tar.gz in var/assets.
"""
from __future__ import annotations
import argparse
import csv
import hashlib
import json
import sqlite3
import tarfile
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'var/assets/mecab-ipadic-2.7.0-20070801.tar.gz'
DB = ROOT / 'var/assets/jmdict-mobile.sqlite'
FIXTURE = ROOT / 'android/ml/analysis/src/test/resources/analysis_fixture.json'
TAGS = {'prt', 'exp', 'pref', 'n-pref', 'suf', 'n-suf', 'adj-suf', 'v-suf',
        'ctr', 'aux', 'aux-v', 'aux-adj', 'cop'}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def compatible(pos):
    parts = pos.split(',')
    if parts[0] == '助詞':
        return {'prt', 'exp'} if parts[2] == '連語' else {'prt'}
    if parts[0] == '接頭詞':
        return {'pref', 'n-pref'}
    if parts[0] == '助動詞':
        return {'aux', 'aux-v', 'aux-adj', 'cop'}
    if parts[1] == '接尾':
        if parts[2] == '助数詞':
            return {'ctr'}
        return {'suf', 'n-suf'} if parts[0] == '名詞' else {'suf', 'v-suf', 'aux-v', 'aux'} if parts[0] == '動詞' else {'suf', 'adj-suf', 'aux-adj'}
    if parts[1] == '非自立' and parts[0] in {'動詞', '形容詞'}:
        return {'aux', 'aux-v'} if parts[0] == '動詞' else {'aux', 'aux-adj'}
    return set()


def lexicon(inv):
    wanted = {}
    for item in inv:
        for written in {item['lemma']} | {f['surface'] for f in item['forms']}:
            wanted.setdefault(written, set()).update(compatible(item['pos']))
    with sqlite3.connect(f'file:{DB.as_posix()}?mode=ro', uri=True) as db:
        meta = dict(db.execute("SELECT key,value FROM meta WHERE key != 'tags'"))
        senses = [dict(zip(('entryId', 'position', 'pos', 'meaning', 'kanjiRestrictions', 'readingRestrictions'), r))
                  for r in db.execute('SELECT entry_id,position,pos,gloss,kanji_restrictions,reading_restrictions FROM senses ORDER BY entry_id,position')
                  if set(r[2].split(',')) & TAGS]
        allowed_by_entry = {}
        for eid, text in db.execute('SELECT entry_id,text FROM forms'):
            if text in wanted:
                allowed_by_entry.setdefault(eid, set()).update(wanted[text])
        senses = [s for s in senses if set(s['pos'].split(',')) & allowed_by_entry.get(s['entryId'], set())]
        ids = {s['entryId'] for s in senses}
        forms = [dict(zip(('entryId', 'text', 'kind', 'appliesTo'), r))
                 for r in db.execute('SELECT entry_id,text,kind,applies_to FROM forms ORDER BY entry_id,kind,text') if r[0] in ids]
    for s in senses:
        s['pos'] = s['pos'].split(',')
        for field in ('kanjiRestrictions', 'readingRestrictions'):
            s[field] = s[field].split('\x1f') if s[field] else []
    for f in forms:
        f['appliesTo'] = f['appliesTo'].split('\x1f') if f['appliesTo'] else []
    return {'meta': {**meta, 'databaseSha256': sha(DB), 'attribution':
            'JMdict/EDICT © EDRDG, CC BY-SA 4.0. https://www.edrdg.org/wiki/index.php/JMdict-EDICT_Dictionary_Project'},
            'forms': forms, 'senses': senses}


def inventory():
    groups = {}
    with tarfile.open(SOURCE) as archive:
        for member in sorted(archive.getmembers(), key=lambda m: m.name):
            if not member.name.endswith('.csv'):
                continue
            for r in csv.reader(archive.extractfile(member).read().decode('euc_jp').splitlines()):
                if r[4] not in {'助詞', '助動詞', '接頭詞'} and r[5] not in {'非自立', '接尾'} and not (r[10] == 'する' and r[4:6] == ['動詞', '自立']):
                    continue
                pos = ','.join(r[4:8])
                lemma = r[10] if r[10] != '*' else r[0]
                key = (lemma, pos, r[8])
                item = groups.setdefault(key, {'lemma': lemma, 'pos': pos, 'conjugationType': r[8], 'forms': []})
                form = {'surface': r[0], 'reading': r[11], 'conjugationForm': r[9]}
                if form not in item['forms']:
                    item['forms'].append(form)
    for region in json.loads(FIXTURE.read_text(encoding='utf-8'))['regions']:
        for token in region.get('tokens', []):
            p = token['partOfSpeech'].split(',')
            if p[0] not in {'助詞', '助動詞', '接頭詞'} and p[1] not in {'非自立', '接尾'}:
                continue
            key = (token['baseForm'], token['partOfSpeech'], token.get('conjugationType', '*'))
            if key not in groups:
                # Older fixture exports omit conjugation fields. Prefer the source group.
                key = next((k for k in groups if k[:2] == key[:2]), key)
            item = groups.setdefault(key, {'lemma': key[0], 'pos': key[1], 'conjugationType': key[2], 'forms': []})
            if not any(f['surface'] == token['surface'] for f in item['forms']):
                reading = ''.join(chr(ord(c) + 0x60) if '\u3041' <= c <= '\u3096' else c for c in token['reading'])
                item['forms'].append({'surface': token['surface'], 'reading': reading,
                                      'conjugationForm': token.get('conjugationForm', '*')})
    return [groups[k] for k in sorted(groups)]


def inflections():
    groups = {}
    with tarfile.open(SOURCE) as archive:
        for member in sorted(archive.getmembers(), key=lambda m: m.name):
            if not member.name.endswith('.csv'):
                continue
            for r in csv.reader(archive.extractfile(member).read().decode('euc_jp').splitlines()):
                if r[4] not in {'動詞', '形容詞', '助動詞'} or r[8] == '*':
                    continue
                groups.setdefault((r[4], r[8], r[9]), {'pos': r[4], 'conjugationType': r[8],
                    'conjugationForm': r[9], 'example': r[0], 'lemma': r[10],
                    'review': {'disposition': 'family-rule', 'family': 'Inflection.describe',
                               'reason': 'The tested conjugation family explains the stem slot without selecting an unsupported contextual meaning.'}})
    return [groups[k] for k in sorted(groups)]


def validate(ledger, asset):
    rules = json.loads((ROOT / 'tools/grammar_rules.json').read_text(encoding='utf-8'))['rules']
    by_rule = {r['id']: r for r in rules}
    errors = []
    if len(by_rule) != len(rules):
        errors.append('duplicate rule IDs')
    for rule in rules:
        if not rule.get('explanation', '').strip() or not rule.get('match'):
            errors.append(f'missing construction explanation or match: {rule["id"]}')
    signatures = [json.dumps({k: r[k] for k in ('match', 'after', 'before', 'atEnd') if k in r}, sort_keys=True) for r in rules]
    if len(signatures) != len(set(signatures)):
        errors.append('duplicate construction matches')
    by_id = {}
    for sense in asset['senses']:
        by_id.setdefault(sense['entryId'], []).append(sense)
        if not set(sense['pos']) & TAGS or not sense['meaning'].strip():
            errors.append(f'incompatible or empty grammatical sense: {sense}')
    for f in asset['forms']:
        if f['entryId'] not in by_id:
            errors.append(f'orphan form: {f}')
    for item in ledger['inventory']:
        review = item.get('review') or {}
        disposition = review.get('disposition')
        if disposition == 'form-rule':
            rule = by_rule.get(review.get('ruleId'))
            if not rule or not rule.get('explanation', '').strip():
                errors.append(f'invalid form-rule: {item["lemma"]}')
            elif not any(e.get('surface', e.get('base')) == item['lemma'] and
                         item['pos'].startswith(e.get('pos', '___')) for e in rule['match']):
                errors.append(f'incompatible form-rule: {item["lemma"]}')
        elif disposition == 'dictionary':
            ids = review.get('entryIds', [])
            allowed = compatible(item['pos'])
            for eid in ids:
                if not any(set(s['pos']) & allowed for s in by_id.get(eid, [])):
                    errors.append(f'incompatible dictionary reference: {item["lemma"]} {eid}')
                if not any(f['entryId'] == eid and f['text'] == item['lemma'] for f in asset['forms']):
                    errors.append(f'inexact dictionary reference: {item["lemma"]} {eid}')
            if not ids:
                errors.append(f'missing dictionary reference: {item["lemma"]}')
            if not supported_morphology(item, asset):
                errors.append(f'no compatible dictionary source morphology: {item["lemma"]} {item["pos"]}')
        elif disposition == 'context-dependent':
            if review.get('family') not in {'counter', 'address', 'dependent-noun', 'dependent-verb',
                    'dependent-adjective', 'prefix', 'suffix', 'particle', 'auxiliary'} or len(review.get('reason', '')) < 40:
                errors.append(f'missing context reason: {item["lemma"]}')
        elif disposition == 'family-rule':
            if review.get('family') != 'suru-verbaliser' or item['lemma'] != 'する':
                errors.append(f'unknown family rule: {item["lemma"]}')
        else:
            errors.append(f'unreviewed disposition: {item["lemma"]}')
    fixtures = ledger['constructions']
    referenced = {f['ruleId'] for f in fixtures}
    for rid in set(by_rule) - referenced:
        errors.append(f'missing construction fixtures: {rid}')
    for fixture in fixtures:
        if fixture['ruleId'] not in by_rule or not all(fixture.get(k) for k in ('positive', 'negative', 'reason')):
            errors.append(f'invalid fixture: {fixture}')
    for item in ledger['inflectionFamilies']:
        if item.get('review', {}).get('family') != 'Inflection.describe':
            errors.append(f'unreviewed inflection family: {item}')
    return errors


def supported_morphology(item, asset):
    """Restriction check over source forms; reading validates an exact spelling only."""
    allowed = compatible(item['pos'])
    by_id = {}
    for f in asset['forms']:
        by_id.setdefault(f['entryId'], []).append(f)
    senses = {}
    for s in asset['senses']:
        if set(s['pos']) & allowed:
            senses.setdefault(s['entryId'], []).append(s)
    for source_form in item['forms']:
        written = {source_form['surface']}
        if source_form['conjugationForm'] != '*' and item['conjugationType'] != '*':
            written.add(item['lemma'])
        for f in asset['forms']:
            if f['text'] not in written:
                continue
            entry_forms = by_id[f['entryId']]
            readings = {r['text'] for r in entry_forms if r['kind'] == 'kana' and
                        (not r['appliesTo'] or f['text'] in r['appliesTo'] or f['kind'] == 'kana') and
                        ''.join(chr(ord(c)+0x60) if '\u3041' <= c <= '\u3096' else c for c in r['text']) == source_form['reading']}
            kanji = set(f['appliesTo']) or {r['text'] for r in entry_forms if r['kind'] == 'kanji'}
            for s in senses.get(f['entryId'], []):
                if s['kanjiRestrictions'] and not (f['text'] in s['kanjiRestrictions'] if f['kind'] == 'kanji' else kanji & set(s['kanjiRestrictions'])):
                    continue
                if s['readingRestrictions'] and not readings & set(s['readingRestrictions']):
                    continue
                return True
    return False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write', action='store_true')
    args = parser.parse_args()
    inv = inventory()
    generated = lexicon(inv)
    path = ROOT / 'tools/grammar_coverage.json'
    existing = json.loads(path.read_text(encoding='utf-8')) if path.exists() else {}
    reviews = {(r['lemma'], r['pos'], r['conjugationType']): r['review'] for r in existing.get('inventory', [])}
    for item in inv:
        item['review'] = reviews.get((item['lemma'], item['pos'], item['conjugationType']))
    ledger = {'sources': {'kuromoji': '0.9.0', 'ipadic': '2.7.0-20070801', 'ipadicSha256': sha(SOURCE),
              'ipadicUrl': 'https://atilika.com/releases/mecab-ipadic/mecab-ipadic-2.7.0-20070801.tar.gz',
              'dictionarySha256': sha(DB)}, 'inventory': inv,
              'fixtureSha256': sha(FIXTURE), 'inflectionFamilies': inflections(),
              'constructions': existing.get('constructions', [])}
    failures = []
    version = re.search(r'^kuromoji\s*=\s*"([^"]+)"', (ROOT / 'android/gradle/libs.versions.toml').read_text(), re.M)
    if not version or version[1] != ledger['sources']['kuromoji']:
        failures.append('Kuromoji pin changed: review the matching IPADIC source before regenerating')
    for target, value in ((path, ledger), (ROOT / 'tools/grammar_lexicon.json', generated)):
        data = json.dumps(value, ensure_ascii=False, indent=2 if target == path else None,
                          separators=None if target == path else (',', ':')) + '\n'
        if args.write:
            target.write_text(data, encoding='utf-8')
        elif not target.exists() or target.read_text(encoding='utf-8') != data:
            failures.append(f'stale generated asset: {target.name}')
    failures += [f"unreviewed: {i['lemma']} {i['pos']}" for i in inv if not i['review']]
    failures += validate(ledger, generated)
    print(f'{len(inv)} grammatical lemmas; {len(generated["senses"])} grammatical senses; {len(failures)} problems')
    for f in failures[:30]:
        print(f)
    return bool(failures)


if __name__ == '__main__':
    raise SystemExit(main())
