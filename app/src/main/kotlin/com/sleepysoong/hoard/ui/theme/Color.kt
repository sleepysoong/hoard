package com.sleepysoong.hoard.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * iOS system palette. Solid colors only — no gradients anywhere in the app.
 * Chrome (bars, sheets, tab bar) is liquid glass; content controls are iOS-solid.
 */
/*
 * Hoard key colours, from the mascot (app icon): matcha body + cocoa face.
 *  - Cocoa is the text/icon accent (links, selected labels, my bubbles): 6.9:1 on white.
 *  - Matcha is the fill accent (selected pills, switches, success): too light for text on
 *    white (2.1:1), so text in "matcha" uses MatchaDeep (4.9:1).
 *  - Dark mode lifts cocoa to Latte and matcha to MatchaLight to stay readable on black.
 */
val HoardCocoa = Color(0xFF7E4E30)
val HoardCocoaDeep = Color(0xFF4A2A14)
val HoardEspresso = Color(0xFF411C03)
val HoardLatte = Color(0xFFD7A57C)
val HoardMatcha = Color(0xFF9CC054)
val HoardMatchaLight = Color(0xFFB4D866)
val HoardMatchaDeep = Color(0xFF5E7A24)
val HoardMatchaPale = Color(0xFFE4F0CB)
val HoardMatchaNight = Color(0xFF2E3E17)
val IOSRedLight = Color(0xFFFF3B30)
val IOSRedDark = Color(0xFFFF453A)

// Light: white-first. The canvas is pure white — glass carries the depth.
val IOSGroupedBgLight = Color(0xFFFFFFFF)
val IOSCardLight = Color(0xFFFFFFFF)
val IOSFieldGrayLight = Color(0xFFE5E5EA)
val IOSSeparatorLight = Color(0xFFC6C6C8)
val IOSSecondaryLabelLight = Color(0xFF6C6C70)

// Dark: black base + elevated cards.
val IOSGroupedBgDark = Color(0xFF000000)
val IOSCardDark = Color(0xFF1C1C1E)
val IOSFieldGrayDark = Color(0xFF38383A)
val IOSSeparatorDark = Color(0xFF38383A)
val IOSSecondaryLabelDark = Color(0xFFAEAEB2)
val IOSSegmentThumbDark = Color(0xFF636366)

// iMessage bubbles.
val IOSIncomingLight = Color(0xFFE5E5EA)
val IOSIncomingDark = Color(0xFF262629)
