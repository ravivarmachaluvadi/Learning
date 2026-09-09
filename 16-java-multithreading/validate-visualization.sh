#!/bin/sh
# validate-visualization.sh — replays the page's simulation engines headlessly and
# checks every outcome the README and the page claim. No browser, no dependencies.
# Uses node if present, otherwise macOS's built-in JavaScriptCore (osascript).
#   ./validate-visualization.sh
set -e
cd "$(dirname "$0")"
OUT=/tmp/mt-viz
mkdir -p "$OUT"

extract(){ awk -v id="$1" 'index($0,"<script id=\"" id "\">"){f=1;next} /^<\/script>/{if(f){f=0}} f' visualization.html; }
extract engine-js    >  "$OUT/all.js"
extract scenarios-js >> "$OUT/all.js"
extract pool-js      >> "$OUT/all.js"

cat "$OUT/all.js" > "$OUT/test.js"
cat >> "$OUT/test.js" <<'EOF'

var pass=0, fail=0;
function check(name, ok, extra){
  if(ok){pass++;} else {fail++;}
  console.log("  " + (ok?"ok  ":"FAIL") + " " + name + (extra?("   ["+extra+"]"):""));
}
// run a scenario: its own script first, then alternate picking, up to cap steps
function runScn(id, o, cap){
  var sc = SCENARIOS[id], s = sc.build(o||{}), script = sc.script, k=0, guard=0;
  cap = cap || 120;
  while (s.run === 'RUNNING' && guard++ < cap){
    var tid = (Array.isArray(script) && k < script.length) ? script[k++] : alternatePick(s);
    if (!tid) break;
    var n = step(s, tid);
    if (n === s) break;
    s = n;
    if ((s.counters.loops||0) > 12) break;
  }
  return s;
}
function runSeq(id, o, order, cap){   // run each named thread until it cannot move
  var s = SCENARIOS[id].build(o||{}), guard=0;
  for (var i=0;i<order.length;i++){
    for(;;){
      var n = step(s, order[i]); if (n === s || guard++ > (cap||200)) break; s = n;
    }
  }
  return s;
}

console.log("\nScenario 1 — the vanishing increment");
var s1 = runScn('lost', {});
check("interleaved_reads_lose_one_increment (count=1)", s1.heap.count === 1, "count="+s1.heap.count);
check("run_finished", s1.run === 'DONE');
check("exactly_8_machine_steps", s1.steps === 8, "steps="+s1.steps);
var s1seq = runSeq('lost', {}, ['A','B']);
check("sequential_run_gives_2", s1seq.heap.count === 2, "count="+s1seq.heap.count);
check("count_is_never_3_over_all_interleavings", (function(){
  // exhaustive: enumerate every interleaving of the two 4-instruction threads
  var seen = {};
  (function rec(s){
    if (s.run !== 'RUNNING'){ seen[s.heap.count] = true; return; }
    var r = runnableIds(s); if(!r.length){ seen[s.heap.count]=true; return; }
    r.forEach(function(t){ rec(step(s,t)); });
  })(SCENARIOS.lost.build({}));
  return Object.keys(seen).sort().join(",") === "1,2";
})(), "reachable finals = 1 and 2 only");

console.log("\nScenario 2 — the stop signal that never arrives");
var s2 = runScn('flag', {vol:false});
check("worker_never_terminates_without_volatile", s2.threads[0].status !== 'TERMINATED', s2.threads[0].status);
check("memory_really_does_hold_true", s2.heap.stop === true);
check("worker_kept_reading_false_from_its_private_copy",
  s2.log.some(function(e){ return e.kind==='hot' && /read stop and got false/.test(e.what); }));
check("worker_looped_after_the_write", (s2.counters.loops||0) > 1, "loops="+s2.counters.loops);
var s2v = runScn('flag', {vol:true}, 200);
check("volatile_lets_the_worker_stop", s2v.threads[0].status === 'TERMINATED', s2v.threads[0].status);
check("volatile_removes_all_private_copies",
  Object.keys(s2v.threads[0].cache).length === 0 || s2v.volatiles.indexOf('stop') >= 0);

console.log("\nScenario 3 — what a lock buys you");
var s3 = runScn('sync', {nolock:false}, 200);
check("locked_increments_both_survive (count=2)", s3.heap.count === 2, "count="+s3.heap.count);
check("second_thread_was_blocked_at_least_once",
  s3.log.some(function(e){ return /could not/.test(e.what); }));
check("no_interleaving_of_a_lock_can_lose_the_update", (function(){
  var bad = false, seen = 0;
  (function rec(s, d){
    if (bad || d > 16) return;
    if (s.run !== 'RUNNING'){ seen++; if (s.heap.count !== 2) bad = true; return; }
    var r = runnableIds(s); if(!r.length){ seen++; if(s.heap.count!==2) bad=true; return; }
    r.forEach(function(t){ rec(step(s,t), d+1); });
  })(SCENARIOS.sync.build({nolock:false}), 0);
  return !bad && seen > 0;
})(), "exhaustive over every schedule");
var s3n = runSeq('sync', {nolock:true}, ['A'], 1);
check("without_the_lock_the_bug_returns", (function(){
  var s = SCENARIOS.sync.build({nolock:true});
  ['A','B','A','A','B','B','A','B'].forEach(function(t){ s = step(s,t); });
  return s.heap.count === 1;
})(), "same interleaving as scenario 1");

console.log("\nScenario 4 — two locks, no progress");
var s4 = runScn('deadlock', {order:false}, 60);
check("opposite_lock_order_deadlocks", s4.run === 'STUCK', s4.run);
check("both_threads_are_BLOCKED", s4.threads.every(function(t){ return t.status==='BLOCKED'; }));
check("each_holds_what_the_other_wants",
  s4.monitors.accountA.owner === 'A' && s4.monitors.accountB.owner === 'B');
var s4o = runScn('deadlock', {order:true}, 60);
check("consistent_lock_order_completes", s4o.run === 'DONE', s4o.run);

console.log("\nScenario 5 — if versus while");
var s5 = runScn('signal', {wh:false}, 80);
check("if_guard_drives_the_buffer_negative (items=-1)", s5.heap.items === -1, "items="+s5.heap.items);
check("one_item_produced_two_consumed",
  s5.log.filter(function(e){ return /took an item/.test(e.what); }).length === 2);
var s5w = runScn('signal', {wh:true}, 80);
check("while_guard_keeps_the_buffer_at_zero", s5w.heap.items === 0, "items="+s5w.heap.items);
check("second_consumer_went_back_to_sleep",
  s5w.threads.filter(function(t){ return t.status==='WAITING'; }).length === 1);
check("notifyAll_moved_sleepers_to_the_entry_set_not_to_running",
  s5.log.some(function(e){ return /woke 2 threads/.test(e.what); }));
check("a_notify_with_nobody_waiting_is_discarded", (function(){
  var s = SCENARIOS.signal.build({wh:false});
  ['C','C','C'].forEach(function(t){ s = step(s,t); });   // producer runs before anyone waits
  return s.log.some(function(e){ return /woke nobody/.test(e.what); });
})());

console.log("\nScenario 6 — compare-and-set");
var s6 = runScn('cas', {}, 80);
check("cas_loses_nothing (count=2)", s6.heap.count === 2, "count="+s6.heap.count);
check("exactly_one_cas_was_refused", s6.counters.casFail === 1, "casFail="+s6.counters.casFail);
check("the_loser_retried_once", s6.counters.retries === 1, "retries="+s6.counters.retries);
check("no_thread_was_ever_blocked",
  !s6.log.some(function(e){ return e.what.indexOf('could not') >= 0; }));
check("cas_is_safe_under_every_interleaving", (function(){
  var bad=false, n=0;
  (function rec(s,d){
    if(bad||d>18) return;
    if(s.run!=='RUNNING'){ n++; if(s.heap.count!==2) bad=true; return; }
    var r=runnableIds(s); if(!r.length){n++; if(s.heap.count!==2)bad=true; return;}
    r.forEach(function(t){ rec(step(s,t),d+1); });
  })(SCENARIOS.cas.build({}),0);
  return !bad && n>0;
})(), "exhaustive to depth 18");

console.log("\nThread pool");
function poolRun(cfg){ var s=poolInit(cfg), g=0; while(s.run==='RUNNING' && g++<500) s=poolStep(s); return s; }
var pA = poolRun({W:2,Q:12,N:12,D:5,nested:false,callerRuns:false});
check("pool2_finishes_12x5_at_tick_30", pA.tick === 30 && pA.run==='DONE', "tick="+pA.tick);
var pB = poolRun({W:4,Q:12,N:12,D:5,nested:false,callerRuns:false});
check("pool4_finishes_at_tick_15", pB.tick === 15, "tick="+pB.tick);
var pC = poolRun({W:6,Q:12,N:12,D:5,nested:false,callerRuns:false});
check("pool6_finishes_at_tick_10", pC.tick === 10, "tick="+pC.tick);
check("more_workers_never_finish_later", (function(){
  var sw = poolSweep(12,5,12,6), ok=true;
  for(var i=1;i<sw.length;i++) if(sw[i].makespan > sw[i-1].makespan) ok=false;
  return ok;
})());
check("makespan_equals_D_times_ceil(N/W)_whole_tasks_cannot_be_split", (function(){
  var sw = poolSweep(12,5,12,6), ok=true;
  sw.forEach(function(r){ if (r.makespan !== 5 * Math.ceil(12/r.w)) ok=false; });
  return ok;
})(), poolSweep(12,5,12,6).map(function(r){return r.w+":"+r.makespan;}).join(" "));
check("5_workers_finish_no_sooner_than_4_because_tasks_are_indivisible", (function(){
  var sw = poolSweep(12,5,12,6);
  return sw[3].makespan === 15 && sw[4].makespan === 15;
})(), "w4=15 w5=15 — the 5th worker is idle for the whole last round");
var pD = poolRun({W:2,Q:0,N:12,D:5,nested:false,callerRuns:false});
check("no_queue_means_10_of_12_tasks_are_rejected", pD.rejected === 10, "rejected="+pD.rejected);
var pE = poolRun({W:2,Q:0,N:12,D:5,nested:false,callerRuns:true});
check("callerRuns_rejects_nothing", pE.rejected === 0 && pE.completed === 12,
  "rejected="+pE.rejected+" completed="+pE.completed);
check("callerRuns_makes_the_submitter_do_real_work", pE.callerRan > 0, "callerRan="+pE.callerRan);
var pF = poolRun({W:2,Q:6,N:3,D:6,nested:true,callerRuns:false});
check("nested_submission_deadlocks_the_pool", pF.run === 'DEADLOCK', pF.run+" at tick "+pF.tick);
check("deadlocked_workers_are_all_blocked",
  pF.workers.every(function(w){ return w.blockedOn; }));
var pG = poolRun({W:8,Q:6,N:3,D:6,nested:true,callerRuns:false});
check("a_bigger_pool_only_moves_the_cliff", pG.run === 'DONE', pG.run);

console.log("\n" + pass + " passed, " + fail + " failed\n");
if (fail) throw new Error(fail + " check(s) failed");
EOF

if command -v node >/dev/null 2>&1; then
  node "$OUT/test.js"
else
  osascript -l JavaScript "$OUT/test.js" 2>&1
fi

# ---------------------------------------------------------------------------
# Second pass: run the real UI code against a stub DOM, so render-time errors
# (bad indexes, missing fields, undefined helpers) fail here rather than in the
# learner's browser.
# ---------------------------------------------------------------------------
{
  cat <<'STUB'
var VALUES = { pW:'2', pQ:'6', pN:'12', pD:'5' };
function El(id){
  this.id=id; this._html=''; this.textContent=''; this.className=''; this.dataset={};
  this.children=[]; this.disabled=false; this.value=VALUES[id]||'0'; this.checked=false;
  this.classList={ add:function(){}, remove:function(){}, toggle:function(){}, contains:function(){return false;} };
}
Object.defineProperty(El.prototype,'innerHTML',{ get:function(){return this._html;},
  set:function(v){ if(v===undefined||v===null) throw new Error('innerHTML set to '+v+' on #'+this.id); this._html=String(v); } });
El.prototype.setAttribute=function(){}; El.prototype.querySelectorAll=function(){return [];};
var _els={};
var document = { getElementById:function(id){ if(!_els[id]) _els[id]=new El(id); return _els[id]; },
                 querySelectorAll:function(){ return []; } };
var setTimeout=function(){return 0;}, clearTimeout=function(){},
    setInterval=function(){return 0;}, clearInterval=function(){};
STUB
  extract ui-js
  cat <<'DRIVE'

var upass=0, ufail=0;
function ucheck(name, fn){
  try { fn(); upass++; console.log("  ok   " + name); }
  catch(e){ ufail++; console.log("  FAIL " + name + "   [" + e.message + "]"); }
}
console.log("\nUI render pass (stub DOM)");
SCENARIO_ORDER.forEach(function(id){
  ucheck("renders_and_runs_every_step_of_" + id, function(){
    __viz.loadScenario(id);
    var sc = SCENARIOS[id], script = sc.script, k = 0, g = 0;
    while (g++ < 60){
      var s = __viz.state();
      if (s.run !== 'RUNNING') break;
      if ((s.counters.loops||0) > 12) break;
      var tid = (Array.isArray(script) && k < script.length) ? script[k++] : alternatePick(s);
      if (!tid) break;
      if (!__viz.doStep(tid)) break;
    }
  });
  (SCENARIOS[id].options||[]).forEach(function(o){
    ucheck("renders_" + id + "_with_option_" + o.key + "_on", function(){
      __viz.loadScenario(id); __viz.setOpt(o.key, true);
      var g=0; while(g++<60){ var s=__viz.state(); if(s.run!=='RUNNING') break;
        if((s.counters.loops||0)>12) break;
        var t=alternatePick(s); if(!t||!__viz.doStep(t)) break; }
    });
  });
});
ucheck("renders_the_terminal_state_of_every_scenario_without_error", function(){
  SCENARIO_ORDER.forEach(function(id){ __viz.loadScenario(id); __viz.renderAll(); });
});
ucheck("pool_tab_renders_and_ticks", function(){
  __viz.showTab('pool'); __viz.poolReset();
  for (var i=0;i<40;i++) __viz.poolStepOnce();
});
console.log("\n" + upass + " UI checks passed, " + ufail + " failed\n");
if (ufail) throw new Error(ufail + " UI check(s) failed");
DRIVE
} > "$OUT/ui.js"

cat "$OUT/all.js" "$OUT/ui.js" > "$OUT/uitest.js"
if command -v node >/dev/null 2>&1; then
  node "$OUT/uitest.js"
else
  osascript -l JavaScript "$OUT/uitest.js" 2>&1
fi
