#!/usr/bin/env python3
"""Linux/macOS integration checks using fake CLI processes; never calls a model."""
import json, os, pathlib, re, subprocess, tempfile, time, urllib.request, urllib.error

HERE = pathlib.Path(__file__).resolve().parent
ROOT = pathlib.Path(tempfile.mkdtemp(prefix='ai-office-integration-'))
PROJECT = ROOT / 'project'; PROJECT.mkdir()
(PROJECT / 'Demo.java').write_text('class Demo { int n = 1; }\n')
AUDIT = ROOT / 'audit.jsonl'
checks = 0

def check(condition, label):
    global checks
    checks += 1
    if not condition: raise AssertionError(label)

MOCK = r'''#!__PYTHON__
import json, os, pathlib, subprocess, sys, time
audit=pathlib.Path(__AUDIT__)
api_mode=pathlib.Path(__API_MODE__)
provider=pathlib.Path(sys.argv[0]).name
if '--help' in sys.argv:
    print('--input-format --output-format');sys.exit(0)
if '--version' in sys.argv:
    print(provider+' mock 1.0');sys.exit(0)
if 'login' in sys.argv and 'status' in sys.argv:
    print('Logged in using API key' if api_mode.exists() else 'Logged in using ChatGPT',file=sys.stderr);sys.exit(0)
for key in ['OPENAI_API_KEY','CODEX_API_KEY','GEMINI_API_KEY','GOOGLE_API_KEY','GOOGLE_GENAI_USE_VERTEXAI']:
    assert key not in os.environ, 'Unexpected billing credential: '+key
prompt=sys.stdin.read()
if provider=='fake-agy':
    assert '--disable-slash-commands' in sys.argv
    assert sys.argv[sys.argv.index('--input-format')+1]=='stream-json'
    agent=sys.argv[sys.argv.index('--agent')+1]
    definition=pathlib.Path('.agents/agents')/agent/'agent.md'
    assert 'subagent: false' in definition.read_text()
    data=json.loads(prompt);assert data['event']=='user';prompt=data['message']['content']
with audit.open('a') as f:f.write(json.dumps({'provider':provider,'args':sys.argv[1:],'prompt':prompt,'at':time.time()})+'\n')
if provider=='fake-agy':
    if 'EXIT_MARKER' in prompt:print('Google login required',file=sys.stderr);sys.exit(2)
    print(json.dumps({'event':'init','init':{'tools':['run_command'] if 'TOOLS_MARKER' in prompt else [],'model':'mock-antigravity-pro'}}),flush=True)
    if 'MISSING_MARKER' in prompt:sys.exit(0)
    if 'MALFORMED_MARKER' in prompt:print('{broken');sys.exit(0)
    print(json.dumps({'event':'step_update','step_update':{'step_type':'agent_response','state':'DONE','text_delta':'partial'}}),flush=True)
    time.sleep(.2)
    if 'FAIL_MARKER' in prompt:result={'status':'ERROR','error':{'message':'Subscription quota exhausted'}}
    elif 'WAIT_MARKER' in prompt:result={'status':'WAITING','response':'not a completed answer'}
    else:
        response=json.dumps({'summary':'AGY 更新 n','edits':[{'path':'Demo.java','content':'class Demo { int n = 3; }\n'}]}) if '只輸出 JSON' in prompt else 'ANTIGRAVITY_REPLY：已分析 Google AI 架構。'
        result={'status':'SUCCESS','response':response,'usage':{'input_tokens':6,'output_tokens':4}}
    print(json.dumps({'event':'result','result':result}),flush=True);sys.exit(0)
if provider=='fake-gemini':
    settings=json.loads(pathlib.Path(os.environ['GEMINI_CLI_SYSTEM_SETTINGS_PATH']).read_text())
    assert settings['security']['auth']['selectedType']=='oauth-personal'
    assert settings['security']['auth']['enforcedType']=='oauth-personal'
    time.sleep(.2)
    print(json.dumps({'response':'GEMINI_REPLY：建議先確認需求。','stats':{'models':{'mock-pro':{'tokens':('wrong' if 'BAD_STATS' in prompt else {'total':5})}}}}));sys.exit(0)
assert 'forced_login_method="chatgpt"' in sys.argv
if 'SLOW_MARKER' in prompt:
    child=subprocess.Popen([sys.executable,'-c','import time;time.sleep(60)'])
    pathlib.Path(__CHILD_PID__).write_text(str(child.pid))
    print(json.dumps({'type':'item.started','item':{'type':'reasoning'}}),flush=True)
    time.sleep(60)
if 'FAIL_MARKER' in prompt:
    print('Quota exceeded; use account later',file=sys.stderr);sys.exit(2)
if '只輸出 JSON' in prompt:
    answer=json.dumps({'summary':'更新 n','edits':[{'path':'Demo.java','content':'class Demo { int n = 2; }\n'}]})
else:answer='CODEX_REPLY：已根據附加內容分析。'
print(json.dumps({'type':'item.completed','item':{'type':'agent_message','text':answer}}),flush=True)
print(json.dumps({'type':'turn.completed','usage':{'input_tokens':4,'output_tokens':3}}),flush=True)
'''
import sys
mock=MOCK.replace('__PYTHON__',sys.executable).replace('__AUDIT__',repr(str(AUDIT))).replace('__API_MODE__',repr(str(ROOT/'api-mode'))).replace('__CHILD_PID__',repr(str(ROOT/'child-pid')))
for p in ('fake-codex','fake-gemini','fake-agy'):
    path=ROOT/p;path.write_text(mock);path.chmod(0o755)

process=None;origin='';token=''
opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
def start():
    global process,origin,token
    env=os.environ.copy();env.update({k:'TEST_ONLY_NOT_A_REAL_KEY' for k in ['OPENAI_API_KEY','CODEX_API_KEY','GEMINI_API_KEY','GOOGLE_API_KEY','GOOGLE_GENAI_USE_VERTEXAI']})
    process=subprocess.Popen(['java','-Duser.home='+str(ROOT/'home'),'-jar',str(HERE/'AI-Office-Java.jar'),'--no-browser','--port','0','--data',str(ROOT/'data')],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,env=env)
    for _ in range(4):
        line=process.stdout.readline()
        match=re.search(r'http://127\.0\.0\.1:\d+',line)
        if match:origin=match[0];break
    assert origin, 'No server URL'
    page=opener.open(origin+'/').read().decode()
    token=re.search(r'name="office-token" content="([^"]+)"',page)[1]
def stop_server():
    global process
    if process is not None:
        process.terminate();process.wait(timeout=8);process=None
def request(path,body=None,headers=None):
    h={'X-Office-Token':token}
    if body is not None:h.update({'Origin':origin,'Content-Type':'application/json'})
    if headers:h.update(headers)
    req=urllib.request.Request(origin+'/api/'+path,data=None if body is None else json.dumps(body).encode(),headers=h)
    try:
        with opener.open(req,timeout=30) as r:return r.status,json.loads(r.read())
    except urllib.error.HTTPError as e:return e.code,json.loads(e.read())
def post(path,body):
    status,data=request(path,body)
    assert status==200,(path,status,data)
    return data
def state():return request('state')[1]
def wait(kind,id,statuses,timeout=10):
    end=time.monotonic()+timeout
    while time.monotonic()<end:
        item=next(t for t in state()[kind] if t['id']==id)
        if item['status'] in statuses:return item
        time.sleep(.05)
    raise AssertionError((kind,id,'timeout',item))

try:
    start()
    check(request('state',headers={'X-Office-Token':'wrong'})[0]==403,'token required')
    check(request('state',headers={'Host':'evil.example'})[0]==403,'host rebinding blocked')
    check(request('state',headers={'Origin':'https://evil.example'})[0]==403,'foreign origin blocked')
    check(request('config',{},headers={'Origin':'https://evil.example'})[0]==403,'foreign POST blocked')
    config={'workspace':str(PROJECT),'Codex':{'command':str(ROOT/'fake-codex'),'model':''},'Gemini':{'command':str(ROOT/'fake-gemini'),'model':''},'Antigravity':{'command':str(ROOT/'fake-agy'),'model':'mock-pro'}}
    config=post('config',config);checked=post('check',{})
    check(all(checked[p]['installed'] for p in ['Codex','Gemini','Antigravity']),'CLI detection')
    check(checked['Codex']['authenticated'] is None,'version check does not claim login')
    check(request('file?path=../outside')[0]==400,'API path traversal rejected')
    task=post('task',{'owner':'Codex','title':'分析','description':'查詢','paths':'Demo.java'})['id']
    result=wait('tasks',task,{'完成','失敗'})
    check(result['status']=='完成' and 'CODEX_REPLY' in result['result'],'analysis subprocess result')
    check(state()['connections']['Codex']['authenticated'] is True,'successful subscription call reported')
    edit=post('task',{'owner':'Codex','title':'修改','mode':'edit','paths':'Demo.java'})['id']
    proposal=wait('tasks',edit,{'待核准','失敗'})
    check(proposal['status']=='待核准','proposal awaits approval')
    check('n = 1' in (PROJECT/'Demo.java').read_text(),'no write before approval')
    (PROJECT/'Demo.java').write_text('external edit\n')
    check(request('approval',{'id':edit,'decision':'approve'})[0]==400,'stale approval blocked')
    check((PROJECT/'Demo.java').read_text()=='external edit\n','stale approval preserves edit')
    (PROJECT/'Demo.java').write_text('class Demo { int n = 1; }\n')
    post('approval',{'id':edit,'decision':'approve'})
    check('n = 2' in (PROJECT/'Demo.java').read_text(),'approved write applied')
    check(any((ROOT/'data/backups').glob('*/originals.json')),'backup created')
    rejected=post('task',{'owner':'Codex','title':'拒絕修改','mode':'edit','paths':'Demo.java'})['id']
    wait('tasks',rejected,{'待核准'});post('approval',{'id':rejected,'decision':'reject'})
    check(next(t for t in state()['tasks'] if t['id']==rejected)['status']=='已拒絕','reject proposal')
    gemini=post('task',{'owner':'Gemini','title':'Gemini 分析','paths':'Demo.java'})['id']
    g=wait('tasks',gemini,{'完成','失敗'})
    check(g['status']=='完成' and g['model']=='mock-pro','Gemini OAuth settings and result')
    malformed=post('task',{'owner':'Gemini','title':'BAD_STATS'})['id']
    check(wait('tasks',malformed,{'完成','失敗'})['status']=='完成','malformed stats task survives')
    check(state()['usage']['Gemini']['calls']>state()['usage']['Gemini']['reportedCalls'],'malformed stats state remains available and unknown')
    meeting=post('meeting',{'topic':'討論架構','rounds':1,'paths':'Demo.java'})['id']
    post('interject',{'id':meeting,'text':'INTERJECT_MARKER：請考慮 Redis'})
    m=wait('meetings',meeting,{'已結束','失敗'})
    check(m['status']=='已結束' and m['summary'].startswith('CODEX_REPLY'),'meeting completes with summary')
    check([v['who'] for v in m['messages'] if v['who']!='你']==['Gemini','Codex'],'turn-taking order')
    audit=[json.loads(line) for line in AUDIT.read_text().splitlines()]
    check(any('共享會議紀錄' in x['prompt'] and 'GEMINI_REPLY' in x['prompt'] and 'INTERJECT_MARKER' in x['prompt'] for x in audit if x['provider']=='fake-codex'),'prior speaker and interjection included')
    agy=post('task',{'owner':'Antigravity','title':'Google AI 分析','description':'引用 "文字" '+('長內容\n'*3000),'paths':'Demo.java'})['id']
    a=wait('tasks',agy,{'完成','失敗'})
    check(a['status']=='完成' and 'ANTIGRAVITY_REPLY' in a['result'] and a['model']=='mock-antigravity-pro','Antigravity NDJSON stdin, terminal result and actual model')
    check(a['usage']['input_tokens']==6,'Antigravity actual usage retained')
    check(state()['connections']['Antigravity']['authenticated'] is True,'Antigravity successful subscription call')
    check(any(e['who']=='Antigravity' and 'DONE' in e['text'] for e in state()['events']),'Antigravity real step event')
    agy_edit=post('task',{'owner':'Antigravity','title':'AGY 修改','mode':'edit','paths':'Demo.java'})['id']
    wait('tasks',agy_edit,{'待核准'});check('n = 2' in (PROJECT/'Demo.java').read_text(),'Antigravity proposal does not write project')
    post('approval',{'id':agy_edit,'decision':'approve'});check('n = 3' in (PROJECT/'Demo.java').read_text(),'Antigravity approved edit applied')
    meet=post('meeting',{'topic':'三位討論','rounds':1,'participants':['Antigravity','Gemini','Codex']})['id']
    m3=wait('meetings',meet,{'已結束','失敗'})
    check(m3['status']=='已結束' and [x['who'] for x in m3['messages'] if x['who']!='你']==['Antigravity','Gemini','Codex'],'three-provider meeting')
    audit=[json.loads(line) for line in AUDIT.read_text().splitlines()]
    check(any('ANTIGRAVITY_REPLY' in x['prompt'] and 'GEMINI_REPLY' in x['prompt'] for x in audit if x['provider']=='fake-codex'),'Antigravity shared transcript reaches Codex')
    check(request('meeting',{'topic':'錯誤參與者','participants':['Antigravity','Antigravity']})[0]==400,'duplicate meeting participants rejected')
    check(request('meeting',{'topic':'錯誤參與者','participants':['other','Codex']})[0]==400,'unknown meeting provider rejected')
    for marker,expected in [('FAIL_MARKER','Subscription quota'),('WAIT_MARKER','WAITING'),('MISSING_MARKER','最終結果'),('MALFORMED_MARKER','輸出無效'),('TOOLS_MARKER','工具'),('EXIT_MARKER','Google login required')]:
        t=post('task',{'owner':'Antigravity','title':marker})['id'];r=wait('tasks',t,{'失敗'})
        check(expected in r['error'],'Antigravity '+marker+' must fail, not report success: '+r['error'])
    prefs=ROOT/'home/.gemini/antigravity-cli/settings.json';prefs.parent.mkdir(parents=True)
    for setting,expected in [({'modelProvider':'gemini'},'API provider'),({'useG1Credits':True},'AI 點數'),({'allowNonWorkspaceAccess':True},'Non-Workspace Access')]:
        prefs.write_text(json.dumps(setting));before=len(AUDIT.read_text().splitlines())
        t=post('task',{'owner':'Antigravity','title':'訂閱設定檢查'})['id'];r=wait('tasks',t,{'失敗'})
        check(expected in r['error'] and len(AUDIT.read_text().splitlines())==before,'unsafe preferences rejected before model call')
    prefs.write_text('{"useG1Credits":false,"allowNonWorkspaceAccess":false}')
    t=post('task',{'owner':'Antigravity','title':'恢復 Google 訂閱'})['id'];wait('tasks',t,{'完成'});check(True,'Antigravity usable after preferences fixed')
    failed=post('task',{'owner':'Codex','title':'FAIL_MARKER'})['id'];f=wait('tasks',failed,{'失敗'})
    check('Quota exceeded' in f['error'],'quota failure shown, no API fallback')
    (ROOT/'api-mode').touch();api_task=post('task',{'owner':'Codex','title':'API 帳號'})['id'];a=wait('tasks',api_task,{'失敗'})
    check('ChatGPT 訂閱' in a['error'],'API login refused');(ROOT/'api-mode').unlink()
    slow=post('task',{'owner':'Codex','title':'SLOW_MARKER'})['id'];wait('tasks',slow,{'執行中'})
    check(request('config',config)[0]==400,'config locked while busy')
    end=time.monotonic()+5
    while not (ROOT/'child-pid').exists() and time.monotonic()<end:time.sleep(.02)
    post('stop',{'id':slow});wait('tasks',slow,{'已停止'})
    check(True,'cancellation completes')
    if (ROOT/'child-pid').exists():
        pid=int((ROOT/'child-pid').read_text());time.sleep(.2)
        def gone():
            try:os.kill(pid,0);return False
            except ProcessLookupError:return True
        deadline=time.monotonic()+3
        while not gone() and time.monotonic()<deadline:time.sleep(.05)
        check(gone(),'descendant cancelled')
    recovered=post('task',{'owner':'Codex','title':'取消後的新任務'})['id'];wait('tasks',recovered,{'完成'})
    check(True,'lane remains usable after cancellation')
    saved_count=len(state()['tasks']);stop_server();start()
    check(len(state()['tasks'])==saved_count,'tasks persist after restart')
    check(state()['config']==config,'configuration persists')
    check(request('export')[0]==200,'export')
    before=(ROOT/'data/office.json').read_text()
    duplicate=subprocess.run(['java','-Duser.home='+str(ROOT/'home'),'-jar',str(HERE/'AI-Office-Java.jar'),'--no-browser','--port','0','--data',str(ROOT/'data')],capture_output=True,text=True,timeout=10)
    check(duplicate.returncode==1 and (ROOT/'data/office.json').read_text()==before,'duplicate process cannot overwrite data')
    slow=post('task',{'owner':'Codex','title':'SLOW_MARKER'})['id'];wait('tasks',slow,{'執行中'});process.kill();process.wait(timeout=8);process=None
    start();check(next(t for t in state()['tasks'] if t['id']==slow)['status']=='已中斷','unfinished work recovered without automatic execution')
    stop_server();stored=json.loads((ROOT/'data/office.json').read_text());stored['version']='0.4-java';del stored['config']['Antigravity']
    (ROOT/'data/office.json').write_text(json.dumps(stored));start()
    check(state()['version']=='0.8.0-java' and state()['config']['Antigravity']['command']=='agy' and len(state()['tasks'])==len(stored['tasks']),'v0.4 migration preserves history and adds Antigravity')
    # New adapters: real local HTTP transport and mocked official Claude protocol.
    import threading
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
    class Handler(BaseHTTPRequestHandler):
        def log_message(self,*args):pass
        def do_GET(self):
            self.send_response(200);self.end_headers();self.wfile.write(json.dumps({'windows':[{'name':'daily','usedPercent':20,'resetsAt':2000000000}]}).encode())
        def do_POST(self):
            data=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            check(self.path=='/v1/chat/completions','company endpoint')
            check(self.headers.get('Authorization')=='Bearer private-test-key','company bearer')
            if data['model']=='bad':self.send_response(401);self.end_headers();self.wfile.write(b'private-test-key');return
            self.send_response(200);self.end_headers();self.wfile.write(json.dumps({'model':'company-local','choices':[{'message':{'content':'COMPANY_REPLY'}}],'usage':{'total_tokens':5}}).encode())
    mockserver=ThreadingHTTPServer(('127.0.0.1',0),Handler);threading.Thread(target=mockserver.serve_forever,daemon=True).start()
    claude=ROOT/'fake-claude';claude.write_text('#!'+sys.executable+'\n'+"import json,os,sys\nassert 'ANTHROPIC_API_KEY' not in os.environ\nif '--version' in sys.argv: print('Claude mock');sys.exit()\nif 'auth' in sys.argv: print(json.dumps({'loggedIn':True,'authMethod':'api_key' if os.path.exists("+repr(str(ROOT/'claude-api'))+") else 'claude.ai'}));sys.exit()\n\njson.loads(sys.argv[sys.argv.index('--settings')+1])\nsys.stdin.read()\nprint(json.dumps({'result':'CLAUDE_REPLY','is_error':False,'usage':{}}))\n");claude.chmod(0o755)
    config=state()['config'];config['Antigravity']['command']=str(ROOT/'fake-agy');config['Claude']={'command':str(claude),'model':''};config['Company']={'baseUrl':'http://127.0.0.1:'+str(mockserver.server_port)+'/v1','model':'good','apiKey':'private-test-key','timeoutSeconds':5}
    post('config',config)
    check('private-test-key' not in json.dumps(state()),'state hides company key')
    check('private-test-key' not in json.dumps(request('export')[1]),'export hides company key')
    before=state()['usage']['Company']
    check(post('company-test',{})['response']=='COMPANY_REPLY','company test transport')
    after=state()['usage']['Company']
    check(after['calls']==before['calls']+1 and after['diagnosticCalls']==before['diagnosticCalls']+1 and after['total']==before['total']+5,'company diagnostic usage accounted')
    check('private-test-key' not in json.dumps(request('export')[1]),'diagnostic export hides key')
    tid=post('task',{'owner':'Company','title':'公司分析'})['id'];check(wait('tasks',tid,{'完成'})['result']=='COMPANY_REPLY','company task')
    tid=post('task',{'owner':'Claude','title':'Claude 分析'})['id'];check(wait('tasks',tid,{'完成'})['result']=='CLAUDE_REPLY','Claude task and subscription')
    (ROOT/'claude-api').touch();tid=post('task',{'owner':'Claude','title':'禁止 API'})['id'];check('訂閱' in wait('tasks',tid,{'失敗'})['error'],'Claude rejects API auth');(ROOT/'claude-api').unlink()
    mid=post('meeting',{'topic':'跨工具會議','rounds':1,'participants':['Claude','Company','Codex','Gemini','Antigravity']})['id'];m=wait('meetings',mid,{'已結束'},timeout=20);check(len(m['messages'])==6,'five participant meeting')
    config=state()['config'];config['Company']['model']='bad';post('config',config);code,res=request('company-test',{});check(code==500 and '401' in res['error'] and 'private-test-key' not in str(res),'HTTP failure sanitization')
    config['Company']['clearApiKey']=True;post('config',config);check(not state()['config']['Company']['hasApiKey'],'clear key')
    rpc=ROOT/'fake-quota-codex';rpc.write_text('#!'+sys.executable+'\n'+"import sys,json\nfor line in sys.stdin:\n m=json.loads(line)\n if m.get('method')=='initialize':print(json.dumps({'id':m['id'],'result':{}}),flush=True)\n elif m.get('method')=='account/rateLimits/read':print(json.dumps({'id':m['id'],'result':{'rateLimits':{'primary':{'usedPercent':55,'windowDurationMins':300,'resetsAt':2000000000}}}}),flush=True)\n");rpc.chmod(0o755)
    config=state()['config'];config['Codex']['command']=str(rpc);config['Company']['quotaUrl']=config['Company']['baseUrl']+'/quota';config['Company']['model']='good';config['Company']['apiKey']='private-test-key';post('config',config);post('company-test',{})
    snapshot={'rate_limits':{'five_hour':{'used_percentage':23.5,'resets_at':2000000000}}}
    cap=subprocess.run(['java','-jar',str(HERE/'AI-Office-Java.jar'),'--capture-claude-quota',str(ROOT/'data/claude-quota.json')],input=json.dumps(snapshot),text=True,capture_output=True);check(cap.returncode==0,'Claude statusLine bridge')
    post('quota-refresh',{});view=state();check(view['quotas']['Codex']['windows'][0]['usedPercent']==55,'official Codex RPC quota')
    check(view['quotas']['Claude']['windows'][0]['usedPercent']==23.5,'Claude snapshot quota')
    check(view['quotas']['Company']['windows'][0]['usedPercent']==20,'company GET quota')
    check(view['usage']['Codex']['reportedCalls']>0 and view['usage']['Company']['total']>0,'usage aggregation')
    check(view['usage']['Claude']['calls']>0 and view['usage']['Claude']['reportedCalls']==0,'missing token usage not zero')
    check(view['usage']['Codex']['calls']==sum(1 for t in view['tasks'] if t['owner']=='Codex' and t.get('result'))+sum(1 for m in view['meetings'] for msg in m['messages'] if msg['who']=='Codex')+sum(1 for m in view['meetings'] if m.get('summary') and m['summarizer']=='Codex'),'meeting summaries included')
    check(view['connections']['Company']['authenticated'] is True,'company test marks connection successful')
    stable=state()['config'];post('config',stable);check(state()['connections']==view['connections'],'unchanged config preserves connection status')
    silent=ROOT/'silent-codex';silent.write_text('#!'+sys.executable+'\nimport time\ntime.sleep(120)\n');silent.chmod(0o755)
    stalled=state()['config'];stalled['Codex']['command']=str(silent);post('config',stalled);started=time.monotonic();post('quota-refresh',{});check(time.monotonic()-started<20,'unresponsive quota process times out without deadlock')
    check(state()['quotas']['Codex']['windows']==[],'quota timeout does not show false balance')
    post('config',config);post('quota-refresh',{});check(state()['quotas']['Codex']['windows'][0]['usedPercent']==55,'quota polling recovers after timeout')
    config['Company']['quotaUrl']='https://outside.invalid/quota';check(request('config',config)[0]==400,'cross-origin quota URL denied')

    mr_rev = post('mr-review', {'mrUrl': 'https://gitlab.example.com/org/repo/-/merge_requests/1', 'baseSha': 'main', 'headSha': 'feat-branch', 'participants': ['Gemini','Antigravity']})['id']
    mr_item = wait('mrReviews', mr_rev, {'已完成','失敗'}, timeout=120)
    check(mr_item['status'] == '已完成', 'multi-agent MR review pipeline completed')
    check(len(mr_item['parallelFindings']) == 2, 'parallel findings collected for 2 agents')
    check(len(mr_item['crossReviews']) == 2, 'cross reviews completed for 2 agents')
    check(len(mr_item['summaryReport']) > 0, 'summary report generated')
    t_del = post('task', {'owner': 'Gemini', 'title': 'Task To Delete'})['id']
    wait('tasks', t_del, {'完成'})
    check(post('task-delete', {'id': t_del})['canUndo'] is True, 'task delete returns undoable')
    undo_res = post('undo-delete', {})
    check(undo_res['ok'] is True and undo_res['id'] == t_del, 'undo task delete recovers task')
    diagnostic=state()['usage']['Company']
    stop_server();start()
    check(state()['usage']['Company']==diagnostic,'diagnostic usage persists across restart')
    check('private-test-key' not in json.dumps(request('export')[1]),'persisted diagnostic export hides key')
    mockserver.shutdown()
    print('Integration checks passed:',checks)
finally:
    stop_server()
