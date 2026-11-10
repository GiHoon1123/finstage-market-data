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
