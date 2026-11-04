# Безпека · Security

## Як повідомити про вразливість · Reporting a vulnerability

Будь ласка, не пишіть про вразливість у відкритих issues. Скористайтеся приватним повідомленням: вкладка **Security → Report a vulnerability** у цьому репозиторії.

Please do not report a vulnerability in public issues. Use a private report: **Security → Report a vulnerability** in this repository.

- Відповідь — протягом **3 днів**. · A reply within **3 days**.
- Виправлення серйозної вразливості — протягом **7 днів**, з новим випуском і поясненням у його описі. · A fix for a serious vulnerability within **7 days**, in a new release with an explanation in its notes.

## Що Комора обіцяє · What Komora promises

- Встановлюється лише той застосунок, що в її списку, підписаний закріпленим ключем, і не старіший за встановлений та за найнижчу дозволену версію. · Only the app in its list is installed, signed with the pinned key, and never older than the installed one or the lowest allowed version.
- Автоматичні оновлення беруть лише випуски, яким уже дві доби. · Automatic updates only take releases that have been out for two days.
- Ключ підпису Комори ніколи не буває на GitHub: CI збирає непідписаний файл, а авторка підписує його на власному компʼютері (`scripts/sign-release.sh`), перевіривши, що це саме той файл. · Komora's signing key is never on GitHub: CI builds an unsigned file, and the author signs it on her own computer (`scripts/sign-release.sh`) after checking it is exactly that file.
- Мережа — лише GitHub через HTTPS; кожне перенаправлення перевіряється. · Network: GitHub only, over HTTPS; every redirect is checked.
- Застосунки з інших джерел Комора не перевіряє: вона лише стежить, щоб оновлення мали той самий ключ, що й перше встановлення. · Apps from other sources are not checked: Komora only makes sure updates carry the same key as the first install.

Як перевірити файл Комори без неї самої — у [README](README.md). · How to check Komora's file without Komora: see [README](README.md).

## Підтримувані версії · Supported versions

Лише остання версія. · Only the latest version.

Потрібен **Android 12** або новіший: лише з нього Android дає застосунку-крамниці оновлювати застосунки без зайвих запитань і закріплювати за собою їхні оновлення, а старші версії вже не отримують оновлень безпеки. · Requires **Android 12** or newer: only from there does Android let a store update apps without needless questions and keep their updates to itself, and older versions no longer get security updates.
