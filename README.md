# Be a Guardian: Android app

A small Android app that shows the Be a Guardian website full screen and adds what a website cannot do:

- keeps sharing a guardian's location while they are **on duty**, even with the screen off
- checks for alerts every few seconds and sounds a **full-screen alarm** over the lock screen
- lets people take the ID photo and selfie with the camera
- opens maps and phone calls in the right apps

The app loads https://be-a-guardian.onrender.com (set in `app/build.gradle`, the `BASE_URL` line). Website updates appear in the app without rebuilding it.

## How to get the APK (no Android Studio needed)

1. On GitHub, create a **new public repository** called `Be-a-Guardian-Android`.
2. Upload everything from this folder to it: `app`, `.github`, `build.gradle`, `settings.gradle`, `gradle.properties`, `.gitignore`, `README.md`.
   - If the `.github` folder does not upload, choose "Add file > Create new file", type `.github/workflows/build.yml` as the name and paste the contents of that file.
3. Open the **Actions** tab. The "Build Android app" run starts by itself (or press "Run workflow"). It takes about 5 to 8 minutes.
4. When it shows a green tick, open the **Releases** section on the right of the repository page and download `Be-a-Guardian.apk`.
5. On an Android phone, open the file. Android asks to allow installs from that source. Allow it, then install.

If the run fails (red cross), open it, copy the error lines at the bottom of the failed step and send them over. First builds often need one or two small fixes.

## What the first version does not do yet
- It does not restart on its own after the phone reboots. Open the app and switch "on duty" on again.
- It checks for alerts every 8 seconds from the phone. This uses battery, so ask guardians to plug in on shift.
- Phone makers (Xiaomi, Oppo, Vivo, Samsung) may stop background apps. The app asks once to be exempt from battery saving. Allow it.
- On some Android 14 phones the full-screen alarm needs "Full-screen notifications" switched on in the app's settings.
- This is a test build signed with a debug key, which is fine for pilots. A Play Store build needs a proper release key.
