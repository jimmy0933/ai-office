'use strict';
const $=s=>document.querySelector(s),token=$('meta[name="office-token"]').content;
const busy=new Set(['佇列中','執行中','討論中','整理中','停止中']);
let state=null,selectedTask='',selectedMeeting='',dir='',file='',page='office',configLoaded=false,lastState='',toastTimer;
const escape=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const time=s=>s?new Date(s).toLocaleTimeString('zh-TW',{hour12:false,hour:'2-digit',minute:'2-digit',second:'2-digit'}):'';
const bind=(sel,event,fn)=>$(sel).addEventListener(event,e=>{if(event==='submit')e.preventDefault();Promise.resolve().then(()=>fn(e)).catch(err=>toast(err.message,true));});
function toast(text,bad=false){clearTimeout(toastTimer);$('#toast').textContent=text;$('#toast').className=bad?'bad':'';toastTimer=setTimeout(()=>$('#toast').classList.add('hidden'),6000);}
async function api(path,body){const r=await fetch('/api/'+path,{method:body===undefined?'GET':'POST',headers:{'X-Office-Token':token,...(body===undefined?{}:{'Content-Type':'application/json'})},...(body===undefined?{}:{body:JSON.stringify(body)})});const data=await r.json();if(!r.ok)throw new Error(data.error||'請求失敗');return data;}
function badge(status){return `<span class="badge ${busy.has(status)?'busy':status==='待核准'?'wait':['失敗','已中斷','已停止','已拒絕'].includes(status)?'fail':''}">${escape(status)}</span>`;}
function jump(p){window.scrollTo(0,0);page=p;document.querySelectorAll('.page').forEach(el=>el.classList.toggle('hidden',el.id!=='page-'+p));document.querySelectorAll('nav button').forEach(el=>el.classList.toggle('active',el.dataset.page===p));$('#page-title').textContent=({office:'你的 AI 辦公室。',tasks:'把事情交給組長。',meetings:'好的想法，一起討論。',files:'從你的專案出發。',settings:'準備好，開始工作。',usage:'額度與用量。'})[p];if(p==='usage')refreshQuota();if(p==='files')loadFiles().catch(e=>toast(e.message,true));}
document.addEventListener('click',e=>{const b=e.target.closest('[data-page],[data-jump]');if(b)jump(b.dataset.page||b.dataset.jump);});
bind('#new-task','click',()=>{jump('tasks');$('#task-form [name="title"]').focus();});bind('#go-meeting','click',()=>jump('meetings'));
function agentStatus(p){const task=state.tasks.find(t=>t.owner===p&&busy.has(t.status));const m=state.meetings.find(m=>busy.has(m.status)&&m.speaker===p);if(m)return {status:m.status,label:'會議：'+m.topic};if(task)return {status:task.status,label:task.title};const c=state.connections[p];return {status:c?.installed?'待命':'尚未檢查',label:c?.authenticated?'最近呼叫成功':c?.installed?(p==='Company'?'已設定網址，請測試連線':'已找到 CLI，尚未確認登入'):'先到連線設定檢查'};}
function render(){if(!state)return;const tasks=state.tasks,meetings=state.meetings,active=tasks.filter(t=>busy.has(t.status)).length+meetings.filter(m=>busy.has(m.status)).length,waiting=tasks.filter(t=>t.status==='待核准').length,done=tasks.filter(t=>t.status==='完成').length;
  $('#nav-count').textContent=tasks.length;$('#room-live').textContent=['Codex','Antigravity','Gemini','Claude','Company'].map(agentStatus).filter(s=>busy.has(s.status)).length+' 位執行中';
  $('#agents').innerHTML=['Codex','Antigravity','Gemini','Claude','Company'].map(p=>{const s=agentStatus(p),c=state.connections[p],model=state.config[p].model||c?.model||'CLI 預設';return `<div class="agent"><div class="agent-top"><div class="avatar ${p!=='Codex'?'purple':''}">${p==='Codex'?'⌘':'✦'}</div><div><strong>${p}</strong><small>${p==='Codex'?'工程組長':p==='Antigravity'?'Google AI 組長':'討論與分析'}</small></div></div><div class="agent-status">${badge(s.status)}</div><div class="agent-model">${escape(s.label.slice(0,90))}<br>模型：${escape(model)}</div></div>`;}).join('');
  $('#stats').innerHTML=[['進行中的工作',active,'任務與會議'],['等待你核准',waiting,'修改提案'],['已完成任務',done,'實際完成'],['會議紀錄',meetings.length,'累計討論']].map(([label,n,unit])=>`<div class="stat"><div class="stat-label">${label}</div><strong>${n}</strong><small>${unit}</small></div>`).join('');
  $('#recent-tasks').innerHTML=tasks.slice(0,4).map(t=>`<div class="recent-row"><span class="who">${escape(t.owner)}</span><button class="text-button title" data-open-task="${t.id}">${escape(t.title)}</button>${badge(t.status)}</div>`).join('')||'<div class="empty">還沒有任務。先指派一件小事吧。</div>';
  $('#events').innerHTML=state.events.slice(-20).reverse().map(e=>`<div class="event-row"><span>${time(e.at)}</span><b>${escape(e.who)}</b><span>${escape(e.text)}</span></div>`).join('')||'<div class="empty">實際工作事件會出現在這裡。</div>';
  if(!selectedTask&&tasks[0])selectedTask=tasks[0].id;if(!selectedMeeting&&meetings[0])selectedMeeting=meetings[0].id;
  $('#task-list').innerHTML=tasks.map(t=>`<button class="select-card ${t.id===selectedTask?'selected':''}" data-task="${t.id}"><strong>${escape(t.title)}</strong>${badge(t.status)} <small>${escape(t.owner)}</small></button>`).join('');renderTask();
  $('#meeting-list').innerHTML=meetings.map(m=>`<button class="select-card ${m.id===selectedMeeting?'selected':''}" data-meeting="${m.id}"><strong>${escape(m.topic)}</strong>${badge(m.status)}</button>`).join('');renderMeeting();
  if(!configLoaded){const f=$('#config-form');f.elements.workspace.value=state.config.workspace;for(const p of ['Codex','Antigravity','Gemini','Claude']){f.elements[p.toLowerCase()+'Command'].value=state.config[p].command;f.elements[p.toLowerCase()+'Model'].value=state.config[p].model;}const co=state.config.Company;f.elements.companyBaseUrl.value=co.baseUrl;f.elements.companyModel.value=co.model;f.elements.companyQuotaUrl.value=co.quotaUrl||'';f.elements.companyTimeout.value=co.timeoutSeconds;f.elements.companyApiKey.placeholder=co.hasApiKey?'已保存；留白保留原 Key':'無需認證可留白';configLoaded=true;loadOptions();}
  renderUsage();drawRoom();
}
function renderTask(){const t=state.tasks.find(t=>t.id===selectedTask);if(!t)return;
  $('#task-detail').innerHTML=`<div class="detail-title"><div><span class="eyebrow">${escape(t.owner)} / ${escape(t.model||'等待回覆')}</span><h2>${escape(t.title)}</h2></div>${badge(t.status)}</div><p class="muted small">${escape(t.description)}<br>建立於 ${new Date(t.createdAt).toLocaleString('zh-TW')} · 附加 ${t.files.length} 個檔案</p><div class="actions">${busy.has(t.status)?`<button data-stop="${t.id}" class="secondary">停止任務</button>`:t.status!=='待核准'?`<button data-retry="${t.id}" class="secondary">重新執行</button>`:''}${t.status==='待核准'?`<button data-approve="${t.id}" class="primary">核准並套用修改</button><button data-reject="${t.id}">拒絕提案</button>`:''}</div>${t.error?`<div class="error">${escape(t.error)}</div>`:''}${t.lastEvent?`<p class="small muted">最後事件：${escape(t.lastEvent)}</p>`:''}<h3>${t.edits?.length?'修改提案':'組長回覆'}</h3><pre>${escape(t.result||'等待 CLI 回覆。')}</pre>${(t.edits||[]).map(e=>`<div class="change"><h3>${escape(e.path)}</h3><div class="diff-grid"><div><small>修改前</small><pre>${escape(e.before)}</pre></div><div><small>修改後</small><pre>${escape(e.content)}</pre></div></div></div>`).join('')}${t.appliedAt?'<p class="small muted">已套用。原始內容已保存於電腦端備份目錄。</p>':''}`;
}
function renderMeeting(){const m=state.meetings.find(m=>m.id===selectedMeeting);if(!m)return;
  $('#meeting-heading').innerHTML=`<div class="detail-title"><h2>${escape(m.topic)}</h2>${badge(m.status)}</div><p class="muted small">${escape((m.participants||['Gemini','Codex']).join(' ＋ '))} · ${m.round||0} / ${m.rounds} 輪${busy.has(m.status)&&m.speaker?' · '+escape(m.speaker)+' 準備／發言中':''}</p>${busy.has(m.status)?`<button data-stop="${m.id}" class="secondary">停止會議</button>`:''}${m.error?`<div class="error">${escape(m.error)}</div>`:''}`;
  $('#messages').innerHTML=m.messages.map(msg=>`<div class="message ${msg.who!=='Codex'&&msg.who!=='你'?'gemini':msg.who==='你'?'you':''}"><div class="message-head"><strong>${escape(msg.who)} ${msg.round?'· 第 '+msg.round+' 輪':''}</strong><span class="muted small">${time(msg.at)}</span></div><p>${escape(msg.text)}</p>${msg.model?`<small class="muted">${escape(msg.model)}</small>`:''}</div>`).join('');
  $('#summary').innerHTML=m.summary?`<div class="summary"><h3>會議結論</h3><p>${escape(m.summary)}</p></div>`:'';$('#interject-form').classList.toggle('hidden',!busy.has(m.status)||m.status==='整理中');
}
document.addEventListener('click',e=>{const b=e.target.closest('button');if(!b||!state)return;(async()=>{
  if(b.dataset.task||b.dataset.openTask){selectedTask=b.dataset.task||b.dataset.openTask;if(b.dataset.openTask)jump('tasks');render();}
  if(b.dataset.meeting){selectedMeeting=b.dataset.meeting;render();}
  if(b.dataset.stop){await api('stop',{id:b.dataset.stop});toast('已要求停止');await refresh();}
  if(b.dataset.retry){const r=await api('retry',{id:b.dataset.retry});selectedTask=r.id;toast('已重新加入佇列');await refresh();}
  if(b.dataset.delete){if(!confirm('確定要刪除此任務？'))return;await api('task-delete',{id:b.dataset.delete});selectedTask='';toast('任務已刪除');await refresh();}
  if(b.dataset.approve||b.dataset.reject){const approve=!!b.dataset.approve;if(approve&&!confirm('套用此提案至專案檔案？原始內容會先備份。'))return;await api('approval',{id:b.dataset.approve||b.dataset.reject,decision:approve?'approve':'reject'});toast(approve?'修改已套用':'提案已拒絕');await refresh();}
})().catch(e=>toast(e.message,true));});
bind('#task-form','submit',async e=>{e.preventDefault();const form=e.target,b=form.querySelector('[type="submit"]');b.disabled=true;try{const data=Object.fromEntries(new FormData(form));const r=await api('task',data);selectedTask=r.id;toast('任務已加入佇列');await refresh();}finally{b.disabled=false;}});
bind('#meeting-form','submit',async e=>{e.preventDefault();const b=e.target.querySelector('button');b.disabled=true;try{const data=Object.fromEntries(new FormData(e.target));data.rounds=Number(data.rounds);data.participants=data.participants.split(',');const r=await api('meeting',data);selectedMeeting=r.id;toast('會議已開始');await refresh();}finally{b.disabled=false;}});
bind('#interject-form','submit',async e=>{e.preventDefault();const input=e.target.elements.text;await api('interject',{id:selectedMeeting,text:input.value});input.value='';toast('下一位發言者會收到你的補充');await refresh();});
function configBody(){const f=$('#config-form').elements;return {workspace:f.workspace.value,Codex:{command:f.codexCommand.value,model:f.codexModel.value},Gemini:{command:f.geminiCommand.value,model:f.geminiModel.value},Antigravity:{command:f.antigravityCommand.value,model:f.antigravityModel.value},Claude:{command:f.claudeCommand.value,model:f.claudeModel.value},Company:{baseUrl:f.companyBaseUrl.value,quotaUrl:f.companyQuotaUrl.value,model:f.companyModel.value,apiKey:f.companyApiKey.value,clearApiKey:f.companyClearKey.checked,timeoutSeconds:Number(f.companyTimeout.value)}};}
bind('#config-form','submit',async e=>{e.preventDefault();await api('config',configBody());$('#config-form').elements.companyApiKey.value='';$('#config-form').elements.companyClearKey.checked=false;configLoaded=false;toast('設定已保存');await refresh();});

async function loadOptions(){
  try{
    const opts = await api('options');
    const f = $('#config-form');
    for(const p of ['Codex','Antigravity','Gemini','Claude']){
      const prefix = p.toLowerCase();
      const execSelect = $('#'+prefix+'CommandSelect');
      const execInput = f.elements[prefix+'Command'];
      if(execSelect){
        const list = opts[p]?.executables || [];
        const current = execInput.value.trim();
        let html = list.map(x=>`<option value="${escape(x)}">${escape(x)}</option>`).join('');
        html += `<option value="__custom__">自訂輸入...</option>`;
        execSelect.innerHTML = html;
        if(list.includes(current)){
          execSelect.value = current;
          execInput.style.display = 'none';
        } else {
          execSelect.value = '__custom__';
          execInput.style.display = 'block';
        }
      }

      const modelSelect = $('#'+prefix+'ModelSelect');
      const modelInput = f.elements[prefix+'Model'];
      if(modelSelect){
        const list = opts[p]?.models || [];
        const current = modelInput.value.trim();
        let html = `<option value="">預設模型 (使用 CLI 預設)</option>`;
        html += list.map(x=>`<option value="${escape(x)}">${escape(x)}</option>`).join('');
        html += `<option value="__custom__">自訂輸入...</option>`;
        modelSelect.innerHTML = html;
        if(current === '' || list.includes(current)){
          modelSelect.value = current;
          modelInput.style.display = 'none';
        } else {
          modelSelect.value = '__custom__';
          modelInput.style.display = 'block';
        }
      }
    }

    const companyModelSelect = $('#companyModelSelect');
    const companyModelInput = f.elements.companyModel;
    if(companyModelSelect){
      const list = opts.Company?.models || [];
      const current = companyModelInput.value.trim();
      let html = `<option value="">請選擇或手動輸入...</option>`;
      html += list.map(x=>`<option value="${escape(x)}">${escape(x)}</option>`).join('');
      html += `<option value="__custom__">自訂輸入...</option>`;
      companyModelSelect.innerHTML = html;
      if(current === '' || list.includes(current)){
        companyModelSelect.value = current;
        companyModelInput.style.display = 'none';
      } else {
        companyModelSelect.value = '__custom__';
        companyModelInput.style.display = 'block';
      }
    }
  }catch(e){}
}

document.addEventListener('change', e=>{
  const select = e.target.closest('select[data-for]');
  if(!select) return;
  const inputName = select.dataset.for;
  const input = $('#config-form').elements[inputName];
  if(!input) return;
  if(select.value === '__custom__'){
    input.style.display = 'block';
    input.focus();
  } else {
    input.style.display = 'none';
    input.value = select.value;
  }
});
bind('#claude-login','click',async()=>{await api('config',configBody());configLoaded=false;const r=await api('claude-login',{});toast(r.message);await refresh();});
bind('#company-test','click',async e=>{e.target.disabled=true;try{await api('config',configBody());configLoaded=false;const r=await api('company-test',{});$('#check-result').textContent='公司模型連線成功 · '+r.model+'\n'+r.response;await refresh();}finally{$('#company-test').disabled=false;}});
bind('#check-cli','click',async e=>{const b=e.target;b.disabled=true;$('#check-result').textContent='正在檢查 CLI 版本…';try{await api('config',configBody());configLoaded=false;const r=await api('check',{});$('#check-result').textContent=Object.entries(r).map(([p,c])=>p+'：'+(c.installed?'已找到 · '+c.version+(c.warning?'\n  '+c.warning:''):'未找到 · '+c.error)).join('\n')+'\n版本檢查不代表已登入。第一個任務成功後，會顯示最近呼叫成功。';await refresh();}finally{b.disabled=false;}});
async function loadFiles(){if(!state)return;$('#workspace-label').textContent=(state.config.workspace||'尚未設定專案資料夾')+(dir?' / '+dir:'');if(!state.config.workspace){$('#file-list').innerHTML='<div class="empty">先到連線設定指定專案。</div>';return;}try{const files=await api('files?dir='+encodeURIComponent(dir));$('#file-list').innerHTML=files.map(f=>`<button class="file-row" data-file="${escape(f.path)}" data-directory="${f.directory}">${f.directory?'▣':'▤'} ${escape(f.name)}</button>`).join('')||'<div class="empty">此資料夾沒有可瀏覽的檔案。</div>';}catch(e){$('#file-list').innerHTML='<div class="empty">'+escape(e.message)+'</div>';}}
bind('#file-list','click',async e=>{const b=e.target.closest('[data-file]');if(!b)return;if(b.dataset.directory==='true'){dir=b.dataset.file;await loadFiles();return;}const f=await api('file?path='+encodeURIComponent(b.dataset.file));file=f.path;$('#file-name').textContent=f.path;$('#file-content').textContent=f.content;$('#attach-file').disabled=false;});
bind('#files-up','click',async()=>{dir=dir.split('/').slice(0,-1).join('/');await loadFiles();});
bind('#attach-file','click',()=>{const input=$('#task-form').elements.paths;const lines=new Set(input.value.split('\n').map(s=>s.trim()).filter(Boolean));lines.add(file);input.value=[...lines].join('\n');toast('已加入任務附件：'+file);jump('tasks');});
bind('#export','click',async()=>{const data=await api('export');const url=URL.createObjectURL(new Blob([JSON.stringify(data,null,2)],{type:'application/json'}));const a=document.createElement('a');a.href=url;a.download='AI-Office-records.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);});
async function refresh(){try{const next=await api('state');$('#link-state').textContent='● 本機已連線';const json=JSON.stringify(next);if(json!==lastState){state=next;lastState=json;render();}}catch(e){$('#link-state').textContent='○ 電腦端已離線';throw e;}}
function drawRoom(){const c=$('#room'),ctx=c.getContext('2d');ctx.imageSmoothingEnabled=false;const r=(x,y,w,h,color)=>{ctx.fillStyle=color;ctx.fillRect(x,y,w,h);};const label=(text,x,y,color='#a4b9a5',size=12)=>{ctx.fillStyle=color;ctx.font=size+'px ui-monospace, monospace';ctx.fillText(text,x,y);};
  r(0,0,960,430,'#1b2726');r(35,58,890,338,'#3a4740');for(let y=60;y<396;y+=32)for(let x=36;x<924;x+=32)r(x,y,30,30,(x+y)%64===0?'#3a4840':'#35443d');
  r(35,25,890,36,'#647160');r(35,55,890,9,'#26362c');r(40,22,880,6,'#8a9880');r(605,61,8,335,'#1e3029');
  for(const x of [82,276,723]){r(x,12,125,39,'#83988b');r(x+6,17,113,26,'#a1c9bd');r(x+59,16,5,30,'#60776c');r(x+6,28,113,4,'#60776c');}
  function plant(x,y){r(x+5,y+28,21,20,'#a08567');r(x,y+40,31,8,'#675f48');r(x+14,y+9,5,22,'#759762');r(x+2,y+9,19,10,'#7faf72');r(x+15,y,16,13,'#a1c28c');r(x,y+21,15,10,'#557d57');}
  plant(60,84);plant(548,340);plant(861,80);plant(864,338);
  r(445,78,98,40,'#243731');r(449,82,90,31,'#334b3c');label('AI OFFICE',457,102,'#bdd29b',12);
  label('ENGINEERING',77,143,'#bcc6a4',11);label('MEETING ROOM',675,142,'#bcc6a4',11);
  function desk(x,y,p,color){r(x+4,y+36,156,12,'#1d2d24');r(x,y,164,43,'#907e59');r(x,y,164,5,'#b29b6d');r(x+9,y+43,8,29,'#5b543d');r(x+144,y+43,8,29,'#5b543d');const working=busy.has(agentStatus(p).status);r(x+42,y-28,65,37,'#1b292a');r(x+47,y-23,55,26,working?color:'#52705e');r(x+64,y+8,28,5,'#344739');r(x+119,y+7,18,14,'#cbb58b');r(x+135,y+9,4,8,'#947d54');r(x+13,y+8,20,15,'#ded6b6');
    for(let i=0;i<3;i++)r(x+53,y-17+i*6,working?30-i*5:23,2,'#243c31');person(x+66,y+53,color);label(p.toUpperCase(),x+19,y+112,color,11);label(working?'WORKING':'STANDBY',x+47,y+129,'#a9b49c',9);
  }
  function person(x,y,color){r(x-4,y+9,40,42,'#233b31');r(x+2,y,25,8,'#3c342d');r(x+1,y+6,27,23,'#d3ad82');r(x+1,y+7,27,6,'#4d3e31');r(x+7,y+17,3,3,'#32342a');r(x+20,y+17,3,3,'#32342a');r(x-1,y+31,33,22,color);r(x+3,y+53,10,13,'#232f30');r(x+18,y+53,10,13,'#232f30');}
  desk(55,130,'Codex','#baf39b');desk(235,130,'Antigravity','#a3d4ef');desk(415,130,'Gemini','#c6b9ed');desk(100,280,'Claude','#edb997');desk(340,280,'Company','#9be4db');
  r(695,184,151,96,'#20332a');r(685,174,151,96,'#927d59');r(689,178,143,86,'#a08c67');r(719,190,31,20,'#ded7b9');r(767,223,28,15,'#415f4a');r(693,281,131,8,'#20332a');
  for(const [x,y] of [[650,191],[848,191],[715,292],[782,292]]){r(x,y,30,26,'#263b31');r(x+4,y+4,22,18,'#718562');}
  const meeting=state?.meetings.find(m=>busy.has(m.status));label(meeting?'IN DISCUSSION':'READY TO TALK',685,334,meeting?'#baf39b':'#a9b49c',11);if(meeting){r(653,194,24,16,'#c6b9ed');r(851,194,24,16,'#baf39b');}
  
}
setInterval(()=>{$('#clock').textContent=new Date().toLocaleTimeString('zh-TW',{hour12:false});},1000);
async function poll(){try{await refresh();}catch(e){}setTimeout(poll,1500);}poll();

let quotaLoading=false;
async function refreshQuota(){if(quotaLoading)return;quotaLoading=true;$('#quota-refresh').disabled=true;try{await api('quota-refresh',{});await refresh();}catch(e){toast(e.message,true);}finally{quotaLoading=false;$('#quota-refresh').disabled=false;}}
bind('#quota-refresh','click',refreshQuota);
setInterval(()=>{if(page==='usage'&&!document.hidden)refreshQuota();},60000);
function renderUsage(){const fmt=n=>Number(n||0).toLocaleString('zh-TW');$('#usage-cards').innerHTML=['Codex','Gemini','Antigravity','Claude','Company'].map(p=>{const u=state.usage?.[p]||{},q=state.quotas?.[p],updated=q?.updatedAt,stale=!updated||Date.now()-new Date(updated).getTime()>120000;const windows=q?.windows||[];let windowsHtml='';if(windows.length>0){windowsHtml=windows.map(w=>{const value=Number(w.usedPercent),valid=Number.isFinite(value)&&value>=0&&value<=100;return valid?`<div class="quota-window"><strong>${escape(w.name)}</strong><span>剩餘 ${(100-value).toFixed(1)}%</span><progress max="100" value="${100-value}"></progress><small>已用 ${value.toFixed(1)}% · 重置 ${w.resetsAt?escape(new Date(Number(w.resetsAt)*1000).toLocaleString('zh-TW')):'未提供'}</small></div>`:''}).join('');}else if(['Gemini','Antigravity'].includes(p)){windowsHtml=`<div class="quota-window"><strong>官方訂閱額度</strong><span>請透過官方 CLI 查詢</span><small>${p==='Gemini'?'開啟官方 CLI 後輸入 /stats model':'開啟官方 CLI 後輸入 /usage'}</small></div>`;}return `<article class="panel"><h2>${p}</h2><p class="muted small">${escape(q?.source||'尚未接入自動額度來源')}<br>${escape(q?.status||'尚未查詢')}</p>${windowsHtml}<p class="small muted">${updated?'更新：'+escape(new Date(updated).toLocaleString('zh-TW')):'尚無更新時間'}${stale&&updated?' · 舊快照，請確認官方額度':''}</p><hr><p>AI Office 累計回覆：<b>${fmt(u.calls)}</b><br>有回報用量：${fmt(u.reportedCalls)} / ${fmt(u.calls)}${u.diagnosticCalls?'<br>其中連線測試：'+fmt(u.diagnosticCalls):''}</p><p>${u.reportedCalls?'已回報 Token：<b>'+fmt(u.total)+'</b><br>輸入 '+fmt(u.input)+' · 輸出 '+fmt(u.output)+'<br>快取讀取 '+fmt(u.cached):'Token：尚未回報'}</p><small class="muted">${u.lastAt?'最近回覆 '+escape(new Date(u.lastAt).toLocaleString('zh-TW')):'尚無回覆'}</small>${['Claude','Gemini','Antigravity'].includes(p)?`<button class="secondary wide" data-quota-terminal="${p}">開啟官方額度查詢</button>`:''}</article>`;}).join('');}
document.addEventListener('click',e=>{const button=e.target.closest('[data-quota-terminal]');if(button)api('quota-terminal',{provider:button.dataset.quotaTerminal}).then(r=>toast(r.message)).catch(e=>toast(e.message,true));});

let fontScale = Number(localStorage.getItem('office-font-scale')) || 1;
function setFontScale(s) {
  fontScale = Math.max(0.8, Math.min(1.4, Math.round(s * 10) / 10));
  document.documentElement.style.setProperty('--font-scale', fontScale);
  localStorage.setItem('office-font-scale', fontScale);
}
setFontScale(fontScale);
bind('#fs-dec', 'click', () => setFontScale(fontScale - 0.1));
bind('#fs-reset', 'click', () => setFontScale(1));
bind('#fs-inc', 'click', () => setFontScale(fontScale + 0.1));

document.addEventListener('paste', e => {
  const target = e.target;
  if (!target || target.tagName !== 'TEXTAREA') return;
  const items = e.clipboardData?.items;
  if (!items) return;
  for (const item of items) {
    if (item.type && item.type.startsWith('image/')) {
      e.preventDefault();
      const file = item.getAsFile();
      if (!file) continue;
      const reader = new FileReader();
      reader.onload = ev => {
        const dataUrl = ev.target.result;
        const textToInsert = `\n![貼上截圖](${dataUrl})\n`;
        const start = target.selectionStart || target.value.length;
        const end = target.selectionEnd || target.value.length;
        target.value = target.value.substring(0, start) + textToInsert + target.value.substring(end);
        target.selectionStart = target.selectionEnd = start + textToInsert.length;
        toast('已貼上剪貼簿截圖');
      };
      reader.readAsDataURL(file);
      break;
    }
  }
});
