# Приватність · Privacy

## Українською

Комора не має акаунтів, аналітики, реклами чи звітів про збої. Вона нічого не надсилає про вас чи ваш телефон.

**Куди Комора звертається.** Лише до GitHub і лише через захищене зʼєднання (HTTPS): `api.github.com`, `github.com`, сервери файлів випусків GitHub і, для знімків екрана, `raw.githubusercontent.com`. Інших адрес у коді немає, перенаправлення на інші сайти відхиляються.

**Що бачить GitHub.** Адресу (IP) телефона, коли Комора питає:
- про нові версії застосунків — коли ви відкриваєте Комору й, якщо ввімкнено, раз на день у фоні (сповіщення чи автоматичні оновлення). У фоні про застосунки з інших джерел Комора питає лише тоді, коли вони встановлені, тож GitHub може бачити, які з доданих вами застосунків є на телефоні. Коли Комора відкрита, вона питає про всі додані джерела, тож GitHub може бачити весь ваш список. Кожен запит підписано назвою «Komora», тож GitHub бачить, що питає саме Комора;
- про знімки екрана й іконки застосунків, які ви додали самі: лише якщо ви ввімкнули «Знімки й іконки сторонніх джерел» (за замовчуванням вимкнено), іконку один раз, а знімки, коли ви відкриваєте подробиці такого застосунку. Вимкнете, і всі ці картинки одразу зникнуть з телефона;
- про знімки екрана застосунків зі списку Комори: одразу після перевірки нових версій, наперед для всіх, коли знімків останньої версії ще немає на телефоні (уперше, після нової версії, іншою мовою чи після того, як Android очистив тимчасову памʼять). Через те, що вони завантажуються для всіх разом, GitHub здебільшого не бачить, який застосунок ви переглядали;
- коли ви завантажуєте файл застосунку.

**Що зберігається на телефоні** (лише в приватній памʼяті Комори, без резервних копій і перенесення на інший телефон): налаштування (тема, мова, сповіщення, автоматичні оновлення), список доданих вами джерел із їхніми ключами, які версії вже були в сповіщенні, від яких версій ви відмовилися й які застосунки сьогодні оновилися автоматично, останні відповіді GitHub про випуски (щоб не завантажувати їх знову, поки нової версії немає; яка версія найновіша, Комора все одно питає, зазвичай не частіше ніж раз на пів години, якщо ви не потягнете список униз; частіше — коли у випуску немає придатного файлу, після невдалого завантаження й перед автоматичним оновленням). Відповідь про прибране джерело стирається, щойно вже не можна скасувати прибирання, а якщо Комору закрили раніше — під час наступного запуску; узагалі Комора тримає не більше 30 таких відповідей. Знімки екрана зберігаються в тимчасовій памʼяті Комори (до 50 МБ для списку Комори, а якщо ввімкнено знімки сторонніх джерел, ще до 25 МБ знімків і 5 МБ іконок; Android може її очистити). Видалення Комори стирає все це.

**Які застосунки Комора бачить.** Застосунки зі свого списку, а також застосунки з іконкою на головному екрані. Ці останні Комора використовує лише для доданих вами джерел, щоб знати, чи встановлено такий застосунок і яким ключем. Цей перелік Комора нікуди не надсилає й не зберігає.

**«Повідомити про помилку»** відкриває GitHub у браузері із заготовкою (назва застосунку, його версія, версія Android). Нічого не надсилається, доки ви самі цього не зробите.

**Посилання «Підтримати Україну»** відкриваються в браузері, на сайтах самих організацій. Комора не знає, чи ви по них переходили.

## English

Komora has no accounts, analytics, ads or crash reports. It sends nothing about you or your phone.

**Where Komora connects.** Only to GitHub, only over HTTPS: `api.github.com`, `github.com`, GitHub's release file servers and, for screenshots, `raw.githubusercontent.com`. There are no other addresses in the code, and redirects to other sites are refused.

**What GitHub sees.** The phone's address (IP) when Komora asks:
- about new versions — when you open Komora and, if turned on, once a day in the background (notifications or automatic updates). In the background Komora asks about apps from other sources only if they are installed, so GitHub can see which of the apps you added are on the phone. While Komora is open it asks about every added source, so GitHub can see your whole list. Every request is labelled "Komora", so GitHub can tell it is Komora asking;
- for the screenshots and icons of the apps you added yourself: only if you turned on "Pictures from added sources" (off by default), the icon once, and the screenshots when you open such an app's details. Turning it off removes all of these pictures from the phone at once;
- for the screenshots of the apps in Komora's list: right after checking for new versions, ahead for all of them, when the screenshots of the latest version are not on the phone yet (the first time, after a new version, in another language, or after Android cleared temporary storage). As they come for all apps together, GitHub mostly cannot see which app was looked at;
- when you download an app's file.

**What stays on the phone** (only in Komora's private storage, never in backups or moved to another phone): settings (theme, language, notifications, automatic updates), the sources you added with their keys, which versions were already in a notification, which versions you turned down and which apps were updated automatically today, GitHub's last answers about releases (so they are not downloaded again while there is no new version; Komora still asks which version is newest, usually not more than once in half an hour unless you pull the list down; more often when a release has no usable file, after a failed download and before an automatic update). The answer about a removed source is erased once the removal can no longer be undone, or at the next start if Komora was closed before that; in all, Komora keeps no more than 30 such answers. Screenshots are kept in Komora's temporary storage (up to 50 MB for Komora's list, and with pictures from added sources turned on, up to 25 MB more of screenshots and 5 MB of icons; Android may clear it). Uninstalling Komora erases all of it.

**Which apps Komora can see.** The apps in its own list, and also apps with a home-screen icon. Komora uses the latter only for the sources you added, to tell whether such an app is installed and with which key. Komora never sends or keeps that list.

**"Report a problem"** opens GitHub in the browser with a draft (the app's name, its version, the Android version). Nothing is sent unless you send it yourself.

**"Support Ukraine" links** open in the browser, on the organisations' own sites. Komora does not know whether you followed them.
