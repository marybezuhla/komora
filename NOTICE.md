# Сторонні бібліотеки · Third-party libraries

Комора поширюється за ліцензією GPL-3.0-or-later ([LICENSE](LICENSE)). У застосунок входять такі бібліотеки (усі — Apache License 2.0):

Komora is distributed under GPL-3.0-or-later ([LICENSE](LICENSE)). The app includes these libraries (all under the Apache License 2.0):

| Бібліотека · Library | Автор · Author | Ліцензія · Licence |
|---|---|---|
| Jetpack Compose (UI, Foundation, Runtime, Animation) | Google (The Android Open Source Project) | Apache-2.0 |
| Compose Material 3 | Google (The Android Open Source Project) | Apache-2.0 |
| AndroidX Core, Activity, Lifecycle, SavedState, Annotation, Collection та інші бібліотеки AndroidX, від яких вони залежать · and other AndroidX libraries they depend on | Google (The Android Open Source Project) | Apache-2.0 |
| Kotlin standard library | JetBrains | Apache-2.0 |
| kotlinx.coroutines | JetBrains | Apache-2.0 |

Повний текст Apache License 2.0: https://www.apache.org/licenses/LICENSE-2.0

Точний перелік із версіями й контрольними сумами — у [gradle/verification-metadata.xml](gradle/verification-metadata.xml) та [app/build.gradle.kts](app/build.gradle.kts). · The exact list with versions and checksums: [gradle/verification-metadata.xml](gradle/verification-metadata.xml) and [app/build.gradle.kts](app/build.gradle.kts).

Лише для тестів (не входять у застосунок) · Tests only (not in the app): JUnit (EPL-1.0), Robolectric (MIT), AndroidX Test (Apache-2.0), JSON-java (Public Domain).
