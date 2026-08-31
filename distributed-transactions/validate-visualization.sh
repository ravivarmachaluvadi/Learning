#!/bin/sh
# validate-visualization.sh — proves the browser engine agrees with the Java lab.
# Extracts the pure-simulation <script id="engine-js"> block from visualization.html,
# replays the same scenarios Tests.java asserts, and checks the same outcomes.
# Uses node if present, otherwise macOS's built-in JavaScriptCore (osascript).
#   ./validate-visualization.sh
set -e
cd "$(dirname "$0")"

# extract the engine block
awk '/<script id="engine-js">/{f=1;next} /<\/script>/{if(f){f=0}} f' visualization.html > /tmp/dtx-viz-engine.js

cat /tmp/dtx-viz-engine.js > /tmp/dtx-viz-test.js
cat >> /tmp/dtx-viz-test.js <<'EOF'

// ---------------- scenario checks (mirror Tests.java) ----------------
let pass=0, fail=0;
const check=(name,ok)=>{ ok?pass++:fail++; console.log("  "+(ok?"ok  ":"FAIL")+" "+name); };
const money=st=>latest(st.shards.S0,"alice")+latest(st.shards.S1,"bob");
const run=(st,fns)=>fns.forEach(f=>f(st));
const T=stepTransfer, A=stepAudit;

{ // Java: tpc_commit_is_atomic_but_visibility_is_not
  const st=initState("TPC","ab",0);
  run(st,[T,T,T,T,newAudit,A,A,T]);
  check("tpc_mid_window_audit_sees_150_then_money_ends_right",
    st.lastAudit.sum===150 && st.lastAudit.fractured && money(st)===200);
}
{ // Java: perco_reader_finishes_a_half_committed_txn_itself
  const st=initState("PERCO","ab",0);
  run(st,[T,T,T,T,T,T,newAudit,A,A]);
  check("perco_mid_commit_audit_rolls_forward_to_200",
    st.lastAudit.sum===200 && st.lastAudit.seen.bob===150 && !st.shards.S1.locks.bob);
}
{ // Java: perco_dead_client_before_flip_gets_rolled_back_by_a_reader
  const st=initState("PERCO","ab",0);
  run(st,[T,T,T,killPriya,newAudit,A]);           // first read must WAIT on the fresh lock
  const waited = !st.omar.done && st.omar.idx===0;
  for(let i=0;i<TTL+2;i++) waitTick(st);
  run(st,[A,A]);
  check("perco_kill_before_flip_ttl_rollback_clean_200",
    waited && st.lastAudit.sum===200 && money(st)===200
    && !Object.keys(st.shards.S0.locks).length && !Object.keys(st.shards.S1.locks).length);
}
{ // Java: perco_dead_client_after_flip_gets_rolled_FORWARD_by_a_reader
  const st=initState("PERCO","ab",0);
  run(st,[T,T,T,T,T,T,killPriya,newAudit,A,A]);
  check("perco_kill_after_flip_reader_completes_commit",
    st.lastAudit.sum===200 && st.lastAudit.seen.bob===150 && money(st)===200);
}
{ // Java: d2pl_dead_coordinator_blocks_innocent_readers
  const st=initState("TPC_2PL","ab",0);
  run(st,[T,T,T,killPriya,newAudit]);
  for(let i=0;i<25;i++) A(st);
  check("d2pl_kill_with_locks_strands_the_auditor", st.omar.gaveUp===true);
}
{ // Java: skewed_clock_reader_misses_an_acknowledged_commit
  const st=initState("CLOCK","ab",4);
  run(st,[T,T,T,T,T,T,T]);
  const acked=st.txn.done;
  run(st,[newAudit,A,A]);
  check("clock_skew_confirmed_transfer_invisible_to_later_audit",
    acked && st.lastAudit.stale && st.lastAudit.seen.alice===100);
}
{ // Java: commit_wait_makes_the_ack_late_enough_to_be_safe + cost ~2*eps
  const st=initState("CLOCK_WAIT","ab",4);
  for(let i=0;i<40 && !st.txn.done;i++) T(st);
  run(st,[newAudit,A,A]);
  check("commit_wait_audit_after_ack_sees_the_transfer",
    st.txn.done && !st.lastAudit.stale && st.lastAudit.seen.alice===50 && st.lastAudit.sum===200);
  check("commit_wait_cost_about_two_eps_ticks", st.txn.waitTicks>=7 && st.txn.waitTicks<=10);
}
{ // Java: naive_crash_between_shards_loses_money_forever
  const st=initState("NAIVE","ab",0);
  run(st,[T,killPriya,T,newAudit,A,A]);
  check("naive_kill_between_shards_money_150_forever",
    money(st)===150 && st.lastAudit.sum===150 && st.lastAudit.fractured);
}
{ // the direction control really moves the primary
  const st=initState("PERCO","ba",0);
  run(st,[T,T]);
  check("direction_control_moves_the_primary_to_S1",
    !!st.shards.S1.locks.bob && st.shards.S1.locks.bob.primary==="bob");
}
{ // happy path clean on every engine
  let allClean=true;
  for(const e of ["NAIVE","TPC","TPC_2PL","PERCO","CLOCK","CLOCK_WAIT"]){
    const st=initState(e,"ab",4);
    for(let i=0;i<40 && !st.txn.done;i++) T(st);
    newAudit(st);
    for(let i=0;i<20 && !(st.omar&&st.omar.done);i++) A(st);
    if(!st.lastAudit || st.lastAudit.sum!==200 || money(st)!==200){ allClean=false; console.log("    (dirty: "+e+")"); }
  }
  check("all_engines_clean_audit_after_quiet_commit", allClean);
}
console.log("\n"+pass+" passed, "+fail+" failed");
if(fail>0) throw new Error(fail+" failures");
"ok";
EOF

if command -v node >/dev/null 2>&1; then
  sed 's/^if(typeof module.*$//' /tmp/dtx-viz-test.js > /tmp/dtx-viz-test-node.js
  node /tmp/dtx-viz-test-node.js
else
  osascript -l JavaScript /tmp/dtx-viz-test.js 2>&1
fi
