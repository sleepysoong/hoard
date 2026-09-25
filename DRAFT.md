안드로이드 앱을 코틀린으로 개발할거야. 제작자 이름은 sleepysoong이고 앱 이름은 Hoard(https://github.com/sleepysoong/hoard)

리퀴드 글래스 디자인 언어를 적용한 앱 디자인 껍데기를 만드는 것이 목표야.

ChatGPT, Claude, Gemini와 같은 AI 앱을 만들건데 실제로 채팅을 보내도 목업 데이터로 응답을 생성하는 척만 해. 기능 설계는 나중에 할꺼니까. 지금은 말 그대로 껍데기만 만들면 되는 상황.

기본은 화이트 톤이고 다크모드도 지원해야하며 리퀴드 글래스를 완벽히 지원해야해. 상황에 따라 리퀴드 글래스를 적용할 수 없는 경우 대안으로 글래스모피즘을 적용해.

Glass Bottom Sheet: https://kyant.gitbook.io/backdrop/tutorials/glass-bottom-sheet

Interactive Glass Bottom Bar: https://kyant.gitbook.io/backdrop/tutorials/interactive-glass-bottom-bar

Glass Bottom Bar: https://kyant.gitbook.io/backdrop/tutorials/glass-bottom-bar

Glass Slider: https://kyant.gitbook.io/backdrop/tutorials/glass-slider

Liquid Button: https://github.com/Kyant0/AndroidLiquidGlass/blob/kmp/app/src/commonMain/kotlin/com/kyant/backdrop/catalog/components/LiquidButton.kt

Liquid Toggle: https://github.com/Kyant0/AndroidLiquidGlass/blob/kmp/app/src/commonMain/kotlin/com/kyant/backdrop/catalog/components/LiquidToggle.kt

Liquid Slider: https://github.com/Kyant0/AndroidLiquidGlass/blob/kmp/app/src/commonMain/kotlin/com/kyant/backdrop/catalog/components/LiquidSlider.kt

Liquid Bottom Tabs: https://github.com/Kyant0/AndroidLiquidGlass/blob/kmp/app/src/commonMain/kotlin/com/kyant/backdrop/catalog/components/LiquidBottomTabs.kt


내 프로젝트에서 리퀴드 글래스 디자인 언어를 적용하여 에이전트가 여러 시행착오를 거쳤는데 참고할 필요가 있다면 참고해

- https://github.com/sleepysoong/auto-band-selector/commit/6aa9ae9c418d91e0c5979dc98faa196842b26dec

- https://github.com/sleepysoong/auto-band-selector/commit/b82f681868bd8ac60fc87e11995bbf5e7d67a8bb

- https://github.com/sleepysoong/auto-band-selector/commit/b82f681868bd8ac60fc87e11995bbf5e7d67a8bb

- https://github.com/sleepysoong/custom-widgets/blob/main/LIQUID_GLASS_PLAN.md



당연히 채팅 앱인 만큼 채팅 가능해야하고 사진 및 파일 첨부도 가능해야해.

시스템 프롬프트도 수정할 수 있어야하고 session을 자유롭게 수정할 수 있어야해.

내 메시지를 수정한다거나 특정 메시지를 기준으로 새로 branch를 판다거나 메시지 삭제 등등도 다 지원하고


모델의 thinking도 잘 보여줘야하고 모델 선택도 가능해야해.

세션별로 컨텍스트 양도 정할 수 있고 각종 플러그인 및 MCP, Skill, 슬래시 커맨드도 지원해야하고 그에 맞는 리퀴드 글래스 UI 및 목업데이터가 필요해.

메시지 아래에는 몇 초가 사용되었는지도 떠야하고 상단 플로팅 바에는 세션 이름 및 현재 컨텍스트 사용량 등등도 보여야해


그리고 메시지를 보내고 앱을 나가도 백그라운드에서 처리를 이어서 해야해.

등등 추가 디테일을 너가 서치해보고 일단 너 마음대로 만들어봐.

지금은 디자인만 하는 단계이므로 리퀴드 글래스 완벽 적용이 가장 중요한 목표야.

절대 그라데이션을 사용하지 마.


[https://github.com/sleepysoong/custom-widgets/blob/main/.github/workflows/build-and-release.yml]와 같은 깃허브 액션을 만들어서 자동으로 사인된 apk를 생성하고 버전을 높이고 릴리즈에 파일을 추가하도록 해줘.
