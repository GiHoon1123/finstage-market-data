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

## JVM 운영 관측과 WebSocket

monitoring 6개·memory 11개·database 13개·WebSocket 7개 경로를 이전했다. WebSocket은 Socket.IO가 아닌 JSON text 채널이며 실제 JDK 클라이언트로 연결·구독·방송·heartbeat·구독 해제를 검증한다. 시세 스트리밍은 시작 요청 후에만 외부 시세를 조회한다.

원본의 일부 health는 실제 외부 연결 없이 healthy를 반환했다. 대상 DB는 SELECT 1, JVM/OS는 실제 값을 사용하고 미설정 외부 연동은 skipped/degraded로 구분한다. Python 객체/RSS와 정확한 cache byte 등 직접 대응하지 않는 값은 null이며 별도 JVM heap 필드를 제공한다. GC 호출은 회수량 보장이 아닌 요청이다.

JDBC 프록시는 실제 statement 실행 시간을 관측하고 prepared SQL의 바인딩 값은 수집하지 않는다. 최근 관측·미저장 slow query는 각각 10,000개까지 보관한다. Hikari 활성/전체 연결은 실제 값을 쓰되 checkout 누적 통계는 null이다. information_schema/performance_schema 권한이 부족하면 index size/uses/score는 null로 구분한다. 원본이 존재하지 않는 컬럼에 대해 추천하는 index SQL도 검토용 문자열로만 제공하며 실행하지 않는다. 격리 MySQL에서 slow query flush·집계·31일 cleanup과 index 조회를 확인했다.

## 복구·비동기 분석의 원본 결함과 경계

복구는 원본의 일봉 DECIMAL 스키마·symbol/date 유니크 제약·행별 저장·부분 실패 집계·주말 제외 누락 조회·연도별 Yahoo 조회를 유지한다. force_update도 원본처럼 기존 행을 덮어쓰지 않고 재수집 후 중복으로 센다. 복구용 신호 생성은 일반 실시간 신호와 다른 기존 MA20/200 상향·RSI·Bollinger 규칙 및 1day timeframe을 유지한다. test_outcomes cleanup은 원본 handler에서 허용하지만 service가 처리하지 않아 대상에서도 삭제하지 않는다.

원본 recovery response_model은 handler의 실제 필드와 달라 직렬화에 실패한다. 대상은 handler의 실행 결과 구조를 응답한다. 유효하지 않은 지원 심볼/cleanup 확인 누락은 기존 HTTP 500을 유지하고, query 범위 검증은 422다. 배경 실행은 인프로세스이며 재시작 시 작업/조회 상태가 사라진다.

비동기 기술 분석의 SMA/EMA는 개별 지표 endpoint의 마지막 값과 달리 원본처럼 전체 index→값 시계열을 반환한다. 전체 분석의 save_results hook은 원본에도 DB 쓰기가 없어 로그만 남긴다. 원본 배경 분석이 요청에 없던 우선 심볼 3개를 추가하는 순서도 유지한다. 존재하지 않는 enqueue_task 호출과 잘못된 상태 조회 메서드는 실행 가능한 JVM 배경 작업/상태 저장으로 대체했다. 기술 스트리밍의 원본에 없는 broadcast 메서드는 실제 WebSocket 구독 채널과 연결했다.

현재 지표 상태 경로는 원본에 호출 대상 get_current_technical_status 메서드가 없었다. 대상은 기존 timeframe 검증을 유지하고 1min/15min/1day에 맞는 실제 시세·지표 계산 결과를 제공한다. 비유한 지표는 다른 JSON 경로처럼 null이다.

REST CORS 설정을 이전했다. security.cors-origins로 쉼표 구분 origin을 지정할 수 있다. 기본 wildcard+credentials에서는 Spring의 origin pattern으로 실제 origin을 반사해 브라우저의 credentials 규칙에 맞춘다. 원본의 무자격 wildcard 응답과 이 세부 동작은 다르다.

가격의 입력 경계는 구분한다. AsyncPriceService 경로는 Yahoo meta.regularMarketPrice를, 동기 YahooPriceClient 경로는 최근 1분 quote.close의 마지막 유효 값과 30초 캐시를 사용한다. 결과 추적과 동기 스케줄러도 후자의 값으로 계산한다. 두 값이 다른 로컬 응답을 주어 혼용을 회귀 검증했다. 원시 데이터 1행은 수익률 계산에 부족해도 data_available=true로 집계하며, 분석 성공 개수와 별도로 센다.

동기 Yahoo 조회는 HTTP 429에만 최대 3회 재시도하고, 비동기 시세 경로는 기존처럼 즉시 부분 실패로 처리한다. 재시도 지연은 원본의 무작위 2~5초 대신 기본 3초(`market.yahoo-retry-delay-ms`)이며 로컬 검증에서는 0으로 설정했다.

## JVM ML 학습·추론

Keras의 LSTM gate·dropout·BatchNorm·공통 Dense·7/14/30일 head를 DJL 0.36.0/PyTorch 2.7.1 CPU의 실제 역전파로 이전했다. 공식 지원 버전/CPU 패키지는 https://djl.ai/engines/pytorch/pytorch-engine/ 와 Maven Central 아티팩트로 확인했다. macOS ARM과 Linux x86 CPU에서 빌드되는 classifier를 지정하며 다른 플랫폼은 별도 확인 대상이다.

원본 pandas 특성 시계열·scaler와 합성 Keras 출력 대조, 실제 학습 loss/gradient·dropout·validation 조기종료, 모델 저장/복원·checksum 거절·5개 HTTP 경로·MySQL 저장을 검증했다. volume은 가격이 없는 날짜를 calendar join으로 추가해 float64로 승격되는 경우에만 float32로 바꾸고, 날짜가 완전한 int64 원본은 큰 거래량 정밀도를 유지한다. 서비스의 validation 분할을 callback에도 전달한다.

기존 .keras/.pkl은 서비스가 직접 실행하지 않는다. 학습 당시 feature 순서가 있는 소유 파일을 명시적으로 JSON artifact로 변환할 수 있으며 절차·실제 검증·원본 차이는 tools/ml/README.md에 기록했다. 모든 기존 모델의 자동 호환이나 TensorFlow와 bit 단위로 같은 학습 결과를 주장하지 않는다. confidence는 원본과 같은 반복 추론 변동성 지표이며 보정된 성공 확률이 아니다. 원본의 전체 sequence scaler fit 후 분할에 따른 데이터 누수 한계도 기록했다.

## 뉴스·번역·감정 분석·알림

Yahoo/Investing RSS와 원문 SHA256 중복 검사·첫 기사 처리·300초 캐시·번역·저장·선택 알림을 이전했다. RSS XML은 외부 엔티티를 해석하지 않는다. 기본 Google 웹 번역은 원본 deep-translator와 같은 query/HTML selector 순서로 직접 요청하며, 테스트는 로컬 HTML 서버를 사용한다. 선택 translation.url HTTP adapter도 제공한다. 실패하면 원본처럼 빈 번역을 저장하며 감정 분석은 로컬에서 수행한다.

금융 단어를 추가한 VADER 3.3.2 규칙·사전은 MIT 라이선스와 함께 Kotlin으로 이전했고 원본 서비스로 생성한 119개 문장의 수치와 대조했다. Python은 fixture 생성 도구에만 사용한다. Telegram은 communications.telegram.enabled=true와 base-url/token/chat-id가 있을 때만 발송하며 개발 검증에서는 로컬 서버만 사용한다. 성공 응답을 확인한 뒤 message_logs를 저장하고 최초 요청의 DB 초기화 누락을 수정했다. 원본 4096자 입력과 DB VARCHAR(1000) 충돌은 발송 전에 1000자 초과를 422로 거절한다.

원본의 all-alerts가 없는 메서드를 호출하는 결함은 실제 알림 4개 유형으로 연결하고, ma/rsi/env-check의 DTO 불일치는 handler 응답 구조로 수정했다. 설정 존재 여부를 실제 자격증명 검증으로 표현하지 않는다. opt-in 뉴스 알림은 신규 기사를 커밋한 후 발송하며 발송 실패 때문에 기사를 롤백하거나 중복 재발송하지 않는다. 원본과 차이는 로컬 HTTP·RSS·실제 MySQL 회귀 테스트로 구분한다.
