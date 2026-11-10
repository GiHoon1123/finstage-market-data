#!/usr/bin/env python3
"""Run the packaged JVM service against a disposable original-schema MySQL database."""
import datetime as dt
import json
import math
import pathlib
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid

ROOT = pathlib.Path(__file__).resolve().parents[1]
PASSWORD = 'migration-test-only'
SCHEMA = 'finstage_packaged_test'


def command(args, text=None, check=True):
    return subprocess.run(args, input=text, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=check)


def main():
    container = 'finstage-packaged-' + uuid.uuid4().hex[:10]
    process = None
    with tempfile.TemporaryDirectory(prefix='finstage-packaged-') as folder:
        log_path = pathlib.Path(folder) / 'service.log'
        try:
            command(['docker', 'run', '--detach', '--name', container, '--publish', '127.0.0.1::3306',
                     '--env', 'MYSQL_ROOT_PASSWORD=' + PASSWORD, '--env', 'MYSQL_DATABASE=' + SCHEMA, 'mysql:8.0'])
            mysql = ['docker', 'exec', '-i', container, 'mysql', '--protocol=TCP', '-h127.0.0.1',
                     '-uroot', '-p' + PASSWORD, SCHEMA]
            for _ in range(90):
                if command(mysql + ['-Nse', 'SELECT 1'], check=False).returncode == 0:
                    break
                time.sleep(1)
            else:
                raise RuntimeError('Disposable MySQL did not become ready')
            command(mysql, (ROOT / 'src/main/resources/db/reference-schema.mysql.sql').read_text())
            values = []
            today = dt.date.today()
            for index in range(160):
                price = 100 + index * .13 + math.sin(index * .2)
                day = today - dt.timedelta(days=159-index)
                values.append(f"('AAPL','{day}',{price-.3},{price+1},{price-1},{price},{10000+index*30},.13,.13)")
            command(mysql, 'INSERT INTO daily_prices(symbol,date,open_price,high_price,low_price,close_price,volume,price_change,price_change_percent) VALUES ' + ','.join(values) + ';')
            mysql_port = command(['docker', 'port', container, '3306/tcp']).stdout.strip().rsplit(':', 1)[1]
            with socket.socket() as reservation:
                reservation.bind(('127.0.0.1', 0))
                port = reservation.getsockname()[1]
            jars = [p for p in (ROOT / 'build/libs').glob('*.jar') if not p.name.endswith('-plain.jar')]
            if len(jars) != 1:
                raise RuntimeError('Build exactly one bootJar before running this script')
            with log_path.open('w') as log:
                process = subprocess.Popen(['java', '-jar', str(jars[0]), '--server.address=127.0.0.1', f'--server.port={port}',
                    f'--spring.datasource.url=jdbc:mysql://127.0.0.1:{mysql_port}/{SCHEMA}?allowPublicKeyRetrieval=true&useSSL=false',
                    '--spring.datasource.username=root', '--spring.datasource.password=' + PASSWORD,
                    '--scheduler.enabled=false', '--communications.telegram.enabled=false',
                    '--market.ml.model-directory=' + str(pathlib.Path(folder) / 'models')], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
            base = f'http://127.0.0.1:{port}'

            def request(path, body=None):
                data = None if body is None else json.dumps(body).encode()
                req = urllib.request.Request(base + path, data=data, headers={'Content-Type': 'application/json'})
                with urllib.request.urlopen(req, timeout=90) as response:
                    return json.load(response)

            for _ in range(90):
                if process.poll() is not None:
                    raise RuntimeError('Packaged service exited before readiness')
                try:
                    request('/api/technical-analysis/utils/signal-types')
                    break
                except (urllib.error.URLError, TimeoutError):
                    time.sleep(1)
            else:
                raise RuntimeError('Packaged service did not become ready')
            for path in ['/api/symbols/symbols', '/api/recovery/status', '/api/ml-prediction/status',
                         '/api/daily-report/status', '/monitoring/health/ready', '/api/tasks/status', '/openapi.json']:
                request(path)
            trained = request('/api/ml-prediction/train', {'symbol': 'AAPL', 'training_days': 180,
                'hyperparameters': {'window_size': 6, 'lstm_units': [3, 2], 'dense_units': [4],
                                    'epochs': 1, 'batch_size': 32, 'dropout_rate': 0.0}})
            if trained['data']['status'] != 'success':
                raise RuntimeError('Packaged native JVM training failed: ' + json.dumps(trained))
            predicted = request('/api/ml-prediction/predict', {'symbol': 'AAPL'})
            if len(predicted['data']['predictions']) != 3:
                raise RuntimeError('Packaged model did not produce three horizons')
            count = command(mysql + ['-Nse', "SELECT COUNT(*) FROM ml_predictions WHERE symbol='AAPL'"]).stdout.strip()
            if count != '3':
                raise RuntimeError('Packaged forecasts were not persisted')
            print('PASS: bootJar + original MySQL schema + HTTP APIs + native JVM training/prediction + three persisted forecasts; no external market or messaging calls')
        except BaseException:
            if log_path.exists():
                print('\n'.join(log_path.read_text().splitlines()[-35:]))
            raise
        finally:
            if process is not None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
            command(['docker', 'rm', '-f', container], check=False)


if __name__ == '__main__':
    main()
