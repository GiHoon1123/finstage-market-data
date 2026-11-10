# 마이그레이션 상태

## 원본 분석

원본의 전체 파일·등록 API·DB 선언을 source-contracts.json과 source-inventory.md에 기록했다.

## 진행

- 공통 Spring MVC·Gradle·CI 구성: 완료, PR #1 CI 통과·병합
- 회사 심볼·재무제표 조회: 구현, HTTP+H2 테스트 4개 및 격리 MySQL 8.0 테스트 4개 통과. 422 세부 오류 문구·원본 country NULL 처리 차이는 decisions.md 참고
- 일봉·복구 7개 및 비동기 분석/현재 상태 10개 경로: 구현, H2/실제 MySQL 일봉·신호 저장 검증 통과. SMA·EMA·RSI·Bollinger·MACD·stochastic·VWAP와 신호 판정: 구현, 원본 pandas 시계열 표본 대조 통과
- 신호 조회·심볼 필터·타입별/일별/강도 통계 5개 경로: 구현, HTTP+H2 및 격리 MySQL 테스트 4개 통과. 패턴·고급 분석·결과 추적·성과/백테스트 24개 경로: 구현, 알고리즘/JDBC 테스트 10개 통과. MySQL 3개는 별도 스크립트로 실행. 보고: 예정
- 기존 async 시세 3개·기술 유틸리티 2개 경로 및 메모리 TTL 캐시: 구현, 추가 HTTP/캐시 검증 7개 통과
- ML 5개 API·실제 JVM 학습/추론·Keras/scaler 변환: 구현, 합성 원본 비교·HTTP·격리 MySQL 검증 통과. tools/ml/README.md 참고
- 뉴스·감정 분석: 후속 모듈 검증 완료, 커밋 준비 중
- 가격 history·배치 current·기술 지표 HTTP: 구현, 로컬 가짜 Yahoo 서버와 Spring HTTP 통합 테스트 5개 통과. 현재가·snapshot/high summary: 구현, 실제 DB 테이블 기반 HTTP 테스트 3개 통과. WebSocket: 구현·실제 연결 검증 통과. Telegram·스케줄러는 후속 모듈에서 검증 중
- 인프로세스 작업 큐 15개 API·5개 원본 예제 작업: 구현, 우선순위/FIFO·재시도 상한·시간 제한·정지/재시작·취소 및 HTTP 테스트 8개 통과. 원본 큐 결함 수정과 시연 함수의 한계는 decisions.md 참고
- 운영 모니터링·메모리·DB 관측·WebSocket 37개 경로: 구현, 실제 WebSocket/H2/실측 4개 및 격리 MySQL 2개 테스트 통과. Python 전용 측정·외부 health 차이는 decisions.md 참고

전체 호환 검증 전 원본/대상 저장소 이름을 바꾸지 않는다.

## 스키마 검증

- `python3 tools/verify_mysql_schema.py`: MySQL 8.0의 원본 19개 테이블·271개 컬럼 생성 및 이름/순서 대조 통과.
- `scripts/test-mysql.sh`: 회사·재무 4개, 신호 4개, 시세 요약 3개 실제 MySQL 테스트 통과.
- 동일 검증을 GitHub Actions mysql job으로 등록했다. CI의 실제 결과는 해당 PR 기록을 기준으로 한다.
