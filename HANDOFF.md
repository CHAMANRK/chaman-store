# HANDOFF: Chaman Store

**Kya hai:** Android app jo Najeef ke apps ka store hai. Dusron ko apps dene ke liye. Sirf download + install (app ke andar run nahi hota).

**Stack:** native Kotlin, bina XML layouts (UI code me), sirf androidx.core dependency. Build GitHub Actions se (debug APK artifact).

**Flow:** `apps.json` (repo root, GitHub raw link `Config.APPS_URL`) se list aati hai. APK GitHub Releases pe hota hai. Install = DownloadManager + FileProvider + system installer. Installed state `PackageManager` se: Install / Update / Open.

**apps.json format:** `{"apps":[{"name","package","versionName","versionCode","description","apkUrl","iconUrl"}]}`. Naya app jodna = ek entry. Update dena = versionCode badhao + naya apkUrl.

**Design:** monochrome dark (#101010 bg, #1C1C1C cards, white button).

**Status:**
- v0.1 built (list + Install/Update/Open). Device test pending.

**Abhi baaki (v0.2+):** app detail screen + screenshots, light theme, search, release signing (same key sab apps me).

**Dhyan:** debug APK har build me alag key se sign hota hai, isliye store ka agla version upar se install nahi hoga. Share karne se pehle signed release workflow lagana hai (CallLogEditor wala).
