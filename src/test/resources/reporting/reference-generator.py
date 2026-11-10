"""Generate fixtures from active source definitions without importing its external integrations."""
import ast
import json
from datetime import datetime, date, timedelta
from pathlib import Path

source = Path('../finstage-market-data-fastapi/app/technical_analysis/service/daily_comprehensive_report_service.py')
tree = ast.parse(source.read_text())
original = next(node for node in tree.body if isinstance(node, ast.ClassDef))
selected = ['_get_backtesting_analysis', '_get_pattern_analysis', '_get_investment_insights', '_generate_report_message', '_generate_investment_insights', '_format_telegram_message', '_translate_pattern_name']
methods = {}
for node in original.body:
    if isinstance(node, ast.FunctionDef) and node.name in selected:
        node.decorator_list = []
        methods[node.name] = node
class FixedDateTime(datetime):
    @classmethod
    def now(cls, tz=None):
        return cls(2026, 11, 10, 0, 0, 0)
    @classmethod
    def utcnow(cls):
        return cls.now()
class Log:
    def __getattr__(self, _): return lambda *args, **kwargs: None
module = ast.Module(body=[ast.ClassDef(name='ReferenceReport', bases=[], keywords=[], body=list(methods.values()), decorator_list=[])], type_ignores=[])
namespace = {'Dict': dict, 'Any': object, 'datetime': FixedDateTime, 'date': date, 'timedelta': timedelta, 'logger': Log()}
exec(compile(ast.fix_missing_locations(module), '<active-source-report>', 'exec'), namespace)
service = namespace['ReferenceReport']()
service.target_symbols = ['^IXIC', '^GSPC']
service.symbol_names = {'^IXIC': '나스닥 지수', '^GSPC': 'S&P 500'}
backtesting = service._get_backtesting_analysis()
patterns = service._get_pattern_analysis()
ml = {'cluster_groups': 6, 'bullish_patterns': 0, 'bearish_patterns': 0, 'neutral_patterns': 0, 'status': 'no_data'}
insights = service._get_investment_insights()
preview = service._generate_report_message(backtesting, patterns, ml, insights)
scenarios = []
for mode in ['empty', 'bullish', 'bearish']:
    bullish = 2 if mode == 'bullish' else 0
    bearish = 2 if mode == 'bearish' else 0
    cluster = {'status': 'success', 'cluster_groups': 3, 'total_patterns': 12, 'bullish_patterns': bullish, 'bearish_patterns': bearish, 'neutral_patterns': 1,
        'strongest_bullish': {'name': 'BB_상승_패턴', 'pattern_count': 4, 'success_rate': 75.0} if bullish else None,
        'strongest_bearish': {'name': 'MACD_하락_패턴', 'pattern_count': 5, 'success_rate': 65.0} if bearish else None}
    all_data = {symbol: {'price': {'current_price': 25000.25, 'change_percent': 1.2 if bullish else -1.2 if bearish else 0.0}, 'ml_analysis': cluster,
        'signals': {'summary': {'total_signals': 5}}} for symbol in service.target_symbols}
    generated = service._generate_investment_insights(all_data)
    scenarios.append({'scenario': mode, 'data': all_data, 'insights': generated, 'message': service._format_telegram_message(all_data, generated)})
output = {'backtesting': backtesting, 'patterns': patterns, 'ml': ml, 'insights': insights, 'preview': preview, 'scenarios': scenarios}
Path('src/test/resources/reporting/source-report-reference.json').write_text(json.dumps(output, ensure_ascii=False))
for node in ast.walk(methods['_generate_report_message']):
    if isinstance(node, ast.AugAssign) and isinstance(node.value, ast.Constant) and isinstance(node.value.value, str) and '📚 용어 해설' in node.value.value:
        Path('src/main/resources/reporting/preview-appendix.txt').write_text(node.value.value)
for node in ast.walk(methods['_format_telegram_message']):
    if isinstance(node, ast.JoinedStr):
        for part in node.values:
            if isinstance(part, ast.Constant) and isinstance(part.value, str) and '🤖 AI 분석 방법 설명' in part.value:
                tail = part.value[part.value.index('\n\n🤖 AI 분석 방법 설명'):]
                Path('src/main/resources/reporting/daily-appendix.txt').write_text(tail)
