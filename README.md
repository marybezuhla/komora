<img src=".github/repo_icon.png" width="96" alt="">

# Комора · Komora

Простий відкритий магазин застосунків для Android. Встановлює й оновлює застосунки зі свого каталогу просто з GitHub. Українська та англійська. Безкоштовно, без реклами, акаунтів і стеження.

**Безпека.** Перелік застосунків вшитий у саму Комору. Кожен файл перед встановленням перевіряється: це має бути саме той застосунок, підписаний ключем, закріпленим у Коморі, і новіший за встановлений. Файл з чужим підписом не встановиться, навіть якщо його підмінили на GitHub. Комора зʼєднується лише з GitHub і лише через захищене зʼєднання.

**Інші джерела** (за замовчуванням вимкнено). У налаштуваннях можна дозволити додавати будь-який публічний репозиторій GitHub, після попередження про ризики й згоди. Комора такі застосунки не перевіряє: перед першим встановленням вона показує назву, пакет і ключ підпису, і далі приймає лише оновлення з тим самим ключем. Додані застосунки позначено окремою вкладкою й червоною рамкою. Щоб знати, чи доданий застосунок встановлено, Комора бачить застосунки з іконкою на головному екрані, але нікуди їх не надсилає й не зберігає.

**Встановлення.** Завантажте .apk з останнього випуску на телефоні й відкрийте його. Браузер чи Android попросять дозволити встановлення з цього джерела. Потрібен Android 12 або новіший.

**Приватність.** Акаунтів, аналітики й реклами немає. Під час кожного запуску Комора питає GitHub про нові випуски, тому GitHub бачить адресу телефона, як і для будь-якого сайту. Якщо в налаштуваннях увімкнено сповіщення про оновлення, ті самі питання (про всі застосунки зі списку) ставляться у фоні раз на день або раз на тиждень, як оберете.

---

A simple open-source app store for Android. Installs and updates the apps in its catalog straight from GitHub. Every file is checked before installing: the right app, signed with the key pinned in Komora for that app, newer than the installed one. Connects only to GitHub, only over HTTPS; GitHub sees the phone's address when Komora checks for updates at each start, and once a day or once a week in the background if update notices are turned on in Settings.

Other sources (off by default): after a warning and the owner's agreement, any public GitHub repository can be added. Komora does not check those apps: before the first install it shows the app's name, package and signing key, and afterwards accepts only updates with the same key. To tell whether an added app is installed, Komora can see apps that have a home-screen icon; it never sends or keeps that list.

Install: download the .apk from the latest release on your phone and open it. Requires Android 12 or newer.

## Ліцензія · License

Комора — вільне програмне забезпечення: її можна поширювати та змінювати за умовами GNU General Public License, опублікованої Free Software Foundation, версії 3 або (на ваш вибір) будь-якої новішої. Вона поширюється без жодних гарантій. Повний текст: [LICENSE](LICENSE).

Komora is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version. It comes without any warranty. Full text: [LICENSE](LICENSE).

SPDX-License-Identifier: GPL-3.0-or-later
