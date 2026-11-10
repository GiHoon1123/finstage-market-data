# 원본 계약 목록

원본 커밋: `afb74022953de00a5fe74f38c6223f236c79b0f3`

Python 199개 파일. 원본을 import하거나 실행하지 않고 AST로 읽었다.

## 등록된 HTTP·WebSocket 경로

| 방식 | 경로 | 원본 |
|---|---|---|
| GET | `/api/database/query-stats` | `app/common/infra/database/routes.py:31` |
| GET | `/api/database/slow-queries` | `app/common/infra/database/routes.py:57` |
| GET | `/api/database/connection-pool` | `app/common/infra/database/routes.py:101` |
| POST | `/api/database/connection-pool/optimize` | `app/common/infra/database/routes.py:125` |
| GET | `/api/database/connection-pool/metrics` | `app/common/infra/database/routes.py:157` |
| GET | `/api/database/indexes/analysis` | `app/common/infra/database/routes.py:188` |
| GET | `/api/database/indexes/optimization-sql` | `app/common/infra/database/routes.py:208` |
| GET | `/api/database/performance-summary` | `app/common/infra/database/routes.py:237` |
| POST | `/api/database/maintenance/reset-metrics` | `app/common/infra/database/routes.py:308` |
| GET | `/api/database/slow-queries/statistics` | `app/common/infra/database/routes.py:374` |
| GET | `/api/database/slow-queries/patterns` | `app/common/infra/database/routes.py:398` |
| POST | `/api/database/slow-queries/cleanup` | `app/common/infra/database/routes.py:422` |
| POST | `/api/database/slow-queries/flush` | `app/common/infra/database/routes.py:444` |
| GET | `/monitoring/health` | `app/common/monitoring/routes.py:33` |
| GET | `/monitoring/health/ready` | `app/common/monitoring/routes.py:68` |
| GET | `/monitoring/health/live` | `app/common/monitoring/routes.py:98` |
| GET | `/monitoring/metrics` | `app/common/monitoring/routes.py:124` |
| GET | `/monitoring/status` | `app/common/monitoring/routes.py:150` |
| GET | `/monitoring/info` | `app/common/monitoring/routes.py:191` |
| GET | `/api/memory/status` | `app/common/utils/memory_api_router.py:25` |
| GET | `/api/memory/health` | `app/common/utils/memory_api_router.py:39` |
| POST | `/api/memory/optimize` | `app/common/utils/memory_api_router.py:55` |
| GET | `/api/memory/diagnostics` | `app/common/utils/memory_api_router.py:74` |
| GET | `/api/memory/cache/stats` | `app/common/utils/memory_api_router.py:90` |
| POST | `/api/memory/cache/cleanup` | `app/common/utils/memory_api_router.py:104` |
| POST | `/api/memory/cache/clear` | `app/common/utils/memory_api_router.py:126` |
| GET | `/api/memory/performance/history` | `app/common/utils/memory_api_router.py:168` |
| GET | `/api/memory/optimization/history` | `app/common/utils/memory_api_router.py:209` |
| GET | `/api/memory/alerts` | `app/common/utils/memory_api_router.py:245` |
| GET | `/api/memory/monitoring/status` | `app/common/utils/memory_api_router.py:278` |
| GET | `/api/tasks/status` | `app/common/web/task_queue_router.py:57` |
| POST | `/api/tasks/submit` | `app/common/web/task_queue_router.py:98` |
| GET | `/api/tasks/task/{task_id}` | `app/common/web/task_queue_router.py:175` |
| GET | `/api/tasks/task/{task_id}/result` | `app/common/web/task_queue_router.py:195` |
| POST | `/api/tasks/task/{task_id}/cancel` | `app/common/web/task_queue_router.py:233` |
| GET | `/api/tasks/workers` | `app/common/web/task_queue_router.py:257` |
| GET | `/api/tasks/queue/stats` | `app/common/web/task_queue_router.py:275` |
| POST | `/api/tasks/system/start` | `app/common/web/task_queue_router.py:292` |
| POST | `/api/tasks/system/stop` | `app/common/web/task_queue_router.py:311` |
| POST | `/api/tasks/maintenance/cleanup` | `app/common/web/task_queue_router.py:330` |
| POST | `/api/tasks/quick/dataset-processing` | `app/common/web/task_queue_router.py:353` |
| POST | `/api/tasks/quick/daily-report` | `app/common/web/task_queue_router.py:387` |
| POST | `/api/tasks/quick/market-analysis` | `app/common/web/task_queue_router.py:414` |
| POST | `/api/tasks/quick/data-cleanup` | `app/common/web/task_queue_router.py:437` |
| GET | `/api/tasks/dashboard` | `app/common/web/task_queue_router.py:460` |
| WEBSOCKET | `/ws/realtime` | `app/common/web/websocket_router.py:27` |
| GET | `/ws/status` | `app/common/web/websocket_router.py:293` |
| GET | `/ws/clients` | `app/common/web/websocket_router.py:312` |
| POST | `/ws/broadcast` | `app/common/web/websocket_router.py:323` |
| POST | `/ws/streaming/start` | `app/common/web/websocket_router.py:371` |
| POST | `/ws/streaming/stop` | `app/common/web/websocket_router.py:397` |
| GET | `/ws/demo` | `app/common/web/websocket_router.py:412` |
| GET | `/api/financials/financials/{symbol}` | `app/company/web/route/financial_router.py:90` |
| GET | `/api/symbols/symbols` | `app/company/web/route/symbol_router.py:79` |
| POST | `/api/messages/` | `app/message_notification/web/route/message_router.py:52` |
| POST | `/api/ml-prediction/train` | `app/ml_prediction/web/route/ml_prediction_router.py:153` |
| POST | `/api/ml-prediction/predict` | `app/ml_prediction/web/route/ml_prediction_router.py:274` |
| GET | `/api/ml-prediction/status` | `app/ml_prediction/web/route/ml_prediction_router.py:334` |
| GET | `/api/ml-prediction/health` | `app/ml_prediction/web/route/ml_prediction_router.py:377` |
| GET | `/api/ml-prediction/info` | `app/ml_prediction/web/route/ml_prediction_router.py:446` |
| POST | `/test/news/{symbol}` | `app/news_crawler/web/route/news_test_router.py:49` |
| GET | `/api/technical-analysis/advanced/similarity-matrix/{symbol}` | `app/technical_analysis/web/route/advanced_pattern_router.py:76` |
| GET | `/api/technical-analysis/advanced/clustering/{symbol}` | `app/technical_analysis/web/route/advanced_pattern_router.py:106` |
| GET | `/api/technical-analysis/advanced/temporal-analysis/{symbol}` | `app/technical_analysis/web/route/advanced_pattern_router.py:138` |
| GET | `/api/technical-analysis/advanced/health` | `app/technical_analysis/web/route/advanced_pattern_router.py:172` |
| GET | `/api/v2/async/symbols/{symbol}/price` | `app/technical_analysis/web/route/async_api_router.py:53` |
| GET | `/api/v2/async/symbols/batch` | `app/technical_analysis/web/route/async_api_router.py:110` |
| GET | `/api/v2/async/technical/analysis/{symbol}` | `app/technical_analysis/web/route/async_api_router.py:149` |
| GET | `/api/v2/technical-analysis/indicators/{symbol}` | `app/technical_analysis/web/route/async_technical_router.py:40` |
| POST | `/api/v2/technical-analysis/batch/indicators` | `app/technical_analysis/web/route/async_technical_router.py:176` |
| GET | `/api/v2/technical-analysis/prices/monitor` | `app/technical_analysis/web/route/async_technical_router.py:260` |
| GET | `/api/v2/technical-analysis/prices/batch` | `app/technical_analysis/web/route/async_technical_router.py:332` |
| POST | `/api/v2/technical-analysis/analysis/comprehensive` | `app/technical_analysis/web/route/async_technical_router.py:415` |
| POST | `/api/v2/technical-analysis/analysis/background` | `app/technical_analysis/web/route/async_technical_router.py:542` |
| GET | `/api/v2/technical-analysis/analysis/status/{task_id}` | `app/technical_analysis/web/route/async_technical_router.py:597` |
| GET | `/api/v2/technical-analysis/cache/stats` | `app/technical_analysis/web/route/async_technical_router.py:630` |
| POST | `/api/v2/technical-analysis/streaming/start` | `app/technical_analysis/web/route/async_technical_router.py:657` |
| POST | `/api/v2/technical-analysis/alerts/subscribe` | `app/technical_analysis/web/route/async_technical_router.py:832` |
| POST | `/api/daily-report/generate` | `app/technical_analysis/web/route/daily_report_router.py:46` |
| GET | `/api/daily-report/preview` | `app/technical_analysis/web/route/daily_report_router.py:84` |
| GET | `/api/daily-report/status` | `app/technical_analysis/web/route/daily_report_router.py:138` |
| GET | `/api/daily-report/health` | `app/technical_analysis/web/route/daily_report_router.py:191` |
| GET | `/api/technical-analysis/outcomes/tracking/summary` | `app/technical_analysis/web/route/outcome_analysis_router.py:73` |
| GET | `/api/technical-analysis/outcomes/tracking/pending` | `app/technical_analysis/web/route/outcome_analysis_router.py:100` |
| GET | `/api/technical-analysis/outcomes/performance/by-signal-type` | `app/technical_analysis/web/route/outcome_analysis_router.py:145` |
| GET | `/api/technical-analysis/outcomes/performance/top-performers` | `app/technical_analysis/web/route/outcome_analysis_router.py:184` |
| POST | `/api/technical-analysis/outcomes/backtesting/simple` | `app/technical_analysis/web/route/outcome_analysis_router.py:218` |
| GET | `/api/technical-analysis/outcomes/monitoring/dashboard` | `app/technical_analysis/web/route/outcome_analysis_router.py:266` |
| POST | `/api/technical-analysis/outcomes/tracking/update` | `app/technical_analysis/web/route/outcome_analysis_router.py:292` |
| POST | `/api/technical-analysis/patterns/discover/{symbol}` | `app/technical_analysis/web/route/pattern_analysis_router.py:55` |
| GET | `/api/technical-analysis/patterns/analysis` | `app/technical_analysis/web/route/pattern_analysis_router.py:99` |
| GET | `/api/technical-analysis/patterns/successful` | `app/technical_analysis/web/route/pattern_analysis_router.py:143` |
| POST | `/api/technical-analysis/patterns/test/{symbol}` | `app/technical_analysis/web/route/pattern_analysis_router.py:191` |
| DELETE | `/api/technical-analysis/patterns/test/{symbol}` | `app/technical_analysis/web/route/pattern_analysis_router.py:226` |
| POST | `/api/recovery/historical-data` | `app/technical_analysis/web/route/recovery_router.py:44` |
| POST | `/api/recovery/technical-analysis` | `app/technical_analysis/web/route/recovery_router.py:115` |
| POST | `/api/recovery/full-recovery` | `app/technical_analysis/web/route/recovery_router.py:195` |
| GET | `/api/recovery/status` | `app/technical_analysis/web/route/recovery_router.py:250` |
| GET | `/api/recovery/data-summary` | `app/technical_analysis/web/route/recovery_router.py:271` |
| DELETE | `/api/recovery/cleanup` | `app/technical_analysis/web/route/recovery_router.py:297` |
| GET | `/api/recovery/health` | `app/technical_analysis/web/route/recovery_router.py:331` |
| GET | `/api/technical-analysis/signals/signals` | `app/technical_analysis/web/route/signal_analysis_router.py:95` |
| GET | `/api/technical-analysis/signals/signals/{symbol}` | `app/technical_analysis/web/route/signal_analysis_router.py:177` |
| GET | `/api/technical-analysis/signals/stats/signal-count` | `app/technical_analysis/web/route/signal_analysis_router.py:259` |
| GET | `/api/technical-analysis/signals/stats/daily-count/{symbol}` | `app/technical_analysis/web/route/signal_analysis_router.py:338` |
| GET | `/api/technical-analysis/signals/stats/signal-strength/{signal_type}` | `app/technical_analysis/web/route/signal_analysis_router.py:389` |
| GET | `/api/technical-analysis/signals/current-status/{symbol}/{timeframe}` | `app/technical_analysis/web/route/signal_analysis_router.py:441` |
| GET | `/api/technical-analysis/signals/performance/all` | `app/technical_analysis/web/route/signal_analysis_router.py:495` |
| GET | `/api/technical-analysis/signals/performance/{signal_type}` | `app/technical_analysis/web/route/signal_analysis_router.py:533` |
| POST | `/api/technical-analysis/signals/backtest/strategy` | `app/technical_analysis/web/route/signal_analysis_router.py:578` |
| GET | `/api/technical-analysis/signals/quality/{signal_type}` | `app/technical_analysis/web/route/signal_analysis_router.py:639` |
| POST | `/api/technical-analysis/signals/outcomes/track/{signal_id}` | `app/technical_analysis/web/route/signal_analysis_router.py:681` |
| PUT | `/api/technical-analysis/signals/outcomes/update` | `app/technical_analysis/web/route/signal_analysis_router.py:718` |
| GET | `/api/technical-analysis/signals/outcomes/{signal_id}` | `app/technical_analysis/web/route/signal_analysis_router.py:748` |
| POST | `/api/technical-analysis/signals/outcomes/test/{signal_id}` | `app/technical_analysis/web/route/signal_analysis_router.py:781` |
| POST | `/api/test/telegram-alerts/all` | `app/technical_analysis/web/route/test_router.py:62` |
| POST | `/api/test/telegram-alerts/ma-breakout` | `app/technical_analysis/web/route/test_router.py:91` |
| POST | `/api/test/telegram-alerts/rsi` | `app/technical_analysis/web/route/test_router.py:151` |
| POST | `/api/test/telegram-alerts/cross` | `app/technical_analysis/web/route/test_router.py:199` |
| POST | `/api/test/telegram-alerts/bollinger` | `app/technical_analysis/web/route/test_router.py:241` |
| GET | `/api/test/env-check` | `app/technical_analysis/web/route/test_router.py:295` |
| GET | `/api/technical-analysis/utils/health` | `app/technical_analysis/web/route/utility_router.py:56` |
| GET | `/api/technical-analysis/utils/signal-types` | `app/technical_analysis/web/route/utility_router.py:102` |

## DB 테이블

- `slow_query_logs`: id, query_hash, query_template, original_query, duration, affected_rows, table_names, operation_type, created_at, execution_timestamp
- `balance_sheets`: id, symbol, date, total_assets, total_liabilities, total_equity, cash_and_equivalents, inventory
- `cash_flow_statements`: id, symbol, date, operating_cash_flow, investing_cash_flow, financing_cash_flow, free_cash_flow
- `income_statements`: id, symbol, date, revenue, cost_of_revenue, gross_profit, operating_income, net_income, eps
- `symbols`: id, symbol, name, country, korea_name
- `price_alert_logs`: id, symbol, alert_type, base_type, base_price, current_price, threshold_percent, actual_percent, base_time, triggered_at
- `price_high_records`: id, symbol, source, price, recorded_at, created_at
- `price_snapshots`: id, symbol, source, close, high, low, volume, snapshot_at, created_at
- `message_logs`: id, message, sent_at
- `ml_models`: id, model_name, model_version, model_type, symbol, model_path, model_format, model_size_mb, checksum, training_start_date, training_end_date, training_duration_minutes, epochs_trained, early_stopped, training_data_start_date, training_data_end_date, training_samples, validation_samples, test_samples, feature_count, window_size, performance_7d_mse, performance_7d_mae, performance_7d_direction_accuracy, performance_14d_mse, performance_14d_mae, performance_14d_direction_accuracy, performance_30d_mse, performance_30d_mae, performance_30d_direction_accuracy, overall_performance_score, hyperparameters, model_architecture, training_config, status, is_active, deployed_at, deprecated_at, description, created_by, tags, created_at, updated_at
- `ml_predictions`: id, symbol, prediction_date, prediction_timeframe, target_date, batch_id, current_price, predicted_price, predicted_direction, price_change_percent, confidence_score, actual_price, actual_direction, actual_change_percent, is_direction_correct, price_error_percent, model_version, model_type, features_used, feature_count, created_at, updated_at, result_updated_at
- `contents`: id, symbol, title, summary, url, html, source, crawled_at, published_at, content_hash
- `content_sentiments`: id, content_id, sentiment_score, sentiment_label, confidence, positive_score, negative_score, neutral_score, compound_score, market_impact_score, is_market_sensitive, analyzer_type, analyzed_at
- `content_translations`: id, content_id, language, title_translated, summary_translated, translator, translated_at, published_at
- `daily_prices`: id, symbol, date, open_price, high_price, low_price, close_price, volume, price_change, price_change_percent, created_at, updated_at
- `pattern_clusters`: id, symbol, cluster_id, cluster_name, timeframe, pattern_count, avg_success_rate, avg_confidence_score, avg_duration_hours, bullish_tendency, bearish_tendency, dominant_signal_types, cluster_center, cluster_radius, representative_patterns, pattern_examples, clustering_algorithm, n_clusters_total, clustering_quality_score, clustered_at, created_at, updated_at
- `signal_outcomes`: id, signal_id, price_1h_after, price_4h_after, price_1d_after, price_1w_after, price_1m_after, return_1h, return_4h, return_1d, return_1w, return_1m, max_price_reached, min_price_reached, max_return, max_drawdown, min_return, is_successful_1h, is_successful_1d, is_successful_1w, is_successful_1m, volatility_1d, volatility_1w, trend_strength, last_updated_at, is_complete, created_at, updated_at
- `signal_patterns`: id, pattern_name, pattern_type, symbol, timeframe, first_signal_id, second_signal_id, third_signal_id, fourth_signal_id, fifth_signal_id, pattern_start, pattern_end, pattern_duration_hours, pattern_outcome_1h, pattern_outcome_1d, pattern_outcome_1w, max_favorable_move, max_adverse_move, is_successful_1h, is_successful_1d, is_successful_1w, confidence_score, market_condition, volatility_level, volume_profile, pattern_description, similar_patterns_count, created_at, updated_at, analyzed_at
- `technical_signals`: id, symbol, signal_type, timeframe, triggered_at, current_price, indicator_value, signal_strength, volume, market_condition, additional_context, alert_sent, alert_sent_at, created_at, updated_at

세부 컬럼 선언과 미등록 라우터는 [source-contracts.json](source-contracts.json)을 참고한다. 경로의 존재는 Kotlin 구현 완료를 의미하지 않는다.
