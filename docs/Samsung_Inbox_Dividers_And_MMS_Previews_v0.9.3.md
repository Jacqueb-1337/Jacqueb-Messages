# Inbox divider and MMS preview fixes v0.9.3

Date: 2026-10-28. Android versionCode 32.

## Divider alignment
The original layout constrained the separator to the row's padded bottom; the row itself has 14dp bottom padding, making the line appear about 14dp too high. It now translates 13dp down to the actual row boundary, and is constrained to the conversation title's start rather than a separate hardcoded offset. Consistent alignment for all rows including selected ones.

## Attachment previews
MMS without a text part but with media previously produced an empty inbox snippet. The conversation database queries for active, archived and recycle-bin threads now display Attachment for a latest empty-body MMS. The Android-provider fallback also produces Attachment for empty-body MMS with actual attachments.

A pre-existing comparison of SMS message timestamps in milliseconds against MMS date timestamps in seconds is corrected: both use milliseconds, avoiding an older SMS overriding newer MMS snippets.

No data migration or message changes. Basic SMS/MMS send-receive and chat-head behavior preserved.

## Validation
Gradle assembleFossDebug passes. On-device visual and image-only MMS verification are still outstanding.
