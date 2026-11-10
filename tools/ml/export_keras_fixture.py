"""One-time synthetic Keras fixture exporter; never reads original model or key files."""
import json
import os
from pathlib import Path
os.environ['TF_CPP_MIN_LOG_LEVEL'] = '3'
os.environ['CUDA_VISIBLE_DEVICES'] = '-1'
import numpy as np
import tensorflow as tf
from tensorflow.keras import layers, Model

tf.config.threading.set_inter_op_parallelism_threads(1)
tf.config.threading.set_intra_op_parallelism_threads(1)
tf.keras.utils.set_random_seed(17)
inputs = layers.Input((4, 2))
x = inputs
for i, units in enumerate([3, 2]):
    x = layers.LSTM(units, return_sequences=i == 0, name=f'lstm_{i+1}')(x)
    x = layers.BatchNormalization(name=f'batch_norm_lstm_{i+1}')(x)
x = layers.Dense(4, activation='relu', name='dense_common_1')(x)
outputs = []
for days in [1, 2, 3]:
    branch = layers.Dense(16, activation='relu', name=f'dense_{days}d_branch')(x)
    outputs.append(layers.Dense(1, name=f'price_prediction_{days}d')(branch))
model = Model(inputs, outputs)
weights = {}
for layer in model.layers:
    values = layer.get_weights()
    if not values:
        continue
    if isinstance(layer, layers.LSTM):
        names = ['kernel', 'recurrent_kernel', 'bias']
    elif isinstance(layer, layers.BatchNormalization):
        names = ['gamma', 'beta', 'moving_mean', 'moving_variance']
        values[2][:] = np.linspace(-0.03, 0.04, len(values[2]))
        values[3][:] = np.linspace(0.5, 1.5, len(values[3]))
        layer.set_weights(values)
    else:
        names = ['kernel', 'bias']
    for name, value in zip(names, values):
        weights[f'{layer.name}_{name}'] = value.reshape(-1).tolist()
x = np.array([[[0.1, 0.2], [0.2, 0.3], [0.4, 0.2], [0.3, 0.7]]], dtype=np.float32)
y = [o.numpy().reshape(-1).tolist() for o in model(x, training=False)]
fixture = {'producer': 'TensorFlow 2.20.0 / Keras synthetic model; no original weights', 'architecture': {'inputFeatures': 2, 'window': 4, 'lstmUnits': [3, 2], 'denseUnits': [4], 'targets': [1, 2, 3], 'dropout': 0.0}, 'weights': weights, 'input': x.reshape(-1).tolist(), 'expected': y}
path = Path(__file__).parent / 'fixtures' / 'keras-lstm-vector.json'
path.parent.mkdir(parents=True, exist_ok=True)
path.write_text(json.dumps(fixture, indent=2))
print(f'Saved synthetic Keras inference fixture with {len(weights)} parameter tensors to {path}')
