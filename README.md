# PIN 滑动输入（Flyme 那种）

小米 17 Pro / HyperOS 4（Android 17 / SDK 37）实测可用。MIT 之类的随意用。

> 目标：Flyme 的锁屏数字密码可以**按住不放、划过即输入**。
> HyperOS 没有这个能力，而且它不是配置项 —— 只能挂代码。这里给出的是一个
> 普通 LSPosed 模块，作用域只有 SystemUI，不需要改系统 APK。

锁屏数字密码支持**按住不放、划过即输入**：手指按在第一位数字上不抬起来，一路划过去，
按划过的顺序依次输入 —— 就是 Flyme 那个「滑动输入密码」。

不需要 root 之外的任何东西：普通 LSPosed 模块，作用域只有 SystemUI。

---

## 一、它是怎么做的（都是在这台机器上实测出来的）

反编译 `/system_ext/priv-app/MiuiSystemUI/MiuiSystemUI.apk`（OS4.0.0.41.XBLCNXM）后确认：

**键盘结构**（`res/layout/keyguard_pin_view.xml`）：

```
com.android.keyguard.KeyguardPINView            ← 根，extends KeyguardPinBasedInputView
├─ LinearLayout
│  ├─ AlphaOptimizedRelativeLayout
│  │  ├─ com.android.keyguard.widget.PasswordTextViewForPIN   id 0x7f0b0912（那排圆点）
│  │  └─ com.android.keyguard.NumPadButton                    id 0x7f0b05d0（确定键，通常隐藏）
│  ├─ 4 × LinearLayout（4 行）
│  │  └─ com.android.keyguard.NumPadKey × 10                 id 0x7f0b05c6..0x7f0b05cf
│  └─ FrameLayout                                            id 0x7f0b0369（⌫/返回）
```

**挂点**：`com.android.keyguard.NumPadKey#onTouchEvent(MotionEvent)`

选它的原因：数字键一旦收到 ACTION_DOWN 就成为这次手势的 touch target，**之后每一个 MOVE
都会送到它手里**，哪怕手指早就跑到别的键上去了。所以从这里旁观能拿到整段手势的完整轨迹。

**输入动作**：直接调按键自己的 `performClick()`。这不是我发明的 —— ROM 自己处理外接键盘
时就是这么干的（`KeyguardPinBasedInputView#onKeyDown`）：

```java
if (i >= 7 && i <= 16) { buttons[i - 7].performClick(); return true; }
```

点击最终落到 `NumPadKey$mListener$1#onClick`：把数字 `append()` 到
`PasswordTextViewForPIN`，并调一次 `PowerManager.userActivity()`。
所以不碰任何私有输入逻辑，也不绕过 `isEnabled()` 检查。

## 二、为什么不会把「点按」搞坏

原生 `View.onTouchEvent` 的规则是：按下后只要手指没离开该键的 touch slop，抬手时才会
`performClick()`；**一旦离开 slop，按下状态就被取消，抬手什么都不会输入**。

也就是说「划过去」这个动作，系统那一路是**一声不吭**的 —— 这正好是我们要补的。补的规则：

| 时刻 | 动作 |
| :--- | :--- |
| 手指离开**起点键**的 slop | 补上起点键（这时系统那一路已彻底失效，补了不会重复） |
| 手指进入一个新的键 | 补上一个键；新键挂起 |
| 抬手 | 如果系统还会自己点起点键（手指没离开过 slop），**什么都不做**，交给系统 |
| 抬手 | 否则补上最后挂起的键 |

于是单点一下仍然只输入一位（有实测：三次独立点按 = 正好 3 位，不翻倍），
而划过 1→2→3 会依次输入 1、2、3。

快速划动时相邻两次事件可能跨过好几个键，所以坐标之间做了插值采样，避免漏键。

**长按 ⌫ 清空**：手指停在 ⌫ 上超过系统长按时间（默认 500ms），会另外回一次
`onKeyHeld`，并且这一下**不再补短按**。ROM 自己在
`KeyguardPinBasedInputViewController#onViewAttached` 里就写了：

```java
deleteView.setOnClickListener(...);        // 删一位
deleteView.setOnLongClickListener(...);    // 清空
```

所以模块这边只是调它自己的 `performLongClick()`，语义完全一致；
万一哪天 ROM 没绑，就退回直接调 `resetPasswordText(true, true)` 清空输入框。

## 三、两个从真机数据里抠出来的坑

### 3.1 斜着划会多输数字 → 命中判定缩到键中央

真机（Xiaomi 17 Pro / HyperOS 4）量出来的键盘几何：

```
键 321 x 192
横向 128 / 449 / 770  —— 正好等于键宽，**横向完全无缝**
纵向 941 / 1150 / 1359 —— 行间只有 17px
```

横向无缝，所以斜划时手指只要有一点弧度就会蹭进隔壁键 —— 而人划不出直线。
拿真机坐标做模拟（弧高 5%）：

| 路径 | 整键判定（旧） | 中央 56%（现在） |
| :--- | :--- | :--- |
| 1→5 | `145` / `415` | **`15`** |
| 5→9 | `589` | **`59`** |
| 7→5 | `785` | **`75`** |
| 5→3 | `563` | **`53`** |
| 一笔 1-5-9-7-5-3 | `14589878563` | **`1598753`** |

所以命中判定只认键**中央 56%** 那一块：真的"划进去了"才算，蹭边角不算。
起点键仍然用整个键判定（人可能按在数字边上，那一按系统会认）。

### 3.2 同一个键被上报两次 → 相邻去重

手指停在中央那块的边界上会来回穿越，同一个键就会被上报两次。
真机日志里抓到过间隔 **5ms** 的重复上报（人不可能是这个速度）：

```
手势  1  4(+75ms)  5(+149ms)  5(+5ms)       ← 多了一个 5
手势  1  4(+100ms) 4(+42ms)  5(+83ms) 5(+88ms)
```

现在规则：**连续两次上报同一个键 → 吞掉**。中间隔了别的键（真的划回来）照报，
所以 `1→5→1` 依然是 `151`；`1-4-5` 加边界抖动从 `1455` 变回 `145`。

### 3.3 `159753` 这种密码必须分两笔（几何上躲不掉）

`9→7` 是横穿，**必然经过 8**。而 5 在 `1→5→9` 里同样是"一条直线穿过的中间那个" ——
两种情况几何上完全一样，任何算法都分不出来（Flyme 也一样，它没有读心术）。所以：

```
1 → 5 → 9      （这是一条笔直的斜线，划过去）
抬手
7 → 5 → 3      （另一条平行的斜线）
```

两笔合起来正好 159753，而且比一笔更顺手。实测日志：

```
23:33:39   1  5(+75ms)  9(+243ms)   [抬手 705ms]   7  5(+33ms)  3(+239ms)   -> 159753
```

（想"一笔划完、中间穿过的数字不算"也能做 —— 但那会让直线划 `1→2→3` 只出 `1`、`3`，
两者是互斥的，只能选一个。）

## 四、安装

1. 装 APK（普通安装即可，它同时是个可打开的演示页）
2. LSPosed 里启用 **PIN 滑动输入**，作用域勾 **SystemUI**（APK 里已经用 `xposedscope` 声明了）
3. **重启 SystemUI**（改完作用域必须重启目标进程才会注入）：
   ```sh
   su -c 'killall com.android.systemui'
   ```
   或者直接重启手机。

## 五、先验证，再上锁屏

APK 里带一个演示页（**滑动输入 · 演示**）：一个和锁屏同样布局的假键盘，
用的是**同一个** `SwipePadController`，挂点也一样（键自己的 `onTouchEvent`）。
在这个页面上试：按住一位数字别松手、一路划过去；再单点几下确认不会翻倍。

手感对了再去锁屏试。

## 六、怎么确认在锁屏上真的生效

模块只在 **hook / 接管 / 出错** 时写 LSPosed 日志：

```sh
su -c 'grep PinSwipe /data/adb/lspd/log/modules_*.log | tail -20'
```

```
PinSwipe: hooked com.android.keyguard.NumPadKey#onTouchEvent
PinSwipe: 接管 PIN 键盘，数字键 10 个，附加键 1 个
```

**逐位日志默认关闭**（模块里的 `LOG_TRIGGERS = false`）。要核对手感就把它改成
`true` 重新装，每一位划过都会留一条 `PinSwipe: 划过输入 4`。

### 实测记录（这台机器上跑出来的）

把逐位日志打开后，锁屏上两次手势的原始时间戳：

```
23:00:19.344   1
23:00:19.444   2   (+100ms)
23:00:19.684   3   (+240ms)
23:00:20.184   7   (+500ms)   ← 抬手后的第二划
23:00:20.258   8   (+74ms)
23:00:20.383   9   (+125ms)
23:02:00.311   1
23:02:00.734   2   (+423ms)
23:02:01.044   3   (+310ms)
```

顺序对、每位只触发一次、0 报错。**而且这 9 条全是多位数连发、没有一条是单独一位** ——
触发只可能发生在 ACTION_MOVE，所以「点按不会被我们多输一位」也就同时被证明了。

## 七、出事了怎么退

模块只做加法、绝不吞事件，最坏情况是「滑动没反应」或「多输一位」，不会让你点不动。
真出问题：

- **不用解锁**的那条路：KernelSU Next 的 safe mode（开机时按住音量减），
  或进 recovery 删掉 `/data/adb/modules/zygisk_lsposed`
- 或者用电脑：`adb shell su -c 'killall com.android.systemui'` 后再在 LSPosed 里关掉模块
- 输错密码会计入失败次数，测试时**别拿一整排数字去划**，划两位就够了（不触发自动校验）

## 八、没做的

- 只覆盖 **PIN**（数字密码）。图形密码 / 混合密码没做 —— 那两种的「划过输入」语义不清晰。
- 停留在 ⌫ 上清空一次，**不会连续重复清空**（不是"一直删"那种自动重复）。
- **一笔划完 159753 这种"中间穿过别的数字"的图案** —— 做不到，见 3.3，那是几何问题不是 bug。
- 长按数字键的原有行为不受影响（我们只补 MOVE，不碰 DOWN/UP）。

## 九、文件

```
app/src/main/java/com/z7ui/pinswipe/
├─ PinSwipeModule.java      LSPosed 入口：挂 NumPadKey#onTouchEvent，按键 performClick()
├─ SwipePadController.java  ★ 划过即输入的状态机（纯逻辑，演示页和模块共用）
└─ DemoActivity.java        演示页：假键盘，先在这里验证手感

app/src/main/assets/xposed_init                LSPosed 入口类声明
app/src/main/res/values/arrays.xml             xposed_scope = com.android.systemui
```

## 十、构建

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
& ..\android-env\build-apk.ps1 . -Task assembleDebug
```
## 十一、作者

Deepseek、B站@z7ui
