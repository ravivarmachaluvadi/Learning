"""audit.py — readability signals for /learn labs (delivery, not depth).

Usage:
    python tools/audit.py .            # every lab, one row each
    python tools/audit.py <lab-folder> # one lab
    python tools/audit.py . --json out.json   # also dump the rows

Columns:
  front   lines before the first '## 1' heading (rule 1: story first; target < 25)
  tmpl    bold/italic labels repeated 3+ times, total occurrences (rule 6: target 0)
  lbls    how many distinct labels repeat
  meas    epistemic asides: "measured", "verified", "headless"… (rule 8: target < 5)
  state   compact state strings like A[p=2 wrote=Zoo@1] (rule 4: target 0 in narrative)
  id_n    DISTINCT snake_case identifiers left in prose, outside tables and code
          fences (rule 4: each should have been introduced in words first)
  cont%   share of later sections that still mention the story's characters (rule 2: target > 60)
  q-hdr   headings containing a question mark (rule 3)
  tsv     words in the opening "three-sentence version" blockquote
"""
import re, os, sys, json
from collections import Counter
ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
# A lab folder has a README.md; the workspace root has one too, so tell them
# apart by LEARNING-PATH.md, which only the root carries.
SINGLE = (os.path.isfile(os.path.join(ROOT, "README.md"))
          and not os.path.isfile(os.path.join(ROOT, "LEARNING-PATH.md")))
SKIP = {"lib", "dino-rescue-adventure", ".git"}
rows=[]
for d in ([os.path.basename(os.path.abspath(ROOT))] if SINGLE else sorted(os.listdir(ROOT))):
    p=os.path.join(ROOT,"README.md") if SINGLE else os.path.join(ROOT,d,"README.md")
    if d in SKIP or not os.path.isfile(p): continue
    raw=open(p,encoding="utf-8",errors="replace").read()
    lines=raw.split("\n")
    # 1. front-matter: lines before the first "## 1" / first story heading
    idx=next((i for i,l in enumerate(lines) if re.match(r"^##\s*1[\.\s]",l)),None)
    front = idx if idx is not None else -1
    # 2. template repetition: bold/italic labels that repeat >=3 times
    labels=re.findall(r"^\s*(?:[-*]\s+)?(?:\*\*|\*|_)([A-Z][A-Za-z ,'/()?-]{2,40}?)(?::\*\*|:\*|:_|\*\*:|\*:|_:)",raw,flags=re.M)
    c=Counter(labels); rep={k:v for k,v in c.items() if v>=3}
    tmpl=sum(rep.values())
    # 3. epistemic asides
    meas=len(re.findall(r"\b(measured|on this machine|headless|verified|reproduc|simulator'?s? (?:own )?engine)\w*",raw,flags=re.I))
    # 4. compact state notation / symbols
    # The rule is "no bare symbol in narrative prose" — a symbol sitting in a
    # table with an "in words" column, or inside a code fence, is exactly where
    # the style contract wants it. So count notation in the prose only.
    prose=re.sub(r"```.*?```"," ",raw,flags=re.S)          # code fences
    prose=re.sub(r"^\s*\|.*$"," ",prose,flags=re.M)         # table rows
    # Count DISTINCT identifiers, not occurrences. The rule is about how many
    # different symbols get dumped on the reader before their words; a symbol
    # that was properly introduced and is then used thirty times is correct
    # writing, not a violation.
    state=len(re.findall(r"\b[A-Z]\[[a-z]+=",prose))+len(re.findall(r"\(\s*n\s*[,=]",prose))
    idn=len(set(re.findall(r"\b[a-zA-Z]+_[a-zA-Z0-9]+\b",prose)))
    # 5. story continuity: capitalised words that appear >=3 times within the first 4 sections, then which % of later H2 sections mention any
    secs=re.split(r"^## ",raw,flags=re.M)
    story="\n".join(secs[1:6]); later=secs[9:] if len(secs)>10 else secs[6:]
    stop=set("The This That There Now But And You Your When What Where Why How Each Every One Two Three Four Five Six Get Imagine Same Both Neither She Her His Once All Here Then Yes Not Nothing Which Who Put Let Suppose Consider Say Our Its Some Only Even First Second Third Also For With Without After Before Until While Because Instead Meanwhile Later Sometimes Something Somebody Everyone Nobody Just Still Already Again Right Wrong Option Case Step Stage Section Figure Table Notice Look Watch Remember Note Predict Reveal Attempt Rule Round Phase Server Client Node Java Spring Kafka Redis Raft Paxos Google Amazon Apache Linux Windows Mac Unix HTTP TCP TLS DNS API SQL JVM GC CPU RAM Real Real-world Plain Technical Concrete Connects Example Key Naive Problem Failure Mechanism Terminology Mental Model Visualization Correctness Production Exercises Glossary Related Next Knowledge Map Checkpoint Beginner Intermediate Engineering If In On At To Of By As Or So No Do It Is Are Was Were Be Been Has Have Had Can Could Will Would Should May Might Must A An I We They He Them Us Me My Their Our These Those Such Than Then Thus Very More Most Less Least Much Many Few Any None Other Another Either Whether Between Among Over Under Through Across Into Onto From Up Down Out Off Back Away Around About Above Below Near Far Down".split())
    cand=[w for w in re.findall(r"\b([A-Z][a-z]{2,})\b",story) if w not in stop]
    cc=Counter(cand); names=[w for w,k in cc.most_common(8) if k>=3]
    if names and later:
        hit=sum(1 for s in later if any(re.search(r"\b"+n+r"\b",s) for n in names))
        cont=round(100*hit/len(later))
    else: cont=None
    # 6. question headings (Socratic)
    h2=re.findall(r"^##+ (.*)$",raw,flags=re.M)
    qh=sum(1 for h in h2 if "?" in h)
    # 7. 3-sentence version length (first blockquote) words
    m=re.search(r"^>\s*\*\*(?:The (?:three|one)[^*]*|.*?version.*?)\*\*(.*?)(?:\n\n|\n[^>])",raw,flags=re.S|re.M)
    tsv=len(m.group(1).split()) if m else 0
    rows.append(dict(lab=d,slug=re.sub(r"^\d+(?:\.\d+)?-","",d),front=front,tmpl=tmpl,tmpl_labels=len(rep),meas=meas,state=state,idn=idn,cont=cont,names=names[:4],qh=qh,h=len(h2),lines=len(lines),tsv=tsv))
print(f"{'lab':36}{'front':>6}{'tmpl':>6}{'lbls':>5}{'meas':>5}{'state':>6}{'id_n':>5}{'cont%':>6}{'q-hdr':>6}{'hdrs':>5}{'lines':>6}{'tsv':>5}  story-names")
for r in sorted(rows,key=lambda r:(-(r['front']>40)-(r['tmpl']>25)-(r['meas']>25)-(r['state']+r['idn']>40)-((r['cont'] or 0)<25), r['lab'])):
    print(f"{r['lab']:36}{r['front']:6d}{r['tmpl']:6d}{r['tmpl_labels']:5d}{r['meas']:5d}{r['state']:6d}{r['idn']:5d}{str(r['cont']):>6}{r['qh']:6d}{r['h']:5d}{r['lines']:6d}{r['tsv']:5d}  {','.join(r['names'])}")
if "--json" in sys.argv:
    json.dump(rows,open(sys.argv[sys.argv.index("--json")+1],"w"),indent=1)
