var ELS = {};
function mkEl(id){
  return { id:id, innerHTML:'', textContent:'', className:'', value:'', disabled:false,
           _h:{}, style:{}, parentNode:{ style:{} },
           addEventListener:function(k,f){ this._h[k]=f; },
           fire:function(k){ if(this._h[k]) this._h[k](); } };
}
['scenSel','btnRun','btnBack','btnStep','btnEnd','btnReset','dHold','dHot','dFill','dFreeze',
 'predict','pq','popts','pres','story','lanes','axis','pages','legend','index','views','stats',
 'logbox','inspector','stepLbl','dialNote'].forEach(function(id){ ELS[id]=mkEl(id); });
ELS.dHold.value='on'; ELS.dHot.value='off'; ELS.dFill.value='67'; ELS.dFreeze.value='on';
var document = { getElementById:function(id){ return ELS[id]; }, addEventListener:function(){} };
var _timers = {}, _tid = 1;
function setInterval(f,ms){ _timers[_tid]=f; return _tid++; }
function clearInterval(t){ delete _timers[t]; }
