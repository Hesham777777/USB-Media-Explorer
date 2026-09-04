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
Card ──AsyncImage(ThumbRequest)──► Coil (ThumbKeyer → مفتاح الذاكرة · ThumbFetcherFactory)
                                     ▼
                      ThumbnailRepository.thumbnail(request)
                                     │
        ┌────────────────────────────┼────────────────────────────┐
        ▼                            ▼                            ▼
  ThumbnailCache              مولّدات حسب النوع                 كاش سلبي
 (فهرس JSON + LRU على        ┌─────────┼──────────┐        (مجلد بلا صور =
  القرص + ذاكرة Coil)        ▼         ▼          ▼          ملف بطول صفر،
                        VIDEO      IMAGE     DIRECTORY        لا يُعاد فحصه)
               VideoFrameExtractor  ImageThumbExtractor  FolderCoverExtractor
                        │                  │            coverScan → CoverRules.rank
                        │                  │            → ترويسة فقط → مصغّرة WebP
   1) MediaMetadataRetriever عبر ParcelFileDescriptor  ← الأساس
   2) ContentResolver.loadThumbnail (API 29+)          ← البديل الحديث
   3) الغلاف المدمج embeddedPicture                    ← اختياري (إعداد)
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
- المفتاح: `md5(uri | size | lastModified | العرض×الارتفاع | الجودة | الاستراتيجية | غلاف مجلد | تفضيل الغلاف المدمج | إصدار المحرك = 5)`.
  أي تعديل على الفيلم على الفلاشة يُبطل المعاينة القديمة تلقائيًا، وأي تغيير في الإعدادات
  يُنتج مفتاحًا مختلفًا بدل الكتابة فوق القديم.
- `ThumbnailCache`: ملفات `cacheDir/thumbs/<md5>.webp` + فهرس JSON (الحجم، `nodeKey`، آخر وصول)،
  مع `pruneTo(limit)` بإخلاء LRU، و`removeForNode()` بعد الحذف/إعادة التسمية،
  و`cleanOrphans()` للتأكد من وجود الملفات، و`clear()` للمسح اليدوي.
- الكتابة على القرص عبر WebP (`WEBP_LOSSY` على API 30+) لتقليل الحجم، مع JPEG كبديل.
- `generationLocks` يمنع توليد نفس المفتاح مرتين، و`AppDispatchers.thumbnail` يحدّ
  التوازي إلى 3 عمليات، فلا يحدث ازدحام على فلاشة بطيئة.

### أغلفة المجلدات — Folder Cover
كل مجلد فيلم يحتوي عادةً على صورة بوستر بجانب ملف الفيديو. هذه الصورة تُستخدم **غلافًا للمجلد نفسه**،
فيظهر المجلد في الشبكة كبطاقة بوستر (الضغط عليها يفتح المجلد كالمعتاد)، بينما تبقى الملفات الأخرى
كما هي: الفيديو = إطار حقيقي من داخله، الصورة = الصورة نفسها، وغيرهما = أيقونة النوع.

الفصل المعماري مقصود (ثلاث مهام، ثلاث وحدات):

| الوحدة | المهمة |
|---|---|
| `VideoFrameExtractor` | إطار حقيقي من داخل ملف الفيديو |
| `ImageThumbExtractor` | الصورة نفسها (مع EXIF وHEIF/AVIF) |
| `FolderCoverExtractor` | **أي صورة داخل المجلد تمثّل المجلد** (غلاف) |
| `CoverRules` | منطق الأولوية الصافي (بلا أندرويد وبلا I/O) — مُختبَر في `CoverRulesTest` |

خطوات الغلاف:
1. `DocProvider.coverScan(folder, imageLimit, videoNameLimit)` — مرور واحد محدود على المجلد
   (File: `listFiles()` مع خروج مبكر · SAF/USB: مؤشّر `DocumentsContract` مع خروج مبكر)،
   يعيد الصور المرشَّحة + أسماء الأفلام داخل المجلد.
2. `CoverRules.rank()` ترتّب المرشَّحات بالأسماء أولًا:
   - صورة باسم الفيلم نفسه (`Dune.Part.Two.2024.1080p.jpg` مع `…​.mkv`) ← أقوى إشارة (+120)،
   - ثم كلمات الفن الصريحة: poster/cover/front/folder/movie/film (+100)،
   - ثم صورة باسم المجلد (+90)، ثم إشارات أضعف (thumb/banner/backdrop/fanart/disc/back)،
   - وخصم لقطات الشاشة والعينات والشعارات (screenshot/sample/logo/icon/…‎ −80)،
   - وخصم GIF (−35) وICO (−90)، مع استبعاد المخفي وأصغر من 3 KB.
   المطابقة تتم على صيغة مطوية من الاسم (حروف وأرقام فقط، بلا حالة) فتعمل مع
   `Movie.2010.1080p.jpg` و`Movie 2010 1080p.jpg` ومع الأسماء العربية كذلك — **لا يوجد اسم
   مجلد أو اسم ملف إجباري**.
3. أعلى 4 مرشَّحات فقط تُفحص أبعادها بقراءة الترويسة (`inJustDecodeBounds`) — بلا أي فكّ
   للبكسلات، فتُفضَّل النسبة الرأسية 2:3 وتُستبعد لقطات الشاشة العريضة والأيقونات الصغيرة.
   إن لم تعلن أي صورة عن نفسها، تُفحص الأكبر حجمًا أولًا.
4. الفائز يُفكّ **مُصغَّرًا** عبر `ImageThumbExtractor` (بحجم الشبكة فقط) ويُرمَّز WebP في الكاش؛
   وإن تعذّر فكّه (RAW غريب/ملف تالف) ينتقل الترتيب إلى المرشَّح التالي.

ضمانات الميزة:
- **للقراءة فقط**: لا نسخ ولا نقل ولا إعادة تسمية ولا حذف ولا كتابة على وحدة التخزين،
  ولا تُنشأ نسخة كاملة من البوستر — الكاش يحمل مصغّرة WebP بحجم العرض فقط.
- **لا إنترنت**: الغلاف من داخل المجلد نفسه، ولا علاقة له بـ MediaStore (يعمل مع ملفات USB/OTG
  غير المفهرسة) ولا بأي خدمة بوسترات.
- **بلا تجميد**: كل شيء على `AppDispatchers.thumbnail` (توازي 3) مع `ensureActive()`، وCoil يلغي
  طلبات العناصر التي خرجت من الشاشة أثناء التمرير.
- **كاش سلبي**: المجلد الذي لا يحتوي صورًا يُسجَّل في الكاش كملف بطول صفر (`NO_COVER`) فلا يُعاد
  فحصه مع كل تمرير أو كل رجوع للشاشة، ويظهر بأيقونة المجلد العادية دون أي خطأ.
- **إبطال صحيح**: مفتاح الكاش يشمل `uri|size|lastModified` للمجلد، وأي حذف/نقل/إعادة تسمية داخل
  المجلد يمرّ بـ `ThumbnailRepository.invalidate()` الذي يُسقط غلاف المجلد الأب أيضًا
  (`invalidateParentCover`)، فتُلتقط الصورة الجديدة عند أول زيارة تالية.

العرض: في أوضاع الشبكة تُرسم بطاقة المجلد بنسبة `FOLDER_COVER_ASPECT` (2:3) مع `ContentScale.Fit`،
أي أن البوستر يظهر كاملًا **دون تمديد أو تشويه**، واسم المجلد تحته. وضع العرض الافتراضي
`GRID_MEDIUM` (3 أعمدة) فيظهر عدة بوسترات في الشاشة، والمجلدات التي تحتوي فيديوهات تأخذ
`GRID_LARGE` تلقائيًا لقراءة الإطارات الحقيقية.

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
