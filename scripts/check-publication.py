"""Check the Git index or prospective source files without displaying matched secrets."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
FORBIDDEN = ('tmp/', 'uploads/', 'eval/', 'dev/', 'docs/求职/', '.codex/', '.idea/',
             '.memsearch/', '.setup/', 'backend/config/', 'node_modules/', 'target/', 'dist/')
REVIEWED_ASSETS = {'user-frontend/public/logo.png': '0e9b59d2040595fdfaafea34d7cc123c8d68fb73cb3838f782ff23778a15a16b'}

def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--git', default='git')
    ap.add_argument('--staged', action='store_true')
    ap.add_argument('--context', type=Path, help='Audit an exported Docker build context')
    ap.add_argument('--manifest', type=Path, help='Write an exact Git pathspec manifest only after a clean audit')
    args = ap.parse_args()
    command = [args.git, 'ls-files', '-z']
    if not args.staged: command += ['--cached', '--others', '--exclude-standard']
    if args.context:
        files = sorted(p.relative_to(args.context).as_posix() for p in args.context.rglob('*') if p.is_file())
    else:
        files = sorted(set(subprocess.check_output(command, cwd=ROOT).decode('utf-8').strip('\0').split('\0')))
    private_values = set()
    for p in (ROOT / '.env', ROOT / 'backend/config/legacy-encryption.key'):
        if not p.exists(): continue
        if p.suffix == '.key': private_values.add(p.read_text(encoding='utf-8').strip()); continue
        for line in p.read_text(encoding='utf-8-sig').splitlines():
            if '=' not in line or line.lstrip().startswith('#'): continue
            name, value = line.split('=', 1)
            value = value.strip().strip('"\'')
            if re.search(r'(?:KEY|TOKEN|SECRET|PWD|PASSWORD)', name) and len(value) >= 12:
                private_values.add(value)
    patterns = {
        'API token literal': r'(?:sk-|ghp_|github_pat_)[A-Za-z0-9_-]{24,}',
        'private key': r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----',
        'personal local path': r'(?:C:[/\\]Users[/\\]PREOATOR|E:[/\\]证件信息)',
        'possible phone number': r'(?<!\d)1[3-9]\d{9}(?!\d)',
    }
    failures = []
    total = 0
    for name in files:
        if not name: continue
        path = ROOT / name
        if any(part in name for part in FORBIDDEN) or (path.name.startswith('.env') and name != '.env.docker.example') or path.name == 'application-dev.yml':
            failures.append((name, 0, 'private/runtime file')); continue
        if args.context: data = (args.context / name).read_bytes()
        else: data = subprocess.check_output([args.git, 'show', ':' + name], cwd=ROOT) if args.staged else path.read_bytes()
        total += len(data)
        if name in REVIEWED_ASSETS and hashlib.sha256(data).hexdigest() == REVIEWED_ASSETS[name]: continue
        if len(data) > 2 * 1024 * 1024: failures.append((name, 0, 'large non-source file'))
        try: lines = data.decode('utf-8-sig').splitlines()
        except UnicodeDecodeError:
            failures.append((name, 0, 'binary file needs explicit review')); continue
        for number, line in enumerate(lines, 1):
            if any(value in line for value in private_values if value): failures.append((name, number, 'local private secret match'))
            for category, pattern in patterns.items():
                if re.search(pattern, line): failures.append((name, number, category))
    for name, line, category in failures: print(f'{name}:{line}: {category}')
    print(f'Publication audit: {len(files)} files, {total:,} bytes, {len(failures)} findings. Secret values are never printed.')
    if args.manifest and not failures and not args.context:
        args.manifest.write_bytes(b''.join((':(literal)'+name).encode('utf-8')+b'\0' for name in files if name))
        print('Exact audited publication manifest created.')
    return 1 if failures else 0

if __name__ == '__main__': sys.exit(main())
