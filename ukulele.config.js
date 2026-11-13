const path = require("path");
const os = require("os");

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
    interpreter: "C:/Program Files/Zulu/zulu-25/bin/java.exe",
    // --enable-native-access=ALL-UNNAMED silences the Java 25 restricted-method
    // warning from lavaplayer's native loader (matches bootRun's jvmArgs).
    // ZGC provides sub-millisecond GC pauses to eliminate Lavaplayer audio buffer underruns.
    interpreter_args: "--enable-native-access=ALL-UNNAMED -XX:+UseZGC -Xms64m -Xmx2g -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=256m -Djava.io.tmpdir=C:/Users/justi/AppData/Local/Temp -jar", // -> java --enable-native-access=ALL-UNNAMED -jar build\libs\ukulele.jar
    max_memory_restart: "2500M",
    autorestart: true,          // pm2 owns the JVM PID, so crash-restart works
    min_uptime: "10s",
    max_restarts: 10,
    exp_backoff_restart_delay: 200,
    watch: false,
    out_file: path.join(os.homedir(), ".pm2", "logs", "ukulele-out.log"),
    error_file: path.join(os.homedir(), ".pm2", "logs", "ukulele-error.log"),
    env: {
      TEMP: "C:\\Users\\justi\\AppData\\Local\\Temp",
      TMP: "C:\\Users\\justi\\AppData\\Local\\Temp"
    }
  }]
}
