# Hoard — Liquid Glass 적용 실전 기록

> 공식 문서 없이 이 기록만 보고도 Android(Compose) 앱에 Liquid Glass(Kyant Backdrop)를
> 입힐 수 있도록, 무엇을 왜 했는지, 어디서 넘어졌고 어떻게 빠져나왔는지를 모았다.
> 코드는 모두 Hoard(`com.sleepysoong.hoard`)의 현재 소스 기준이다.

- 최종 개정: 2026-09-27 (3차 — 디자인 완성 시점의 전면 개정)
- 대상: Compose/Material3는 익숙하지만 Backdrop은 처음인 개발자
- 기준 버전: `io.github.kyant0:backdrop:2.0.1`, `io.github.kyant0:shapes:1.2.1`
- 관련 파일: `app/src/main/kotlin/com/sleepysoong/hoard/ui/glass/*`

---

## 0. 먼저 읽을 결론

1. **Backdrop은 위젯이 아니라 "효과" 라이브러리다.** 버튼·토글·탭·팝업은 없다.
   `layerBackdrop`(기록)과 `drawBackdrop`(효과) 위에 디자인 시스템을 직접 만든다.
2. **순서가 실패의 대부분이다.** `배경만 기록 → 형제에서 소비 → 글자는 마지막에`.
   자기가 읽는 backdrop 안에 자기를 기록하면 RenderThread가 루프에 빠진다(4장).
3. **새 윈도우(Dialog/Popup/ModalBottomSheet)에서는 Activity backdrop을 못 쓴다.**
   그래서 모든 팝업은 같은 윈도우 오버레이다(5장).
4. **흰 바탕에서 유리는 색이 아니라 가장자리가 만든다.** 헤어라인 림, 그림자, 눌렀을 때의
   렌즈. 그리고 **유리 위에 불투명한 걸 칠하는 순간 유리는 사라진다**(9장).
5. **공통 컴포넌트 하나 + 파라미터 오버라이드.** 화면마다 새로 만들면 반드시 어긋난다.
   팝업·상단 바·컨트롤 모두 한 벌씩만 있다(11장).
6. **바이너리가 진실이다.** GitBook 예제와 2.0.1 AAR의 시그니처가 다르다. `javap`(10장).
7. **테마는 폰이 아니라 앱 설정을 따른다.** `isSystemInDarkTheme()` 금지, `LocalHoardDarkTheme`(8장).
8. **모션은 한 물리계.** 스프링 토큰(`GlassMotion`), 등장은 튀고 퇴장은 안 튄다.
   그리고 `animateFloatAsState(1f)`는 등장 애니메이션이 아니다(17장).
9. **눈으로 보기 전엔 끝난 게 아니다.** Robolectric 스크린샷 + 가상 시계 프레임 측정 +
   픽셀 검사로 확인한다(20장). 빌드는 저사양으로(21장).

---

## 1. Backdrop이 주는 것 / 주지 않는 것

| 라이브러리가 주는 것 | 직접 만든 것 |
| --- | --- |
| `rememberLayerBackdrop()`, `Modifier.layerBackdrop()` — 기록 | 버튼, 스위치, 세그먼트, 탭 바, 팝업, 상단 바 |
| `Modifier.drawBackdrop(...)` — blur·vibrancy·lens·colorControls | 톤 체계, 토큰, 타이포, 여백 |
| `Highlight`, `Shadow`, `InnerShadow` | 다크모드, 대비, 터치 영역, 모션 |
| `rememberCombinedBackdrop`, `rememberBackdrop(변환)` | "제품에서 어떻게 쓰는가"의 규칙 |

"Backdrop을 넣었다" ≠ "디자인이 됐다". 그래서 `GlassTokens`, `glassMaterial`, `GlassMotion`이 있다.

---

## 2. 빌드 게이트

Backdrop 2.0.1 AAR은 **`minCompileSdk = 37`** 을 요구한다. 의존성 한 줄이 아니라 도구체인 업그레이드다.

| 항목 | 값 | 비고 |
| --- | --- | --- |
| compileSdk | **37** | AAR 메타데이터가 강제. 검사를 끄지 말 것 |
| minSdk | 31 | `RenderEffect`(블러)가 12부터 |
| targetSdk | 35 | |
| AGP / Gradle | 9.3.2 / 9.7.1 | |
| Kotlin | 2.4.10 | Compose 플러그인 동일 버전 |
| Compose BOM | 2026.09.00 | |
| JDK | 21 (Gradle·테스트 실행) | 앱 바이트코드 타깃은 17. Markdown/LaTeX 라이브러리가 Java 21 클래스라 로컬 JVM 테스트에 21 필요 |

CI(`.github/workflows/build-and-release.yml`)는 `sdkmanager "platforms;android-37.0"`을 별도로 설치한다.

---

## 3. 아키텍처 — 3개 층

```text
[1] 배경 층   Box.matchParentSize().layerBackdrop(backdrop).background(colors.background)
              — 기록 대상. 제품 UI는 여기에 없다(단색만, 그라데이션 금지).
[2] Backdrop  rememberLayerBackdrop { drawRect(White); drawContent() }
[3] 소비자     drawBackdrop(backdrop, shape, effects, highlight, shadow, innerShadow, …)
              — 텍스트/아이콘은 이 뒤에 선명하게 그린다.
```

`GlassHost`(GlassTheme.kt)가 [1]·[2]를 만들고 `LocalGlassBackdrop`으로 내려준다.
`GlassTheme`은 `resolveGlassMode(SDK)`로 `Off(<31) / BlurOnly(31–32) / Full(33+)`를 정한다.
Preview(`LocalInspectionMode`)는 Off. 사용자에게 켜고 끌 옵션은 주지 않는다.

규칙:
- 기록과 소비는 **형제**여야 한다.
- 배경 층에는 배경만. 컨트롤·텍스트를 넣지 않는다.
- `LayerBackdrop`은 컴포지션 안에서만(ViewModel·싱글톤 저장 금지).

---

## 4. 가장 많이 죽는 실수 — RenderThread 루프

`layerBackdrop(b)`는 그 노드가 그리는 것을 매 프레임 기록한다. 그 안에서 `b`를 읽으면
읽기가 기록을 다시 부르며 자기 자신을 그린다.

```kotlin
// ⛔ 금지: 자식이 부모가 기록 중인 backdrop을 읽음
Column(Modifier.layerBackdrop(b)) { GlassCard() /* b를 읽음 */ }

// ✅ 기록(배경)과 소비(콘텐츠)를 형제로
Box {
    Box(Modifier.matchParentSize().layerBackdrop(b).background(bg))
    ScreenContent()   // 여기서 b를 읽는다
}
```

**부분 기록 레이어**도 같은 규칙이다. 스위치 트랙이나 세그먼트 라벨처럼 "이 레이어를
썸이 굴절시켜야 할 때"는 레이어를 따로 만들어 **형제**로 둔다(11.4).

```kotlin
val trackLayer = rememberLayerBackdrop()
Box(Modifier.matchParentSize().layerBackdrop(trackLayer).drawBehind { drawRect(trackColor) })
Box(Modifier.drawBackdrop(backdrop = rememberCombinedBackdrop(global, trackLayer), …))  // 형제
```

---

## 5. 윈도우 분리 — 모든 팝업은 같은 윈도우 오버레이

`Dialog`, `Popup`, `ModalBottomSheet`, `DropdownMenu`, `Tooltip`은 **새 윈도우**를 연다.
새 윈도우에서는 Activity의 기록 레이어를 샘플링할 수 없다(검게 나오거나 크래시).

Hoard의 결정: **팝업은 전부 `GlassAnchoredOverlay` 위의 `GlassPopup`** — 루트 Box 안에
그려지는 같은 윈도우 오버레이다. 예전의 `GlassDialog`(별도 창)는 삭제했다.

같은 윈도우 오버레이이기 때문에 직접 챙겨야 하는 것:
- **뒤로 가기**: Dialog가 대신 처리해 주지 않는다 → `BackHandler { close() }`.
- **스크림 범위**: 오버레이가 NavHost의 좌우 패딩 안에 있어서 스크림이 화면 끝까지
  닿지 않았다 → `drawBehind`로 사방 4000px까지 칠한다.
- **바깥 탭 닫기**: 스크림 `clickable(indication = null)`.

> 판정 규칙: "이 Composable은 새 윈도우를 여는가?" 그렇다면 그 안에서 글래스를 쓰지 않는다.

---

## 6. 팝업 시스템 — `GlassActionSheet.kt`

모든 팝업(롱프레스 메뉴, 모델 선택, 세션 설정, 메시지 수정, 브랜치, 삭제 확인, 이름 변경,
MCP 추가)이 **하나의 레이아웃**이다:

```text
헤더 카드(제목·메시지) · 본문 카드(행 또는 폼, 넘치면 스크롤) · 버튼 줄(취소 / 확인)
```

```kotlin
GlassPopup(
    onDismiss = onDismiss,
    title = "세션 설정",
    message = "이 세션에만 적용됩니다 (목업).",
    anchor = settingsAnchor,               // 누른 요소 Rect. null이면 화면 가운데
    confirmLabel = "저장",
    confirmEnabled = name.isNotBlank() && contextLimit != null,
    onConfirm = { …; onDismiss() },
    bodyPadding = PaddingValues(16.dp)
) {
    GlassTextField(value = name, onValueChange = { name = it }, label = { Text("세션 이름") }, singleLine = true)
    …
}
```

- 행은 `GlassPopupRow(label, subtitle, icon, selected, destructive, closesPopup)`,
  구분선은 `GlassPopupDivider(inset)`. 메뉴는 `GlassAnchoredMenu`(= GlassPopup + 행 목록).
- 위치: 먼저 `onGloballyPositioned`로 카드 크기를 잰 뒤, 앵커 위/아래 중 공간이 넉넉한 쪽에
  띄우고 화면 안으로 클램프한다. `transformOrigin`은 앵커 쪽 → 누른 곳에서 커져 나온다.
- 앵커 Rect는 회전 시 날아가므로 `rememberSaveable(stateSaver = Saver<Rect?, Any>(…4 float…))`.
- 모델 선택은 제목 바(`GlassFloatingBar`의 `onGloballyPositioned`) 바로 아래에 앵커.
- **닫힘도 애니메이션이다.** `LocalPopupCloser`가 "퇴장 애니메이션 → 액션" 순서를 보장한다.
  취소·확인 버튼과 `closesPopup = true` 행은 이걸 통해 닫힌다. 확인 동작(저장 등)은
  **퇴장이 끝난 뒤** 적용된다(`PopupMotionTest`가 검증).

### 실패 사례

| 증상(사용자 피드백) | 원인 | 해결 |
| --- | --- | --- |
| "팝업 디자인이 다 달라, 세션 설정 이상해" | 팝업마다 헤더/카드/버튼 복붙, 확인창만 별도 창 Dialog | `GlassPopup` 하나로 통합, `GlassDialog` 삭제 |
| 세션 설정 입력칸 라벨이 칸 테두리를 파먹음 | `OutlinedTextField` 라벨 노치가 채운 배경을 자름 | `GlassTextField` = 라벨이 안에 뜨는 filled `TextField`, 기본 전체 폭 |
| 모델 선택이 "이상하게 투명" | 오버레이에 카드 없이 텍스트를 바로 띄움 | 오버레이는 컨테이너일 뿐, 내용은 항상 카드로 |
| `BoxScope.GlassAnchoredOverlay` Unresolved | Box 밖에서 BoxScope 확장 호출 | 리시버 없는 함수 + 내부 `BoxWithConstraints` |

---

## 7. 재질 — `glassMaterial` 효과 스택

화면 코드는 `glassMaterial`을 직접 부르지 않고 공통 컴포넌트를 쓴다(내부 전용 `internal`).

```kotlin
Modifier.drawBackdrop(
    backdrop = backdrop,
    shape = { shape },
    effects = {
        vibrancy()
        colorControls(saturation = 1.06f, brightness = if (dark) 0f else 0.015f)
        blur(spec.blur.toPx())
        if (mode == GlassMode.Full) lens(
            refractionHeight = minOf(spec.lensHeight.toPx(), size.minDimension / 2.6f),
            refractionAmount = minOf(spec.lensAmount.toPx(), size.minDimension * 0.45f),
            depthEffect = false,            // true면 가장자리에 흰 띠
            chromaticAberration = false
        )
    },
    highlight = null,                       // 카드에는 끔(9장). 컨트롤 썸은 켬(11.4)
    shadow = if (lifted) {{ Shadow(18.dp, DpOffset(0.dp, 6.dp), if (dark) Black else Color(0xFF2E2016), if (dark) .34f else .13f) }} else null,
    innerShadow = if (enabled && tone != Thin) {{ InnerShadow(10.dp, DpOffset(0.dp, 1.5.dp), Black, spec.innerShadow) }} else null,
    onDrawSurface = { drawRect(tint.copy(alpha = if (dark) alpha * 0.86f else alpha)) }
)
.border(GlassTokens.hairline, outline, shape)   // 흰 바탕에서 유리의 존재감
.clip(shape)
```

- 크기 클램프(`size.minDimension`)가 없으면 작은 요소에서 렌즈가 요소 전체를 뒤집어 버린다.
- **Fallback(Off)**: `background(tint.copy(alpha = light .86 / dark .72))` + 헤어라인 = 글래스모피즘.

---

## 8. 톤 · 토큰 · 테마

```kotlin
Thin    -> blur 5,  lens (8, 14),  tint .26, inner .05   // 버튼, 칩, 필, 아이콘 버튼
Regular -> blur 10, lens (16, 26), tint .34, inner .08   // 카드, 말풍선, 팝업 카드
Thick   -> blur 16, lens (30, 52), tint .52, inner .10   // 탭 바, 입력 바, 상단 바, 세그먼트 트랙
// 다크: tint ×1.22(≤.60), inner ×1.4 — 검은 캔버스에서도 재질이 읽히게
```

`GlassTokens`: `cardRadius 22`, `sheetRadius 28`, `touchMin 44`, `barHeight 64`,
`hairline 0.5`, `modalActionHeight 54`, `popupWidth 360`, `shadowBleed 24` (dp).

### 테마는 앱 설정을 따른다

설정 → 화면 스타일(시스템/라이트/다크)은 폰 테마와 다를 수 있다.
`isSystemInDarkTheme()`는 **폰** 테마를 읽는다. 세그먼트 썸과 `glassMaterial`이 이걸 읽어서
"앱은 다크, 폰은 라이트"일 때 흰 썸 위에 흰 글자가 그려졌다(대비 1.98).

```kotlin
val LocalHoardDarkTheme = staticCompositionLocalOf { false }   // HoardTheme이 제공
val dark = LocalHoardDarkTheme.current                          // 색이 테마에 달린 곳은 전부 이것
```

`isSystemInDarkTheme()`가 허용되는 곳은 "시스템" 옵션을 해석하는 `MainActivity` 한 곳뿐이다.
`ThemeContrastTest`가 앱×폰 4조합에서 선택 글자 대비 ≥ 4.5를 픽셀로 잰다.

---

## 9. 흰 바탕에서 유리가 사라지는 문제 — "리퀴드가 아닌데?" 체크리스트

GlassHost가 기록하는 건 단색 배경뿐이다. 흰 배경을 굴절시키면 흰색이라 **렌즈와 블러가 눈에
보이지 않는다.** 흰 바탕의 유리는 이것들이 만든다:

1. **헤어라인 림**(0.5dp, onSurface α .13)과 **드롭 섀도**(따뜻한 코코아 톤 `0xFF2E2016`)
2. **내부 그림자**(두께감, α .05–.10)
3. **눌렀을 때의 렌즈**(움직이는 것에서만 굴절이 보인다)
4. **선택 표시는 틴트 글래스**(primaryContainer 계열) — 흰 유리 위 흰 썸은 안 보인다

사용자에게 "리퀴드가 아니다"를 들은 곳은 전부 아래 중 하나였다. 새 컴포넌트는 이 표로 점검한다.

| 들은 피드백 | 실제 원인 | 해결 |
| --- | --- | --- |
| 세그먼트(MCP/플러그인…)가 리퀴드가 아님 | 글래스 트랙 **위에 불투명 회색(α .9)을 한 겹 더 칠함** + 흰 불투명 썸 | 트랙 위에 아무것도 칠하지 않음, 탭 바 구조로 재작성(11.4) |
| 토글이 리퀴드가 아님 | 썸이 평소엔 흰 불투명(Kyant 원본은 누를 때만 유리) | `glassAtRest`: 평소에도 서리 낀 유리 + 풀 스페큘러 림 |
| "테스트/제거"가 리퀴드가 아님 | 그냥 파란/빨간 텍스트 링크 | `GlassPillButton`(틴트 글래스 캡슐) |
| 입력창 첨부 칩·슬래시 메뉴 | `background(surfaceContainerHigh)` 불투명 | 각각 Thin 글래스 캡슐 / Regular 글래스 카드 |
| 도구·설정 그룹 카드 | `GlassGroupedSection`이 불투명 흰 `Surface` | `GlassSurface`로 |

> 규칙: **글래스 표면 위에 `background()`로 불투명 면을 올리지 않는다.** 필요하면 그
> 요소 자체를 Thin 글래스로 만든다.

### 9.1 "카드 아래를 흰색으로 칠한 것 같다" — 두 번 들었고 원인이 달랐다

1. **스페큘러 워시**: `Highlight.Default`의 비스듬한 반사광 + `lens(depthEffect = true)`가
   흰 캔버스에서 카드 아래쪽을 하얗게 씻어냈다 → 카드 재질은 `highlight = null`, `depthEffect = false`.
2. **그림자 잘림**: 그림자 자체는 정상이었고, **그려지는 영역이 잘렸다.**
   - wrap-content `LazyColumn`이 마지막 카드 12dp 아래에서 끝나 그 선에서 그림자가 끊김
     (스크롤 컨테이너는 스크롤 방향 가장자리에서 그리기를 자른다)
   - 불투명 `Surface`가 위 요소의 그림자를 덮음
   - 해결: 목록은 `weight(1f)`로 남은 공간을 채우고, 스크롤 끝에 `GlassTokens.shadowBleed`(24dp)
   - 검증: `ShadowClipTest`가 카드 아래 픽셀 밝기를 한 줄씩 읽어 급격한 변화(>3)를 잡는다
     (수정 전 7.9)

진단 팁: 사용자 스크린샷을 PIL로 열어 **문제 영역의 세로 한 줄 RGB를 출력**하면
"부드럽게 옅어지다가 한 번에 255로 튄다"(=잘림)인지 "넓게 하얘진다"(=워시)인지 바로 갈린다.

---

## 10. API의 진실은 AAR — `javap`

```bash
aar=$(find ~/.gradle/caches -path "*kyant0/backdrop-android*" -name "backdrop.aar")
mkdir -p /tmp/bd && cd /tmp/bd && unzip -o "$aar" classes.jar && unzip -o classes.jar
javap -cp . com.kyant.backdrop.DrawBackdropModifierKt     # drawBackdrop 시그니처
javap -cp . com.kyant.backdrop.backdrops.CombinedBackdropKt
javap -p -cp . com.kyant.backdrop.highlight.Highlight     # Default / Ambient / Plain
```

확인해 둔 것:
- `Shadow(radius: Dp, offset: DpOffset, color, alpha, blendMode)` — offset은 **DpOffset**
- `drawBackdrop(backdrop, shape: () -> Shape, effects, highlight: (() -> Highlight)?, shadow, innerShadow, layerBlock, exportedBackdrop, onDrawBehind, onDrawBackdrop, onDrawSurface, onDrawFront)`
- `lens(refractionHeight, refractionAmount, depthEffect, chromaticAberration)`
- `rememberBackdrop(backdrop) { drawBackdrop -> scale(…) { drawBackdrop() } }` — 레이어 변환
- `rememberCombinedBackdrop(a, b)` / `(a, b, c)`
- `Highlight.Default` / `.Ambient` / `.Plain`, `copy(width, blurRadius, alpha, style)`

Kyant 카탈로그 원본(`LiquidToggle.kt`, `LiquidBottomTabs.kt`, `LiquidSlider.kt`)은
`raw.githubusercontent.com/Kyant0/AndroidLiquidGlass/kmp/app/src/commonMain/kotlin/com/kyant/backdrop/catalog/components/`
에서 바로 읽을 수 있다. 구조를 베끼되 **그대로 두면 흰 바탕에서 약하다**(11.4).

---

## 11. 디자인 시스템 — 정의하고, 오버라이드로만 쓴다

### 11.1 컴포넌트 지도 (전부 `ui/glass/`)

| 분류 | 컴포넌트 | 쓰는 곳 |
| --- | --- | --- |
| 기반 | `GlassTheme`, `GlassHost`, `glassMaterial`, `GlassTokens`, `GlassTone` | 전체 |
| 표면 | `GlassSurface`, `GlassCard`, `GlassGroupedSection`(=GlassSurface) | 카드, 그룹 |
| 상단 바 | `GlassFloatingBar(title, subtitle, onTitleClick, navigationIcon, actions)` | 세션·도구·설정·채팅 |
| 하단 바 | `GlassBottomBar` + `GlassTabItem` | 탭 |
| 버튼 | `GlassButton` / `GlassSecondaryButton` / `GlassCapsuleButton` / `GlassIconButton` / `GlassPillButton` | |
| 입력 | `GlassTextField`(filled), `GlassTokenField`(숫자·"토큰"·범위 검증) | 팝업, 설정 |
| 리퀴드 컨트롤 | `GlassSwitch`, `GlassSegmentedControl`, `GlassSlider` | 도구, 설정 (`GlassSlider`: 설정 · 자동 압축) |
| 팝업 | `GlassAnchoredOverlay`(내부), `GlassPopup`, `GlassPopupRow`, `GlassPopupDivider`, `GlassAnchoredMenu` | 모든 팝업 |
| 모션 | `GlassMotion`, `liquidPress`, `liquidClickable`, `HoardTransitions`, `screenCanvas` | 전체 |
| 기타 | `GlassSectionHeader`, `GlassRowDivider`, `GlassTypingDots`, `GlassAnimatedVisibility`, `GlassEmptyState` | |

현재 **호출하는 곳이 없는** 것: `GlassFilterChip`, `GlassBadge`, `GlassModalBottomSheet`/`GlassBottomSheetPanel`/`SheetGrabber`,
`LargeTitle`/`CollapsingLargeTitle`. 되살릴 땐 11.4 수준으로 리퀴드화한 뒤 쓴다 — `GlassSlider`가 그렇게
Material `Slider` 래퍼에서 Kyant `LiquidSlider` 구조로 다시 만들어졌다(11.4).

### 11.2 상단 플로팅 바는 모든 탭이 같다

```kotlin
GlassFloatingBar(title = "세션", actions = { GlassIconButton(onClick = newSession) { Icon(Add, "새 세션") } })
GlassFloatingBar(title = "도구", subtitle = "모델이 호출하는 기기 도구")
GlassFloatingBar(title = "설정")                                  // 스크롤 영역 밖 → 고정
GlassFloatingBar(title = session.name, subtitle = "모델 · 사용/한도 토큰", onTitleClick = { showModels = true },
                 navigationIcon = { … }, actions = { … })
```

좌우 슬롯은 44dp 고정이라 제목이 항상 광학 중앙. 큰 제목(LargeTitle)을 쓰던 도구·설정을
이걸로 바꿨다. `TopBarConsistencyTest`가 세 탭의 바 위치·높이·폭이 같은지, 설정 스크롤 중
바가 고정인지 검사한다.

### 11.3 하지 말 것

- 화면 코드에서 `glassMaterial` 직접 호출, `background()`로 불투명 면 만들기(9장)
- 같은 역할의 컴포넌트를 화면마다 따로 작성(팝업 사례, 6장)
- Material 기본 리플을 유리 위에 두기 — 리플 대신 `liquidPress`
- `OutlinedTextField`(노치), 텍스트 링크 버튼

### 11.4 리퀴드 컨트롤 — `GlassLiquidControls.kt`

모든 썸은 `liquidThumb`: **트랙을 자기 레이어에 기록 → 썸이 형제로서
`rememberCombinedBackdrop(global, trackLayer)`를 굴절**한다. 누르면 부풀며
blur가 빠지고 lens·스페큘러·내부 그림자가 들어와 투명한 렌즈가 된다.
움직일 때는 속도에 비례해 늘어났다가 둥글게 돌아온다.

**반드시 지킬 것 (각각 한 번씩 틀렸다)**

1. **썸의 크기/늘어남은 `drawBackdrop(layerBlock = …)` 안에서.** 바깥 `graphicsLayer`로
   키우면 backdrop 좌표 매핑이 모르고, 굴절된 트랙이 렌즈 안에서 어긋나 그려진다.
   → 대신 semantics bounds에는 스케일이 안 잡힌다. 테스트는 픽셀로 잰다(20장).
2. **트랙을 렌즈 안으로 압축(`scale`)할지 신중히.** Kyant 스위치는 평소 트랙을 0으로
   찌그러뜨린다(흰 썸). 평소에 압축하면 트랙 밖 빈 영역이 딸려 와 **회색 초승달**,
   넓은 트랙이면 **검은 테두리 + 색수차 줄무늬**가 생긴다.
3. **평소에도 유리여야 한다(`glassAtRest`).** 서리 42% + `Highlight.Default` 림 + 약한 렌즈 +
   옅은 내부 그림자 → 초록 트랙이 비쳐 보인다. 누르면 기존처럼 투명한 렌즈.
4. **글자 위에 틴트를 칠하지 않는다.** 세그먼트 알약의 틴트를 `onDrawSurface`에 칠했더니
   굴절된 라벨을 덮어 선택 글자가 바랬다(대비 1.3). 틴트는 **기록 레이어 안, 글자 아래**에.

**스위치** `GlassSwitch`: 트랙 64×28, 썸 40×24. 탭 토글(`toggleable`, Role.Switch) + 드래그
(touchSlop 넘으면 소비해서 탭으로 안 셈, 놓으면 0.5 기준 스냅). 누르면 1.5배.
꺼짐 트랙 아래에도 Thin 글래스를 깐다(평평한 회색 방지).

**슬라이더** `GlassSlider`(Kyant `LiquidSlider` 구조, 스위치와 같은 문법): 주어진 폭을 채우고 높이 ≥ 44dp.
트랙 6dp 캡슐(스위치의 `trackOff` / 강조색)을 자기 레이어에 기록하고, 썸 40×24는 스위치 썸 그대로
(`liquidThumb`, `glassAtRest`) — 누르면 1.5배. 썸 중심은 [20dp, 폭−20dp]만 움직이고(오버슈트도 클램프)
채움은 `폭 × f`에서 끝난다 → 항상 썸 밑이라 밖에서 보면 "썸까지 초록", 0이면 비고 끝이면 꽉 찬다
(썸 중심까지 채우면 0에서도 유리 썸 너머로 초록 자투리가 비친다).
- 드래그: 어디서든 손가락만큼. touchSlop을 넘으면 소비(부모 스크롤 안 됨), 그 전에 부모가 가져가면
  Final 패스에서 보고 포기. 탭: 그 자리로 스프링(0.62, 520), 썸 자체를 탭하면 그대로.
- `steps` > 0: 손가락은 연속으로 따르고(칸마다 점프시키지 않는다 — 누르는 동안은 손가락을 따른다, 17장)
  칸이 바뀔 때만 `onValueChange` + 햅틱 틱, 놓으면 가까운 칸에 스프링으로 안착.
  `onValueChangeFinished`는 드래그·탭마다 정확히 한 번 — 저장은 여기서.
- RTL 대칭, 비활성 α .45. 접근성은 Material Slider와 같다(`progressBarRangeInfo` + `setProgress`).
  TalkBack은 범위 대비 %를 읽는다(50–100의 70 → "40%") → 단위 있는 값이면 호출부가 `stateDescription`을 단다.
- 규칙 1(스케일·늘어남은 layerBlock, 이동만 바깥 translationX) · 2(평소 1:1) · 3(평소에도 유리)을 그대로 따른다.
- 드래그를 `snapTo`로 따라가면 속도가 0이라 놓을 때까지 안 늘어난다 → 썸이 임계 감쇠 스프링(1.0, 3000)으로
  손가락을 쫓는다(2프레임쯤 늦음, 오버슈트 없음). 늘어남 = `0.18 × tanh(|v| / 750dp/s)`.
- **layerBlock은 안에서 읽은 스냅샷 상태가 바뀔 때만 다시 돈다**(`drawBackdrop` = `graphicsLayer(layerBlock)` + …,
  2.0.1 AAR에서 확인). `Animatable.velocity`는 상태가 아니라서 `value`를 같이 읽어야 매 프레임 늘어남이 갱신된다.

**세그먼트** `GlassSegmentedControl`(높이 44dp) — 하단 탭 바와 같은 구조(Kyant LiquidBottomTabs):
```text
트랙      glassMaterial(Capsule, surface, Thick)                 ← 탭 바와 같은 재질
라벨 사본  alpha(0) · clearAndSetSemantics · layerBackdrop(labels) · drawBehind(pillTint)
          · 강조색(onPrimaryContainer) 글자                        ← 알약이 굴절시키는 원본
보이는 라벨 색 = lerp(onSurfaceVariant, accent, 알약과의 거리)       ← fallback 겸 부드러운 색 전환
알약      drawBackdrop(combined(global, labels)), 3dp 인셋으로 트랙을 꽉 채움,
          vibrancy, lens×press(색수차), Highlight.Default α .35→1, 서리 12%×press
```
탭 → 스프링(0.7, 420)으로 오버슈트 이동, 알약 드래그 → 가장 가까운 칸으로 스냅.
접근성: 보이는 라벨에 `Role.Tab` + `selected` + `onClick`. 사본은 semantics 제거
(안 하면 `onNodeWithText("스킬")`이 2개를 찾아 테스트가 깨진다).

**하단 탭 바 알약**: 바 높이 전체 − 4dp 인셋(64dp 바 / 56dp 알약). 52dp로 떠 있을 땐
"너무 낮아 어색하다"는 피드백. 위치는 `Animatable` + 스프링(0.72, 420),
늘어남 = `0.2 × tanh(|v| × 0.12)` — 하드 캡(coerceAtMost)은 평평한 구간이 생겨 기계적이다.

**필 버튼** `GlassPillButton(label, onClick, tint = Neutral/Accent/Destructive)`: 카드 안
인라인 동작용 Thin 글래스 캡슐. `liquidClickable`을 **재질보다 먼저** 걸어야 알약 전체가 눌린다.

---

## 12. 겪은 크래시 · 버그 목록

| 증상 | 원인 | 해결 |
| --- | --- | --- |
| 전송 시 앱 즉시 종료(API 34+) | `MissingForegroundServiceTypeException` | Manifest `SystemForegroundService`에 `foregroundServiceType="dataSync"`, 병합 manifest를 aapt2로 확인 |
| 키보드 위 입력바 이중 여백 | `imePadding` + `navigationBarsPadding` 동시 적용 | IME 떠 있을 땐 네비바 여백 제거(15장) |
| 시트 그래버 잠재 크로스 윈도우 | ModalBottomSheet(새 창)에서 Activity backdrop | 솔리드로(5장) |
| 카드 아래 흰 띠 | 스페큘러 워시 / 그림자 잘림 | 9.1 |
| 다크에서 Hoard 말풍선 안 보임 | surfaceContainer ≈ 배경 | 다크 톤 밀도 보정(8장) |
| 다크에서 선택 글자 안 보임 | `isSystemInDarkTheme()`(폰 테마) | `LocalHoardDarkTheme`(8장) |
| 세션 탭인데 '도구'가 강조 | 선택 인덱스를 별도 상태로 보관, 초기값이 옛 4탭 기준 + Back 후 미갱신 | 강조를 **현재 라우트에서 계산**, 탭 이동은 `popUpTo("sessions")` |
| 채팅 진입 중 목록이 채팅 위로 비침 | 전환 중 alpha로 페이드된 레이어가 형제 위에 합성 | 전환에서 alpha 제거 + 목적지마다 `screenCanvas()`(17장) |
| 세그먼트 선택 글자 바램 | 틴트를 글자 위(onDrawSurface)에 칠함 | 틴트를 기록 레이어 글자 아래로(11.4) |
| 스위치 썸 안 회색 초승달 | 평소 트랙 압축 | 평소 1:1, 누를 때만 압축 |

---

## 13. 새 프로젝트 적용 순서

1. 도구체인(2장). AAR 메타데이터 검사는 끄지 않는다.
2. `GlassTheme` + `GlassHost`(배경만 기록) + `LocalHoardDarkTheme` 같은 **앱 테마 로컬**.
3. `glassMaterial` 하나 + 톤 3종 + 토큰.
4. 표면 → 상단/하단 바 → 버튼 → 팝업(같은 윈도우) → 리퀴드 컨트롤 순서로 공통 컴포넌트화.
5. `GlassMotion` 스프링 토큰 + `liquidPress`. 모든 탭 요소에 적용.
6. 9장 체크리스트로 "흰 바탕에서 유리로 보이는가" 점검. 다크도.
7. Robolectric 스크린샷·프레임 테스트로 확인(20장), 마지막은 실기기.

---

## 14. 제품 결정의 기록

- **그라데이션 금지.** 단색 + 글래스만.
- **화이트 우선**, 다크 지원. 테마는 앱 설정(시스템/라이트/다크)을 따른다.
- 효과 수준은 SDK로 자동(`resolveGlassMode`). 사용자에게 묻지 않는다.
- **백그라운드 답변은 항상 켜짐.** 앱을 나가도 답변은 끝까지 생성된다. 설정 토글 없음(기획 의도).
- 컨텍스트 한도는 **슬라이더가 아니라 숫자 입력**(`GlassTokenField`, 1,000–2,000,000).
- 탭 요소는 최소 44dp.
- 목업에 정당성 없는 UI는 넣지 않는다(예: 읽음 표시는 삭제).
- 메시지 메타데이터는 한 줄: `모델명 · 시간 · 초 · 토큰`.

---

## 15. 키보드 & IME 인셋

`WindowInsets.ime`는 키보드가 떠 있으면 네비바 영역까지 포함한다. 두 패딩을 같이 걸면 이중 여백.

```kotlin
.statusBarsPadding().imePadding()
.padding(bottom = when {
    inChat -> navBarBottom + 18.dp     // 탭바 없음
    imeVisible -> 0.dp                 // IME가 네비바 포함
    else -> navBarBottom + 108.dp      // 탭바 있음
})                                     // animateDpAsState(spring(0.86, 420))
```

입력: `ImeAction.Default`(Enter=줄바꿈), `Alt+Enter`만 전송(`onPreviewKeyEvent`). 채팅 화면이나
IME가 떠 있으면 하단 탭 바를 숨긴다.

---

## 16. 앵커드 팝업 위치 계산의 함정

1. 먼저 재고 나중에 놓는다: `onGloballyPositioned`로 크기 → 위/아래 결정 → 클램프 → `offset`.
2. `transformOrigin`을 앵커 쪽으로(위에 뜨면 pivotY=1, 아래면 0) — 누른 곳에서 커져 나옴.
3. 회전 대비 `rememberSaveable` Rect Saver.
4. 내용은 항상 카드(6장). 오버레이만 있으면 "이상하게 투명하다".

---

## 17. 모션 그래머 — `GlassMotion`

모션 규칙: **도착은 살짝 넘쳤다 안착, 퇴장은 빠르고 안 튐, 누르는 동안은 손가락을 따른다.**

```kotlin
object GlassMotion {
    fun bouncy()  = spring(0.68f, 380f, SCALE_THRESHOLD)   // 등장: 팝업·말풍선·칩
    fun snappy()  = spring(0.60f, 700f, SCALE_THRESHOLD)   // 선택 이동
    fun smooth()  = spring(0.86f, 420f, SCALE_THRESHOLD)   // 화면 전환·레이아웃
    fun exit()    = spring(1.00f, 1100f, SCALE_THRESHOLD)  // 누르는 순간·사라짐(안 튐)
    fun release() = spring(0.50f, 600f, SCALE_THRESHOLD)   // 손 뗌: 1.0을 넘었다 안착
    fun leave()   = tween(170, CubicBezier(0.4, 0, 1, 1))  // 팝업 퇴장
    fun <T> fade() = tween(160, …)                          // 페이드·색만
    offsetSmooth() / offsetBouncy() / sizeSmooth()          // IntOffset/IntSize용
    const val SCALE_THRESHOLD = 0.0005f
    const val PRESS_SCALE = 0.955f; const val PRESS_SCALE_LARGE = 0.975f
}
```

움직이는 것에는 tween을 쓰지 않는다(페이드·색만 tween).

### 적용 현황

| 대상 | 동작 |
| --- | --- |
| 눌림 `liquidPress` | 누르면 0.955(아이콘 버튼 0.88, 큰 카드 0.975)로 가라앉고, 떼면 ≈1.007로 튀었다 안착 |
| 팝업 | 앵커 쪽에서 0.82 → 1.010 → 1.0, 닫힘 170ms 축소+페이드 후 상태 변경 |
| 탭 바 | 캡슐 스프링 이동 + 속도 비례 늘어남, 아이콘 누르면 0.86·선택 1.08 |
| 화면 전환 `HoardTransitions` | 채팅 push: 오른쪽에서 슬라이드, 목록은 1/3 시차. pop은 역재생. 탭 전환: 96%에서 bouncy 등장 |
| 하단 탭 바 등장/퇴장 | 아래에서 bouncy하게 올라옴, 빠르게 내려감 |
| 말풍선 | 새 것만 꼬리 모서리에서 0.6 → 1.022 → 1.0 + 24dp 아래에서. 채팅 열 때 기존 것은 정지 |
| 목록 | `animateItem(placementSpec = offsetSmooth())` — 추가·삭제·재생성 시 나머지가 미끄러짐 |
| 생각 과정 | `expandVertically(sizeSmooth)` + bouncy scale |
| 입력창 | 높이 `animateContentSize`, 첨부 칩 pop, 슬래시 메뉴 솟아오름, 보낼 수 있게 되면 전송 버튼 부풂 |
| 스위치/세그먼트/슬라이더 | 11.4 |

### 교훈 (모두 실제로 틀렸던 것)

1. **`animateFloatAsState(targetValue = 1f)`는 등장 애니메이션이 아니다.** 시작값=목표값이라
   한 번도 재생되지 않는다. 팝업과 말풍선의 기존 "등장"이 전부 이랬다.
   ```kotlin
   val appear = remember(id) { Animatable(if (animateEntrance) 0f else 1f, visibilityThreshold = SCALE_THRESHOLD) }
   LaunchedEffect(id) { if (appear.value < 1f) appear.animateTo(1f, GlassMotion.bouncy()) }
   ```
2. **오버슈트가 안 보이면 `visibilityThreshold`.** Float 기본 0.01은 목표 1% 안에서
   애니메이션을 끝내 1.007 같은 튐을 통째로 삼킨다 → 0.0005.
3. **화면 push에 alpha를 쓰지 않는다.** 페이드 중인 레이어가 형제 위에 합성돼 나가는 화면이
   들어오는 화면 위로 비친다. 화면은 원래 투명(GlassHost 배경이 비침)이라 목적지마다
   불투명 `screenCanvas()`(NavHost 패딩 밖 40dp까지 칠함)를 깐다.
4. **닫힘도 애니메이션.** 상태를 먼저 바꾸면 팝업이 그냥 사라진다 → `LocalPopupCloser`.
5. **이미 있던 것은 튀지 않는다.** `initialIds = remember(session.id) { messages.ids }`.
6. **속도 기반 변형은 부드러운 포화(tanh).** 하드 캡은 평평한 구간이 생겨 로봇 같다.

---

## 18. 채팅 로직과 맞물린 UI 규칙 (재생성 · 수정)

- **다시 생성은 그 자리에서.** 대상 답변 **앞**의 가장 가까운 사용자 메시지로 답한다
  (예전 코드는 뒤쪽 = 곧 지워질 질문을 골랐다). `replaceSessionTail(idx)` 후 워커가 같은 자리에 append.
- **수정 후 다시 생성**: 수정한 메시지 뒤를 잘라내고 그 바로 뒤에 새 답변. 공백 수정 무시.
- 워커는 세션별 unique work: 전송 `APPEND_OR_REPLACE`(순서대로 하나씩),
  재생성·수정 `REPLACE`(옛 꼬리의 작업 교체). 답할 메시지(`parent_id`)가 사라졌으면 건너뜀.
- ViewModel 동작은 한 프레임 늦은 `uiState`가 아니라 **저장소 현재 값**으로 판단한다.
- 엔진 입력은 `ReplyRequest`(모델, 시스템 프롬프트, 컨텍스트 한도로 자른 히스토리, 첨부,
  켜진 도구)를 **실행 시점에 저장소에서** 만든다. 상단 바 사용량 = 다음 요청이 싣는 양.

---

## 19. 사용자 피드백 → 원인 사전

같은 말이 다른 원인일 수 있으니, 들으면 여기서부터 의심한다.

| 들은 말 | 먼저 볼 것 |
| --- | --- |
| "리퀴드가 아닌데?" | 유리 위 불투명 면 / 평소 불투명 썸 / 텍스트 링크 / 선택 표시가 흰색(9장) |
| "아래를 흰색으로 칠한 것 같다" | 스페큘러 워시(highlight, depthEffect) 또는 그림자 잘림(스크롤 경계)(9.1) |
| "팝업마다 다르다" | 공통 컴포넌트 우회, 별도 창 Dialog(6장) |
| "다크에서 글자가 안 보인다" | `isSystemInDarkTheme()` / 틴트가 글자 위(8장, 11.4) |
| "강조된 게 엉뚱하다" | 선택 상태를 따로 들고 있음 → 라우트/소스에서 계산 |
| "높이가 낮아 어색하다" | 선택 알약이 컨테이너를 못 채움(4dp 인셋 기준) |
| "딱딱하다 / 안 튄다" | 등장이 안 도는 `animateFloatAsState(1f)`, threshold, tween(17장) |
| "전환 중에 뭔가 비친다" | 전환 alpha, 투명한 목적지(17장) |

---

## 20. 눈으로 확인하는 방법 — Robolectric 시각 테스트

실기기 없이(KVM 없음) 대부분을 JVM에서 확인했다.

```kotlin
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)              // 실제 렌더링(렌즈·하이라이트도 그려짐)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")   // 다크는 "+night"
class PopupScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()   // 진짜 앱을 조작
    fun shot(name: String) = compose.onRoot().captureToImage().asAndroidBitmap()
        .compress(PNG, 100, File(artifacts, "$name.png").outputStream())
}
```

산출물: `app/build/test-artifacts/{popups,liquid,motion,shadow,theme,topbar}/*.png`.
**PNG를 직접 열어 본다.** 수치 테스트가 통과해도 스크린샷에서 틀린 게 여러 번 나왔다
(초승달, 색수차 테두리, 바랜 글자, 비치는 목록).

### 프레임 단위 모션 측정

```kotlin
// 1) 진짜 시계로 화면을 연 다음(입력 처리 완료) 가상 시계로 전환
compose.onNodeWithText("…").performClick(); compose.waitForIdle()
compose.mainClock.autoAdvance = false
// 2) 한 프레임씩
val curve = (1..40).map { compose.mainClock.advanceTimeByFrame(); scaleOf("target") }
assertTrue(curve.max() > 1.005f)   // 오버슈트가 실제로 있는가
```

함정:
- 스케일 = `boundsInRoot.width / size.width`. **testTag를 graphicsLayer 뒤에** 달아야 변형이 잡힌다.
- 부모 경계에 잘리면 1.0 이상이 안 나온다 → 대상 주변에 여유 공간.
- `drawBackdrop(layerBlock)` 안의 스케일은 semantics에 안 잡힌다 → **픽셀로 잰다**
  (예: 스위치 중앙선의 첫 비배경 픽셀 x가 줄었는가).
- `advanceTimeBy(1000)` 한 번보다 `advanceTimeByFrame()` 반복이 실제 디스플레이에 가깝다
  (한 번에 크게 넘기면 팝업이 아직 없다고 나온 적 있음).
- merged 노드(`toggleable`) 안의 태그는 `useUnmergedTree = true`.
- 테스트 간 전역 상태 누수 주의: `MockAiEngine.pace`(테스트 전용 엔진), `Engines.offline`, `SettingsStore`는 규칙/하네스에서 복구.

### 픽셀 검사 예

- 대비: 선택 칸의 최빈색(배경)과 가장 먼 색(글자)의 WCAG 대비 ≥ 4.5(`ThemeContrastTest`)
- 그림자 잘림: 카드 아래 40dp 세로선의 행간 밝기 차 < 3(`ShadowClipTest`)
- 유리 여부: 켜진 스위치 썸 중앙 픽셀이 초록을 띠는가(`LiquidControlsTest`)

---

## 21. 저사양 빌드 (개발 PC 3코어 / 6GB가 빌드 중 멈춘다)

- 항상 `scripts/gradlew-lowspec.sh <task>` (nice 19 + ionice idle, worker 1, 끝나면 데몬 종료).
- 좁게 먼저(`--tests '*OneTest*'`), 전체 스위트는 커밋 전에 한 번. 무거운 작업 동시 실행 금지.
- 상한: Gradle 힙 1280m · SerialGC · ActiveProcessorCount=2, 테스트 JVM 1 fork · 1024m · C1만.
  메모리가 모자라면 상한을 올리지 말고 실행을 쪼갠다. 실측: 전체 72개 약 75초, APK 약 27초.
- 셸 함정: `cd dir && (모니터) & …` 는 **cd까지 백그라운드로** 보낸다(빌드가 엉뚱한
  디렉터리에서 돈다). `cd dir; (모니터) & …`로 분리.
- `cut -c`는 한글을 바이트 단위로 잘라 줄이 "사라진 것처럼" 보인다. 파일 자체는 멀쩡하다.

---

## 시작 스니펫 (다른 프로젝트의 최소 단위)

```kotlin
val LocalAppDark = staticCompositionLocalOf { false }

@Composable
fun GlassAppHost(dark: Boolean, content: @Composable BoxScope.() -> Unit) {
    val mode = if (LocalInspectionMode.current) GlassMode.Off else resolveGlassMode(Build.VERSION.SDK_INT)
    val backdrop = if (mode != GlassMode.Off) rememberLayerBackdrop { drawRect(Color.White); drawContent() } else null
    CompositionLocalProvider(LocalGlassMode provides mode, LocalAppDark provides dark) {
        Box(Modifier.fillMaxSize()) {
            // 배경만 기록한다. 콘텐츠는 형제로.
            Box(Modifier.matchParentSize().then(backdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier)
                .background(MaterialTheme.colorScheme.background))
            CompositionLocalProvider(LocalGlassBackdrop provides backdrop) { content() }
        }
    }
}
```

여기에 `glassMaterial` → `GlassTokens`/톤 → `GlassMotion` → 공통 컴포넌트(표면·바·버튼·팝업·
리퀴드 컨트롤) 순으로 쌓고, 컴포넌트마다 9장 체크리스트와 20장 스크린샷으로 확인한다.
