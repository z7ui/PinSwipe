package com.z7ui.pinswipe;

import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.List;

/**
 * 「划过就输入」的状态机。
 *
 * 刻意做成**不依赖 Xposed 的纯逻辑**：同一份代码既跑在演示页里，
 * 也跑在锁屏的 hook 里，所以你在演示页验证过的手感就是锁屏上的手感。
 *
 * ## 为什么起点键要特殊处理
 *
 * 原生 {@code View.onTouchEvent} 的行为是：手指按在某个键上，只要没离开它的
 * touch slop，抬手时就会 {@code performClick()}；一旦离开 slop，按下状态就被取消，
 * 抬手也不会有任何输入。
 *
 * 所以「手指划过去」这个动作，系统那一路是**一声不吭**的 —— 这正是我们要补的。
 * 但补的时候必须避开重复：
 *
 *  - 起点键：只有确认手指已离开它的 slop（系统那一路上彻底失效）之后，才补它；
 *  - 其它键：手指离开时补（这些键根本没收到 DOWN，系统不可能自己点）；
 *  - 抬手：如果系统还会自己点起点键，就什么都不做，交给系统。
 *
 * 于是**点按路径完全不受影响** —— 单点一下仍然只输入一位。
 *
 * ## 长按
 *
 * 手指停在**附加键**（⌫）上超过系统长按时间，会另外回调一次
 * {@link KeySink#onKeyHeld}，并且这一下不再补短按 —— 锁屏上「长按 ⌫ 清空」
 * 就是这么接进来的。
 */
public final class SwipePadController {

    public interface KeySink {
        /** 这个键该被"按"一下了：演示页里走 performClick()，锁屏里也走 performClick()。 */
        void onKeyTriggered(View key);

        /** 手指停在这一个键上超过长按时间。只需要实现附加键（⌫）的语义。 */
        default void onKeyHeld(View key) {
        }
    }

    private final List<View> digitKeys;
    /** 额外的可划过键，目前只有 ⌫。只有这些键参与长按。 */
    private final List<View> auxiliaryKeys;
    private final KeySink sink;

    private final Rect rect = new Rect();
    private final int[] loc = new int[2];
    private int slop = -1;

    private boolean active;
    private View downKey;
    private boolean downPending;   // 起点键还没补
    private View pending;          // 手指当前所在键（可能为 null）
    private float lastX;
    private float lastY;

    private View holdKey;          // 已经挂上长按计时器的键
    private boolean holdFired;     // 当前 pending 键是否已经长按结算过

    private final Runnable holdCallback = new Runnable() {
        @Override
        public void run() {
            View key = pending;
            if (!active || key == null || key != holdKey) {
                return;
            }
            holdFired = true;
            try {
                sink.onKeyHeld(key);
            } catch (Throwable ignored) {
            }
        }
    };

    public SwipePadController(List<View> digitKeys, List<View> auxiliaryKeys, KeySink sink) {
        this.digitKeys = digitKeys;
        this.auxiliaryKeys = auxiliaryKeys;
        this.sink = sink;
    }

    /** 永远返回 false：纯粹旁观，绝不吞事件。 */
    public boolean onTouch(MotionEvent event) {
        try {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    begin(event);
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (active) {
                        move(event);
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (active) {
                        finish();
                    }
                    break;
                default:
                    break;
            }
        } catch (Throwable ignored) {
            // 任何情况下都不能影响原生输入
        }
        return false;
    }

    private void begin(MotionEvent event) {
        active = true;
        lastX = event.getRawX();
        lastY = event.getRawY();
        downKey = hit(lastX, lastY);
        downPending = downKey != null;
        pending = downKey;
        armHold(downKey);
    }

    private void move(MotionEvent event) {
        float x = event.getRawX();
        float y = event.getRawY();

        // 手指离开起点的 slop → 系统这一路彻底不会点它了，这时补它才安全
        if (downPending && !insideSlop(downKey, x, y)) {
            downPending = false;
            trigger(downKey);
        }

        // 快速划动时相邻两次事件之间可能跨过好几个键，插值采样免得漏掉
        int steps = sampleCount(lastX, lastY, x, y);
        for (int i = 1; i <= steps; i++) {
            float t = (float) i / steps;
            sample(lastX + (x - lastX) * t, lastY + (y - lastY) * t);
        }

        lastX = x;
        lastY = y;
    }

    private void sample(float x, float y) {
        View key = hit(x, y);
        if (key == pending) {
            return;
        }
        // 离开上一个键：长按结算过就不再补短按
        if (pending != null && pending != downKey && !holdFired) {
            trigger(pending);
        }
        cancelHold();
        pending = key;
        armHold(key);
    }

    private void finish() {
        cancelHold();
        // pending == downKey 且系统还会自己点 → 交给系统；长按结算过也不再补短按
        if (pending != null && !holdFired && !(pending == downKey && downPending)) {
            trigger(pending);
        }
        active = false;
        downKey = null;
        pending = null;
        downPending = false;
        holdFired = false;
    }

    /** 只有附加键（⌫）值得长按 —— 数字键长按在锁屏上没有语义。 */
    private void armHold(View key) {
        cancelHold();
        holdFired = false;
        if (key == null || auxiliaryKeys == null || !auxiliaryKeys.contains(key)) {
            return;
        }
        holdKey = key;
        try {
            key.postDelayed(holdCallback, longPressTimeout(key));
        } catch (Throwable ignored) {
            holdKey = null;
        }
    }

    private void cancelHold() {
        if (holdKey != null) {
            try {
                holdKey.removeCallbacks(holdCallback);
            } catch (Throwable ignored) {
            }
            holdKey = null;
        }
    }

    private static int longPressTimeout(View key) {
        try {
            return ViewConfiguration.get(key.getContext()).getLongPressTimeout();
        } catch (Throwable ignored) {
            return 500;
        }
    }

    private void trigger(View key) {
        if (key == null) {
            return;
        }
        try {
            sink.onKeyTriggered(key);
        } catch (Throwable ignored) {
        }
    }

    private View hit(float rawX, float rawY) {
        View v = find(digitKeys, rawX, rawY);
        return v != null ? v : find(auxiliaryKeys, rawX, rawY);
    }

    private View find(List<View> keys, float rawX, float rawY) {
        if (keys == null) {
            return null;
        }
        for (int i = 0; i < keys.size(); i++) {
            View key = keys.get(i);
            if (key == null || !key.isShown() || !key.isEnabled()) {
                continue;
            }
            key.getLocationOnScreen(loc);
            rect.set(loc[0], loc[1], loc[0] + key.getWidth(), loc[1] + key.getHeight());
            if (rect.contains((int) rawX, (int) rawY)) {
                return key;
            }
        }
        return null;
    }

    /** 和 View.onTouchEvent 用的是同一个判定：自己的范围向外扩一个 touch slop。 */
    private boolean insideSlop(View key, float rawX, float rawY) {
        if (key == null) {
            return false;
        }
        key.getLocationOnScreen(loc);
        rect.set(loc[0], loc[1], loc[0] + key.getWidth(), loc[1] + key.getHeight());
        int s = slop();
        rect.inset(-s, -s);
        return rect.contains((int) rawX, (int) rawY);
    }

    private int sampleCount(float x0, float y0, float x1, float y1) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        int step = Math.max(2, slop() / 2);
        int n = (int) (dist / step);
        return Math.max(1, Math.min(n, 32));
    }

    private int slop() {
        if (slop <= 0) {
            int value = 8;
            try {
                View sample = !digitKeys.isEmpty() ? digitKeys.get(0)
                        : ((auxiliaryKeys != null && !auxiliaryKeys.isEmpty()) ? auxiliaryKeys.get(0) : null);
                if (sample != null) {
                    value = ViewConfiguration.get(sample.getContext()).getScaledTouchSlop();
                }
            } catch (Throwable ignored) {
            }
            slop = Math.max(4, value);
        }
        return slop;
    }
}
