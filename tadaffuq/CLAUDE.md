# تدفّق — ملاحظات للمطورين ونماذج الذكاء الاصطناعي

- مشروع Gradle مستقل داخل `tadaffuq/` (وحدات: `engine` Kotlin/JVM خالص، `data` Room+DataStore، `app` Compose+Hilt). لا علاقة له ببناء «سَيْل» في جذر المستودع.
- الواجهة عربية أولاً (RTL) والإنجليزية اختيارية: `res/values` عربي، `res/values-en` إنجليزي. لكل إعداد سطر شرح.
- الأرقام لاتينية ومعزولة اتجاهياً: استعمل `sr(R.string.x, args)` بدل `stringResource(id, args)`، و`Fmt.iso()` للمسارات والروابط داخل نص عربي.
- النص يلتف ولا يُقص (لا `maxLines=1`/Ellipsis للمحتوى).
- لا قراءات مختلقة: الرسوم والمشهد الحي من `DownloadManager.live`/`speedHistory` فقط، وتتوقف الحركة عند توقف البيانات. «طبيب التحميل» قواعد ثابتة، لا يُسمّى ذكاءً اصطناعياً.
- Room مصدر الحقيقة؛ لا `fallbackToDestructiveMigration`. أي تغيير في الجداول = رفع `version` + Migration + اختبار.
- لا كتابة فوق ملف دون موافقة: `FileNames.uniqueIn` عند الاكتمال.
- الاستئناف لا يتم إلا إذا تطابق الحجم وETag/Last-Modified (`Prober.sameContent`) وتغطي الأجزاء الملف (`SegmentPlanner.isConsistent`)، وكل طلب جزء يحمل If-Range.
- لا وظائف وسائط متخصصة (بث، تشغيل، تحويل، ضغط).
- مفتاح التوقيع لا يُرفع: `keystore.properties` أو `TADAFFUQ_KEYSTORE`/`TADAFFUQ_KEYSTORE_PASSWORD`/`TADAFFUQ_KEY_ALIAS`.
- قبل الرفع: `./gradlew :engine:test :app:testDebugUnitTest :app:assembleRelease`. لقطات الشاشة: `TADAFFUQ_SHOTS=$PWD/build/shots ./gradlew :app:testDebugUnitTest --tests '*ScreenshotTest*'` وراجعها بعينك.
- Maven Central يرد أحياناً بـ 429؛ مرآة Google مضافة أولاً في `settings.gradle.kts`.

## Working preferences
- اكتب كل الأسئلة والردود للمستخدم بالعربية فقط.
