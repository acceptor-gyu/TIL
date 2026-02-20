#!/bin/bash
# add-til-template 종료 후 자동으로 task-til 호출

# stdin으로 들어온 SubagentStop 이벤트 데이터 읽기
INPUT=$(cat)

# /tmp/til-created-file.txt 에서 생성된 파일명 읽기
if [ -f /tmp/til-created-file.txt ]; then
  FILENAME=$(cat /tmp/til-created-file.txt)

  if [ -n "$FILENAME" ]; then
    # Claude에게 다음 작업 지시
    echo "✅ 템플릿 생성 완료: $FILENAME"
    echo ""
    echo "이제 task-til 서브에이전트를 호출하여 내용을 작성합니다..."
    echo ""
    echo "CLAUDE_INSTRUCTION: Task tool을 사용하여 task-til 서브에이전트를 호출하세요. prompt에 다음을 포함하세요: '파일명: $FILENAME'"

    # 임시 파일 삭제
    rm -f /tmp/til-created-file.txt
  fi
fi

exit 0
