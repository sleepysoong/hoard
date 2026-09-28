# 세션 Todo 도구

Todo는 세션 실행 상태다. 대화 메시지, 모델 추론, 도구 호출 카드에서 상태를 재구성하지 않는다.
우선순위 필드와 기능은 제공하지 않는다.

## 저장과 수명

- 앱 전체에서 `filesDir/hoard-store.todos.db` 하나를 사용한다. `todos.session_id`로 세션을 구분한다.
- 기존 세션·메시지 JSON 저장 형식은 유지한다. 이번 변경은 대화 DB 전체 이관을 포함하지 않는다.
- `todo_sessions`는 유효한 세션의 등록부이고 `todos`는 실행 상태다. 삭제 시 외래 키 cascade로 할 일도 지운다.
- 새 세션/브랜치 생성 및 세션 삭제 때 기존 JSON을 즉시 flush한다. 두 저장소를 아우르는 단일 트랜잭션은 없으며,
  비정상 종료로 등록부에 남은 고아 행은 다른 세션에 노출하거나 자동으로 재연결하지 않는다.
- Todo 변경은 SQLite 트랜잭션으로 저장한다. 커밋 후에만 `StateFlow`와 `TodoUpdatedEvent(type="todo.updated")`를 갱신한다.
  느린 이벤트 구독자가 중간 알림을 놓쳐도 UI는 최신 `StateFlow` 스냅샷을 읽을 수 있다.
- `(session_id) WHERE status = 'in_progress'` 고유 인덱스가 동시 활성 작업을 DB에서도 차단한다.
- `position`은 생성 순서를 보존한다. 삭제 시 남은 항목을 재정렬하지 않는다. 우선순위 의미는 없다.
- 브랜치 생성 시 **그 시점의 미완료 항목**을 새 ID로 복제한다. 과거 메시지 시점으로 Todo를 되감지는 않는다.
- 앱 재시작 시 SQLite에서 복구한다. 메시지 편집·재생성·삭제 및 컨텍스트 자르기로 Todo는 바뀌지 않는다.

## 호출 계약

하나의 `todo` 도구를 제공한다. `op`으로 요청을 구분하고 실행 전에 연산별 필수·허용 필드를 검증한다.
JSON 스키마의 루트는 기존 라우터와 provider bridge가 처리하는 object 형식을 사용한다.

```json
{"op":"create","content":"세션 복원 흐름 구현"}
{"op":"update","id":"todo_...","status":"in_progress"}
{"op":"update","id":"todo_...","content":"세션 복원 및 재개 흐름 구현"}
{"op":"update","id":"todo_...","status":"completed"}
{"op":"remove","id":"todo_..."}
{"op":"list"}
{"op":"clear"}
```

- 모델은 `sessionId`를 선택할 수 없다. Worker가 생성한 도구 인스턴스의 세션에만 접근한다.
- `create`는 항상 `pending`이다. `update`는 content/status 중 하나 이상이 필요하다.
- 불필요한 필드, 빈 내용, 알 수 없는 상태, 다른 세션의 ID는 오류로 반환한다.
- 각 연산 결과는 `{sessionId, todos: [...]}`이며 각 항목은 id/sessionId/content/status/position/createdAt/updatedAt을 포함한다.
- 한 세션에 최대 100개, 내용은 항목당 최대 500자다. 잘라 저장하지 않고 초과 입력을 거부한다.
- `remove`, `clear`는 영구 삭제다. 불필요해진 작업은 보통 `cancelled`로 남기도록 프롬프트에서 안내한다.
- 모든 Todo 호출은 `parallelSafe=false`다. 같은 모델 라운드에서는 호출 순서대로 실행하며 다른 도구의 병렬 실행 구간과 섞이지 않는다.

허용 전이:

| 현재 | 변경 가능 |
| --- | --- |
| pending | in_progress, cancelled |
| in_progress | completed, pending, cancelled |
| completed | in_progress |
| cancelled | pending |

같은 상태로의 갱신은 허용하며 내용과 상태가 같으면 timestamp도 유지한다.
동시에 두 항목을 시작하려 하면 먼저 성공한 항목만 진행 중으로 남고 나머지는 오류를 받는다.

## 모델과 UI

- 복잡한 작업의 구체적인 결과 단위를 추적한다. 단순 질문이나 도구 호출 하나씩에 할 일을 만들지 않는다.
- 한 항목만 진행 중으로 두고 실제 완료 즉시 갱신한다. 범위가 바뀌면 목록도 갱신한다.
- 새 사용자 턴 또는 Worker 재시작 시 미완료 항목이 있으면 현재 전체 상태를 developer 메시지로 **첫 요청에만** 넣는다.
  DB 내용은 데이터로 취급하도록 명시하며, 이전 대화와 달라도 현재 DB가 기준이다.
- 동일 턴의 후속 tool continuation에는 reminder를 반복 삽입하지 않는다. 필요한 경우 모델이 `todo list`로 갱신한다.
- reminder는 채팅 저장소에 추가하지 않는다. 기존 history trimming 바깥에서 삽입되므로 오래된 메시지가 빠져도 상태가 유지된다.
  향후 compaction을 구현할 때도 새 요청의 실행 상태를 DB에서 읽고 요약 대상으로 넣지 않아야 한다.
- Todo는 라우터 모드에서 항상 등록되며 웹/파일/Termux 설정과 독립적이다.
- 채팅 상단의 접이식 글래스 패널은 커밋된 상태를 표시한다. 상태 라벨·완료 개수·현재 작업을 보여주고 긴 목록은 내부 스크롤한다.
- 현재 앱은 subagent 실행을 제공하지 않는다. 추가 시 별도 세션을 사용하고 부모 세션 도구를 전달하지 않아야 한다.
- 기존 답변당 도구 라운드 제한은 유지한다. 제한/취소/실패로 남은 할 일은 다음 턴에 복구한다.

## 검증

실패 조건은 `TodoFlowTest`에 먼저 열거했다. 세션 격리·상태 검증·동시 시작·SQLite 실패 rollback·재실행·
브랜치 독립성·삭제 후 늦은 호출·컨텍스트 축소·reminder 비반복을 실제 도구/Worker 경로로 검증한다.

저사양 래퍼로 한 번에 한 명령만 실행한다.

```bash
scripts/gradlew-lowspec.sh :app:testDebugUnitTest -q --tests '*TodoFlowTest'
scripts/gradlew-lowspec.sh :app:testDebugUnitTest -q --tests '*TodoPanelTest'
SLEEPYROUTER_BIN=/path/to/fresh-binary scripts/gradlew-lowspec.sh :app:testDebugUnitTest -q --tests '*RealSleepyrouterTest'
```

산출물: `app/build/test-artifacts/TodoFlowTest.*.txt`, `app/build/test-artifacts/todo/light.png`,
`app/build/test-artifacts/todo/dark.png`, `app/build/test-artifacts/RealSleepyrouterTest.*.txt`.
실제 기기의 GPU/IME/백그라운드 동작은 Robolectric 스크린샷과 별도로 확인해야 한다.
