# DESIGN.md — Porch Light

The design language for Jibaro Family Safety, written to be reused across the other apps in
the ecosystem. Read this before changing anything visual. Values here are copied from the
theme files; if they ever disagree, the code is what ships and this file needs updating.

Theme code lives in `app/src/main/java/com/example/familysafety/ui/theme/`:
`Color.kt`, `StatusColors.kt`, `PersonColors.kt`, `Type.kt`, `Shape.kt`, `Spacing.kt`,
`Motion.kt`, `Theme.kt`.

## The idea

The app icon is a map pin around a small house with its window lit: a porch light left on.
"Jibaro" refers to the Puerto Rican countryside farmer, and jíbaro casitas were painted
bright "so people could see their houses in the dark" (Daniel Winterbottom, on Puerto Rican
casita gardens). A family-safety app does the same job: it lets family find each other at
night.

That idea sets the overall feel: pine night, one warm light, plain construction. It is not
applied as decoration elsewhere. In particular, people's colours are chosen for legibility,
not culture (see *Person colours*).

## Principles

1. **Night is the ground.** Deep pine, not black and not navy. The light theme is the same
   pine thinned to a whitewash, not an office grey.
2. **One porch light per screen.** Amber marks the single most important thing on a screen.
   If two things want amber, one of them is wrong.
3. **People are the colour.** Each person has one colour and keeps it everywhere. Everything
   around them stays quiet.
4. **Built from what's at hand.** Plain rows, real words, few icons. A container only where
   something truly stands apart.
5. **Specific over default.** Every choice should have a reason that belongs to this app. The
   recognisable defaults of generated UIs are listed under *Avoid*.

## Colour

All neutrals lean toward pine. Contrast ratios are WCAG 2.x, quoted against the background
named.

### Neutrals

| Role | Dark (night) | Light (day) |
|---|---|---|
| Background | `#101A15` | `#F2F5F0` |
| Surface (cards, sheets) | `#16231C` | `#FFFFFF` |
| Raised surface | `#1D2C24` | `#E7EDE6` |
| Text | `#EEF2EC` (14.4:1) | `#15231B` (14.8:1) |
| Secondary text | `#A3B2A8` (7.3:1) | `#536358` (5.8:1) |
| Disabled text | `#5E6E63` | `#97A69B` |
| Control border (`outline`) | `#64796B` (≥ 3.1:1) | `#7A8C7F` (≥ 3.0:1) |
| Hairline (`outlineVariant`) | `#2C3D33` | `#D3DDD4` |

`outline` is what Material draws around text fields and outlined buttons, so it must clear
3:1 for control boundaries. `outlineVariant` is for card edges and dividers and stays quiet.

### Porch light (accent)

| | Dark | Light |
|---|---|---|
| Amber | `#E8C858` (10.9:1 on background) | `#8A6A0F` (5.1:1 on white) |

Text on an amber fill is dark: `#0F1A14`.

### Status

Status colours mean state, never decoration. Light splits each into a text value (4.5:1) and
an indicator value for dots and icons (3:1). Use `statusColors.*Text` or `*Indicator`.

| Signal | Dark | Light text | Light indicator |
|---|---|---|---|
| Connected / healthy | `#35B378` | `#1B7E4D` | `#219A5E` |
| Warning | `#E2B45F` | `#A36000` | `#C77800` |
| Danger | `#FF5B66` | `#C32B36` | `#E03E4A` |

### Person colours

Twelve colours at one perceived brightness (OKLCH lightness 0.74), spread as far apart as
the colour wheel allows. Source of truth: `PersonColors.kt` (`PersonPalette`).

| Name | Fill | Text on light | Pattern |
|---|---|---|---|
| Red | `#FB8083` | `#A8353E` | Horizontal lines |
| Tangerine | `#F68953` | `#A14200` | Vertical lines |
| Marigold | `#E49921` | `#885700` | Diagonal / |
| Olive | `#C4AB11` | `#736300` | Diagonal \ |
| Lime | `#93BB48` | `#516F00` | Grid |
| Jade | `#54C57A` | `#00773B` | Crosshatch |
| Teal | `#00C6AB` | `#007463` | Dots |
| Lagoon | `#00C1D2` | `#00717B` | Checker |
| Sky | `#08BAF8` | `#006C93` | Zigzag |
| Periwinkle | `#87A7FF` | `#3F5BB8` | Bricks |
| Orchid | `#C290F5` | `#7947A5` | Dashes |
| Rose | `#EA82C5` | `#99387B` | Rings |

Rules:

- **One source.** Every place a person's colour appears (avatar, map pin, history trail,
  chat name) goes through `PersonPalette.forMember(memberId, colorHue)`. Never compute a
  person's colour from a hue directly; that is how one person once had five different
  shades.
- **Initials are dark** (`OnPersonColor`, `#0F1A14`, ≥ 7.2:1 on every fill).
- **Names as text** use `PersonColor.themedText()`: the fill on dark, the darker shade on
  light (≥ 5.1:1).
- **Map trails** use the darker shade in both themes; the street tiles are always light.
- **Stored as a hue.** `FamilyMember.colorHue` still holds an HSL hue so older builds keep
  working. `forHue` converts it to the perceptual wheel before matching; the wheels do not
  line up (HSL red, 0°, is about 29° perceptually). Picking a colour stores its `storedHue`.
- **Automatic colours** (members who never chose) come from the member ID by index, so they
  spread evenly across the twelve.
- People and status signals may share hues. They never share a context: a person is always a
  circle with an initial beside a name.

### Colour-blind patterns

No twelve colours stay distinct for everyone with red-green colour blindness, so each colour
has a pattern (table above). **Settings → Appearance → Colour-blind patterns** turns them on.

- Drawn as a ring at the edge of initial avatars, so lines never cross the initial.
- Drawn in the body of map pins, which widens while the setting is on.
- Stored on the phone only (`PersonPatternsPreference`), never synced: it is the need of the
  person looking, not of the people shown.
- Patterns are drawn with Android's canvas by `PersonPattern.draw`, shared by Compose and
  the map's bitmaps. Add a pattern there, not as a one-off.

## Type

Three faces, each with one job. All are SIL Open Font License, **bundled** in `res/font`
(licences in `assets/licenses`), never fetched through the downloadable-fonts provider: the
app makes no network request for its type and renders the same offline.

| Face | Job | Material roles |
|---|---|---|
| Bitter (600, 700) | Headings only | display*, headline*, titleLarge |
| Atkinson Hyperlegible Next (400–700) | Everything read or tapped | title Medium/Small, body*, label* |
| Atkinson Hyperlegible Mono (400, 600) | Codes, fingerprints, recovery words, coordinates | `NumericFamily` |

Atkinson was drawn by the Braille Institute so easily confused letters (I l 1, O 0) stay
distinct. That is why zeros are slashed, including in ordinary text. **Kept on purpose**; a
plain-zero variant exists if that decision changes.

The type scale in `Type.kt` is fixed: body 15sp, labels down to 11sp.

## Shape

Corner radius says what kind of thing something is. Pick by role, never by eye.

| Radius | For |
|---|---|
| 10 dp | Anything tapped or holding content: buttons, fields, cards, menus, panels |
| 20 dp | Things that slide over the screen: dialogs, bottom sheets |
| Circle | People (avatars) and icon-only buttons, including icon-only FABs |
| Pill | Small status tags and badges only |
| Square | Lines, not objects: progress bars (`StrokeCap.Butt`), dividers |

Chat bubbles keep their own asymmetric shape as a deliberate exception.

All five Material shape sizes are set in `Shape.kt`, so no Material default (4 dp fields,
28 dp dialogs) leaks through. Material's `Button` and `OutlinedButton` default to a full pill
outside that scale, so every call passes `shape = ButtonShape`.

**Nested corners:** when a rounded element sits within `p` of a container's corner and
`p` is smaller than the container's radius `r`, give it radius `r - p` (or `r` when flush).
Once `p ≥ r`, it takes its own role's radius.

## Spacing

`Spacing.kt`: 4, 8, 16, 24, 32, 48 dp. Screen gutters are 16 dp.

## Layout and components

- **Lists before cards.** A card says "this is a separate object you can pick up". People,
  conversations and settings are lists of the same kind of thing: rows with hairline
  dividers. Keep cards for things that truly stand apart (a warning, a file tile).
- **An icon has to earn its place.** Navigation and universally recognised actions only;
  otherwise a word.
- **The floating action** (`FloatingActionLabelButton`) is a flat raised surface with a
  hairline and shadow, 10 dp corners. No gradient.

## Motion

- **Kept on purpose:** the soft amber glow behind the selected bottom-tab icon (the screen's
  porch light) and the shimmer on loading file thumbnails.
- Both stop animating when the phone's *Remove animations* setting is on, or Battery Saver
  has switched animations off (`rememberReducedMotion`): the glow appears instantly and the
  shimmer holds still.
- No other decorative motion: no sheen, glow or gradient unless it says something.

## Words

Write from the user's side: name things by what people recognise, say exactly what a button
does, and explain errors with what to do next. No marketing words in the UI ("seamless",
"peace of mind", "empower").

## Avoid

The defaults that make an app read as generated:

- Inter, Space Grotesk, Geist, or one italic serif word in a sans heading
- Purple-to-blue gradients, lavender accents, coloured glows
- Tailwind "slate" greys under a coloured theme
- The same bordered card around every block; a coloured stripe down a card's edge
- An icon on every label; emoji as icons
- Sheen, glass or shimmer for its own sake
- Several unrelated corner radii
- Swapping one default for the next (cream background, serif display and terracotta is
  another one)

## Ecosystem

Shared by every app: the pine neutrals, the three typefaces, the shape and spacing rules,
the status colours, and the twelve person colours with their patterns.

Owned by each app: one accent. Family Safety's is the porch light. Another app takes a
different accent (avoid reusing a person colour as an app accent inside the same app). Side
by side on a home screen they should read as houses on one street.

## Decision log

| Decision | Why |
|---|---|
| Pine neutrals replace Tailwind slate | Slate was the most recognisable default in the app |
| Bundled Bitter + Atkinson instead of Inter | Inter is the most-cited tell; bundling keeps type private and offline |
| Radius by role (10 / 20 / circle / pill / square) | Nine unrelated radii read as assembled |
| Metal sheen removed from the floating button | Decoration that said nothing |
| Person colours: version A (equal brightness) | Calmer; chosen over the alternating-brightness version |
| Person colours use the whole wheel | People and status signals never share a context |
| Person colours are not culturally themed | The jíbaro idea sets the feel; people's colours need legibility |
| Colour-blind patterns as a per-phone setting | No colour set works for all colour vision; patterns do |
| Tab glow kept | It marks where you are: the tab bar's porch light |
| Thumbnail shimmer kept | It signals loading; respects Remove animations |
| Slashed zeros kept (for now) | Part of Atkinson's legibility design |

The visual reference pages for this document were published as private Claude artifacts:
*Porch Light Design Language* and *Family Colour Test Sheet*.
