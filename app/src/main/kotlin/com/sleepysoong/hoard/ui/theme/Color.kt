package com.sleepysoong.hoard.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Hoard palette. Solid colors only — no gradients anywhere in the app.
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
val HoardLatte = Color(0xFFD7A57C)
val HoardMatcha = Color(0xFF9CC054)
val HoardMatchaLight = Color(0xFFB4D866)
val HoardMatchaDeep = Color(0xFF5E7A24)
val HoardMatchaPale = Color(0xFFE4F0CB)
val HoardMatchaNight = Color(0xFF2E3E17)
val HoardRedLight = Color(0xFFFF3B30)
val HoardRedDark = Color(0xFFFF453A)

// Light: white-first. The canvas is pure white — glass carries the depth.
val HoardGroupedBgLight = Color(0xFFFFFFFF)
val HoardCardLight = Color(0xFFFFFFFF)
val HoardFieldGrayLight = Color(0xFFE5E5EA)
val HoardSeparatorLight = Color(0xFFC6C6C8)
val HoardSecondaryLabelLight = Color(0xFF6C6C70)

// Dark: black base + elevated cards.
val HoardFieldGrayDark = Color(0xFF38383A)
val HoardSeparatorDark = Color(0xFF38383A)
val HoardSecondaryLabelDark = Color(0xFFAEAEB2)

// Incoming message bubbles.
