"""Python 3 표준라이브러리만 사용. 같은 폴더의 기획 JSON에서 조건부 산술표를 생성한다.
게임/배포 JAR을 실행하거나 네트워크에 접속하지 않는다. 결과는 실효 DPS가 아니다.
"""
import csv,json,math,pathlib
P=pathlib.Path(__file__).resolve().parent
D=json.loads((P/'전환_구현전달_기획.json').read_text())
def save(name,rows):
 with (P/name).open('w',newline='',encoding='utf-8-sig') as f:
  w=csv.DictWriter(f,fieldnames=list(rows[0]));w.writeheader();w.writerows(rows)
def reduced(d,a=0,t=0):return d*(1-min(20,max(a/5,a-d/(2+t/4)))/25)
def gate(events,window=10):
 """단일 시도/보스/소유자 테스트. 실제 컨트롤러 아님. 정렬 입력, 동시 사건 안정순서."""
 start=-math.inf;maximum=0;out=[]
 for tick,damage in sorted(events,key=lambda x:x[0]):
  if damage<=0:out.append((tick,0));continue
  if tick>=start+window-1e-9:start=tick;maximum=0
  delta=max(0,damage-maximum);maximum=max(maximum,damage);out.append((tick,delta))
 return out
def hits(w,end,first_only=False):
 v=w['after_initial_design'];a=v['A0'];s=v['speed'];m=w['analysis']['multipliers']
 if a is None:
  if w['key']=='C05_OPEN_PATH_BOW':return [(t,10) for t in range(20,int(math.ceil(end)),24)]
  return []
 period=20/s;offset=period/2 if w['profile_observation'] else 0
 return [(offset+i*period,a*m[0 if first_only else i%len(m)]) for i in range(max(0,math.floor((end-offset+1e-9)/period)+1)) if offset+i*period < end-1e-9]
rows=[]
for w in D['weapons']:
 for seconds in [.5,1,2,3,5,10,20,26,60]:
  for mode in ['콤보완주','첫타만_리셋비용0_상한']:
   ev=hits(w,seconds*20,mode.startswith('첫타'))
   for n in [1,3,5]:
    for q in [1,.5]:
     if not ev and w['key']=='C03_GUIDE_BOW':total='배포 기본탄도·치명분산 미고정, 숫자 생성 안 함'
     else:total=round(sum(d for _,d in ev)*(1+q*(n-1)),6)
     rows.append({'무기':w['key'],'초':seconds,'모드':mode,'적수':n,'보조대상_민감도q':q,'타점수':len(ev),'원시총피해_조건부':total,'10tick창_단일피해':round(sum(d for _,d in gate(ev)),6),'전제':'구간[0,T);모든타점가시·명중;인챈트힘F방어0. n배는광역가능보장아님;활은단일대상만 해석'})
# Bow/fan do not gain n targets from melee conversion.
for r in rows:
 if r['무기'] in ['C03_GUIDE_BOW','C05_OPEN_PATH_BOW','CTL-FOX-W01'] and r['적수']>1:
  r['원시총피해_조건부']='예외경로: 자동광역 없음, 단일대상행 참조'
save('시간별_적수별_피해.csv',rows)
# Mana-only exact cooldown cadence; on failure skip until next original cadence, no cooldown consumed.
rows=[]
for sk in D['skills']:
 for M in [200,300,400]:
  mana=M;casts=0;first=None
  for tick in range(6001):
   if tick>0:mana=min(M,mana+M*.01/20)
   if tick%sk['cd']==0 and tick<6000:
    if mana+1e-8>=sk['cost']:mana-=sk['cost'];casts+=1
    elif first is None:first=tick/20
  rows.append({'기술':sk['key'],'최대마나_민감도':M,'300초시전수':casts,'300초끝잔량':round(mana,6),'첫부족초':first if first is not None else '없음','초당이론소비':sk['cost']/(sk['cd']/20),'초당회복':M*.01,'전제':'시전t0,끝300초발동제외·끝회복포함;단독스킬·쿨정시·잠금충돌없음;300/400은장비지급약속아닌민감도'})
save('마나_지속.csv',rows)
def rotation(w,sk,end=1200,with_kj=False):
 a=w['after_initial_design']['A0'];speed=w['after_initial_design']['speed'];m=w['analysis']['multipliers'];period=20/speed
 now=0;last=0;mana=200;nexts=0;nextj=120;combo=0;events=[];casts=0;kjcasts=0;first_fail=None
 # Alternation resets combo only in this conservative analytic branch, not an asserted BC rule.
 while now<end-1e-9:
  mana=min(200,mana+(now-last)*.1);last=now
  typ='normal'
  if sk and now>=nexts-1e-9:
   if mana>=sk['cost']-1e-8:typ='skill'
   elif first_fail is None:first_fail=now
  if typ=='normal' and with_kj and now>=nextj-1e-9 and mana>=24-1e-8:typ='kj'
  if typ=='skill':
   mana-=sk['cost'];nexts=now+sk['cd'];casts+=1;combo=0
   if sk['hit'] is not None:
    hit=now+sk['hit']+(5 if sk['key'] in ['OLY-ART-W01','CTL-THO-W01','CTL-FOX-W01'] else 0)
    events.append((hit,sk['raw']))
    if 'dot' in sk:
     events.extend((hit+dt,x) for dt,x in zip(sk['dot_offsets'],sk['dot']))
   now+=sk['lock']
  elif typ=='kj':
   mana-=24;nextj=now+240;kjcasts+=1;combo=0;events.append((now+6,8));now+=12
  else:
   offset=period/2 if w['profile_observation'] else 0;events.append((now+offset,a*m[combo%len(m)]));combo+=1;now+=period
 events=[e for e in events if e[0]<end-1e-9];mana=min(200,mana+max(0,end-last)*.1)
 return {'원시피해':round(sum(x for _,x in events),6),'10tick창후피해':round(sum(x for _,x in gate(events)),6),'끝마나':round(mana,6),'무기시전':casts,'KJ시전':kjcasts,'첫마나부족초':None if first_fail is None else round(first_fail/20,6),'타격사건수':len(events)}
rows=[]
for w in D['weapons']:
 if w['after_initial_design']['A0'] is None:continue
 sk=next((s for s in D['skills'] if s['key']==w['key']),None)
 for sec in [60,300]:
  for kj in [False,True]:
   rows.append({'무기':w['key'],'초':sec,'KJ_기준프로필교대':kj,**rotation(w,sk,sec*20,kj),'전제':'자유시점무기기술우선→KJ→평타;스킬후콤보첫단계가정;비행5tick;완전명중·응수성공·도트허용 상한;KJ=기준24/240/원시8/잠금12,실제획득신설0'})
save('스킬교대_피해와마나.csv',rows)
# Every battle, a transparent static sensitivity; not predictions of actual kill time.
rows=[]
for r in D['raids']:
 for key in ['H01','C05_LANDING_SWORD','UNIFIED-SWORD']:
  w=next(x for x in D['weapons'] if x['key']==key);a=w['after_initial_design']['A0'];s=w['after_initial_design']['speed'];m=w['analysis']['multipliers'];armor=r['armor_before_after'][1];tough=r['toughness_before_after'][1]
  for uptime in [.45,.65,.85]:
   per=sum(reduced(a*x,armor,tough) for x in m)/len(m)*s*uptime*.9
   # Single world-unique sword cannot be cloned five times.
   ordinary=next(x for x in D['weapons'] if x['key']=='H01')
   od=sum(reduced(9*x,armor,tough) for x in ordinary['analysis']['multipliers'])/3*1.25*uptime*.9
   party=per+4*od if key=='UNIFIED-SWORD' else 5*per
   sec=r['hp5_after']/party+(72 if r['key']=='C26' else 0)
   rows.append({'전투':r['key'],'무기조건':key if key!='UNIFIED-SWORD' else '합일1+H01비교4_동시획득보장아님','5인HP':r['hp5_after'],'방어':armor,'강인':tough,'접근가동률':uptime,'명중가정':.9,'1인조건부DPS':round(per,6),'5인조건부DPS':round(party,6),'정적초':round(sec,6),'추가기믹초':72 if r['key']=='C26' else 0,'한계':'시간평균근사·공간분산·스킬·피격억제·페이즈초과손실 미포함. C05지상가동률은별도해석,T03닫힘방어20별도.'})
save('레이드_5인_민감도.csv',rows)
assert gate([(0,8),(5,12),(9,4),(10,8)])==[(0,8),(5,4),(9,0),(10,8)]
assert sum(x for _,x in gate([(0,8)]))+sum(x for _,x in gate([(0,8)]))==16
assert len(D['weapons'])==13 and len(D['excluded_nonweapons'])==30 and len(D['additional_reference_audit'])==18

# 1~5 participants: same stage-wise defense calculation, explicitly conditional.
rows=[]
for r in D['raids']:
 for n,hp in enumerate(r['participant_sensitivity']['hp_before_after_equal'],1):
  for key in ['H01','C05_LANDING_SWORD','UNIFIED-SWORD']:
   w=next(w for w in D['weapons'] if w['key']==key)
   for uptime in [.45,.65,.85]:
    def rate(w):
     return sum(reduced(w['after_initial_design']['A0']*m,r['armor_before_after'][1],r['toughness_before_after'][1]) for m in w['analysis']['multipliers'])/len(w['analysis']['multipliers'])*w['after_initial_design']['speed']*uptime*.9
    one=rate(w);ordinary=rate(next(w for w in D['weapons'] if w['key']=='H01'))
    party=one+(n-1)*ordinary if key=='UNIFIED-SWORD' else n*one
    goal=r['participant_sensitivity']['target_seconds_by_n'][n-1]
    rows.append({'전투':r['key'],'초기및동시인원':n,'HP':hp,'무기조건':key if key!='UNIFIED-SWORD' else '합일1+H01비교'+str(n-1),'접근가동률':uptime,'명중가정':.9,'파티조건부DPS':round(party,6),'정적초':round(hp/party+r['participant_sensitivity']['gate_seconds'],6),'목표하한_설계':goal[0],'목표상한_설계':goal[1],'범위':'무버프·무스킬·단계별방어. T03열림만. 실제TTK/획득보장 아님'})
save('레이드_1부터5인_민감도.csv',rows)
assert len(rows)==405

print('조건부 산술표 5종 생성. 정적 assertion PASS. 게임 실행 없음.')
