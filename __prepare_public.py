from pathlib import Path
import subprocess,shutil
source=Path(r'D:\Projects\SamsungMessagesApp')
target=Path(r'D:\Projects\JacquebMessagesPublic')
if target.exists() and any(target.iterdir()):
 raise RuntimeError('Refusing to overwrite an existing public staging directory')
target.mkdir(parents=True, exist_ok=True)
files=subprocess.check_output(['git','ls-files','-z'],cwd=source).split(b'\0')
files+=subprocess.check_output(['git','ls-files','--others','--exclude-standard','-z'],cwd=source).split(b'\0')
count=0
for raw in files:
 if not raw:continue
 relative=Path(raw.decode('utf-8').replace('/','\\'))
 if len(relative.parts)>1 and relative.parts[:2]==('.github','workflows'):
  continue
 item=source / relative
 if not item.is_file():continue
 output=target / relative
 output.parent.mkdir(parents=True,exist_ok=True)
 shutil.copy2(item,output)
 count+=1
workflow=target/'.github'/'workflows'/'android-build.yml'
workflow.parent.mkdir(parents=True,exist_ok=True)
workflow.write_text('''name: Android build\non:\n  push:\n    branches: [main]\n  pull_request:\n  workflow_dispatch:\njobs:\n  build:\n    runs-on: ubuntu-latest\n    permissions:\n      contents: read\n    steps:\n      - uses: actions/checkout@v4\n      - uses: actions/setup-java@v4\n        with:\n          distribution: temurin\n          java-version: '21'\n      - uses: android-actions/setup-android@v3\n      - name: Assemble preview APK\n        run: bash ./gradlew :app:assembleFossDebug --no-daemon\n      - uses: actions/upload-artifact@v4\n        with:\n          name: Jacqueb-Messages-debug\n          path: app/build/outputs/apk/foss/debug/*.apk\n''',encoding='utf-8',newline='\n')
notes=Path(r'D:\Projects\JacquebMessages-v0.9.18-release-notes.md')
notes.write_text('''# Jacqueb Messages v0.9.18\n\nInitial independent public developer preview of Jacqueb Messages, based on Fossify Messages (GPLv3).\n\nIncludes: floating SMS chat heads, shared persistent drafts, MMS download/retry interface, conversation themes and previews, animated transitions, keyboard message cascade, improved system insets and chat-head navigation behavior.\n\n**Installation:** Download the attached `Jacqueb-Messages-v0.9.18-debug.apk`. This is a debug-signed preview, not a Play Store or production-signed build. It uses Android package `me.jacqueb.messages.debug`. Installing over an APK signed with a different key may require uninstalling the existing build. Back up your messages and app data first.\n\n**Known limitations:** Carrier MMS, overlay behavior on custom Android ROMs, and all device navigation modes still need wider real-device testing.\n''',encoding='utf-8')
print('Public staging:',target,'files:',count+1,'release notes:',notes)
