# 마이그레이션 상태

## 구현 범위

원본 커밋 `afb74022953de00a5fe74f38c6223f236c79b0f3`의 main에 등록된 HTTP 122개·WebSocket 1개 경로를 이전했다. `RouteCoverageTest`가 실제 Spring handler와 원본 inventory를 대조하며 WebSocket은 실제 연결로 확인한다. 원본의 선언만 있고 마운트되지 않은 helper를 새 공개 API로 임의 추가하지 않는다.

- 회사·재무·시세·기술 지표·신호 조회/통계·패턴·고급 군집·성과·결과 추적.
- 일봉 저장/복구·비동기 분석/스트리밍·현재 상태·CORS·검증 오류 처리.
- 작업 큐 15개 경로와 원본 예제 5개 작업. 예제의 난수 데이터를 실제 시세라고 표현하지 않는다.
- 운영/메모리/DB/WebSocket 37개 경로와 JDBC 관측·slow query 저장/복구.
- 뉴스·메시지/테스트 알림 8개 경로, Yahoo/Investing RSS·직접 Google 번역·금융 VADER.
- ML 5개 경로와 실제 JVM LSTM 학습/추론·scaler·모델/예측 저장·Keras 변환 도구.
- 일일 리포트 4개 경로·scheduler 포트, 실제 활성 18개 예약 작업.

## 검증

- 전체 `./gradlew build`: 99개 테스트 중 91개 통과, 실패 0. MySQL 전용 8개는 기본 실행에서 환경 변수로 분리한다.
- MySQL 8.0: 원본 19개 테이블·271개 컬럼 생성/순서 확인. API/JDBC·분석·운영·복구·ML·뉴스·스케줄러·리포트를 새 컨테이너에서 별도 실행했다. CI matrix도 같은 스크립트로 검증한다.
- 원본 pandas 지표 4개 전체 시계열, ML 특성/scaler·새 Keras 출력, VADER 119문장, 보고서/insight 3개 시나리오를 대조했다.
- 실제 로컬 WebSocket 연결·구독·방송·heartbeat, 로컬 HTTP/RSS 외부 경계·중복·실패 조건을 검증했다.
- 실제 bootJar·격리 MySQL·HTTP·native JVM 학습/추론·3개 예측 저장을 확인하고 테스트 서버/모델/DB를 정리했다.
- CI 통과 후 모듈 PR을 순서대로 main에 병합한다. 개별 PR의 GitHub Actions가 실제 실행 근거다.

## 구분한 한계

기존 Keras/scaler는 학습 당시 feature 순서가 있는 소유 파일을 명시적으로 변환해야 한다. 모든 기존 모델 자동 호환·학습 bit 일치·보정된 예측 신뢰도를 주장하지 않는다. 원본의 시연값·무동작 저장 hook·잘못된 DTO/helper와 해결 여부는 decisions.md 및 tools/ml/README.md에 구분한다.

스케줄러/큐/락은 프로세스 내부다. 운영 외부 서비스·실제 자격증명·전체 운영 데이터 snapshot의 동일성이나 운영 부하 성능은 이번 로컬/CI 검증 범위가 아니다. 정책과 플랫폼 차이는 숨기지 않고 명시한다.
