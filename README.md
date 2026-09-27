# CartCheck

Android app that watches Swiggy / Zomato / Blinkit, detects the cart screen, and shows an
on-screen card with estimated calories, sugar and protein plus a "good / caution / bad" verdict
against your goals. You tap **Continue** (or **Order anyway**) to proceed.

```
Screen (MediaProjection) → on-device OCR (ML Kit) → cart detector → your backend → Claude Haiku → overlay card
```

Only extracted **text** leaves the phone, and only for cart screens. Screenshots stay on-device.

## 1. Run the backend (Go, no dependencies)

```bash
cd backend
export ANTHROPIC_API_KEY=sk-ant-...
export APP_TOKEN=pick-any-secret        # the app must send the same value
go run .                                 # listens on :8080
```

Quick test:

```bash
curl -s localhost:8080/analyze -H "X-App-Token: $APP_TOKEN" \
  -d '{"app":"Swiggy","screen_text":"Coca-Cola 750 ml x1\nChicken Biryani x1\nBill Details\nTo Pay 389","goals":{"max_calories":800,"max_sugar_g":25}}'
```

Deploy: the `Dockerfile` works as-is on Render, Railway or Fly.io. Set `ANTHROPIC_API_KEY` and `APP_TOKEN`
as environment variables there. Optional: `MODEL` (default `claude-haiku-4-5-20251001`), `PORT`.

## 2. Run the app

1. Open the `android/` folder in Android Studio (Ladybug or newer) and let Gradle sync.
2. Run on a real phone (Android 10+). The emulator works too, but you'd need the delivery apps installed.
3. In the app, set **Backend URL**:
   - Emulator + local backend: `http://10.0.2.2:8080` (default)
   - Phone + local backend: `http://<your-laptop-LAN-IP>:8080` (same Wi-Fi)
   - Deployed: `https://your-service.onrender.com`
4. Enter the same **App token**, tap **Test backend with a sample cart**. You should see a verdict.
5. Grant steps 1 and 2, tap **Start monitoring**, choose **Entire screen**.
6. Open Swiggy / Zomato / Blinkit and go to your cart.

## Tuning

| What | Where |
|---|---|
| Apps watched (add Zepto, Instamart, etc.) | `CaptureService.TARGET_APPS` |
| Cart-screen keywords | `CartDetector.markers` |
| How often it checks (2.5 s) | `CaptureService.POLL_MS` |
| Rating rules / output format | `systemPrompt` in `backend/main.go` |

## Known limitations

- It reads only what's visible. On long carts, scroll so all items show and tap **Re-check**.
- Nutrition for restaurant dishes is an estimate; the card says "~" for a reason.
- Android shows a persistent "screen is being shared" indicator while monitoring. This is by design and cannot be hidden.
- Some banking/payment screens block capture (FLAG_SECURE). That's fine, since the cart screen comes before payment.
- Before a Play Store release: remove `usesCleartextTraffic`, add a privacy policy, and declare screen capture + usage access in the Data Safety form.
