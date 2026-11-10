# Finstage Market Data Kotlin

Python·FastAPI 기반 Finstage-Market-Data를 Kotlin·Spring MVC로 이전한 시장 데이터 서비스다. 원본에 등록된 HTTP 122개 경로와 WebSocket 1개 경로, MySQL 19개 테이블·271개 컬럼을 기준으로 구현·검증했다. 원본 결함 수정과 수치/운영 경계의 차이는 [설계 결정](docs/decisions.md)에 기록한다.

## 실행

Kotlin 2.3.21, Spring Boot 4.1.2, JDK 21, Gradle 9.8.2. 기본 포트는 8000, Swagger는 `/docs`, OpenAPI는 `/openapi.json`이다.

```bash
export DATABASE_URL='jdbc:mysql://localhost:3306/finstage_market_data'
export MYSQL_USER='finstage'
export MYSQL_PASSWORD='your-local-password'
./gradlew bootRun
```

기존 MySQL 스키마를 읽으며 자동 DDL을 실행하지 않는다. 새 테스트 DB의 원본 DDL은 `src/main/resources/db/reference-schema.mysql.sql`이다. 이 파일은 기존 DB에 자동 적용하는 마이그레이션이 아니다.

## 기능과 설정

- 회사/재무·시세·OHLCV·기술 지표·신호·패턴/군집·성과/결과 추적·일봉 복구·리포트.
- 우선순위 작업 큐·운영/메모리/DB 관측·실시간 JSON WebSocket.
- Yahoo/Investing RSS·직접 Google 웹 번역·금융 VADER와 선택 알림.
- 실제 JVM LSTM 학습·추론·모델/예측 저장. native CPU 플랫폼·기존 Keras/scaler 변환은 [ML 안내](tools/ml/README.md) 참고.

`security.cors-origins`는 쉼표 구분 origin 설정이다. Yahoo 경계는 `market.yahoo-base-url`, 동기 경로의 429 지연은 `market.yahoo-retry-delay-ms`로 설정한다.

예약 작업은 기본 비활성화다. `scheduler.enabled=true`에서 원본의 활성 18개 작업이 180초 간격으로 실행된다. 작업/큐 소유권은 단일 JVM 범위이며 재시작 시 메모리 작업 상태가 사라진다. ML 수집 심볼은 원본 최종 빈 맵을 유지하고 `scheduler.ml-symbols`로 지정할 수 있다.

Telegram은 `communications.telegram.enabled=true`와 `communications.telegram.base-url/token/chat-id`를 명시해야 발송한다. RSS 대체 주소는 `communications.news.rss-base-url/investing-base-url`, Google 대체 주소는 `communications.translation.google-url`, 선택 번역 어댑터는 `communications.translation.url`이다.

## 검증

```bash
./gradlew build
python3 tools/verify_mysql_schema.py
python3 scripts/packaged-smoke.py
```

`build`는 lint·단위/HTTP·원본 수치 fixture·실제 WebSocket·전체 경로 대조를 실행한다. 기본 실행에서 MySQL 전용 8개 테스트는 환경 변수가 필요해 건너뛰며, CI의 격리 MySQL matrix에서 실제 실행한다.

`packaged-smoke.py`는 새 MySQL에 원본 DDL과 합성 가격을 준비하고 bootJar를 실제 실행한다. HTTP 조회·native JVM 학습/예측·3개 예측 저장을 확인한 뒤 서버·DB·임시 모델을 정리한다. 운영 외부 시세·알림을 호출하지 않는다.

Docker가 있는 환경에서 `scripts/test-mysql.sh`, `test-analysis-mysql.sh`, `test-operations-mysql.sh`, `test-recovery-mysql.sh`, `test-ml-mysql.sh`, `test-communications-mysql.sh`, `test-scheduler-mysql.sh`, `test-reporting-mysql.sh`를 각각 실행할 수 있다. 모든 스크립트는 새 DB 컨테이너와 테스트 자격증명을 사용하고 종료 시 정리한다.

외부 시세/번역/알림 검증은 로컬 HTTP/RSS 서버를 사용했다. 학습은 새 합성 데이터·모델로 실제 수행했으며 운영 데이터·원본 비밀 파일을 복사하지 않았다.

[원본 계약 목록](docs/source-inventory.md) · [검증 상태와 한계](docs/migration-status.md)
