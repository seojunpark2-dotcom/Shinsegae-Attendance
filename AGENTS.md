# Shinsegae Attendance project instructions

- Make changes and publish releases on `main` only. Do not modify other branches unless the user explicitly changes this preference.
- Before each APK release, review the change and version all three parts of `major.minor.revision`:
  - Increase `major` for a change that replaces the existing usage workflow or breaks compatibility with existing settings. The move from computer-connected execution to a standalone Android APK and the main-screen control redesign are already counted, so the current major baseline is `3`.
  - Increase `minor` for an independent new feature that preserves existing settings and usage. The current minor baseline is `7`; fixes and refinements to the same feature do not count again.
  - Increase `revision` by one for every modified APK release. It is cumulative and never resets when major or minor changes. The current baseline is `28`.
- Increase Android `versionCode` on every release. Keep `versionName`, APK and ZIP filenames, and the README download links aligned. Preserve older releases as history.
- Never commit account credentials, device unlock secrets, local Android SDK paths, or attachment files.
