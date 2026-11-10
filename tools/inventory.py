#!/usr/bin/env python3
"""Read source syntax without importing or starting the original application."""
import ast
import json
import subprocess
from pathlib import Path

source = Path(__file__).resolve().parents[2] / 'finstage-market-data-fastapi'
root = Path(__file__).resolve().parents[1]
files = sorted((source / 'app').rglob('*.py'))
main = ast.parse((source / 'app/main.py').read_text())
mounts = {}
for statement in main.body:
    if isinstance(statement, ast.Expr) and isinstance(statement.value, ast.Call):
        call = statement.value
        if isinstance(call.func, ast.Attribute) and call.func.attr == 'include_router':
            if call.args and isinstance(call.args[0], ast.Name):
                mounts[call.args[0].id] = next((ast.literal_eval(k.value) for k in call.keywords if k.arg == 'prefix'), '')
imports = {}
for node in ast.walk(main):
    if isinstance(node, ast.ImportFrom) and node.module:
        for alias in node.names:
            imports[(node.module, alias.name)] = alias.asname or alias.name
routes = []
tables = []
for file in files:
    tree = ast.parse(file.read_text())
    module = str(file.relative_to(source)).removesuffix('.py').replace('/', '.')
    prefixes = {}
    for node in tree.body:
        if isinstance(node, ast.Assign) and isinstance(node.value, ast.Call) and ast.unparse(node.value.func) == 'APIRouter':
            prefix = next((ast.literal_eval(k.value) for k in node.value.keywords if k.arg == 'prefix'), '')
            for name in node.targets:
                if isinstance(name, ast.Name): prefixes[name.id] = prefix
    for node in ast.walk(tree):
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            for d in node.decorator_list:
                if isinstance(d, ast.Call) and isinstance(d.func, ast.Attribute) and d.func.attr in ['get','post','put','patch','delete','websocket'] and d.args and isinstance(d.args[0],ast.Constant):
                    router = ast.unparse(d.func.value)
                    alias = imports.get((module, router), router if file.name == 'main.py' else None)
                    mounted = alias in mounts or (file.name == 'main.py' and router == 'app')
                    path = mounts.get(alias, '') + prefixes.get(router, '') + d.args[0].value
                    routes.append({'method':d.func.attr.upper(),'path':path,'mounted':mounted,'function':node.name,'source':str(file.relative_to(source)),'line':node.lineno})
        if isinstance(node, ast.ClassDef):
            table=None;columns=[]
            for field in node.body:
                if isinstance(field,ast.Assign):
                    if any(isinstance(t,ast.Name) and t.id=='__tablename__' for t in field.targets):table=ast.literal_eval(field.value)
                    if isinstance(field.value,ast.Call) and ast.unparse(field.value.func)=='Column':
                        columns.append({'name':ast.unparse(field.targets[0]),'definition':ast.unparse(field.value)})
            if table:tables.append({'table':table,'source':str(file.relative_to(source)),'columns':columns})
commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=source,text=True).strip()
result={'source_commit':commit,'python_files':len(files),'lines':sum(len(p.read_text().splitlines()) for p in files),'routes':routes,'tables':tables}
(root/'docs/source-contracts.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
(root/'docs/source-inventory.md').write_text('# 원본 계약 목록\n\n원본 커밋: `'+commit+'`\n\nPython '+str(len(files))+'개 파일. 원본을 import하거나 실행하지 않고 AST로 읽었다.\n\n## 등록된 HTTP·WebSocket 경로\n\n| 방식 | 경로 | 원본 |\n|---|---|---|\n'+'\n'.join('| '+r['method']+' | `'+r['path']+'` | `'+r['source']+':'+str(r['line'])+'` |' for r in routes if r['mounted'])+'\n\n## DB 테이블\n\n'+'\n'.join('- `'+t['table']+'`: '+', '.join(c['name'] for c in t['columns']) for t in tables)+'\n\n세부 컬럼 선언과 미등록 라우터는 [source-contracts.json](source-contracts.json)을 참고한다. 경로의 존재는 Kotlin 구현 완료를 의미하지 않는다.\n')
print(len(files),'files',len(routes),'routes',sum(r['mounted'] for r in routes),'mounted',len(tables),'tables')
