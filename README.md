# Pocket FX

Pocket FX is a native Android currency calculator. It converts one amount into an ordered list of currencies, saves favorites and settings on the device, and charts historical reference rates from Frankfurter.

## Build and install

Use Android Studio Panda 3 Patch 1 or newer with Android SDK Platform 37.0, SDK Build-Tools 36.0.0, and JDK 17. The app targets Android 17 (API 37) and runs on Android 8.0 (API 26) or later.

Open this directory in Android Studio, install the required SDK packages from SDK Manager, and run the `app` configuration on a device or emulator. For a terminal build, set `JAVA_HOME` to your JDK and `ANDROID_HOME` to your Android SDK, or let Android Studio write the SDK location to `local.properties`.

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On Windows, use `gradlew.bat` in place of `./gradlew`. The Gradle wrapper pins Gradle 9.3.1 and verifies the distribution checksum.

## Using the converter

The first launch uses English, the system theme, and 100 EUR converted to USD, RSD, and RUB. Tap the X beside the amount to clear it. When you change the source currency, the previous source takes the new source’s place in the target list or is appended if the new source was outside that list. Use **Add currency** to choose targets. Hold a target row or its drag handle, then drag it up or down to reorder. Hold it near the top or bottom of the screen to scroll while dragging. **Move up**, **Move down**, and removal are also available in the row menu. Star a currency in the picker to add it to favorites. Long-press a converted amount to copy its displayed value.

Settings offers English, Russian, and Serbian Latin, along with the system, light, and dark themes. Settings also shows data freshness and lets you request a refresh. Your choices and amount are restored when you reopen the app.

## Architecture

- Jetpack Compose and Material 3 render the Converter, History, and Settings destinations. The ViewModel exposes state using StateFlow.
- Retrofit and OkHttp fetch the current USD-based dataset and historical series over HTTPS. Separate repositories validate and cache each provider’s responses.
- Domain functions use BigDecimal for amounts, cross-rates, and history statistics. Editing the amount uses the rates already on the device.
- Room stores the latest conversion dataset and Frankfurter history in separate tables, with decimal values encoded as strings. Preferences DataStore stores the amount, currency selections and order, favorites, theme, and language.

## Exchange rate data

Pocket FX uses the [ExchangeRate-API Open Access endpoint](https://www.exchangerate-api.com/docs/free) at `https://open.er-api.com/v6/latest/USD`. It uses the provider’s USD-based data to compute cross-rates locally. [Rates By Exchange Rate API](https://www.exchangerate-api.com).

At launch and whenever the app resumes, it checks whether the last successful request was at least ten minutes ago or the cache is empty. A manual refresh bypasses the cache TTL. Only one request can run at a time. After HTTP 429, the app honors the provider’s retry window. A failed refresh leaves the existing rates and last successful check intact. Pocket FX makes no periodic background requests.

**Last checked** is when Pocket FX last received and saved a valid response. **Rate updated** is the timestamp of the provider’s latest publication. The provider generally publishes a new dataset once per day. After the first successful download, conversion works offline.

## History

History uses Frankfurter v2. The scrollable period selector offers **7D**, **30D**, **90D**, **180D**, **1Y**, and **5Y**, with **30D** selected initially. Ranges end on the current UTC calendar date. The three short ranges include today. The 180D range starts 180 days earlier. The yearly ranges use calendar years and account for leap days. The currency picker includes Frankfurter’s supported three-letter currency codes along with the converter’s currencies. [Frankfurter API documentation](https://frankfurter.dev/)

- `GET https://api.frankfurter.dev/v2/rates?base=EUR&quotes=USD&from=2026-10-20&to=2026-11-18` fetches a time series for a pair.
- `GET https://api.frankfurter.dev/v2/currencies` fetches the currency catalog.

The swap button between the currency selectors reverses the History pair and keeps the selected period. When a cached series covers the pair and dates, Pocket FX uses BigDecimal reciprocals to show the reversed rates and recalculates the statistics. The Converter keeps its own selection.

The chart uses all published observations in the range, including any weekend observations. Missing dates have no points. Point selection and statistics use the full series. Two observations are required for the chart and percentage change.

Room caches successful responses by provider, pair, and exact date range for 24 hours. A saved range that fully covers a shorter request can serve it directly. History displays saved data immediately and revalidates expired entries when requested. The refresh action bypasses the TTL. Downloads remain available offline, including after a date rollover when a saved range of the same length exists for the pair. The screen identifies the saved dates when it shows an earlier range.

The v1-to-v2 database migration keeps the latest cached conversion dataset and its successful-check timestamps, and removes the older ExchangeRate-API snapshots. The migration preserves all DataStore preferences.

### Verified coverage

On November 18, 2026, Frankfurter returned observations for all 12 directions among EUR, USD, RSD, and RUB within the last 7, 30, and 90 days. It also returned EUR series for CMD, KPW, and SVC, which were outside the converter’s dataset. Full-resolution five-year requests returned 1,827 observations each for EUR/USD and EUR/RUB and 1,820 for EUR/RSD. The responses were about 115 KB each and fit in one request per pair. Coverage depends on the pair and dates.

For the 90-day EUR series ending November 18, Frankfurter rejected FOK, KID, and TVD as invalid currency codes. It recognized BGN, CLF, HRK, SLL, and ZWL and returned no observations for that window. Pocket FX displays localized messages for both cases.

## Tests

```sh
./gradlew testDebugUnitTest lintDebug
./gradlew connectedDebugAndroidTest
```

The JVM suite covers decimal calculations, input, source switching, historical statistics, and both providers’ HTTPS and cache behavior using MockWebServer and injectable clocks. Connected tests cover the Room migration, persisted history and preferences, Serbian Latin localization, drag reordering, long-press copying, source switching, History swaps, chart interactions, and offline states. Start an emulator or connect a device before running the connected tests. Tests use controlled data and run independently of the public rate endpoint.
# currency_converter
