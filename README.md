# Firebase Remote Config Push (Android Studio / IntelliJ)

Push values to **Firebase Remote Config** directly from **Android Studio** or **IntelliJ IDEA**—no Firebase Console needed.

This plugin is built for developers who want a **fast, safe, and simple** way to manage Remote Config while staying inside their IDE.

---

## Features

- **Direct Push**: Update Remote Config parameters instantly from your IDE.
- **Smart Validation**:
  - **Key Check**: Prevents invalid key formats (only alphanumeric characters and underscores allowed).
  - **Type Support**: Locally validates **JSON**, **Number**, **Boolean**, and **String** before pushing.
- **Parameter Groups**: Push parameters directly into named parameter groups, or to root parameters.
- **Project Awareness**: Displays the active Firebase Project ID in the push dialog to prevent accidental pushes to the wrong environment.
- **Safe Merging**: Automatically fetches the current template and merges your changes—**never** overwrites your entire configuration.
- **Project Isolation**: Service account path is saved per project, so each project uses its own credentials.

---

## Supported Value Types

- **String**
- **Number**
- **Boolean**
- **JSON**

---

## Requirements

Before using this plugin, make sure you have:

- A **Firebase project**
- A **Firebase Service Account JSON file**
- The service account must have **Remote Config Admin** permissions

---

## Step-by-Step Setup & Configuration

### Step 1: Create a Firebase Service Account

1. Go to **Firebase Console**
2. Open your project
3. Navigate to:
   ```
   Project Settings → Service Accounts
   ```
4. Click **Generate new private key**
5. Download the `.json` file

> **Important:**
> Never commit this file to Git. Add it to your `.gitignore`.

---

### Step 2: Configure the Plugin

1. Open **Android Studio**
2. Go to:
   ```
   Settings → Tools → Firebase Push
   ```
   (macOS: `Android Studio → Settings → Tools → Firebase Push`)
3. Click the folder icon next to **Service account JSON** and select the file you downloaded

The path is saved **per project**, so you only do this once.

---

### Step 3: Push a Remote Config Value

1. Open the **Tools** menu
2. Go to **Firebase Push → Push to Remote Config**
3. Fill in the dialog:
   - **Key** → e.g. `enable_new_checkout`
   - **Value** → e.g. `true`
   - **Type** → `Boolean` / `String` / `Number` / `JSON`
   - **Group** → e.g. `feature_flags` (leave blank for root parameters)
4. Click **OK**

The plugin will:
- Fetch the existing Remote Config template
- Merge your change safely
- Push only the updated values

---

## Switching Firebase Projects

If you need to change credentials:

1. Open the **Tools** menu
2. Go to **Firebase Push → Reset Service Account**
3. On your next push you will be prompted to select a new service account file

---

## Best Practices

- Always add service account files to `.gitignore`
- Use separate service accounts for staging and production
- Never share service account keys publicly
- Double-check the **Project ID** shown in the push dialog before confirming

---

## Who This Is For

- Android developers
- Flutter engineers
- Mobile developers
- Anyone tired of opening Firebase Console just to update a flag

---

**Built with ❤️ for Flutter & Mobile Developers**
