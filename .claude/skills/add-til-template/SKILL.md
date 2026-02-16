---
name: add-til-template
model: opus
description: 새로운 TIL(Today I Learned) 항목을 생성하고 제목에 적절한 내용을 template에 맞게 제목만 작성한다!
disable-model-invocation: true
argument-hint: [카테고리] [제목]
---

# 새로운 TIL 항목 추가

이 skill은 새로운 학습 항목을 프로젝트에 추가합니다.

## 수행 작업

1. **오늘 날짜 확인**: YYMMDD 형식으로 오늘 날짜 생성
2. **파일 번호 결정**: 같은 날짜의 기존 파일을 확인하여 다음 번호 부여
3. **새 파일 생성**: `YYMMDD_번호_제목.md` 형식으로 마크다운 파일 생성
4. **내용 작성**: 양식에 맞는 또는 관련 내용 중 추가로 알았으면 좋은 내용을 공식 문서를 참고해서 작성
5. **키워드 선정**: 작성한 제목 및 목차에 적절한 키워드를 5 ~ 10개 선정

## 사용법

```
/add-til-template [카테고리] [제목]
```

**예시:**
- `/add-til-template Database Redis 캐싱 전략`
- `/add-til-template Network TCP 혼잡 제어`
- `/add-til-template Algorithm 이진 탐색 트리`

## 인자 처리

- **$ARGUMENTS[0]** 또는 **$0**: 카테고리 (예: Database, Network, Algorithm)
- **$ARGUMENTS[1..]** 또는 **$1 이후**: 제목 (나머지 모든 단어를 제목으로 사용)

## 파일 템플릿

새로 생성되는 마크다운 파일은 다음 구조를 따릅니다:
- 필요한 경우 일부를 변경하여 활용해도 좋습니다.

```markdown
# [제목]

## 개요
[학습한 내용의 간단한 소개]

## 상세 내용
[본문]

## 핵심 정리
- 핵심 포인트 1
- 핵심 포인트 2

## 키워드

## 참고 자료
-
```

## 주의사항

- 카테고리가 README에 없으면 새로 생성
- 파일명은 자동으로 스네이크 케이스로 변환 (공백 → 언더스코어)
- 날짜 형식: YYYY.MM.DD

## 실행 후 확인

1. 새 마크다운 파일이 생성되었는지 확인
2사용자에게 생성된 파일 경로와 다음 단계 안내
