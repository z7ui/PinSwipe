package com.z7ui.pinswipe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 演示页：一个和锁屏同样布局的假数字键盘。
 *
 * 存在的意义是**在碰真锁屏之前把逻辑跑通**。它和模块共用同一个
 * {@link SwipePadController}，而且挂点也一样（键自己的 onTouchEvent），
 * 所以这里手感对了，锁屏上就是对的。
 *
 * 用法：在这个页面上用手指按住一位数字、不要抬手，一路划过去，
 * 中间那行会按顺序显示每一位 —— 单点仍然只输入一位，这是关键。
 *
 * 底部三个按钮：重置演示 / 清除模块日志 / 关于。
 */
public class DemoActivity extends Activity {

    private static final String TAG = "PinSwipeDemo";

    /** 逐事件的触摸轨迹日志。默认关掉：一次手势能刷几十行，没必要。 */
    private static final boolean LOG_TOUCH_EVENTS = false;

    /** 模块写日志的地方（LSPosed 的日志目录，所有模块共用这一份）。 */
    private static final String LOG_DIR = "/data/adb/lspd/log";

    private final StringBuilder entered = new StringBuilder();
    private final StringBuilder order = new StringBuilder();
    private TextView dots;
    private TextView trace;
    private TextView logSize;
    private boolean clearing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        int pad = dp(16);
        root.setPadding(pad, dp(40), pad, dp(16));

        TextView title = new TextView(this);
        title.setText(R.string.demo_title);
        title.setTextColor(0xFF8AB4F8);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("按住一位数字别松手，一路划过去。单点应该仍然只输入一位。");
        hint.setTextColor(0xFF9AA0A6);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hint.setPadding(0, dp(6), 0, 0);
        root.addView(hint);

        dots = new TextView(this);
        dots.setTextColor(Color.WHITE);
        dots.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
        dots.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dotsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(76));
        dotsLp.topMargin = dp(10);
        root.addView(dots, dotsLp);

        LinearLayout padView = new LinearLayout(this);
        padView.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams padLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(padView, padLp);

        List<View> digits = new ArrayList<>(10);
        List<View> aux = new ArrayList<>(1);

        int[][] rows = {{1, 2, 3}, {4, 5, 6}, {7, 8, 9}, {-2, 0, -1}};
        for (int[] row : rows) {
            LinearLayout rowView = new LinearLayout(this);
            rowView.setOrientation(LinearLayout.HORIZONTAL);
            rowView.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            for (int value : row) {
                if (value == -2) {
                    rowView.addView(new View(this), cell());
                } else {
                    KeyView key = new KeyView(value);
                    rowView.addView(key, cell());
                    if (value >= 0) {
                        digits.add(key);
                    } else {
                        aux.add(key);
                    }
                }
            }
            padView.addView(rowView);
        }

        SwipePadController controller = new SwipePadController(digits, aux,
                new SwipePadController.KeySink() {
                    @Override
                    public void onKeyTriggered(View key) {
                        // 和模块里的 press() 一模一样
                        Log.i(TAG, "trigger value=" + ((KeyView) key).value);
                        key.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        key.performClick();
                    }

                    @Override
                    public void onKeyHeld(View key) {
                        // 和模块里的 hold() 一模一样
                        Log.i(TAG, "hold value=" + ((KeyView) key).value);
                        key.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        key.performLongClick();
                    }
                });
        for (View key : digits) {
            ((KeyView) key).controller = controller;
        }
        for (View key : aux) {
            ((KeyView) key).controller = controller;
        }

        trace = new TextView(this);
        trace.setTextColor(0xFF9AA0A6);
        trace.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        trace.setPadding(0, dp(12), 0, 0);
        // 固定高度：文字变长不能让键盘跟着动，否则测试结果不可信
        trace.setHeight(dp(40));
        trace.setText("划过顺序：");
        root.addView(trace);

        logSize = new TextView(this);
        logSize.setTextColor(0xFF9AA0A6);
        logSize.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        logSize.setText(R.string.demo_log_reading);
        root.addView(logSize, wrap());

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(6), 0, 0);

        Button reset = new Button(this);
        reset.setText(R.string.demo_reset);
        reset.setOnClickListener(v -> {
            entered.setLength(0);
            order.setLength(0);
            renderDots();
        });
        buttons.addView(reset, weight());

        Button clearLog = new Button(this);
        clearLog.setText(R.string.demo_clear_log);
        clearLog.setOnClickListener(v -> clearModuleLog());
        buttons.addView(clearLog, weight());

        Button about = new Button(this);
        about.setText(R.string.demo_about);
        about.setOnClickListener(v -> showAbout());
        buttons.addView(about, weight());

        root.addView(buttons, wrap());

        setContentView(root);
        renderDots();
        refreshLogSize();
    }

    private final class KeyView extends Button {

        final int value;
        SwipePadController controller;

        KeyView(int value) {
            super(DemoActivity.this);
            this.value = value;
            setAllCaps(false);
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            setText(value >= 0 ? String.valueOf(value) : "⌫");
            setOnClickListener(v -> input(value));
            if (value < 0) {
                // ⌫ 的另一种用法：直接长按清空（和锁屏一致）
                setOnLongClickListener(v -> {
                    entered.setLength(0);
                    order.append('C');
                    renderDots();
                    return true;
                });
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (LOG_TOUCH_EVENTS) {
                int[] loc = new int[2];
                getLocationOnScreen(loc);
                Log.i(TAG, "key=" + value
                        + " action=" + event.getActionMasked()
                        + " raw=" + event.getRawX() + "," + event.getRawY()
                        + " local=" + event.getX() + "," + event.getY()
                        + " keyLoc=" + loc[0] + "," + loc[1]
                        + " size=" + getWidth() + "x" + getHeight());
            }
            // 和模块的挂点一致：先让控制器旁观，再走原生逻辑
            if (controller != null) {
                controller.onTouch(event);
            }
            return super.onTouchEvent(event);
        }
    }

    private void input(int value) {
        if (value >= 0) {
            if (entered.length() < 16) {
                entered.append(value);
            }
        } else if (entered.length() > 0) {
            entered.setLength(entered.length() - 1);
        }
        order.append(value >= 0 ? String.valueOf(value) : "⌫");
        renderDots();
    }

    private void renderDots() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entered.length(); i++) {
            sb.append('●');
        }
        dots.setText(sb.length() == 0 ? "—" : sb.toString());
        trace.setText("划过顺序：" + order + "    位数：" + entered.length());
    }

    // ---------------- 模块日志 ----------------

    /** 读一下模块日志现在占多大（需要 root）。 */
    private void refreshLogSize() {
        runRoot("du -sk " + LOG_DIR + " 2>/dev/null | cut -f1", out -> {
            if (isNumber(out)) {
                logSize.setText(getString(R.string.demo_log_size, humanSize(out)));
            } else {
                logSize.setText(R.string.demo_log_size_unknown);
            }
        });
    }

    /**
     * 清空模块日志。
     *
     * 用**截断**而不是删除：这两个文件正被 lspd 打开着，删掉它们空间要等 lspd 关闭
     * 才会真正释放，而截断立刻生效 —— 这正是"临时文件把盘堆满"的直接解法。
     */
    private void clearModuleLog() {
        if (clearing) {
            return;
        }
        clearing = true;
        logSize.setText(R.string.demo_log_clearing);
        String cmd = "b=$(du -sk " + LOG_DIR + " 2>/dev/null | cut -f1); "
                + "for f in " + LOG_DIR + "/modules_*.log " + LOG_DIR + "/verbose_*.log; do "
                + "[ -f \"$f\" ] && : > \"$f\"; done; "
                + "a=$(du -sk " + LOG_DIR + " 2>/dev/null | cut -f1); echo \"$b $a\"";
        runRoot(cmd, out -> {
            clearing = false;
            String[] parts = out == null ? new String[0] : out.trim().split(" ");
            if (parts.length < 2 || !isNumber(parts[0]) || !isNumber(parts[1])) {
                logSize.setText(R.string.demo_log_clear_failed);
                return;
            }
            long before = Long.parseLong(parts[0]);
            long after = Long.parseLong(parts[1]);
            logSize.setText(getString(R.string.demo_log_size, humanSize(String.valueOf(after))));
            new AlertDialog.Builder(this)
                    .setTitle(R.string.demo_clear_log)
                    .setMessage(getString(R.string.demo_log_cleared,
                            humanSize(String.valueOf(Math.max(before - after, 0)))))
                    .setPositiveButton(R.string.about_ok, null)
                    .show();
        });
    }

    /** 关于：作者、仓库、版本号。 */
    private void showAbout() {
        String version = "";
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            version = info.versionName + " (" + info.versionCode + ")";
        } catch (Throwable ignored) {
        }
        String message = getString(R.string.about_body)
                + "\n\n" + getString(R.string.about_author)
                + "\n" + getString(R.string.about_repo)
                + "\n" + getString(R.string.about_version, version);
        new AlertDialog.Builder(this)
                .setTitle(R.string.about_title)
                .setMessage(message)
                .setPositiveButton(R.string.about_ok, null)
                .show();
    }

    /** 跑一条 root 命令，stdout+stderr 一起收回并在主线程回调。 */
    private void runRoot(final String command, final Consumer<String> callback) {
        new Thread(() -> {
            String output = "";
            try {
                ProcessBuilder builder = new ProcessBuilder("su", "-c", command);
                builder.redirectErrorStream(true);
                Process process = builder.start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                process.waitFor();
                reader.close();
                output = sb.toString().trim();
            } catch (Throwable t) {
                Log.w(TAG, "root 命令失败: " + t);
            }
            final String result = output;
            runOnUiThread(() -> callback.accept(result));
        }).start();
    }

    private static boolean isNumber(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static String humanSize(String kbText) {
        long kb;
        try {
            kb = Long.parseLong(kbText.trim());
        } catch (Throwable t) {
            return kbText;
        }
        if (kb < 1024) {
            return kb + " KB";
        }
        return String.format(java.util.Locale.US, "%.1f MB", kb / 1024.0);
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        int m = dp(3);
        lp.setMargins(m, 0, m, 0);
        return lp;
    }

    private LinearLayout.LayoutParams cell() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        int m = dp(6);
        lp.setMargins(m, m, m, m);
        return lp;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
