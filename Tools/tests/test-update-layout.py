#!/usr/bin/env python3
"""Catch the overloaded-layout-helper regression that collapsed the update page.

Compile the page's actual scroll layout expression with production LayoutHelper
and minimal Android layout-parameter doubles. Real rendering is checked separately
on an emulator; this guards Java's int-gravity versus float-weight overload choice.
"""
from pathlib import Path
import re
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
page = root / 'TMessagesProj/src/main/java/org/telegram/ui/MoagramUpdatesActivity.java'
helper = root / 'TMessagesProj/src/main/java/org/telegram/ui/Components/LayoutHelper.java'
expression = re.search(r'root\.addView\(scroll,\s*(LayoutHelper\.createLinear\([^)]*\))\);', page.read_text())
assert expression, 'Update-page scroll layout expression not found; adapt the layout regression test'
stubs = {
    'android/annotation/SuppressLint': 'public @interface SuppressLint { String[] value(); }',
    'android/view/Gravity': '''public class Gravity {
        public static final int LEFT=3, RIGHT=5;
        public static int getAbsoluteGravity(int g,int d) { return g; }
    }''',
    'android/view/View': '''public class View {
        public static class MeasureSpec {
            public static final int EXACTLY=0x40000000;
            public static int makeMeasureSpec(int s,int m) { return s | m; }
        }
    }''',
    'android/view/ViewGroup': '''public class ViewGroup extends View {
        public static class LayoutParams {
            public int width,height;
            public LayoutParams(int w,int h) { width=w; height=h; }
        }
        public static class MarginLayoutParams extends LayoutParams {
            public int leftMargin,topMargin,rightMargin,bottomMargin;
            public MarginLayoutParams(int w,int h) { super(w,h); }
            public void setMargins(int l,int t,int r,int b) {
                leftMargin=l; topMargin=t; rightMargin=r; bottomMargin=b;
            }
        }
    }''',
    'android/widget/FrameLayout': '''public class FrameLayout extends android.view.ViewGroup {
        public static class LayoutParams extends MarginLayoutParams {
            public int gravity=-1;
            public LayoutParams(int w,int h) { super(w,h); }
            public LayoutParams(int w,int h,int g) { super(w,h); gravity=g; }
        }
    }''',
    'android/widget/ScrollView': 'public class ScrollView extends FrameLayout {}',
    'android/widget/LinearLayout': '''public class LinearLayout extends android.view.ViewGroup {
        public static class LayoutParams extends MarginLayoutParams {
            public float weight;
            public int gravity=-1;
            public LayoutParams(int w,int h) { super(w,h); }
            public LayoutParams(int w,int h,float v) { super(w,h); weight=v; }
        }
    }''',
    'android/widget/RelativeLayout': '''public class RelativeLayout extends android.view.ViewGroup {
        public static class LayoutParams extends MarginLayoutParams {
            public LayoutParams(int w,int h) { super(w,h); }
            public void addRule(int a) {}
            public void addRule(int a,int b) {}
        }
    }''',
    'androidx/core/view/ViewCompat': '''public class ViewCompat {
        public static final int LAYOUT_DIRECTION_RTL=1, LAYOUT_DIRECTION_LTR=0;
    }''',
    'org/telegram/messenger/AndroidUtilities': '''public class AndroidUtilities {
        public static int dp(float v) { return Math.round(v); }
    }''',
    'org/telegram/messenger/LocaleController': 'public class LocaleController { public static boolean isRTL; }',
}
with tempfile.TemporaryDirectory(prefix='moagram-layout-') as temporary:
    temp = Path(temporary)
    sources = [helper]
    for name, body in stubs.items():
        path = temp / (name + '.java')
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('package ' + name.rsplit('/', 1)[0].replace('/', '.') + ';\n' + body)
        sources.append(path)
    test = temp / 'UpdateLayoutTest.java'
    test.write_text('''import org.telegram.ui.Components.LayoutHelper;
public class UpdateLayoutTest {
    public static void main(String[] args) {
        android.widget.LinearLayout.LayoutParams scroll = ''' + expression[1] + ''';
        if (scroll.width != LayoutHelper.MATCH_PARENT) throw new AssertionError("Scroll area must span the page");
        if (scroll.height == 0 && scroll.weight <= 0) throw new AssertionError(
            "Update content collapses to zero height: the third helper argument selected gravity instead of weight");
        System.out.println("Update-page layout regression passed: scroll height=" + scroll.height + ", weight=" + scroll.weight);
    }
}
''')
    sources.append(test)
    subprocess.run(['javac', '-d', str(temp), *map(str, sources)], check=True)
    subprocess.run(['java', '-cp', str(temp), 'UpdateLayoutTest'], check=True)
