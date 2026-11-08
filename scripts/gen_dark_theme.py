# Генератор тёмной темы: python scripts/gen_dark_theme.py (запускать из папки frontend)
import re, colorsys
css=open('css/cqa.css').read()
# remove comments
css_nc=re.sub(r'/\*.*?\*/','',css,flags=re.S)
SKIP=('sidebar','hero','nav-','help-','brand','theme-toggle','data-theme','cqa-total','score-card','cqa-eval-total','modal-backdrop','cqa-led','cqa-progress ','::selection')
PROPS=('color','background','background-color','border','border-color','border-top','border-bottom','border-left','border-right','border-top-color','border-left-color','outline','fill','stroke','caret-color')
def tx(r,g,b,a=None):
    h,l,s=colorsys.rgb_to_hls(r/255,g/255,b/255)
    if s<0.3 or l>0.97:
        if l>0.5: l2=0.075+(1-l)*0.95
        else: l2=min(0.93,1-l)
        s2=min(s,0.25)
    else:
        if l>0.85: l2,s2=0.17,s*0.55
        elif l<0.25: l2,s2=0.80,s*0.8
        else: l2,s2=l,s
    R,G,B=[round(x*255) for x in colorsys.hls_to_rgb(h,l2,s2)]
    return f'#{R:02x}{G:02x}{B:02x}'+(f'{a:02x}' if a is not None else '')
def conv(val):
    def hexrep(m):
        x=m.group(1)
        if len(x) in(3,4): x=''.join(ch*2 for ch in x)
        r,g,b=int(x[0:2],16),int(x[2:4],16),int(x[4:6],16); a=int(x[6:8],16) if len(x)==8 else None
        return tx(r,g,b,a)
    v=re.sub(r'#([0-9a-fA-F]{8}|[0-9a-fA-F]{6}|[0-9a-fA-F]{3,4})\b',hexrep,val)
    v=re.sub(r'\bwhite\b',lambda m:tx(255,255,255),v)
    def rgba(m):
        r,g,b=[int(float(x)) for x in m.group(1,2,3)]; a=m.group(4)
        h=tx(r,g,b)[1:]; R,G,B=int(h[0:2],16),int(h[2:4],16),int(h[4:6],16)
        return f'rgba({R},{G},{B},{a})' if a else f'rgb({R},{G},{B})'
    v=re.sub(r'rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*(?:,\s*([\d.]+)\s*)?\)',rgba,v)
    return v
out=[]
for m in re.finditer(r'([^{}@]+)\{([^{}]*)\}',css_nc):
    sel=m.group(1).strip(); body=m.group(2)
    if not sel or sel.startswith('from') or sel.startswith('to') or re.fullmatch(r'[\d%, ]+',sel): continue
    if any(k in sel for k in SKIP): continue
    decls=[]
    for d in body.split(';'):
        if ':' not in d: continue
        p,v=d.split(':',1); p=p.strip().lower(); v=v.strip()
        if (p in PROPS or p.startswith('--')) and ('#' in v or 'rgb' in v or 'white' in v):
            nv=conv(v)
            if nv!=v: decls.append(f'{p}:{nv}')
    if not decls: continue
    sels=[]
    for s in sel.split(','):
        s=s.strip()
        if s.startswith('html'): s=s.replace('html','html[data-theme="dark"]',1)
        elif s.startswith('body'): s='html[data-theme="dark"] '+s
        else: s='html[data-theme="dark"] '+s
        sels.append(s)
    out.append(','.join(sels)+'{'+';'.join(decls)+'}')
base='''html[data-theme="dark"],html[data-theme="dark"] body{background:#0b1411;color:#dfe9e3;color-scheme:dark}
html[data-theme="dark"] #cqa-root{background:#0b1411}
html[data-theme="dark"] input,html[data-theme="dark"] select,html[data-theme="dark"] textarea{color:#e3ece7}
html[data-theme="dark"] .cqa-progress-card{background:#13201b;color:#e3ece7}
html[data-theme="dark"] .cqa-progress-head{color:#e3ece7}
html,body,#cqa-root,.cqa-panel,.cqa-stat,.cqa-topbar,.cqa-main,.cqa-modal{transition:background-color .25s ease,color .25s ease,border-color .25s ease}
'''
out=[r for r in out if '{--cqa-bg' not in r]
base+='html[data-theme="dark"] #cqa-root{--cqa-bg:#0d1613;--cqa-ink:#e2ece7;--cqa-muted:#8fa39a;--cqa-soft:#16231d;--cqa-stroke:#24342c;--cqa-surface:#121d18}\n'
open('css/cqa-dark.css','w').write('/* Тёмная тема: сгенерирована из cqa.css (scripts в истории), переключатель в шапке */\n'+base+'\n'.join(out)+'\n')
print(len(out),'rules')
