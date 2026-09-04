# البناء والتشغيل (Build & Run)

## 0. أسرع طريقة للحصول على ملف APK جاهز — GitHub Actions

لا تحتاج Android Studio ولا أي أدوات محلية:

1. ادفع أي commit إلى الفرع (أو افتح تبويب **Actions** في المستودع).
2. سير العمل **Build APK** (`.github/workflows/build-apk.yml`) يعمل تلقائيًا على كل push
   إلى `main` أو `arena/**`، أو يدويًا عبر **Run workflow**.
3. بعد ~5–10 دقائق، افتح تشغيل السير → قسم **Artifacts** → نزّل
   `USB-Media-Explorer-debug-apk`.
4. انقل `USB-Media-Explorer-debug.apk` إلى الهاتف وثبّته مباشرة
   (نسخة debug موقّعة بمفتاح التطوير، لذلك يلزم تفعيل «تثبيت من مصادر غير معروفة»).

ملاحظات:
- عند رفع وسم مثل `v1.0.0` يُنشئ السير **Release** مرفقًا به ملف الـAPK.
- نسخة `release` تُبنى غير موقّعة (`app-release-unsigned.apk`)؛ لتوقيعها أضف المفاتيح
   كما في القسم 4.
- `gradle-wrapper.jar` غير مودَع في المستودع (ملف ثنائي)، لذلك يولّده السير بأمر
  `gradle wrapper --gradle-version 8.9` قبل البناء.

## 1. المتطلبات

| الأداة | الإصدار |
|---|---|
| Android Studio | Ladybug (2024.2) أو أحدث |
| JDK | 17 (المدمج في Android Studio يكفي) |
| Gradle | 8.9 (محدَّد في `gradle/wrapper/gradle-wrapper.properties`) |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.0.21 (مع `kotlin.plugin.compose`) |
| compileSdk / targetSdk | 35 |
| minSdk | 24 (Android 7.0) |

التطبيق **لا يحتاج إنترنت أثناء التشغيل**؛ الإنترنت مطلوب فقط لتنزيل اعتماديات Gradle
أول مرة (AndroidX، Media3، Coil، Compose).

## 2. الفتح في Android Studio

1. `File → Open` ثم اختر مجلد المشروع (الذي يحتوي `settings.gradle.kts`).
2. انتظر انتهاء مزامنة Gradle.
3. `Run 'app'` على جهاز حقيقي — المحاكي لا يوفّر USB OTG فعليًا، وهذه هي الحالة التي
   يُبنى التطبيق لأجلها.

> **ملاحظة عن `gradlew`:** مستودع Git لا يحتوي `gradle/wrapper/gradle-wrapper.jar`
> (ملف ثنائي). عند الفتح أول مرة سيعرض Android Studio إنشاء الغلاف تلقائيًا،
> أو يمكنك توليده يدويًا إن كان Gradle مثبّتًا لديك:
>
> ```bash
> gradle wrapper --gradle-version 8.9
> ```
>
> بعد ذلك يعمل `./gradlew assembleDebug` كالمعتاد.

## 3. أوامر مفيدة

```bash
./gradlew :app:assembleDebug          # بناء APK للتطوير
./gradlew :app:assembleRelease        # بناء نسخة الإصدار (minify + shrink مفعّلان)
./gradlew :app:testDebugUnitTest      # اختبارات الوحدات (Formatters, DocSorter, BulkRenamePlanner, MediaKind)
./gradlew :app:installDebug           # التثبيت على جهاز موصول
./gradlew :app:lintDebug              # فحص Lint
```

نسخة Debug تحمل `applicationIdSuffix = ".debug"` لتثبيتها بجانب نسخة الإصدار.

## 4. توقيع نسخة الإصدار

`app/build.gradle.kts` لا يحتوي توقيعًا. أضف:

```kotlin
android {
    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_PATH") ?: "release.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }
    buildTypes {
        release { signingConfig = signingConfigs.getByName("release") }
    }
}
```

ولا تضع `*.jks` في المستودع (مُدرَج في `.gitignore`).

## 5. تجربة التطبيق على فلاشة USB

1. وصّل الفلاشة عبر كابل OTG.
2. تظهر بطاقة «فلاشة USB» في الشاشة الرئيسية بحالة *يتطلب صلاحية وصول*.
3. اضغط **منح صلاحية الوصول** ← منتقي المستندات يفتح على الوحدة نفسها ← اختر المجلد الجذر.
4. من الآن تُحفظ المنحة (`takePersistableUriPermission`) ولا يُطلب الإذن مرة أخرى،
   حتى بعد إعادة التوصيل أو إعادة تشغيل الهاتف (طالما سمح أندرويد بذلك).
5. افتح مجلد أفلام: تُستخرج المعاينات أثناء التمرير وتُخزَّن في `cacheDir/thumbs`.

### للتحقق من أن المعاينة من الفيديو نفسه
- أغلق الإنترنت (وضع الطيران): التطبيق يعمل بالكامل — بند 23.
- راقب `Logcat` بفلتر `MediaMetadataRetriever` / `ThumbnailCache`.
- تحقق من عدم إنشاء أي ملف بجانب الفيلم على الفلاشة: الكود يفتح بوضع `"r"` فقط
  (`openFd(uri, "r")`) ولا يكتب إلا داخل `cacheDir` الخاص بالتطبيق — بند 24.

## 6. ما يجب اختباره على جهاز حقيقي

| الحالة | السبب |
|---|---|
| فلاشة exFAT كبيرة (1TB+) | سلوك `StatFs` وسرعة `getScaledFrameAtTime` عبر USB |
| MKV بترجمة مدمجة + ملف `.srt` جانبي | اختيار المسار في المشغّل |
| فصل الفلاشة أثناء التمرير | مسار `VolumeEvent.Detached` وحالة الخطأ في المتصفح |
| نسخ مجلد 40GB بين وحدتين | الخدمة الأمامية، الإيقاف المؤقت، الاستئناف، ETA |
| HEIC/HEIF وGIF | `ImageDecoder` مقابل `BitmapFactory` |
| أندرويد 11 مقابل أندرويد 14 | `createOpenDocumentTreeIntent` مقابل صلاحيات `READ_MEDIA_*` |

## 7. قيود معروفة

- **المساحة الحرة لوحدة SAF**: لا توجد واجهة برمجية رسمية؛ تُعرض إن كان مسار التركيب
  (`/storage/XXXX-YYYY`) قابلًا للقراءة، وإلا تظهر «المساحة غير متوفرة».
- **معدل الإطارات/الترميز**: يعتمد على ما تستخرجه `MediaMetadataRetriever` و`MediaExtractor`
  من الحاوية؛ بعض ملفات AVI/RMVB القديمة لا تعيد قيمًا.
- **استئناف النقل بعد إلغاء العملية**: الإيقاف المؤقت/الاستئناف مدعوم بالكامل،
  أما بعد الإلغاء فيُحذف الملف الجزئي ويُعاد النقل من البداية.
- **`getDirectory()`/`getUuid()` في `StorageVolume`**: واجهات مخفية تُستدعى بالانعكاس
  مع بديل آمن، وقد لا تعمل على بعض الأجهزة.
