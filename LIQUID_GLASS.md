# Hoard — Liquid Glass 적용 실전 기록

> 이 문서는 주니어 개발자가 **공식 문서 없이 이 기록만으로** Android 앱에
> Liquid Glass(Backdrop, Kyant)를 적용할 수 있을 정도로, 무엇을/왜 했는지,
> 어디에서 크게 넘어졌고 어떻게 피했는지를 자세히 담는다. 코드 조각은 모두
> Hoard 소스(패키지 `com.sleepysoong.hoard`)에서 발췌한다.

- 작성 시점: 2026-09-26 (2차 개정)
- 대상 독자: Compose(Material3)는 익숙하지만 Backdrop은 처음인 개발자
- 기준 버전: `io.github.kyant0:backdrop:2.0.1`, `io.github.kyant0:shapes:1.2.1`

---

## 0. 먼저 읽을 결론

1. **Backdrop은 위젯 라이브러리가 아니라 "효과" 라이브러리다.** 버튼·토글·탭·
   시트 같은 완성품은 제공하지 않는다. `layerBackdrop`(배경 캡처)과
   `drawBackdrop`(효과 적용) 두 함수 위에, 디자인 시스템은 직접 만든다.
2. **순서가 실패의 3/4다.** `배경 레이어 캡처 → (형제에서만) 소비 → 텍스트/아이콘은
   마지막에 선명하게`. 이 순서 하나로 크래시와 "뽀얗게 보인 글자"가 갈린다.
3. **가장 많이 죽는 곳**: (a) `layerBackdrop`을 샘플링 박스로 중첩 → RenderThread 루프
   (b) Dialog/Popup에서 Activity의 backdrop 사용 → 크로스 윈도우 크래시
   (c) `ForegroundServiceType` 미선언 → `MissingForegroundServiceTypeException`.
4. **문서가 아니라 AAR이 진실의 원천이다.** GitBook 최신 예시와 우리가 받은
   2.0.1 바이너리의 API가 달랐다(파라미터 이름·타입·기본값). `javap`로 클래스
   시그니처를 확인하고 넘어갔다(10장).
5. **흰 바탕은 색이 아니라 림·그림자가 유리를 만든다.** tint가 약한 환경에서는
   "헤어라인(0.5dp) + 내부/외부 그림자"가 구분을 책임진다.
6. **Highlight.Default가 "하얗게 칠한" 아티팩트를 만든다.** 앵글드 스페큘러 워시가
   화이트 캔버스에서 카드 하단을 세탁해버린다. `highlight = null` + 림라인으로.
7. **공통 컴포넌트를 정의하고 파라미터로 오버라이드하라.** 화면마다 새로 만들면
   디자인 통일이 깨진다(11장).

---

## 1. Backdrop이 주는 것 / 주지 않는 것

| 라이브러리가 주는 것 | 주지 않는 것(직접 만든 것) |
| --- | --- |
| `rememberLayerBackdrop()` — 배경 캡처 | 완성 버튼, 칩, 슬라이더, 탭 바, 시트 |
| `drawBackdrop` — blur·vibrancy·lens·shadow | 스타일 토큰, 타이포, 여백, 탭/테마 전략 |
| `Highlight`·`Shadow`·`InnerShadow` | 다크모드, 대비, 터치 영역 |
| `exportedBackdrop`(글래스 위 글래스) | 제품에서 그것을 어떻게 쓰는가의 규칙 |

즉 “Backdrop을 넣었다” ≠ “디자인이 됐다”. 라이브러리는 픽셀 표면만 연, 앱의
디자인 언어는 스스로 책임져야 한다. 이것이 `GlassTokens`와 `glassMaterial`을
만든 이유다.

---

## 2. 빌드 게이트(여기서 막히면 이under간 선행)

Backdrop 2.0.1 AAR는 **`minCompileSdk = 37`**을 요구한다. 기본 템플릿(compileSdk
34/35, Kotlin 1.9)에는 의존성 한 줄로 들어가지 않고, **도구체인 업그레이드로
봐야 한다.**

실제로 빌드가 성공한 조합:

| 항목 | 값 | 비고 |
| --- | --- | --- |
| `compileSdk` | **37** | AAR 메타데이터가 강제 |
| `minSdk` | 31 | `RenderEffect`(블러)가 12부터 |
| `targetSdk` | 35 | 런타임 권한(POST_NOTIFICATIONS 등) 기준과 독립 |
| AGP | 9.3.2 | CompileSdk 37 맞추기 |
| Gradle | 9.7.1 | `gradle-wrapper.properties`에 고정 |
| Kotlin | 2.4.10 | Compose 플러그인과 동일 버전 |
| Compose BOM | 2026.09.00 | foundation 1.9+ 필요 |
| `backdrop` | 2.0.1 | `mavenCentral()` |
| `shapes` | 1.2.1 | 비-Compose shape 도구(`Capsule` 등) |
| JDK | — | 17 (AGP 9 기준) |

주의: compileSdk를 올리는 것을 “dependecy 추가”로 여기면 안 된다. SDK, pkg을
깎는다는 식의 우회는 쓰지 말고 metadata 체크를 그대로 통과해야 한다.

---

## 3. 아키텍처 — 3 레이어로 알고리즘을 잡는다

리퀴드 글래스가 보이기까지 정확한 책임 분리:

```text
[1] Background layer
      Box.matchParentSize().layerBackdrop(backdrop)
      — 캡처 대상. 제품 UI는 여기 없다.

[2] Backdrop = LayerBackdrop
      rememberLayerBackdrop { drawRect(White); drawContent() }
      — 화면 스냅샷 버퍼.

[3] Consumers
      drawBackdrop(backdrop, shape, effects{...}, highlight, shadow, innerShadow)
      — [2] 캡처를 shape로 잘라 fx 적용. 텍스트는 이 안에섭이 나오지 않도록.
```

규칙(하나라도 위반하면 크래시):

- **캡처와 소비는 “형제”**. 같은 `Column(scope)`에서 `layerBackdrop`과 `drawBackdrop`
  을 같이 쓰는 순서는 위험하다 — 4장.
- **배경은 캡처만 한다.** 컨트롤과 텍스트는 캡처 박스에 절대 넣지 않는다.
- **텍스트는 `glassMaterial` 이후에** 그리기. angle값상 blur에 들어가면 안 된다.
- **`LayerBackdrop`은 Composition 한정.** ViewModel/싱글톤에 저장 안 한다.

---

## 4. 가장 많이 죽는 실수 — RenderThread 루프

`layerBackdrop(backdrop)`이 그 자식 렌더링을 매 프레임 캡처하므로(some "record
what this subtree draws"), 그 자식 안에서 같은 backdrop을 읽으면 읽기가 다시
기록을 유발해 프레임이 자기 자신을 그리게 된다.

**금지사항**

```kotlin
Box(Modifier.fillMaxSize()) {
    val backdrop = rememberLayerBackdrop()
    // ⛔ 이 Box의 자식 전체가 backdrop에 기록됨
    Column(Modifier.layerBackdrop(backdrop)) {
        GlassCard(backdrop) { ... }  // 자식 안에서 같은 backdrop을 읽음 → 루프
    }
}
```

**해결 — 캡처/소비 분리**

```kotlin
Box(Modifier.fillMaxSize()) {
    // A — 캡처 대상: 배경 색상만(콘텐츠는 절대 없음)
    Box(
        Modifier.matchParentSize()
            .layerBackdrop(backdrop)
            .background(colors.background)
    )
    // B — 소비 대상: 上面에서 backdrop을 읽어 효과만 적용
    ScreenContent(backdrop = backdrop)
}
```

**글래스 위에 글래스**(시트 안 컨트롤, 카드 안 버튼)는 `exportedBackdrop`으로:

```kotlin
val sheetBackdrop = rememberLayerBackdrop()
Column(
    Modifier.drawBackdrop(
        backdrop = backdrop,            // 부모(Act backdrop)을 읽음
        exportedBackdrop = sheetBackdrop  // 이 표면을 자식에게 새 기준으로 표출
    )
) {
    CompositionLocalProvider(LocalGlassBackdrop provides sheetBackdrop) {
        // 자식은 sheetBackdrop 기준 (부모 중첩 없음) → 루프 없음
        content()
    }
}
```

> 규칙: **“내가 소비한 backdrop이 내 자식 위에 다시 기록되게 하지 않는다.”**

---

## 5. 윈도우 분리 — Dialog/Popup은 Activity의 backdrop을 쓰면 안 된다

Backdrop의 레이어는 **하는 윈도우의 하드웨어 GraphicLayer**에 붙는다. Compose의
`Dialog`·`Popup`·`DropdownMenu`·`Tooltip`·`ModalBottomSheet`는 모두 **새 윈도우**
를 연다. 거기서 Activity의 backdrop을(즉 LocalGlassBackdrop)` 습관적으로 읽어
그리면 다른 윈도우의 레이어를 그리는 것이 되고, 아예 검게만 나오거나 RenderThread가
크래시할 수 있다.

Hoard가 실제로 한 결정:

| 팝업 | 대처 |
| --- | --- |
| 모달 팝업 (`GlassPopup`) | Dialog 창을 쓰지 않는다 — 같은 윈도우 오버레이라 Activity backdrop을 그대로 샘플링 (구 `GlassDialog`는 삭제) |
| 바네임(ModalBottomSheet: model/settings) | **본체는 솔리드+알파(0.92f)**, 같은 화면의 진짜 팝업은 in-window overlay로 |
| 메시지 액션 시트 (`GlassActionSheet`) | **같은 윈도우 내 오버레이** → 진짜 글래스 셰이더가 정상 동작 |

이번 작업에서 실제로 실수했다가 바로잡은 곳: **시트 그래버** (`SheetGrabber`)에
`glassMaterial`을 붙여 Activity backdrop을 쓰려 했다. 사용자는 크래시를 보기 전에
제가 코드 검토 중에 발견해 솔리드로 교체했다팔. 문법을 모르면 이 맥락은 발견이
안 난다 — “새 윈도우”를 isWin 판정하는 규칙이 그만큼 중요하다.

> 판정 규칙: “이 Composable은 새 윈도우를 여는가?” — Dialog, Popup, ModalBottomSheet,
> Tooltip, DropdownMenu, 컨텍스트 메뉴. 맞우면 그 안에서 **Activity backdrop은
> 절대 쓰지 않는다**. 자체 host를 만들거나 no-op.

---

## 6. 액션 시트가 “같은 윈도우 오버레이”인 이유

요구는 “메시지 롱프레스 → 리퀴드 글래스 팝업”. 그럼에도 Popup 새 창이면 안 돼므로,
루트 `Box`의 자식으로 구현했다:

```kotlin
Box(Modifier.fillMaxSize()) {
    // ... 기존 화면
    if (menuTarget != null) {
        GlassActionSheet(...)   // BoxScope이므로 같은 윈도우에 탑층으로 렌더링
    }
}
```

각 카드/버튼은 평범한 `glassMaterial(...)`로 그려지고 스크림이 뒤를 어둡게 처리한다.
즉 시각적으로는 완전한 팝업이지만, Backdrop은 그 뒤의 표면을 제대로 읽는다.

---

## 7. 시각 품질 스택 — 효과는 어떤 순서로 켜는가

**2026-09-26 개정**: `Highlight.Default`가 앵글드 스페큘러 **워시**를 그려
화이트 캔버스에서 "카드 하단이 하얗게 칠해진" 아티팩트를 만들었다.
유저가 두 번 지적했고, `highlight = null` + 헤어라인 보더 + 그림자로 해결했다.
`lens`의 `depthEffect`도 같은 증상을 가중했다 — `false`로 고정.

현재의 정확한 순서:

```kotlin
effects = {
    vibrancy()
    colorControls(saturation = 1.06f, brightness = if (dark) 0f else 0.015f)
    blur(spec.blur.toPx())
    if (mode == GlassMode.Full) {
        lens(
            refractionHeight = minOf(spec.lensHeight.toPx(), size.minDimension / 2.6f),
            refractionAmount = minOf(spec.lensAmount.toPx(), size.minDimension * 0.45f),
            depthEffect = false,        // ← "하얀 밴드" 아티팩트
            chromaticAberration = false
        )
    }
},
highlight = null,   // ← 앵글드 워시 → "칠한" 느낌. 림라인으로 대체
shadow = if (lifted) { {
    Shadow(radius = 18.dp, offset = DpOffset(0.dp, 6.dp),
           color = if (dark) Color.Black else Color(0xFF1B2A4A),
           alpha = if (dark) 0.34f else 0.13f)
} } else null,
innerShadow = if (enabled && tone != GlassTone.Thin) { {
    InnerShadow(radius = 10.dp, offset = DpOffset(0.dp, 1.5.dp),
                color = Color.Black, alpha = spec.innerShadow)
} } else null
// 이후: .border(GlassTokens.hairline, outline, shape).clip(shape)
```

파라미터의 감각:

| 효과 | 역할 | 우리의 시작값 |
| --- | --- | --- |
| `blur` | 표면 흐림 | Thin 5dp / Regular 10dp / Thick 16dp |
| `lens` | 가장자리 굴절 | Thin (8,14) / Regular (16,26) / Thick (30,52) |
| `vibrancy` | 색 보정 | 항상 on |
| `colorControls` | 미세 보정 | light: saturation 1.06·bright +0.015, dark: saturation 1.06 |
| ~~`Highlight`~~ | 림 하이라이트 | **끔** — 앵글드 워시가 "칠한" 아티팩트를 만듦 |
| `Shadow` | 드롭섀도(띄움) | 18dp, Y 6dp, dark: Black α0.34, light: 0xFF1B2A4A α0.13 |
| `InnerShadow` | 두께감 | Thin 톤에서는 off, Regular/Thick만: 10dp, Y 1.5dp, α 0.05-0.14 |

**중요한 제물**: blur/lens는 `Float`로 px를 원한다. `Dp.toPx()`는
`Density` 스코프가 있으면 안전하고, `size.minDimension` 클램핑으로 0이나
부풀은 렌즈를 막는다.

**다크 모드 밀도 보정**: `specFor(tone, mode, dark)`에서
`tintAlpha × 1.22`(상한 0.60), `innerShadow × 1.4`를 곱한다.

---

## 8. 톤 시스템 — 똑같은 재질이면 안 된다

`GlassTone { Thin, Regular, Thick }`로 재질 밀도를 역할별로 분리했다.

```kotlin
private fun specFor(tone: GlassTone, mode: GlassMode): ToneSpec = when (tone) {
    GlassTone.Thin    -> ToneSpec(blur=5.dp,  lensHeight=8.dp,  lensAmount=14.dp, tintAlpha=.26f, innerShadow=.05f)
    GlassTone.Regular -> ToneSpec(blur=10.dp, lensHeight=16.dp, lensAmount=26.dp, tintAlpha=.34f, innerShadow=.08f)
    GlassTone.Thick   -> ToneSpec(blur=16.dp, lensHeight=30.dp, lensAmount=52.dp, tintAlpha=.52f, innerShadow=.10f)
}
```

역할 매핑:

- **Thick** — 하단 탭 바, 입력 바 같은 “한 화면에서 제일 강한 표면”, 바텀시트 본체
- **Regular** — 카드, 메시지 버블, 액션 메뉴
- **Thin** — 버튼, 칩, 토글, 세그먼트 셀렉터, 아이콘 버튼

이 분리가 있으면 탭 바를 띄워 놓고도 필드/버튼이 같은 맛으로 안 셰익한다.
(또한 `mode == Full`이 아닌 환경에서는 `specFor`에서 lens를 0으로 깎은 뒤
blur+tint만 남긴다 — API 31~32 API 레벨일관성 문제)

---

## 9. 흰 배경에서 유리가 사라지는 문제

사용자 요구가 “화이트 온리, 색 블롭 제거”로 바뀌자, 표면이 흰 위에서는 아무것도
보이지 않았다. tint가 흰색이라 그렇다. 그때 채운 것:

1. **hairline** — `border(0.5dp, onSurface.copy(alpha=0.13))`. 기계처럼 작지만
   iOS 사용자는 가장자리 한 줄로 존재감을hd한다.
2. **InnerShadow**(색지도돌기) — 유리 “두께”. α 0.05~0.10 정도로 아주 낮춘다.
3. **외부 Shadow 회색 계열** — `Color(0xFF1B2A4A)` 같은 회청, 흑 빛이 아닌 색.
4. 스페큘러 Highlight(림)는 라이트에서 alpha를 높인다.

결국 “색이 없는 背景에서는 표면의 변화보다 가장자리에만 생겨있는 artifact가
유리가 되도록”이라는 이야기다. 사용자 입장에서는 “직업보는 데로 보이느냐”는
것 하나도 chiac.

---

### 9.1 그림자가 직선으로 잘리는 문제 ("아래를 흰색으로 칠한 것 같다")

그림자 자체는 문제가 아니다. **그림자가 그려지는 영역이 잘린 것**이다.

- 원인 1: 내용 높이만큼만 차지하는(wrap-content) `LazyColumn`. 스크롤 컨테이너는
  스크롤 방향 가장자리에서 그리기를 자른다. 목록이 마지막 카드 바로 아래에서
  끝나면 그림자가 그 선에서 끊겨, 그 아래가 흰색 띠처럼 보인다.
- 원인 2: 불투명 `Surface`(흰색)가 바로 위 요소의 그림자를 덮어 그린다.
- 해결: 목록은 남은 공간을 채우고(`weight(1f)`), 스크롤 컨테이너 끝에
  `GlassTokens.shadowBleed`(24dp = 그림자 반경 18 + 오프셋 6) 여백을 준다.
  그룹 섹션(`IOSGroupedSection`)도 불투명 Surface가 아니라 글래스 카드로 만든다.
- 검증: `ShadowClipTest`가 카드 바로 아래 픽셀 밝기를 한 줄씩 읽어 급격한 변화(잘린 선)를 잡는다.

---

## 10. API true의 원천 — javap를 습관화하라

최신 GitBook 예제가 AAR 2.0.1과 달랐다(타입·이름·기본값). 바보같이 빌드 실패에
시간을 쓰지 않으려면, 실제 바이너리를 확인한다:

```bash
# 경로 예시 (Gradle Cacheatic)
jars=/root/.gradle/caches/modules-2/files-2.1/io.github.kyant0/backdrop-android/2.0.1/.../backdrop.aar
unzip -o $jars classes.jar
unzip -o classes.jar -d classes
javap -classpath classes com.kyant.backdrop.shadow.Shadow   # 실제 시그니처
```

확인 목록:

- `Shadow(radius: Dp, offset: DpOffset, color: Color, alpha: Float, blendMode: BlendMode)`
  — **offset이 DpOffset(geometry Offset가 아님)**. 처음에 `Offset(0f, 6.dp)`로 쓰다 컴파일 에러.
- `InnerShadow` 동일 포맷.
- `Highlight`의 `Default`/`Plain`/`Ambient` — 우리는 `Highlight.Default.copy(alpha=)`가 충분.
- `lens(refractionHeight: Float, refractionAmount: Float, depthEffect: Boolean, chromaticAberration: Boolean)`

**규칙**: docs/GitBook이 움직이는 그대로, 우리가 받은 바이너리만 진실하다.

---

## 11. 디자인 시스템 — 정의한 다음 오버라이드로만 쓴다

플로팅 바, 팝업, 버튼, 슬라이더처럼 여러 화면에서 반복되는 요소는 직접 화면에
돌려놓지 말고, **공통 컴포넌트로 한 곳에 정의하고 재정의 파라미터만 넘긴다**.
같은 버튼이 두 화면에 있으면 뭐가 한쪽이고 다른 쪽이냐를 알 수 없으므로,
애플(및 일반적인 현대 앱) 전략이 아프게 반복됩니다.

```kotlin
shape = RoundedCornerShape(…)        // 코너는 여기만
tint = MaterialTheme.colorScheme.surface
tone = GlassTone.Thin / Regular / Thick
enabled = true / false
```

### 11.1 컴포넌트 계층

| 수준 | 컴포넌트 | 오버라이드 포인트 |
| --- | --- | --- |
| 표면 | `glassMaterial(...)` | shape · tone · tint · outlineColor · lifted |
| 버튼 | `GlassButton` / `GlassSecondaryButton` | enabled · destructive · shape · content |
| | `GlassCapsuleButton` | primary / destructive, 모달 하단(취소·확인) 쌍용 |
| | `GlassIconButton` | 44dp 터치영역, 아이콘+onClick만 넘김 |
| 입력 | `GlassTextField` | label·placeholder·singleLine·visualTransformation 등 Material 계약 그대로 |
| | `GlassSlider` | value·onValueChange·steps·range, 기존 Slider 벡터 그대로 |
| | `GlassSwitch` | checked·onCheckedChange·enabled |
| 뷰 | `GlassCard` / `GlassSurface` | shape · tone · color(표면컨테이너) |
| 상단 바 | `GlassFloatingBar` | navigationIcon · title/subtitle(onTitleClick) · actions 슬롯 |
| 오버레이 | `GlassAnchoredOverlay` | anchor(Rect), 내용 슬롯 (직접 쓰지 말 것) |
| 팝업 | `GlassPopup` | **모든 팝업의 유일한 레이아웃**: 헤더 카드 · 본문 카드 · 버튼 줄. title/message/anchor/confirmLabel/body 슬롯만 채운다 |
| | `GlassPopupRow` / `GlassPopupDivider` | 팝업 본문의 행(아이콘·부제·체크)과 구분선 |
| | `GlassAnchoredMenu` | `GlassPopup` + 액션 행 목록 |

사용처 입장에서는 shape나 색상을 조정하지 않고 호출하는 슬롯만 채우면 됩니다. 채팅 상단과 세션 목록 상단이 지금 동일하게 이것으로 처리됩니다.

### 11.2 실전 판례

```kotlin
// 채팅 상단바
GlassFloatingBar(
    title = session.name,
    subtitle = "${session.modelId} · ${usedTokens}/${session.contextLimit} 토큰",
    onTitleClick = { showModels = true },
    navigationIcon = {
        GlassIconButton(onClick = onBack) { Icon(ArrowBack, contentDescription = "뒤로") }
    },
    actions = {
        Box(Modifier.onGloballyPositioned { settingsAnchor = it.boundsInRoot() }) {
            GlassIconButton(onClick = { showSettings = true }) { Icon(Settings, contentDescription = "설정") }
        }
    }
)

// 세션 목록 상단바
GlassFloatingBar(
    title = "세션",
    actions = { GlassIconButton(onClick = newSession) { Icon(Add, null) } }
)
```

좌우 슬롯은 모두 44dp로 고정해서 타이틀이 자동으로 광학 가운데에 오게 하고,
타이틀 클릭 영역은 `indication = null`의 ripple 없는 클릭으로 만드는 게 기본값입니다.
이렇게 해 두면 제목을 누를 때만 모델 피커가 뜨고, 설정 버튼 옆에서 중복 스타일 복처이 줄어듭니다.

### 11.3 공통 컴포넌트에서 안 하면 좋은 거

- `Modifier.glassMaterial(...)`을 화면 코드에서 직접 호출. 위 계층의 컴포넌트를
  쓰거나, 새 컴포넌트를 공통 파일에 추가한다.
- Compose `Popup`/`Dialog`에 글래스 넣기 — 5장.
- 같은 동작을 하는 컴포넌트를 두 화면에 따로 작성 (이 프로젝트에서 이미 했음).
  실제 사례: 세션 설정·모델 선택·롱프레스 메뉴가 헤더/카드/버튼을 각자 복붙했고,
  확인·수정 다이얼로그는 별도 창 `GlassDialog`라 모양이 전혀 달랐다. 게다가
  `OutlinedTextField`의 라벨이 채워진 필드에 노치를 파서 "이상한 팝업"이 됐다.
  → `GlassPopup` 하나로 통합, `GlassDialog` 삭제, 필드는 라벨이 안에 뜨는 filled TextField.
  검증: `PopupScreenshotTest`가 실제 앱을 조작해 팝업마다 라이트/다크 PNG를
  `app/build/test-artifacts/popups/`에 남긴다.

원리: 과정을 줄이려다 복제가 늘면, 디자인 통일성만이 아니라 수정할 때마다
수정해야 하는 곳이 증해진다.**오버라이드로만 연결**
라고 부르는 규칙을 세우면, 새로운 화면이 추가될 때 공간 매칭이 남아나지 않습니다.

---

모든 glass 표면은 토큰을 통해서만 만큰다:

```kotlin
object GlassTokens {
    val cardRadius = 22.dp
    val sheetRadius = 28.dp
    val fieldRadius = 20.dp
    val controlRadius = 16.dp
    val bubbleRadius = 19.dp
    val tailRadius = 5.dp
    val touchMin = 44.dp
    val barHeight = 64.dp
    val hairline = 0.5.dp
}
```

화면 코드는 유일한 진입점만 호출한다:

```kotlin
@Composable
internal fun Modifier.glassMaterial(
    shape: Shape = RoundedCornerShape(GlassTokens.cardRadius),
    tint: Color = MaterialTheme.colorScheme.surface,
    tone: GlassTone = GlassTone.Regular,
    enabled: Boolean = true,
    outlineColor: Color? = null,
    lifted: Boolean = true
): Modifier
```

컴포지션 계약:

- 시각은 `glassMaterial`만이 소유, `enabled=false`면 비교적 계단intval로 색만 바스·글로 꺼진다.
- 클릭/포커스는 Material이拾う(Button, FilterChip을 그대로 두고 그 위에 표면).
- 텍스트는 blur/lens 바깥에서 그린다(데이터가 아니라 렌더링 순서로).

---

## 12. 실제로 겪은 크래시 리스트

| 증상 | 원인 | 해결 |
| --- | --- | --- |
| 전송 시 앱 즉시 터짐 | API 34+ `MissingForegroundServiceTypeException` (WorkManager FGS 타입 미선언) | Manifest에서 `SystemForegroundService`에 `dataSync` 선언, **APK의 병합 manifest을 aapt2로 검증** |
| 입력바 전송 버튼이 곡 근처에서 돌출 | 26dp 바 코너 + 26/22dp 서로 다른 아이콘 크기 + 우측 여백 부족 | 모든 컨트롤을 `GlassTokens.touchMin`(44dp) Box로 통일 + baseline을 `Alignment.Bottom`으로 |
| 세그먼트 타이틀의 letterSpacing/타이포 이상 | 하드코드 예제 값 복사 | Typography 토큰 정의(크기별 -5%)로 통일 |
| 키보드 떠 있을 때 입력바 밑에 이중 여백 | `.navigationBarsPadding()`이 IME와 함께 적용 | `if (imeVisible) navigationBarsPadding 제거` |
| 시트 그래버의 잠재 크로스 윈도우 | `ModalBottomSheet`가 새 윈도우인데 Activity backdrop 샘플링 | 그래버를 솔리드로 교체(5장) |
| release build daemon 터짐 | 기기 메모리 한계(코드 무관) | 빌드 전 메모리 잡고 빌드, CI는 여유 여분 사용 |
| **세션 카드 하단이 "하얗게 칠한" 것처럼 보임** | `Highlight.Default`의 앵글드 스페큘러 워시 + `lens(depthEffect=true)`가 화이트 캔버스에서 카드 표면을 세탁 | `highlight = null`, `depthEffect = false` + 헤어라인 보더 + 그림자만으로 림 정의 |
| **모델 피커가 "이상하게 투명"** | `GlassAnchoredOverlay` 안에 카드 래핑 없이 Text/LazyColumn을 날로 띄움 | 액션 메뉴와 동일한 **헤더 카드 + 목록 카드** 구조로 통일 (11장 규칙 준수) |
| **`BoxScope.GlassAnchoredOverlay`가 "Unresolved reference"** | BoxScope 확장 함수를 Box 리시버 없는 곳에서 호출 | 리시버 제거(`fun GlassAnchoredOverlay`) — 오버레이가 어디서든 호출 가능 |
| **다크 모드에서 Hoard 버블이 안 보임** | `scheme.surfaceContainer`(#19191C)가 배경(#0C0C0F)과 거의 같음 | `specFor`에 `dark` 플래그로 tintAlpha/innerShadow 증폭 |
| **입력바가 네비바에 붙음** | 하단 여백 계산이 IME/비-IME 상태와 독립적이지 않았음 | `WindowInsets.navigationBars` + 상태별 `bottomReserve` 계산 |
| **재생성 시 아래에 새 메시지가 추가됨** | `retryFrom`이 `appendMessage`만 하고 기존 위치를 안 자름 | `replaceSessionTail(sessionId, idx)` — 목록을 인덱스에서 자른 뒤 스트리밍 |

---

## 13. 적용 순서(새 프로젝트 체크리스트)

1. 도구체인 정렬: compileSdk=37, AGP 9.3.2, Gradle 9.7.1, Kotlin 2.4.10, Compose BOM 최신.
2. Backdrop/Shapes alias 연결. **AAR metadata 검사는 끄지 않는다.**
3. GlassHost: 캡처 전용 sibling에 `layerBackdrop`, Consumer sibling에서 drawBackdrop.
4. `glassMaterial` 1개 작성: tone + blur/lens + highlight + innerShadow + outerShadow 최소 한 세트.
5. 공통 컴포넌트를 순차 전환: 버튼→카드→바텀시트(전체 윈도우 시트)→Dialog.
6. 인터랙션 향상: liquidClickable(리플 없는 프레스 스쿼시 + 햅틱), 44dp 터치, 컬러 옵션.
7. 빌드 검증: assembleDebug + assembleRelease, `apksigner verify`, `aapt2 dump xmltree`로 FGS 선언 확인.
8. 실기기에서 보고 확정(스그린샷 말고): 블러 국부가 균일한지, 하이라이트가 과도하지 않은지, 텍스트가 흐린지.

---

## 14. 제품 결정의 기록

- **그라데이션은 사용하지 않는다.** 배경 변화는 solid color + blur(둥근 서클)로만.
- **화이트 온리**에서 유리는 tint가 아니라 가장자리 artifact로 읽는다(9장).
- **최대 효과 자동**: `resolveGlassMode(Build.VERSION.SDK_INT)` — 사용자에게
  "On/Off"를 묻지 않는다. API 31~32는 BlurOnly, 33+는 Full.
- **백그라운드 답변은 항상 활성** — 설정에 토글을 두지 않는다(기획 의도).
- 탭/버튼/전송은 44dp에 맞춰 통일; 어디서든 Touchable을 한 공통으로.

---

## 15. 키보드 & IME 인셋 — 이중 여백 방지

**핵심**: `WindowInsets.ime`는 키보드가 떠 있을 때 **네비게이션 바 영역까지 포함**한다.
따라서 `imePadding()`과 `navigationBarsPadding()`을 동시에 걸면 이중 여백이 생긴다.

```kotlin
// ✅ 정확한 방법
modifier
    .statusBarsPadding()
    .imePadding()
    .padding(bottom = when {
        inChat -> navBarBottom + 18.dp    // 탭바 없음: 네비바+여백
        imeVisible -> 0.dp                // IME가 nav bar 포함
        else -> navBarBottom + 108.dp     // 탭바 있음: 네비바+탭바
    })
```

**채팅 입력바가 키보드 위에 붙는 조건**:
- `BasicTextField`의 `imeAction = ImeAction.Default` (Enter = 줄바꿈)
- `onPreviewKeyEvent`에서 `Key.Enter && isAltPressed`만 전송 트리거
- 전송 버튼은 별도 44dp 글래스 버튼 (11장의 `GlassAttachButton` 패턴)

---

## 16. 앵커드 팝업 — 위치 계산의 함정

메시지 롱프레스 → 팝업이 **누른 버블 바로 위/아래에** 떠야 한다.

**측정 순서**: 카드를 먼저 배치하지 말고 `onGloballyPositioned`로 크기를 잰 뒤
위치를 결정한다:

```kotlin
Column(
    Modifier
        .onGloballyPositioned {
            cardW = it.size.width; cardH = it.size.height
            // 여기서 위/아래 방향 결정 + x/y 클램프
        }
        .offset { IntOffset(placedX, placedY) }
        .graphicsLayer {
            transformOrigin = TransformOrigin(anchorX/cardW, if (above) 1f else 0f)
        }
)
```

**3가지 함정**:
1. **`BoxScope` 확장 함수 리시버** — `fun BoxScope.X()`는 Box 밖에서 호출하면
   "Unresolved reference"가 난다. `fun X()`로 만들고 내부에서 `BoxWithConstraints`를
   쓰는 게 안전하다.
2. **회전 시 앵커 소실** — `remember { Rect? }`는 configuration change에서 날아간다.
   `rememberSaveable(stateSaver = Saver<Rect?, Any>(save = { listOf(l,t,r,b) }, restore = { ... }))`
   로 4개 float를 저장해야 한다.
3. **팝업 내용이 "날로" 뜸** — `GlassAnchoredOverlay`는 컨테이너일 뿐이다. 내용은
   반드시 `glassMaterial(shape, surface)` 카드로 감싸야 한다. 그렇지 않으면
   투명한 텍스트가 스크림 위에 떠서 "이상하게 투명하다"는 피드백을 받는다.

---

## 17. 모션 그래머 — 일관된 물리 시스템

모든 애니메이션이 `GlassMotion`에서 정의한 5개 스펙을 통과한다:

```kotlin
object GlassMotion {
    val springs = SpringSpec<Float>(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow)
    val springSnappy = SpringSpec<Float>(Spring.DampingRatioNoBouncy, Spring.StiffnessHigh)
    val fast = TweenSpec<Float>(180, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
    val fastColor = TweenSpec<Color>(180, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
    val relaxed = TweenSpec<Float>(240, easing = CubicBezierEasing(0.25f, 0f, 0f, 1f))
}
```

| 스펙 | 용도 |
| --- | --- |
| `springs` | 버블 등장, 팝업 등장 (부드러운 바운스) |
| `springSnappy` | 탭 전환, 인디케이터 (빠르고 overshoot 없음) |
| `fast` | 스크림 페이드, 저비용 UI |
| `fastColor` | 아이콘/텍스트 색 전환 (알파 보간) |
| `relaxed` | 큰 카드/오버레이 등장 |

**메시지 버블 등장 애니메이션** — 사용자는 오른쪽 하단 코너에서, Hoard는 왼쪽에서:

```kotlin
val appear by animateFloatAsState(1f, GlassMotion.springs)
Box(
    Modifier.graphicsLayer {
        scaleX = 0.95f + 0.05f * appear
        scaleY = 0.95f + 0.05f * appear
        alpha = appear
        transformOrigin = TransformOrigin(if (isUser) 1f else 0f, 0.85f)
    }
)
```

---

## 18. 재생성(in-place retry) — 아래에 추가하지 말고 그 자리에서

메시지를 "다시 생성"하면 목록 맨 끝에 새 버블이 추가되는 게 아니라, 대상 버블이
그 자리에서 교체돼야 한다:

```kotlin
// Repository
fun replaceSessionTail(sessionId: String, fromIndex: Int) {
    _messages.update { map ->
        map[sessionId]?.let { full ->
            if (fromIndex < full.size) map + (sessionId to full.take(fromIndex)) else map
        } ?: map
    }
}

// ViewModel.retryFrom
val idx = messages.indexOfFirst { it.id == messageId }
if (idx < 0) return
repo.replaceSessionTail(session.id, idx)  // ← 여기서 자르고
ChatResponseWorker.enqueue(...)            // ← 워커가 같은 위치에 append
```

**원리**: 워커의 `appendMessage`는 항상 목록 끝에 추가하므로, "자르고 → 추가"하면
결과적으로 그 인덱스에 새 버블이 들어간다.

---

## 19. 2026-09-26 종합 감사에서 나온 교훈

1. **같은 인터페이스가 세 벌로 돌면 반드시 하나는 망가진다.** 탭 바/앵커드
   오버레이/모달 다이얼로그가 각각 따로 놀다가 결국 시각 불일치로 사용자 피드백.
2. **사용하지 않는 파라미터는 남기지 마라.** `isLastUserMessage`는 체크 아이콘을
   지우고 나서도 시그니처에 남아 호출부에서 조용히 죽은 코드가 됐다.
3. **사용자가 "왜 이게 있지?"라고 물으면 정답은 삭제다.** 실제 메신저도 아닌데
   읽음 체크 아이콘이 있었다. 목업 데이터에 정당성 없는 UI는 낭비다.
4. **메타데이터는 발바닥 라인으로** — 모델명이 버블 위에 별도 라인으로 떠 있으면
   시선이 분산된다. `모델명 · 시간 · 토큰` 한 줄로 병합.

---

## 시작 스니펫(다른 프로젝트의 최소 단위)

```kotlin
@Composable
fun GlassAppHost(content: @Composable BoxScope.() -> Unit) {
    val mode = if (LocalInspectionMode.current) GlassMode.Off else resolveGlassMode(Build.VERSION.SDK_INT)
    val backdrop = if (mode != GlassMode.Off) rememberLayerBackdrop { drawRect(Color.White); drawContent() } else null
    CompositionLocalProvider(LocalGlassMode provides mode) {
        Box(Modifier.fillMaxSize()) {
            if (backdrop != null) {
                Box(Modifier.matchParentSize().layerBackdrop(backdrop).background(MaterialTheme.colorScheme.background))
            }
            CompositionLocalProvider(LocalGlassBackdrop provides backdrop, content = content)
        }
    }
}
```

이것으로 시작해 `glassMaterial`, `GlassTokens`, 그다음 컴포넌트(버튼·칩·카드)
를 순서대로 append하면 끝난다.
