const path = require("path");
const os = require("os");
const fs = require("fs");

/**
 * Parses `cacheLocalFilesInMemory` and `maxTotalCacheMb` from the `config:` block of ukulele.yml
 * without requiring external npm packages, stripping unquoted `#` comments and falling back to
 * BotProps.kt defaults (`true` / `512` MB) when ukulele.yml or keys are absent.
 */
function resolveDynamicMemorySizing(ymlPath) {
  let cacheEnabled = true;
  let maxTotalCacheMb = 512;

  if (fs.existsSync(ymlPath)) {
    try {
      const lines = fs.readFileSync(ymlPath, "utf8").split(/\r?\n/);
      let inConfigBlock = false;
      for (const rawLine of lines) {
        // Strip inline comments outside quotes
        let line = "";
        let inSingle = false;
        let inDouble = false;
        for (let i = 0; i < rawLine.length; i++) {
          const ch = rawLine[i];
          if (ch === "'" && !inDouble) inSingle = !inSingle;
          else if (ch === '"' && !inSingle) inDouble = !inDouble;
          else if (ch === "#" && !inSingle && !inDouble) break;
          line += ch;
        }
        if (!line.trim()) continue;

        const topLevelMatch = line.match(/^([A-Za-z0-9_-]+)\s*:/);
        if (topLevelMatch) {
          inConfigBlock = topLevelMatch[1] === "config";
          continue;
        }
        if (!inConfigBlock) continue;

        const propMatch = line.match(/^\s+([A-Za-z0-9_-]+)\s*:\s*(\S+)/);
        if (!propMatch) continue;
        const key = propMatch[1];
        const val = propMatch[2].replace(/^['"]|['"]$/g, "");
        if (key === "cacheLocalFilesInMemory") {
          cacheEnabled = val.toLowerCase() !== "false";
        } else if (key === "maxTotalCacheMb") {
          const parsed = parseInt(val, 10);
          if (!Number.isNaN(parsed) && parsed >= 0) {
            maxTotalCacheMb = parsed;
          }
        }
      }
    } catch (err) {
      console.error(`Warning: failed to parse ${ymlPath} for dynamic heap sizing:`, err.message);
    }
  }

  const totalSystemRamMb = Math.floor(os.totalmem() / (1024 * 1024));
  const hardwareMaxHeapMb = Math.max(1024, Math.floor(totalSystemRamMb * 0.65));
  const requestedCacheMb = cacheEnabled ? maxTotalCacheMb : 0;
  const heapMaxMb = Math.max(512, Math.min(requestedCacheMb + 2048, hardwareMaxHeapMb));
  const pm2RestartMb = heapMaxMb + 2048;

  return { heapMaxMb, pm2RestartMb };
}

const { heapMaxMb, pm2RestartMb } = resolveDynamicMemorySizing(path.join(__dirname, "ukulele.yml"));

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
    // ZGC provides sub-millisecond GC pauses to eliminate Lavaplayer audio buffer underruns,
    // and ZUncommitDelay=60 returns evicted cache pages back to Windows after 60s.
    interpreter_args: `--enable-native-access=ALL-UNNAMED -XX:+UseZGC -XX:ZUncommitDelay=60 -Xms64m -Xmx${heapMaxMb}m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=256m -Djava.io.tmpdir=C:/Users/justi/AppData/Local/Temp -jar`,
    max_memory_restart: `${pm2RestartMb}M`,
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
