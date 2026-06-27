module.exports = {
  apps : [{
    name: "ukulele",
    // Jar is the "script" so pm_exec_path lives inside the project; this lets
    // PM2 find ./package.json and show its version in `pm2 list`.
    script: "build\\libs\\ukulele.jar",
    interpreter: "java",        // pm2 spawns the JVM directly (no shell/.bat)
    interpreter_args: "-jar",   // -> java -jar build\libs\ukulele.jar
    cwd: "C:\\Users\\justi\\Projects\\ukulele",
    autorestart: true,          // pm2 owns the JVM PID, so crash-restart works
    watch: false
  }]
}
