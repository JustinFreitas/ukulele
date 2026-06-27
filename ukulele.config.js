module.exports = {
  apps : [{
    name: "ukulele",
    script: "java",
    args: ["-jar", "build\\libs\\ukulele.jar"],
    cwd: "C:\\Users\\justi\\Projects\\ukulele",
    interpreter: "none", // run java directly, not through a shell/.bat
    autorestart: true,    // pm2 owns the JVM PID, so crash-restart works
    watch: false
  }]
}
