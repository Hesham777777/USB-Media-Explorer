# بنية التطبيق (Architecture)

> هذا الملف يشرح كيف نُفِّذت متطلبات `README.md` فعليًا في الكود، وأين توجد كل ميزة.

## 1. نظرة عامة

```
┌──────────────────────────────────────────────────────────────────────┐
│ UI (Jetpack Compose + Material 3)                                     │
│  Home · Browse · Player · Viewer · Search · Favorites · Settings      │
└───────────────▲───────────────────────────────────────▲──────────────┘
                │ StateFlow<UiState>                     │ callbacks
┌───────────────┴───────────────────────────────────────┴──────────────┐
│ ViewModels (BrowseViewModel · PlayerViewModel · …)                     │
└───────────────▲───────────────────────────────────────────────────────┘
                │
┌───────────────┴──────────────────────────────────────────────────────┐
│ Data layer                                                             │
│  doc/      DocNode + DocProvider (File ↔ SAF) + DocRepository          │
│  volume/   VolumeRepository · VolumeMonitor · VolumeEventBus           │
│  thumb/    ThumbnailRepository · VideoFrameExtractor · ThumbnailCache  │
│  metadata/ MediaMetadataReader · MetadataRepository · MetadataStore    │
│  ops/      FileOpsEngine · FileOpsManager · FileOpsService             │
│  search/   SearchEngine                                                │
│  settings/ SettingsRepository (DataStore)                              │
│  store/    JsonStore (favorites · recents · resume · folder prefs)     │
└───────────────────────────────────────────────────────────────────────┘
```

لا يوجد Hilt/Dagger: كل الاعتماديات تُنشأ مرة واحدة في `di/AppContainer.kt` وتُمرَّر
لـ ViewModels عبر `viewModelFactory { … }`. السبب: المشروع لا يحتاج أكثر من Singletons،
وتجنُّب معالجات التعليقات التوضيحية (KAPT/KSP) يجعل البناء أسرع وأقل عرضة للكسر.

## 2. توحيد التخزين: `DocNode` + `DocProvider`

أهم قرار معماري في التطبيق. أندرويد يتعامل مع التخزين الداخلي بمسارات `File`، بينما
وحدات USB/OTG لا تُفتح إلا عبر Storage Access Framework بمستندات `content://`.

| الواجهة | التنفيذ | متى تُستخدم |
|---|---|---|
| `DocProvider` | `FileDocProvider` | التخزين الداخلي، وبطاقات SD المكشوفة كمسار |
| `DocProvider` | `SafDocProvider`  | USB OTG، وأي وحدة تُمنح عبر `ACTION_OPEN_DOCUMENT_TREE` |
| الواجهة الموحّدة | `DocRepository`   | يختار المزوّد حسب `Uri.scheme` ويضيف Breadcrumb وFileProvider |

`DocNode` يحمل: `uri`, `name`, `isDirectory`, `size`, `lastModified`, `mimeType`,
`volumeId`, `displayPath`, `isWritable`, `canCreateChildren`, `documentId`.

النتيجة: **كل الشاشات والعمليات والمعاينات تعمل بنفس الكود** سواء كان الملف على الفلاشة
أو على الذاكرة الداخلية.

### لماذا لا نعتمد على MediaStore؟
تنفيذًا للبند 27 في المواصفات: وحدات USB غير مفهرسة غالبًا في MediaStore، لذلك:
- `SafDocProvider.openFd()` يُعيد `ParcelFileDescriptor` حقيقيًا من مستند SAF.
- هذا الـ fd يُمرَّر مباشرة إلى `MediaMetadataRetriever.setDataSource(fd)` و`MediaExtractor`،
  أي أن التطبيق **يقرأ الفيديو من الفلاشة نفسها** ويستخرج الإطار دون أي نسخ.

## 3. اكتشاف وحدات التخزين

`VolumeRepository` يبني القائمة بهذا الترتيب:
1. التخزين الداخلي (`Environment.getExternalStorageDirectory()` + `StatFs`).
2. الوحدات القابلة للإزالة من `StorageManager.storageVolumes` مع:
   - منحة SAF محفوظة سابقًا (`persistedUriPermissions`) ← حالة `READY` دون أي حوار،
   - أو مسار `/storage/XXXX-YYYY` قابل للقراءة ← `READY`،
   - وإلا ← `NEEDS_PERMISSION` مع `Intent` جاهز (`createOpenDocumentTreeIntent()` على أندرويد 11+،
     أو `ACTION_OPEN_DOCUMENT_TREE` مع `INITIAL_URI`).
3. منح الشجرة المحفوظة التي لا تطابق وحدة مُركَّبة (مجلد فرعي منحه المستخدم).
4. جهاز USB Mass Storage ظاهر في `UsbManager` لكن لم تُركَّب وحدته بعد.

الأحداث: `VolumeMonitor` (مسجَّل وقت التشغيل: USB attach/detach + MEDIA_* + `StorageVolumeCallback`
على أندرويد 11+) و`VolumeEventReceiver` (في `AndroidManifest` لأن بثّ `MEDIA_MOUNTED` مستثنى من
قيود الخلفية). الكل ينشر في `VolumeEventBus` ← `VolumeRepository.refresh()`.

`FileSystemProbe` يقرأ `/proc/mounts` لعرض نوع النظام (FAT32/exFAT/NTFS) بشكل تجميلي فقط.

## 4. خط إنتاج المعاينات (أهم جزء)

```
Card ──AsyncImage(ThumbRequest)──► Coil
                                     │  ThumbKeyer  → مفتاح الذاكرة
                                     │  ThumbFetcherFactory
                                     ▼
                            ThumbnailRepository.thumbnail(request)
                                     │
                    ┌────────────────┼──────────────────────────┐
                    ▼                ▼                          ▼
            ThumbnailCache    VideoFrameExtractor      ImageThumbExtractor
           (فهرس JSON + LRU)         │                          │
                                     │                  ImageDecoder (HEIF/AVIF/GIF)
                                     │                  BitmapFactory + inSampleSize + EXIF
                                     ▼
             1) MediaMetadataRetriever عبر ParcelFileDescriptor  ← الأساس
             2) ContentResolver.loadThumbnail (API 29+)          ← البديل الحديث
             3) الغلاف المدمج embeddedPicture                    ← آخر محاولة
             4) فشل كامل → أيقونة النوع + الامتداد                ← بند 25
```

### اختيار الإطار (بند 4)
`FrameStrategy` = FIRST / 5% / 10% / 25% / MIDDLE / **AUTO**.
في وضع AUTO:
1. تُجرَّب المواضع `10% → 25% → 50% → 3% → 75%`.
2. كل موضع يُقرأ مصغَّرًا (128px) عبر `getScaledFrameAtTime` (API 27+).
3. `Bitmaps.frameScore()` يسجّل كل إطار حسب: متوسط الإضاءة (يرفض الأسود/المحروق)،
   الانحراف المعياري (يرفض الألوان المصمتة)، واللونية (يفضّل المحتوى الحقيقي).
4. يتوقف الفحص مبكرًا عند درجة ≥ 0.68 ثم يُستخرج الإطار الفائز بالحجم الكامل.

### التخزين المؤقت (بند 5)
- المفتاح: `md5(uri | size | lastModified | العرض×الارتفاع | الجودة | الاستراتيجية | معاينة مجلد | تفضيل الغلاف | بوستر | أسلوب المجلد | إصدار المحرك = 4)`.
  أي تعديل على الفيلم على الفلاشة يُبطل المعاينة القديمة تلقائيًا، وأي تغيير في الإعدادات
  يُنتج مفتاحًا مختلفًا بدل الكتابة فوق القديم.
- `ThumbnailCache`: ملفات `cacheDir/thumbs/<md5>.webp` + فهرس JSON (الحجم، `nodeKey`، آخر وصول)،
  مع `pruneTo(limit)` بإخلاء LRU، و`removeForNode()` بعد الحذف/إعادة التسمية،
  و`cleanOrphans()` للتأكد من وجود الملفات، و`clear()` للمسح اليدوي.
- الكتابة على القرص عبر WebP (`WEBP_LOSSY` على API 30+) لتقليل الحجم، مع JPEG كبديل.
- `generationLocks` يمنع توليد نفس المفتاح مرتين، و`AppDispatchers.thumbnail` يحدّ
  التوازي إلى 3 عمليات، فلا يحدث ازدحام على فلاشة بطيئة.

### جدار البوسترات (ViewMode.POSTER)
وضع العرض الافتراضي الآن: بطاقات رأسية بنسبة **2:3** (عمودان رأسيًا / 4 أفقيًا) تشبه جدار بوسترات
الأفلام، والاسم وسطر المعلومات فوق تظليل متدرّج أسفل البطاقة، مع شارة المدة وشريط استئناف
المشاهدة وعلامات المفضلة/التحديد فوق الصورة.

مصدر الصورة هو **الملف نفسه دائمًا** (بند 23 — لا إنترنت ولا تحميل بوسترات):
1. الغلاف المدمج `MediaMetadataRetriever.embeddedPicture` إن وُجد داخل MKV/MP4
   (يفضَّل تلقائيًا في وضع البوسترات ما دام إعداد «الغلاف المدمج أولًا» مفعَّلًا — وهو الافتراضي).
2. وإلا إطار مُستخرج حسب `FrameStrategy`.
3. الإطار/الغلاف يُقصّ من الوسط إلى نسبة البوستر (`Bitmaps.centerCrop`) بدل ترك هوامش،
   لأن إطار 16:9 يجب أن يملأ بطاقة 2:3.

العلم `poster` جزء من `ThumbRequest` ومفتاح الكاش، فتحصل البطاقة نفسها على نسختين مستقلتين
(شبكة عادية وبوستر) دون تعارض. المجلدات تبقى مربّعة في هذا الوضع (`ContentScale.Fit`) حتى لا
يُقصّ شكل المجلد.

### مجلدات بأسلوب ويندوز (FolderPreviewStyle.WINDOWS)
`Bitmaps.composeFolderWindows(parts, w, h)` ترسم مجلدًا حقيقيًا بأوامر Canvas فقط
(بلا موارد إضافية وبلا حدود إصدار أندرويد):

```
      ┌────────────┐            لسان أعلى اليسار (44% من العرض)
    ┌─┴────────────┴────────────┐
    │  [معاينة 1]  [معاينة 2]   │  الجيب: حتى 4 معاينات من وسائط المجلد نفسه
    │  [معاينة 3]  [معاينة 4]   │
    ├───────────────────────────┤  الشفة الأمامية تغطي أسفل الجيب
    └───────────────────────────┘
```

- جسم المجلد والشفة بتدرّجات كهرمانية، وجيب داكن بزوايا دائرية، وخط إضاءة على حافة الشفة،
  وحدّ خارجي شبه شفاف ليقرأ المجلد على الخلفيات الفاتحة أيضًا.
- تخطيط الجيب يتكيّف مع عدد المعاينات: 1 (كامل) / 2 (نصفان) / 3 (اثنان + عريض) / 4 (2×2)،
  وكل خانة تُملأ بقصّ من الوسط (`drawCropped`) فلا تظهر فراغات.
- الخلفية الشفافة تبقى محفوظة لأن الترميز WebP يدعم ألفا.
- المجلد الذي لا يحتوي وسائط قابلة للمعاينة يُرسم بشكل المجلد الفارغ (بدل أيقونة عامة)
  حتى تبقى الشبكة متجانسة.
- الأسلوب البديل `MONTAGE` هو الفسيفساء 2×2 السابقة، وكلاهما قابل للتبديل من الإعدادات.

### الأداء (بند 22)
- Lazy grids (`LazyVerticalGrid`) مع مفاتيح ثابتة.
- `LaunchedEffect(node.key)` داخل كل بطاقة ← `onItemVisible()` يطلب البيانات الوصفية
  وحجم محتوى المجلدات **للعناصر الظاهرة فقط**.
- `MetadataRepository` بطابور `Channel` (سعة 256، DROP_OLDEST) + `Semaphore(2)` لقراءة الترويسات.
- `MetadataStore` يحفظ النتائج في JSON حتى لا تُقرأ ترويسة الفيلم مرتين.
- Coil يلغي الطلبات عند خروج البطاقة من الشاشة.

## 5. المشغّل (بنود 9–11، 19)

- `Media3 ExoPlayer` مع `DefaultDataSource` ← تشغيل مباشر من `content://` على USB دون نسخ.
- قائمة التشغيل = فيديوهات نفس المجلد بترتيب المتصفح الحالي ← التالي/السابق دون رجوع.
- الترجمة: اكتشاف تلقائي لملفات `srt/ass/ssa/vtt` بجانب الفيلم (مطابقة الاسم) + تحميل يدوي
  عبر `replaceMediaItem` بإضافة `SubtitleConfiguration`.
- المسارات: `player.currentTracks` → خيارات صوت/ترجمة، والتبديل عبر `TrackSelectionParameters`.
- الاستئناف: `PlaybackPositionStore` يحفظ الموضع كل ~10 ثوانٍ وعند الإيقاف/الخروج،
  مع حوار «استئناف / من البداية» (قابل للتعطيل).
- التحكم: Compose فوق `PlayerView(useController=false)` — نقر = إظهار/إخفاء،
  نقر مزدوج يمين/يسار = ±10 ثوانٍ، سحب أفقي = تمرير، سحب رأسي أيمن = مستوى الصوت،
  قفل الشاشة، السرعة، نسبة العرض، ملء الشاشة.

## 6. عمليات الملفات (بنود 14–16)

- `FileOpsEngine`: نسخ/نقل/حذف/ضغط/استخراج/إعادة تسمية جماعية، بنسخ تدفّقي
  (buffer = 256KB) يعمل بين الأنظمة الخلفية (File ↔ SAF) وبين الوحدات.
- `FileOpsManager`: سجل مهام مع `JobProgress` (النسبة، السرعة عبر `SpeedTracker`
  بنافذة 3 ثوانٍ، الوقت المتبقي، العنصر الحالي)، دعم إيقاف مؤقت/استئناف/إلغاء،
  وحافظة نسخ/قص (`Clipboard`) لأمر «لصق».
- `FileOpsService`: خدمة أمامية (`dataSync`) تعرض إشعارًا مستمرًا وتتوقف تلقائيًا عند انتهاء الطابور.
- الأسماء المتكررة تُعالَج بـ `uniqueName()` → `name (1).ext`.
- حماية Zip-Slip عند الاستخراج، والحذف/إعادة التسمية تُبطل المعاينات والبيانات الوصفية المخزّنة.

## 7. التخزين المحلي الصغير

بدل Room (وما يستلزمه من KSP) تُستخدم `JsonStore`: ملف JSON واحد لكل مجموعة، كتابة ذرّية
(ملف مؤقت + إعادة تسمية)، و`StateFlow` للتغييرات:
`favorites.json` · `recent.json` · `playback.json` · `folder_prefs.json` · `metadata.json` ·
`thumbs_index.json`.

## 8. الواجهة واللغات

- Material 3 مع `dynamicColor` (أندرويد 12+) ولوحة احتياطية في `ui/theme/Color.kt`.
- `ThemeMode` = SYSTEM / LIGHT / DARK، ووضع Player غامق دائمًا.
- `values/strings.xml` + `values-ar/strings.xml` (تغطية كاملة) مع `locales_config.xml`
  و`AppCompatDelegate.setApplicationLocales` لتبديل اللغة داخل التطبيق.
- `android:supportsRtl="true"` + اتجاه الأعمدة يتغيّر حسب الوضع الأفقي/الرأسي.

## 9. خارطة الملفات

```
app/src/main/java/com/usbmediaexplorer/
├── UsbMediaExplorerApp.kt      Application + ImageLoaderFactory
├── MainActivity.kt             نشاط واحد + نوايا VIEW/USB + اللغة
├── di/AppContainer.kt          graph الاعتماديات
├── data/
│   ├── doc/       DocNode, DocProvider, FileDocProvider, SafDocProvider, DocRepository, DocUri, DocSorter, MediaKind
│   ├── volume/    VolumeInfo, VolumeRepository, VolumeMonitor, VolumeEventBus, VolumeEventReceiver, FileSystemProbe
│   ├── thumb/     ThumbModels, ThumbnailCache, VideoFrameExtractor, ImageThumbExtractor, ThumbnailRepository, CoilSetup
│   ├── metadata/  MediaMetadata, MediaMetadataReader, MetadataStore, MetadataRepository
│   ├── ops/       OpModels, FileOpsEngine, FileOpsManager, FileOpsService, OpsNotifications
│   ├── search/    SearchEngine
│   ├── settings/  AppSettings, SettingsRepository
│   └── store/     JsonStore, FavoritesStore, RecentStore, PlaybackPositionStore, FolderPrefsStore
├── ui/
│   ├── AppRoot.kt  Scaffold عام + شريط النقل الدائم
│   ├── theme/      Color, Type, Theme
│   ├── nav/        Routes, AppNavigator, AppNavHost
│   ├── common/     LocalApp, MediaIcons, ThumbModels (MediaThumbnail), Dialogs
│   ├── home/       HomeScreen, HomeViewModel, VolumeCard
│   ├── browse/     BrowseScreen, BrowseViewModel, components/{DocGrid, BreadcrumbBar, Sheets, DetailsSheet}
│   ├── player/     PlayerScreen, PlayerViewModel
│   ├── viewer/     ImageViewerScreen (+ ViewModel)
│   ├── search/     SearchScreen, SearchViewModel
│   ├── library/    FavoritesScreen, RecentScreen (+ ViewModels)
│   ├── settings/   SettingsScreen
│   └── ops/        TransfersScreen, TransferBar
└── util/           Formatters, Bitmaps, Hashing, SpeedTracker, AppDispatchers, Intents, Permissions, Power
```
