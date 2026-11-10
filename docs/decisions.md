# 설계 결정

## 1. 기술과 계약

DEX 프로젝트와 같은 Kotlin 2.3.21, Spring Boot 4.1.2, JDK 21, Gradle 9.8.2를 사용한다. Spring MVC에서 원본 FastAPI의 경로와 JSON을 유지한다. 원본은 Python이므로 향후 원본 저장소 접미사는 `-fastapi`로 구분한다.

## 2. DB

MySQL 테이블과 컬럼은 원본 SQLAlchemy 선언을 기준으로 유지한다. JdbcTemplate의 바인드 변수와 명시적 RowMapper를 사용한다. 자동 DDL 없이 기존 스키마를 읽는다. H2 테스트 통과와 MySQL 통합 검증은 별도로 기록한다.

## 3. 숫자와 분석

금융제표는 원본이 Float, 가격은 DECIMAL이다. DEX의 정수 금액 모델을 그대로 적용해 API 숫자 타입을 바꾸지 않는다. pandas의 rolling min_periods·표본 표준편차·EMA adjust=False·RSI 초기값을 표본 테스트로 고정한다.

## 4. 검증 경계

원본 환경변수를 읽거나 외부 Telegram·거래 API를 호출하지 않는다. Python AST로 계약만 추출하며 실제 서버 시작에 따른 자동 배치·메시지 전송을 피한다. ML 모델의 학습·추론은 원본의 모델 형식과 수치 일치가 확인되기 전 완료 처리하지 않는다.

## 참고

- https://docs.spring.io/spring-framework/reference/data-access/jdbc/core.html
- https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-validation.html

## 회사 조회 호환

- `/api/symbols/symbols`, `/api/financials/financials/{symbol}`의 중복된 경로 구성도 그대로 유지한다.
- 심볼 미등록은 원본 서비스가 None을 반환하고 라우터가 성공 응답으로 감싸므로 HTTP 200·data=null을 유지한다.
- 입력 검증 HTTP 422와 detail 배열은 유지하되, JVM 검증 메시지와 Pydantic 오류 code의 세부 문구 차이는 존재한다.
- DB 오류의 내부 SQL·연결 정보는 공개하지 않는다. 원본의 예외 문자열 반환과 달리 일반 메시지를 사용한다. 기존 스키마의 country NULL은 DB 선언대로 응답할 수 있고, 원본 SymbolResponse의 중복 country 선언은 별도 호환 한계다.

## 지표 계산 검증

원본 순수 TechnicalIndicatorService 클래스를 AST로 추출해 데코레이터만 제거하고 pandas 2.2.3·NumPy 2.2.5에서 실행했다. 상승·혼합·고정·짧은 데이터 4종 전체 시계열을 Kotlin과 비교한다. SMA warmup, 일반 EMA adjust=False, MACD adjust=True, 표본 표준편차 ddof=1, RSI 초기 delta=0을 유지한다. 캐시 데코레이터의 내부 객체 식별자 기반 동작은 계산 함수에 복제하지 않는다.

NaN/Infinity는 순수 계산에서는 IEEE 값으로 유지한다. HTTP JSON에는 null로 내보낸다. 원본 FastAPI가 비유한 숫자를 직렬화하다 실패할 수 있는 구간과 명시적으로 다른 동작이며 허위 숫자를 만들지 않는다.

## 시세 HTTP 어댑터

원본 AsyncPriceService와 같은 Yahoo chart 경로·query·OHLCV 배열을 사용한다. connect 10초·요청 30초 제한, 외부 실패 시 null, 배치별 부분 실패를 유지한다. Kotlin 코루틴 IO dispatcher와 Semaphore로 동시 조회를 제한한다. 테스트는 로컬 HttpServer 응답으로 실행하며 실제 Yahoo 연결은 검증 결과에 포함하지 않는다. batch_size가 0 이하이면 실행 불능을 피하려고 HTTP 422로 거절한다.

## 신호 조회의 기존 동작

원본 by-symbol은 signal_type이 없으면 LIMIT/OFFSET 후 timeframe 필터를 적용하고, pagination.total은 기본 limit=100의 조회 결과를 센다. 이 순서와 상한도 보존해 테스트한다. to_dict는 0인 price/indicator/strength를 null로 내보내므로 보존한다. 이 동작의 개선은 이전 완료 뒤 별도 변경으로 다룬다.

## 시세 요약

최고가 기록은 최신 날짜가 아니라 가장 큰 price를 선택한다. previous_high/low는 함수명과 달리 최신 snapshot이 속한 당일의 값이므로 원본 조건을 유지한다. 기본 심볼은 SYMBOL_PRICE_MAP의 실제 두 지수(^IXIC, ^GSPC)다. get_current(include_details=true)의 price_summary에서는 이미 조회한 현재가를 재사용해 추가 Yahoo 호출을 줄인다.

## 스키마 재현 검증

원본의 Column·UniqueConstraint·Index·ForeignKey 선언만 AST로 추출해 SQLAlchemy 2.0.40의 MySQL DDL compiler로 생성했다. 앱 import·환경변수·운영 DB 연결은 하지 않았다. 동결한 SQL은 새 테스트 DB용이며 앱 시작 때 기존 DB에 실행하지 않는다. 격리 MySQL 8.0에서 19개 테이블·271개 컬럼의 생성과 이름·순서를 원본 inventory와 대조했다. `tools/verify_mysql_schema.py`는 Python 표준 라이브러리·Docker만으로 이 검증을 재현한다.

## 인프로세스 작업 큐

원본의 우선순위 큐·4개 worker·작업 조회·지수 지연 재시도·시간 제한·시작/정지와 편의 API 15개를 코루틴으로 이전한다. 분산 큐나 영속 큐가 아니므로 프로세스가 종료되면 작업 상태는 사라진다. 예약된 재시도는 같은 프로세스에서 정지/재시작할 때 유지한다.

원본은 재시도 시 새 ID와 초기 카운터로 다시 제출해 재시도 한도가 지켜지지 않을 수 있고, 취소 중 동일 lock 재획득 문제도 있다. 대상은 ID를 유지하고 재시도 횟수를 제한하며 취소를 잠금 중첩 없이 수행한다. 실패한 작업의 상태 조회를 원본 router가 404로 바꾸던 오류도 실제 failed 상태 조회로 수정한다. 테스트로 우선순위/FIFO·재시도 상한·시간 제한 후 후속 실행·예약 취소·정지/재시작을 확인한다.

원본 background_tasks의 5개 예제 함수는 실제 시세/메시지/DB 정리가 아니라 난수와 지연을 사용하는 시연 코드다. 대상도 시연 함수로 구분하며 외부 메시지나 데이터 삭제를 수행하지 않는다. JVM 난수는 Python의 프로세스별 hash/NumPy 난수열과 같지 않다. pandas 객체 메모리 추정치는 JVM과 대응하지 않아 memory_usage_mb=null로 표시한다. 실제 일일 종합 리포트와 이 예제 작업의 generate_report는 다른 기능이다.

## 패턴·결과 추적·성과 분석

패턴/advanced/outcomes/signals 성과 경로 24개를 이전했다. 원본 AdvancedPatternService는 같은 이름의 temporal similarity 메서드가 뒤에서 재정의되어 datetime 비교 경로가 실패한다. 대상은 datetime 근접도와 pattern duration 유사도를 별도 타입의 함수로 나누고 두 정상 패턴 비교를 회귀 검증한다. duration>=1h를 성공으로 세는 원본 고급 분석의 특이한 기준은 그대로 유지한다.

수익이 있지만 손실이 없는 profit_factor 등 비유한 값은 내부 계산에서 유지하고 HTTP 응답에서 null로 정규화해 유효한 JSON으로 전달한다. 일부 DB 집계는 조회 후 JVM에서 계산하므로 큰 데이터셋에서는 원본과 메모리 사용량이 다르다. 테스트용 추적 API가 기존 결과를 교체하는 원본 부작용은 유지한다. H2와 격리 MySQL 8.0에서 추적 멱등성·수익률 갱신·패턴 외래키/DECIMAL 및 실제 HTTP 직렬화를 검증한다.

## 기존 비동기 시세 API와 유틸리티

`/api/v2/async` 3개 경로의 5분/10분 캐시와 배치 동시성 5를 유지했다. 원본 default_cache_manager는 LRU가 아니라 만료가 가장 빠른 값을 제거하는 메모리 캐시이므로 같은 정책을 사용한다. 별도 memory_cache.py의 LRU 통계와는 구분한다.

단일 시세 경로는 원본 handler가 price/source를 반환해도 AsyncPriceResponse가 이를 버리고 current_price 등 optional 필드를 null로 출력한다. 이 기존 HTTP 계약을 보존하며 시세는 batch 또는 `/api/v2/market-price/current/{symbol}`에서 확인할 수 있다. 유틸리티 2개 경로는 반대로 required 필드가 다른 response_model 때문에 원본이 직렬화에 실패한다. 대상은 handler의 실제 signal_types/health 구조를 응답하도록 이 명백한 DTO 불일치를 수정했다. DB health는 실제 최근 1시간 신호 조회로 확인한다.

필수 query/body 검증 오류는 HTTP 422 detail 배열로 통일한다. Pydantic과 JVM의 세부 오류 문구 차이는 남는다. 외부 오류에는 원본의 내부 예외 문자열 대신 일반 메시지를 사용한다.
