#!/usr/bin/env python3
"""PaiSmart release worker. Standard library only; no shell evaluation of .env."""
import base64
import fcntl
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.request
import urllib.parse
import zipfile

JAR = 'SmartPAI-0.0.1-SNAPSHOT.jar'


def digest(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for b in iter(lambda: f.read(1024 * 1024), b''):
            h.update(b)
    return h.hexdigest()


def read_env(path):
    values = {}
    for line in Path(path).read_text().splitlines():
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        m = re.fullmatch(r'([A-Za-z_][A-Za-z0-9_]*)=(.*)', line)
        if m:
            k, v = m.groups()
            if len(v) >= 2 and v[0] == v[-1] and v[0] in "\"'":
                v = v[1:-1]
            values[k] = v
    return values


def write_json(path, obj):
    p = Path(str(path) + '.tmp')
    p.write_text(json.dumps(obj, indent=2, ensure_ascii=False))
    os.replace(p, path)


def request(url, data=None, token=None):
    headers = {'Cache-Control': 'no-cache'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    if data is not None:
        headers['Content-Type'] = 'application/json'
        data = json.dumps(data).encode()
    req = urllib.request.Request(url, data=data, headers=headers)
    with urllib.request.urlopen(req, timeout=20) as r:
        if r.status != 200:
            raise RuntimeError('Unexpected HTTP status')
        return r.read()


def api_check(base, env, new=False):
    body = json.loads(request(base + '/api/v1/users/login', {
        'username': env['ADMIN_BOOTSTRAP_USERNAME'],
        'password': env['ADMIN_BOOTSTRAP_PASSWORD']}))
    if body.get('code') != 200:
        raise RuntimeError('Login rejected')
    token = body['data']['token']
    paths = ['/api/v1/users/me', '/api/v1/admin/invite-codes?page=1&size=1']
    if new:
        paths.append('/api/v1/admin/document-conflicts?page=1&size=1')
    for path in paths:
        b = json.loads(request(base + path, token=token))
        if b.get('code') != 200:
            raise RuntimeError('API check failed: ' + path)
    return paths


def assets(index):
    return re.findall(r'(?:src|href)=["\'](/assets/[^"\']+)["\']', index)


def static_check(base, directory):
    expected = (directory / 'index.html').read_bytes()
    if request(base + '/') != expected:
        raise RuntimeError('Public index differs from release')
    paths = assets(expected.decode())
    if not paths:
        raise RuntimeError('No entry assets in index')
    for path in paths:
        if hashlib.sha256(request(base + path)).hexdigest() != digest(directory / path.lstrip('/')):
            raise RuntimeError('Public asset differs: ' + path)
    return len(paths)


def db_settings(env):
    parsed = urllib.parse.urlsplit(env['SPRING_DATASOURCE_URL'].removeprefix('jdbc:'))
    name = parsed.path.lstrip('/')
    if not re.fullmatch(r'[A-Za-z0-9_]+', name):
        raise RuntimeError('Unsupported database name')
    return parsed.hostname or 'localhost', parsed.port or 3306, name


def cnf_quote(value):
    return '"' + str(value).replace('\\', '\\\\').replace('"', '\\"').replace('\n', '\\n').replace('\r', '\\r') + '"'


def db_backup(env, target):
    host, port, name = db_settings(env)
    # Temporary defaults file is created with 0600 before credentials are written.
    with tempfile.TemporaryDirectory(prefix='paismart-db-') as td:
        cnf = Path(td) / 'client.cnf'
        fd = os.open(cnf, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'w') as f:
            f.write('[client]\n' + '\n'.join(k + '=' + cnf_quote(v) for k, v in {
                'host': host, 'port': port, 'user': env['SPRING_DATASOURCE_USERNAME'],
                'password': env['SPRING_DATASOURCE_PASSWORD']}.items()) + '\n')
        tmp = target.with_suffix('.partial')
        with open(Path(td) / 'stderr', 'wb') as err:
            p = subprocess.Popen(['mysqldump', '--defaults-extra-file=' + str(cnf),
                                  '--single-transaction', '--quick', '--routines', '--triggers',
                                  '--events', '--hex-blob', '--set-gtid-purged=OFF', name],
                                 stdout=subprocess.PIPE, stderr=err)
            try:
                with gzip.open(tmp, 'wb') as f:
                    shutil.copyfileobj(p.stdout, f)
                rc = p.wait(timeout=300)
            finally:
                p.stdout.close()
                if p.poll() is None:
                    p.kill()
                    p.wait()
            if rc:
                raise RuntimeError('mysqldump failed; service untouched, partial backup not accepted')
        # Read every byte to validate CRC and require a complete nonempty SQL dump.
        has_table = False
        tail = b''
        with gzip.open(tmp, 'rb') as f:
            for b in iter(lambda: f.read(1024 * 1024), b''):
                has_table |= b'CREATE TABLE' in b
                tail = (tail + b)[-4096:]
        if not has_table or b'Dump completed' not in tail:
            raise RuntimeError('Incomplete SQL backup')
        os.replace(tmp, target)
    return {'database': name, 'sha256': digest(target), 'bytes': target.stat().st_size}


def assert_pid(backend):
    pid = int((backend / 'pid.log').read_text().strip())
    raw = Path('/proc/{}/cmdline'.format(pid)).read_bytes().split(b'\0')
    cmd = [b.decode() for b in raw if b]
    if Path('/proc/{}/cwd'.format(pid)).resolve() != backend.resolve():
        raise RuntimeError('PID working directory mismatch')
    if '-jar' not in cmd or Path(cmd[cmd.index('-jar') + 1]).name != JAR:
        raise RuntimeError('PID is not PaiSmart')
    return pid, cmd


def alive(pid):
    try:
        return Path('/proc/{}/stat'.format(pid)).read_text().split(') ', 1)[1][0] != 'Z'
    except FileNotFoundError:
        return False


def stop(backend):
    pid, _ = assert_pid(backend)
    os.kill(pid, signal.SIGTERM)
    end = time.monotonic() + 30
    while alive(pid) and time.monotonic() < end:
        time.sleep(1)
    if alive(pid):
        os.kill(pid, signal.SIGKILL)
        for _ in range(10):
            if not alive(pid):
                break
            time.sleep(1)
    if alive(pid):
        raise RuntimeError('Cannot stop old process')


def start(backend, env, cmd, log):
    merged = dict(os.environ)
    merged.update(env)
    with open(log, 'ab', buffering=0) as out:
        p = subprocess.Popen(cmd, cwd=backend, env=merged, stdin=subprocess.DEVNULL,
                             stdout=out, stderr=out, start_new_session=True)
    (backend / 'pid.log').write_text(str(p.pid) + '\n')
    return p.pid


def wait_ready(backend, pid, log):
    end = time.monotonic() + 150
    while time.monotonic() < end:
        if not alive(pid):
            raise RuntimeError('Application exited; see restricted release startup log')
        if log.exists() and b'Started SmartPaiApplication' in log.read_bytes():
            actual, _ = assert_pid(backend)
            if actual != pid:
                raise RuntimeError('Running PID changed')
            return
        time.sleep(2)
    raise RuntimeError('Application startup timed out')


def atomic_copy(src, dest):
    tmp = dest.with_name(dest.name + '.release-tmp')
    shutil.copy2(src, tmp)
    os.replace(tmp, dest)


def unpack(zip_path, dest):
    with zipfile.ZipFile(zip_path) as z:
        for item in z.infolist():
            p = Path(item.filename)
            if p.is_absolute() or '..' in p.parts or not p.parts or p.parts[0] != 'dist':
                raise RuntimeError('Unsafe frontend archive path')
            if (item.external_attr >> 16) & 0o170000 == 0o120000:
                raise RuntimeError('Symlink in frontend archive')
        z.extractall(dest)
    index = dest / 'dist/index.html'
    if not index.is_file():
        raise RuntimeError('Missing frontend index')
    for p in assets(index.read_text()):
        if not (dest / 'dist' / p.lstrip('/')).is_file():
            raise RuntimeError('Missing frontend entry asset')
    # Nginx needs to traverse/read these; only static assets are made world-readable.
    for p in dest.rglob('*'):
        p.chmod(0o755 if p.is_dir() else 0o644)
    dest.chmod(0o755)


class Release:
    def __init__(self, config):
        self.c = config
        self.backend = Path(config['backend'])
        self.frontend = Path(config['frontend'])
        self.directory = self.backend / '.releases' / config['release_id']
        self.env = read_env(self.backend / '.env')
        self.public = config['public_url']
        self.local = 'http://127.0.0.1:' + self.env.get('SERVER_PORT', '8081')
        self.state_path = self.directory / 'state.json'

    def status(self):
        return json.loads(self.state_path.read_text()) if self.state_path.exists() else {'status': 'staged'}

    def state(self, status, **fields):
        d = self.status()
        d.update(fields, status=status, updated_at=time.strftime('%Y-%m-%dT%H:%M:%S%z'))
        write_json(self.state_path, d)
        print(json.dumps(d, ensure_ascii=False), flush=True)

    def probe(self):
        pid, _ = assert_pid(self.backend)
        api_check(self.local, self.env)
        static_check(self.public, self.frontend / 'dist')
        if shutil.disk_usage(self.backend).free < 3 * 1024**3:
            raise RuntimeError('Less than 3 GiB free disk')
        for binary in ('mysqldump', 'mysql'):
            if not shutil.which(binary):
                raise RuntimeError('Missing ' + binary)
        return {'status': 'probe_ok', 'pid': pid, 'jar_sha256': digest(self.backend / JAR),
                'index_sha256': digest(self.frontend / 'dist/index.html'),
                'database': db_settings(self.env)[2]}

    def prepare(self):
        if self.status()['status'] != 'staged':
            raise RuntimeError('Release already prepared; use another release ID')
        manifest = json.loads((self.directory / 'manifest.json').read_text())
        for name, key in [(JAR, 'jar_sha256'), ('dist.zip', 'dist_sha256')]:
            if digest(self.directory / name) != manifest[key]:
                raise RuntimeError('Uploaded artifact hash mismatch: ' + name)
        self.probe()
        _, cmd = assert_pid(self.backend)
        info = {'cmd': cmd, 'env_sha256': digest(self.backend / '.env'),
                'old_jar_sha256': digest(self.backend / JAR),
                'old_index_sha256': digest(self.frontend / 'dist/index.html')}
        # Unique directory, no cleanup of prior releases or backups.
        backups = self.directory / 'backup'
        backups.mkdir(mode=0o700)
        info['database_backup'] = db_backup(self.env, backups / 'database.sql.gz')
        shutil.copy2(self.backend / JAR, backups / JAR)
        with tarfile.open(backups / 'dist.tar.gz', 'w:gz', dereference=True) as t:
            t.add(self.frontend / 'dist', arcname='dist')
        with tarfile.open(backups / 'dist.tar.gz', 'r:gz') as t:
            if 'dist/index.html' not in t.getnames():
                raise RuntimeError('Invalid frontend backup')
        release_static = self.frontend / 'releases' / self.c['release_id']
        release_static.parent.mkdir(mode=0o755, exist_ok=True)
        release_static.parent.chmod(0o755)
        release_static.mkdir(mode=0o755)
        unpack(self.directory / 'dist.zip', release_static)
        write_json(self.directory / 'before.json', info)
        self.state('prepared', commit=manifest['commit'], jar_sha256=manifest['jar_sha256'],
                   database_backup=info['database_backup'])

    def check(self, new=True):
        manifest = json.loads((self.directory / 'manifest.json').read_text())
        before = json.loads((self.directory / 'before.json').read_text())
        expected = manifest['jar_sha256'] if new else before['old_jar_sha256']
        if digest(self.backend / JAR) != expected:
            raise RuntimeError('Installed JAR hash mismatch')
        pid, _ = assert_pid(self.backend)
        if digest(self.backend / '.env') != before['env_sha256']:
            raise RuntimeError('Online .env changed')
        api_check(self.local, self.env, new)
        api_check(self.public, self.env, new)
        count = static_check(self.public, self.frontend / 'dist')
        return {'pid': pid, 'jar_sha256': expected, 'entry_assets_checked': count}

    def rollback(self):
        before = json.loads((self.directory / 'before.json').read_text())
        # Validate backups before stopping a process.
        backup = self.directory / 'backup' / JAR
        if digest(backup) != before['old_jar_sha256']:
            raise RuntimeError('Rollback JAR checksum failed')
        pidfile = self.backend / 'pid.log'
        if pidfile.exists() and alive(int(pidfile.read_text())):
            stop(self.backend)
        atomic_copy(backup, self.backend / JAR)
        prev = self.frontend / ('dist.prev.' + self.c['release_id'])
        current = self.frontend / 'dist'
        if prev.exists() or prev.is_symlink():
            if current.exists() or current.is_symlink():
                current.rename(self.frontend / ('dist.failed.' + self.c['release_id'] + '.' + str(int(time.time()))))
            prev.rename(current)
        # If cutover failed before moving the old directory, it is still current.
        if digest(current / 'index.html') != before['old_index_sha256']:
            raise RuntimeError('Rollback frontend mismatch')
        log = self.directory / ('rollback-' + str(int(time.time())) + '.log')
        pid = start(self.backend, self.env, before['cmd'], log)
        wait_ready(self.backend, pid, log)
        result = self.check(new=False)
        self.state('rolled_back', **result)

    def apply(self):
        if self.status()['status'] != 'prepared':
            raise RuntimeError('Release is not prepared')
        before = json.loads((self.directory / 'before.json').read_text())
        manifest = json.loads((self.directory / 'manifest.json').read_text())
        for name, key in [(JAR, 'jar_sha256'), ('dist.zip', 'dist_sha256')]:
            if digest(self.directory / name) != manifest[key]:
                raise RuntimeError('Staging hash changed')
        if digest(self.backend / JAR) != before['old_jar_sha256'] or digest(self.backend / '.env') != before['env_sha256']:
            raise RuntimeError('Online files changed since backup')
        self.probe()
        prev = self.frontend / ('dist.prev.' + self.c['release_id'])
        if prev.exists() or prev.is_symlink():
            raise RuntimeError('Previous frontend path already exists')
        new_dist = self.frontend / 'releases' / self.c['release_id'] / 'dist'
        if not (new_dist / 'index.html').is_file():
            raise RuntimeError('Staged frontend missing')
        self.state('applying')
        try:
            stop(self.backend)
            atomic_copy(self.directory / JAR, self.backend / JAR)
            # Two renames leave a very short gap only on the initial directory-to-symlink switch.
            (self.frontend / 'dist').rename(prev)
            next_link = self.frontend / ('dist.next.' + self.c['release_id'])
            next_link.symlink_to(new_dist)
            os.replace(next_link, self.frontend / 'dist')
            log = self.directory / 'startup.log'
            pid = start(self.backend, self.env, before['cmd'], log)
            wait_ready(self.backend, pid, log)
            result = self.check(new=True)
            # Runners may complete just after Spring's Started log line.
            time.sleep(5)
            if not alive(pid):
                raise RuntimeError('Application exited after initial readiness')
            self.state('succeeded', **result)
        except BaseException as exc:
            self.state('rolling_back', failure_type=type(exc).__name__)
            try:
                self.rollback()
            except BaseException as rollback_error:
                self.state('rollback_failed', failure_type=type(rollback_error).__name__)
                raise
            raise RuntimeError('Release failed and was rolled back') from exc


def main():
    os.umask(0o077)
    action, encoded = sys.argv[1:3]
    c = json.loads(base64.b64decode(encoded))
    if not re.fullmatch(r'[A-Za-z0-9_-]+', c['release_id']):
        raise RuntimeError('Invalid release ID')
    r = Release(c)
    if action == 'probe':
        print(json.dumps(r.probe()))
    elif action == 'status':
        print(json.dumps(r.status()))
    elif action == 'verify':
        print(json.dumps(r.check(r.status()['status'] != 'rolled_back')))
    else:
        with open(r.backend / '.release.lock', 'a') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            if action not in ('prepare', 'apply', 'rollback'):
                raise RuntimeError('Unsupported action')
            try:
                getattr(r, action)()
            except Exception:
                if action == 'apply' and r.status()['status'] == 'prepared':
                    r.state('failed', failure_type='PreCutoverCheckFailed')
                raise


if __name__ == '__main__':
    try:
        main()
    except Exception as exc:
        # Never dump credential-bearing locals, response bodies, or subprocess arguments.
        print('DEPLOY ERROR: ' + type(exc).__name__ + ': ' + str(exc), file=sys.stderr)
        sys.exit(1)
