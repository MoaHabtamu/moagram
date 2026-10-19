#!/usr/bin/env python3
"""Execute the production date-picker callbacks with a sender that records their arguments.

This isolates the Android view without rewriting its callback bodies or requiring a login.
"""
import re
import subprocess
import tempfile
from pathlib import Path

repository = Path(__file__).resolve().parents[2]
source = repository / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java"
text = source.read_text()
signature = "public void didSelectDate(boolean notify, int scheduleDate, int scheduleRepeatPeriod)"
callbacks = []
for match in re.finditer(re.escape(signature), text):
    opening = text.index("{", match.end())
    depth = 1
    end = opening + 1
    while depth:
        depth += (text[end] == "{") - (text[end] == "}")
        end += 1
    callbacks.append((text.count("\n", 0, match.start()) + 1, text[match.start():end]))

assert len(callbacks) >= 4, "The compose scheduling entry points were not found"
java = ["""
public class ScheduleCallbackTest {
    static class Preview {
        void dismiss(boolean value) {}
        void dismissInstant() {}
    }
    static class Sender {
        Preview messageSendPreview;
        int calls, date, repeat;
        boolean silent;
        boolean sendMessageInternal(boolean notify, int scheduleDate, int scheduleRepeatPeriod, long stars, boolean confirm) {
            calls++;
            date = scheduleDate;
            repeat = scheduleRepeatPeriod;
            silent = !notify;
            return false;
        }
    }
"""]
for index, (_, callback) in enumerate(callbacks):
    java.append(f"static class Callback{index} extends Sender {{ {callback} }}")
java.append("public static void main(String[] args) {")
for index, (line, _) in enumerate(callbacks):
    java.append(f"""
    for (int period : new int[] {{0, 86400, 604800, 1209600, 2592000, 7862400, 15724800, 31536000}}) {{
        Callback{index} callback = new Callback{index}();
        callback.didSelectDate(false, 1800001000, period);
        if (callback.calls != 1 || callback.date != 1800001000 || callback.repeat != period || !callback.silent) {{
            throw new AssertionError("Scheduling callback at source line {line} discarded selection: expected repeat=" + period + ", actual=" + callback.repeat);
        }}
    }}
""")
java.append(f'System.out.println("Schedule callbacks: {len(callbacks) * 8} checks passed");')
java.append("}}")
with tempfile.TemporaryDirectory(prefix="moagram-schedule-callbacks-") as temporary:
    path = Path(temporary) / "ScheduleCallbackTest.java"
    path.write_text("\n".join(java))
    subprocess.run(["javac", "-d", temporary, str(path)], check=True)
    subprocess.run(["java", "-cp", temporary, "ScheduleCallbackTest"], check=True)
