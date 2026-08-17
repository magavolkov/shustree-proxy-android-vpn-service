# Shustree Proxy for Android

[English](#english) | [Русский](#русский)

---

## English

# [Shustree Proxy](https://shustree.ru) for Android

Shustree Proxy is an ultra-lightweight, high-performance Android VPN client built with [Kotlin](https://kotlinlang.org/) and [Jetpack Compose](https://developer.android.com/jetpack/compose). Designed with zero third-party tunneling or cryptography dependencies, it delivers native networking performance and absolute data privacy.

This repository contains **100% accurate source code** corresponding to the production [v1.4.3 release on Google Play Store](https://play.google.com/store/apps/details?id=ru.shustree.shustreeproxy).

📖 **Documentation:** [shustree.ru/ru/doumentation](https://shustree.ru/ru/doumentation)

---

### Key Features

* **MTU 1280 Optimization:** Built with [`VpnService`](https://developer.android.com/reference/android/net/VpnService) configured to an optimal 1280 MTU, ensuring high packet throughput, zero fragmentation issues, and stable connectivity across constrained networks.
* **Selective App Split Tunneling:** Integrated [`disallowedApplications`](https://developer.android.com/reference/android/net/VpnService.Builder#addDisallowedApplication(java.lang.String)) builder parameters for targeted local routing optimization based on geographic region.
* **Fast & Resilient TLS Encryption:** High-speed full-duplex TLS tunneling layer (`TcpProxyWorker`) featuring active reconnect logic, automated ping/keep-alive framing, and Doze-mode resilience.
* **Clean Architecture (1 Thread – 1 Worker):** Strict thread-isolation design using Kotlin Coroutines [`Dispatchers.IO.limitedParallelism(1)`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-coroutine-dispatcher/limited-parallelism.html) for dedicated TUN-reader, TUN-writer, and TCP worker channels—eliminating race conditions and lock contention.
* **Zero Third-Party Tunneling Dependencies:** No Ktor, no OkHttp, no external C/C++ native libs, and no third-party tunneling/crypto frameworks. Powered strictly by standard JDK sockets ([`SSLSocket`](https://developer.android.com/reference/javax/net/ssl/SSLSocket)), [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-overview.html), and native Android APIs ([`android.net.VpnService`](https://developer.android.com/reference/android/net/VpnService)).

---

### Absolute Privacy Promise

> **Not a single byte leaves your device unexpectedly.**
> There is **no Advertising ID**, no telemetry, no tracking SDKs, and zero connection to third-party APIs. The client communicates solely with the official Shustree API, transmitting nothing beyond system service responses associated with a locally generated device UUID.

---

### Requirements & Tech Stack

* **Language:** [Kotlin](https://kotlinlang.org/)
* **UI Framework:** [Jetpack Compose (Material 3)](https://developer.android.com/jetpack/compose/designsystems/material3)
* **Min SDK:** 24 ([Android 7.0 Nougat](https://developer.android.com/about/versions/nougat))
* **Target SDK:** 36 ([Android 15 / Baklava](https://developer.android.com/about/versions/15))
* **Architecture:** [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-overview.html) & [Channels](https://kotlinlang.org/docs/channels.html) (Thread-limited IO)

---

## Русский

# [Shustree Proxy](https://shustree.ru) для Android

Shustree Proxy — это сверхлегкий и производительный VPN-клиент для Android, разработанный на [Kotlin](https://kotlinlang.org/) и [Jetpack Compose](https://developer.android.com/jetpack/compose). Проект создан без использования сторонних сетевых библиотек и криптографических фреймворков, что обеспечивает максимальную скорость работы нативных средств ОС и абсолютную приватность данных.

Исходный код в этом репозитории на **100% соответствует продакшен-версии 1.4.3**, опубликованной в [Google Play Store](https://play.google.com/store/apps/details?id=ru.shustree.shustreeproxy).

📖 **Документация:** [shustree.ru/ru/doumentation](https://shustree.ru/ru/doumentation)

---

### Основные особенности

* **Оптимизированный MTU 1280:** Настройка [`VpnService`](https://developer.android.com/reference/android/net/VpnService) с MTU 1280 обеспечивает высокую скорость передачи пакетов без фрагментации в сложных сетевых условиях.
* **Сплит-туннелирование (Disallowed Apps):** Гибкая настройка списка исключенных приложений через [`VpnService.Builder`](https://developer.android.com/reference/android/net/VpnService.Builder#addDisallowedApplication(java.lang.String)) для оптимизации трафика под конкретную страну.
* **Быстрое и отказоустойчивое TLS-шифрование:** Полнодуплексный TLS-туннель (`TcpProxyWorker`) с автоматическим переподключением, генерацией keep-alive пингов и защитой от обрывов сокета.
* **Чистая архитектура (1 поток — 1 воркер):** Строгая изоляция потоков через [`Dispatchers.IO.limitedParallelism(1)`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-coroutine-dispatcher/limited-parallelism.html). Отдельные выделенные каналы для чтения TUN, записи TUN и обработки TCP-трафика без состояний гонки и блокировок.
* **Минимальный уровень зависимостей:** В проекте не используются Ktor, OkHttp, сторонние C/C++ библиотеки или сторонние фреймворки для туннелирования и шифрования. Все работает исключительно на стандартных сокетах JDK ([`SSLSocket`](https://developer.android.com/reference/javax/net/ssl/SSLSocket)), [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-overview.html) и нативных инструментах Android SDK ([`android.net.VpnService`](https://developer.android.com/reference/android/net/VpnService)).

---

### Главный принцип приватности

> **Ни один байт клиента не уходит никуда без вашего ведома.**
> В приложении **полностью отсутствует Advertising ID**, нет аналитических метрик, трекеров и связей со сторонними API. Клиент взаимодействует только с официальным API Shustree, которое отправляет исключительно служебные данные в ответ на анонимный UUID устройства.

---

### Стек технологий и требования

* **Язык:** [Kotlin](https://kotlinlang.org/)
* **Интерфейс:** [Jetpack Compose (Material 3)](https://developer.android.com/jetpack/compose/designsystems/material3)
* **Min SDK:** 24 ([Android 7.0 Nougat](https://developer.android.com/about/versions/nougat))
* **Target SDK:** 36 ([Android 15 / Baklava](https://developer.android.com/about/versions/15))
* **Асинхронность:** [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-overview.html) & [Channels](https://kotlinlang.org/docs/channels.html)
