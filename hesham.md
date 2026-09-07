---
title: "تقرير التدقيق الفني والأمني لتطبيق USB Media Explorer"
author: "Codex"
date: "2026-09-06"
tags:
  - android
  - mobile-security
  - architecture
  - file-manager
  - audit
categories:
  - security-audit
  - technical-documentation
lang: ar
dir: rtl
---

# تقرير التدقيق الفني والأمني

> **ملاحظة التحقق والتحرير:** يعتمد هذا المستند على آخر تقرير تدقيق عربي كامل صادر في سياق المشروع. لم يتضمن نص التقرير قيمة فعلية لكلمة مرور أو مفتاح خاص أو بيانات تعريف شخصية، ولذلك لم تُجرَ أي عملية حجب نصي. أُبقيت الإشارات الوصفية إلى مواضع الخلل، وحُوّلت روابط الملفات المحلية المطلقة إلى روابط نسبية صالحة داخل المستودع.

النتيجة العامة: المشروع منظّم وظيفيًا ويستخدم Kotlin وJetpack Compose وMedia3 وSAF بصورة جيدة، لكنه غير آمن حاليًا للنشر الإنتاجي بسبب مشكلتين حرجتين: تسريب مفتاح التوقيع، واحتمال فقدان البيانات أثناء النقل بين وحدات التخزين.

نطاق الفحص كان الكود والوثائق وCI والاختبارات. لم يُرفق APK/IPA، ولا توجد نسخة iOS أو مزامنة سحابية فعلية. تعذر تنفيذ Gradle لأن `gradle-wrapper.jar` و`gradlew` غير موجودين، وأمر `gradle` غير مثبت.

## النتائج ذات الأولوية

| الشدة | النتيجة | الأثر |
|---|---|---|
| حرج | مفتاح توقيع الإصدار وكلمة مروره منشوران | يمكن توقيع تحديث خبيث مقبول كتحديث للتطبيق |
| حرج | النقل بين وحدتين يحذف المصدر أثناء النسخ | فقدان أو تشظّي ملفات المستخدم عند الامتلاء أو فصل USB |
| عالٍ | السماح بنسخ مجلد إلى نفسه أو إلى أحد أحفاده | تكرار لا نهائي وامتلاء التخزين |
| عالٍ | عمليات متزامنة تستخدم حالة مشتركة غير آمنة | تصادم أسماء وفشل/فساد عمليات النقل |
| عالٍ | فك ZIP بلا حصص للحجم أو العدد أو العمق | ZIP bomb، استنزاف التخزين والبطارية |
| عالٍ | بيانات السجل والمسارات قابلة للنسخ الاحتياطي | تسريب أسماء الملفات وسجل المشاهدة ومعرّفات SAF |
| عالٍ | فهرس البحث الجزئي يُعامل كفهرس كامل | نتائج بحث خاطئة أو مفقودة |
| عالٍ | فروع `arena/**` تستطيع استبدال الإصدار العام | ضعف سلامة سلسلة التوريد |
| متوسط | نموذج صلاحيات Android 13/14 غير دقيق | صلاحية جزئية تُعامل كأنها وصول كامل |
| متوسط | مهام النقل غير مستديمة وتبدأ الخدمة متأخرًا | ضياع العملية عند قتل العملية أو أثناء تقدير الحجم |
| متوسط | مخازن JSON ليست ذرية فعليًا | فقدان المفضلة والسجل عند الانقطاع |
| متوسط | اختبارات وRelease gates غير كافية | احتمالية مرتفعة للانحدارات |

## 1. مفتاح التوقيع المكشوف — حرج

المفتاح وكلمات مروره مضمنة في [`app/build.gradle.kts`](app/build.gradle.kts#L25)، ويؤكد [`keystore/README.md`](keystore/README.md#L1) أنه منشور عمدًا ويوقّع كل إصدارات release.

تحققت عمليًا من أن كلمة المرور المنشورة تفتح الملف وأنه يحتوي `Shrouded Keybag`، دون استخراج المفتاح.

الإصلاح الفوري:

- إيقاف نشر APK الحالي.
- اعتبار المفتاح مخترقًا نهائيًا؛ حذفه من آخر commit لا يعيد الثقة.
- إنشاء مفتاح release جديد داخل خدمة أسرار أو HSM، وفصل مفتاح debug عنه.
- إزالة كلمات المرور من Gradle وتحميلها من أسرار CI.
- لمستخدمي التوزيع الجانبي الحاليين، الخيار الأكثر أمانًا هو حزمة جديدة `applicationId` أو إعادة تثبيت موثوقة؛ تدوير المفتاح لا يبطل قدرة من حصل على المفتاح القديم.
- نشر release فقط، وعدم توزيع APK قابل للتصحيح.

## 2. فقدان بيانات أثناء النقل — حرج

في [`FileDocProvider.kt`](app/src/main/java/com/usbmediaexplorer/data/doc/FileDocProvider.kt#L194)، إذا فشل `renameTo` يبدأ `copyTree`، لكنه يحذف كل ملف مصدر فور نسخه في [السطر 217](app/src/main/java/com/usbmediaexplorer/data/doc/FileDocProvider.kt#L217).

إذا امتلأت الوجهة أو فُصل USB في منتصف العملية:

1. تكون بعض الملفات قد حُذفت من المصدر.
2. تبقى نسخة جزئية في الوجهة.
3. يعيد المحرك محاولة النسخ بطريقة ثانية.
4. قد تتوزع محتويات المجلد بين وجهتين أو تُفقد.

الإصلاح الأدنى:

```kotlin
override suspend fun moveTo(node: DocNode, targetParent: DocNode): DocNode? {
    if (node.volumeId != targetParent.volumeId) return null
    val source = File(requireNotNull(node.uri.path))
    val target = File(requireNotNull(targetParent.uri.path), source.name)
    return if (!target.exists() && source.renameTo(target)) toNode(target) else null
}
```

يجب حذف `copyTree` من مسار النقل السريع. النقل بين الوحدات يجب أن يكون:

`نسخ إلى اسم مؤقت → تحقق من الحجم/التجزئة → تثبيت الوجهة → حذف المصدر`.

## 3. النسخ إلى المجلد نفسه — عالٍ

ينشئ [`FileOpsEngine.kt`](app/src/main/java/com/usbmediaexplorer/data/ops/FileOpsEngine.kt#L93) الوجهة ثم يعيد قراءة أطفال المصدر دون منع أن تكون الوجهة داخل المصدر.

اختبار إعادة الإنتاج الآمن:

- أنشئ `A/file.bin`.
- انسخ `A` والصقه داخل `A`.
- سيظهر `A/A/...` حتى نفاد المساحة أو فشل المسار.

يجب إضافة فحص مركزي قبل copy/move/zip:

```kotlin
require(!docRepository.isSameOrDescendant(destination, source)) {
    "Destination cannot be inside source"
}
```

يُنفذ للمسارات عبر `canonicalFile`، ولـSAF عبر authority وdocument ID.

## 4. التزامن وسلامة العمليات — عالٍ

كل عملية تنشئ coroutine مستقلة في [`FileOpsManager.kt`](app/src/main/java/com/usbmediaexplorer/data/ops/FileOpsManager.kt#L197)، بينما المحرك يشترك في `listingCache` قابل للتعديل في [`FileOpsEngine.kt`](app/src/main/java/com/usbmediaexplorer/data/ops/FileOpsEngine.kt#L35).

المطلوب:

- طابور عمليات محدود.
- عملية كتابة واحدة لكل وحدة تخزين.
- جعل cache محليًا لكل job بدل حقل singleton.
- قفل عمليات rename/copy التي تستهدف المجلد نفسه.
- سجل عمليات مستديم في Room مع حالات `STAGING/VERIFIED/COMMITTED`.

## 5. ZIP bomb وسلامة فك الضغط — عالٍ

الحماية من `../` موجودة، وهي نقطة إيجابية، لكن [`unzip`](app/src/main/java/com/usbmediaexplorer/data/ops/FileOpsEngine.kt#L269) لا يحد:

- إجمالي الحجم المفكوك.
- عدد الملفات.
- حجم الملف الواحد.
- عمق المسار وطوله.
- نسبة الضغط.
- المساحة الحرة المطلوبة.

أضف حدودًا قابلة للضبط، مثل 10,000 ملف، عمق 32، وتوقف قبل تجاوز المساحة المتاحة. استخدم مجلد staging واحذفه بالكامل عند الإلغاء. وفي الضغط، فشل فتح المصدر يجب أن يفشل العملية بدل إنشاء ملف ZIP فارغ.

## 6. الخصوصية والنسخ الاحتياطي — عالٍ

`allowBackup=true` في [`AndroidManifest.xml`](app/src/main/AndroidManifest.xml#L42)، بينما [`backup_rules.xml`](app/src/main/res/xml/backup_rules.xml#L2) يستثني المصغرات وقواعد البيانات فقط.

ملفات مثل `recent.json` و`playback.json` و`metadata.json` تحتوي أسماء ومسارات وسجل مشاهدة واضحًا في [`RecentStore.kt`](app/src/main/java/com/usbmediaexplorer/data/store/RecentStore.kt#L11).

التوصية الأقوى: `android:allowBackup="false"`. أو السماح بنسخ الإعدادات غير الحساسة فقط واستثناء جميع ملفات التاريخ والمسارات. لا توجد Telemetry ولا صلاحية إنترنت، وهذه نقطة خصوصية جيدة.

## 7. خطأ نتائج البحث — عالٍ

يتوقف المسح عند 500 نتيجة في [`SearchEngine.kt`](app/src/main/java/com/usbmediaexplorer/data/search/SearchEngine.kt#L124)، ثم يُخزن الجزء الذي تم مسحه كـsnapshot كامل في [السطر 148](app/src/main/java/com/usbmediaexplorer/data/search/SearchEngine.kt#L148).

بعد بحث واسع، قد لا يجد البحث اللاحق ملفًا موجودًا خارج أول 500 نتيجة.

الإصلاح: لا تخزن snapshot عندما تكون `truncated=true`، أو أكمل الفهرسة مع التوقف عن إضافة نتائج فقط. أبطِل الفهرس فور أي عملية ملفات.

## 8. CI وسلسلة التوريد — عالٍ

في [`build-apk.yml`](.github/workflows/build-apk.yml#L6):

- أي push إلى `arena/**` يستطيع تحديث `apk-latest`.
- الصلاحية العامة `contents: write`.
- إجراءات GitHub مثبتة بإصدارات tags لا بـcommit SHA.
- debug APK يُرفع قبل نجاح الاختبارات.
- Lint غير بوابة، وداخل Gradle `abortOnError=false`.
- wrapper غير مودع ولا يوجد SHA-256 لتوزيعة Gradle.
- سجلات الفشل تُدفع تلقائيًا إلى المستودع.

اجعل بناء الفروع read-only، والنشر من tag محمي فقط، وافصل release job بصلاحية `contents: write`. ثبّت actions بـSHA وأضف dependency verification وOSV/Dependabot.

## الصلاحيات والمنصة

يعامل [`Permissions.kt`](app/src/main/java/com/usbmediaexplorer/util/Permissions.kt#L92) وصول Android 14 الجزئي، أو إحدى صلاحيتي الصور/الفيديو، كأنه وصول تخزين كامل، مع أن التصفح يعتمد على المسارات الخام وليس MediaStore.

كذلك تُطلب صلاحيات الصور والفيديو والصوت والإشعارات دفعة واحدة. الأفضل طلبها سياقيًا، واعتماد SAF كمسار افتراضي، وطلب `MANAGE_EXTERNAL_STORAGE` فقط عند شرح الحاجة. التطبيق مؤهل نظريًا ضمن فئة مدير الملفات، لكنه يحتاج تصريح Google Play وسياسة خصوصية دقيقة.

قلّص `<external-path path=".">` في [`file_paths.xml`](app/src/main/res/xml/file_paths.xml#L12)، وأزل `file://` و`BROWSABLE` من مرشح VIEW، واجعل مستقبل وسائط التخزين غير مصدّر إن كان النظام وحده يحتاجه.

## الجودة والأداء والاختبارات

الإيجابيات:

- فصل جيد بين UI وViewModels وطبقات `DocProvider`.
- قراءة الفيديو مباشرة من SAF دون نسخه.
- تصغير الصور قبل عرضها.
- تقييد توليد المصغرات إلى 3 وmetadata إلى 2.
- موارد العربية والإنجليزية متطابقة: 422 نصًا لكل لغة، مع RTL.

الفجوات:

- 18,984 سطر إنتاج مقابل 846 سطر اختبار فقط.
- لا توجد اختبارات instrumented/UI.
- أكبر الشاشات تتجاوز 1,000 سطر، ما يزيد صعوبة الصيانة.
- `JsonStore` يحذف الملف الأصلي قبل تثبيت المؤقت في [`JsonStore.kt`](app/src/main/java/com/usbmediaexplorer/data/store/JsonStore.kt#L67)، ويبتلع أخطاء الكتابة. استخدم `AtomicFile` أو Room.
- تقدير حجم المجلد يتم قبل تشغيل الخدمة الأمامية، وقد يقرأ الشجرة كاملة مرتين.
- المهام محفوظة في الذاكرة و`START_NOT_STICKY`، فلا تستأنف بعد قتل العملية.

اختبارات الإصدار الإلزامية:

- فصل USB وامتلاء التخزين عند كل 1–5% من النسخ.
- نسخ/نقل المجلد إلى نفسه وإلى أحد أحفاده.
- ملفات ZIP عميقة، متكررة، تالفة وعالية الضغط.
- عمليات متزامنة إلى الوجهة نفسها.
- SAF providers تعيد null أو أسماء معدلة.
- Android 11 و13 و14 و15 مع منح جزئي ورفض دائم.
- 10,000 ملف، فيديو 100GB، وصور ضخمة/تالفة.
- TalkBack، حجم خط 200%، RTL، وتباين الألوان.

الأدوات المقترحة: Android Lint، Detekt، Ktlint، Semgrep، MobSF، JADX، apksigner، OSV-Scanner، Gradle dependency verification، Jazzer للفuzzing، Macrobenchmark، Baseline Profiles، Perfetto، heapprofd وBattery Historian.
