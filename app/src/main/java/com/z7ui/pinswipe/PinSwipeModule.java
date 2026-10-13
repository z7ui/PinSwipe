package com.z7ui.pinswipe;

import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed 入口。
 *
 * 挂点是 HyperOS 4（Android 17 / MiuiSystemUI.apk）上实测出来的：
 *
 *   com.android.keyguard.NumPadKey#onTouchEvent(MotionEvent)   ← 每个数字键
 *
 * 为什么挂在这里：数字键被按下后就成了这次手势的 touch target，之后的每一个
 * MOVE 都会送到它手里（哪怕手指已经跑到别的键上）。所以从这里旁观，能拿到整段
 * 手势的完整轨迹，而且**返回 false / 不改任何参数**，原生逻辑一点不受影响。
 *
 * 为什么不挂 KeyguardPINView：它压根没有覆写任何触摸方法，挂上去只会挂到
 * ViewGroup#dispatchTouchEvent 上，那会影响全系统所有 ViewGroup。
 *
 * 输入动作用的是 ROM 自己的 performClick()，和它处理外接键盘时走的是同一条路：
 *   KeyguardPinBasedInputView#onKeyDown → buttons[i].performClick()
 * 所以不碰任何私有输入逻辑，也不会绕过启用状态检查。
 */
public class PinSwipeModule implements IXposedHookLoadPackage {

    private static final String TAG = "PinSwipe";
    private static final String SCOPE = "com.android.systemui";
    private static final String PAD_CLASS = "com.android.keyguard.KeyguardPinBasedInputView";
    private static final String KEY_CLASS = "com.android.keyguard.NumPadKey";
    /** 逐位日志。默认关掉（只在 hook / 接管 / 出错时留一条），需要排查时再打开。 */
    private static final boolean LOG_TRIGGERS = false;

    /** 划过一位 = 短按；停在 ⌫ 上 = 长按（ROM 自己把长按绑成了清空）。 */
    private final SwipePadController.KeySink sink = new SwipePadController.KeySink() {
        @Override
        public void onKeyTriggered(View key) {
            press(key);
        }

        @Override
        public void onKeyHeld(View key) {
            hold(key);
        }
    };

    private final WeakHashMap<View, SwipePadController> controllers = new WeakHashMap<>();
    private boolean broken;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || !SCOPE.equals(lpparam.packageName)) {
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(KEY_CLASS, lpparam.classLoader,
                    "onTouchEvent", MotionEvent.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (broken) {
                                return;
                            }
                            try {
                                View key = (View) param.thisObject;
                                SwipePadController controller = controllerFor(key);
                                if (controller != null) {
                                    controller.onTouch((MotionEvent) param.args[0]);
                                }
                            } catch (Throwable t) {
                                // 出错就整个停掉，保证锁屏输入不受影响
                                broken = true;
                                XposedBridge.log(TAG + ": 出错了，本次开机内停用 -> " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": hooked " + KEY_CLASS + "#onTouchEvent");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook 失败 -> " + t);
        }
    }

    private synchronized SwipePadController controllerFor(View key) {
        View pad = findPad(key);
        if (pad == null) {
            return null;
        }
        SwipePadController cached = controllers.get(pad);
        if (cached != null) {
            return cached;
        }

        List<View> digits = new ArrayList<>(10);
        Object buttons = XposedHelpers.callMethod(pad, "getButtons");
        if (buttons instanceof Object[]) {
            for (Object button : (Object[]) buttons) {
                if (button instanceof View) {
                    digits.add((View) button);
                }
            }
        }

        List<View> extras = new ArrayList<>(1);
        try {
            Object delete = XposedHelpers.getObjectField(pad, "mDeleteButton");
            if (delete instanceof View) {
                extras.add((View) delete);
            }
        } catch (Throwable ignored) {
        }

        SwipePadController controller = new SwipePadController(digits, extras, sink);
        controllers.put(pad, controller);
        XposedBridge.log(TAG + ": 接管 PIN 键盘，数字键 " + digits.size() + " 个，附加键 " + extras.size() + " 个");
        return controller;
    }

    /**
     * 长按 ⌫：ROM 自己就把它绑成了清空
     * （{@code KeyguardPinBasedInputViewController#onViewAttached} 里
     * {@code mDeleteButton.setOnLongClickListener(...)}），所以优先走它自己的语义。
     */
    private void hold(View key) {
        if (LOG_TRIGGERS) {
            XposedBridge.log(TAG + ": 长按附加键 -> 尝试清空");
        }
        try {
            if (key.performLongClick()) {
                haptic(key);
                return;
            }
        } catch (Throwable ignored) {
        }
        // 万一哪天 ROM 没绑（或者绑在别处），直接清空输入框
        try {
            View pad = findPad(key);
            if (pad != null) {
                XposedHelpers.callMethod(pad, "resetPasswordText", true, true);
                haptic(key);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 清空失败 -> " + t);
        }
    }

    private void haptic(View key) {
        try {
            key.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        } catch (Throwable ignored) {
        }
    }

    private void press(View key) {
        if (LOG_TRIGGERS) {
            try {
                Object digit = XposedHelpers.getObjectField(key, "mDigit");
                XposedBridge.log(TAG + ": 划过输入 " + digit);
            } catch (Throwable ignored) {
                // ⌫ 之类没有 mDigit 字段
                XposedBridge.log(TAG + ": 划过按下（非数字键）");
            }
        }
        haptic(key);
        key.performClick();
    }

    /** 往上找到 PIN 键盘容器；它持有 buttons[] 和 mDeleteButton。 */
    private static View findPad(View key) {
        ViewParent parent = key.getParent();
        while (parent instanceof View) {
            View candidate = (View) parent;
            for (Class<?> c = candidate.getClass(); c != null; c = c.getSuperclass()) {
                if (PAD_CLASS.equals(c.getName())) {
                    return candidate;
                }
            }
            parent = candidate.getParent();
        }
        return null;
    }
}
