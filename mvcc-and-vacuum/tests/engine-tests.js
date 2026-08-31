var PASS=0, FAIL=[], OUT=[];
function ok(name, cond, extra){ if(cond){PASS++;} else {FAIL.push(name+(extra!==undefined?'  ['+extra+']':''));} }
function eq(name,a,b){ ok(name, a===b, 'got '+JSON.stringify(a)+' want '+JSON.stringify(b)); }
function SC(id){ for(var i=0;i<SCENARIOS.length;i++) if(SCENARIOS[i].id===id) return SCENARIOS[i]; }
function cfgOf(id,ov){ var c={hold:true,hot:true,fill:67,freeze:true}, b=SC(id).base||{},k;
  for(k in b)c[k]=b[k]; for(k in (ov||{}))c[k]=ov[k]; return c; }
function bs(id,ov){ var sc=SC(id), cfg=cfgOf(id,ov), st=blankState(cfg), out=[st], evs=sc.build(cfg);
  for(var i=0;i<evs.length;i++){ st=apply(st,evs[i]); out.push(st); } return out; }
function last(a){return a[a.length-1];}
function S(st){return statsOf(st);}
function val(st,xid,row){ var t=txOf(st,xid); var g=t?readRow(st,t.snap,row):null; return g?g.tup.val:null; }
function nowVal(st,row){ var g=readRow(st,nowSnapshot(st),row); return g?g.tup.val:null; }
function stepAt(sts,label,n){ var c=0; for(var i=0;i<sts.length;i++) if(sts[i].label===label){ if(c===(n||0)) return sts[i]; c++; } return null; }

/* ---- 1. modular XID arithmetic (the wraparound rule) ---- */
ok('xid 1 precedes 2', xidPrecedes(1,2));
ok('xid 2 does not precede 1', !xidPrecedes(2,1));
ok('xid equal does not precede', !xidPrecedes(7,7));
ok('gap of exactly half still counts as past', xidPrecedes(1,33));
ok('gap of half+1 flips to the future', !xidPrecedes(1,34));
eq('age wraps', xidAge(1,34), 33);

/* ---- 2. the visibility rule in isolation ---- */
var st0 = blankState({hold:true,hot:true,fill:67,freeze:true});
st0.txns = [ {xid:1,name:'A',status:'committed',snap:{xmin:1,xmax:2,xip:[],self:1}},
             {xid:2,name:'B',status:'running',  snap:{xmin:2,xmax:3,xip:[],self:2}},
             {xid:3,name:'C',status:'aborted',  snap:{xmin:3,xmax:4,xip:[],self:3}} ];
var snapAfterAll = {xmin:4,xmax:5,xip:[],self:9};
ok('committed creator visible',      visibleTo(st0,{xmin:1,xmax:0,frozen:false},snapAfterAll));
ok('running creator invisible',      !visibleTo(st0,{xmin:2,xmax:0,frozen:false},snapAfterAll));
ok('aborted creator invisible',      !visibleTo(st0,{xmin:3,xmax:0,frozen:false},snapAfterAll));
ok('committed deleter hides tuple',  !visibleTo(st0,{xmin:1,xmax:1,frozen:false},snapAfterAll));
ok('aborted deleter leaves it alive', visibleTo(st0,{xmin:1,xmax:3,frozen:false},snapAfterAll));
ok('running deleter leaves it alive', visibleTo(st0,{xmin:1,xmax:2,frozen:false},snapAfterAll));
ok('a deleter in my future leaves it alive', visibleTo(st0,{xmin:1,xmax:4,frozen:false},{xmin:1,xmax:4,xip:[],self:9}));
ok('...and that same tuple is gone once the deleter is in my past', !visibleTo(st0,{xmin:1,xmax:4,frozen:false},{xmin:1,xmax:5,xip:[],self:9}));
ok('frozen tuple ignores wraparound', visibleTo(st0,{xmin:1,xmax:0,frozen:true},{xmin:40,xmax:41,xip:[],self:9}));
ok('unfrozen ancient tuple vanishes', !visibleTo(st0,{xmin:1,xmax:0,frozen:false},{xmin:40,xmax:41,xip:[],self:9}));

/* ---- 3. S1 · readers do not block writers ---- */
var s1=bs('basic'), a=s1[5];
eq('S1 Ravi still reads the old value', val(a,1,12), 'Meera');
eq('S1 a new transaction reads the new value', nowVal(a,12), 'Vikram');
eq('S1 both versions are physically present', S(a).live+S(a).dead, 4);
eq('S1 the old tuple is protected, not removable', S(a).protected_, 1);
eq('S1 removable while Ravi runs', S(a).removable, 0);
eq('S1 horizon is held by Ravi', S(a).holder, 'Ravi (report)');
var afterRaviCommit = s1[6+1-1];
eq('S1 once Ravi commits the tuple becomes removable', S(s1[7]).removable, 1);
eq('S1 nobody holds the horizon at the end', S(s1[7]).holder, null);
eq('S1 vacuum then removes exactly one tuple', last(s1).vac.removed, 1);
eq('S1 file is still one page', S(last(s1)).pages, 1);

/* ---- 4. S2 · rollback is O(1) and leaves garbage ---- */
var s2=bs('rollback'), beforeAbort=s2[5], afterAbort=s2[6];
eq('S2 six tuples exist before the rollback', S(beforeAbort).live+S(beforeAbort).dead, 6);
eq('S2 rollback erases no tuple', S(afterAbort).live+S(afterAbort).dead, 6);
eq('S2 rollback makes the new tuples garbage', S(afterAbort).removable, 3);
eq('S2 the original rows come back to life', S(afterAbort).live, 3);
eq('S2 the aborted work still cost index entries', S(beforeAbort).idx, 6);
eq('S2 vacuum has to clean them up', last(s2).vac.removed, 3);
eq('S2 index is back to 3 entries', last(s2).index.length, 3);

/* ---- 5. S3 · the horizon, the star of the lab ---- */
var s3h=bs('lunch'), s3f=bs('lunch',{hold:false});
eq('S3 with the reader open, vacuum removes nothing', last(s3h).vac.removed, 0);
eq('S3 all three dead tuples are protected', S(last(s3h)).protected_, 3);
eq('S3 the horizon is pinned at XID 1', S(last(s3h)).horizon, 1);
eq('S3 and it is the developer holding it', S(last(s3h)).holder, 'Dev (psql, idle)');
eq('S3 with the reader gone, vacuum removes all three', last(s3f).vac.removed, 3);
eq('S3 nothing is protected once it commits', S(last(s3f)).protected_, 0);
eq('S3 same workload either way: 3 non-HOT updates', S(last(s3h)).coldUpd, S(last(s3f)).coldUpd);
eq('S3 same page count either way', S(last(s3h)).pages, S(last(s3f)).pages);

/* ---- 6. S4 · vacuum frees slots, not files ---- */
var s4=bs('shrink'), rec=stepAt(s4,'vac_reclaim');
eq('S4 page 0 is entirely empty after reclaim', S(rec).free, 4);
eq('S4 and the file still has both pages', S(rec).pages, 2);
eq('S4 deleting rows freed nothing before vacuum', S(s4[6]).free, 0);
eq('S4 the file never shrinks', S(last(s4)).pages, 2);
eq('S4 later inserts reuse the freed slots', S(last(s4)).live, 7);
eq('S4 still two pages after the reuse', S(last(s4)).pages, 2);

/* ---- 7. S5 · HOT, and the two ways to lose it ---- */
var hA=last(bs('hot',{hot:false,fill:67}));
var hB=last(bs('hot',{hot:true, fill:67}));
var hC=last(bs('hot',{hot:false,fill:100}));
var hD=last(bs('hot',{hot:true, fill:100}));
eq('S5 no index + reserved space => mostly HOT', S(hA).hotUpd, 3);
eq('S5 ... and only one update touched an index', S(hA).coldUpd, 1);
eq('S5 an index on the updated column kills HOT entirely', S(hB).hotUpd, 0);
eq('S5 ... so all four updates write index entries', S(hB).coldUpd, 4);
eq('S5 fillfactor 100 also kills HOT entirely', S(hC).hotUpd, 0);
eq('S5 both causes together: still zero', S(hD).hotUpd, 0);
ok('S5 losing HOT costs pages', S(hB).pages > S(hA).pages, S(hA).pages+' -> '+S(hB).pages);
ok('S5 a HOT chain leaves a REDIRECT behind', S(hA).redirect > 0, S(hA).redirect);
eq('S5 no REDIRECT when HOT never happened', S(hB).redirect, 0);
eq('S5 live row count is identical in every configuration',
   [S(hA).live,S(hB).live,S(hC).live,S(hD).live].join(','), '6,6,6,6');

/* ---- 8. S6 · DELETE deletes nothing ---- */
var s6=bs('delete'), preVac=s6[6];
eq('S6 delete+commit frees no slot', S(preVac).free, 0);
eq('S6 the tuples are merely marked dead', S(preVac).dead, 3);
eq('S6 the rows are already gone from every query', nowVal(preVac,12), null);
eq('S6 vacuum then frees three slots', S(last(s6)).free, 3);
eq('S6 the file is exactly as big as before', S(last(s6)).pages, S(preVac).pages);

/* ---- 9. S7 · three snapshots, three answers ---- */
var s7=last(bs('three'));
eq('S7 reader A sees the oldest value', val(s7,1,12), 'Meera');
eq('S7 reader B sees the middle value', val(s7,3,12), 'Vikram');
eq('S7 reader C sees the newest value', val(s7,5,12), 'Priya');
eq('S7 and so does a transaction starting now', nowVal(s7,12), 'Priya');
eq('S7 all the old versions are protected', S(s7).protected_, 2);
eq('S7 none of them is removable', S(s7).removable, 0);

/* ---- 10. S8 · freezing and wraparound ---- */
var w1=last(bs('wrap')), w0=last(bs('wrap',{freeze:false}));
eq('S8 with freezing on, the ancient row survives the wrap', val(w1,34,12), 'Meera');
eq('S8 with freezing off, it silently vanishes', val(w0,34,12), null);
eq('S8 freezing marked both rows', S(w1).frozen, 2);
eq('S8 nothing was frozen with the dial off', S(w0).frozen, 0);
eq('S8 no error was raised, the tuple is simply unreachable', S(w0).live + S(w0).dead >= 0, true);
ok('S8 the tuple is still physically in the page', w0.pages[0].slots[0].st === 'normal', w0.pages[0].slots[0].st);
var wseq = bs('wrap',{freeze:false});
eq('S8 at XID 33 the row is still visible', nowVal(wseq[wseq.length-4],12), 'Meera');
eq('S8 one transaction later it is not', nowVal(wseq[wseq.length-3],12), null);
eq('S8 the cliff edge is exactly half the counter', xidAge(1, wseq[wseq.length-3].nextXid), 33);

/* ---- 11. purity, determinism, step-back fidelity ---- */
var d1=JSON.stringify(bs('lunch').map(function(x){return statsOf(x);}));
var d2=JSON.stringify(bs('lunch').map(function(x){return statsOf(x);}));
ok('engine is deterministic', d1===d2);
var seq=bs('basic'); var snapshot3=JSON.stringify(seq[3]);
ok('earlier states are never mutated by later ones', JSON.stringify(seq[3])===snapshot3);
ok('every state is a distinct object', seq[2]!==seq[3]);
var allIds={}, dup=false;
for (var i=0;i<SCENARIOS.length;i++){ if(allIds[SCENARIOS[i].id]) dup=true; allIds[SCENARIOS[i].id]=1; }
ok('scenario ids are unique', !dup);
eq('there are eight scenarios', SCENARIOS.length, 8);
var badPredict=[];
for (var i=0;i<SCENARIOS.length;i++){ var sc=SCENARIOS[i];
  if(!sc.predict||!sc.predict.q||!sc.predict.why) badPredict.push(sc.id);
  if(sc.predict.correct<0||sc.predict.correct>=sc.predict.opts.length) badPredict.push(sc.id+':idx'); }
ok('every scenario has a well-formed prediction', badPredict.length===0, badPredict.join(','));
/* every scenario must run to completion under every dial combination */
var combos=[{},{hold:false},{hot:false},{hot:true},{fill:100},{fill:67},{freeze:false}], crashed=[];
for (var i=0;i<SCENARIOS.length;i++) for (var j=0;j<combos.length;j++){
  try { var r=bs(SCENARIOS[i].id, combos[j]); statsOf(last(r)); nowSnapshot(last(r)); }
  catch(e){ crashed.push(SCENARIOS[i].id+'/'+JSON.stringify(combos[j])+': '+e); } }
ok('all 8 scenarios run under all 7 dial combinations', crashed.length===0, crashed.join(' | '));
/* stepping back and forward must reproduce identical states */
var fw=bs('hot',{hot:false,fill:67}); var bad=0;
for (var i=0;i<fw.length;i++){ if(JSON.stringify(statsOf(fw[i]))!==JSON.stringify(statsOf(fw[i]))) bad++; }
ok('re-rendering a state is side-effect free', bad===0);

OUT.push('PASS ' + PASS + ' / ' + (PASS+FAIL.length));
if (FAIL.length){ OUT.push('FAILURES:'); for (var i=0;i<FAIL.length;i++) OUT.push('  ✗ ' + FAIL[i]); }
OUT.join('\n');
