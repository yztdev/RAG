import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SCRIPT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('deploy_remote', SCRIPT / 'deploy_remote.py')
r = importlib.util.module_from_spec(spec)
spec.loader.exec_module(r)


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def test_env_is_literal_and_keeps_special_characters(self):
        p = self.root / '.env'
        value = 'a$HOME`uname`$(touch NOPE)!#=\\z'
        p.write_text('# ignored\nSECRET_2=\'' + value + '\'\nOTHER="x=y"\nRAW=abc#xyz\n')
        self.assertEqual(r.read_env(p), {'SECRET_2': value, 'OTHER': 'x=y', 'RAW': 'abc#xyz'})
        self.assertFalse((self.root / 'NOPE').exists())

    def test_mysql_option_escaping(self):
        self.assertEqual(r.cnf_quote('a"b\\c\nd'), '"a\\"b\\\\c\\nd"')

    def test_all_dry_actions_invoke_no_remote_command_and_make_no_files(self):
        # A mock PATH rejects and records any ssh/scp invocation.
        bindir = self.root / 'bin'
        bindir.mkdir()
        marker = self.root / 'called'
        for command in ('ssh', 'scp'):
            p = bindir / command
            p.write_text('#!/bin/sh\ntouch "' + str(marker) + '"\nexit 90\n')
            p.chmod(0o755)
        env = dict(os.environ, DRY_RUN='1', RELEASE_ID='unit-dry', PATH=str(bindir) + ':' + os.environ['PATH'])
        for action in ('all', 'prepare', 'rollback', 'verify'):
            cp = subprocess.run(['bash', str(SCRIPT / 'deploy-release.sh'), action], env=env,
                                cwd=self.root, capture_output=True, text=True)
            self.assertEqual(cp.returncode, 0, cp.stderr)
            self.assertTrue(json.loads(cp.stdout)['dry_run'])
        self.assertFalse(marker.exists())
        self.assertFalse((SCRIPT.parent / 'target/releases/unit-dry').exists())

    def test_failed_mysqldump_cannot_create_accepted_backup(self):
        p = self.root / 'mysqldump'
        p.write_text('#!/bin/sh\necho "CREATE TABLE t (x INT);"\necho "-- Dump completed"\nexit 2\n')
        p.chmod(0o755)
        env = {'SPRING_DATASOURCE_URL': 'jdbc:mysql://localhost:3306/RAG知识库?x=y',
               'SPRING_DATASOURCE_USERNAME': 'fixture', 'SPRING_DATASOURCE_PASSWORD': 'test"#\\'}
        with patch.dict(os.environ, PATH=str(self.root) + ':' + os.environ['PATH']):
            with self.assertRaisesRegex(RuntimeError, 'mysqldump failed'):
                r.db_backup(env, self.root / 'db.sql.gz')
        self.assertFalse((self.root / 'db.sql.gz').exists())

    def test_archive_traversal_is_rejected(self):
        p = self.root / 'dist.zip'
        with zipfile.ZipFile(p, 'w') as z:
            z.writestr('dist/../../outside', 'bad')
        with self.assertRaisesRegex(RuntimeError, 'Unsafe'):
            r.unpack(p, self.root / 'unpack')
        self.assertFalse((self.root / 'outside').exists())

    def make_release(self):
        back = self.root / 'backend'
        front = self.root / 'frontend'
        back.mkdir()
        front.mkdir()
        (back / '.env').write_text('SERVER_PORT=8081\n')
        (back / r.JAR).write_bytes(b'old')
        (front / 'dist').mkdir()
        (front / 'dist/index.html').write_text('old index')
        cfg = {'release_id': 'fixture', 'backend': str(back), 'frontend': str(front), 'public_url': 'https://invalid.example'}
        release = r.Release(cfg)
        release.directory.mkdir(parents=True)
        (release.directory / r.JAR).write_bytes(b'new')
        (release.directory / 'dist.zip').write_bytes(b'zip')
        (release.directory / 'backup').mkdir()
        (release.directory / 'backup' / r.JAR).write_bytes(b'old')
        new = front / 'releases/fixture/dist'
        new.mkdir(parents=True)
        (new / 'index.html').write_text('new index')
        r.write_json(release.directory / 'manifest.json', {'jar_sha256': r.digest(release.directory / r.JAR), 'dist_sha256': r.digest(release.directory / 'dist.zip')})
        r.write_json(release.directory / 'before.json', {'old_jar_sha256': r.digest(back / r.JAR), 'env_sha256': r.digest(back / '.env'), 'old_index_sha256': r.digest(front / 'dist/index.html'), 'cmd': ['java', '-jar', r.JAR]})
        r.write_json(release.state_path, {'status': 'prepared'})
        return release

    def test_failed_cutover_invokes_rollback(self):
        release = self.make_release()
        with patch.object(release, 'probe'), patch.object(r, 'stop'), \
             patch.object(r, 'atomic_copy', side_effect=OSError('disk failure')), \
             patch.object(release, 'rollback') as rollback, contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaisesRegex(RuntimeError, 'rolled back'):
                release.apply()
            rollback.assert_called_once()

    def test_failed_api_check_restores_real_old_artifacts(self):
        release = self.make_release()
        checks = []
        def check(new=True):
            checks.append(new)
            if new:
                raise RuntimeError('API failure')
            self.assertEqual((release.backend / r.JAR).read_bytes(), b'old')
            self.assertEqual((release.frontend / 'dist/index.html').read_text(), 'old index')
            return {'pid': 123}
        with patch.object(release, 'probe'), patch.object(r, 'stop'), \
             patch.object(r, 'start', return_value=123), patch.object(r, 'wait_ready'), \
             patch.object(release, 'check', side_effect=check), contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaisesRegex(RuntimeError, 'rolled back'):
                release.apply()
        self.assertEqual(checks, [True, False])
        self.assertEqual(release.status()['status'], 'rolled_back')
        self.assertTrue(list(release.frontend.glob('dist.failed.*')))

    def test_changed_staging_never_stops_service(self):
        release = self.make_release()
        (release.directory / r.JAR).write_bytes(b'tampered')
        with patch.object(r, 'stop') as stop:
            with self.assertRaisesRegex(RuntimeError, 'hash changed'):
                release.apply()
            stop.assert_not_called()


if __name__ == '__main__':
    unittest.main()
