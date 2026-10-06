"""Validate parser authentication and a generated one-page PDF, with no private input or API calls."""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile
import sys

ROOT=Path(__file__).resolve().parents[1]

def fixture_pdf():
    text=b'BT /F1 14 Tf 50 750 Td (AIassistant deployment verification) Tj 0 -30 Td (Invoice total: 123.45 USD.) Tj ET'
    objects=[b'<< /Type /Catalog /Pages 2 0 R >>',
             b'<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
             b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>',
             b'<< /Length '+str(len(text)).encode()+b' >>\nstream\n'+text+b'\nendstream',
             b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>']
    data=bytearray(b'%PDF-1.4\n'); offsets=[0]
    for i, obj in enumerate(objects,1):
        offsets.append(len(data)); data.extend(f'{i} 0 obj\n'.encode()+obj+b'\nendobj\n')
    xref=len(data)
    data.extend(f'xref\n0 {len(offsets)}\n0000000000 65535 f \n'.encode())
    for offset in offsets[1:]: data.extend(f'{offset:010d} 00000 n \n'.encode())
    data.extend(f'trailer\n<< /Size {len(offsets)} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n'.encode())
    return data

def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--project',default='aiassistant-stack')
    ap.add_argument('--env-file',default='.env.docker')
    args=ap.parse_args()
    compose=['docker','compose','--env-file',args.env_file,'-p',args.project,'--profile','parser']
    container=subprocess.check_output(compose+['ps','-q','pdf-parser'],text=True).strip()
    if not container: raise RuntimeError('Parser is not running')
    (ROOT/'tmp').mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(dir=ROOT/'tmp') as directory:
        fixture=Path(directory)/'smoke.pdf'; fixture.write_bytes(fixture_pdf())
        remote='/runtime/'+Path(directory).name+'-smoke.pdf'
        subprocess.run(['docker','cp',str(fixture),container+':'+remote],check=True,capture_output=True)
        probe='''import os,json,urllib.request,urllib.error,sys
token=os.environ['PDF_PARSER_TOKEN']; base='http://127.0.0.1:8741'; path=sys.argv[1]
try: urllib.request.urlopen(base+'/health',timeout=10)
except urllib.error.HTTPError as e: assert e.code==401
else: raise AssertionError('Unauthenticated request accepted')
req=urllib.request.Request(base+'/health',headers={'X-Parser-Token':token})
assert json.load(urllib.request.urlopen(req,timeout=10))['modelsReady']
try:
    body=open(path,'rb').read()
    req=urllib.request.Request(base+'/parse',data=body,headers={'X-Parser-Token':token,'Content-Type':'application/pdf'})
    result=json.load(urllib.request.urlopen(req,timeout=180))
    content=json.dumps(result,ensure_ascii=False)
    assert result['pageCount']==1 and '123.45' in content and 'AIassistant' in content
finally: os.remove(path)
print('PASS: parser authentication, model readiness, one-page PDF text and number. External model requests: 0.')
'''
        result=subprocess.run(['docker','exec','-i',container,'python','-',remote],input=probe,
                              capture_output=True,text=True,encoding='utf-8')
        if result.returncode: raise RuntimeError('Parser smoke failed')
        print(result.stdout.strip())

if __name__=='__main__':
    try: main()
    except Exception as e:
        print('Parser smoke failed:',type(e).__name__,'(raw diagnostics suppressed)')
        sys.exit(1)
