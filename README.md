# Pocket FX

Pocket FX is a native Android currency calculator. It converts one amount into an ordered list of currencies, saves favorites and settings on the device, and charts rates from previously downloaded publications.

## Build and install

Use Android Studio Panda 3 Patch 1 or newer with Android SDK Platform 37.0, SDK Build-Tools 36.0.0, and JDK 17. The app targets Android 17 (API 37) and runs on Android 8.0 (API 26) or later.

Open this directory in Android Studio, install the required SDK packages from SDK Manager, and run the `app` configuration on a device or emulator. For a terminal build, set `JAVA_HOME` to your JDK and `ANDROID_HOME` to your Android SDK, or let Android Studio write the SDK location to `local.properties`.

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On Windows, use `gradlew.bat` in place of `./gradlew`. The Gradle wrapper pins Gradle 9.3.1 and verifies the distribution checksum.

## Using the converter

The first launch uses English, the system theme, and 100 EUR converted to USD, RSD, and RUB. Tap the source currency to change it. Use **Add currency** to choose targets and the menu on each result to reorder or remove it. Star a currency in the picker to add it to favorites. Long-press a converted amount to copy its displayed value.

Settings lets you change the language and theme, check data freshness, and request a refresh. Your choices and amount are restored when you reopen the app.

## Architecture

- Jetpack Compose and Material 3 render the Converter, History, and Settings destinations. The ViewModel exposes state using StateFlow.
- Retrofit and OkHttp fetch the provider’s USD-based dataset over HTTPS. The repository validates the payload and coordinates refreshes.
- Domain functions use BigDecimal for amounts, cross-rates, and history statistics. Editing the amount uses the rates already on the device.
- Room stores full rate snapshots as JSON with decimal values encoded as strings. Preferences DataStore stores the amount, currency selections and order, favorites, theme, and language.

## Exchange rate data

Pocket FX uses the [ExchangeRate-API Open Access endpoint](https://www.exchangerate-api.com/docs/free) at `https://open.er-api.com/v6/latest/USD`. It uses the provider’s USD-based data to compute cross-rates locally. [Rates By Exchange Rate API](https://www.exchangerate-api.com).

At launch and whenever the app resumes, it checks whether the last successful request was at least ten minutes ago or the cache is empty. A manual refresh bypasses the cache TTL. Only one request can run at a time. After HTTP 429, the app honors the provider’s retry window. A failed refresh leaves the existing rates and last successful check intact. Pocket FX makes no periodic background requests.

**Last checked** is when Pocket FX last received and saved a valid response. **Rate updated** is the timestamp of the provider’s latest publication. The provider generally publishes a new dataset once per day. After the first successful download, conversion and saved history work offline.

## History

Room keeps one snapshot per provider, base currency, and publication timestamp. Pocket FX prunes observations older than 365 days after successful checks and retains the latest dataset for offline conversion. The chart shows observed values within the selected 7-, 30-, or 90-day period. A pair needs two stored observations to draw a chart. History builds from downloads on the device. Each point corresponds to an actual publication with valid rates for both currencies.

## Tests

```sh
./gradlew testDebugUnitTest lintDebug
./gradlew connectedDebugAndroidTest
```

The JVM suite covers decimal calculations, input, historical statistics, TTL decisions, and HTTPS requests using MockWebServer with an injectable clock. Connected tests cover Room and DataStore persistence and key Compose interactions, including offline screens and chart point selection. Start an emulator or connect a device before running the connected tests. Tests use controlled data and run independently of the public rate endpoint.
# currency_converter
