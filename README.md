# Shustree Proxy for Android

[English](#english) | [Русский](#русский)

---

## English

# [Shustree Proxy](https://shustree.ru) for Android

Shustree Proxy is an ultra-lightweight, high-performance Android VPN client built with Kotlin and Jetpack Compose[cite: 5, 8]. Designed with zero third-party tunneling or cryptography dependencies, it delivers native networking performance and absolute data privacy.

This repository contains **100% accurate source code** corresponding to the production [v1.4.3 release on Google Play Store](https://play.google.com/store/apps/details?id=ru.shustree.shustreeproxy)[cite: 8].

📖 **Documentation:** [shustree.ru/ru/doumentation](https://shustree.ru/ru/doumentation)

---

### Key Features

* **MTU 1280 Optimization:** Built with `VpnService` configured to an optimal 1280 MTU, ensuring high packet throughput, zero fragmentation issues, and stable connectivity across constrained networks[cite: 5].
* **Selective App Split Tunneling:** Integrated `disallowedApplications` builder parameters for targeted local routing optimization based on geographic region[cite: 5].
* **Fast & Resilient TLS Encryption:** High-speed full-duplex TLS tunneling layer (`TcpProxyWorker`) featuring active reconnect logic, automated ping/keep-alive framing, and Doze-mode resilience[cite: 5, 6].
* **Clean Architecture (1 Thread – 1 Worker):** Strict thread-isolation design using Kotlin Coroutines `Dispatchers.IO.limitedParallelism(1)` for dedicated TUN-reader, TUN-writer, and TCP worker channels—eliminating race conditions and lock contention[cite: 5, 6].
* **Zero Third-Party Tunneling Dependencies:** No Ktor, no OkHttp, no external C/C++ native libs, and no third-party tunneling/crypto frameworks[cite: 8]. Powered strictly by standard JDK sockets (`SSLSocket`), Coroutines, and native Android APIs (`android.net.VpnService`)[cite: 5, 6, 8].

---

### Absolute Privacy Promise

> **Not a single byte leaves your device unexpectedly.**
> There is **no Advertising ID**, no telemetry, no tracking SDKs, and zero connection to third-party APIs. The client communicates solely with the official Shustree API, transmitting nothing beyond system service responses associated with a locally generated device UUID[cite: 5].

---

### Requirements & Tech Stack

* **Language:** Kotlin[cite: 8]
* **UI Framework:** Jetpack Compose (Material 3)[cite: 8]
* **Min SDK:** 24 (Android 7.0)[cite: 8]
* **Target SDK:** 36 (Android 15)[cite: 8]
* **Architecture:** Kotlin Coroutines & Channels (Thread-limited IO)[cite: 5]

---

## Русский

# [Shustree Proxy](https://shustree.ru) для Android

Shustree Proxy — это сверхлегкий и производительный VPN-клиент для Android, разработанный на Kotlin и Jetpack Compose[cite: 5, 8]. Проект создан без использования сторонних сетевых библиотек и криптографических фреймворков, что обеспечивает максимальную скорость работы нативных средств ОС и абсолютную приватность данных.

Исходный код в этом репозитории на **100% соответствует продакшен-версии 1.4.3**, опубликованной в [Google Play Store](https://play.google.com/store/apps/details?id=ru.shustree.shustreeproxy)[cite: 8].

📖 **Документация:** [shustree.ru/ru/doumentation](https://shustree.ru/ru/doumentation)

---

### Основные особенности

* **Оптимизированный MTU 1280:** Настройка `VpnService` с MTU 1280 обеспечивает высокую скорость передачи пакетов без фрагментации в сложных сетевых условиях[cite: 5].
* **Сплит-туннелирование (Disallowed Apps):** Гибкая настройка списка исключенных приложений через `VpnService.Builder` для оптимизации трафика под конкретную страну[cite: 5].
* **Быстрое и отказоустойчивое TLS-шифрование:** Полнодуплексный TLS-туннель (`TcpProxyWorker`) с автоматическим переподключением, генерацией keep-alive пингов и защитой от обрывов сокета[cite: 5, 6].
* **Чистая архитектура (1 поток — 1 воркер):** Строгая изоляция потоков через `Dispatchers.IO.limitedParallelism(1)`. Отдельные выделенные каналы для чтения TUN, записи TUN и обработки TCP-трафика без состояний гонки и блокировок[cite: 5, 6].
* **Минимальный уровень зависимостей:** В проекте не используются Ktor, OkHttp, сторонние C/C++ библиотеки или сторонние фреймворки для туннелирования и шифрования[cite: 8]. Все работает исключительно на стандартных сокетах JDK (`SSLSocket`), Kotlin Coroutines и нативных инструментах Android SDK (`android.net.VpnService`)[cite: 5, 6, 8].

---

### Главный принцип приватности

> **Ни один байт клиента не уходит никуда без вашего ведома.**
> В приложении **полностью отсутствует Advertising ID**, нет аналитических метрик, трекеров и связей со сторонними API. Клиент взаимодействует только с официальным API Shustree, которое отправляет исключительно служебные данные в ответ на анонимный UUID устройства[cite: 5].

---

### Стек технологий и требования

* **Язык:** Kotlin[cite: 8]
* **Интерфейс:** Jetpack Compose (Material 3)[cite: 8]
* **Min SDK:** 24 (Android 7.0)[cite: 8]
* **Target SDK:** 36 (Android 15)[cite: 8]
* **Асинхронность:** Kotlin Coroutines & Channels[cite: 5]
