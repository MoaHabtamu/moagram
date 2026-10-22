#!/usr/bin/env python3
"""Run production passcode dispatch and mode selection without an Android UI."""
from pathlib import Path
import subprocess
import tempfile


repository = Path(__file__).resolve().parents[2]
source = (repository / "TMessagesProj/src/main/java/org/telegram/ui/PasscodeActivity.java").read_text()


def method(signature):
    start = source.index(signature)
    opening = source.index("{", start)
    end, depth = opening + 1, 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


java = r"""
public class PasscodeFlowTest {
    static final int TYPE_MANAGE_CODE_SETTINGS = 0, TYPE_SETUP_CODE = 1,
        TYPE_ENTER_CODE_TO_MANAGE_SETTINGS = 2, TYPE_ENTER_CODE_TO_GRAM_WALLET = 3;
    static class SharedConfig {
        static final int PASSCODE_TYPE_PIN = 0, PASSCODE_TYPE_PASSWORD = 1;
        static int passcodeType, badPasscodeTries, saves;
        static void saveConfig() { saves++; }
    }
    static class HiddenChatsController {
        static final HiddenChatsController instance = new HiddenChatsController();
        boolean useTelegramPasscode;
        static HiddenChatsController getInstance(int account) { return instance; }
        boolean isUseTelegramPasscode() { return useTelegramPasscode; }
    }
    static class AndroidUtilities {
        static int delay;
        static void hideKeyboard(Object view) {}
        static void runOnUIThread(Runnable action) { action.run(); }
        static void runOnUIThread(Runnable action, int milliseconds) { delay = milliseconds; action.run(); }
    }
    static class CodeNumberField { void clearFocus() {} }
    static class CodeFieldContainer { CodeNumberField[] codeField = {new CodeNumberField()}; }
    static class Keyboard { void setEditText(Object field) {} }
    static class PasscodeActivity {
        boolean isHiddenChats;
        int currentAccount, type, currentPasswordType, finished, settingsOpened;
        Runnable onPasscodeSuccess, openedSettings, enteredCode;
        CodeNumberField passwordEditText = new CodeNumberField();
        CodeFieldContainer codeFieldContainer = new CodeFieldContainer();
        Keyboard keyboardView = new Keyboard();
        PasscodeActivity(int value) { type = value; }
        void finishFragment() { finished++; }
        void presentFragment(PasscodeActivity fragment, boolean remove) {
            if (fragment.type != TYPE_MANAGE_CODE_SETTINGS) throw new AssertionError("wrong settings screen");
            settingsOpened++;
        }
        void animateSuccessAnimation(Runnable action) { action.run(); }
    %s
    %s
    %s
    }
    static int checks;
    static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    static PasscodeActivity screen(boolean hidden, int type) {
        SharedConfig.badPasscodeTries = 3;
        SharedConfig.saves = 0;
        SharedConfig.passcodeType = SharedConfig.PASSCODE_TYPE_PIN;
        HiddenChatsController.instance.useTelegramPasscode = false;
        AndroidUtilities.delay = 0;
        PasscodeActivity screen = new PasscodeActivity(type);
        screen.isHiddenChats = hidden;
        return screen;
    }
    public static void main(String[] args) {
        PasscodeActivity hidden = screen(true, TYPE_ENTER_CODE_TO_MANAGE_SETTINGS);
        int[] hiddenCalls = {0};
        hidden.onPasscodeSuccess = () -> hiddenCalls[0]++;
        hidden.completePasscodeEntry();
        check(hiddenCalls[0] == 1, "hidden-chat callback lost");
        check(hidden.onPasscodeSuccess == null, "hidden callback must be consumed");
        check(hidden.finished == 1 && hidden.settingsOpened == 0, "hidden unlock opened app settings");
        check(SharedConfig.badPasscodeTries == 3 && SharedConfig.saves == 0, "hidden unlock changed app retry state");
        check(AndroidUtilities.delay == 350, "hidden screen must close before callback");
        hidden = screen(true, TYPE_ENTER_CODE_TO_MANAGE_SETTINGS);
        SharedConfig.passcodeType = SharedConfig.PASSCODE_TYPE_PASSWORD;
        check(hidden.isPinCode() && !hidden.isPassword(), "own hidden PIN must work with an app password");
        HiddenChatsController.instance.useTelegramPasscode = true;
        check(!hidden.isPinCode() && hidden.isPassword(), "inherited app password mode was lost");
        SharedConfig.passcodeType = SharedConfig.PASSCODE_TYPE_PIN;
        check(hidden.isPinCode() && !hidden.isPassword(), "inherited app PIN mode was lost");
        hidden.completePasscodeEntry();
        check(hidden.finished == 1 && hidden.settingsOpened == 0, "hidden fallback must only close");

        PasscodeActivity wallet = screen(false, TYPE_ENTER_CODE_TO_GRAM_WALLET);
        int[] walletCalls = {0};
        wallet.enteredCode = () -> walletCalls[0]++;
        check(wallet.isPinCode() && !wallet.isPassword(), "wallet app PIN mode was lost");
        SharedConfig.passcodeType = SharedConfig.PASSCODE_TYPE_PASSWORD;
        check(!wallet.isPinCode() && wallet.isPassword(), "wallet app password mode was lost");
        wallet.completePasscodeEntry();
        check(walletCalls[0] == 1 && wallet.enteredCode == null, "wallet callback lost or not consumed");
        check(wallet.finished == 1 && wallet.settingsOpened == 0, "wallet unlock opened passcode settings");
        check(SharedConfig.badPasscodeTries == 0 && SharedConfig.saves == 1, "wallet retry state was not reset");
        wallet = screen(false, TYPE_ENTER_CODE_TO_GRAM_WALLET);
        wallet.completePasscodeEntry();
        check(wallet.finished == 1 && wallet.settingsOpened == 0, "wallet without callback must close");

        PasscodeActivity settings = screen(false, TYPE_ENTER_CODE_TO_MANAGE_SETTINGS);
        int[] settingsCalls = {0};
        settings.openedSettings = () -> settingsCalls[0]++;
        settings.completePasscodeEntry();
        check(settings.settingsOpened == 1 && settings.finished == 0, "settings navigation changed");
        check(settingsCalls[0] == 1 && settings.openedSettings == null, "settings callback changed");
        check(SharedConfig.badPasscodeTries == 0 && SharedConfig.saves == 1, "settings retry state changed");
        System.out.println("Passcode flows: " + checks + " checks passed");
    }
}
""" % (method("private boolean isPinCode()"), method("private boolean isPassword()"),
       method("private void completePasscodeEntry()"))

with tempfile.TemporaryDirectory(prefix="moagram-passcode-") as directory:
    path = Path(directory) / "PasscodeFlowTest.java"
    path.write_text(java)
    subprocess.run(["javac", "-d", directory, str(path)], check=True)
    subprocess.run(["java", "-cp", directory, "PasscodeFlowTest"], check=True)
