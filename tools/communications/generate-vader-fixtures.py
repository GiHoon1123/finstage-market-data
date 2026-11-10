"""Run with a temporary venv containing vaderSentiment==3.3.2; never needed at runtime."""
import ast
import json
import pathlib
import random
import typing
from vaderSentiment.vaderSentiment import SentimentIntensityAnalyzer

root = pathlib.Path(__file__).resolve().parents[2]
source = root.parent / 'Finstage-Market-Data/app/news_crawler/service/sentiment_analyzer.py'
parsed = ast.parse(source.read_text())
klass = next(node for node in parsed.body if isinstance(node, ast.ClassDef))
class QuietLogger:
    def __getattr__(self, name):
        return lambda *args, **kwargs: None
namespace = {'Dict': typing.Dict, 'Any': typing.Any, 'Optional': typing.Optional,
             'SentimentIntensityAnalyzer': SentimentIntensityAnalyzer,
             'logger': QuietLogger(), 're': __import__('re')}
exec(compile(ast.Module(body=[klass], type_ignores=[]), str(source), 'exec'), namespace)
reference = namespace['SentimentAnalyzer']()
texts = [
    '', '   ', 'The market is open today.', 'Earnings surge after a strong quarterly report.',
    'BREAKING: stock market crash and urgent profit warning!', 'Stocks rally but earnings disappoint.',
    'This is good', 'This is GOOD!!!', 'This is not good', 'This is not very good',
    'I never thought this would be so good', 'I am never so happy', 'This is without doubt good',
    'This is the least good result', 'This is at least good', 'This is very least good',
    'No good, no loss, no profit', 'No good or great results', 'The bomb', 'This is the bomb',
    'I think this is the bomb', 'yeah right', 'This is kind of good', 'This is sort of good',
    'profit profit but profit profit', 'bad bad but bad bad', 'good!!!???',
    'good :)', 'good :D', '🤗 stock rises', 'The company ❤️ earnings', 'Not bad at all',
    'The deficit widened; the stock plunged.', 'Revenue climbs but annual profit falls.',
    'Apple earnings beat expectations while revenue was weak.', 'The price is $100, down 3%.',
    '中国 stock GOOD', 'AAPL has a robust growth outlook.', 'The report was VERY BAD!!!',
]
random.seed(42)
for _ in range(80):
    texts.append(' '.join(random.choice(['profit', 'not', 'good', 'bad', 'but', 'very', 'never',
                                      'kind', 'of', 'so', 'the', 'market', 'earnings', 'rally',
                                      'down', 'loss', 'strong', 'crash', 'without', 'doubt'])
                          for _ in range(random.randint(3, 12))))
fixtures = [{'text': text, 'expected': reference.analyze_sentiment(text)} for text in texts]
destination = root / 'src/test/resources/communications/vader-fixtures.json'
destination.parent.mkdir(parents=True, exist_ok=True)
destination.write_text(json.dumps(fixtures, ensure_ascii=False, indent=2) + '\n')
print(f'{len(fixtures)} original Python VADER fixtures written')
