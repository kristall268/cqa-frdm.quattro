window.CQA = window.CQA || {};
CQA.router = (()=>{
  function parse(){const raw=decodeURIComponent((location.hash||'#/').slice(1));if(/^\/c\/[^/]+$/.test(raw))return {page:'card',id:raw.slice(3)};if(raw==='/checklists')return {page:'checklists'};if(raw==='/appeals')return {page:'appeals'};if(raw==='/conversations')return {page:'conversations'};if(/^\/conversations\/(gross|review|failed|new|attention)$/.test(raw))return {page:'conversations',flag:raw.split('/')[2]};if(raw==='/results')return {page:'results'};return {page:'dashboard'};}
  function go(path){location.hash='#'+path;}
  return {parse,go};
})();
