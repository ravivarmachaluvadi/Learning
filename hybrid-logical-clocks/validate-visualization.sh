#!/bin/sh
# validate-visualization.sh — proves the page's JS clock engine agrees with the Java lab.
# Extracts the pure <script id="engine-js"> block from visualization.html, replays the
# exact rule cases YourClock.java asserts, and re-checks the lab's property claims.
# Uses node if present, otherwise macOS's built-in JavaScriptCore (osascript).
#   ./validate-visualization.sh
set -e
cd "$(dirname "$0")"

awk '/<script id="engine-js">/{f=1;next} /<\/script>/{if(f){f=0}} f' visualization.html > /tmp/hlc-viz-engine.js

cat /tmp/hlc-viz-engine.js > /tmp/hlc-viz-test.js
cat >> /tmp/hlc-viz-test.js <<'EOF'

let pass=0, fail=0;
const check=(name,ok)=>{ ok?pass++:fail++; console.log("  "+(ok?"ok  ":"FAIL")+" "+name); };

// ---- the six YourClock.java receive cases, verbatim (must match Java exactly) ----
function recvCase(l0,c0,pt,ml,mc){ const l=[l0],c=[c0],lam=[0]; return stepRecv("HLC",0,pt,{l:ml,c:mc},l,c,lam); }
const cases=[
  [20,9,30,25,3, 30,0], [5,2,11,20,3, 20,4], [40,6,38,35,9, 40,7],
  [50,2,50,50,7, 50,8], [60,4,55,60,1, 60,5], [10,0,10,10,0, 10,1]];
let rulesOk=true;
for(const [l0,c0,pt,ml,mc,wl,wc] of cases){
  const r=recvCase(l0,c0,pt,ml,mc);
  if(r.l!==wl||r.c!==wc){ rulesOk=false; console.log(`    case (${l0},${c0}) pt=${pt} msg=(${ml},${mc}): got (${r.l},${r.c}) want (${wl},${wc})`); } }
check("hlc_receive_rule_matches_java_on_all_six_yourclock_cases", rulesOk);

// ---- local rule ----
{ const l=[10],c=[5],lam=[0];
  const a=stepLocal("HLC",0,12,l,c,lam), b=stepLocal("HLC",0,12,l,c,lam);
  check("hlc_local_rule_reset_then_borrow", a.l===12&&a.c===0&&b.l===12&&b.c===1); }

// ---- property claims on the page's schedule (mirror Tests.java qualitatively) ----
const off=[5,-5,0], spread=10;
{ const g=grade(compute("WALL",off,false));
  check("wall_inverts_and_ties_under_skew ("+g.inv+"+"+g.ties+")", g.inv>0); }
{ const g=grade(compute("WALL",[0,0,0],false));
  check("wall_zero_skew_no_inversions_but_ties ("+g.ties+")", g.inv===0 && g.ties>0); }
{ const g=grade(compute("LAMPORT",off,false));
  check("lamport_never_inverts_never_ties", g.inv===0 && g.ties===0); }
{ const n=grade(compute("NAIVE",off,true)), h=grade(compute("HLC",off,true));
  check("naive_drifts_past_hlc_under_burst ("+Math.max(...n.drift)+" vs "+Math.max(...h.drift)+")",
    n.inv===0 && Math.max(...n.drift)>Math.max(...h.drift)); }
{ const ev=compute("HLC",off,true); const g=grade(ev);
  check("hlc_zero_inversions_zero_ties", g.inv===0 && g.ties===0);
  check("hlc_drift_bounded_by_spread ("+Math.max(...g.drift)+" <= "+spread+")", Math.max(...g.drift)<=spread);
  check("hlc_burst_goes_into_c", ev.some(e=>e.stamp.c>0));
  let torn=0;
  const ls=ev.map(e=>e.stamp.l);
  for(let T=Math.min(...ls)-1;T<=Math.max(...ls)+1;T++) torn+=cutInfo(ev,T).torn.length?1:0;
  check("hlc_no_cut_position_ever_tears", torn===0); }
{ const ev=compute("WALL",off,false);
  let torn=0; const ls=ev.map(e=>e.stamp.l);
  for(let T=Math.min(...ls)-1;T<=Math.max(...ls)+1;T++) torn+=cutInfo(ev,T).torn.length?1:0;
  check("wall_some_cut_positions_tear ("+torn+")", torn>0); }
{ const a=tokenDemo("HLC",off,false), b=tokenDemo("HLC",off,true);
  check("token_demo_stale_without_fresh_with", a.stale===true && b.stale===false); }
{ const a=tokenDemo("WALL",off,true);
  check("wall_cannot_honor_a_token", a.stale===true); }

console.log("\n"+pass+" passed, "+fail+" failed");
if(fail>0) throw new Error(fail+" failures");
"ok";
EOF

if command -v node >/dev/null 2>&1; then
  sed 's/^if(typeof module.*$//' /tmp/hlc-viz-test.js > /tmp/hlc-viz-test-node.js
  node /tmp/hlc-viz-test-node.js
else
  osascript -l JavaScript /tmp/hlc-viz-test.js 2>&1
fi
