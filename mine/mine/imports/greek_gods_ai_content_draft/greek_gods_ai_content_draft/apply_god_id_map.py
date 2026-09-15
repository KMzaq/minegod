#!/usr/bin/env python3
import json, sys
from pathlib import Path

ROOT=Path(__file__).resolve().parent
MAP=ROOT/'god-id-map.json'
FILES=[
 'ai-personas.greek.draft.json',
 'npc-agents.greek.draft.json',
 'npc-character-tags.greek.draft.json',
 'ai-knowledge.greek.draft.json',
 'dialogue-examples.greek.draft.json',
]

def replace(obj, mp):
    if isinstance(obj,str): return mp.get(obj,obj)
    if isinstance(obj,list): return [replace(v,mp) for v in obj]
    if isinstance(obj,dict): return {mp.get(k,k): replace(v,mp) for k,v in obj.items()}
    return obj

raw=json.loads(MAP.read_text(encoding='utf-8'))
missing=[m for m in raw['mappings'] if not m.get('finalGodId')]
if missing:
    print(f'ERROR: finalGodId가 비어 있는 항목 {len(missing)}개')
    for m in missing[:20]: print(' -',m['displayName'],m['draftId'])
    sys.exit(2)
mp={m['draftId']:m['finalGodId'] for m in raw['mappings']}
out=ROOT/'merge-ready'
out.mkdir(exist_ok=True)
for fn in FILES:
    data=json.loads((ROOT/fn).read_text(encoding='utf-8'))
    data=replace(data,mp)
    target=out/fn.replace('.greek.draft','.greek')
    target.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('wrote',target)
print('완료: merge-ready 폴더의 파일을 기존 config JSON에 병합할 수 있습니다.')
