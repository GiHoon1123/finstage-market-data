# JVM ML 이전·검증

운영 경로는 Kotlin → DJL 0.36.0 → PyTorch 2.7.1 CPU다. Python 서버 호출·가짜 예측·원본 모델을 이용한 테스트는 없다. TensorFlow와 pandas는 **새 합성 fixture 생성과 명시적인 일회성 파일 변환**에만 사용한다.

## 실제 구현

- Keras 순서의 LSTM gate i/f/c/o, unit forget bias, orthogonal recurrent kernel, timestep 전체에서 유지되는 gate별 input/recurrent dropout mask.
- LSTM별 BatchNorm(모집단 분산, epsilon .001, moving momentum .99), 공통 Dense, 7/14/30일 각각 Dense16→linear head.
- 전체 LSTM·BN·Dense 역전파, 가중 MSE(.5/.3/.2), Adam(epsilon1e-7), epoch shuffle, validation EarlyStopping(patience10·최고 가중치 복구), ReduceLR(patience5·.5·최소1e-7).
- 원본 pandas 계산의 가격·volume·technical·calendar·lag·rolling 특성, adjust=true EWM, rolling ddof=1, ffill/bfill/0. 원본 순서의 89개 feature를 합성80행 전체에서 비교했다.
- 실제 DB 가격·technical signal·calendar outer join과 raw float32/IQR 처리, 선택적인 sentiment 집계·파생 특성. 학습에 쓰인 순서를 artifact에 저장하고 예측 때 정렬한다.
- minmax·standard·robust scaler와 역변환. JSON 숫자·배열만 읽는 portable artifact, SHA256 검증·임시 파일+원자 이동.
- 원본 `ml_models`·`ml_predictions`의 메타데이터·활성 모델·3기간 결과 저장. 5개 mounted route train/predict/status/health/info 유지.

## 실행

공통 Gradle 빌드가 테스트를 실행한다. 실제 PyTorch CPU 라이브러리는 Gradle의 macOS ARM/Linux x86 classifier로 로딩된다. 모델 경로는 `market.ml.model-directory`(기본 models/ml_prediction)로 지정한다.

```bash
./gradlew test --tests '*Ml*Test'
```

HTTP 훈련의 `hyperparameters`에서 epochs·batch_size·learning_rate·lstm_units·dense_units·window_size·dropout_rate·activation을 지정할 수 있다. 기본값은 원본 60/[50,50]/[25]/.2/100epochs/32batch/.001이다. DB에 실제 가격이 없으면 오류를 반환한다. 테스트는 외부 시장 데이터를 조회하지 않는다.

`MlNetworkTest`는 TensorFlow 2.20의 새 작은 모델 가중치를 읽어 모든 출력 비교, 전체 계층의 실제 gradient 변화, loss 감소, dropout .2, validation plateau 조기종료, 저장·복원 결과를 확인한다. `MlHttpIntegrationTest`는 H2의 원본 ML 컬럼과 새 150일 가격에서 실제 train→save→activate→skip→reload→predict→3행 저장·checksum 변조 거절을 검증한다. `MlMySqlTest`는 지정한 격리 MySQL의 원본 DDL로 같은 데이터 경로를 확인한다.

## 기존 Keras 파일의 명시적인 일회성 변환

JVM은 `.keras`나 실행 가능한 `.pkl`을 직접 읽지 않는다. 소유한 신뢰 가능한 파일만 아래 도구로 변환한다. **학습 당시 feature 이름의 정확한 순서가 필수**다. 원본 scaler는 이름 없는 numpy 배열에 fit되어 scaler만으로 순서를 복원할 수 없다. 순서를 추측해 0으로 채운 모델을 호환 모델이라고 표시하지 않는다.

```bash
python tools/ml/export_keras_artifact.py \
  --model /owned/bundle/model.keras \
  --scalers /owned/bundle/scalers \
  --features /owned/bundle/ordered-feature-names.json \
  --symbol '^GSPC' --version v1.0.0 \
  --output /new/bundle/model.jvm.json
```

변환 환경은 TensorFlow2.20·numpy·scikit-learn을 사용한다. exporter는 Keras safe_mode로 읽고, 소유한 scaler pickle을 명시적으로 읽는다. 기존 출력은 덮어쓰지 않는다. `ml_models.model_path/model_format/checksum`은 변환 파일·djl-json·SHA256에 맞춰 별도 전환해야 한다. 이 도구는 DB를 자동 변경하지 않는다. 새 합성 Keras+scaler bundle을 직접 만들고 변환한 `exported-keras-artifact.json`을 JVM에서 읽어 원본 TensorFlow 출력과 scaler 역변환을 비교한다.

fixture 재생성 도구: `export_keras_fixture.py`, `export_feature_fixture.py`. 후자는 원본 파일 전체나 설정을 import하지 않고 순수 FeatureEngineer method의 AST만 실행하므로 원본 DB·키·캐시를 읽지 않는다. pandas2.2.3을 사용한다(원본 fillna(method=...)가 pandas3에서 삭제됨).

## 의도적으로 구분한 차이·한계

- 원본 API의 hyperparameters는 handler에서 전달되지 않았으나 Kotlin은 명시 입력을 적용한다. 원본 내부 split은 .7/.15/.15이며 API validation_split과 무관하다. 이 split을 유지했다.
- 원본 FeatureEngineer의 sentiment 기본 True가 요청 False를 무시하던 경로 대신, Kotlin은 요청 flag를 artifact에 저장하고 예측도 같은 flag를 쓴다.
- 원본 병렬 source 수집의 column order와 sentiment 유무에 따른 23/26개 차이는 기존 unnamed scaler만으로 복구되지 않는다. 신규 artifact는 feature 순서를 보존하고 없는 dynamic signal을 명시적으로 0으로 맞춘다. **기존 모든 model/scaler의 자동 호환을 주장하지 않는다.**
- 원본 scaler를 전체 sequence에 fit한 뒤 train/validation/test로 나누는 순서는 유지했다. 따라서 미래 구간의 분포가 scaler에 반영되는 데이터 누수 한계도 남는다.
- confidence는 원본처럼 inference 반복의 상대 표준편차다. dropout을 끈 같은 모델을 반복하면 거의 .9가 나오므로 통계적으로 보정된 성공 확률이 아니다.
- 수치적인 gate/BN/출력 fixture는 일치하지만 난수 초기화·shuffle·native 최적화까지 TensorFlow와 학습 결과가 bit 단위로 같다는 주장은 하지 않는다. BPTT와 callbacks는 JVM에서 실제 실행된다.
- 마운트되지 않은 별도 평가·백테스트·tracking helper 전체를 새 route로 추가하지 않았다. 학습 test split의 실제 MSE·MAE·방향 정확도는 저장한다. 뉴스 sentiment 집계와 다중 source/IQR 조합의 전체 원본 차등 fixture는 추가 검증 대상이다.
