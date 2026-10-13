package com.z7ui.pinswipe;

import android.app.Activity;
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

import java.util.ArrayList;
import java.util.List;

/**
 * 演示页：一个和锁屏同样布局的假数字键盘。
 *
 * 存在的意义是**在碰真锁屏之前把逻辑跑通**。它和模块共用同一个
 * {@link SwipePadController}，而且挂点也一样（键自己的 onTouchEvent），
 * 所以这里手感对了，锁屏上就是对的。
 *
 * 用法：在这个页面上用手指按住一位数字、不要抬手，一路划过去，
 * 中间那行会按顺序显示每一位 —— 单点仍然只输入一位，这是关键。
 */
public class DemoActivity extends Activity {

    private static final String TAG = "PinSwipeDemo";

    private final StringBuilder entered = new StringBuilder();
    private final StringBuilder order = new StringBuilder();
    private TextView dots;
    private TextView trace;

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

        Button clear = new Button(this);
        clear.setText("清空");
        clear.setOnClickListener(v -> {
            entered.setLength(0);
            order.setLength(0);
            renderDots();
        });
        root.addView(clear, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        renderDots();
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
            int[] loc = new int[2];
            getLocationOnScreen(loc);
            Log.i(TAG, "key=" + value
                    + " action=" + event.getActionMasked()
                    + " raw=" + event.getRawX() + "," + event.getRawY()
                    + " local=" + event.getX() + "," + event.getY()
                    + " keyLoc=" + loc[0] + "," + loc[1]
                    + " size=" + getWidth() + "x" + getHeight());
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
