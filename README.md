# ConnectX — Android SMS Gateway for EMS

> **Automated Cellular SMS Gateway for EMS (Point of Sale & Enterprise Management System)**  
> Turn your Android device into a dedicated, cost-effective cellular SMS dispatch server.

[![Android SDK 35](https://img.shields.io/badge/Android%20SDK-35-2383E2.svg)](https://developer.android.com)
[![Kotlin 2.1.0](https://img.shields.io/badge/Kotlin-2.1.0-7F52FF.svg)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Compose-Material%203-4285F4.svg)](https://developer.android.com/jetpack/compose)
[![Theme: Light](https://img.shields.io/badge/UI-Mobbin%20Light-2383E2.svg)](#ui-and-design-system)

---

## 🌟 Key Highlights

- ⚡ **Direct Cellular Dispatch**: Sends customer transaction SMS directly from your local SIM card with zero aggregator markups.
- 🎨 **Modern Light UI Theme**: Crisp white (`#FFFFFF`) canvas, Electric Azure Blue (`rgb(35, 131, 226)`), and Pearl surfaces.
- 📱 **Mobbin-Inspired Onboarding**: Interactive 4-step walkthrough with visual feature highlights and progress indicators.
- 👤 **Administrator Profile**: Complete administrator profile dashboard showing authentic Short ID (`#1001`), verified credentials, registered phone, address, and server infrastructure.
- 🏷️ **Formatted Message Types**: Replaced "null" card titles with formatted categories (Sales Invoice Confirmation, Due Invoice Reminder, Exchange Invoice Confirmation, Return Invoice Confirmation, Payment Confirmation, Custom Message).
- 🚫 **Cancel Queued SMS**: One-tap cancellation for pending/queued messages directly from the Activity feed and detail modal.
- 🔄 **Multi-Store & Dual-SIM Routing**: Pair multiple retail outlets to a single device and assign dedicated SIM slots.
- ⏳ **Best-in-Class Loaders**: Smooth circular progress spinners and animated skeleton shimmer loading rows.
- 🛡️ **Enterprise Security**: Android KeyStore hardware token encryption with zero administrator password retention.

---

## 🚀 Quick Setup

1. **Clone the Repository**:
   ```bash
   git clone https://github.com/sa8650/ConnectX.git
   ```
2. **Open in Android Studio** (Ladybug 2024.2.1+ or newer with JDK 17).
3. **Build and Run**:
   ```bash
   ./gradlew assembleDebug
   ```
4. **Sign In**:
   - Enter your **EMS Website URL** (e.g., `https://your-ems.pages.dev`).
   - Enter your **Administrator Email** and **Password**.
   - Select your shop, grant SMS/Phone permissions, and pick your sending SIM.

---

## 📖 Comprehensive Documentation

For complete technical specifications, full API references, architecture diagrams, telephony details, and EMS integration schemas, see:

👉 **[CONNECTX_DOCUMENTATION.md](./CONNECTX_DOCUMENTATION.md)**

---

## 📄 License & Attribution

Built and maintained by **Dexter Studio** for **EMS**.  
All rights reserved.
