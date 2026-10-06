"""Generate private Docker settings without displaying secret values; never overwrite a file."""
import argparse
import os
from pathlib import Path
import secrets

ROOT = Path(__file__).resolve().parents[1]
KEYS = ('MYSQL_ROOT_PASSWORD', 'MYSQL_PASSWORD', 'PG_PASSWORD', 'REDIS_PASSWORD',
        'JWT_SECRET', 'ENCRYPTION_KEY', 'BOOTSTRAP_ADMIN_PASSWORD', 'PDF_PARSER_TOKEN')

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / '.env.docker')
    args = parser.parse_args()
    data = (ROOT / '.env.docker.example').read_text(encoding='utf-8')
    for key in KEYS:
        data = data.replace(f'{key}=CHANGE_ME', f'{key}={secrets.token_hex(32)}')
    with args.output.open('x', encoding='utf-8', newline='\n') as f:
        f.write(data)
    if os.name != 'nt': args.output.chmod(0o600)
    print('Private configuration created. Open it locally to view the administrator password; do not commit it.')

if __name__ == '__main__': main()
