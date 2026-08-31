var P=0, F=[];
function ok(n,c,x){ if(c)P++; else F.push(n+(x!==undefined?'  ['+x+']':'')); }
function has(s,sub){ return String(s).indexOf(sub)>=0; }
function $$(id){ return ELS[id]; }

boot();
ok('boot populated the scenario list', has($$('scenSel').innerHTML,'option') && has($$('scenSel').innerHTML,'basic'));
ok('boot rendered a first frame', $$('pages').innerHTML.length>0 && $$('stats').innerHTML.length>0);
ok('boot showed the prediction', $$('pq').textContent.length>20 && has($$('popts').innerHTML,'button'));
ok('back is disabled at step 0', $$('btnBack').disabled===true);

var COMBOS=[['on','off','67','on'],['off','off','67','on'],['on','on','67','on'],
            ['on','off','100','on'],['on','on','100','off'],['off','on','100','off']];
var crashed=[], emptied=[], dialBad=[];
for (var si=0; si<SCENARIOS.length; si++){
  var sc=SCENARIOS[si];
  for (var ci=0; ci<COMBOS.length; ci++){
    $$('dHold').value=COMBOS[ci][0]; $$('dHot').value=COMBOS[ci][1];
    $$('dFill').value=COMBOS[ci][2]; $$('dFreeze').value=COMBOS[ci][3];
    try {
      $$('scenSel').value=sc.id; $$('scenSel').fire('change');
      /* dial enable/disable must match the scenario's declared dials */
      var want={hold:'dHold',hot:'dHot',fill:'dFill',freeze:'dFreeze'};
      for (var k in want){
        var live=(sc.dials||[]).indexOf(k)>=0;
        if ($$(want[k]).disabled !== !live) dialBad.push(sc.id+'/'+k);
      }
      /* forward through every step */
      var n=UI.states.length;
      for (var i=1;i<n;i++){
        stepFwd();
        if (!$$('pages').innerHTML.length || !$$('stats').innerHTML.length ||
            !$$('lanes').innerHTML.length || !$$('views').innerHTML.length) emptied.push(sc.id+'@'+i);
      }
      ok('['+sc.id+'/'+ci+'] step is disabled at the end', $$('btnStep').disabled===true);
      /* backward all the way */
      for (var i=n-1;i>0;i--) stepBack();
      ok('['+sc.id+'/'+ci+'] back is disabled at step 0 again', $$('btnBack').disabled===true);
      /* jump controls */
      runToEnd(); resetAll();
      /* inspect a few slots at the end state */
      runToEnd();
      selSlot(0,1); selSlot(1,0); selSlot(0,5);
      if (!has($$('inspector').textContent,'ctid') &&
          !has($$('inspector').textContent,'does not exist')) emptied.push(sc.id+'/inspector-oob');
      selSlot(0,0);
      if (!has($$('inspector').textContent,'ctid')) emptied.push(sc.id+'/inspector');
      /* answer the prediction, both a right and a wrong option */
      pick(sc.predict.correct);
      if (!has($$('pres').innerHTML,'✅')) emptied.push(sc.id+'/predict-right');
      pick((sc.predict.correct+1)%sc.predict.opts.length);
      if (!has($$('pres').innerHTML,'❌')) emptied.push(sc.id+'/predict-wrong');
      /* play / pause toggle */
      autoRun(); autoRun();
    } catch(e){ crashed.push(sc.id+'/'+ci+': '+e); }
  }
}
ok('no scenario x dial combination throws', crashed.length===0, crashed.slice(0,3).join(' | '));
ok('no panel ever renders empty', emptied.length===0, emptied.slice(0,5).join(', '));
ok('dials are enabled exactly where the scenario declares them', dialBad.length===0, dialBad.slice(0,5).join(','));

/* specific rendered content */
$$('scenSel').value='lunch'; $$('dHold').value='on'; $$('scenSel').fire('change'); runToEnd();
ok('lunch/hold shows the developer holding the horizon', has($$('stats').innerHTML,'Dev (psql, idle)'));
ok('lunch/hold reports zero removed', has($$('stats').innerHTML,'>0<'));
ok('the horizon line is drawn with its holder named', has($$('lanes').innerHTML,'held by Dev'));
$$('dHold').value='off'; $$('dHold').fire('change'); runToEnd();
ok('lunch/free shows nobody holding the horizon', has($$('stats').innerHTML,'nobody'));

$$('scenSel').value='hot'; $$('dHot').value='off'; $$('dFill').value='67'; $$('scenSel').fire('change'); runToEnd();
var hotHtml=$$('stats').innerHTML;
ok('HOT run reports HOT updates', has(hotHtml,'HOT updates'));
ok('HOT run draws a REDIRECT slot', has($$('pages').innerHTML,'REDIRECT'));
ok('a HOT chain is flagged in the index panel', has($$('index').innerHTML,'↷'));
$$('dHot').value='on'; $$('dHot').fire('change'); runToEnd();
ok('with the index present there is no REDIRECT', !has($$('pages').innerHTML,'REDIRECT'));

$$('scenSel').value='wrap'; $$('dFreeze').value='off'; $$('scenSel').fire('change'); runToEnd();
ok('wraparound with freezing off shows "no row"', has($$('views').innerHTML,'no row'));
$$('dFreeze').value='on'; $$('dFreeze').fire('change'); runToEnd();
ok('wraparound with freezing on still shows the value', has($$('views').innerHTML,'Meera'));
ok('frozen tuples are drawn frozen', has($$('pages').innerHTML,'(f)'));

$$('scenSel').value='basic'; $$('scenSel').fire('change');
ok('scenario 1 has no live dials', has($$('dialNote').innerHTML,'no dials'));
ok('the legend is rendered', has($$('legend').innerHTML,'horizon protects it'));
ok('the log is rendered', $$('logbox').textContent.length>10);

var out='PASS '+P+' / '+(P+F.length);
if (F.length){ out+='\nFAILURES:\n  ✗ '+F.join('\n  ✗ '); }
out;
