#!/usr/bin/env python3
"""Validate the frozen original schema in an isolated disposable MySQL server."""
import json
import subprocess
import time
import uuid
from pathlib import Path

root=Path(__file__).resolve().parents[1]
name='finstage-schema-'+uuid.uuid4().hex[:8]
def run(*args,**kw):return subprocess.check_output(args,text=True,stderr=subprocess.DEVNULL,**kw)
def sql(value):return run('docker','exec','-i',name,'mysql','-uroot','-pmigration-test-only','--batch','--skip-column-names','finstage_schema_test',input=value)
try:
    run('docker','run','--detach','--name',name,'--env','MYSQL_ROOT_PASSWORD=migration-test-only','--env','MYSQL_DATABASE=finstage_schema_test','mysql:8.0')
    for attempt in range(90):
        try:
            sql('SELECT 1;');break
        except subprocess.CalledProcessError:time.sleep(1)
    else:raise RuntimeError('MySQL readiness timeout')
    sql((root/'src/main/resources/db/reference-schema.mysql.sql').read_text())
    expected=json.loads((root/'docs/source-contracts.json').read_text())['tables']
    actual=sql("SELECT table_name,column_name FROM information_schema.columns WHERE table_schema='finstage_schema_test' ORDER BY table_name,ordinal_position;")
    columns={}
    for line in actual.splitlines():
        table,column=line.split('\t');columns.setdefault(table,[]).append(column)
    for table in expected:
        assert columns[table['table']]==[c['name'] for c in table['columns']],table['table']
    assert len(columns)==len(expected)
    print('Verified all',len(expected),'tables and',sum(len(v) for v in columns.values()),'columns in MySQL 8.0')
finally:
    subprocess.run(['docker','rm','-f',name],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
