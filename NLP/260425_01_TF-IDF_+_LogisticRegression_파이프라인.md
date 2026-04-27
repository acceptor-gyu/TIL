# TF-IDF + LogisticRegression 파이프라인

## 개요

텍스트 분류(Text Classification) 모델을 만들 때 가장 먼저 시도해볼 수 있는 고전적인 베이스라인 조합이 TF-IDF + 로지스틱 회귀(Logistic Regression)다. scikit-learn의 `Pipeline` API를 사용하면 두 단계를 하나의 객체로 묶어 데이터 누수(data leakage) 없이 전처리-학습-예측을 일관되게 관리할 수 있다. 딥러닝 모델 대비 학습 속도가 매우 빠르고 해석 가능성이 높아, 실무에서도 첫 번째 솔루션으로 자주 사용된다.

## 상세 내용

### 1. TF-IDF란 무엇인가

TF-IDF는 Term Frequency - Inverse Document Frequency의 약자로, 문서 내 특정 단어의 중요도를 수치화하는 방법이다. 단순 빈도(Bag of Words)와 달리 모든 문서에 자주 등장하는 단어(the, is, 은, 는 등)의 가중치를 자동으로 낮추고, 특정 문서에서만 자주 등장하는 단어에 높은 가중치를 부여한다.

#### 수식

```
TF(t, d)  = 문서 d에서 단어 t의 등장 횟수 / 문서 d의 총 단어 수
IDF(t)    = log((1 + n) / (1 + df(t))) + 1    # scikit-learn의 smooth_idf=True 기준
TF-IDF(t, d) = TF(t, d) × IDF(t)
```

- **n**: 전체 문서 수
- **df(t)**: 단어 t가 등장한 문서 수
- `smooth_idf=True`는 분모에 1을 더해 분모가 0이 되는 것을 방지한다
- 최종적으로 각 문서 벡터는 L2 정규화(norm='l2')를 적용해 크기를 1로 만든다

#### BoW와의 차이

| 방법 | 특징 | 단점 |
|------|------|------|
| **Bag of Words** | 단어 출현 횟수만 반영 | "the", "is" 같은 불용어 과대 반영 |
| **TF-IDF** | 문서 간 희귀도를 반영한 가중치 부여 | 단어 순서, 문맥 반영 불가 |

---

### 2. scikit-learn TfidfVectorizer 핵심 파라미터

```python
from sklearn.feature_extraction.text import TfidfVectorizer

vectorizer = TfidfVectorizer(
    max_features=10_000,      # 상위 빈도 n개 단어만 사용 (메모리 절약)
    ngram_range=(1, 2),       # 단어 1개 + 연속 2개 단위까지 특성으로 사용
    min_df=2,                 # 2개 미만 문서에 등장한 단어 무시 (희귀 단어 제거)
    max_df=0.9,               # 90% 이상 문서에 등장한 단어 무시 (불용어 효과)
    sublinear_tf=True,        # tf → 1 + log(tf)로 변환 (빈도 스케일 압축)
    stop_words='english',     # 영어 불용어 제거 (한국어는 직접 리스트 지정)
    norm='l2',                # 각 문서 벡터를 L2 정규화 (기본값)
)
```

| 파라미터 | 역할 |
|----------|------|
| `max_features` | 가장 빈도 높은 n개 단어만 사용. 고차원 특성 공간을 제어 |
| `ngram_range=(1, 2)` | 유니그램 + 바이그램 모두 사용. "not good" 같은 문맥 포착 가능 |
| `min_df` | 극히 희귀한 단어 제거. 노이즈 감소 |
| `max_df` | 너무 흔한 단어 제거. 불용어 효과와 유사 |
| `sublinear_tf` | 빈도 차이를 로그 스케일로 압축. "the"가 100번 등장해도 1번과 극단적 차이를 만들지 않음 |

---

### 3. LogisticRegression 핵심 파라미터

텍스트 분류에서 LogisticRegression을 사용할 때 중요한 파라미터는 다음과 같다.

```python
from sklearn.linear_model import LogisticRegression

clf = LogisticRegression(
    C=1.0,            # 정규화 강도의 역수. 작을수록 강한 정규화 (과적합 방지)
    solver='saga',    # sparse 데이터에 적합한 solver
    max_iter=1000,    # 텍스트 데이터는 수렴에 더 많은 반복이 필요
    n_jobs=-1,        # 모든 CPU 코어 활용 병렬 처리
    random_state=42,
)
```

#### Solver 선택 기준

| Solver | L1 | L2 | Elastic-Net | 특징 |
|--------|----|----|-------------|------|
| `lbfgs` | X | O | X | 소/중규모 데이터, 기본값 |
| `saga` | O | O | O | sparse 대규모 데이터에 최적 |
| `liblinear` | O | O | X | 소규모 데이터, 이진 분류 |

TF-IDF 결과는 극도로 희소(sparse)한 행렬이기 때문에 `saga` solver가 수렴 속도 면에서 유리하다.

#### C 파라미터 (정규화)

- **C가 크면**: 정규화 약함 → 학습 데이터에 더 잘 맞춤 → 과적합 위험
- **C가 작으면**: 정규화 강함 → 더 단순한 모델 → 과소적합 위험
- 실무에서는 `LogisticRegressionCV`로 교차 검증을 통해 최적 C를 자동 탐색하는 것을 권장한다

---

### 4. Pipeline으로 묶기

scikit-learn `Pipeline`은 여러 변환 단계와 최종 추정기를 순서대로 연결하는 API다. 핵심 이점은 `fit()`을 학습 데이터에만 수행하고, `transform()`은 학습/테스트 모두에 일관되게 적용되어 **데이터 누수(data leakage)**를 방지한다는 점이다.

```python
from sklearn.pipeline import Pipeline
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.model_selection import train_test_split
from sklearn.metrics import classification_report

# 데이터 분리
X_train, X_test, y_train, y_test = train_test_split(
    texts, labels, test_size=0.2, random_state=42, stratify=labels
)

# 파이프라인 구성
pipeline = Pipeline([
    ("tfidf", TfidfVectorizer(
        max_features=10_000,
        ngram_range=(1, 2),
        min_df=2,
        sublinear_tf=True,
    )),
    ("clf", LogisticRegression(
        C=1.0,
        solver="saga",
        max_iter=1000,
        n_jobs=-1,
        random_state=42,
    )),
])

# 학습
pipeline.fit(X_train, y_train)

# 평가
y_pred = pipeline.predict(X_test)
print(classification_report(y_test, y_pred))
```

Pipeline에서 각 단계의 이름은 `__`(더블 언더스코어)로 파라미터를 참조할 수 있다. 예를 들어 `tfidf__ngram_range`는 TfidfVectorizer의 `ngram_range` 파라미터를 가리킨다.

---

### 5. GridSearchCV / RandomizedSearchCV로 하이퍼파라미터 탐색

Pipeline과 GridSearch를 결합하면 전처리 파라미터와 모델 파라미터를 함께 최적화할 수 있다.

```python
from sklearn.model_selection import RandomizedSearchCV
import numpy as np

param_distributions = {
    "tfidf__max_features": [5_000, 10_000, 50_000],
    "tfidf__ngram_range": [(1, 1), (1, 2)],
    "tfidf__min_df": [1, 2, 5],
    "tfidf__sublinear_tf": [True, False],
    "clf__C": np.logspace(-2, 2, 10),   # 0.01 ~ 100 사이 10개
    "clf__solver": ["saga", "lbfgs"],
}

search = RandomizedSearchCV(
    estimator=pipeline,
    param_distributions=param_distributions,
    n_iter=30,
    cv=5,
    scoring="f1_weighted",
    n_jobs=-1,
    random_state=42,
    verbose=1,
)

search.fit(X_train, y_train)

print("Best params:", search.best_params_)
print("Best CV score:", search.best_score_)
```

#### GridSearch vs RandomizedSearch 선택

| | GridSearchCV | RandomizedSearchCV |
|-|---|---|
| 탐색 방식 | 모든 조합 | 랜덤 n개 조합 |
| 파라미터 많을 때 | 비실용적 | 효율적 |
| 연속 파라미터 (C 등) | 격자 한계 | 연속 분포 사용 가능 |
| 사용 권장 | 파라미터 3개 이하 | 파라미터 4개 이상 |

---

### 6. 모델 해석 - 중요 특성 확인

로지스틱 회귀는 각 특성(단어)에 가중치(coef_)를 부여하기 때문에 모델의 판단 근거를 직접 확인할 수 있다.

```python
import numpy as np

# 학습된 파이프라인에서 각 구성 요소 추출
vectorizer = pipeline.named_steps["tfidf"]
classifier = pipeline.named_steps["clf"]

feature_names = vectorizer.get_feature_names_out()

# 이진 분류 기준 (클래스가 2개일 때)
coefs = classifier.coef_[0]
top_positive = np.argsort(coefs)[-10:][::-1]  # 긍정 방향 상위 10개
top_negative = np.argsort(coefs)[:10]          # 부정 방향 상위 10개

print("긍정 예측에 기여한 단어:")
for idx in top_positive:
    print(f"  {feature_names[idx]}: {coefs[idx]:.4f}")

print("\n부정 예측에 기여한 단어:")
for idx in top_negative:
    print(f"  {feature_names[idx]}: {coefs[idx]:.4f}")
```

---

### 7. 모델 저장과 배포

학습이 완료된 Pipeline은 직렬화하여 저장하고 재사용할 수 있다.

```python
import joblib

# 저장
joblib.dump(pipeline, "text_classifier.pkl")

# 로드 후 사용
loaded_pipeline = joblib.load("text_classifier.pkl")
predictions = loaded_pipeline.predict(["새로운 텍스트 입력"])
```

`joblib`은 numpy 배열과 sparse 행렬을 효율적으로 직렬화하기 때문에 `pickle`보다 텍스트 분류 모델 저장에 적합하다.

---

### 8. 성능 평가 지표 선택

| 지표 | 사용 시점 |
|------|-----------|
| **Accuracy** | 클래스 분포가 균형 잡힌 경우 |
| **F1 (weighted)** | 클래스 불균형이 있을 때, 클래스별 지지도(support) 반영 |
| **F1 (macro)** | 모든 클래스를 동등하게 평가하고 싶을 때 |
| **ROC-AUC** | 이진 분류에서 임계값 독립적 성능 평가 |
| **Precision / Recall** | 오탐(False Positive) vs 미탐(False Negative) 비용이 다를 때 |

스팸 분류처럼 정상 메일을 스팸으로 잘못 분류하는 비용이 높다면 Precision에 집중하고, 스팸을 놓치는 비용이 높다면 Recall에 집중해야 한다.

---

### 9. TF-IDF + LR의 한계와 대안

| 한계 | 설명 | 대안 |
|------|------|------|
| 단어 순서 무시 | "not good"과 "good not"을 동일하게 처리 | n-gram 범위 확장 또는 BERT |
| 의미적 유사도 미반영 | "happy"와 "joyful"을 다른 단어로 취급 | Word Embedding, BERT |
| 문맥 이해 불가 | 동일 단어의 다의어 처리 불가 | Contextual Embedding |
| OOV 취약 | 학습 시 본 적 없는 단어 처리 불가 | FastText, BERT |

그럼에도 TF-IDF + LR은 다음과 같은 상황에서 실질적인 첫 번째 선택이다.
- 레이블이 부족해 파인튜닝이 어려운 경우
- 빠른 학습과 예측이 필요한 경우
- 모델 해석 가능성이 요구되는 경우 (규제, 감사 등)
- 베이스라인 성능 빠르게 확인이 필요한 경우

---

## 핵심 정리

- TF-IDF는 단어 빈도(TF)와 역문서 빈도(IDF)를 곱해 희귀하지만 중요한 단어에 높은 가중치를 부여한다
- scikit-learn `Pipeline`은 TfidfVectorizer와 LogisticRegression을 하나로 묶어 데이터 누수 없이 학습/예측을 관리한다
- `ngram_range=(1, 2)`, `sublinear_tf=True`, `min_df`/`max_df` 설정으로 벡터라이저 성능을 크게 개선할 수 있다
- 텍스트 분류에서는 sparse 데이터에 최적화된 `saga` solver를 사용하고, `C` 파라미터는 교차 검증으로 탐색한다
- `Pipeline`과 `RandomizedSearchCV`를 결합하면 전처리-모델 파라미터를 통합 최적화할 수 있다
- `coef_` 속성으로 각 단어의 기여도를 직접 확인할 수 있어 해석 가능성이 높다
- TF-IDF + LR은 빠른 베이스라인 구축에 적합하지만, 문맥 이해가 필요하면 BERT 계열로 전환을 고려한다

## 키워드

- **TF-IDF (Term Frequency - Inverse Document Frequency)**: 단어 빈도(TF)와 역문서 빈도(IDF)의 곱으로 단어의 중요도를 측정하는 통계 기법. 많은 문서에 공통으로 등장하는 단어의 가중치를 자동으로 낮춰 문서 특유의 핵심 단어를 부각시킨다.
- **TfidfVectorizer**: scikit-learn에서 원시 텍스트를 TF-IDF 행렬로 변환하는 클래스. CountVectorizer + TfidfTransformer를 하나로 합친 것과 동일하다. `max_features`, `ngram_range`, `sublinear_tf` 등의 파라미터로 세밀하게 제어할 수 있다.
- **LogisticRegression**: 선형 모델 기반의 분류 알고리즘. 텍스트 분류에서 sparse 벡터와 궁합이 좋고, 각 특성(단어)의 가중치를 `coef_`로 직접 확인할 수 있어 해석 가능성이 높다.
- **Pipeline**: 여러 변환 단계와 최종 추정기를 하나의 객체로 연결하는 scikit-learn API. `fit()`은 학습 데이터에만 적용되므로 데이터 누수를 방지한다. 각 단계 파라미터는 `단계명__파라미터명` 형식으로 참조한다.
- **n-gram**: 연속된 n개의 토큰 시퀀스. `ngram_range=(1, 2)`는 단일 단어(유니그램)와 연속 2개 단어(바이그램)를 모두 특성으로 사용한다. "not good" 같은 부정 표현을 하나의 특성으로 포착할 수 있다.
- **sublinear_tf**: TF를 `1 + log(TF)`로 변환하는 옵션. 단어가 100번 등장해도 1번 등장한 단어와 극단적인 차이를 만들지 않도록 빈도 스케일을 압축한다. 텍스트 분류에서 성능 향상에 도움이 된다.
- **saga solver**: scikit-learn LogisticRegression의 solver 중 하나. L1, L2, Elastic-Net 정규화를 모두 지원하며, TF-IDF처럼 sparse 고차원 데이터에서 수렴 속도가 빠르다.
- **데이터 누수 (Data Leakage)**: 학습 데이터 이외의 정보(테스트 데이터 포함)가 모델 학습에 영향을 주는 문제. TfidfVectorizer를 `fit`할 때 테스트 데이터를 포함하면 IDF 계산이 왜곡된다. Pipeline을 사용하면 자동으로 방지된다.
- **RandomizedSearchCV**: 하이퍼파라미터 공간에서 무작위로 조합을 샘플링하는 탐색 방법. GridSearchCV 대비 탐색 비용이 낮고, 연속 분포(np.logspace 등)를 파라미터로 지정할 수 있어 파라미터가 많은 경우에 유리하다.
- **coef_**: scikit-learn 선형 모델의 특성별 가중치 배열. TF-IDF + LR 조합에서는 각 단어가 각 클래스 예측에 얼마나 기여하는지 직접 확인할 수 있어 모델 해석(Model Interpretability)에 활용된다.

## 참고 자료

- [scikit-learn TfidfVectorizer 공식 문서](https://scikit-learn.org/stable/modules/generated/sklearn.feature_extraction.text.TfidfVectorizer.html)
- [scikit-learn LogisticRegression 공식 문서](https://scikit-learn.org/stable/modules/generated/sklearn.linear_model.LogisticRegression.html)
- [scikit-learn Pipeline 공식 예제: 텍스트 특성 추출 및 평가](https://scikit-learn.org/stable/auto_examples/model_selection/plot_grid_search_text_feature_extraction.html)
- [scikit-learn 희소 특성 기반 문서 분류 예제](https://scikit-learn.org/stable/auto_examples/text/plot_document_classification_20newsgroups.html)
- [Text Classification: Baseline with TF-IDF and Logistic Regression](https://medium.com/@ryblovartem/text-classification-baseline-with-tf-idf-and-logistic-regression-2591fe162f3b)
