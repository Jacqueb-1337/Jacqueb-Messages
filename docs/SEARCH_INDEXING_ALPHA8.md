# Search indexing and contact-aware semantic search

## Root cause
Earlier initialization bulk imported provider messages into Room only when
appRunCount was 1. Messages from unvisited threads were missing from both
ordinary search and the semantic index.

## Implementation
- Separate private no-backup text catalog from Android SMS and MMS providers,
  with distinct keys for SMS and MMS even when numeric IDs collide.
- A background worker imports existing history, reconciles deletions/updates,
  and refreshes when provider data changes. No conversation needs opening.
- Keyword search works without a model. Semantic vectors are calculated from
  the complete catalog, not from the partial Room conversation cache.
- Query parsing recognizes contact/group names as thread filters. Xbox Boss
  searches Xbox-related messages in Boss conversations. Game console Boss
  searches for messages semantically similar to game console, within Boss.
  From Boss finds the contact's conversation messages.
- Safe threads are excluded from search and embedding. No Safe encryption
  or migration changes are included in this branch.
- Search UI refreshes automatically after the background catalog scan.

## Validation
- Android emulator imported all 37 existing synthetic SMS without opening any
  conversation. After adding another SMS under a synthetic #1 Boss contact,
  Xbox boss returned it without opening the thread.
- Game console boss also returned the synthetic Xbox SMS with the Accurate
  MiniLM L6 engine installed.
- Real-device carrier MMS indexing still requires validation. Very large
  histories may need paged ingestion and scheduling optimization.
