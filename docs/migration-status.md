# 마이그레이션 상태

## 원본 분석

원본의 전체 파일·등록 API·DB 선언을 source-contracts.json과 source-inventory.md에 기록했다.

## 진행

- 공통 Spring MVC·Gradle·CI 구성: 완료, PR #1 CI 통과·병합
- 회사 심볼·재무제표 조회: 구현, HTTP+H2 테스트 4개 및 격리 MySQL 8.0 테스트 4개 통과. 422 세부 오류 문구·원본 country NULL 처리 차이는 decisions.md 참고
- 일봉 저장·기술적 지표: 예정
- 패턴·신호·결과 추적·보고: 예정
- ML·학습 모델·뉴스·감정 분석: 예정
- 외부 데이터 수집·Telegram·스케줄러·WebSocket: 예정
- 운영 모니터링·메모리·작업 큐: 예정

전체 호환 검증 전 원본/대상 저장소 이름을 바꾸지 않는다.
