"""Run only the original pure feature methods against newly generated market rows."""
import ast
import json
from pathlib import Path
from types import SimpleNamespace
import numpy as np
import pandas as pd
from sklearn.preprocessing import MinMaxScaler, StandardScaler, RobustScaler
source = Path(__file__).resolve().parents[3] / 'finstage-market-data-fastapi/app/ml_prediction/ml/data/feature_engineer.py'
tree = ast.parse(source.read_text())
original = next(n for n in tree.body if isinstance(n, ast.ClassDef) and n.name == 'FeatureEngineer')
selected = {'_prepare_features', '_add_price_features', '_add_volume_features', '_add_technical_features', '_add_time_features', '_add_lag_features', '_add_rolling_features', '_add_sentiment_features', '_calculate_rsi', '_handle_missing_values', '_create_sequences_and_targets'}
original.body = [n for n in original.body if isinstance(n, ast.FunctionDef) and n.name in selected]
module = ast.fix_missing_locations(ast.Module(body=[original], type_ignores=[]))
class Logger:
    def __getattr__(self, _):
        return lambda *a, **k: None
namespace = {'pd': pd, 'np': np, 'Optional': __import__('typing').Optional, 'List': list, 'Dict': dict, 'Tuple': __import__('typing').Tuple, 'logger': Logger(), 'ml_settings': SimpleNamespace(data=SimpleNamespace(use_time_features=True))}
exec(compile(module, str(source), 'exec'), namespace)
engineer = object.__new__(namespace['FeatureEngineer'])
engineer.use_sentiment_features = False
engineer.original_feature_count = 7
engineer.window_size = 6
engineer.target_days = [1, 2, 3]
dates = pd.date_range('2024-01-01', periods=80)
t = np.arange(80, dtype=float)
close = 100 + t * 0.17 + np.sin(t / 3)
frame = pd.DataFrame({'open': close - 0.4, 'high': close + 1.2, 'low': close - 1.1, 'close': close, 'volume': 1000 + t * 7, 'price_change': np.r_[0, np.diff(close)], 'price_change_percent': np.r_[0, np.diff(close) / close[:-1] * 100]}, index=dates)
features = engineer._prepare_features(frame.copy())
x, labels = engineer._create_sequences_and_targets(features, 'close')
scalers = {}
small = np.array([[1., 10.], [3., 20.], [7., 50.], [11., 70.]])
for name, scaler in [('minmax', MinMaxScaler()), ('standard', StandardScaler()), ('robust', RobustScaler())]:
    scalers[name] = scaler.fit_transform(small).tolist()
fixture = {'producer': 'Original AST-selected pure pandas FeatureEngineer methods on synthetic data', 'dates': [d.date().isoformat() for d in dates], 'observations': frame.to_dict(orient='records'), 'featureNames': features.columns.tolist(), 'rows': features.values.tolist(), 'sequenceCount': len(x), 'firstSequence': x[0].reshape(-1).tolist(), 'firstLabels': [float(labels[f'{d}d'][0]) for d in [1, 2, 3]], 'scalerInput': small.tolist(), 'scalers': scalers}
path = Path(__file__).parent / 'fixtures/feature-vector.json'
path.write_text(json.dumps(fixture, indent=2, allow_nan=False))
print(f'Saved {len(features)} rows, {len(features.columns)} original feature columns, {len(x)} original target sequences')
