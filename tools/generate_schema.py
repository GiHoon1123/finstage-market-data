#!/usr/bin/env python3
"""Compile isolated SQLAlchemy declarations without importing the original app."""
import ast
import datetime
from pathlib import Path
import sqlalchemy as sa
from sqlalchemy.dialects import mysql
from sqlalchemy.schema import CreateIndex, CreateTable

root = Path(__file__).resolve().parents[1]
source = root.parent / 'Finstage-Market-Data'
metadata = sa.MetaData()
namespace = {name:getattr(sa,name) for name in ['Column','Integer','BigInteger','String','Float','Boolean','Text','Date','DateTime','DECIMAL','Index','UniqueConstraint','ForeignKey','func']}
namespace['datetime'] = datetime.datetime
namespace['date'] = datetime.date

def declaration(node):
    # Only known declarative constructors and their literal arguments are evaluated.
    for child in ast.walk(node):
        if isinstance(child, ast.Call):
            name = ast.unparse(child.func)
            if name not in set(namespace) | {'func.now','datetime.utcnow','datetime.now'}:
                raise ValueError('Unsupported declaration call: '+name)
        if isinstance(child,(ast.Lambda,ast.ListComp,ast.DictComp,ast.GeneratorExp)):
            raise ValueError('Unsupported declaration expression')
    return eval(compile(ast.Expression(body=node),'<isolated-schema>','eval'),{'__builtins__':{}},namespace)

for path in sorted((source/'app').rglob('*.py')):
    tree=ast.parse(path.read_text())
    for cls in (n for n in tree.body if isinstance(n,ast.ClassDef)):
        fields={ast.unparse(n.targets[0]):n.value for n in cls.body if isinstance(n,ast.Assign) and len(n.targets)==1}
        if '__tablename__' not in fields:continue
        table=ast.literal_eval(fields['__tablename__'])
        columns=[]
        for name,value in fields.items():
            if isinstance(value,ast.Call) and ast.unparse(value.func)=='Column':
                column=declaration(value);column.name=name;columns.append(column)
        args=[]
        table_args=fields.get('__table_args__')
        if isinstance(table_args,ast.Tuple):
            args=[declaration(n) for n in table_args.elts if not isinstance(n,ast.Dict)]
        sa.Table(table,metadata,*columns,*args)

dialect=mysql.dialect()
statements=['-- Generated from isolated original SQLAlchemy 2.0.40 declarations.','-- Apply only to a new empty MySQL schema. Existing databases are not migrated by this file.']
for table in metadata.sorted_tables:
    statements.append(str(CreateTable(table).compile(dialect=dialect)).strip()+';')
    for index in sorted(table.indexes,key=lambda i:i.name):statements.append(str(CreateIndex(index).compile(dialect=dialect))+';')
output=root/'src/main/resources/db/reference-schema.mysql.sql';output.parent.mkdir(parents=True,exist_ok=True)
output.write_text('\n\n'.join(statements)+'\n')
print('generated',len(metadata.tables),'original MySQL tables')
