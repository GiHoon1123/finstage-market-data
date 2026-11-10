# Finstage Market Data Kotlin

Python·FastAPI 기반 Finstage-Market-Data를 Kotlin·Spring MVC로 이전하는 저장소.

## 실행

JDK 21에서 `./gradlew bootRun`. 기본 포트 8000, Swagger `/docs`, OpenAPI `/openapi.json`.
기존 MySQL 스키마에 `DATABASE_URL`, `MYSQL_USER`, `MYSQL_PASSWORD`로 연결한다.
기존 데이터를 변경하는 자동 DDL은 실행하지 않는다. 외부 알림·주문은 검증 중 실행하지 않는다.

## 검증과 범위

`./gradlew build`로 ktlint와 테스트를 실행한다. 원본은 `../Finstage-Market-Data`에서 읽는다.
[원본 API·테이블 목록](docs/source-inventory.md), [설계 결정](docs/decisions.md), [이전 상태](docs/migration-status.md)를 참고한다.
원본에 ML·크롤링·스케줄러·운영 API가 포함돼 있어 모듈별로 순차 이전 중이다. 전체 이전 완료를 의미하지 않는다.
