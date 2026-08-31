#!/bin/sh
# Cross-checks the browser engine against the Java one. There is no node here, so
# the page's JavaScript is run under macOS's JavaScriptCore with a stubbed DOM.
# Every shared cell of the two matrices must agree.
set -e
cd "$(dirname "$0")"
python3 - <<'PY'
import re
s = open('visualization.html').read()
open('/tmp/iso.js','w').write(re.search(r'<script>([\s\S]*)</script>', s).group(1))
PY
cat > /tmp/isostub.js <<'JS'
function mkEl(id){ return { id, innerHTML:'', textContent:'', className:'', value:'', disabled:false,
  style:{}, dataset:{}, classList:{add(){},remove(){},contains(){return false}}, closest(){return null},
  getBoundingClientRect(){return{left:0,top:0,width:900,height:400}}, querySelectorAll(){return []} }; }
const _e = {};
var document = { getElementById(i){ if(!_e[i]) _e[i]=mkEl(i); return _e[i]; },
  addEventListener(){}, querySelectorAll(){ return { forEach(){} } }, createElement(t){ return mkEl(t) } };
var window = {};
JS
{ cat /tmp/isostub.js; echo '(function(){'; sed 's/^"use strict";//' /tmp/iso.js; cat <<'JS'
  var out = [], MAP = { allowed:'ALLOWED', err:'safe(err)', wait:'safe(wait)', safe:'safe' };
  SCENARIOS.forEach(function(sc){
    var row = [];
    LEVELS.forEach(function(l){ row.push(MAP[runSchedule(sc, l.id, sc.steps.map(function(_,i){return i;})).verdict]); });
    out.push(sc.key.padEnd(18) + row.join('  '));
  });
  try { for(var i=0;i<SCENARIOS.length;i++){ load(i); for(var k=0;k<4;k++){ S.step++; renderAll(); } }
        LEVELS.forEach(function(l){ S.level=l.id; renderAll(); }); move(1,-1);
        out.push('all controls exercised without error'); }
  catch(e){ out.push('UI FAILURE: ' + e.message); }
  return out.join('\n');
JS
echo '})()'; } > /tmp/isorun.js
echo "browser engine:"
osascript -l JavaScript /tmp/isorun.js
echo
echo "java engine (compare the same rows):"
java src/MiniLab.java | sed -n '3,12p'
