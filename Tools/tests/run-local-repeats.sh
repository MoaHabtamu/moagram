#!/usr/bin/env bash
set -euo pipefail
repeat_repo="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$repeat_repo"
python3 Tools/tests/test-schedule-callbacks.py
python3 Tools/tests/test-passcode-flows.py
python3 Tools/tests/test-ghost-requests.py
python3 Tools/tests/test-photo-permissions.py
python3 Tools/tests/test-moagram-updates.py
python3 Tools/tests/test-update-feed.py
repeat_sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$repeat_sdk" ]]; then
    repeat_sdk="$(sed -n 's/^sdk.dir=//p' local.properties)"
fi
repeat_android="$repeat_sdk/platforms/android-36/android.jar"
repeat_classes="${REPEAT_CLASSES:-TMessagesProj/build/intermediates/javac/standalone/compileStandaloneJavaWithJavac/classes}"
if [[ ! -f "$repeat_classes/org/telegram/tgnet/TLRPC.class" ]]; then
    echo 'First run: ./gradlew :TMessagesProj:compileStandaloneJavaWithJavac'
    exit 1
fi
repeat_temp="$(mktemp -d)"
trap 'rm -rf "$repeat_temp"' EXIT
repeat_gson="${REPEAT_GSON_JAR:-/usr/share/java/gson-2.10.1.jar}"
if [[ ! -f "$repeat_gson" ]]; then
    repeat_gson="$(python3 - "${GRADLE_USER_HOME:-$HOME/.gradle}" <<'PY'
from pathlib import Path
import re
import sys

jars = list(Path(sys.argv[1]).glob(
    'caches/modules-2/files-2.1/com.google.code.gson/gson/*/*/gson-*.jar'))
if not jars:
    sys.exit('Gson not found. Compile the app first or set REPEAT_GSON_JAR.')
print(max(jars, key=lambda path: tuple(int(part) for part in re.findall(r'\d+', path.name))))
PY
)"
fi
repeat_classpath="$repeat_classes:$repeat_android:$repeat_gson"
javac -cp "$repeat_classpath" -sourcepath '' -d "$repeat_temp" \
    Tools/tests/jvm/android/util/Base64.java \
    Tools/tests/jvm/org/telegram/messenger/ApplicationLoader.java \
    Tools/tests/jvm/org/json/JSONObject.java Tools/tests/jvm/org/json/JSONArray.java \
    TMessagesProj/src/main/java/org/telegram/messenger/RepeatSchedule.java \
    TMessagesProj/src/main/java/org/telegram/messenger/LocalRepeatController.java \
    Tools/tests/RepeatScheduleTest.java Tools/tests/LocalRepeatProtocolTest.java Tools/tests/LocalRepeatQueueTest.java
java -cp "$repeat_temp:$repeat_classpath" RepeatScheduleTest
java -cp "$repeat_temp:$repeat_classpath" LocalRepeatProtocolTest
java -cp "$repeat_temp:$repeat_classpath" org.telegram.messenger.LocalRepeatQueueTest
