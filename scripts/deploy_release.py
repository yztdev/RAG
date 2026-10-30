#!/usr/bin/env python3
"""Local release coordinator. DRY_RUN=1 never invokes ssh/scp or changes files."""
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import time
import zipfile

ROOT = Path(__file__).resolve().parent.parent
JAR = 'SmartPAI-0.0.1-SNAPSHOT.jar'
WORKER = ROOT / 'scripts/deploy_remote.py'


def sha(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for b in iter(lambda: f.read(1024 * 1024), b''):
            h.update(b)
    return h.hexdigest()


def git(*args):
    return subprocess.check_output(['git', '-C', str(ROOT), *args], text=True).strip()


def deploy_setting(name, default=None):
    """Read a deploy setting from the environment, then the git-ignored local .env."""
    value = os.environ.get(name)
    env_file = ROOT / '.env'
    if not value and env_file.is_file():
        for line in env_file.read_text(encoding='utf-8').splitlines():
            key, sep, raw = line.partition('=')
            if sep and key.strip() == name:
                value = raw.strip().strip('"\'')
                break
    return value or default


def ssh_target():
    """Server address and key path never live in the repo; see scripts/DEPLOY.md."""
    host = deploy_setting('DEPLOY_SERVER_HOST')
    key = deploy_setting('DEPLOY_SERVER_KEY')
    if not host or not key:
        raise RuntimeError('Set DEPLOY_SERVER_HOST and DEPLOY_SERVER_KEY in the environment or local .env')
    user = deploy_setting('DEPLOY_SERVER_USER', 'root')
    return user + '@' + host, key


def main():
    action =sys.argv[1] if len(sys.argv) > 1 else 'all'
    if action not in ('all', 'plan', 'preflight', 'prepare', 'publish', 'status', 'verify', 'rollback'):
        raise RuntimeError('Use plan/preflight/prepare/publish/all/status/verify/rollback')
    release_id = os.environ.get('RELEASE_ID')
    if not release_id or not re.fullmatch(r'[A-Za-z0-9_-]+', release_id):
        raise RuntimeError('Set RELEASE_ID explicitly (letters, digits, _ or -)')
    cfg = {'release_id': release_id,
           'backend': '/home/www/RAG知识库', 'frontend': '/home/www/RAG知识库-Front',
           'public_url': 'https://smart.paicoding.com'}
    remote_dir = cfg['backend'] + '/.releases/' + release_id
    encoded = base64.b64encode(json.dumps(cfg).encode()).decode()
    if action == 'plan' or os.environ.get('DRY_RUN', '1') != '0':
        print(json.dumps({'dry_run': True, 'action': action, 'config': cfg,
                          'steps': ['freeze artifacts and hashes', 'upload to unique release directory',
                                    'verify hashes, backup database/JAR/dist, extract frontend',
                                    'detach remote worker: switch, login/API/static checks, rollback on failure'],
                          'note': 'No local writes or SSH/SCP executed'}, indent=2))
        return
    host, key = ssh_target()
    opts =['-i', key, '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=15',
            '-o', 'StrictHostKeyChecking=yes', '-o', 'ServerAliveInterval=15']

    def ssh(command, source=None, capture=False):
        return subprocess.run(['ssh', *opts, host, command], input=source, text=True,
                              stdout=subprocess.PIPE if capture else None, check=True)

    def worker(phase, inline=False, capture=False):
        path = '-' if inline else remote_dir + '/deploy_remote.py'
        return ssh(shlex.join(['python3', path, phase, encoded]),
                   WORKER.read_text() if inline else None, capture)

    if action == 'preflight':
        worker('probe', inline=True)
        return
    if action in ('status', 'verify', 'rollback'):
        worker(action)
        return
    if action == 'publish':
        publish(ssh, worker, remote_dir, encoded, release_id)
        return
    local_dir = ROOT / 'target' / 'releases' / release_id
    if local_dir.exists():
        raise RuntimeError('Release ID already used; inspect status or choose a new ID')
    if git('diff', '--name-only', 'HEAD', '--', 'src', 'frontend', 'pom.xml'):
        raise RuntimeError('Tracked application changes are not committed; build provenance unclear')
    # Freeze a copy. Later builds in target/ cannot alter this release.
    local_dir.mkdir(parents=True)
    shutil.copy2(ROOT / 'target' / JAR, local_dir / JAR)
    with zipfile.ZipFile(local_dir / 'dist.zip', 'w', zipfile.ZIP_DEFLATED) as z:
        for p in sorted((ROOT / 'frontend/dist').rglob('*')):
            if p.is_symlink():
                raise RuntimeError('Frontend contains symlink')
            if p.is_file():
                z.write(p, 'dist/' + str(p.relative_to(ROOT / 'frontend/dist')))
    shutil.copy2(WORKER, local_dir / 'deploy_remote.py')
    manifest = {'release_id': release_id, 'commit': git('rev-parse', 'HEAD'),
                'jar_sha256': sha(local_dir / JAR), 'dist_sha256': sha(local_dir / 'dist.zip'),
                'index_sha256': sha(ROOT / 'frontend/dist/index.html'),
                'worker_sha256': sha(local_dir / 'deploy_remote.py')}
    (local_dir / 'manifest.json').write_text(json.dumps(manifest, indent=2))
    print(json.dumps(manifest, indent=2), flush=True)
    worker('probe', inline=True)
    ssh('umask 077; mkdir -p ' + shlex.quote(cfg['backend'] + '/.releases') +
        ' && mkdir ' + shlex.quote(remote_dir))
    for name in (JAR, 'dist.zip', 'manifest.json', 'deploy_remote.py'):
        subprocess.run(['scp', *opts, str(local_dir / name), host + ':' + remote_dir + '/' + name], check=True)
    # Worker itself is verified before execution too.
    got = ssh('sha256sum ' + shlex.quote(remote_dir + '/deploy_remote.py'), capture=True).stdout.split()[0]
    if got != manifest['worker_sha256']:
        raise RuntimeError('Remote worker hash mismatch')
    worker('prepare')
    if action == 'prepare':
        return
    publish(ssh, worker, remote_dir, encoded, release_id)


def publish(ssh, worker, remote_dir, encoded, release_id):
    current = json.loads(worker('status', capture=True).stdout)
    if current['status'] != 'prepared':
        raise RuntimeError('Only a prepared release can be published')
    # Detached worker owns rollback even if the local task/SSH disappears.
    cmd = shlex.join(['python3', remote_dir + '/deploy_remote.py', 'apply', encoded])
    ssh('nohup ' + cmd + ' > ' + shlex.quote(remote_dir + '/deployment.log') +
        ' 2>&1 < /dev/null & echo "Remote deployment worker PID=$!"')
    end = time.monotonic() + 600
    last = None
    while time.monotonic() < end:
        result = json.loads(worker('status', capture=True).stdout)
        if result['status'] != last:
            print(json.dumps(result), flush=True)
            last = result['status']
        if last == 'succeeded':
            worker('verify')
            print('RELEASE VERIFIED: ' + release_id, flush=True)
            return
        if last in ('rolled_back', 'rollback_failed', 'failed'):
            raise RuntimeError('Release did not succeed: ' + last)
        time.sleep(5)
    raise RuntimeError('Worker still pending; inspect status and restricted deployment.log; do not start another release')


if __name__ == '__main__':
    try:
        main()
    except Exception as exc:
        print('DEPLOY ERROR: ' + type(exc).__name__ + ': ' + str(exc), file=sys.stderr)
        sys.exit(1)
