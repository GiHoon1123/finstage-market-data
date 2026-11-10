"""Explicit one-time export of an owned Keras/scikit-learn bundle; JVM runtime never uses Python.

Ordered feature names are required because original pickle scalers were fitted on unnamed arrays.
Only pass trusted scaler pickle files: pickle is executable Python serialization.
"""
import argparse
import json
import pickle
from pathlib import Path
import numpy as np
import tensorflow as tf

parser = argparse.ArgumentParser()
parser.add_argument('--model', type=Path, required=True)
parser.add_argument('--scalers', type=Path, required=True)
parser.add_argument('--features', type=Path, required=True, help='JSON array in exact training column order')
parser.add_argument('--symbol', required=True)
parser.add_argument('--version', required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
if args.output.exists():
    raise SystemExit('Refusing to overwrite an existing model artifact')
model = tf.keras.models.load_model(args.model, compile=False, safe_mode=True)
names = json.loads(args.features.read_text())
if not isinstance(names, list) or len(set(names)) != len(names) or len(names) != model.input_shape[-1]:
    raise SystemExit('Unique ordered feature names must match model input feature count')
lstms = [layer for layer in model.layers if isinstance(layer, tf.keras.layers.LSTM)]
common = [layer for layer in model.layers if layer.name.startswith('dense_common_')]
targets = [int(layer.name.removeprefix('price_prediction_').removesuffix('d')) for layer in model.layers if layer.name.startswith('price_prediction_')]
if len(targets) != 3:
    raise SystemExit('Original three independent forecast heads are required')
weights = {}
for layer in model.layers:
    values = layer.get_weights()
    if not values:
        continue
    if isinstance(layer, tf.keras.layers.LSTM):
        labels = ['kernel', 'recurrent_kernel', 'bias']
    elif isinstance(layer, tf.keras.layers.BatchNormalization):
        if not layer.center or not layer.scale or abs(layer.epsilon - 0.001) > 1e-12:
            raise SystemExit('Unsupported BatchNorm configuration')
        labels = ['gamma', 'beta', 'moving_mean', 'moving_variance']
    elif isinstance(layer, tf.keras.layers.Dense):
        labels = ['kernel', 'bias']
    else:
        raise SystemExit(f'Unsupported weighted layer {layer.name}: {type(layer).__name__}')
    if len(labels) != len(values):
        raise SystemExit(f'Unexpected weight layout: {layer.name}')
    for label, value in zip(labels, values):
        weights[f'{layer.name}_{label}'] = np.asarray(value, dtype=np.float32).reshape(-1).tolist()
def scaler(path):
    with path.open('rb') as handle:
        value = pickle.load(handle)
    kind = type(value).__name__
    if kind == 'MinMaxScaler':
        center = -value.min_ / value.scale_
        scale = 1.0 / value.scale_
        method = 'minmax'
    elif kind == 'StandardScaler':
        center = value.mean_ if value.with_mean else np.zeros(value.n_features_in_)
        scale = value.scale_ if value.with_std else np.ones(value.n_features_in_)
        method = 'standard'
    elif kind == 'RobustScaler':
        center = value.center_ if value.with_centering else np.zeros(value.n_features_in_)
        scale = value.scale_ if value.with_scaling else np.ones(value.n_features_in_)
        method = 'robust'
    else:
        raise SystemExit(f'Unsupported scaler {kind}')
    return {'method': method, 'center': np.asarray(center).tolist(), 'scale': np.asarray(scale).tolist()}
artifact = {'format': 'finstage-jvm-lstm-v1', 'symbol': args.symbol, 'version': args.version, 'architecture': {'inputFeatures': len(names), 'window': model.input_shape[1], 'lstmUnits': [layer.units for layer in lstms], 'denseUnits': [layer.units for layer in common], 'targets': targets, 'dropout': lstms[0].dropout, 'activation': common[0].activation.__name__ if common else 'relu', 'lossWeights': [0.5, 0.3, 0.2]}, 'featureNames': names, 'featureScaler': scaler(args.scalers / 'feature_scaler.pkl'), 'targetScalers': [scaler(args.scalers / f'target_scaler_{days}d.pkl') for days in targets], 'sentiment': 'avg_sentiment' in names, 'weights': weights, 'trainingLoss': []}
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(artifact, allow_nan=False))
print(f'Exported {len(weights)} tensors and {len(names)} ordered features to {args.output}')
