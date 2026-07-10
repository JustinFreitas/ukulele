module.exports = {
  apps : [{
    name: "ukulele",
    // Jar is the "script" so pm_exec_path lives inside the project; this lets
    // PM2 find ./package.json and show its version in `pm2 list`.
    script: "build\\libs\\ukulele.jar",
    // Pin the JVM to the Azul 25 JDK. The lavaplayer fork ships Java 25 bytecode
    // (class file v69), so a bare "java" that resolves to an older JDK on PATH
    // fails at launch with UnsupportedClassVersionError. Absolute path removes
    // all PATH ordering ambiguity across restarts.
    interpreter: "C:/Users/justi/.jdks/azul-25.0.2/bin/java.exe",
    // --enable-native-access=ALL-UNNAMED silences the Java 25 restricted-method
    // warning from lavaplayer's native loader (matches bootRun's jvmArgs).
    interpreter_args: "--enable-native-access=ALL-UNNAMED -jar", // -> java --enable-native-access=ALL-UNNAMED -jar build\libs\ukulele.jar
    autorestart: true,          // pm2 owns the JVM PID, so crash-restart works
    watch: false
  }]
}
