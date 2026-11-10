#!/usr/bin/env python3
"""Execute only the isolated pure original class; never import its application."""
import ast
import contextlib
import io
import json
import math
from pathlib import Path
import numpy as np
import pandas as pd

root = Path(__file__).resolve().parents[1]
source = root.parent / 'finstage-market-data-fastapi/app/technical_analysis/service/technical_indicator_service.py'
tree = ast.parse(source.read_text())
cls = next(n for n in tree.body if isinstance(n, ast.ClassDef) and n.name == 'TechnicalIndicatorService')
for node in ast.walk(cls):
    if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
        node.decorator_list = []
namespace = {'pd':pd, 'np':np, 'Optional':__import__('typing').Optional, 'Dict':dict, 'Any':object, 'Tuple':tuple}
exec(compile(ast.fix_missing_locations(ast.Module(body=[cls],type_ignores=[])),str(source),'exec'),namespace)
service = namespace['TechnicalIndicatorService']()

def safe(value):
    if isinstance(value,dict):return {k:safe(v) for k,v in value.items()}
    return [float(v) if math.isfinite(v) else None for v in value]

results=[]
for name,prices in [('mixed',[100 + math.sin(i/3)*8+i/5 for i in range(65)]),('constant',[20.0]*65),('rising',[float(i+1) for i in range(65)]),('short',[5.0,4.0,7.0])]:
    volume=[0.0 if i<2 else 10.0+i for i in range(len(prices))]
    frame=pd.DataFrame({'high':[v+2 for v in prices],'low':[v-2 for v in prices],'close':prices,'volume':volume})
    with contextlib.redirect_stdout(io.StringIO()):
        expected={
            'sma':safe(service.calculate_sma(frame.close,5)),
            'ema':safe(service.calculate_ema(frame.close,5)),
            'rsi':safe(service.calculate_rsi(frame.close)),
            'bollinger':safe(service.calculate_bollinger_bands(frame.close)),
            'macd':safe(service.calculate_macd(frame.close)),
            'stochastic':safe(service.calculate_stochastic(frame)),
            'vwap':safe(service.calculate_vwap(frame)),
        }
    results.append({'name':name,'prices':prices,'volumes':volume,'expected':expected})
p=root/'src/test/resources/indicator-vectors.json';p.parent.mkdir(parents=True,exist_ok=True)
p.write_text(json.dumps(results,allow_nan=False,indent=2)+'\n')
print('generated',len(results),'original pandas scenarios')
