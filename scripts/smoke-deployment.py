"""Exercise a fresh deployment without external model calls; never print credentials or messages."""
import argparse
import json
from pathlib import Path
import sys
import subprocess
import urllib.error
import urllib.request

def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--url', default='http://127.0.0.1:8080')
    ap.add_argument('--env-file', type=Path, default=Path('.env.docker'))
    ap.add_argument('--restart', action='store_true', help='Also restart the backend and verify persisted memory and task records')
    ap.add_argument('--project', default='aiassistant-stack', help='Compose project to restart when --restart is explicitly supplied')
    args = ap.parse_args()
    settings = dict(line.split('=', 1) for line in args.env_file.read_text(encoding='utf-8').splitlines()
                    if '=' in line and not line.lstrip().startswith('#'))
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    token = None
    count = 0
    def request(path, body=None, method=None, raw=False):
        headers = {'Content-Type': 'application/json'}
        if token: headers['Authorization'] = 'Bearer ' + token
        req = urllib.request.Request(args.url.rstrip('/') + path,
                data=json.dumps(body).encode() if body is not None else None, headers=headers, method=method)
        with opener.open(req, timeout=60) as response:
            data=response.read().decode('utf-8')
            if raw: return data
            result=json.loads(data)
            if 'code' in result:
                assert result['code']==0, 'API reported failure'
                return result.get('data')
            return result
    def passed(name):
        nonlocal count
        count += 1
        print('PASS:', name)
    assert '<html' in request('/', raw=True).lower(); passed('Frontend served')
    assert request('/api/actuator/health')['status']=='UP'; passed('Backend and database health')
    try: request('/api/chat/sessions')
    except urllib.error.HTTPError as e: assert e.code==401
    else: raise AssertionError('Unauthenticated access accepted')
    passed('Authentication required')
    login=request('/api/auth/login', {'username':'admin','password':settings['BOOTSTRAP_ADMIN_PASSWORD'],'scope':'admin'})
    token=login['accessToken']; passed('Generated administrator can log in')
    request('/api/auth/me'); passed('Authenticated profile')
    # Fresh test session is removed even when any later check fails.
    session=request('/api/chat/sessions', {'title':'Deployment smoke check'})
    sid=session['id']; passed('Create session')
    try:
        memory=request(f'/api/chat/sessions/{sid}/memory'); assert isinstance(memory,dict)
        passed('Memory endpoint available')
        stream=request(f'/api/chat/sessions/{sid}/stream', {'content':'123+456等于多少','knowledgeMode':'NONE'}, raw=True)
        assert '579' in stream, 'Local calculation failed'
        assert 'event:error' not in stream and 'event: error' not in stream, 'SSE returned error'
        usage=[]
        for block in stream.replace('\r\n','\n').split('\n\n'):
            lines=block.splitlines()
            if any(line.replace(' ','')=='event:usage' for line in lines):
                usage.append(json.loads('\n'.join(line[5:].lstrip() for line in lines if line.startswith('data:'))))
        assert usage and all(u['modelRequests']==0 and u['promptTokens']==0 and u['completionTokens']==0 for u in usage)
        passed('Local calculation streamed through Nginx without model credentials')
        runs=request(f'/api/chat/sessions/{sid}/runs'); assert len(runs)>0
        passed('Task journal accessible')
        if args.restart:
            memory=request(f'/api/chat/sessions/{sid}/memory')
            updated=request(f'/api/chat/sessions/{sid}/memory',
                {'label':'部署测试预算','value':'2300元','category':'FACT','pinned':False,'version':memory['version']})
            assert any(i['value']=='2300元' for i in updated['items'])
            passed('Write synthetic memory for restart check')
            compose=['docker','compose','--env-file',str(args.env_file),'-p',args.project]
            subprocess.run(compose+['restart','backend'],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
            subprocess.run(compose+['up','-d','--wait','--wait-timeout','180','backend','web'],
                check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
            login=request('/api/auth/login', {'username':'admin','password':settings['BOOTSTRAP_ADMIN_PASSWORD'],'scope':'admin'})
            token=login['accessToken']; passed('Administrator password preserved after restart')
            restored=request(f'/api/chat/sessions/{sid}/memory')
            assert any(i['value']=='2300元' for i in restored['items'])
            passed('Memory persists after backend restart')
            restored_runs=request(f'/api/chat/sessions/{sid}/runs')
            assert {r['id'] for r in runs}<={r['id'] for r in restored_runs}
            passed('Task journal persists after backend restart')
    finally:
        request(f'/api/chat/sessions/{sid}', method='DELETE')
    passed('Delete test session')
    print(f'{count} deployment checks passed. External model requests: 0; model tokens: 0.')

if __name__=='__main__':
    try: main()
    except Exception as e:
        print('Deployment smoke failed:', type(e).__name__, '(details suppressed to protect credentials)')
        sys.exit(1)
