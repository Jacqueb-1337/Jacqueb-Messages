# Fossify Commons 7.1.3 – Jacqueb Messages local branding variant

This folder contains a reproducibly patched GPLv3 Android AAR derived from `org.fossify:commons:7.1.3` by [FossifyOrg/commons](https://github.com/FossifyOrg/commons). Source code: https://github.com/FossifyOrg/commons . The original library is copyright its respective contributors and subject to GNU GPL v3.0.

**Reason for the local fork:** Original Commons 7.1.3 `BaseSimpleActivity.onCreate` displays a misleading `You are using a fake version` popup if the installed package name does not start with `org.fossify.`. It triggers every 100 app launches OR randomly on approximately 1/51 launches. `com.jacqueb.messages` is an independent GPL-based messaging app and not presented as an official Fossify installation; the check is inapplicable. Other app package security and signing checks are unchanged.

**Exact patch:** `tools/patch_fossify_commons.py` verifies the SHA-256 of the original published AAR, then replaces the UTF-8 constant `org.fossify.` with equal-length `com.jacqueb.` in three Commons bytecode classes:

- `org/fossify/commons/activities/BaseSimpleActivity.class`
- `org/fossify/commons/compose/extensions/ActivityExtensionsKt.class`
- `org/fossify/commons/compose/extensions/ComposeActivityExtensionsKt.class`

All other AAR entries are unchanged, and the copied Maven POM preserves upstream transitive dependencies and license information. The patch script and upstream source specify the complete changes for GPL compliance and reproducibility. Regenerate using the source library in Gradle's Maven cache and run `py tools/patch_fossify_commons.py`.

Do not update the original Commons version without reviewing and rerunning the patch. The pinned local Maven coordinate `org.fossify:commons:7.1.3-jacqueb.1` ensures builds do not silently regress to the upstream warning.
