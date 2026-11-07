window.CQA = window.CQA || {};
/* Выгрузка результатов в формате сдачи (CQA Student Guide, приложение «Формат JSON 1.0»):
 * results/dev/results.json и results/prod/results.json. Данные формирует backend (GET /api/results/export),
 * здесь — проверка формата, сводка и скачивание файла. Правила совпадают со scripts/results.py. */
CQA.results = (()=>{
  const {icon,esc,badge,toast,empty,fmtScore}=CQA.ui;
  const CODES=['contact','discovery','identification','consultation','empathy','dialogue','closure','critical'];
  const TITLES={contact:'Установление контакта',discovery:'Выявление потребности',identification:'Идентификация',consultation:'Корректность и полнота',empathy:'Эмпатия',dialogue:'Ведение, скорость, грамотность',closure:'Завершение',critical:'Грубые ошибки'};
  const ID2CODE={c1:'contact',c2:'discovery',c3:'identification',c4:'consultation',c5:'empathy',c6:'dialogue',c7:'closure',c8:'critical'};
  const ALLOWED={contact:[true,false],discovery:[true,false,null],identification:[true,false,null],consultation:[0,5,10,15,20,null],empathy:[true,false],dialogue:[0,4,7,10],closure:[true,false,null],critical:[true,false]};
  let doc=null, params={groupId:'DEV',checklistVersionId:'',source:'final',expect:''};

  /* ---------- формат (используется и mock-режимом api.js) ---------- */
  const format={
    error(id,msg){return {conversation_id:id,status:'error',criteria:[],score:null,error:msg||'Не удалось оценить диалог'};},
    toEntry(id,r){
      if(!r||!Array.isArray(r.criteria))return format.error(id,'Нет результата оценки');
      const by={};for(const x of r.criteria){const code=CODES.includes(String(x.criterionId))?x.criterionId:ID2CODE[x.criterionId];if(code&&!by[code])by[code]=x;}
      const missing=CODES.filter(c=>!by[c]);if(missing.length)return format.error(id,'В версии чек-листа нет обязательных критериев: '+missing.join(', '));
      let sp=0,sw=0,critical=false;
      const criteria=CODES.map(code=>{const x=by[code];let value=null;if(!x.isNa&&x.answer!=null)value=x.type==='SCALE'?Number(x.answer):x.answer==='YES';
        let turns=[...new Set((x.evidenceIds||[]).map(n=>Number(n)+1))].sort((a,b)=>a-b);
        if(!turns.length&&x.checkedRange){const f=Number(x.checkedRange.from),t=Number(x.checkedRange.to);turns=t-f<=5?Array.from({length:t-f+1},(_,k)=>f+k+1):[f+1,t+1];}
        if(x.type==='FLAG')critical=value===true;else if(!x.isNa&&Number(x.weight)>0){sw+=Number(x.weight);sp+=Number(x.points)||0;}
        return {criterion_id:code,value,rationale:String(x.reason||'').trim(),evidence_turns:turns};});
      return {conversation_id:id,status:'ok',criteria,score:{final_score:sw?Math.round(sp/sw*10000)/100:null,critical_flag:critical}};
    },
    validate(d,expect){const errors=[],warns=[];
      if(!d||typeof d!=='object')return {errors:['Корень должен быть объектом'],warns};
      if(d.schema_version!=='1.0')errors.push('schema_version должен быть "1.0"');
      if(!String(d.team_name||'').trim())errors.push('team_name пуст');
      if(!d.model||!['local','external_api','hybrid'].includes(d.model.mode)||!String(d.model.name||'').trim())errors.push('model: нужен {mode: local|external_api|hybrid, name}');
      else if(/mock/i.test(d.model.name))warns.push('Оценки сделаны в демо-режиме (mock), без настоящей модели');
      const res=Array.isArray(d.results)?d.results:[];if(!Array.isArray(d.results))errors.push('results должен быть массивом');
      if(expect&&res.length!==Number(expect))errors.push(`Ожидается ${expect} записей, получено ${res.length}`);
      const seen=new Set();
      res.forEach((r,i)=>{const w=`${r.conversation_id||'#'+i}`;if(!r.conversation_id){errors.push(`Запись ${i+1}: нет conversation_id`);return;}
        if(seen.has(r.conversation_id))errors.push(`${w}: повторяющийся ID`);seen.add(r.conversation_id);
        if(r.status==='error'){if(r.criteria?.length||r.score!==null||!r.error)errors.push(`${w}: для error нужны criteria=[], score=null и текст ошибки`);else warns.push(`${w}: не оценён — ${r.error}`);return;}
        if(r.status!=='ok'){errors.push(`${w}: status должен быть ok или error`);return;}
        const ids=(r.criteria||[]).map(c=>c.criterion_id);if(ids.length!==8||CODES.some(c=>!ids.includes(c)))errors.push(`${w}: нужны все 8 критериев по одному разу`);
        let crit;(r.criteria||[]).forEach(c=>{const a=ALLOWED[c.criterion_id];if(!a)return;if(!a.some(v=>v===c.value))errors.push(`${w}: ${c.criterion_id} = ${JSON.stringify(c.value)} недопустимо`);if(c.criterion_id==='critical')crit=c.value;if(!Array.isArray(c.evidence_turns)||c.evidence_turns.some(t=>!Number.isInteger(t)||t<1))errors.push(`${w}: ${c.criterion_id}.evidence_turns — номера реплик с 1`);if(!String(c.rationale||'').trim())warns.push(`${w}: ${c.criterion_id} без обоснования`);});
        const fs=r.score?.final_score;if(fs!=null&&(typeof fs!=='number'||fs<0||fs>100||Math.round(fs*100)/100!==fs))errors.push(`${w}: final_score — число 0..100 с 2 знаками`);
        if(r.score?.critical_flag!==crit)errors.push(`${w}: critical_flag должен совпадать с critical`);});
      return {errors,warns};}
  };

  /* ---------- экран ---------- */
  function valueView(code,v){if(v===null)return badge('N/A','cqa-muted');if(v===true)return badge(code==='critical'?'Выявлено':'Да',code==='critical'?'cqa-bad':'cqa-good');if(v===false)return badge(code==='critical'?'Нет':'Нет',code==='critical'?'cqa-good':'cqa-bad');return badge(String(v),'cqa-blue');}
  function summary(){if(!doc)return '';const res=doc.results||[];const ok=res.filter(r=>r.status==='ok');const crit=ok.filter(r=>r.score?.critical_flag).length;const avg=ok.filter(r=>r.score?.final_score!=null);const mean=avg.length?avg.reduce((s,r)=>s+r.score.final_score,0)/avg.length:null;const v=format.validate(doc,params.expect);
    const cards=[['Записей',res.length,`Набор ${esc(params.groupId)}`,'file'],['Оценено',ok.length,`Ошибок: ${res.length-ok.length}`,'check'],['Средний балл',mean==null?'—':fmtScore(mean),'final_score','bars'],['Грубые ошибки',crit,'critical_flag = true','alert']];
    return `<section class="cqa-stats">${cards.map(([l,val,f,ic])=>`<article class="cqa-stat"><div class="cqa-stat-top"><span class="cqa-stat-label">${esc(l)}</span><span class="cqa-stat-icon">${icon(ic,18)}</span></div><div class="cqa-stat-value">${esc(val)}</div><small class="cqa-stat-foot">${esc(f)}</small></article>`).join('')}</section>
    <section class="cqa-panel"><header class="cqa-panel-header"><div><h3 class="cqa-panel-title">Проверка формата</h3><p class="cqa-panel-caption">schema 1.0 · модель: ${esc(doc.model?.mode)} / ${esc(doc.model?.name)} · команда: ${esc(doc.team_name)}</p></div>${v.errors.length?badge(`Ошибок: ${v.errors.length}`,'cqa-bad'):badge('Формат корректен','cqa-good')}</header><div class="cqa-panel-body cqa-results-checks">${v.errors.map(e=>`<div class="cqa-check-line cqa-check-bad">${icon('close',14)} ${esc(e)}</div>`).join('')}${v.warns.map(e=>`<div class="cqa-check-line cqa-check-warn">${icon('alert',14)} ${esc(e)}</div>`).join('')}${!v.errors.length&&!v.warns.length?`<div class="cqa-check-line cqa-check-ok">${icon('check',14)} Все записи заполнены, ID уникальны, значения и номера реплик соответствуют правилам.</div>`:''}
      <div class="cqa-results-actions"><button class="cqa-btn cqa-btn-primary" data-action="results-download">${icon('save',16)} Скачать results.json</button><button class="cqa-btn cqa-btn-ghost" data-action="results-copy">${icon('copy',16)} Копировать JSON</button><span class="cqa-text-dim">Положите файл в <b>results/${/^dev$/i.test(params.groupId)?'dev':'prod'}/results.json</b>, затем commit и push.</span></div></div></section>
    <section class="cqa-panel"><header class="cqa-panel-header"><div><h3 class="cqa-panel-title">Содержимое</h3><p class="cqa-panel-caption">Номера реплик в файле — с 1 (в интерфейсе — с 0, как в обосновании модели)</p></div></header><div class="cqa-table-wrap"><table class="cqa-table cqa-results-table"><thead><tr><th>conversation_id</th><th>Статус</th>${CODES.map(c=>`<th title="${esc(TITLES[c])}">${esc(c)}</th>`).join('')}<th>final_score</th></tr></thead><tbody>${res.map(r=>`<tr class="cqa-row-click" data-action="open-conversation" data-id="${esc(r.conversation_id)}"><td><span class="cqa-table-id">${esc(r.conversation_id)}</span></td><td>${r.status==='ok'?badge('ok','cqa-good'):`<span title="${esc(r.error)}">${badge('error','cqa-bad')}</span>`}</td>${CODES.map(c=>{const x=(r.criteria||[]).find(k=>k.criterion_id===c);return `<td title="${esc(x?.rationale||r.error||'')}">${x?valueView(c,x.value):'—'}</td>`;}).join('')}<td><b>${r.score?.final_score==null?'—':fmtScore(r.score.final_score)}</b></td></tr>`).join('')}</tbody></table></div></section>`;}
  async function page(){const root=document.getElementById('cqa-main');let versions=[];try{versions=await CQA.api.get('/api/checklists');}catch(e){toast(e.message,true);}
    root.innerHTML=`<header class="cqa-page-head"><div><p class="cqa-eyebrow">RESULTS / SUBMISSION</p><h1 class="cqa-h1">Выгрузка результатов</h1><p class="cqa-subtitle">Файл для сдачи в формате JSON 1.0 · results/dev и results/prod</p></div></header>
    <section class="cqa-panel"><div class="cqa-filters"><label class="cqa-field" style="min-width:160px"><span class="cqa-field-label">Группа (group_id)</span><input class="cqa-input" id="cqa-res-group" value="${esc(params.groupId)}" /></label><label class="cqa-field" style="min-width:230px"><span class="cqa-field-label">Версия чек-листа</span><select class="cqa-select" id="cqa-res-checklist"><option value="">Последняя оценка диалога</option>${versions.map(v=>`<option value="${v.id}" ${String(params.checklistVersionId)===String(v.id)?'selected':''}>${esc(v.name)} · v${v.version}</option>`).join('')}</select></label><label class="cqa-field" style="min-width:230px"><span class="cqa-field-label">Источник оценки</span><select class="cqa-select" id="cqa-res-source"><option value="final" ${params.source==='final'?'selected':''}>С учётом апелляций (final)</option><option value="original" ${params.source==='original'?'selected':''}>Исходная оценка AI</option></select></label><label class="cqa-field" style="width:150px"><span class="cqa-field-label">Ожидается записей</span><input class="cqa-input" id="cqa-res-expect" inputmode="numeric" placeholder="DEV 65 · PROD 10" value="${esc(params.expect)}" /></label><button class="cqa-btn cqa-btn-primary" data-action="results-generate" style="align-self:end">${icon('file',16)} Сформировать</button></div>
    <div class="cqa-panel-body"><div class="cqa-insight">${icon('info',16)}<span>Для контрольного набора (1 час до стоп-кода) удобнее одна команда: <code>python scripts/results.py prod --group &lt;ID&gt; --expect 10</code> — импорт, оценка, повтор упавших, выгрузка в results/prod/results.json и проверка.</span></div></div></section>
    <div id="cqa-results-out">${doc?summary():''}</div>`;}
  async function generate(){params.groupId=document.getElementById('cqa-res-group')?.value.trim()||'DEV';params.checklistVersionId=document.getElementById('cqa-res-checklist')?.value||'';params.source=document.getElementById('cqa-res-source')?.value||'final';params.expect=(document.getElementById('cqa-res-expect')?.value||'').trim();
    const b=document.querySelector('[data-action="results-generate"]');if(b){b.disabled=true;b.innerHTML='<span class="cqa-loader"></span> Формируем…';}
    try{const qs=new URLSearchParams({groupId:params.groupId,source:params.source});if(params.checklistVersionId)qs.set('checklistVersionId',params.checklistVersionId);doc=await CQA.api.get('/api/results/export?'+qs);const out=document.getElementById('cqa-results-out');if(out)out.innerHTML=summary();toast(`Сформировано записей: ${doc.results?.length||0}`);}
    catch(e){toast(e.message,true);const out=document.getElementById('cqa-results-out');if(out)out.innerHTML=empty('Не удалось сформировать файл',e.message);}
    finally{if(b){b.disabled=false;b.innerHTML=icon('file',16)+' Сформировать';}}}
  function download(){if(!doc)return;const blob=new Blob([JSON.stringify(doc,null,2)+'\n'],{type:'application/json;charset=utf-8'});const a=document.createElement('a');a.href=URL.createObjectURL(blob);a.download='results.json';document.body.appendChild(a);a.click();setTimeout(()=>{URL.revokeObjectURL(a.href);a.remove();},500);}
  async function copy(){if(!doc)return;try{await navigator.clipboard.writeText(JSON.stringify(doc,null,2));toast('JSON скопирован');}catch(_){toast('Копирование недоступно в этом браузере',true);}}
  function action(name){if(name==='results-generate'){generate();return true;}if(name==='results-download'){download();return true;}if(name==='results-copy'){copy();return true;}return false;}
  return {page,action,format};
})();
