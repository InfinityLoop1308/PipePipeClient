## README

The client of [PipePipe](https://codeberg.org/NullPointerException/PipePipe).

## Building and testing

You need:

- JDK 25
- the Android SDK with the `android-37` platform (set `sdk.dir` in `local.properties` or `ANDROID_HOME`)
- the [PipePipe](https://codeberg.org/NullPointerException/PipePipe) repository checked out with its submodules, because the build includes `../PipePipeExtractor`

```
git clone https://codeberg.org/NullPointerException/PipePipe.git
cd PipePipe
git submodule update --init PipePipeClient PipePipeExtractor
cd PipePipeClient
```

Then, from `PipePipeClient`:

```
./gradlew testDebugUnitTest                            # JVM unit tests (app/src/test)
./gradlew assembleDebug lintDebug -DskipFormatKtlint   # what CI runs; APKs land in app/build/outputs/apk/debug
```

The instrumented tests in `app/src/androidTest` need a connected device or emulator: `./gradlew connectedDebugAndroidTest`.
