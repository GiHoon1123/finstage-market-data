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
