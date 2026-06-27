module.exports = {
  apps : [{
    name: "ukulele",
    // Jar is the "script" so pm_exec_path lives inside the project; this lets
    // PM2 find ./package.json and show its version in `pm2 list`.
    script: "build\\libs\\ukulele.jar",
    interpreter: "java",        // pm2 spawns the JVM directly (no shell/.bat)
    // --enable-native-access=ALL-UNNAMED silences the Java 25 restricted-method
    // warning from lavaplayer's native loader (matches bootRun's jvmArgs).
    interpreter_args: "--enable-native-access=ALL-UNNAMED -jar", // -> java --enable-native-access=ALL-UNNAMED -jar build\libs\ukulele.jar
    cwd: "C:\\Users\\justi\\Projects\\ukulele",
    autorestart: true,          // pm2 owns the JVM PID, so crash-restart works
    watch: false
  }]
}
