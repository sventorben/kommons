---
layout: default
title: Unusual Login Time Condition
nav_order: 4
---

# ⏰ Unusual Login Time Condition

This condition can be used in an authentication flow to detect whether a user is logging in at an **unusual time of day**, based on their **recent login behavior**.

It enables **adaptive authentication** — for example, requiring OTP only when login occurs outside a user’s typical time range.

---

## 🧠 What It Does

On each successful login, the system stores the **login time** in ISO format (`HH:mm:ss`) under the user attribute:

```
kommons.usualLoginTimes
```

It retains **only the 5 most recent login times**, in **UTC**.

When the condition is evaluated, it:

1. Parses the latest 5 recorded login times
2. Finds the **earliest** and **latest** time among them
3. Extends that time window by a configurable number of minutes (skew)
4. Checks whether the **current login time (UTC)** falls **within** the extended range

If the login occurs **outside** that range, the condition evaluates to `true` — and the next step in the flow (such as OTP) will be triggered.

> 💡 This provides lightweight behavioral detection based on recent login activity without requiring external context.

---

## ⚙️ Configuration

This condition supports one configuration option:

| Name                    | Description                                                                 |
|-------------------------|-----------------------------------------------------------------------------|
| `kommons.skew.minutes`  | Number of minutes to extend the allowed range before and after              |

### 🧮 Example

Last 5 stored login times:

```
09:15:00, 09:42:00, 10:00:00, 10:05:00, 10:18:00
```

- Min = `09:15`, Max = `10:18`
- Skew = `30`
- Evaluated time window: `08:45 — 10:48` (in UTC)

Any login outside this window will cause the condition to trigger.

---

## ✅ When to Use

Use this condition if you want to:

- Require OTP when users log in at **atypical times**
- Allow seamless login during their **usual schedule**
- Add a **lightweight anomaly check** with minimal configuration

---

## 🔧 Example Flow Setup

To enforce OTP only when login time deviates from recent behavior:

1. Go to **Authentication → Flows**
2. Create or edit a conditional subflow
3. Add **Unusual Login Time Condition**
4. Under it, add **OTP Form**
5. Set the condition config:

   ```
   kommons.skew.minutes = 30
   ```

This allows logins within a 30-minute skew from the earliest/latest recent times. Logins outside the window trigger OTP.

---

## 🧪 Testing

1. Log in several times during your normal hours (e.g. around 09:00–10:30 UTC)
2. Wait and log in at a much later or earlier time (e.g. 02:00 UTC)
3. The condition should now evaluate to `true`, and OTP (or another subflow) will be required

---

## 🗂️ User Attribute Format

The condition stores login times in this attribute:

```
kommons.usualLoginTimes
```

Format: ISO_LOCAL_TIME (`HH:mm:ss`)
Timezone: Always **UTC**

Example values:

```
09:15:00
09:47:00
10:05:00
10:18:00
11:00:00
```

Only the **5 most recent times** are retained.
Older entries are removed automatically on new logins.

---

## 📝 Notes

- The skew range is applied to both ends of the min–max time interval
- The check uses **UTC time only**
- If no login history is present, the condition returns `false` (i.e. does not trigger)
- This condition is **non-blocking** — it only triggers optional subflows like OTP
