MMS retry and send-time attachment compression

- Failed-message retry now restores its attachment as well as its text into the composer. The status label has an explicit click handler and says Tap to review and retry. SMS and MMS IDs are distinguished when recovering the original.
- Compression is offered ONLY after pressing Send, including retries. Selecting an attachment, viewing a stored message, and restoring a failed message do not compress anything.
- On Send, attachment size is checked against the smaller of the app setting and the selected SIM carrier maximum, with room reserved for the MMS envelope. No limit in settings disables only the app ceiling, not the carrier ceiling.
- Oversized photos, videos and audio offer compression of a temporary copy. Accepting continues the pending send. Originals remain untouched. Unsupported file types explain that a smaller file is required.
- Video/audio encoding uses Media3 Transformer. Video uses a bounded bitrate and decreasing resolution fallbacks. Every output is checked for actual size before it can be sent. Impossible targets fail clearly rather than silently sending oversized media. Very long videos may need shortening.
- MMS-provider attachments use descriptor/stream sizing when picker metadata is absent. Attachment-read failures abort sending instead of continuing without the attachment. A failed retry retains the original failed provider message; originals are removed only after a successful replacement.

Validation: real Android emulator tests use a generated 1.5 MB, five-second H264/AAC test clip and a 100 KB target. Verify dialog cancel, actual output bound, video and audio tracks, retained source, no-limit carrier ceiling, MMS provider sizing, actual failed-video status-label tap, no compression before Send, and original retention after failed retry. Carrier delivery to a real recipient is not automatically tested.

Test command: adb shell am instrument -w -e suite mms com.jacqueb.messages.dev.test/org.fossify.messages.SafeRegressionInstrumentation
Build uses the normal JDK21 configuration, with a per-command override of Xmx1200m and MaxMetaspaceSize600m for the larger Media3 compilation. The project metaspace cap is now 600m (heap remains 900m by default), since the historical 400m cap failed compilation; no global machine settings were changed.

Synthetic fixture generation: ffmpeg -f lavfi -i testsrc2=size=640x360:rate=24 -f lavfi -i sine=frequency=440:sample_rate=44100 -t 5 -c:v libx264 -b:v 3M -c:a aac -b:a 128k mms-test.mp4

Source: messaging/MmsAttachmentPreparer.kt, activities/ThreadActivity.kt, adapters/ThreadAdapter.kt, helpers/ImageCompressor.kt, receivers/MmsSentReceiver.kt, messaging/MessagingUtils.kt. Internal host activity is debug-only. Test media lives only in androidTest assets.

M5 is Android MMS_ERROR_IO_ERROR and can include PDU size/read failures. The screenshot's original failure trace was no longer available. This release fixes outgoing size handling and retry behavior without claiming that every possible M5 cause has been reproduced.

Delivery complete: GitHub prerelease v0.11.0-alpha.12, versionCode 66, signed certificate matches alpha11. Release page and APK both HTTP200; GitHub asset digest and phone download hash match the built APK. Emulator in-place release update and main launch passed. Owner phone install succeeded and reports alpha12/code66. Production commit 0a98fe1; build-metadata allowance commit abf2073. Final targeted regression log __alpha12_device_test.log has compression, retry_retention and retry_ui PASS. Signed release build log __alpha12_release_build.log. No carrier message was sent by the agent.
