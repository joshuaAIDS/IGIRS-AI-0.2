# 📱 IGIRS AI Android Companion (Standalone Mobile Assistant)

> **A native, zero-PC Android Assistant powered directly by NVIDIA NIM, Android Speech Recognition, Text-To-Speech, and Android System Voice Interaction.**

---

## 🌟 Highlights

- **100% Standalone (Zero PC Required)**: Communicates directly with NVIDIA NIM cloud endpoints (`https://integrate.api.nvidia.com/v1/chat/completions`) using your mobile data or Wi-Fi.
- **Siri / Google Assistant Integration**: Implements Android's `VoiceInteractionService` so you can summon IGIRS by **long-pressing the Power button** or swiping up from the bottom screen corners.
- **J.A.R.V.I.S. Persona**: Direct, sub-second responses with British elegance and natural conversational tone.
- **3D Cyber Core Visualizer**: Custom animated `CyberOrbView` with gyroscopic gimbal rings, plasma core, and dynamic voice audio reactivity.
- **Mobile Action Automation**:
  - 💬 **WhatsApp**: Instant message dispatch (`"Send WhatsApp message to John hello"`)
  - 📞 **Phone Calling**: Direct dialer engagement (`"Call Mom"`)
  - 🎵 **YouTube Music**: Direct song autoplay (`"Play Bohemian Rhapsody"`)
  - ⏱️ **Timers & Alarms**: Set system timers & alarms (`"Set timer for 10 minutes"`)
  - 🌐 **Web Search**: Fast Google search routing (`"Search for quantum computing"`)

---

## 🚀 Getting Started

### 1. Open in Android Studio or Build APK
- **In Android Studio**: Open the `android` folder in Android Studio and click **Run** (or connect your phone with USB Debugging enabled).
- **Via Command Line**:
  ```powershell
  cd android
  .\gradlew.bat assembleDebug
  ```
  The generated APK will be in:
  `android/app/build/outputs/apk/debug/app-debug.apk`

### 2. Enter Your NVIDIA NIM API Key
1. Open the **IGIRS AI** app on your phone.
2. Enter your NVIDIA API key (`nvapi-...`) in the configuration card.
3. Tap **Save NVIDIA Key**.

### 3. Set as Default Digital Assistant
1. Tap the **"Set as Default Assistant"** button inside the app (or open your phone's **Settings > Apps > Default Apps > Digital Assistant App**).
2. Select **IGIRS AI** as your default assistant.

### 4. Summon Like Siri!
- **Long-press your Power button** (or swipe up from the corner, or hold the Home button).
- The floating cyber HUD appears immediately over any active application or lock screen.
- Speak your command naturally, and IGIRS AI will respond with voice!
