---
layout: main
---

# Find your audiobook position

Open the same book in Librera, open **Search**, and tap **Sync with audiobook**.
Allow microphone access, then play the audiobook near the device. An installed
Android speech recognition service is required. The service uses the device's
default language and may use a network connection. There is no separate language
picker in Librera; configure the device or recognition service for the audiobook
language. The installed service must support that language.

The search field shows the recognized speech. Possible passages appear below it.
You can select a result yourself. After two distinct, sufficiently confident
recognition updates agree on a page, Librera navigates there, highlights the
passage, and stops listening. Short or ambiguous phrases do not cause an automatic
jump. Recognition errors are tolerated, including wrong or missing Chinese
characters; simplified and traditional text are matched in their respective
scripts, without automatic transliteration.

Tap **Stop listening** to cancel. Closing the search dialog or leaving the reader
also stops listening. Sessions stop after two minutes; tap **Sync with audiobook**
again to continue.

Typed search supports partial words and Chinese substrings. To limit it to a
range of displayed pages, append a range, for example `river[10:20]`.

## Saved indexes

**Index books automatically on first open** builds the index in the background.
When disabled, the first search builds it. Later searches and reopened books reuse
the saved index. Changed book contents or layout rebuild the affected index.

Librera keeps at most 32 inactive/active indexes within a 128 MiB budget when
possible, evicting older indexes and obsolete layouts. Indexes currently in use
are protected until their books close, so active books can temporarily exceed
the budget. Eviction only means the next search rebuilds that index.

Use **Clear saved search indexes** in Preferences to reclaim this space. Indexes
for open books are removed after those books close. Saved indexes are private to
the app and excluded from Android backups.
