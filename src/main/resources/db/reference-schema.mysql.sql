-- Generated from isolated original SQLAlchemy 2.0.40 declarations.

-- Apply only to a new empty MySQL schema. Existing databases are not migrated by this file.

CREATE TABLE balance_sheets (
	id INTEGER NOT NULL AUTO_INCREMENT, 
	symbol VARCHAR(16), 
	date DATE, 
	total_assets FLOAT, 
	total_liabilities FLOAT, 
	total_equity FLOAT, 
	cash_and_equivalents FLOAT, 
	inventory FLOAT, 
	PRIMARY KEY (id), 
	CONSTRAINT uq_balance_symbol_date UNIQUE (symbol, date)
);

CREATE INDEX ix_balance_sheets_id ON balance_sheets (id);

CREATE INDEX ix_balance_sheets_symbol ON balance_sheets (symbol);

CREATE TABLE cash_flow_statements (
	id INTEGER NOT NULL AUTO_INCREMENT, 
	symbol VARCHAR(16), 
	date DATE, 
	operating_cash_flow FLOAT, 
	investing_cash_flow FLOAT, 
	financing_cash_flow FLOAT, 
	free_cash_flow FLOAT, 
	PRIMARY KEY (id), 
	CONSTRAINT uq_cashflow_symbol_date UNIQUE (symbol, date)
);

CREATE INDEX ix_cash_flow_statements_id ON cash_flow_statements (id);

CREATE INDEX ix_cash_flow_statements_symbol ON cash_flow_statements (symbol);

CREATE TABLE contents (
	id BIGINT NOT NULL COMMENT 'PK' AUTO_INCREMENT, 
	symbol VARCHAR(20) NOT NULL COMMENT '종목 티커 (예: TSLA)', 
	title TEXT NOT NULL COMMENT '기사 제목', 
	summary TEXT COMMENT '기사 요약 또는 본문 일부', 
	url TEXT NOT NULL COMMENT '기사 원본 URL', 
	html TEXT NOT NULL COMMENT '기사 원본 URL', 
	source VARCHAR(255) COMMENT '출처 도메인 (예: reuters.com)', 
	crawled_at DATETIME NOT NULL COMMENT '크롤링 시각', 
	published_at DATETIME NOT NULL COMMENT '기사 발행 시각', 
	content_hash VARCHAR(64) NOT NULL COMMENT '중복 방지를 위한 콘텐츠 해시', 
	PRIMARY KEY (id), 
	UNIQUE (content_hash)
);

CREATE TABLE daily_prices (
	id BIGINT NOT NULL COMMENT '일봉 데이터 고유 ID' AUTO_INCREMENT, 
	symbol VARCHAR(10) NOT NULL COMMENT '심볼 (^IXIC: 나스닥, ^GSPC: S&P 500)', 
	date DATE NOT NULL COMMENT '거래일 (YYYY-MM-DD)', 
	open_price DECIMAL(12, 4) NOT NULL COMMENT '시가 (장 시작 가격)', 
	high_price DECIMAL(12, 4) NOT NULL COMMENT '고가 (하루 중 최고 가격)', 
	low_price DECIMAL(12, 4) NOT NULL COMMENT '저가 (하루 중 최저 가격)', 
	close_price DECIMAL(12, 4) NOT NULL COMMENT '종가 (장 마감 가격, 가장 중요!)', 
	volume BIGINT COMMENT '거래량 (하루 총 거래된 주식 수)', 
	price_change DECIMAL(12, 4) COMMENT '
        가격 변화량 (종가 - 전일 종가)
        양수: 상승, 음수: 하락, 0: 보합
        ', 
	price_change_percent DECIMAL(8, 4) COMMENT '
        가격 변화율 (%%)
        계산식: (종가 - 전일종가) / 전일종가 * 100
        ', 
	created_at DATETIME COMMENT '레코드 생성 시점', 
	updated_at DATETIME COMMENT '레코드 수정 시점', 
	PRIMARY KEY (id), 
	CONSTRAINT uq_symbol_date UNIQUE (symbol, date)
);

CREATE INDEX idx_date ON daily_prices (date);

CREATE INDEX idx_symbol ON daily_prices (symbol);

CREATE INDEX idx_symbol_date_desc ON daily_prices (symbol, date);

CREATE INDEX idx_symbol_date_range ON daily_prices (symbol, date);

CREATE TABLE income_statements (
	id INTEGER NOT NULL AUTO_INCREMENT, 
	symbol VARCHAR(16), 
	date DATE, 
	revenue FLOAT, 
	cost_of_revenue FLOAT, 
	gross_profit FLOAT, 
	operating_income FLOAT, 
	net_income FLOAT, 
	eps FLOAT, 
	PRIMARY KEY (id), 
	CONSTRAINT uq_income_symbol_date UNIQUE (symbol, date)
);

CREATE INDEX ix_income_statements_id ON income_statements (id);

CREATE INDEX ix_income_statements_symbol ON income_statements (symbol);

CREATE TABLE message_logs (
	id BIGINT NOT NULL COMMENT 'PK' AUTO_INCREMENT, 
	message VARCHAR(1000) NOT NULL COMMENT '전송된 메시지 내용', 
	sent_at DATETIME NOT NULL COMMENT '메시지 전송 시각', 
	PRIMARY KEY (id)
);

CREATE TABLE ml_models (
	id BIGINT NOT NULL COMMENT 'ML 모델 고유 ID' AUTO_INCREMENT, 
	model_name VARCHAR(100) NOT NULL COMMENT '모델 이름 (예: nasdaq_lstm_v1)', 
	model_version VARCHAR(50) NOT NULL COMMENT '모델 버전 (예: v1.0.0)', 
	model_type VARCHAR(20) NOT NULL COMMENT '모델 타입 (lstm, gru, transformer)', 
	symbol VARCHAR(10) NOT NULL COMMENT '대상 심볼 (^IXIC, ^GSPC)', 
	model_path VARCHAR(500) NOT NULL COMMENT '모델 파일 저장 경로', 
	model_format VARCHAR(20) NOT NULL COMMENT '모델 파일 형식 (keras, tensorflow, onnx)', 
	model_size_mb DECIMAL(10, 2) COMMENT '모델 파일 크기 (MB)', 
	checksum VARCHAR(64) COMMENT '모델 파일 체크섬 (무결성 검증용)', 
	training_start_date DATETIME NOT NULL COMMENT '훈련 시작 시점', 
	training_end_date DATETIME NOT NULL COMMENT '훈련 완료 시점', 
	training_duration_minutes INTEGER COMMENT '훈련 소요 시간 (분)', 
	epochs_trained INTEGER NOT NULL COMMENT '실제 훈련된 에포크 수', 
	early_stopped BOOL NOT NULL COMMENT '조기 종료 여부', 
	training_data_start_date DATETIME NOT NULL COMMENT '훈련 데이터 시작 날짜', 
	training_data_end_date DATETIME NOT NULL COMMENT '훈련 데이터 종료 날짜', 
	training_samples BIGINT NOT NULL COMMENT '훈련 샘플 수', 
	validation_samples BIGINT NOT NULL COMMENT '검증 샘플 수', 
	test_samples BIGINT NOT NULL COMMENT '테스트 샘플 수', 
	feature_count INTEGER NOT NULL COMMENT '사용된 특성 개수', 
	window_size INTEGER NOT NULL COMMENT '시계열 윈도우 크기', 
	performance_7d_mse DECIMAL(12, 8) COMMENT '7일 예측 MSE (Mean Squared Error)', 
	performance_7d_mae DECIMAL(12, 8) COMMENT '7일 예측 MAE (Mean Absolute Error)', 
	performance_7d_direction_accuracy DECIMAL(5, 4) COMMENT '7일 예측 방향 정확도 (0.0 ~ 1.0)', 
	performance_14d_mse DECIMAL(12, 8) COMMENT '14일 예측 MSE', 
	performance_14d_mae DECIMAL(12, 8) COMMENT '14일 예측 MAE', 
	performance_14d_direction_accuracy DECIMAL(5, 4) COMMENT '14일 예측 방향 정확도', 
	performance_30d_mse DECIMAL(12, 8) COMMENT '30일 예측 MSE', 
	performance_30d_mae DECIMAL(12, 8) COMMENT '30일 예측 MAE', 
	performance_30d_direction_accuracy DECIMAL(5, 4) COMMENT '30일 예측 방향 정확도', 
	overall_performance_score DECIMAL(5, 4) COMMENT '종합 성능 점수 (0.0 ~ 1.0)', 
	hyperparameters TEXT COMMENT '하이퍼파라미터 정보 (JSON 형태)', 
	model_architecture TEXT COMMENT '모델 구조 정보 (JSON 형태)', 
	training_config TEXT COMMENT '훈련 설정 정보 (JSON 형태)', 
	status VARCHAR(20) NOT NULL COMMENT '모델 상태 (training, active, deprecated, failed)', 
	is_active BOOL NOT NULL COMMENT '현재 활성 모델 여부', 
	deployed_at DATETIME COMMENT '배포 시점', 
	deprecated_at DATETIME COMMENT '사용 중단 시점', 
	description TEXT COMMENT '모델 설명', 
	created_by VARCHAR(100) COMMENT '모델 생성자', 
	tags TEXT COMMENT '모델 태그 (JSON 배열)', 
	created_at DATETIME COMMENT '레코드 생성 시점', 
	updated_at DATETIME COMMENT '레코드 수정 시점', 
	PRIMARY KEY (id), 
	CONSTRAINT uq_model_name_version UNIQUE (model_name, model_version)
);

CREATE INDEX idx_ml_model_active ON ml_models (is_active, status);

CREATE INDEX idx_ml_model_name ON ml_models (model_name);

CREATE INDEX idx_ml_model_performance ON ml_models (overall_performance_score);

CREATE INDEX idx_ml_model_symbol ON ml_models (symbol);

CREATE INDEX idx_ml_model_training_date ON ml_models (training_end_date);

CREATE INDEX idx_ml_model_type ON ml_models (model_type);

CREATE TABLE ml_predictions (
	id BIGINT NOT NULL COMMENT 'ML 예측 결과 고유 ID' AUTO_INCREMENT, 
	symbol VARCHAR(10) NOT NULL COMMENT '심볼 (^IXIC: 나스닥, ^GSPC: S&P 500)', 
	prediction_date DATE NOT NULL COMMENT '예측을 수행한 날짜', 
	prediction_timeframe VARCHAR(10) NOT NULL COMMENT '예측 기간 (7d, 14d, 30d)', 
	target_date DATE NOT NULL COMMENT '예측 대상 날짜 (prediction_date + timeframe)', 
	batch_id VARCHAR(50) NOT NULL COMMENT '동시 예측 배치 ID (같은 요청의 7d/14d/30d 연결)', 
	current_price DECIMAL(12, 4) NOT NULL COMMENT '예측 시점의 현재 가격', 
	predicted_price DECIMAL(12, 4) NOT NULL COMMENT '예측된 미래 가격', 
	predicted_direction VARCHAR(10) NOT NULL COMMENT '예측 방향 (up, down, neutral)', 
	price_change_percent DECIMAL(8, 4) COMMENT '예상 가격 변화율 (%%)', 
	confidence_score DECIMAL(5, 4) NOT NULL COMMENT '예측 신뢰도 점수 (0.0 ~ 1.0)', 
	actual_price DECIMAL(12, 4) COMMENT '실제 가격 (target_date에 업데이트)', 
	actual_direction VARCHAR(10) COMMENT '실제 방향 (up, down, neutral)', 
	actual_change_percent DECIMAL(8, 4) COMMENT '실제 가격 변화율 (%%)', 
	is_direction_correct BOOL COMMENT '방향 예측 정확 여부', 
	price_error_percent DECIMAL(8, 4) COMMENT '가격 예측 오차율 (%%)', 
	model_version VARCHAR(50) NOT NULL COMMENT '사용된 모델 버전', 
	model_type VARCHAR(20) NOT NULL COMMENT '모델 타입 (lstm, gru, transformer)', 
	features_used TEXT COMMENT '사용된 특성 정보 (JSON 형태)', 
	feature_count BIGINT COMMENT '사용된 특성 개수', 
	created_at DATETIME COMMENT '레코드 생성 시점', 
	updated_at DATETIME COMMENT '레코드 수정 시점', 
	result_updated_at DATETIME COMMENT '실제 결과 업데이트 시점', 
	PRIMARY KEY (id), 
	CONSTRAINT uq_symbol_date_timeframe UNIQUE (symbol, prediction_date, prediction_timeframe)
);

CREATE INDEX idx_ml_prediction_analysis ON ml_predictions (symbol, prediction_timeframe, prediction_date);

CREATE INDEX idx_ml_prediction_batch ON ml_predictions (batch_id);

CREATE INDEX idx_ml_prediction_date ON ml_predictions (prediction_date);

CREATE INDEX idx_ml_prediction_model ON ml_predictions (model_version);

CREATE INDEX idx_ml_prediction_pending_update ON ml_predictions (target_date, actual_price);

CREATE INDEX idx_ml_prediction_symbol ON ml_predictions (symbol);

CREATE INDEX idx_ml_prediction_target_date ON ml_predictions (target_date);

CREATE TABLE pattern_clusters (
	id BIGINT NOT NULL COMMENT '클러스터 고유 ID' AUTO_INCREMENT, 
	symbol VARCHAR(20) NOT NULL COMMENT '클러스터링 대상 심볼 (예: ^IXIC, ^GSPC)', 
	cluster_id INTEGER NOT NULL COMMENT '
        클러스터 번호 (0부터 시작)
        예: 0, 1, 2, 3, 4, 5 (총 6개 그룹으로 분류한 경우)
        ', 
	cluster_name VARCHAR(100) NOT NULL COMMENT '
        클러스터 이름 (자동 생성)
        예시:
        - RSI_상승_패턴: RSI 기반 상승 신호 패턴들
        - MA_하락_패턴: 이동평균 기반 하락 신호 패턴들
        - 볼린저_중립_패턴: 볼린저밴드 기반 중립 패턴들
        - 복합_상승_패턴: 여러 지표 조합 상승 패턴들
        ', 
	timeframe VARCHAR(10) NOT NULL COMMENT '클러스터링 시간대 (1min, 15min, 1day)', 
	pattern_count INTEGER NOT NULL COMMENT '클러스터에 속한 패턴 개수', 
	avg_success_rate DECIMAL(5, 2) COMMENT '
        클러스터 평균 성공률 (%%)
        클러스터 내 모든 패턴들의 성공률 평균
        예: 68.5 (68.5%% 성공률)
        ', 
	avg_confidence_score DECIMAL(5, 2) COMMENT '
        클러스터 평균 신뢰도 점수 (0~100)
        클러스터 내 모든 패턴들의 신뢰도 점수 평균
        ', 
	avg_duration_hours DECIMAL(8, 2) COMMENT '
        클러스터 평균 패턴 지속 시간 (시간)
        클러스터 내 패턴들의 평균 지속 시간
        예: 4.5 (평균 4시간 30분)
        ', 
	bullish_tendency DECIMAL(5, 2) COMMENT '
        상승 성향 점수 (0~1)
        클러스터가 상승 패턴에 얼마나 치우쳐 있는지
        1.0: 완전한 상승 패턴 그룹
        0.0: 상승 패턴이 전혀 없음
        ', 
	bearish_tendency DECIMAL(5, 2) COMMENT '
        하락 성향 점수 (0~1)
        클러스터가 하락 패턴에 얼마나 치우쳐 있는지
        1.0: 완전한 하락 패턴 그룹
        0.0: 하락 패턴이 전혀 없음
        ', 
	dominant_signal_types TEXT COMMENT '
        클러스터 내 주요 신호 타입들 (JSON 배열)
        예: ["RSI", "MA", "BB"] - RSI, 이동평균, 볼린저밴드가 주요 신호
        ', 
	cluster_center TEXT COMMENT '
        클러스터 중심점 좌표 (JSON 배열)
        K-means 알고리즘에서 계산된 클러스터의 중심점
        예: [0.5, 1.0, 0.0, 0.8, 0.2, 1.0, 0.0, 0.3, 0.4, ...]
        
        각 차원의 의미:
        - 0: 지속시간 (정규화)
        - 1-6: 신호 타입별 원-핫 인코딩 (RSI, MA, BB, MACD, CROSS, VOLUME)
        - 7-8: 상승/하락 성향
        - 9-10: 시장 상황 (상승장/하락장)
        - 11-12: 시간적 특성 (요일/월)
        ', 
	cluster_radius DECIMAL(8, 4) COMMENT '
        클러스터 반경 (클러스터 내 패턴들의 평균 거리)
        작을수록 클러스터 내 패턴들이 유사함
        클수록 클러스터 내 패턴들이 다양함
        ', 
	representative_patterns TEXT COMMENT '
        대표 패턴들의 ID 목록 (JSON 배열)
        클러스터를 가장 잘 나타내는 패턴들 (최대 5개)
        예: [123, 456, 789] - signal_patterns 테이블의 ID들
        ', 
	pattern_examples TEXT COMMENT '
        패턴 예시 설명 (JSON 객체)
        사용자가 이해하기 쉬운 패턴 설명들
        예: {
            "main_pattern": "RSI 과매도 후 20일선 돌파",
            "variations": ["RSI 30 이하 → MA20 상향돌파", "RSI 반등 + 거래량 증가"],
            "success_examples": ["2024-01-15: +3.2%%", "2024-02-03: +2.8%%"]
        }
        ', 
	clustering_algorithm VARCHAR(20) COMMENT '
        사용된 클러스터링 알고리즘
        - kmeans: K-means 클러스터링
        - hierarchical: 계층적 클러스터링
        - dbscan: DBSCAN 클러스터링
        ', 
	n_clusters_total INTEGER NOT NULL COMMENT '전체 클러스터 개수 (예: 6개 그룹으로 분류)', 
	clustering_quality_score DECIMAL(5, 2) COMMENT '
        클러스터링 품질 점수 (0~100)
        클러스터링이 얼마나 잘 되었는지 평가
        - 100: 완벽한 클러스터링 (그룹 간 명확한 구분)
        - 0: 매우 나쁜 클러스터링 (그룹 간 구분 불가)
        
        계산 요소:
        - 클러스터 내 응집도 (Intra-cluster cohesion)
        - 클러스터 간 분리도 (Inter-cluster separation)
        - 실루엣 점수 (Silhouette score)
        ', 
	clustered_at DATETIME NOT NULL COMMENT '클러스터링 실행 시점', 
	created_at DATETIME COMMENT '레코드 생성 시점', 
	updated_at DATETIME COMMENT '레코드 수정 시점', 
	PRIMARY KEY (id), 
	CONSTRAINT uq_cluster_unique UNIQUE (symbol, cluster_id, timeframe, clustered_at)
);

CREATE INDEX idx_cluster_id ON pattern_clusters (cluster_id);

CREATE INDEX idx_clustered_at ON pattern_clusters (clustered_at);

CREATE INDEX idx_performance ON pattern_clusters (avg_success_rate, avg_confidence_score);

CREATE INDEX idx_symbol_clustered_at ON pattern_clusters (symbol, clustered_at);

CREATE INDEX idx_symbol_timeframe ON pattern_clusters (symbol, timeframe);

CREATE INDEX idx_tendency ON pattern_clusters (bullish_tendency, bearish_tendency);

CREATE TABLE price_alert_logs (
	id BIGINT NOT NULL COMMENT 'PK' AUTO_INCREMENT, 
	symbol VARCHAR(20) NOT NULL COMMENT '종목 코드 (예: AAPL, ^GSPC 등)', 
	alert_type VARCHAR(30) NOT NULL COMMENT '
    알림 종류
    - price_rise: 전일 종가 기준 급등 (기준 등락률 이상 상승)
    - price_drop: 전일 종가 기준 급락 (기준 등락률 이하 하락)
    - new_high: 상장 후 최고가 갱신
    - drop_from_high: 상장 후 최고가 대비 급락 (기준 등락률 이하 하락)
    - break_prev_high: 전일 고점 돌파
    - break_prev_low: 전일 저점 하회
    ', 
	base_type VARCHAR(30) NOT NULL COMMENT '
    기준 가격 종류
    - prev_close: 전일 종가 기준
    - all_time_high: 상장 후 최고가 기준
    - prev_high: 전일 고점 기준
    - prev_low: 전일 저점 기준
    ', 
	base_price FLOAT NOT NULL COMMENT '비교 기준 가격 (예: 전일 종가, 최고가, 고점, 저점)', 
	current_price FLOAT NOT NULL COMMENT '현재 시세', 
	threshold_percent FLOAT NOT NULL COMMENT '알림 기준 등락률 (예: 5.0)', 
	actual_percent FLOAT NOT NULL COMMENT '실제 변동률 (예: -5.8)', 
	base_time DATETIME NOT NULL COMMENT '기준 가격 발생 시각 (예: 종가, 최고가, 고점 등)', 
	triggered_at DATETIME NOT NULL COMMENT '알림 발생 시각', 
	PRIMARY KEY (id)
);

CREATE INDEX ix_price_alert_logs_symbol ON price_alert_logs (symbol);

CREATE TABLE price_high_records (
	id BIGINT NOT NULL AUTO_INCREMENT, 
	symbol VARCHAR(20) NOT NULL COMMENT '종목 코드', 
	source VARCHAR(30) NOT NULL COMMENT '데이터 제공자 (예: yahoo)', 
	price FLOAT NOT NULL COMMENT '기록된 최고가', 
	recorded_at DATETIME NOT NULL COMMENT '해당 고점이 기록된 시점', 
	created_at DATETIME NOT NULL COMMENT '기록 저장 시각', 
	PRIMARY KEY (id)
);

CREATE INDEX ix_price_high_records_symbol ON price_high_records (symbol);

CREATE TABLE price_snapshots (
	id BIGINT NOT NULL COMMENT 'PK' AUTO_INCREMENT, 
	symbol VARCHAR(20) NOT NULL COMMENT '종목 코드 (예: AAPL, ^IXIC)', 
	source VARCHAR(30) NOT NULL COMMENT '데이터 제공자 (예: yahoo, investing)', 
	close FLOAT COMMENT '종가', 
	high FLOAT COMMENT '고가', 
	low FLOAT COMMENT '저가', 
	volume FLOAT COMMENT '거래량', 
	snapshot_at DATETIME NOT NULL COMMENT '가격 기준 시각 (예: 2024-07-08 00:00)', 
	created_at DATETIME NOT NULL COMMENT '저장된 시각', 
	PRIMARY KEY (id)
);

CREATE INDEX ix_price_snapshots_symbol ON price_snapshots (symbol);

CREATE TABLE slow_query_logs (
	id INTEGER NOT NULL AUTO_INCREMENT, 
	query_hash VARCHAR(32) NOT NULL COMMENT '쿼리 해시 (정규화된 쿼리 식별자)', 
	query_template TEXT NOT NULL COMMENT '정규화된 쿼리 템플릿', 
	original_query TEXT COMMENT '원본 쿼리 (최대 2000자)', 
	duration FLOAT NOT NULL COMMENT '실행 시간 (초)', 
	affected_rows INTEGER COMMENT '영향받은 행 수', 
	table_names VARCHAR(500) COMMENT '관련 테이블명들 (쉼표 구분)', 
	operation_type VARCHAR(20) COMMENT '쿼리 타입 (SELECT, INSERT, etc.)', 
	created_at DATETIME NOT NULL COMMENT '기록 시간', 
	execution_timestamp DATETIME NOT NULL COMMENT '쿼리 실행 시간', 
	PRIMARY KEY (id)
);

CREATE INDEX idx_slow_query_duration_time ON slow_query_logs (duration, execution_timestamp);

CREATE INDEX idx_slow_query_execution_time ON slow_query_logs (execution_timestamp);

CREATE INDEX idx_slow_query_hash_time ON slow_query_logs (query_hash, execution_timestamp);

CREATE INDEX idx_slow_query_operation_time ON slow_query_logs (operation_type, execution_timestamp);

CREATE INDEX ix_slow_query_logs_created_at ON slow_query_logs (created_at);

CREATE INDEX ix_slow_query_logs_duration ON slow_query_logs (duration);

CREATE INDEX ix_slow_query_logs_execution_timestamp ON slow_query_logs (execution_timestamp);

CREATE INDEX ix_slow_query_logs_operation_type ON slow_query_logs (operation_type);

CREATE INDEX ix_slow_query_logs_query_hash ON slow_query_logs (query_hash);

CREATE TABLE symbols (
	id INTEGER NOT NULL AUTO_INCREMENT, 
	symbol VARCHAR(10) NOT NULL, 
	name VARCHAR(255) NOT NULL, 
	country VARCHAR(100), 
	korea_name VARCHAR(255), 
	PRIMARY KEY (id), 
	UNIQUE (symbol)
);

CREATE TABLE technical_signals (
	id BIGINT NOT NULL COMMENT '신호 고유 ID' AUTO_INCREMENT, 
	symbol VARCHAR(20) NOT NULL COMMENT '심볼 (예: NQ=F, ^IXIC, AAPL)', 
	signal_type VARCHAR(50) NOT NULL COMMENT '
        신호 타입 (예시):
        - MA20_breakout_up: 20일선 상향 돌파
        - MA200_breakout_down: 200일선 하향 이탈
        - RSI_overbought: RSI 과매수 (70 이상)
        - RSI_oversold: RSI 과매도 (30 이하)
        - BB_touch_upper: 볼린저 밴드 상단 터치
        - golden_cross: 골든크로스 (50일선이 200일선 상향돌파)
        - dead_cross: 데드크로스 (50일선이 200일선 하향이탈)
        ', 
	timeframe VARCHAR(10) NOT NULL COMMENT '시간대 (1min, 15min, 1hour, 1day)', 
	triggered_at DATETIME NOT NULL COMMENT '신호 발생 시점 (UTC 기준)', 
	current_price DECIMAL(12, 4) NOT NULL COMMENT '신호 발생 시점의 현재 가격', 
	indicator_value DECIMAL(12, 4) COMMENT '
        신호와 관련된 지표값
        - 이동평균 신호: MA값 (예: 200일선 값)
        - RSI 신호: RSI값 (예: 72.5)
        - 볼린저밴드 신호: 상단/하단 밴드값
        ', 
	signal_strength DECIMAL(8, 4) COMMENT '
        신호의 강도 (%%)
        - 이동평균 돌파: 돌파폭 (현재가 - MA값) / MA값 * 100
        - RSI: 과매수/과매도 정도 (70 이상이면 70에서 얼마나 벗어났는지)
        - 볼린저밴드: 밴드에서 얼마나 벗어났는지
        ', 
	volume BIGINT COMMENT '신호 발생 시점의 거래량 (거래량 급증 여부 판단용)', 
	market_condition VARCHAR(20) COMMENT '
        신호 발생 시점의 시장 상황
        - bullish: 상승장
        - bearish: 하락장  
        - sideways: 횡보장
        - volatile: 변동성 큰 상황
        ', 
	additional_context TEXT COMMENT '
        추가 컨텍스트 정보 (JSON 형태)
        예: {"prev_signals": ["RSI_overbought"], "market_news": "FOMC 발표"}
        ', 
	alert_sent BOOL COMMENT '텔레그램 알림 발송 여부', 
	alert_sent_at DATETIME COMMENT '알림 발송 시점', 
	created_at DATETIME COMMENT '레코드 생성 시점', 
	updated_at DATETIME COMMENT '레코드 수정 시점', 
	PRIMARY KEY (id), 
	CONSTRAINT uq_signal_unique UNIQUE (symbol, signal_type, timeframe, triggered_at)
);

CREATE INDEX idx_signal_type ON technical_signals (signal_type);

CREATE INDEX idx_symbol_signal_timeframe ON technical_signals (symbol, signal_type, timeframe);

CREATE INDEX idx_symbol_triggered_at ON technical_signals (symbol, triggered_at);

CREATE INDEX idx_timeframe ON technical_signals (timeframe);

CREATE INDEX idx_triggered_at ON technical_signals (triggered_at);

CREATE TABLE content_sentiments (
	id BIGINT NOT NULL COMMENT 'PK' AUTO_INCREMENT, 
	content_id BIGINT NOT NULL COMMENT '원본 콘텐츠 FK', 
	sentiment_score FLOAT NOT NULL COMMENT '감정 점수 (-1.0 ~ +1.0)', 
	sentiment_label VARCHAR(20) NOT NULL COMMENT '감정 라벨 (positive, negative, neutral)', 
	confidence FLOAT NOT NULL COMMENT '분석 신뢰도 (0.0 ~ 1.0)', 
	positive_score FLOAT NOT NULL COMMENT '긍정 점수 (0.0 ~ 1.0)', 
	negative_score FLOAT NOT NULL COMMENT '부정 점수 (0.0 ~ 1.0)', 
	neutral_score FLOAT NOT NULL COMMENT '중립 점수 (0.0 ~ 1.0)', 
	compound_score FLOAT NOT NULL COMMENT '복합 점수 (-1.0 ~ +1.0)', 
	market_impact_score FLOAT COMMENT '시장 영향도 점수 (-1.0 ~ +1.0)', 
	is_market_sensitive BOOL COMMENT '시장 민감 뉴스 여부', 
	analyzer_type VARCHAR(50) NOT NULL COMMENT '사용한 분석기 (vader, textblob 등)', 
	analyzed_at DATETIME NOT NULL COMMENT '분석 시각', 
	PRIMARY KEY (id), 
	FOREIGN KEY(content_id) REFERENCES contents (id) ON DELETE CASCADE
);

CREATE TABLE content_translations (
	id BIGINT NOT NULL COMMENT 'PK' AUTO_INCREMENT, 
	content_id BIGINT NOT NULL COMMENT '원본 콘텐츠 FK', 
	language VARCHAR(10) NOT NULL COMMENT '번역 언어 (예: ko)', 
	title_translated TEXT COMMENT '번역된 제목', 
	summary_translated TEXT COMMENT '번역된 요약', 
	translator VARCHAR(50) COMMENT '사용한 번역기 (예: openai, deepl, papago 등)', 
	translated_at DATETIME NOT NULL COMMENT '번역 시각', 
	published_at DATETIME NOT NULL COMMENT '기사 발행 시각', 
	PRIMARY KEY (id), 
	FOREIGN KEY(content_id) REFERENCES contents (id) ON DELETE CASCADE
);

CREATE TABLE signal_outcomes (
	id BIGINT NOT NULL COMMENT '결과 추적 고유 ID' AUTO_INCREMENT, 
	signal_id BIGINT NOT NULL COMMENT '추적 대상 신호의 ID (technical_signals 테이블 참조)', 
	price_1h_after DECIMAL(12, 4) COMMENT '신호 발생 1시간 후 가격', 
	price_4h_after DECIMAL(12, 4) COMMENT '신호 발생 4시간 후 가격', 
	price_1d_after DECIMAL(12, 4) COMMENT '신호 발생 1일(24시간) 후 가격', 
	price_1w_after DECIMAL(12, 4) COMMENT '신호 발생 1주일(7일) 후 가격', 
	price_1m_after DECIMAL(12, 4) COMMENT '신호 발생 1개월(30일) 후 가격', 
	return_1h DECIMAL(8, 4) COMMENT '
        1시간 후 수익률 (%%)
        계산식: (1시간후가격 - 신호발생시가격) / 신호발생시가격 * 100
        예: +2.5 (2.5%% 상승), -1.2 (-1.2%% 하락)ㅁ
        ', 
	return_4h DECIMAL(8, 4) COMMENT '4시간 후 수익률 (%%)', 
	return_1d DECIMAL(8, 4) COMMENT '1일 후 수익률 (%%)', 
	return_1w DECIMAL(8, 4) COMMENT '1주일 후 수익률 (%%)', 
	return_1m DECIMAL(8, 4) COMMENT '1개월 후 수익률 (%%)', 
	max_price_reached DECIMAL(12, 4) COMMENT '
        신호 발생 후 도달한 최고 가격
        - 상승 신호의 경우: 얼마나 높이 올라갔는지 확인
        - 하락 신호의 경우: 반등이 얼마나 있었는지 확인
        ', 
	min_price_reached DECIMAL(12, 4) COMMENT '
        신호 발생 후 도달한 최저 가격
        - 상승 신호의 경우: 중간에 얼마나 떨어졌는지 확인
        - 하락 신호의 경우: 얼마나 낮게 떨어졌는지 확인
        ', 
	max_return DECIMAL(8, 4) COMMENT '
        최대 수익률 (%%)
        신호 발생 후 가장 좋았을 때의 수익률
        예: 200일선 돌파 후 최대 +8.5%%까지 올라갔다
        ', 
	max_drawdown DECIMAL(8, 4) COMMENT '
        최대 손실률 (%%)
        신호 발생 후 가장 나빴을 때의 손실률 (음수)
        예: RSI 과매수 신호 후 최대 -5.2%%까지 떨어졌다
        ', 
	min_return DECIMAL(8, 4) COMMENT '최소 수익률 (%%) - 추적 기간 중 가장 낮은 수익률', 
	is_successful_1h BOOL COMMENT '
        1시간 기준 성공 여부
        - 상승 신호: 1시간 후 가격이 올랐으면 True
        - 하락 신호: 1시간 후 가격이 떨어졌으면 True
        - 중립 신호: 특정 기준에 따라 판정
        ', 
	is_successful_1d BOOL COMMENT '1일 기준 성공 여부', 
	is_successful_1w BOOL COMMENT '1주일 기준 성공 여부', 
	is_successful_1m BOOL COMMENT '1개월 기준 성공 여부', 
	volatility_1d DECIMAL(8, 4) COMMENT '
        1일간 변동성 (%%)
        신호 발생 후 1일간 가격 변동의 표준편차
        높을수록 가격이 많이 흔들렸다는 의미
        ', 
	volatility_1w DECIMAL(8, 4) COMMENT '1주일간 변동성 (%%)', 
	trend_strength DECIMAL(8, 4) COMMENT '
        추세 강도 점수 (0~100)
        신호 방향으로 얼마나 일관되게 움직였는지 측정
        100: 완벽한 일방향 움직임
        0: 완전한 횡보 또는 반대 방향 움직임
        ', 
	last_updated_at DATETIME COMMENT '마지막 업데이트 시점 (가격 데이터 수집 시점)', 
	is_complete BOOL COMMENT '
        추적 완료 여부
        - False: 아직 추적 중 (1개월이 지나지 않음)
        - True: 추적 완료 (1개월 후 데이터까지 수집 완료)
        ', 
	created_at DATETIME COMMENT '레코드 생성 시점', 
	updated_at DATETIME COMMENT '레코드 수정 시점', 
	PRIMARY KEY (id), 
	CONSTRAINT uq_signal_outcome UNIQUE (signal_id), 
	FOREIGN KEY(signal_id) REFERENCES technical_signals (id) ON DELETE CASCADE
);

CREATE INDEX idx_is_complete ON signal_outcomes (is_complete);

CREATE INDEX idx_last_updated_at ON signal_outcomes (last_updated_at);

CREATE INDEX idx_returns ON signal_outcomes (return_1d, return_1w);

CREATE INDEX idx_signal_id ON signal_outcomes (signal_id);

CREATE INDEX idx_success_analysis ON signal_outcomes (is_successful_1d, is_successful_1w);

CREATE TABLE signal_patterns (
	id BIGINT NOT NULL COMMENT '패턴 고유 ID' AUTO_INCREMENT, 
	pattern_name VARCHAR(100) NOT NULL COMMENT '
        패턴 이름 (예시):
        - RSI_overbought_then_BB_upper: RSI 과매수 후 볼린저 상단 터치
        - MA200_breakout_with_volume: 200일선 돌파 + 거래량 급증
        - golden_cross_after_consolidation: 횡보 후 골든크로스
        - triple_bottom_reversal: 삼중 바닥 반전 패턴
        ', 
	pattern_type VARCHAR(20) NOT NULL COMMENT '
        패턴 유형:
        - sequential: 순차적 패턴 (A → B → C)
        - concurrent: 동시 발생 패턴 (A + B + C)
        - leading: 선행 지표 패턴 (A → 시간 간격 → B)
        - reversal: 반전 패턴
        - continuation: 지속 패턴
        ', 
	symbol VARCHAR(20) NOT NULL COMMENT '패턴이 발생한 심볼', 
	timeframe VARCHAR(10) NOT NULL COMMENT '패턴 분석 시간대', 
	first_signal_id BIGINT NOT NULL COMMENT '첫 번째 신호 ID (패턴의 시작점)', 
	second_signal_id BIGINT COMMENT '두 번째 신호 ID', 
	third_signal_id BIGINT COMMENT '세 번째 신호 ID', 
	fourth_signal_id BIGINT COMMENT '네 번째 신호 ID', 
	fifth_signal_id BIGINT COMMENT '다섯 번째 신호 ID', 
	pattern_start DATETIME NOT NULL COMMENT '패턴 시작 시점 (첫 번째 신호 발생 시점)', 
	pattern_end DATETIME NOT NULL COMMENT '패턴 완성 시점 (마지막 신호 발생 시점)', 
	pattern_duration_hours DECIMAL(8, 2) COMMENT '
        패턴 지속 시간 (시간 단위)
        패턴 시작부터 완성까지 걸린 시간
        예: 2.5 (2시간 30분), 24.0 (1일), 168.0 (1주일)
        ', 
	pattern_outcome_1h DECIMAL(8, 4) COMMENT '패턴 완성 후 1시간 후 수익률 (%%)', 
	pattern_outcome_1d DECIMAL(8, 4) COMMENT '패턴 완성 후 1일 후 수익률 (%%)', 
	pattern_outcome_1w DECIMAL(8, 4) COMMENT '패턴 완성 후 1주일 후 수익률 (%%)', 
	max_favorable_move DECIMAL(8, 4) COMMENT '
        패턴 방향으로 최대 움직임 (%%)
        - 상승 패턴: 최대 상승률
        - 하락 패턴: 최대 하락률 (음수)
        ', 
	max_adverse_move DECIMAL(8, 4) COMMENT '
        패턴 반대 방향으로 최대 움직임 (%%)
        - 상승 패턴: 최대 하락률 (음수)
        - 하락 패턴: 최대 상승률
        ', 
	is_successful_1h BOOL COMMENT '1시간 기준 패턴 성공 여부', 
	is_successful_1d BOOL COMMENT '1일 기준 패턴 성공 여부', 
	is_successful_1w BOOL COMMENT '1주일 기준 패턴 성공 여부', 
	confidence_score DECIMAL(5, 2) COMMENT '
        패턴 신뢰도 점수 (0~100)
        - 100: 매우 강한 패턴 (높은 성공률)
        - 50: 중립적 패턴
        - 0: 매우 약한 패턴 (낮은 성공률)
        
        계산 요소:
        - 과거 동일 패턴의 성공률
        - 신호들 간의 시간 간격
        - 시장 상황과의 일치도
        - 거래량 등 부가 지표
        ', 
	market_condition VARCHAR(20) COMMENT '
        패턴 발생 시 시장 상황
        - strong_uptrend: 강한 상승 추세
        - weak_uptrend: 약한 상승 추세
        - sideways: 횡보
        - weak_downtrend: 약한 하락 추세
        - strong_downtrend: 강한 하락 추세
        ', 
	volatility_level VARCHAR(10) COMMENT '
        패턴 발생 시 변동성 수준
        - low: 낮은 변동성 (< 1%% 일일 변동)
        - medium: 보통 변동성 (1-3%% 일일 변동)
        - high: 높은 변동성 (> 3%% 일일 변동)
        ', 
	volume_profile VARCHAR(20) COMMENT '
        패턴 발생 시 거래량 프로필
        - increasing: 거래량 증가 중
        - decreasing: 거래량 감소 중
        - spike: 거래량 급증
        - normal: 평상시 수준
        ', 
	pattern_description TEXT COMMENT '
        패턴에 대한 상세 설명 (JSON 형태)
        {
            "signals": ["RSI 과매수", "볼린저 상단 터치"],
            "sequence": "RSI가 먼저 70을 돌파한 후 2시간 내에 볼린저 상단 터치",
            "market_context": "상승 추세 중 과열 신호",
            "expected_outcome": "단기 조정 예상"
        }
        ', 
	similar_patterns_count BIGINT COMMENT '과거 동일한 패턴 발생 횟수 (참고용)', 
	created_at DATETIME COMMENT '패턴 레코드 생성 시점', 
	updated_at DATETIME COMMENT '패턴 레코드 수정 시점', 
	analyzed_at DATETIME COMMENT '패턴 분석 완료 시점', 
	PRIMARY KEY (id), 
	FOREIGN KEY(first_signal_id) REFERENCES technical_signals (id), 
	FOREIGN KEY(second_signal_id) REFERENCES technical_signals (id), 
	FOREIGN KEY(third_signal_id) REFERENCES technical_signals (id), 
	FOREIGN KEY(fourth_signal_id) REFERENCES technical_signals (id), 
	FOREIGN KEY(fifth_signal_id) REFERENCES technical_signals (id)
);

CREATE INDEX idx_market_condition ON signal_patterns (market_condition, volatility_level);

CREATE INDEX idx_pattern_name ON signal_patterns (pattern_name);

CREATE INDEX idx_pattern_time_range ON signal_patterns (pattern_start, pattern_end);

CREATE INDEX idx_pattern_type ON signal_patterns (pattern_type);

CREATE INDEX idx_success_analysis ON signal_patterns (is_successful_1d, confidence_score);

CREATE INDEX idx_symbol_timeframe ON signal_patterns (symbol, timeframe);
