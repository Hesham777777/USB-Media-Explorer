# إعداد وتدوير مفتاح التوقيع (خطوة واحدة يقوم بها مالك المستودع)

## لماذا هذا الملف؟

التدقيق الأمني (hesham.md، بند CRITICAL الأول) وجد أن مفتاح التوقيع `keystore/usbmedia.p12`
وكلمات مروره (`usbmedia`) **مودعة في مستودع عام**. أي مفتاح نُشر علنًا يعتبر مكشوفًا: يستطيع
أي شخص بناء APK موقّع بنفس البصمة وتوزيعه كأنه تحديث رسمي للتطبيق. الحل: مفتاح جديد يبقى في
أسرار GitHub فقط، والمفتاح القديم يُحذف من المستودع.

البنية التحتية جاهزة الآن في المستودع وتعمل تلقائيًا:

- `app/build.gradle.kts` يقرأ أولًا `keystore/ci.p12` + متغيرات البيئة
  `USBMEDIA_STORE_PASSWORD` / `USBMEDIA_KEY_ALIAS` / `USBMEDIA_KEY_PASSWORD`،
  ويرتد إلى المفتاح المودَع فقط ما دامت الأسرار غير مضبوطة.
- `.github/workflows/build-apk.yml` يحوّل السر `USBMEDIA_KEYSTORE_B64` إلى `keystore/ci.p12`
  داخل بيئة البناء (ملف متجاهَل في `.gitignore`، لا يعود إلى المستودع أبدًا).
- النشر يتطلب الآن `app-release.apk` الموقّع تحديدًا؛ أي بناء غير موقّع يفشل قبل النشر.

الخطوات التالية هي **كل ما ينقص** لإتمام التدوير (≈10 دقائق، مرة واحدة).

## 1) توليد مفتاح جديد (على أي جهاز فيه JDK 17+)

```bash
keytool -genkeypair -v \
  -keystore usbmedia-new.p12 \
  -storetype PKCS12 \
  -keyalg RSA -keysize 4096 -validity 10950 \
  -alias usbmedia \
  -dname "CN=USB Media Explorer, OU=Dev, O=USB Media Explorer, C=YE"
```

- اختر كلمة مرور قوية **مختلفة عن `usbmedia`** (سيُطلبها keytool مرتين).
- احتفظ بنسخة احتياطية من `usbmedia-new.p12` + كلمة المرور في مكان آمن خارج الإنترنت
  (مدير كلمات مرور). فقدان المفتاح = فقدان مسار التحديث لكل المستخدمين.

## 2) رفع الأسرار إلى GitHub

```bash
base64 -w0 usbmedia-new.p12 > keystore.b64        # macOS: base64 -i usbmedia-new.p12 -o keystore.b64
gh secret set USBMEDIA_KEYSTORE_B64 < keystore.b64
gh secret set USBMEDIA_STORE_PASSWORD              # يُطلب الإدخال تفاعليًا: كلمة مرور المتجر
gh secret set USBMEDIA_KEY_ALIAS                   # القيمة: usbmedia
gh secret set USBMEDIA_KEY_PASSWORD                # كلمة مرور المفتاح (في PKCS12 هي نفسها عادةً)
rm -f keystore.b64                                  # لا تبقِ نسخًا متناثرة
```

(أو من الواجهة: Settings → Secrets and variables → Actions → New repository secret،
مع لصق محتوى `keystore.b64` في `USBMEDIA_KEYSTORE_B64`.)

## 3) التحقق

أعد تشغيل آخر Workflow (أو اعمل push فارغًا):

- سجل البناء يجب أن يطبع `Signing with the rotated key from repository secrets.`
- خطوة النشر يجب أن تنجح بـ `app-release.apk`.
- للتأكد من البصمة الجديدة على الهاتف:
  `apksigner verify --print-certs app-release.apk` (أو راقب رسالة التثبيت).

## 4) حذف المفتاح القديم من المستودع

```bash
git rm keystore/usbmedia.p12
# حدّث keystore/README.md ليعكس الوضع الجديد
git commit -m "chore(security): remove the compromised committed signing key"
git push
```

ملاحظات:

- المفتاح القديم يبقى في **تاريخ git** — وهذا مقبول بعد التدوير (لم يعد يُستخدم في أي بناء
  جديد). مسح التاريخ بالكامل (BFG/filter-repo) اختياري ويعيد كتابة كل الـcommits.
- `.gitignore` يمنع الآن إضافة أي `*.p12` جديد عن طريق الخطأ.

## 5) على هاتف المستخدم (مرة واحدة فقط)

أول نسخة موقّعة بالمفتاح الجديد **لن تُثبَّت فوق** النسخة القديمة (بصمة مختلفة → رسالة
«App not installed»). المطلوب مرة واحدة:

1. إزالة تثبيت النسخة الحالية يدويًا.
2. تثبيت `app-release.apk` الجديد من رابط الـRelease المعتاد:
   `https://github.com/Hesham777777/USB-Media-Explorer/releases/download/apk-latest/app-release.apk`

ملفاتك على وسائط التخزين (USB/SD/الذاكرة الداخلية) **لا تتأثر إطلاقًا** — يُفقد فقط سجل
التطبيق المحلي (المفضلة، المجلدات الأخيرة، مواضع التشغيل) لأنه يُحذف مع إزالة التثبيت.
كل النسخ اللاحقة تُثبَّت فوق بعضها طبيعيًا وتحافظ على البيانات.

## 6) تشديدات مكمّلة (اختيارية، من واجهة GitHub)

- **Settings → Rules → Rulesets → New tag ruleset**: حماية النمط `v*` حتى لا يُنشئ أي
  شخص (أو توكن مسروق) وسوم إصدارات عشوائية.
- **Settings → Branches**: حماية `main` (يلزم Pull Request + نجاح الفحوصات).
- Dependabot مفعّل الآن (`.github/dependabot.yml`) ويراقب تحديثات Actions وGradle أسبوعيًا؛
  راجع PRs-اته وادمجها — تحديثات Actions ترفع الـSHA المثبّت إلى إصدارات أحدث بأمان.
