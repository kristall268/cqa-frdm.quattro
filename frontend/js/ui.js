window.CQA = window.CQA || {};
CQA.ui = (() => {
  const paths = {
    home:'<path d="m3 10 9-7 9 7v10a1 1 0 0 1-1 1h-5v-7H9v7H4a1 1 0 0 1-1-1z"/>',
    grid:'<rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/>',
    message:'<path d="M21 11.5a8.4 8.4 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.4 8.4 0 0 1-3.8-.9L3 21l1.9-5.7a8.4 8.4 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.4 8.4 0 0 1 3.8-.9h.5a8.5 8.5 0 0 1 8 8z"/>',
    checklist:'<rect x="3" y="3" width="18" height="18" rx="3"/><path d="m7 9 1.5 1.5L11 8M13.5 9H17M7 15l1.5 1.5L11 14M13.5 15H17"/>',
    shield:'<path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10Z"/><path d="m9 12 2 2 4-4"/>',
    sparkle:'<path d="m12 3 1.5 6 6 1.5-6 1.5L12 18l-1.5-6L4 10.5 10.5 9z"/><path d="m19 17 .6 2.4L22 20l-2.4.6L19 23l-.6-2.4L16 20l2.4-.6z"/>',
    search:'<circle cx="11" cy="11" r="7"/><path d="m20 20-4-4"/>',
    arrow:'<path d="M5 12h14m-7-7 7 7-7 7"/>',back:'<path d="m15 18-6-6 6-6"/>',down:'<path d="m6 9 6 6 6-6"/>',chevron:'<path d="m9 18 6-6-6-6"/>',
    plus:'<path d="M12 5v14M5 12h14"/>',check:'<path d="m5 12 4 4L19 6"/>',close:'<path d="M18 6 6 18M6 6l12 12"/>',
    clock:'<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
    phone:'<path d="M22 16.9v3a2 2 0 0 1-2.2 2A19.7 19.7 0 0 1 3.1 5.2 2 2 0 0 1 5.1 3h3a2 2 0 0 1 2 1.7l.6 3a2 2 0 0 1-.6 1.8L8.3 11A16 16 0 0 0 13 15.7l1.5-1.8a2 2 0 0 1 1.8-.6l3 .6a2 2 0 0 1 1.7 2z"/>',
    bars:'<path d="M3 3v18h18M7 16v-4M12 16V7M17 16v-7"/>',
    filter:'<path d="M4 7h16M7 12h10M10 17h4"/>',
    upload:'<path d="M12 16V4m-5 5 5-5 5 5"/><path d="M4 16v3a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3"/>',
    refresh:'<path d="M20 11a8 8 0 0 0-14-5L4 8M4 3v5h5"/><path d="M4 13a8 8 0 0 0 14 5l2-2M20 21v-5h-5"/>',
    bolt:'<path d="m13 2-3 9H5l8 11 2-9h5z"/>',
    alert:'<path d="m12 3 10 18H2z"/><path d="M12 9v4m0 4h.01"/>',
    play:'<path d="m8 5 11 7-11 7z"/>',
    edit:'<path d="m16 5 3 3M4 20l4.5-1L20 7.5 16.5 4 5 15.5z"/>',
    trash:'<path d="M3 6h18M8 6V4h8v2M6 6l1 15h10l1-15M10 10v7M14 10v7"/>',
    info:'<circle cx="12" cy="12" r="9"/><path d="M12 11v5m0-9h.01"/>',
    dots:'<circle cx="5" cy="12" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/>',
    file:'<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6M8 13h8M8 17h8"/>',
    eye:'<path d="M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12Z"/><circle cx="12" cy="12" r="3"/>',
    external:'<path d="M14 4h6v6M20 4l-10 10"/><path d="M20 13v7H4V4h7"/>',
    save:'<path d="M4 3h14l3 3v15H3V3zM7 3v7h10V3M7 21v-8h10v8"/>',
    menu:'<path d="M4 7h16M4 12h16M4 17h16"/>',
    copy:'<rect x="7" y="7" width="13" height="13" rx="2"/><path d="M4 16V4h12"/>',
    layers:'<path d="m12 2 9 5-9 5-9-5zM3 12l9 5 9-5M3 17l9 5 9-5"/>',
    calendar:'<rect x="3" y="5" width="18" height="16" rx="2"/><path d="M7 3v4M17 3v4M3 10h18"/>'
  };
  function icon(name, size=18){return `<svg class="cqa-icon" style="width:${Number(size)}px;height:${Number(size)}px" viewBox="0 0 24 24" aria-hidden="true">${paths[name]||paths.dots}</svg>`;}
  function esc(v){return String(v==null?'':v).replace(/[&<>"']/g,s=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[s]));}
  const money=v=>v===null||v===undefined?'—':`$${Number(v).toFixed(4)}`;
  const fmtScore=v=>v===null||v===undefined?'—':Number(v).toFixed(2);
  function scoreClass(v){return v===null||v===undefined?'cqa-muted':Number(v)>=85?'cqa-good':Number(v)>=60?'cqa-warn':'cqa-bad';}
  function badge(t,cls='cqa-muted'){return `<span class="cqa-badge ${cls}">${esc(t)}</span>`;}
  function empty(title,desc,button=''){return `<div class="cqa-empty"><div class="cqa-empty-figure">${icon('file',29)}</div><strong>${esc(title)}</strong><p>${esc(desc)}</p>${button}</div>`;}
  function skeleton(){return `<div class="cqa-panel cqa-panel-body" style="display:grid;gap:18px;padding:26px"><div class="cqa-skeleton" style="width:50%;height:22px"></div>${Array.from({length:6},(_,i)=>`<div class="cqa-skeleton" style="width:${78+i*3}%;height:36px"></div>`).join('')}</div>`;}
  function toast(text,isError=false){const host=document.getElementById('cqa-toast-host');if(!host)return;const el=document.createElement('div');el.className=`cqa-toast${isError?' cqa-toast-error':''}`;el.setAttribute('role','status');el.innerHTML=`${icon(isError?'alert':'check',17)}<span></span>`;el.querySelector('span').textContent=text;host.appendChild(el);setTimeout(()=>el.remove(),isError?5000:2500);}
  function modal(title,subtitle,inner,footer){const host=document.getElementById('cqa-modal-host');if(!host)return;host.innerHTML=`<div class="cqa-modal-backdrop" data-action="close-modal"><section class="cqa-modal" role="dialog" aria-modal="true" aria-label="${esc(title)}"><header class="cqa-modal-header"><div><h3>${esc(title)}</h3><p>${esc(subtitle||'')}</p></div><button class="cqa-circle-btn" type="button" data-action="close-modal" aria-label="Закрыть">${icon('close')}</button></header><div class="cqa-modal-body">${inner}</div><footer class="cqa-modal-footer">${footer||`<button class="cqa-btn cqa-btn-ghost" data-action="close-modal">Закрыть</button>`}</footer></section></div>`;let first=host.querySelector('input,textarea,button');if(first)first.focus();}
  function closeModal(){const el=document.getElementById('cqa-modal-host');if(el)el.innerHTML='';}
  function readForm(){const box=document.querySelector('.cqa-modal');return box?new FormData(box.querySelector('form')||document.createElement('form')):null;}
  function fmtDate(s){if(!s)return '—';let d=new Date(s);return Number.isNaN(d.getTime())?'—':d.toLocaleDateString('ru-RU',{day:'2-digit',month:'short',hour:'2-digit',minute:'2-digit'});}
  return {icon,esc,badge,empty,skeleton,toast,modal,closeModal,readForm,money,fmtScore,scoreClass,fmtDate};
})();

CQA.progress=(()=>{let timer=null,el=null;const stages=['Подготовка транскрипта и маскирование ПДн','Модель-оценщик анализирует 8 критериев','Проверка доказательств и формата','Вторая модель перепроверяет оценку','Арбитр решает спорные критерии','Расчёт баллов и итога'];function start(){stop(true);el=document.createElement('div');el.className='cqa-progress';el.innerHTML='<div class="cqa-progress-card"><div class="cqa-progress-head"><span class="cqa-loader"></span><strong>AI анализирует диалог</strong><span class="cqa-progress-pct">0%</span></div><div class="cqa-progress-bar"><i></i></div><div class="cqa-progress-stage"></div></div>';document.body.appendChild(el);let p=0,t0=Date.now();const bar=el.querySelector('.cqa-progress-bar i'),pct=el.querySelector('.cqa-progress-pct'),st=el.querySelector('.cqa-progress-stage');timer=setInterval(()=>{const s=(Date.now()-t0)/1000;p=Math.min(94,100*(1-Math.exp(-s/14)));bar.style.width=p.toFixed(1)+'%';pct.textContent=Math.round(p)+'%';st.textContent=stages[Math.min(stages.length-1,Math.floor(p/17))];},200);}function stop(now){if(timer){clearInterval(timer);timer=null;}if(!el)return;const e=el;el=null;if(now){e.remove();return;}e.querySelector('.cqa-progress-bar i').style.width='100%';e.querySelector('.cqa-progress-pct').textContent='100%';e.querySelector('.cqa-progress-stage').textContent='Готово';setTimeout(()=>{e.classList.add('cqa-progress-out');setTimeout(()=>e.remove(),350);},450);}return {start,stop};})();
