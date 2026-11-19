# StemStudio AI (Android, 6-Stem Local Separator)

Автономный Android-проект для разделения музыки на 6 стемов (`vocals`, `drums`, `bass`, `guitar`, `piano`, `other`) локально на ARM64 с помощью `htdemucs_6s.onnx`.

## Требования для сборки
- Android Studio Ladybug / Meerkat+ (или Android SDK 35 + Gradle 8.10.2)
- JDK 17
- Python 3.11 в PATH (используется плагином Chaquopy только на этапе сборки)

## Сборка APK
Открой папку `StemStudio` в Android Studio (она автоматически подтянет Gradle Wrapper) или выполни в терминале:
```bash
gradle assembleDebug --no-configuration-cache
```
Готовый файл: `app/build/outputs/apk/debug/app-debug.apk`

## Модель (автозагрузка при первом запуске)
- FP16 (136 MB, по умолчанию): https://huggingface.co/StemSplitio/htdemucs-6s-onnx/resolve/main/htdemucs_6s_fp16weights.onnx
- FP32 (258 MB): https://huggingface.co/StemSplitio/htdemucs-6s-onnx/resolve/main/htdemucs_6s.onnx
